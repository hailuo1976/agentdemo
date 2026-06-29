package com.demo.agentscope.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单智能体进度跟踪器。
 * <p>
 * 跟踪单个智能体的规划、工具调用和执行进度，
 * 根据详细程度级别控制输出信息量。
 * </p>
 */
public class AgentProgressTracker {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 智能体名称 */
    private final String agentName;

    /** 详细程度 */
    private final VerbosityLevel verbosity;

    /** 当前迭代次数 */
    private int currentIteration;

    /** 开始时间 */
    private long startTimeMs;

    public AgentProgressTracker(String agentName, VerbosityLevel verbosity) {
        this.agentName = agentName;
        this.verbosity = verbosity;
        this.currentIteration = 0;
    }

    /**
     * 记录回复开始。
     */
    public void onReplyStart(String userInput) {
        startTimeMs = System.currentTimeMillis();
        currentIteration = 0;

        if (verbosity == VerbosityLevel.MINIMAL) return;

        System.out.println();
        System.out.println("\u001B[1m\u001B[34m  🤖 " + agentName + " 思考中...\u001B[0m");
        if (verbosity.ordinal() >= VerbosityLevel.VERBOSE.ordinal()) {
            System.out.println("\u001B[2m  输入: " + truncate(userInput, 80) + "\u001B[0m");
        }
    }

    /**
     * 记录模型调用开始（规划阶段）。
     */
    public void onModelCallStart() {
        currentIteration++;
        if (verbosity == VerbosityLevel.MINIMAL) return;

        if (verbosity.ordinal() >= VerbosityLevel.STANDARD.ordinal()) {
            System.out.println("\u001B[34m  📡 迭代 " + currentIteration + ": 模型推理中...\u001B[0m");
        }
    }

    /**
     * 记录模型调用完成。
     */
    public void onModelCallComplete(int promptTokens, int completionTokens) {
        if (verbosity == VerbosityLevel.DEBUG) {
            System.out.println("\u001B[2m  tokens: prompt=" + promptTokens +
                    ", completion=" + completionTokens + "\u001B[0m");
        }
    }

    /**
     * 记录工具调用。
     */
    public void onToolCall(String toolName) {
        if (verbosity == VerbosityLevel.MINIMAL) return;

        if (verbosity.ordinal() >= VerbosityLevel.STANDARD.ordinal()) {
            System.out.println("\u001B[36m  🔧 调用工具: " + toolName + "\u001B[0m");
        }
    }

    /**
     * 记录工具调用完成。
     */
    public void onToolCallComplete(String toolName, boolean success, long durationMs) {
        if (verbosity == VerbosityLevel.DEBUG) {
            String icon = success ? "\u001B[32m✓\u001B[0m" : "\u001B[31m✗\u001B[0m";
            System.out.println("  " + icon + " " + toolName + " (" + durationMs + "ms)");
        }
    }

    /**
     * 记录回复完成。
     */
    public void onReplyComplete(long durationMs) {
        if (verbosity == VerbosityLevel.MINIMAL) return;

        if (verbosity.ordinal() >= VerbosityLevel.STANDARD.ordinal()) {
            System.out.println("\u001B[2m  完成: " + currentIteration + " 次迭代, " +
                    durationMs + "ms\u001B[0m");
        }
    }

    /**
     * 记录错误。
     */
    public void onError(String error) {
        System.out.println("\u001B[31m  ❌ 错误: " + error + "\u001B[0m");
    }

    public int getCurrentIteration() {
        return currentIteration;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
