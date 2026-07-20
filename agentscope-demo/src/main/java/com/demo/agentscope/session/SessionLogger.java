package com.demo.agentscope.session;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.ContentBlockCodec;
import com.demo.agentscope.message.Msg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话日志写入器：以 JSONL（每行一条 JSON）格式追加写入会话文件。
 * <p>
 * 参考 Claude Code 的 transcript 机制，每条条目通过 {@link SessionEntry#parentUuid()}
 * 指向前一条，形成线性 DAG 链。日志覆盖完整的交互生命周期：
 * 用户输入 → 模型调用 → 工具调用 → 工具结果 → 回复结束。
 * </p>
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 *   <li><b>追加写入 + 每条 flush</b>：崩溃安全优先于性能。</li>
 *   <li><b>永不抛异常</b>：所有 IO 错误 swallowed 到 SLF4J，避免污染 Agent 主循环。</li>
 *   <li><b>跨 reply 存活</b>：SessionLogger 实例由 SessionLoggingMiddleware 持有，
 *       独立于每次 reply() 重建的 AgentContext。</li>
 * </ul>
 */
public class SessionLogger {

    private static final Logger log = LoggerFactory.getLogger(SessionLogger.class);

    /** JSONL 文件扩展名 */
    private static final String FILE_SUFFIX = ".jsonl";

    /** 会话日志根目录（如 {@code workspace/sessions}） — 仅用于 {@link #getSessionFile()} 调试 */
    private final Path sessionsRoot;

    /** 会话唯一标识 */
    private final String sessionId;

    /** JSONL 文件完整路径 */
    private final Path sessionFile;

    /** Jackson 序列化器（线程安全，复用） */
    private final ObjectMapper objectMapper;

    /** 当前 parent UUID，指向最后一条写入的条目；用于维持 DAG 链 */
    private String currentParentUuid;

    /** 写入器；构造时打开，{@link #close()} 时释放 */
    private BufferedWriter writer;

    /**
     * 新建会话日志（创建新文件）。
     *
     * @param baseDir   日志根目录（如 {@code workspace/sessions}）
     * @param sessionId 会话唯一标识
     */
    public SessionLogger(Path baseDir, String sessionId) {
        this(baseDir, sessionId, null);
    }

    /**
     * 续写会话日志（{@code /resume} 场景：在已存在文件末尾追加）。
     *
     * @param baseDir           日志根目录
     * @param sessionId         会话唯一标识
     * @param initialParentUuid 首条新条目的 parent UUID（通常是恢复前最后一条的 uuid）
     */
    public SessionLogger(Path baseDir, String sessionId, String initialParentUuid) {
        this.sessionsRoot = Objects.requireNonNull(baseDir, "baseDir 不能为 null").resolve("sessions");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为 null");
        this.currentParentUuid = initialParentUuid;
        this.objectMapper = new ObjectMapper();

        this.sessionFile = sessionsRoot.resolve(sessionId + FILE_SUFFIX);

        openWriter();
    }

    private void openWriter() {
        try {
            Files.createDirectories(sessionFile.getParent());
            // 若文件已存在（续写）则追加；否则新建
            StandardOpenOption[] opts = Files.exists(sessionFile)
                    ? new StandardOpenOption[]{StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
            writer = Files.newBufferedWriter(sessionFile, StandardCharsets.UTF_8, opts);
            log.info("会话日志已就绪: {} (sessionId={}, append={})",
                    sessionFile, sessionId, currentParentUuid != null);
        } catch (IOException e) {
            log.error("打开会话日志文件失败，会话日志功能将不可用: {}", sessionFile, e);
            writer = null;
        }
    }

    /**
     * @return 会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * @return 当前 parent UUID（最后一条已写入条目的 uuid）
     */
    public String getCurrentParentUuid() {
        return currentParentUuid;
    }

    /**
     * @return 会话 JSONL 文件完整路径
     */
    public Path getSessionFile() {
        return sessionFile;
    }

    // ==================== 日志写入方法 ====================

    /**
     * 记录回复开始（用户输入）。
     *
     * @param sessionId 会话 ID（与构造参数一致，这里冗余传入以便日后支持多 agent 共享日志）
     * @param userInput 用户输入的原始文本
     */
    public void logReplyStart(String sessionId, String userInput) {
        List<SessionEntry.BlockDto> blocks = new ArrayList<>();
        blocks.add(new SessionEntry.BlockDto(
                ContentBlock.TextBlock.TYPE, userInput,
                null, null, null, null, null, null, null));
        append(new SessionEntry(
                newUuid(), currentParentUuid, SessionEntry.EntryType.REPLY_START,
                now(), this.sessionId, "user", blocks, null, null, null));
    }

    /**
     * 记录模型调用请求（时间标记）。
     *
     * @param request 中间件传入的请求 Msg（Agent 当前传占位符 {@code "[模型调用请求]"}）
     */
    public void logModelCall(Msg request) {
        if (request == null) {
            return;
        }
        append(new SessionEntry(
                newUuid(), currentParentUuid, SessionEntry.EntryType.LLM_REQUEST,
                now(), sessionId, request.getRole(),
                toBlockDtos(request.getContent()), null, null, null));
    }

    /**
     * 记录模型调用响应。
     *
     * @param response 模型返回的 assistant Msg
     */
    public void logModelCallEnd(Msg response) {
        if (response == null) {
            return;
        }
        SessionEntry.TokenUsageDto usageDto = null;
        if (response.getUsage() != null) {
            usageDto = new SessionEntry.TokenUsageDto(
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens());
        }
        append(new SessionEntry(
                newUuid(), currentParentUuid, SessionEntry.EntryType.LLM_RESPONSE,
                now(), sessionId, response.getRole(),
                toBlockDtos(response.getContent()), usageDto, null, null));
    }

    /**
     * 记录单次工具调用请求。
     *
     * @param toolCall 工具调用块
     */
    public void logToolCall(ContentBlock.ToolCallBlock toolCall) {
        if (toolCall == null) {
            return;
        }
        List<SessionEntry.BlockDto> blocks = new ArrayList<>();
        blocks.add(new SessionEntry.BlockDto(
                ContentBlock.ToolCallBlock.TYPE, null,
                toolCall.getId(), toolCall.getName(), toolCall.getArguments(),
                null, null, null, null));
        append(new SessionEntry(
                newUuid(), currentParentUuid, SessionEntry.EntryType.TOOL_CALL,
                now(), sessionId, "assistant", blocks, null, null, null));
    }

    /**
     * 记录单次工具执行结果。
     *
     * @param result 工具结果块
     */
    public void logToolResult(ContentBlock.ToolResultBlock result) {
        if (result == null) {
            return;
        }
        List<SessionEntry.BlockDto> blocks = new ArrayList<>();
        blocks.add(new SessionEntry.BlockDto(
                ContentBlock.ToolResultBlock.TYPE, null,
                result.getToolCallId(), null, null,
                result.getContent(), result.isError(), null, null));
        append(new SessionEntry(
                newUuid(), currentParentUuid, SessionEntry.EntryType.TOOL_RESULT,
                now(), sessionId, "tool", blocks, null, null, null));
    }

    /**
     * 记录回复结束（附 agentState 快照）。
     *
     * @param agentState 智能体状态 Map（允许为 null）
     */
    public void logReplyEnd(Map<String, Object> agentState) {
        append(new SessionEntry(
                newUuid(), currentParentUuid, SessionEntry.EntryType.REPLY_END,
                now(), sessionId, "assistant", null, null, agentState, null));
    }

    /**
     * 记录回复过程中的错误。
     *
     * @param message 错误信息
     */
    public void logError(String message) {
        append(new SessionEntry(
                newUuid(), currentParentUuid, SessionEntry.EntryType.ERROR,
                now(), sessionId, "assistant", null, null, null, message));
    }

    /**
     * 关闭日志写入器，释放文件句柄。
     */
    public void close() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                log.warn("关闭会话日志写入器失败: {}", sessionFile, e);
            }
            writer = null;
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 追加一条 entry 到文件，更新 parentUuid 链。
     * <p>
     * 任何 IO 异常都 swallowed 到 SLF4J，不向调用方抛出。
     * </p>
     */
    private void append(SessionEntry entry) {
        if (writer == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(entry);
            writer.write(json);
            writer.newLine();
            writer.flush();
            currentParentUuid = entry.uuid();
        } catch (Exception e) {
            log.error("写入会话日志失败 (sessionId={}, type={})",
                    sessionId, entry.type(), e);
        }
    }

    /**
     * 把 {@link ContentBlock} 列表转换为可序列化的 DTO 列表。
     * 委托至 {@link ContentBlockCodec#toBlockDtos(List)} 统一实现。
     */
    private List<SessionEntry.BlockDto> toBlockDtos(List<ContentBlock> blocks) {
        return ContentBlockCodec.toBlockDtos(blocks);
    }

    private static String newUuid() {
        return UUID.randomUUID().toString();
    }

    private static String now() {
        return Instant.now().toString();
    }
}
