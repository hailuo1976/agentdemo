package com.demo.agentscope.session;

import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.middleware.AgentContext;
import com.demo.agentscope.middleware.Middleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 会话日志中间件。
 * <p>
 * 镜像 {@link com.demo.agentscope.middleware.TracingMiddleware} 的结构，
 * 实现 {@link Middleware} 全部 6 个 hook，每个 hook 把对应事件委托给
 * {@link SessionLogger} 持久化到 JSONL 文件。
 * </p>
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 *   <li>不持有任何状态：所有持久化逻辑在 SessionLogger 中，本类只做桥接。</li>
 *   <li>{@link #onReplyStart} 从 {@code ctx.getAttribute("userInput")} 读取用户输入原文
 *       （由 {@code Agent.reply()} 在 fireReplyStart 之前设置）。</li>
 *   <li>{@link #onReplyEnd} 从 {@code ctx.getAttribute("agentState")} 读取智能体状态快照
 *       （由 {@code Agent.reply()} 在 fireReplyEnd 之前设置）。</li>
 * </ul>
 */
public class SessionLoggingMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(SessionLoggingMiddleware.class);

    /** AgentContext 属性键：用户输入原文（由 Agent.reply 设置） */
    public static final String ATTR_USER_INPUT = "userInput";

    /** AgentContext 属性键：智能体状态快照（由 Agent.reply 设置） */
    public static final String ATTR_AGENT_STATE = "agentState";

    private final SessionLogger sessionLogger;

    /**
     * @param sessionLogger 被委托的日志写入器（不可为 null）
     */
    public SessionLoggingMiddleware(SessionLogger sessionLogger) {
        this.sessionLogger = sessionLogger;
    }

    /**
     * @return 当前持有的 SessionLogger（供 {@code /resume} 替换日志器场景读取旧引用）
     */
    public SessionLogger getSessionLogger() {
        return sessionLogger;
    }

    @Override
    public void onReplyStart(AgentContext ctx) {
        Object userInput = ctx.getAttribute(ATTR_USER_INPUT);
        String text = userInput != null ? userInput.toString() : "";
        try {
            sessionLogger.logReplyStart(ctx.getSessionId(), text);
        } catch (Exception e) {
            log.warn("[SessionLog] onReplyStart 记录失败", e);
        }
    }

    @Override
    public void onModelCall(AgentContext ctx, Msg request) {
        try {
            sessionLogger.logModelCall(request);
        } catch (Exception e) {
            log.warn("[SessionLog] onModelCall 记录失败", e);
        }
    }

    @Override
    public void onModelCallEnd(AgentContext ctx, Msg response) {
        try {
            sessionLogger.logModelCallEnd(response);
        } catch (Exception e) {
            log.warn("[SessionLog] onModelCallEnd 记录失败", e);
        }
    }

    @Override
    public void onToolCall(AgentContext ctx, ContentBlock.ToolCallBlock toolCall) {
        try {
            sessionLogger.logToolCall(toolCall);
        } catch (Exception e) {
            log.warn("[SessionLog] onToolCall 记录失败", e);
        }
    }

    @Override
    public void onToolResult(AgentContext ctx, ContentBlock.ToolResultBlock result) {
        try {
            sessionLogger.logToolResult(result);
        } catch (Exception e) {
            log.warn("[SessionLog] onToolResult 记录失败", e);
        }
    }

    @Override
    public void onReplyEnd(AgentContext ctx, EventStream stream) {
        try {
            Object state = ctx.getAttribute(ATTR_AGENT_STATE);
            @SuppressWarnings("unchecked")
            Map<String, Object> stateMap = state instanceof Map
                    ? (Map<String, Object>) state : null;
            sessionLogger.logReplyEnd(stateMap);
        } catch (Exception e) {
            log.warn("[SessionLog] onReplyEnd 记录失败", e);
        }
    }
}
