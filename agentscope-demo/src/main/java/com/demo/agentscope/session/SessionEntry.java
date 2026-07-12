package com.demo.agentscope.session;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 会话日志条目（一行 JSONL）。
 * <p>
 * 表示会话日志文件（{@code <sessionId>.jsonl}）中的一条记录，
 * 对应 AgentScope 交互流程中的一个原子事件：用户输入、模型调用、
 * 工具调用、工具结果、回复开始/结束、错误等。
 * </p>
 * <p>
 * 采用 Java 17 {@link record}，不可变、自动序列化。
 * 通过 {@link #parentUuid()} 串联形成 DAG，支持 Claude Code 式的
 * conversation chain walking，便于恢复时按链重建上下文。
 * </p>
 *
 * @param uuid        本条目唯一标识
 * @param parentUuid  前一条目的 uuid；首条为 null
 * @param type        条目类型，见 {@link EntryType}
 * @param timestamp   ISO-8601 时间戳（{@code Instant.now().toString()}）
 * @param sessionId   会话 ID，同一会话内所有条目相同
 * @param role        消息角色：system / user / assistant / tool（来自 {@link com.demo.agentscope.message.Msg}）
 * @param content     内容块列表（序列化后的 {@link com.demo.agentscope.message.ContentBlock}）
 * @param usage       Token 用量（仅 LLM_RESPONSE 非空）
 * @param agentState  智能体状态快照（仅 REPLY_END 非空）
 * @param errorMsg    错误信息（仅 ERROR 类型非空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEntry(
        String uuid,
        String parentUuid,
        EntryType type,
        String timestamp,
        String sessionId,
        String role,
        List<BlockDto> content,
        TokenUsageDto usage,
        Map<String, Object> agentState,
        String errorMsg
) {

    /**
     * 条目类型枚举。
     * <p>
     * 对应中间件洋葱模型 6 个 hook 触发点，外加错误兜底。
     * </p>
     */
    public enum EntryType {
        /** 回复开始：捕获用户输入文本 */
        REPLY_START,
        /** 模型调用请求：时间标记（实际 prompt 由上下文链重建） */
        LLM_REQUEST,
        /** 模型调用响应：assistant 消息，含文本/工具调用块 */
        LLM_RESPONSE,
        /** 单次工具调用请求（审计用；上下文重建时已包含在 LLM_RESPONSE） */
        TOOL_CALL,
        /** 单次工具执行结果 */
        TOOL_RESULT,
        /** 回复结束：附 agentState 快照 */
        REPLY_END,
        /** 回复过程中发生错误 */
        ERROR
    }

    /**
     * 内容块 DTO（flat 结构覆盖 6 种 ContentBlock 子类）。
     * <p>
     * 通过 {@link #type} 字段判别式，未使用的字段为 null。
     * 用于 JSON 序列化/反序列化，避免多态 Jackson 配置的复杂性。
     * </p>
     *
     * @param type       块类型：text / data / tool_call / tool_result / thinking / hint
     * @param text       TextBlock / ThinkingBlock / HintBlock 的文本内容
     * @param toolCallId ToolCallBlock / ToolResultBlock 的关联 ID
     * @param toolName   ToolCallBlock 的工具名称
     * @param arguments  ToolCallBlock 的参数 JSON 字符串
     * @param content    ToolResultBlock 的结果文本
     * @param isError    ToolResultBlock 是否为错误
     * @param mimeType   DataBlock 的 MIME 类型
     * @param dataBase64 DataBlock 的 Base64 编码数据
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BlockDto(
            String type,
            String text,
            String toolCallId,
            String toolName,
            String arguments,
            String content,
            Boolean isError,
            String mimeType,
            String dataBase64
    ) {}

    /**
     * Token 用量 DTO。
     *
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenUsageDto(
            int promptTokens,
            int completionTokens
    ) {}
}
