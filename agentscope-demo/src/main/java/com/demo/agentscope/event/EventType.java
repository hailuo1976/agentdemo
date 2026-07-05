package com.demo.agentscope.event;

/**
 * AgentScope 2.0 事件类型枚举。
 * <p>
 * 定义了事件流中所有可能的事件类型，涵盖回复生命周期、模型调用、
 * 内容块、工具交互、权限管理、工作空间操作及多智能体协作等场景。
 * </p>
 */
public enum EventType {

    // ---- 回复生命周期 ----

    /** 回复开始 */
    REPLY_START,
    /** 回复结束 */
    REPLY_END,

    // ---- 模型调用 ----

    /** 模型调用开始 */
    MODEL_CALL_START,
    /** 模型调用结束 */
    MODEL_CALL_END,

    // ---- 内容块事件 ----

    /** 文本内容块 */
    TEXT_BLOCK,
    /** 文本增量片段（流式） */
    TEXT_DELTA,
    /** 思考内容块 */
    THINKING_BLOCK,
    /** 思考增量片段（流式） */
    THINKING_DELTA,

    // ---- 工具交互 ----

    /** 工具调用 */
    TOOL_CALL,
    /** 工具结果 */
    TOOL_RESULT,

    // ---- 用户与外部交互 ----

    /** 需要用户确认 */
    REQUIRE_USER_CONFIRM,
    /** 需要外部执行 */
    REQUIRE_EXTERNAL_EXECUTION,

    // ---- 错误与异常 ----

    /** 错误事件 */
    ERROR,

    // ---- 上下文管理 ----

    /** 上下文压缩 */
    CONTEXT_COMPRESSED,

    // ---- 权限管理 ----

    /** 权限检查 */
    PERMISSION_CHECK,
    /** 权限询问 */
    PERMISSION_ASK,

    // ---- 工作空间操作 ----

    /** 工作空间操作 */
    WORKSPACE_OPERATION,

    // ---- 多智能体协作 ----

    /** 创建智能体团队 */
    AGENT_TEAM_CREATE,
    /** 创建智能体 */
    AGENT_CREATE,
    /** 智能体消息 */
    AGENT_MESSAGE,
    /** 解散团队 */
    TEAM_DISSOLVE,

    // ---- 预算控制 ----

    /** 回复预算超限 */
    REPLY_BUDGET_EXCEEDED;
}
