package com.demo.agentscope.ui;

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

    /** 思考流是否已经开始（用于控制 DIM 样式只在首尾输出一次） */
    private boolean thinkingStreamStarted;

    /** 工具输出流式回显是否已开头（用于在首行打引导、末行打收尾分隔） */
    private boolean streamingActive;

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

    /**
     * 流式文本增量实时回显。
     * <p>
     * 不换行直接输出，刷新 stdout 以实现 token 级实时渲染。
     * 在 MINIMAL/QUIET 模式下静默。
     * </p>
     *
     * @param delta 文本增量片段
     */
    public void onTextDelta(String delta) {
        if (verbosity == VerbosityLevel.MINIMAL) return;
        System.out.print(delta);
        System.out.flush();
    }

    /**
     * 流式思考增量实时回显（DIM 样式）。
     *
     * @param delta 思考内容增量片段
     */
    public void onThinkingDelta(String delta) {
        if (verbosity == VerbosityLevel.MINIMAL) return;
        if (!thinkingStreamStarted) {
            System.out.print("\u001B[2m");
            thinkingStreamStarted = true;
        }
        System.out.print(delta);
        System.out.flush();
    }

    /**
     * 流式回复结束：换行收尾，避免后续输出粘连。同时闭合思考流的 DIM 样式。
     */
    public void onStreamEnd() {
        if (verbosity == VerbosityLevel.MINIMAL) return;
        if (thinkingStreamStarted) {
            System.out.print("\u001B[0m");
            thinkingStreamStarted = false;
        }
        System.out.println();
    }

    /**
     * 工具执行期间逐行输出实时回显。
     * <p>
     * 由 {@code CodeExecutionManager.OutputLineCallback} 触发，用于 execute_python 等长任务执行过程中
     * 把 stdout / stderr 同步打印到终端，避免用户在超时窗口内看不到任何反馈。
     * 首次调用会先打一个引导行（"工具输出 ↓"），结束后再 {@link #onToolOutputStreamEnd} 收尾。
     * </p>
     *
     * @param toolName 工具名（如 "execute_python"）
     * @param stream   "stdout" 或 "stderr"
     * @param line     本行内容（不含换行符）
     */
    public void onToolOutputStream(String toolName, String stream, String line) {
        if (verbosity == VerbosityLevel.MINIMAL) return;
        if (!streamingActive) {
            streamingActive = true;
            System.out.println("\u001B[2m  ── " + toolName + " 输出 ↓ ──\u001B[0m");
        }
        String prefix = "stderr".equals(stream)
                ? "\u001B[33m  │ \u001B[0m"
                : "\u001B[2m  │ \u001B[0m";
        System.out.println(prefix + line);
    }

    /**
     * 流式输出收尾：打一个分隔线，把后续模型回复和输出分开。
     */
    public void onToolOutputStreamEnd() {
        if (!streamingActive) return;
        streamingActive = false;
        System.out.println("\u001B[2m  ──────────────\u001B[0m");
        System.out.flush();
    }

    public int getCurrentIteration() {
        return currentIteration;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
