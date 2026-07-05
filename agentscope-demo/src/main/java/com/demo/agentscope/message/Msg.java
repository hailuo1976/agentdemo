package com.demo.agentscope.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * AgentScope 2.0 消息模型。
 * <p>
 * 消息由角色（role）和一组内容块（ContentBlock）组成，
 * 支持文本、工具调用、思考过程等多种内容类型共存于同一条消息中。
 * 每条消息还附带 token 用量统计和元数据信息。
 * </p>
 */
public class Msg {

    private static final Logger log = LoggerFactory.getLogger(Msg.class);

    /** 消息唯一标识 */
    private final String id;

    /** 消息角色：system / user / assistant / tool */
    private final String role;

    /** 消息内容块列表 */
    private final List<ContentBlock> content;

    /** Token 用量统计 */
    private final TokenUsage usage;

    /** 消息时间戳 */
    private final Instant timestamp;

    /** 元数据 */
    private final Map<String, Object> metadata;

    /**
     * 构造消息。
     *
     * @param id        消息ID
     * @param role      消息角色
     * @param content   内容块列表
     * @param usage     Token用量
     * @param timestamp 时间戳
     * @param metadata  元数据
     */
    public Msg(String id, String role, List<ContentBlock> content,
               TokenUsage usage, Instant timestamp, Map<String, Object> metadata) {
        this.id = Objects.requireNonNull(id, "消息ID不能为null");
        this.role = Objects.requireNonNull(role, "消息角色不能为null");
        this.content = content != null ? new ArrayList<>(content) : new ArrayList<>();
        this.usage = usage != null ? usage : new TokenUsage(0, 0);
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    /**
     * 构造消息（无Token用量和元数据）。
     *
     * @param id      消息ID
     * @param role    消息角色
     * @param content 内容块列表
     */
    public Msg(String id, String role, List<ContentBlock> content) {
        this(id, role, content, null, null, null);
    }

    // ==================== Getter ====================

    public String getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    public List<ContentBlock> getContent() {
        return Collections.unmodifiableList(content);
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    // ==================== 辅助方法 ====================

    /**
     * 添加内容块到消息中。
     *
     * @param block 内容块
     * @return 当前消息对象（支持链式调用）
     */
    public Msg addContentBlock(ContentBlock block) {
        if (block != null) {
            content.add(block);
            log.debug("添加内容块: type={}, 消息id={}", block.getType(), id);
        }
        return this;
    }

    /**
     * 就地替换指定位置的内容块。
     * <p>
     * 主要用于上下文压缩:保留 Msg 的 id/role/结构,只替换内部某个 block
     * (例如把 ToolCallBlock.arguments 换成 stub,保留 tool_use.id 不变)。
     * </p>
     *
     * @param index   要替换的位置(0-based)
     * @param newBlock 新内容块
     * @return 当前消息对象
     * @throws IndexOutOfBoundsException index 越界
     */
    public Msg replaceBlock(int index, ContentBlock newBlock) {
        content.set(index, Objects.requireNonNull(newBlock, "新内容块不能为null"));
        return this;
    }

    /**
     * 获取消息中所有文本内容块的文本拼接结果。
     *
     * @return 拼接后的文本内容，若无文本块则返回空字符串
     */
    public String getTextContent() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.TextBlock textBlock) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(textBlock.getText());
            }
        }
        return sb.toString();
    }

    /**
     * 获取消息中所有工具调用内容块。
     *
     * @return 工具调用块列表
     */
    public List<ContentBlock.ToolCallBlock> getToolCalls() {
        List<ContentBlock.ToolCallBlock> toolCalls = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.ToolCallBlock toolCallBlock) {
                toolCalls.add(toolCallBlock);
            }
        }
        return toolCalls;
    }

    /**
     * 粗略估算本消息占用 token 数。
     * <p>
     * 遍历所有 ContentBlock 类型(TextBlock/ToolCallBlock/ToolResultBlock),
     * 累计字符数后除以 3(英文约 1 token ≈ 4 字符,中文约 1 token ≈ 2 字符,取折中)。
     * 与 {@link #getTextContent()} 不同,本方法会把工具调用的 arguments
     * 和工具结果的 content 一并计入 —— 用于触发上下文压缩阈值。
     * </p>
     *
     * @return 估算的 token 数,至少为 1(空消息也占位)
     */
    public int getEstimatedTokens() {
        int chars = 0;
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.TextBlock t) {
                chars += t.getText() != null ? t.getText().length() : 0;
            } else if (block instanceof ContentBlock.ToolCallBlock tc) {
                chars += tc.getName() != null ? tc.getName().length() : 0;
                String args = tc.getArguments();
                if (args != null) chars += args.length();
            } else if (block instanceof ContentBlock.ToolResultBlock tr) {
                String c = tr.getContent();
                if (c != null) chars += c.length();
            }
        }
        return Math.max(1, chars / 3);
    }

    /**
     * 估算一组消息的总 token 数(供上下文压缩阈值判断使用)。
     *
     * @param messages 消息列表(可为 null/empty)
     * @return 总 token 估算值
     */
    public static int sumEstimatedTokens(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Msg msg : messages) {
            total += msg.getEstimatedTokens();
        }
        return total;
    }

    /**
     * 判断消息中是否包含工具调用。
     *
     * @return 是否包含工具调用
     */
    public boolean hasToolCalls() {
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.ToolCallBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * 向元数据中设置键值对。
     *
     * @param key   键
     * @param value 值
     */
    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    /**
     * 从元数据中获取指定键的值。
     *
     * @param key 键
     * @return 对应的值，不存在则返回null
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    @Override
    public String toString() {
        return "Msg{id='" + id + "', role='" + role +
                "', contentBlocks=" + content.size() +
                ", hasToolCalls=" + hasToolCalls() + "}";
    }

    // ==================== TokenUsage 内部类 ====================

    /**
     * Token 用量统计。
     * <p>记录模型调用消耗的输入和输出 token 数量。</p>
     */
    public static class TokenUsage {

        /** 输入 token 数量 */
        private final int promptTokens;

        /** 输出 token 数量 */
        private final int completionTokens;

        public TokenUsage(int promptTokens, int completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        /**
         * 获取总 token 数量。
         *
         * @return 输入与输出 token 之和
         */
        public int getTotalTokens() {
            return promptTokens + completionTokens;
        }

        @Override
        public String toString() {
            return "TokenUsage{prompt=" + promptTokens + ", completion=" + completionTokens +
                    ", total=" + getTotalTokens() + "}";
        }
    }
}
