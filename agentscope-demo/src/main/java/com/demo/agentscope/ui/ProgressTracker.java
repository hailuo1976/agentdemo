package com.demo.agentscope.ui;

import java.util.List;
import java.util.Map;

/**
 * 进度跟踪器接口。
 * <p>
 * 用于跟踪智能体执行过程中的规划、工具调用和任务进度。
 * </p>
 */
public interface ProgressTracker {

    /**
     * 记录规划开始。
     *
     * @param agentName 智能体名称
     * @param goal 任务目标
     */
    void onPlanningStart(String agentName, String goal);

    /**
     * 记录规划决策点。
     *
     * @param agentName 智能体名称
     * @param decision 决策内容
     * @param reason 决策原因
     */
    void onPlanningDecision(String agentName, String decision, String reason);

    /**
     * 记录规划完成。
     *
     * @param agentName 智能体名称
     * @param steps 规划步骤数
     */
    void onPlanningComplete(String agentName, int steps);

    /**
     * 记录工具调用开始。
     *
     * @param agentName 智能体名称
     * @param toolName 工具名称
     * @param iteration 当前迭代次数
     */
    void onToolCallStart(String agentName, String toolName, int iteration);

    /**
     * 记录工具调用完成。
     *
     * @param agentName 智能体名称
     * @param toolName 工具名称
     * @param success 是否成功
     * @param durationMs 耗时（毫秒）
     */
    void onToolCallComplete(String agentName, String toolName, boolean success, long durationMs);

    /**
     * 记录执行进度更新。
     *
     * @param agentName 智能体名称
     * @param current 当前进度
     * @param total 总进度
     * @param message 进度消息
     */
    void onProgressUpdate(String agentName, int current, int total, String message);

    /**
     * 记录执行完成。
     *
     * @param agentName 智能体名称
     * @param totalIterations 总迭代次数
     * @param durationMs 总耗时（毫秒）
     */
    void onExecutionComplete(String agentName, int totalIterations, long durationMs);

    /**
     * 记录错误。
     *
     * @param agentName 智能体名称
     * @param error 错误信息
     */
    void onError(String agentName, String error);
}
