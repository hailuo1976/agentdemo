package com.demo.agentscope.config;

import java.util.Locale;

/**
 * Agent 运行时限制的集中配置。
 * <p>
 * 把原本散落在各组件里的 {@code private static final} 常量集中到一个可变对象，
 * 支持「代码默认值 → 环境变量 → REPL 斜杠命令」三入口、按优先级覆盖：
 * <ul>
 *   <li>启动期：{@link #loadFromEnv()} 读取环境变量覆盖代码默认值</li>
 *   <li>运行期：REPL {@code /config set key=value} 通过 setter 即时调整</li>
 * </ul>
 * </p>
 * <p>
 * 各组件持本实例的引用（或其中的具体值），既能读取最新值，又避免散落的硬编码。
 * <b>非 final</b>：所有字段都可被 REPL 动态修改。
 * </p>
 */
public class AgentLimits {

    // ---- 迭代与预算 ----
    /** ReAct 循环的最大迭代次数。 */
    private int maxIterations = 50;

    /** 单次回复的 token 预算上限。 */
    private int replyBudgetTokens = 500_000;

    /** 单次 LLM 调用的输出 token 上限（max_tokens 参数）。 */
    private int maxOutputTokens = 8192;

    /** 迭代预算告警阈值：剩余轮数 ≤ 此值时注入 user 角色预算告警。 */
    private int iterationWarnRemaining = 10;

    /** token 预算告警阈值：已用百分比 ≥ 此值时注入告警（0-100）。 */
    private int tokenBudgetWarnPercent = 80;

    // ---- 上下文 ----
    /** 触发上下文压缩的 token 阈值。 */
    private int maxContextTokens = 40_000;

    /** 压缩时保留的最近消息条数。 */
    private int maxRecentMessages = 10;

    /** 上下文召回的短期记忆条数。 */
    private int shortTermMemoryLimit = 3;

    /** 上下文召回的长期记忆条数。 */
    private int longTermMemoryLimit = 2;

    // ---- 微压缩 ----
    /** {@link com.demo.agentscope.context.MicroCompactor} 保留的最近工具调用数。 */
    private int microCompactorKeepRecent = 5;

    /** {@link com.demo.agentscope.context.MicroCompactor} 触发压缩的工具调用次数。 */
    private int microCompactorTriggerToolCount = 12;

    // ---- 工具结果摘要 ----
    /** 单条工具结果触发摘要的字符阈值。 */
    private int toolResultSummaryThreshold = 3000;

    /** 工具结果摘要的最大字符长度。 */
    private int toolResultSummaryMaxLength = 500;

    // ---- 执行约束 ----
    /** 命令执行超时秒数。 */
    private long commandTimeoutSeconds = 30;

    /** workspace 操作超时秒数。 */
    private long workspaceTimeoutSeconds = 60;

    /** 单文件大小上限（字节），0 表示不限制。 */
    private long maxFileSizeBytes = 0L;

    // ---- LLM HTTP 客户端 ----
    /**
     * LLM 流式 HTTP 读取超时秒数。
     * <p>
     * 默认 300 秒（5 分钟）。模型在推理过程中可能长时间不输出 token（深度思考、长上下文召回），
     * OkHttp 默认 10 秒会触发 {@link java.net.SocketTimeoutException}。该值应覆盖最长的「静默期」。
     * </p>
     */
    private long llmReadTimeoutSeconds = 300;

    /** LLM HTTP 连接建立超时秒数。 */
    private long llmConnectTimeoutSeconds = 30;

    /** LLM HTTP 写入请求体超时秒数。 */
    private long llmWriteTimeoutSeconds = 30;

    /**
     * LLM 调用遇到 {@link java.net.SocketTimeoutException} 时的最大重试次数。
     * <p>
     * 仅在「未产生任何输出」时重试流式请求（已发射 delta 的请求重试会导致重复输出）。
     * 默认 2 次，配合指数退避（2s、4s）。
     * </p>
     */
    private int llmMaxRetries = 2;

    public AgentLimits() {
    }

    /**
     * 从环境变量读取覆盖值。仅在启动期调用一次；REPL 设置优先级更高（之后才被调用）。
     * 未设置或非法的环境变量会被静默跳过并保留代码默认值，不抛异常。
     *
     * @return this，便于链式调用
     */
    public AgentLimits loadFromEnv() {
        maxIterations = readInt("MAX_ITERATIONS", maxIterations);
        replyBudgetTokens = readInt("REPLY_BUDGET", replyBudgetTokens);
        maxOutputTokens = readInt("MAX_OUTPUT_TOKENS", maxOutputTokens);
        iterationWarnRemaining = readInt("ITERATION_WARN_REMAINING", iterationWarnRemaining);
        tokenBudgetWarnPercent = readInt("TOKEN_BUDGET_WARN_PERCENT", tokenBudgetWarnPercent);
        maxContextTokens = readInt("MAX_CONTEXT_TOKENS", maxContextTokens);
        maxRecentMessages = readInt("MAX_RECENT_MESSAGES", maxRecentMessages);
        shortTermMemoryLimit = readInt("SHORT_TERM_MEMORY_LIMIT", shortTermMemoryLimit);
        longTermMemoryLimit = readInt("LONG_TERM_MEMORY_LIMIT", longTermMemoryLimit);
        microCompactorKeepRecent = readInt("MICRO_COMPACTOR_KEEP_RECENT", microCompactorKeepRecent);
        microCompactorTriggerToolCount = readInt("MICRO_COMPACTOR_TRIGGER_TOOL_COUNT", microCompactorTriggerToolCount);
        toolResultSummaryThreshold = readInt("TOOL_RESULT_SUMMARY_THRESHOLD", toolResultSummaryThreshold);
        toolResultSummaryMaxLength = readInt("TOOL_RESULT_SUMMARY_MAX_LENGTH", toolResultSummaryMaxLength);
        commandTimeoutSeconds = readLong("COMMAND_TIMEOUT_SECONDS", commandTimeoutSeconds);
        workspaceTimeoutSeconds = readLong("WORKSPACE_TIMEOUT_SECONDS", workspaceTimeoutSeconds);
        maxFileSizeBytes = readLong("MAX_FILE_SIZE_BYTES", maxFileSizeBytes);
        llmReadTimeoutSeconds = readLong("LLM_READ_TIMEOUT_SECONDS", llmReadTimeoutSeconds);
        llmConnectTimeoutSeconds = readLong("LLM_CONNECT_TIMEOUT_SECONDS", llmConnectTimeoutSeconds);
        llmWriteTimeoutSeconds = readLong("LLM_WRITE_TIMEOUT_SECONDS", llmWriteTimeoutSeconds);
        llmMaxRetries = readInt("LLM_MAX_RETRIES", llmMaxRetries);
        return this;
    }

    /**
     * 应用一行 {@code key=value} 配置，REPL {@code /config set} 调用。
     *
     * @param expression 形如 {@code maxIterations=30}
     * @throws IllegalArgumentException key 未知或值非法
     */
    public void apply(String expression) {
        String[] parts = expression.split("=", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("格式应为 key=value，收到：" + expression);
        }
        String key = parts[0].trim();
        String value = parts[1].trim();
        switch (key) {
            case "maxIterations" -> maxIterations = requirePositiveInt(key, value);
            case "replyBudgetTokens" -> replyBudgetTokens = requirePositiveInt(key, value);
            case "maxOutputTokens" -> maxOutputTokens = requirePositiveInt(key, value);
            case "iterationWarnRemaining" -> iterationWarnRemaining = requireNonNegativeInt(key, value);
            case "tokenBudgetWarnPercent" -> {
                int pct = requireNonNegativeInt(key, value);
                if (pct > 100) {
                    throw new IllegalArgumentException("tokenBudgetWarnPercent 必须在 0-100，收到：" + value);
                }
                tokenBudgetWarnPercent = pct;
            }
            case "maxContextTokens" -> maxContextTokens = requirePositiveInt(key, value);
            case "maxRecentMessages" -> maxRecentMessages = requirePositiveInt(key, value);
            case "shortTermMemoryLimit" -> shortTermMemoryLimit = requireNonNegativeInt(key, value);
            case "longTermMemoryLimit" -> longTermMemoryLimit = requireNonNegativeInt(key, value);
            case "microCompactorKeepRecent" -> microCompactorKeepRecent = requirePositiveInt(key, value);
            case "microCompactorTriggerToolCount" -> microCompactorTriggerToolCount = requirePositiveInt(key, value);
            case "toolResultSummaryThreshold" -> toolResultSummaryThreshold = requirePositiveInt(key, value);
            case "toolResultSummaryMaxLength" -> toolResultSummaryMaxLength = requirePositiveInt(key, value);
            case "commandTimeoutSeconds" -> commandTimeoutSeconds = requirePositiveLong(key, value);
            case "workspaceTimeoutSeconds" -> workspaceTimeoutSeconds = requirePositiveLong(key, value);
            case "maxFileSizeBytes" -> maxFileSizeBytes = requireNonNegativeLong(key, value);
            case "llmReadTimeoutSeconds" -> llmReadTimeoutSeconds = requirePositiveLong(key, value);
            case "llmConnectTimeoutSeconds" -> llmConnectTimeoutSeconds = requirePositiveLong(key, value);
            case "llmWriteTimeoutSeconds" -> llmWriteTimeoutSeconds = requirePositiveLong(key, value);
            case "llmMaxRetries" -> llmMaxRetries = requireNonNegativeInt(key, value);
            default -> throw new IllegalArgumentException("未知配置项：" + key);
        }
    }

    // ---- getters / setters ----

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public int getReplyBudgetTokens() { return replyBudgetTokens; }
    public void setReplyBudgetTokens(int replyBudgetTokens) { this.replyBudgetTokens = replyBudgetTokens; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public int getIterationWarnRemaining() { return iterationWarnRemaining; }
    public void setIterationWarnRemaining(int iterationWarnRemaining) { this.iterationWarnRemaining = iterationWarnRemaining; }

    public int getTokenBudgetWarnPercent() { return tokenBudgetWarnPercent; }
    public void setTokenBudgetWarnPercent(int tokenBudgetWarnPercent) { this.tokenBudgetWarnPercent = tokenBudgetWarnPercent; }

    public int getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }

    public int getMaxRecentMessages() { return maxRecentMessages; }
    public void setMaxRecentMessages(int maxRecentMessages) { this.maxRecentMessages = maxRecentMessages; }

    public int getShortTermMemoryLimit() { return shortTermMemoryLimit; }
    public void setShortTermMemoryLimit(int shortTermMemoryLimit) { this.shortTermMemoryLimit = shortTermMemoryLimit; }

    public int getLongTermMemoryLimit() { return longTermMemoryLimit; }
    public void setLongTermMemoryLimit(int longTermMemoryLimit) { this.longTermMemoryLimit = longTermMemoryLimit; }

    public int getMicroCompactorKeepRecent() { return microCompactorKeepRecent; }
    public void setMicroCompactorKeepRecent(int microCompactorKeepRecent) { this.microCompactorKeepRecent = microCompactorKeepRecent; }

    public int getMicroCompactorTriggerToolCount() { return microCompactorTriggerToolCount; }
    public void setMicroCompactorTriggerToolCount(int microCompactorTriggerToolCount) { this.microCompactorTriggerToolCount = microCompactorTriggerToolCount; }

    public int getToolResultSummaryThreshold() { return toolResultSummaryThreshold; }
    public void setToolResultSummaryThreshold(int toolResultSummaryThreshold) { this.toolResultSummaryThreshold = toolResultSummaryThreshold; }

    public int getToolResultSummaryMaxLength() { return toolResultSummaryMaxLength; }
    public void setToolResultSummaryMaxLength(int toolResultSummaryMaxLength) { this.toolResultSummaryMaxLength = toolResultSummaryMaxLength; }

    public long getCommandTimeoutSeconds() { return commandTimeoutSeconds; }
    public void setCommandTimeoutSeconds(long commandTimeoutSeconds) { this.commandTimeoutSeconds = commandTimeoutSeconds; }

    public long getWorkspaceTimeoutSeconds() { return workspaceTimeoutSeconds; }
    public void setWorkspaceTimeoutSeconds(long workspaceTimeoutSeconds) { this.workspaceTimeoutSeconds = workspaceTimeoutSeconds; }

    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }

    public long getLlmReadTimeoutSeconds() { return llmReadTimeoutSeconds; }
    public void setLlmReadTimeoutSeconds(long llmReadTimeoutSeconds) { this.llmReadTimeoutSeconds = llmReadTimeoutSeconds; }

    public long getLlmConnectTimeoutSeconds() { return llmConnectTimeoutSeconds; }
    public void setLlmConnectTimeoutSeconds(long llmConnectTimeoutSeconds) { this.llmConnectTimeoutSeconds = llmConnectTimeoutSeconds; }

    public long getLlmWriteTimeoutSeconds() { return llmWriteTimeoutSeconds; }
    public void setLlmWriteTimeoutSeconds(long llmWriteTimeoutSeconds) { this.llmWriteTimeoutSeconds = llmWriteTimeoutSeconds; }

    public int getLlmMaxRetries() { return llmMaxRetries; }
    public void setLlmMaxRetries(int llmMaxRetries) { this.llmMaxRetries = llmMaxRetries; }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "AgentLimits{maxIterations=%d, replyBudgetTokens=%d, maxOutputTokens=%d, "
                        + "iterationWarnRemaining=%d, "
                        + "tokenBudgetWarnPercent=%d%%, maxContextTokens=%d, maxRecentMessages=%d, "
                        + "shortTermMemoryLimit=%d, longTermMemoryLimit=%d, "
                        + "microCompactorKeepRecent=%d, microCompactorTriggerToolCount=%d, "
                        + "toolResultSummaryThreshold=%d, toolResultSummaryMaxLength=%d, "
                        + "commandTimeoutSeconds=%d, workspaceTimeoutSeconds=%d, maxFileSizeBytes=%d, "
                        + "llmReadTimeoutSeconds=%d, llmConnectTimeoutSeconds=%d, llmWriteTimeoutSeconds=%d, llmMaxRetries=%d}",
                maxIterations, replyBudgetTokens, maxOutputTokens, iterationWarnRemaining, tokenBudgetWarnPercent,
                maxContextTokens, maxRecentMessages, shortTermMemoryLimit, longTermMemoryLimit,
                microCompactorKeepRecent, microCompactorTriggerToolCount,
                toolResultSummaryThreshold, toolResultSummaryMaxLength,
                commandTimeoutSeconds, workspaceTimeoutSeconds, maxFileSizeBytes,
                llmReadTimeoutSeconds, llmConnectTimeoutSeconds, llmWriteTimeoutSeconds, llmMaxRetries);
    }

    // ---- 解析辅助 ----

    private static int readInt(String env, int fallback) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long readLong(String env, long fallback) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int requirePositiveInt(String key, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " 必须为正整数，收到：" + value);
        }
        return parsed;
    }

    private static int requireNonNegativeInt(String key, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(key + " 不能为负，收到：" + value);
        }
        return parsed;
    }

    private static long requirePositiveLong(String key, String value) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " 必须为正，收到：" + value);
        }
        return parsed;
    }

    private static long requireNonNegativeLong(String key, String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(key + " 不能为负，收到：" + value);
        }
        return parsed;
    }
}
