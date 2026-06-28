package com.demo.agentscope.message;

import java.util.Arrays;
import java.util.Objects;

/**
 * 内容块抽象基类。
 * <p>
 * AgentScope 2.0 中消息内容由多个内容块（ContentBlock）组成，
 * 每个内容块代表一种特定类型的内容，如文本、工具调用、思考过程等。
 * 采用静态内部类方式定义各子类型，便于扩展与序列化。
 * </p>
 */
public abstract class ContentBlock {

    /** 内容块类型标识 */
    private final String type;

    protected ContentBlock(String type) {
        this.type = Objects.requireNonNull(type, "内容块类型不能为null");
    }

    /**
     * 获取内容块类型标识。
     *
     * @return 类型字符串
     */
    public String getType() {
        return type;
    }

    @Override
    public String toString() {
        return "ContentBlock{type='" + type + "'}";
    }

    // ==================== 静态内部子类 ====================

    /**
     * 文本内容块。
     * <p>承载普通文本内容，是最常用的内容块类型。</p>
     */
    public static class TextBlock extends ContentBlock {

        /** 类型标识 */
        public static final String TYPE = "text";

        private final String text;

        public TextBlock(String text) {
            super(TYPE);
            this.text = text != null ? text : "";
        }

        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return "TextBlock{text='" + text + "'}";
        }
    }

    /**
     * 数据内容块。
     * <p>承载二进制数据，如图片、文件等，通过 MIME 类型标识数据格式。</p>
     */
    public static class DataBlock extends ContentBlock {

        /** 类型标识 */
        public static final String TYPE = "data";

        private final String mimeType;
        private final byte[] data;

        public DataBlock(String mimeType, byte[] data) {
            super(TYPE);
            this.mimeType = Objects.requireNonNull(mimeType, "MIME类型不能为null");
            this.data = data != null ? data.clone() : new byte[0];
        }

        public String getMimeType() {
            return mimeType;
        }

        public byte[] getData() {
            return data.clone();
        }

        @Override
        public String toString() {
            return "DataBlock{mimeType='" + mimeType + "', dataSize=" + data.length + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DataBlock that)) return false;
            return mimeType.equals(that.mimeType) && Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            int result = mimeType.hashCode();
            result = 31 * result + Arrays.hashCode(data);
            return result;
        }
    }

    /**
     * 工具调用内容块。
     * <p>表示智能体发起的一次工具调用，包含调用ID、工具名称和参数。</p>
     */
    public static class ToolCallBlock extends ContentBlock {

        /** 类型标识 */
        public static final String TYPE = "tool_call";

        private final String id;
        private final String name;
        private final String arguments;

        public ToolCallBlock(String id, String name, String arguments) {
            super(TYPE);
            this.id = Objects.requireNonNull(id, "工具调用ID不能为null");
            this.name = Objects.requireNonNull(name, "工具名称不能为null");
            this.arguments = arguments != null ? arguments : "";
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getArguments() {
            return arguments;
        }

        @Override
        public String toString() {
            return "ToolCallBlock{id='" + id + "', name='" + name + "'}";
        }
    }

    /**
     * 工具结果内容块。
     * <p>承载工具调用的返回结果，通过 toolCallId 关联到对应的工具调用。</p>
     */
    public static class ToolResultBlock extends ContentBlock {

        /** 类型标识 */
        public static final String TYPE = "tool_result";

        private final String toolCallId;
        private final String content;
        private final boolean isError;

        public ToolResultBlock(String toolCallId, String content, boolean isError) {
            super(TYPE);
            this.toolCallId = Objects.requireNonNull(toolCallId, "工具调用ID不能为null");
            this.content = content != null ? content : "";
            this.isError = isError;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public String getContent() {
            return content;
        }

        public boolean isError() {
            return isError;
        }

        @Override
        public String toString() {
            return "ToolResultBlock{toolCallId='" + toolCallId + "', isError=" + isError + "}";
        }
    }

    /**
     * 思考内容块。
     * <p>承载模型的推理/思考过程，用于展示 Chain-of-Thought 等中间推理步骤。</p>
     */
    public static class ThinkingBlock extends ContentBlock {

        /** 类型标识 */
        public static final String TYPE = "thinking";

        private final String text;

        public ThinkingBlock(String text) {
            super(TYPE);
            this.text = text != null ? text : "";
        }

        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return "ThinkingBlock{text='" + text + "'}";
        }
    }

    /**
     * 提示内容块。
     * <p>承载系统提示或引导信息，用于向用户展示辅助说明。</p>
     */
    public static class HintBlock extends ContentBlock {

        /** 类型标识 */
        public static final String TYPE = "hint";

        private final String text;

        public HintBlock(String text) {
            super(TYPE);
            this.text = text != null ? text : "";
        }

        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return "HintBlock{text='" + text + "'}";
        }
    }
}
