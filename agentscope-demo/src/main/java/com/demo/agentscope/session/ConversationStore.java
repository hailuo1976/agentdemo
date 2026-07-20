package com.demo.agentscope.session;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.ContentBlockCodec;
import com.demo.agentscope.message.Msg;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 命名对话存取。
 * <p>
 * 与 {@link SessionLogger} 自动会话日志分离：{@code ConversationStore} 面向
 * 用户显式保存/加载的"命名对话"（{@code /context save <name>}），以 pretty
 * JSON 存放在 {@code workspace/conversations/<name>.json}，便于人工检视和版本管理。
 * </p>
 * <p>
 * 顶层 JSON 数组，元素是 {@link MsgDto}；{@code content} 复用
 * {@link SessionEntry.BlockDto}（已由 {@link ContentBlockCodec} 验证完整覆盖
 * 6 种块类型）。{@code /context undo} 使用的单槽快照存放在
 * {@code workspace/conversations/.last_state.json}（隐藏文件，覆盖写）。
 * </p>
 */
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    /** 存放目录名（相对 workspaceDir） */
    public static final String DIR_NAME = "conversations";

    /** undo 单槽快照文件名（隐藏文件） */
    public static final String UNDO_FILENAME = ".last_state.json";

    /** 名称白名单：只允许字母、数字、下划线、连字符 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

    private final Path conversationsDir;
    private final ObjectMapper objectMapper;

    public ConversationStore(Path workspaceDir) {
        if (workspaceDir == null) {
            throw new IllegalArgumentException("workspaceDir 不能为 null");
        }
        this.conversationsDir = workspaceDir.resolve(DIR_NAME);
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ==================== 命名对话 ====================

    /**
     * 保存命名对话。
     *
     * @param name     对话名（仅 {@code [a-zA-Z0-9_-]+}）
     * @param messages 消息列表
     * @param force    是否覆盖已存在；false 时已存在则抛 {@link IllegalStateException}
     * @return 写入的文件路径
     */
    public Path save(String name, List<Msg> messages, boolean force) {
        validateName(name);
        ensureDir();
        Path file = conversationsDir.resolve(name + ".json");
        if (!force && Files.exists(file)) {
            throw new IllegalStateException("对话已存在: " + name + "（使用 --force 覆盖）");
        }
        List<MsgDto> dtos = toDtos(messages);
        try {
            objectMapper.writeValue(file.toFile(), dtos);
            log.info("对话 [{}] 已保存（{} 条消息）→ {}", name, dtos.size(), file);
            return file;
        } catch (IOException e) {
            throw new RuntimeException("保存对话失败: " + name, e);
        }
    }

    /**
     * 加载命名对话。
     *
     * @param name 对话名
     * @return 重建的消息列表（每条消息带新 UUID，与 {@link SessionRecovery#load} 行为一致）
     * @throws IllegalArgumentException 文件不存在
     */
    public List<Msg> load(String name) {
        validateName(name);
        Path file = conversationsDir.resolve(name + ".json");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("对话不存在: " + name);
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            MsgDto[] dtos = objectMapper.readValue(json, MsgDto[].class);
            List<Msg> messages = new ArrayList<>(dtos.length);
            for (MsgDto dto : dtos) {
                messages.add(fromDto(dto));
            }
            log.info("对话 [{}] 已加载（{} 条消息）← {}", name, messages.size(), file);
            return messages;
        } catch (IOException e) {
            throw new RuntimeException("加载对话失败: " + name, e);
        }
    }

    /**
     * 列出所有命名对话（去掉 .json 后缀）。
     */
    public List<String> list() {
        if (!Files.exists(conversationsDir)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(conversationsDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        names.add(fileName.substring(0, fileName.length() - ".json".length()));
                    });
        } catch (IOException e) {
            log.error("列出对话目录失败: {}", conversationsDir, e);
            return Collections.emptyList();
        }
        Collections.sort(names);
        return names;
    }

    // ==================== Undo 快照 ====================

    /**
     * 写 undo 单槽快照（覆盖写）。
     */
    public void snapshot(List<Msg> messages) {
        ensureDir();
        Path file = conversationsDir.resolve(UNDO_FILENAME);
        List<MsgDto> dtos = toDtos(messages);
        try {
            objectMapper.writeValue(file.toFile(), dtos);
            log.debug("undo 快照已写入（{} 条消息）→ {}", dtos.size(), file);
        } catch (IOException e) {
            log.warn("写入 undo 快照失败: {}", e.getMessage());
            throw new RuntimeException("写入 undo 快照失败", e);
        }
    }

    /**
     * 读 undo 快照。
     *
     * @return 快照中的消息列表；无快照返回 null
     */
    public List<Msg> loadSnapshot() {
        Path file = conversationsDir.resolve(UNDO_FILENAME);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            MsgDto[] dtos = objectMapper.readValue(json, MsgDto[].class);
            List<Msg> messages = new ArrayList<>(dtos.length);
            for (MsgDto dto : dtos) {
                messages.add(fromDto(dto));
            }
            return messages;
        } catch (IOException e) {
            log.warn("读取 undo 快照失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean snapshotExists() {
        return Files.exists(conversationsDir.resolve(UNDO_FILENAME));
    }

    // ==================== 序列化 DTO ====================

    /**
     * 单条消息的序列化结构（复用 {@link SessionEntry.BlockDto} 作 content）。
     *
     * @param role      消息角色
     * @param timestamp 时间戳（ISO-8601 字符串；可能为 null）
     * @param content   内容块 DTO 列表
     * @param usage     Token 用量 DTO；可能为 null
     * @param metadata  元数据；可能为 null
     */
    public record MsgDto(
            String role,
            String timestamp,
            List<SessionEntry.BlockDto> content,
            SessionEntry.TokenUsageDto usage,
            Map<String, Object> metadata
    ) {}

    // ==================== 内部 ====================

    private void ensureDir() {
        try {
            Files.createDirectories(conversationsDir);
        } catch (IOException e) {
            throw new RuntimeException("创建对话目录失败: " + conversationsDir, e);
        }
    }

    private void validateName(String name) {
        if (name == null || name.isEmpty() || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "非法对话名: " + name + "（仅允许字母、数字、下划线、连字符）");
        }
    }

    private List<MsgDto> toDtos(List<Msg> messages) {
        List<MsgDto> dtos = new ArrayList<>();
        if (messages == null) return dtos;
        for (Msg m : messages) {
            dtos.add(toDto(m));
        }
        return dtos;
    }

    private MsgDto toDto(Msg m) {
        SessionEntry.TokenUsageDto usageDto = null;
        if (m.getUsage() != null) {
            usageDto = new SessionEntry.TokenUsageDto(
                    m.getUsage().getPromptTokens(),
                    m.getUsage().getCompletionTokens());
        }
        String ts = m.getTimestamp() != null ? m.getTimestamp().toString() : null;
        Map<String, Object> metadata = m.getMetadata() != null && !m.getMetadata().isEmpty()
                ? m.getMetadata() : null;
        return new MsgDto(
                m.getRole(),
                ts,
                ContentBlockCodec.toBlockDtos(m.getContent()),
                usageDto,
                metadata);
    }

    private Msg fromDto(MsgDto dto) {
        List<ContentBlock> blocks = ContentBlockCodec.fromBlockDtos(dto.content());
        Msg.TokenUsage usage = null;
        if (dto.usage() != null) {
            usage = new Msg.TokenUsage(dto.usage().promptTokens(), dto.usage().completionTokens());
        }
        // 加载时生成新 UUID，与 SessionRecovery.load 行为一致
        return new Msg(UUID.randomUUID().toString(), dto.role(), blocks, usage, null, dto.metadata());
    }
}
