package com.demo.agentscope.middleware;

import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链路追踪中间件。
 * <p>
 * 记录所有hook事件的执行时间与关键信息，并统计模型调用次数、
 * 工具调用次数和总token用量。通过 getStats() 可获取汇总统计。
 * </p>
 */
public class TracingMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(TracingMiddleware.class);

    /** 总token用量 */
    private final AtomicInteger totalTokens;

    /** 模型调用次数 */
    private final AtomicInteger modelCallCount;

    /** 工具调用次数 */
    private final AtomicInteger toolCallCount;

    public TracingMiddleware() {
        this.totalTokens = new AtomicInteger(0);
        this.modelCallCount = new AtomicInteger(0);
        this.toolCallCount = new AtomicInteger(0);
    }

    @Override
    public void onReplyStart(AgentContext ctx) {
        long now = System.currentTimeMillis();
        ctx.setReplyStartTime(now);
        log.info("[Tracing] onReplyStart | agentId={}, sessionId={}, time={}",
                ctx.getAgentId(), ctx.getSessionId(), now);
    }

    @Override
    public void onModelCall(AgentContext ctx, Msg request) {
        long elapsed = System.currentTimeMillis() - ctx.getReplyStartTime();
        log.info("[Tracing] onModelCall | agentId={}, requestRole={}, contentBlocks={}, elapsed={}ms",
                ctx.getAgentId(), request.getRole(), request.getContent().size(), elapsed);
    }

    @Override
    public void onModelCallEnd(AgentContext ctx, Msg response) {
        modelCallCount.incrementAndGet();
        Msg.TokenUsage usage = response.getUsage();
        int tokens = usage != null ? usage.getTotalTokens() : 0;
        totalTokens.addAndGet(tokens);
        long elapsed = System.currentTimeMillis() - ctx.getReplyStartTime();
        log.info("[Tracing] onModelCallEnd | agentId={}, promptTokens={}, completionTokens={}, " +
                        "totalTokens={}, cumulativeTokens={}, elapsed={}ms",
                ctx.getAgentId(),
                usage != null ? usage.getPromptTokens() : 0,
                usage != null ? usage.getCompletionTokens() : 0,
                tokens, totalTokens.get(), elapsed);
    }

    @Override
    public void onToolCall(AgentContext ctx, ContentBlock.ToolCallBlock toolCall) {
        toolCallCount.incrementAndGet();
        long elapsed = System.currentTimeMillis() - ctx.getReplyStartTime();
        log.info("[Tracing] onToolCall | agentId={}, toolName={}, callId={}, elapsed={}ms",
                ctx.getAgentId(), toolCall.getName(), toolCall.getId(), elapsed);
    }

    @Override
    public void onToolResult(AgentContext ctx, ContentBlock.ToolResultBlock result) {
        long elapsed = System.currentTimeMillis() - ctx.getReplyStartTime();
        log.info("[Tracing] onToolResult | agentId={}, toolCallId={}, isError={}, elapsed={}ms",
                ctx.getAgentId(), result.getToolCallId(), result.isError(), elapsed);
    }

    @Override
    public void onReplyEnd(AgentContext ctx, EventStream stream) {
        long elapsed = System.currentTimeMillis() - ctx.getReplyStartTime();
        log.info("[Tracing] onReplyEnd | agentId={}, totalTokens={}, modelCalls={}, toolCalls={}, " +
                        "events={}, elapsed={}ms",
                ctx.getAgentId(), totalTokens.get(), modelCallCount.get(),
                toolCallCount.get(), stream.size(), elapsed);
    }

    /**
     * 获取追踪统计信息。
     *
     * @return 统计数据Map，包含 totalTokens、modelCallCount、toolCallCount
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTokens", totalTokens.get());
        stats.put("modelCallCount", modelCallCount.get());
        stats.put("toolCallCount", toolCallCount.get());
        return stats;
    }
}
