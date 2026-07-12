package com.demo.agentscope.session;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 会话日志读取器：从 JSONL 重建会话上下文。
 * <p>
 * 支持两种访问粒度：
 * </p>
 * <ul>
 *   <li>{@link #listSessions()} —— 轻量元数据扫描（仅读首/尾行），用于 {@code /sessions} 列表。</li>
 *   <li>{@link #load(String)} —— 全量加载，重建 {@code List&lt;Msg&gt;}，用于 {@code /resume}。</li>
 * </ul>
 * <p>
 * 参考 Claude Code 的 conversationRecovery：按条目顺序遍历，跳过损坏行（部分恢复优于全失败），
 * 通过最后一条 entry 的类型判定是否中断。
 * </p>
 */
public class SessionRecovery {

    private static final Logger log = LoggerFactory.getLogger(SessionRecovery.class);
    private static final String FILE_SUFFIX = ".jsonl";

    private final Path sessionsDir;
    private final ObjectMapper objectMapper;

    /**
     * @param baseDir 日志根目录（{@code SessionLogger} 构造时传入的同一路径，
     *                实际读取的是 {@code baseDir/sessions/}）
     */
    public SessionRecovery(Path baseDir) {
        this.sessionsDir = Objects.requireNonNull(baseDir, "baseDir 不能为 null").resolve("sessions");
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 公开 API ====================

    /**
     * 列出所有已保存的会话（按 lastActivity 倒序）。
     *
     * @return 会话摘要列表；无文件时返回空列表
     */
    public List<SessionSummary> listSessions() {
        if (!Files.exists(sessionsDir)) {
            return Collections.emptyList();
        }
        List<SessionSummary> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            stream.filter(p -> p.toString().endsWith(FILE_SUFFIX))
                    .forEach(p -> {
                        try {
                            result.add(summarize(p));
                        } catch (Exception e) {
                            log.warn("读取会话摘要失败: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.error("列出会话目录失败: {}", sessionsDir, e);
            return Collections.emptyList();
        }
        result.sort(Comparator.comparing(
                (SessionSummary s) -> s.lastActivity() == null ? "" : s.lastActivity())
                .reversed());
        return result;
    }

    /**
     * 全量加载会话，重建消息列表。
     * <p>
     * 条目类型 → Msg 映射规则：
     * </p>
     * <ul>
     *   <li>{@code REPLY_START} → user Msg（用户输入）</li>
     *   <li>{@code LLM_RESPONSE} → assistant Msg（含所有 ContentBlock + TokenUsage）</li>
     *   <li>{@code TOOL_RESULT} → tool Msg</li>
     *   <li>{@code LLM_REQUEST} / {@code TOOL_CALL} → 跳过（前者是时间标记，后者已包含在 LLM_RESPONSE）</li>
     *   <li>{@code REPLY_END} → 提取 agentState，不建 Msg</li>
     *   <li>{@code ERROR} → 转 assistant Msg 文本</li>
     * </ul>
     *
     * @param sessionId 会话 ID（支持完整 UUID 或前缀；本方法仅接受完整 ID，前缀匹配由 REPL 负责）
     * @return 恢复的会话对象
     * @throws IllegalArgumentException 文件不存在
     */
    public RecoveredSession load(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId 不能为 null");
        Path file = sessionsDir.resolve(sessionId + FILE_SUFFIX);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("会话文件不存在: " + file);
        }

        List<SessionEntry> entries = readAllEntries(file);
        if (entries.isEmpty()) {
            return new RecoveredSession(sessionId, new ArrayList<>(), null, 0,
                    sessionsDir.relativize(file).toString(), null, false, null);
        }

        List<Msg> messages = new ArrayList<>();
        Map<String, Object> lastAgentState = null;
        String firstTimestamp = null;
        String lastTimestamp = null;

        for (SessionEntry e : entries) {
            if (firstTimestamp == null) firstTimestamp = e.timestamp();
            lastTimestamp = e.timestamp();

            switch (e.type()) {
                case REPLY_START -> {
                    String text = extractText(e.content());
                    messages.add(new Msg(UUID.randomUUID().toString(), "user",
                            List.of(new ContentBlock.TextBlock(text))));
                }
                case LLM_RESPONSE -> messages.add(rebuildAssistantMsg(e));
                case TOOL_RESULT -> messages.add(rebuildToolMsg(e));
                case ERROR -> {
                    String msg = e.errorMsg() != null ? e.errorMsg() : "unknown error";
                    messages.add(Msg.assistantText("[会话错误] " + msg));
                }
                case REPLY_END -> lastAgentState = e.agentState();
                case LLM_REQUEST, TOOL_CALL -> { /* 跳过 */ }
            }
        }

        boolean incomplete = !entries.isEmpty()
                && entries.get(entries.size() - 1).type() != SessionEntry.EntryType.REPLY_END
                && entries.get(entries.size() - 1).type() != SessionEntry.EntryType.ERROR;
        String lastUuid = entries.get(entries.size() - 1).uuid();

        return new RecoveredSession(sessionId, messages, lastAgentState,
                entries.size(), firstTimestamp, lastTimestamp, incomplete, lastUuid);
    }

    // ==================== 内部读取 ====================

    private List<SessionEntry> readAllEntries(Path file) {
        List<SessionEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    entries.add(objectMapper.readValue(line, SessionEntry.class));
                } catch (Exception e) {
                    log.warn("跳过损坏的 JSONL 行 (file={}) - {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("读取会话文件失败: {}", file, e);
        }
        return entries;
    }

    private SessionSummary summarize(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String id = stripSuffix(file.getFileName().toString());
        if (lines.isEmpty() || lines.stream().allMatch(String::isBlank)) {
            return new SessionSummary(id, null, null, 0, 0, "");
        }

        SessionEntry first = null;
        SessionEntry last = null;
        int totalEntries = 0;
        int userMsgCount = 0;
        String preview = "";

        for (String line : lines) {
            if (line.isBlank()) continue;
            totalEntries++;
            try {
                SessionEntry e = objectMapper.readValue(line, SessionEntry.class);
                if (first == null) first = e;
                if (e.type() == SessionEntry.EntryType.REPLY_START) {
                    userMsgCount++;
                    if (preview.isEmpty()) {
                        preview = truncate(extractText(e.content()), 80);
                    }
                }
                last = e;
            } catch (Exception ignored) {}
        }

        return new SessionSummary(id,
                first != null ? first.timestamp() : null,
                last != null ? last.timestamp() : null,
                totalEntries, userMsgCount, preview);
    }

    private Msg rebuildAssistantMsg(SessionEntry e) {
        List<ContentBlock> blocks = new ArrayList<>();
        Msg.TokenUsage usage = null;
        if (e.usage() != null) {
            usage = new Msg.TokenUsage(e.usage().promptTokens(), e.usage().completionTokens());
        }
        if (e.content() != null) {
            for (SessionEntry.BlockDto b : e.content()) {
                ContentBlock block = fromBlockDto(b);
                if (block != null) blocks.add(block);
            }
        }
        return new Msg(UUID.randomUUID().toString(),
                e.role() != null ? e.role() : "assistant",
                blocks, usage, null, null);
    }

    private Msg rebuildToolMsg(SessionEntry e) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (e.content() != null) {
            for (SessionEntry.BlockDto b : e.content()) {
                ContentBlock block = fromBlockDto(b);
                if (block != null) blocks.add(block);
            }
        }
        return new Msg(UUID.randomUUID().toString(), "tool", blocks);
    }

    /**
     * BlockDto → ContentBlock 的反序列化，按 type 字段判别。
     */
    private ContentBlock fromBlockDto(SessionEntry.BlockDto b) {
        if (b == null || b.type() == null) return null;
        return switch (b.type()) {
            case ContentBlock.TextBlock.TYPE -> new ContentBlock.TextBlock(b.text() != null ? b.text() : "");
            case ContentBlock.ToolCallBlock.TYPE -> new ContentBlock.ToolCallBlock(
                    b.toolCallId() != null ? b.toolCallId() : UUID.randomUUID().toString(),
                    b.toolName() != null ? b.toolName() : "unknown",
                    b.arguments() != null ? b.arguments() : "{}");
            case ContentBlock.ToolResultBlock.TYPE -> new ContentBlock.ToolResultBlock(
                    b.toolCallId() != null ? b.toolCallId() : UUID.randomUUID().toString(),
                    b.content() != null ? b.content() : "",
                    b.isError() != null && b.isError());
            case ContentBlock.ThinkingBlock.TYPE -> new ContentBlock.ThinkingBlock(b.text() != null ? b.text() : "");
            case ContentBlock.HintBlock.TYPE -> new ContentBlock.HintBlock(b.text() != null ? b.text() : "");
            case ContentBlock.DataBlock.TYPE -> {
                String mt = b.mimeType() != null ? b.mimeType() : "application/octet-stream";
                byte[] data = b.dataBase64() != null
                        ? Base64.getDecoder().decode(b.dataBase64()) : new byte[0];
                yield new ContentBlock.DataBlock(mt, data);
            }
            default -> {
                log.warn("未识别的 BlockDto type: {}", b.type());
                yield null;
            }
        };
    }

    private static String extractText(List<SessionEntry.BlockDto> content) {
        if (content == null || content.isEmpty()) return "";
        for (SessionEntry.BlockDto b : content) {
            if (ContentBlock.TextBlock.TYPE.equals(b.type()) && b.text() != null) {
                return b.text();
            }
        }
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String stripSuffix(String fileName) {
        if (fileName.endsWith(FILE_SUFFIX)) {
            return fileName.substring(0, fileName.length() - FILE_SUFFIX.length());
        }
        return fileName;
    }

    // ==================== DTO records ====================

    /**
     * 会话摘要（轻量扫描结果）。
     *
     * @param sessionId     会话 ID（去掉 .jsonl 后缀）
     * @param firstTimestamp 首条 entry 的时间戳
     * @param lastTimestamp  尾条 entry 的时间戳
     * @param entryCount     总 entry 行数
     * @param userMessageCount 用户消息数（REPLY_START 条数）
     * @param preview        首条用户输入文本（截断 80 字）
     */
    public record SessionSummary(
            String sessionId,
            String firstTimestamp,
            String lastTimestamp,
            int entryCount,
            int userMessageCount,
            String preview
    ) {
        /** 向后兼容：lastActivity 别名 */
        public String lastActivity() { return lastTimestamp; }
        /** 向后兼容：messageCount 别名 */
        public int messageCount() { return userMessageCount; }
    }

    /**
     * 恢复的会话对象（全量加载结果）。
     *
     * @param sessionId        会话 ID
     * @param messages         重建的消息列表（可直接传给 {@link com.demo.agentscope.agent.Agent#restoreContext(List)}）
     * @param agentState       最后一次 REPLY_END 的状态快照（可能为 null）
     * @param totalEntries     总 entry 行数
     * @param firstTimestamp   会话起始时间
     * @param lastTimestamp    会话最后活动时间
     * @param hasIncompleteTurn 最后是否中断（未以 REPLY_END/ERROR 结尾）
     * @param lastEntryUuid    最后一条 entry 的 uuid（用于 /resume 续写日志链）
     */
    public record RecoveredSession(
            String sessionId,
            List<Msg> messages,
            Map<String, Object> agentState,
            int totalEntries,
            String firstTimestamp,
            String lastTimestamp,
            boolean hasIncompleteTurn,
            String lastEntryUuid
    ) {
        /** 最后活动时间的别名 */
        public String lastActivity() { return lastTimestamp; }
    }
}
