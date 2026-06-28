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
 * 在 onModelCallEnd 中累计 token 用量，当总用量超过预设预算时，
 * 向事件流发射 REPLY_BUDGET_EXCEEDED 事件并打印警告日志。
 * </p>
 *
 * <h3>降级策略</h3>
 * <p>
 * 自 2026-06 调整：预算超限不再抛出 {@link BudgetExceededException} 中断回复，
 * 改为只发射事件 + 警告日志。原因：
 * <ul>
 *   <li>复杂任务（团队多轮工具调用、长上下文）容易触发，硬中断会让用户得不到部分结果</li>
 *   <li>{@code Agent.maxIterations} 已是兜底安全网，避免无限循环</li>
 *   <li>保留事件可观测性，调用方可自行监听并决定是否中止</li>
 * </ul>
 * </p>
 *
 * <h3>预算配置</h3>
 * <ul>
 *   <li>默认 500_000 tokens（足够多轮 ReAct 循环）</li>
 *   <li>支持环境变量 {@code REPLY_BUDGET} 覆盖，例如 {@code REPLY_BUDGET=1000000}</li>
 * </ul>
 * </p>
 */
public class ReplyBudgetControlMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(ReplyBudgetControlMiddleware.class);

    /** 默认预算上限（token 数） */
    private static final int DEFAULT_BUDGET = 500_000;

    /** 环境变量名：用于覆盖默认预算 */
    private static final String ENV_REPLY_BUDGET = "REPLY_BUDGET";

    /** 预算上限 */
    private final int budget;

    /** 累计 token 用量 */
    private final AtomicInteger usedTokens;

    /** 是否已经触发过本次回复的预算超限事件（避免重复发射） */
    private final AtomicInteger warnedFlag;

    /** 关联的事件流引用，用于预算超限时发射事件 */
    private volatile EventStream boundStream;

    public ReplyBudgetControlMiddleware() {
        this(resolveBudgetFromEnv());
    }

    public ReplyBudgetControlMiddleware(int budget) {
        this.budget = budget;
        this.usedTokens = new AtomicInteger(0);
        this.warnedFlag = new AtomicInteger(0);
    }

    /**
     * 从环境变量读取预算，未设置或非法时返回默认值。
     */
    private static int resolveBudgetFromEnv() {
        String raw = System.getenv(ENV_REPLY_BUDGET);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BUDGET;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_BUDGET;
        } catch (NumberFormatException e) {
            log.warn("[BudgetControl] 环境变量 {}={} 解析失败，使用默认预算 {}",
                    ENV_REPLY_BUDGET, raw, DEFAULT_BUDGET);
            return DEFAULT_BUDGET;
        }
    }

    @Override
    public void onReplyStart(AgentContext ctx) {
        // 每轮回复开始时重置累计用量与告警标记
        usedTokens.set(0);
        warnedFlag.set(0);
        log.debug("[BudgetControl] 回复开始 | agentId={}, budget={}", ctx.getAgentId(), budget);
    }

    @Override
    public void onModelCallEnd(AgentContext ctx, Msg response) {
        Msg.TokenUsage usage = response.getUsage();
        int tokens = usage != null ? usage.getTotalTokens() : 0;
        int cumulative = usedTokens.addAndGet(tokens);

        log.debug("[BudgetControl] onModelCallEnd | agentId={}, tokens={}, cumulative={}, budget={}",
                ctx.getAgentId(), tokens, cumulative, budget);

        if (cumulative > budget && warnedFlag.compareAndSet(0, 1)) {
            log.warn("[BudgetControl] 预算超限（已降级为警告，不中断回复）| agentId={}, budget={}, actual={}",
                    ctx.getAgentId(), budget, cumulative);

            // 发射预算超限事件，保留可观测性
            if (boundStream != null) {
                boundStream.emit(AgentEvent.replyBudgetExceeded(ctx.getAgentId(), budget, cumulative));
            }
            // 不再抛出 BudgetExceededException，让 Agent 继续 ReAct 循环
            // 兜底由 Agent.maxIterations 保证，避免无限消耗
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
     * 获取当前累计 token 用量。
     *
     * @return 累计 token 数
     */
    public int getUsedTokens() {
        return usedTokens.get();
    }

    /**
     * 获取预算上限。
     *
     * @return 预算上限 token 数
     */
    public int getBudget() {
        return budget;
    }
}
