package com.demo.agentscope.middleware;

import com.demo.agentscope.event.AgentEvent;
import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回复预算控制中间件。
 * <p>
 * 在 onModelCallEnd 中累计token用量，当总用量超过预设预算时，
 * 抛出 BudgetExceededException 并向事件流发射 REPLY_BUDGET_EXCEEDED 事件。
 * </p>
 */
public class ReplyBudgetControlMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(ReplyBudgetControlMiddleware.class);

    /** 默认预算上限（token数） */
    private static final int DEFAULT_BUDGET = 100000;

    /** 预算上限 */
    private final int budget;

    /** 累计token用量 */
    private final AtomicInteger usedTokens;

    /** 关联的事件流引用，用于预算超限时发射事件 */
    private volatile EventStream boundStream;

    public ReplyBudgetControlMiddleware() {
        this(DEFAULT_BUDGET);
    }

    public ReplyBudgetControlMiddleware(int budget) {
        this.budget = budget;
        this.usedTokens = new AtomicInteger(0);
    }

    @Override
    public void onReplyStart(AgentContext ctx) {
        // 每轮回复开始时重置累计用量
        usedTokens.set(0);
        log.debug("[BudgetControl] 回复开始 | agentId={}, budget={}", ctx.getAgentId(), budget);
    }

    @Override
    public void onModelCallEnd(AgentContext ctx, Msg response) {
        Msg.TokenUsage usage = response.getUsage();
        int tokens = usage != null ? usage.getTotalTokens() : 0;
        int cumulative = usedTokens.addAndGet(tokens);

        log.debug("[BudgetControl] onModelCallEnd | agentId={}, tokens={}, cumulative={}, budget={}",
                ctx.getAgentId(), tokens, cumulative, budget);

        if (cumulative > budget) {
            log.warn("[BudgetControl] 预算超限 | agentId={}, budget={}, actual={}",
                    ctx.getAgentId(), budget, cumulative);

            // 发射预算超限事件
            if (boundStream != null) {
                boundStream.emit(AgentEvent.replyBudgetExceeded(ctx.getAgentId(), budget, cumulative));
            }

            throw new BudgetExceededException(budget, cumulative);
        }
    }

    @Override
    public void onReplyEnd(AgentContext ctx, EventStream stream) {
        // 绑定事件流引用，以便后续 onModelCallEnd 中可以发射事件
        this.boundStream = stream;
        log.info("[BudgetControl] 回复结束 | agentId={}, usedTokens={}, budget={}",
                ctx.getAgentId(), usedTokens.get(), budget);
    }

    /**
     * 获取当前累计token用量。
     *
     * @return 累计token数
     */
    public int getUsedTokens() {
        return usedTokens.get();
    }

    /**
     * 获取预算上限。
     *
     * @return 预算上限token数
     */
    public int getBudget() {
        return budget;
    }
}
