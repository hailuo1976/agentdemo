package com.demo.agentscope.middleware;

/**
 * 预算超限异常。
 * <p>
 * 当累计token用量超过预设预算时抛出此异常，
 * 用于中断智能体回复流程并通知上层调用方。
 * </p>
 */
public class BudgetExceededException extends RuntimeException {

    /** 预算上限 */
    private final int budget;

    /** 实际消耗 */
    private final int actual;

    public BudgetExceededException(int budget, int actual) {
        super(String.format("回复预算超限: budget=%d, actual=%d, exceeded=%d",
                budget, actual, actual - budget));
        this.budget = budget;
        this.actual = actual;
    }

    public int getBudget() {
        return budget;
    }

    public int getActual() {
        return actual;
    }
}
