package com.demo.agentscope.middleware;

import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;

/**
 * 中间件接口（洋葱模型）。
 * <p>
 * AgentScope 2.0 中间件系统的核心接口，定义了6个hook点，
 * 对应智能体回复生命周期的各个阶段。中间件按照洋葱模型执行：
 * 请求阶段从外到内依次执行，响应阶段从内到外依次执行。
 * </p>
 *
 * <pre>
 *   onReplyStart → onModelCall → [LLM] → onModelCallEnd
 *                                     ↕
 *                            onToolCall → [Tool] → onToolResult
 *   onReplyEnd ← ← ← ← ← ← ← ← ← ← ← ← ← ←
 * </pre>
 */
public interface Middleware {

    /**
     * 回复开始前触发。
     * <p>在智能体开始处理回复之前调用，可用于初始化上下文、记录开始时间等。</p>
     *
     * @param ctx 智能体上下文
     */
    default void onReplyStart(AgentContext ctx) {
        // 默认空实现
    }

    /**
     * 模型调用前触发。
     * <p>在LLM调用之前调用，可用于请求改写、参数校验、限流等。</p>
     *
     * @param ctx     智能体上下文
     * @param request 发送给模型的请求消息
     */
    default void onModelCall(AgentContext ctx, Msg request) {
        // 默认空实现
    }

    /**
     * 模型调用后触发。
     * <p>在LLM返回响应之后调用，可用于响应校验、token统计、内容过滤等。</p>
     *
     * @param ctx      智能体上下文
     * @param response 模型返回的响应消息
     */
    default void onModelCallEnd(AgentContext ctx, Msg response) {
        // 默认空实现
    }

    /**
     * 工具调用前触发。
     * <p>在工具执行之前调用，可用于权限校验、参数审计、调用拦截等。</p>
     *
     * @param ctx      智能体上下文
     * @param toolCall 工具调用内容块
     */
    default void onToolCall(AgentContext ctx, ContentBlock.ToolCallBlock toolCall) {
        // 默认空实现
    }

    /**
     * 工具执行后触发。
     * <p>在工具返回结果之后调用，可用于结果缓存、结果过滤、日志记录等。</p>
     *
     * @param ctx    智能体上下文
     * @param result 工具执行结果内容块
     */
    default void onToolResult(AgentContext ctx, ContentBlock.ToolResultBlock result) {
        // 默认空实现
    }

    /**
     * 回复结束后触发。
     * <p>在智能体完成整个回复流程之后调用，可用于上下文压缩、
     * 统计汇总、资源清理等收尾工作。</p>
     *
     * @param ctx    智能体上下文
     * @param stream 事件流
     */
    default void onReplyEnd(AgentContext ctx, EventStream stream) {
        // 默认空实现
    }
}
