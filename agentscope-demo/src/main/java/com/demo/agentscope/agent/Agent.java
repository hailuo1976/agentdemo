package com.demo.agentscope.agent;

import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.event.AgentEvent;
import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.middleware.AgentContext;
import com.demo.agentscope.middleware.MiddlewareChain;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionEngine;
import com.demo.agentscope.ui.AgentProgressTracker;
import com.demo.agentscope.ui.VerbosityLevel;
import com.demo.agentscope.workspace.WorkspaceManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * AgentScope 2.0 统一智能体。
 * <p>
 * 整合了 LLM 调用、工具执行、中间件、权限检查和工作空间管理等核心能力。
 * 支持自动工具调用循环（ReAct 模式）：模型输出工具调用 → 执行工具 →
 * 将结果反馈给模型 → 模型继续推理，直到产出最终答案或达到最大迭代次数。
 * </p>
 *
 * <pre>
 * 回复生命周期：
 *   onReplyStart → [用户消息入上下文]
 *     → onModelCall → [LLM 调用] → onModelCallEnd
 *       → onToolCall → [工具执行] → onToolResult  (循环)
 *     → [最终答案]
 *   → onReplyEnd
 * </pre>
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 智能体唯一标识 */
    private final String id;

    /** 智能体名称 */
    private final String name;

    /** 系统提示词（支持动态更新，如团队模式下注入团队工具描述） */
    private String systemPrompt;

    /** 中间件链 */
    private final MiddlewareChain middlewareChain;

    /** 聊天模型客户端 */
    private final ChatModel chatModel;

    /** MCP 客户端 */
    private final MCPClient mcpClient;

    /** 凭证提供者 */
    private final CredentialProvider credentialProvider;

    /** 权限引擎 */
    private final PermissionEngine permissionEngine;

    /** 工作空间管理器 */
    private final WorkspaceManager workspaceManager;

    /** 对话上下文（消息历史） */
    private final List<Msg> context;

    /** 智能上下文管理器（可选） */
    private com.demo.agentscope.context.ContextManager contextManager;

    /** 系统提示词生成器（可选；由 main 装配，支持运行期 regenerateSystemPrompt） */
    private SystemPromptGenerator systemPromptGenerator;

    /** 当前股票工具开关状态（由 main 装配，regenerateSystemPrompt 时使用） */
    private boolean stockToolsEnabled;

    /** Token 预算中间件引用（可选；由 main 装配用于运行期注入告警与查询用量） */
    private com.demo.agentscope.middleware.ReplyBudgetControlMiddleware replyBudgetMiddleware;

    /** 智能体状态（等价于 AgentState） */
    private final Map<String, Object> agentState;

    /** 提供商名称 */
    private final String providerName;

    /** 最大工具调用迭代次数 */
    private int maxIterations;

    /** 运行时限制引用（迭代/token 预算告警读取此实例） */
    private com.demo.agentscope.config.AgentLimits limits;

    /** 进度跟踪器（每次 reply 时按当前 verbosity 重建） */
    private AgentProgressTracker progressTracker;

    /**
     * 构造智能体。
     */
    public Agent(String name, String systemPrompt,
                 ChatModel chatModel, MCPClient mcpClient,
                 CredentialProvider credentialProvider,
                 PermissionEngine permissionEngine,
                 WorkspaceManager workspaceManager,
                 String providerName) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.middlewareChain = new MiddlewareChain();
        this.chatModel = chatModel;
        this.mcpClient = mcpClient;
        this.credentialProvider = credentialProvider;
        this.permissionEngine = permissionEngine;
        this.workspaceManager = workspaceManager;
        this.context = new ArrayList<>();
        this.agentState = new HashMap<>();
        this.providerName = providerName;
        this.maxIterations = 50;

        log.info("智能体已创建: id={}, name={}", id, name);
    }

    // ==================== 核心方法：reply ====================

    /**
     * 处理用户输入并返回回复。
     * <p>
     * 主入口方法，执行完整的工具调用循环：
     * <ol>
     *   <li>创建 AgentContext，触发 onReplyStart</li>
     *   <li>将用户消息添加到上下文</li>
     *   <li>构建消息列表，调用 LLM</li>
     *   <li>如果响应包含工具调用 → 执行工具，添加结果到上下文，继续循环</li>
     *   <li>如果响应无工具调用 → 这就是最终答案，跳出循环</li>
     *   <li>触发 onReplyEnd 并返回最终消息</li>
     * </ol>
     * </p>
     *
     * @param userInput 用户输入文本
     * @return 智能体最终回复消息
     */
    public Msg reply(String userInput) {
        log.info("智能体 [{}] 收到用户输入: {}", name, userInput);

        // 进度跟踪：每次回复都按当前 verbosity 重建跟踪器，使 REPL 的 verbosity 调整立即生效
        this.progressTracker = new AgentProgressTracker(name, VerbosityLevel.fromEnv());
        progressTracker.onReplyStart(userInput);
        long replyStartTime = System.currentTimeMillis();

        // 创建智能体上下文
        AgentContext ctx = new AgentContext(id, UUID.randomUUID().toString(), "default");
        ctx.setReplyStartTime(replyStartTime);
        ctx.setAttribute("userInput", userInput);

        // 触发 onReplyStart
        middlewareChain.fireReplyStart(ctx);

        // 添加用户消息到上下文
        Msg userMsg = new Msg(UUID.randomUUID().toString(), "user",
                List.of(new ContentBlock.TextBlock(userInput)));
        context.add(userMsg);

        // 事件流
        EventStream eventStream = new EventStream(id);
        eventStream.emit(AgentEvent.replyStart(id));

        Msg finalResponse = null;

        try {
            // 工具调用循环
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                log.debug("智能体 [{}] 第 {} 次迭代", name, iteration + 1);

                // 进度跟踪：模型调用开始
                progressTracker.onModelCallStart();

                // 构建发送给模型的消息列表
                List<Msg> messages = buildMessages(iteration);

                // 上下文压缩:工具密集对话时,把早期 tool_call.arguments / tool_result.content
                // 替换为 stub,避免 8K+ 字符的 Python 脚本被每轮重发(参考 Claude Code microCompact)
                // 注意:必须在 buildMessages() 之后调用 —— messages 是 context 的别名视图,
                // 修改 context 内的 block 会被下一轮 buildMessages() 反映到请求体
                int compactedCount = com.demo.agentscope.context.MicroCompactor.compactIfNeeded(
                        context,
                        contextManager != null ? contextManager.getMicroCompactorKeepRecent() : 5,
                        contextManager != null ? contextManager.getMicroCompactorTriggerToolCount() : 12);

                // 获取可用工具列表
                List<MCPClient.ToolInfo> tools = mcpClient.listTools();

                // 触发 onModelCall
                Msg requestMsg = new Msg(UUID.randomUUID().toString(), "user",
                        List.of(new ContentBlock.TextBlock("[模型调用请求]")));
                middlewareChain.fireModelCall(ctx, requestMsg);

                // 调用 LLM
                long modelStartTime = System.currentTimeMillis();
                Msg response = chatModel.chat(providerName, messages, tools);

                // 进度跟踪：模型调用完成
                Msg.TokenUsage usage = response.getUsage();
                if (usage != null) {
                    progressTracker.onModelCallComplete(usage.getPromptTokens(), usage.getCompletionTokens());
                    if (log.isInfoEnabled()) {
                        log.info("[compact] turn={} promptTokens={} completionTokens={} toolCallsSoFar={} compactedCalls={} estimatedTokens={}",
                                iteration + 1, usage.getPromptTokens(), usage.getCompletionTokens(),
                                countToolCallsSoFar(context), compactedCount, Msg.sumEstimatedTokens(messages));
                    }
                }

                // 触发 onModelCallEnd
                middlewareChain.fireModelCallEnd(ctx, response);

                // 将助手响应添加到上下文
                context.add(response);

                // 检查是否有工具调用
                if (!response.hasToolCalls()) {
                    // 无工具调用，这是最终答案
                    finalResponse = response;
                    log.debug("智能体 [{}] 产出最终答案", name);
                    break;
                }

                // 处理工具调用
                for (ContentBlock.ToolCallBlock toolCall : response.getToolCalls()) {
                    log.info("智能体 [{}] 调用工具: {}", name, toolCall.getName());

                    // 进度跟踪：工具调用
                    progressTracker.onToolCall(toolCall.getName());

                    // 触发 onToolCall
                    middlewareChain.fireToolCall(ctx, toolCall);

                    // 权限检查
                    Map<String, Object> toolArgs = parseToolArguments(toolCall.getArguments());
                    var decision = permissionEngine.check(toolCall.getName(), toolArgs);
                    if (decision.isDenied()) {
                        handlePermissionDenied(ctx, toolCall, decision.getReason());
                        continue;
                    }

                    // 执行工具
                    long toolStartTime = System.currentTimeMillis();
                    MCPClient.ToolResult toolResult = mcpClient.executeTool(toolCall.getName(), toolArgs);
                    long toolDuration = System.currentTimeMillis() - toolStartTime;

                    // 进度跟踪：工具调用完成
                    progressTracker.onToolCallComplete(toolCall.getName(), toolResult.isSuccess(), toolDuration);

                    // 工具结果(MCPClient 内 ToolResultSummarizer 已统一对超长输出做摘要)
                    String resultContent = toolResult.isSuccess() ? toolResult.getOutput() : toolResult.getError();

                    // 构建工具结果消息
                    ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
                            toolCall.getId(), resultContent, !toolResult.isSuccess());
                    Msg toolResultMsg = buildToolResultMsg(resultBlock);
                    context.add(toolResultMsg);

                    // 触发 onToolResult
                    middlewareChain.fireToolResult(ctx, resultBlock);

                    // 发射事件
                    eventStream.emit(AgentEvent.toolResult(id, toolCall.getName(), resultContent));
                }
            }

            // 如果达到最大迭代次数仍未获得最终答案
            if (finalResponse == null) {
                int toolCallCount = countToolCallsSoFar(context);
                String warning = String.format(
                        "已达到最大迭代次数上限（%d 轮）。本次对话执行了 %d 次工具调用，由于预算耗尽无法继续。" +
                        "建议：①重启对话并简化任务；②拆分为多个子任务；③如需继续当前任务，请基于以上进度重新提问。",
                        maxIterations, toolCallCount);
                log.warn("智能体 [{}]: {}", name, warning);
                finalResponse = Msg.assistantText(warning);
                context.add(finalResponse);
            }
        } catch (Exception e) {
            log.error("智能体 [{}] 回复过程异常", name, e);
            progressTracker.onError(e.getMessage());
            finalResponse = Msg.assistantText("处理过程中发生错误: " + e.getMessage());
            eventStream.emit(AgentEvent.error(id, e.getMessage()));
        }

        // 将上下文传递给中间件，供 ContextCompressionMiddleware 使用
        ctx.setAttribute("contextMessages", context);

        // 触发 onReplyEnd
        middlewareChain.fireReplyEnd(ctx, eventStream);
        eventStream.emit(AgentEvent.replyEnd(id));

        // 进度跟踪：回复完成
        long replyDuration = System.currentTimeMillis() - replyStartTime;
        progressTracker.onReplyComplete(replyDuration);

        // 更新状态
        agentState.put("lastReplyTime", Instant.now().toString());
        agentState.put("totalIterations", (int) agentState.getOrDefault("totalIterations", 0) + 1);

        return finalResponse;
    }

    /**
     * 流式回复的同步包装：调用 {@link #replyStream}，完成后返回最终 assistant 消息。
     *
     * @param userInput 用户输入文本
     * @return 最终 assistant 回复消息；若 {@link #replyStream} 异常结束未追加 assistant，返回携带错误说明的 assistant Msg（不抛异常）
     */
    public Msg replySyncFromStream(String userInput) {
        int contextSizeBefore = context.size();
        replyStream(userInput);
        // 不扫描历史：避免误取前一轮残留的 assistant 消息
        for (int i = context.size() - 1; i >= contextSizeBefore; i--) {
            Msg m = context.get(i);
            if ("assistant".equals(m.getRole())) {
                return m;
            }
        }
        log.warn("replyStream 未追加 assistant 消息: before={}, after={}",
                contextSizeBefore, context.size());
        return Msg.assistantText(
                "[内部错误] replyStream 未产生 assistant 回复。可能原因：模型调用失败、迭代预算耗尽未输出。");
    }

    /**
     * 流式回复，边执行边发射事件。
     * <p>
     * 调用真实的 SSE 流式 API（{@link ChatModel#chatStream}），token 级实时回显。
     * 流式路径下，TEXT_DELTA / THINKING_DELTA 实时转发给 {@link AgentProgressTracker}
     * 进行控制台渲染；流结束后聚合为完整 assistant Msg 加入上下文。
     * </p>
     *
     * @param userInput 用户输入文本
     * @return 事件流（已消费完毕，包含完整的 delta / tool_call / model_call_end 事件）
     */
    public EventStream replyStream(String userInput) {
        log.info("智能体 [{}] 流式回复开始", name);

        // 进度跟踪：每次回复都按当前 verbosity 重建跟踪器
        this.progressTracker = new AgentProgressTracker(name, VerbosityLevel.fromEnv());
        progressTracker.onReplyStart(userInput);
        long replyStartTime = System.currentTimeMillis();

        // 创建上下文和事件流
        AgentContext ctx = new AgentContext(id, UUID.randomUUID().toString(), "default");
        ctx.setReplyStartTime(replyStartTime);
        EventStream eventStream = new EventStream(id);

        // 触发 onReplyStart
        middlewareChain.fireReplyStart(ctx);
        eventStream.emit(AgentEvent.replyStart(id));

        // 添加用户消息
        Msg userMsg = new Msg(UUID.randomUUID().toString(), "user",
                List.of(new ContentBlock.TextBlock(userInput)));
        context.add(userMsg);

        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                log.debug("智能体 [{}] 流式第 {} 次迭代", name, iteration + 1);

                // 进度跟踪：模型调用开始
                progressTracker.onModelCallStart();

                List<Msg> messages = buildMessages(iteration);

                // 上下文压缩（与 reply() 一致）
                int compactedCount = com.demo.agentscope.context.MicroCompactor.compactIfNeeded(
                        context,
                        contextManager != null ? contextManager.getMicroCompactorKeepRecent() : 5,
                        contextManager != null ? contextManager.getMicroCompactorTriggerToolCount() : 12);

                List<MCPClient.ToolInfo> tools = mcpClient.listTools();

                middlewareChain.fireModelCall(ctx, new Msg(UUID.randomUUID().toString(), "user",
                        List.of(new ContentBlock.TextBlock("[流式模型调用]"))));

                // 流式调用：通过 sink 在 SSE 读取过程中实时回显 + 累积，避免两阶段回放
                StreamSinkSink sink = new StreamSinkSink(eventStream, progressTracker);
                chatModel.chatStream(providerName, messages, tools, id, sink);
                Msg response = sink.buildResponse();

                middlewareChain.fireModelCallEnd(ctx, response);
                context.add(response);

                if (log.isInfoEnabled()) {
                    Msg.TokenUsage usage = response.getUsage();
                    log.info("[stream-compact] turn={} promptTokens={} completionTokens={} toolCalls={} compactedCalls={}",
                            iteration + 1,
                            usage != null ? usage.getPromptTokens() : 0,
                            usage != null ? usage.getCompletionTokens() : 0,
                            response.getToolCalls().size(), compactedCount);
                }

                // 输出截断告警：finish_reason=length 表示模型输出达到 max_tokens 上限，
                // 上一条 assistant 的 tool_call 参数可能不完整（如 write_file 的 content 被截断）。
                // 注入一条 user 角色告警，让模型在下一轮主动重发被截断的调用、避免基于残缺参数继续推进。
                if (sink.isOutputTruncated()) {
                    int mt = sink.getTruncatedMaxTokens();
                    String warnText = String.format(
                            "[系统提示] 上一轮输出达到 max_tokens%s 上限被强制截断。" +
                            "刚才发出的 tool_call 参数可能不完整（例如 write_file 的 content 末段缺失、" +
                            "JSON 提前闭合、字段被切半）。请检查上一次工具执行结果：若参数确实残缺，" +
                            "重新发起该工具调用并确保参数完整；若结果可用，请继续推进任务。",
                            mt > 0 ? "（" + mt + "）" : "");
                    Msg truncWarn = new Msg(UUID.randomUUID().toString(), "user",
                            List.of(new ContentBlock.TextBlock(warnText)));
                    context.add(truncWarn);
                    log.warn("[truncation] 迭代 {} 检测到 finish_reason=length，已注入截断告警；本次 toolCalls={}",
                            iteration + 1, response.getToolCalls().size());
                }

                if (!response.hasToolCalls()) {
                    // 流式回复结束换行
                    progressTracker.onStreamEnd();
                    break;
                }

                // 执行工具
                for (ContentBlock.ToolCallBlock toolCall : response.getToolCalls()) {
                    log.info("智能体 [{}] 调用工具: {}", name, toolCall.getName());
                    progressTracker.onToolCall(toolCall.getName());
                    middlewareChain.fireToolCall(ctx, toolCall);

                    // 权限检查（与 reply() 一致）
                    Map<String, Object> toolArgs = parseToolArguments(toolCall.getArguments());
                    var decision = permissionEngine.check(toolCall.getName(), toolArgs);
                    if (decision.isDenied()) {
                        handlePermissionDenied(ctx, toolCall, decision.getReason());
                        continue;
                    }

                    long toolStartTime = System.currentTimeMillis();
                    MCPClient.ToolResult toolResult = mcpClient.executeTool(toolCall.getName(), toolArgs);
                    long toolDuration = System.currentTimeMillis() - toolStartTime;

                    progressTracker.onToolCallComplete(toolCall.getName(), toolResult.isSuccess(), toolDuration);

                    String resultContent = toolResult.isSuccess() ? toolResult.getOutput() : toolResult.getError();
                    ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
                            toolCall.getId(), resultContent, !toolResult.isSuccess());
                    Msg toolResultMsg = buildToolResultMsg(resultBlock);
                    context.add(toolResultMsg);

                    middlewareChain.fireToolResult(ctx, resultBlock);
                    eventStream.emit(AgentEvent.toolResult(id, toolCall.getName(), resultContent));
                }
            }
        } catch (Exception e) {
            log.error("智能体 [{}] 流式回复异常", name, e);
            progressTracker.onError(e.getMessage());
            eventStream.emit(AgentEvent.error(id, e.getMessage()));
        }

        // 将上下文传递给中间件
        ctx.setAttribute("contextMessages", context);

        middlewareChain.fireReplyEnd(ctx, eventStream);
        eventStream.emit(AgentEvent.replyEnd(id));

        long replyDuration = System.currentTimeMillis() - replyStartTime;
        progressTracker.onReplyComplete(replyDuration);

        // 更新状态
        agentState.put("lastReplyTime", Instant.now().toString());
        agentState.put("totalIterations", (int) agentState.getOrDefault("totalIterations", 0) + 1);

        return eventStream;
    }

    /**
     * 流式 SSE sink 的可变累积器：在 ChatModel 读取 SSE 流的过程中，
     * 每收到一个事件就同时转发到外层 eventStream、推送 progressTracker 实时回显、
     * 并累积文本/思考/工具调用，最后通过 {@link #buildResponse()} 构造 Msg。
     * <p>
     * 相比此前"先全部读完再回放"的 drainModelStream，此方案确保 token 级实时渲染。
     * </p>
     */
    private static final class StreamSinkSink implements com.demo.agentscope.model.StreamSink {
        private final EventStream outer;
        private final AgentProgressTracker tracker;
        private final StringBuilder textBuf = new StringBuilder();
        private final StringBuilder reasoningBuf = new StringBuilder();
        private final List<ContentBlock.ToolCallBlock> toolCalls = new ArrayList<>();
        private int promptTokens = 0;
        private int completionTokens = 0;
        /** 本次模型调用是否因 finish_reason=length 被截断。*/
        private boolean outputTruncated = false;
        /** 截断时的 max_tokens 上限（用于告警文案）。*/
        private int truncatedMaxTokens = 0;

        StreamSinkSink(EventStream outer, AgentProgressTracker tracker) {
            this.outer = outer;
            this.tracker = tracker;
        }

        @Override
        public void onEvent(AgentEvent e) {
            switch (e.getType()) {
                case TEXT_DELTA -> {
                    String delta = e.getData("delta", String.class);
                    if (delta != null && !delta.isEmpty()) {
                        textBuf.append(delta);
                        tracker.onTextDelta(delta);
                        outer.emit(e);
                    }
                }
                case THINKING_DELTA -> {
                    String delta = e.getData("delta", String.class);
                    if (delta != null && !delta.isEmpty()) {
                        reasoningBuf.append(delta);
                        tracker.onThinkingDelta(delta);
                        outer.emit(e);
                    }
                }
                case TOOL_CALL -> {
                    outer.emit(e);
                    String name = e.getData("toolName", String.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> argsMap = e.getData("arguments", Map.class);
                    if (name != null && argsMap != null) {
                        Object raw = argsMap.get("raw");
                        Object id = argsMap.get("id");
                        String argsStr = raw instanceof String s ? s : "";
                        String callId = id instanceof String idStr ? idStr : "call_" + UUID.randomUUID();
                        toolCalls.add(new ContentBlock.ToolCallBlock(callId, name, argsStr));
                    }
                }
                case MODEL_CALL_END -> {
                    outer.emit(e);
                    Integer pt = e.getData("promptTokens", Integer.class);
                    Integer ct = e.getData("completionTokens", Integer.class);
                    if (pt != null) promptTokens = pt;
                    if (ct != null) completionTokens = ct;
                }
                case OUTPUT_TRUNCATED -> {
                    outputTruncated = true;
                    Integer mt = e.getData("maxTokens", Integer.class);
                    if (mt != null) truncatedMaxTokens = mt;
                    outer.emit(e);
                }
                default -> outer.emit(e);
            }
        }

        /** 本次流是否被 max_tokens 截断。*/
        boolean isOutputTruncated() {
            return outputTruncated;
        }

        int getTruncatedMaxTokens() {
            return truncatedMaxTokens;
        }

        Msg buildResponse() {
            List<ContentBlock> blocks = new ArrayList<>();
            if (!textBuf.isEmpty()) {
                blocks.add(new ContentBlock.TextBlock(textBuf.toString()));
            }
            if (!reasoningBuf.isEmpty()) {
                blocks.add(new ContentBlock.ThinkingBlock(reasoningBuf.toString()));
            }
            blocks.addAll(toolCalls);

            if (promptTokens > 0 || completionTokens > 0) {
                tracker.onModelCallComplete(promptTokens, completionTokens);
            }
            Msg.TokenUsage usage = new Msg.TokenUsage(promptTokens, completionTokens);
            return new Msg(UUID.randomUUID().toString(), "assistant", blocks, usage, Instant.now(), null);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建发送给模型的消息列表（包含系统提示词和上下文历史）。
     * <p>
     * 当接近迭代或 token 预算上限时，会在 system 之后插入一条 user 角色的预算告警，
     * 让模型感知紧迫感、主动收敛。告警消息只注入到本次调用的返回列表，
     * 不写入 context 字段，因此不污染对话历史与短期记忆。
     * </p>
     */
    private List<Msg> buildMessages(int iteration) {
        List<Msg> messages;
        // 如果启用了智能上下文管理器，使用它来构建上下文
        if (contextManager != null) {
            String lastUserQuery = getLastUserQuery();
            messages = contextManager.buildContext(lastUserQuery, context);
        } else {
            // 否则使用简单的上下文构建方式
            messages = new ArrayList<>();
            // 系统提示词
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                Msg systemMsg = new Msg(UUID.randomUUID().toString(), "system",
                        List.of(new ContentBlock.TextBlock(systemPrompt)));
                messages.add(systemMsg);
            }
            // 对话历史
            messages.addAll(context);
        }

        // 预算告警注入位置：system 之后、其它消息之前
        Msg budgetWarning = buildBudgetWarningIfAny(iteration);
        if (budgetWarning != null) {
            int insertAt = findFirstNonSystemIndex(messages) ;
            messages.add(Math.max(insertAt, 0), budgetWarning);
        }
        return messages;
    }

    /**
     * 找到第一条非 system 角色消息的索引（用于把告警插入到 system 之后）。
     * 若列表为空或全是 system，返回 size（即追加到末尾）。
     */
    private int findFirstNonSystemIndex(List<Msg> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!"system".equals(messages.get(i).getRole())) {
                return i;
            }
        }
        return messages.size();
    }

    /**
     * 检查是否需要注入预算告警（迭代 + token 双维度）。
     *
     * @return user 角色告警 Msg，或 null 表示无需告警
     */
    private Msg buildBudgetWarningIfAny(int iteration) {
        if (limits == null) {
            return null;
        }
        String iterationWarning = null;
        int warnRemaining = limits.getIterationWarnRemaining();
        if (warnRemaining > 0 && iteration >= 0) {
            int remaining = maxIterations - iteration - 1;
            if (remaining <= warnRemaining) {
                iterationWarning = String.format(
                        "迭代预算紧张：剩余 %d/%d 轮（告警阈值 %d）。请评估是否能在剩余轮次内收敛；" +
                        "若不能，请优先产出阶段性结论与未完成项，让用户决定下一步。",
                        remaining, maxIterations, warnRemaining);
            }
        }

        String tokenWarning = null;
        if (replyBudgetMiddleware != null) {
            int budgetUsed = replyBudgetMiddleware.getUsedTokens();
            int budgetLimit = limits.getReplyBudgetTokens();
            int warnPercent = limits.getTokenBudgetWarnPercent();
            if (budgetLimit > 0 && warnPercent > 0 && budgetUsed * 100 >= (long) budgetLimit * warnPercent) {
                tokenWarning = String.format(
                        "Token 预算紧张：已用 %d/%d（%.0f%%）。请尽快收敛输出，" +
                        "优先完成核心结论，省略冗余探索。",
                        budgetUsed, budgetLimit, budgetUsed * 100.0 / budgetLimit);
            }
        }

        if (iterationWarning == null && tokenWarning == null) {
            return null;
        }
        String combined = "[系统提示] " +
                (iterationWarning != null && tokenWarning != null
                        ? iterationWarning + " " + tokenWarning
                        : (iterationWarning != null ? iterationWarning : tokenWarning));
        return new Msg(UUID.randomUUID().toString(), "user",
                List.of(new ContentBlock.TextBlock(combined)));
    }

    /**
     * 获取最后一条用户消息。
     */
    private String getLastUserQuery() {
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if ("user".equals(msg.getRole())) {
                return msg.getTextContent();
            }
        }
        return null;
    }

    /**
     * 解析工具调用参数 JSON 字符串为 Map。
     */
    private Map<String, Object> parseToolArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析工具参数失败: {}", argumentsJson, e);
            return Map.of();
        }
    }

    /**
     * 统计 context 中累计的 ToolCallBlock 数量(用于压缩观测日志)。
     */
    private int countToolCallsSoFar(List<Msg> ctx) {
        int count = 0;
        for (Msg msg : ctx) {
            count += msg.getToolCalls().size();
        }
        return count;
    }

    /**
     * 构建工具结果消息。
     */
    private Msg buildToolResultMsg(ContentBlock.ToolResultBlock resultBlock) {
        return new Msg(UUID.randomUUID().toString(), "tool", List.of(resultBlock));
    }

    /**
     * 处理权限拒绝：构造错误 tool result，并经中间件 + 事件流 + 进度跟踪反馈给模型与用户。
     */
    private void handlePermissionDenied(AgentContext ctx,
                                        ContentBlock.ToolCallBlock toolCall,
                                        String reason) {
        log.warn("工具 [{}] 被权限引擎拒绝: {}", toolCall.getName(), reason);
        String denyMsg = String.format(
                "权限拒绝: 工具 %s 被禁止执行。原因：%s。\n" +
                "你可以：①调整参数规避命中规则；②换用其他允许的工具；" +
                "③如认为该能力必需，请停止并向用户说明。",
                toolCall.getName(), reason != null ? reason : "未指定");
        ContentBlock.ToolResultBlock deniedResult = new ContentBlock.ToolResultBlock(
                toolCall.getId(), denyMsg, true);
        Msg toolResultMsg = buildToolResultMsg(deniedResult);
        context.add(toolResultMsg);
        middlewareChain.fireToolResult(ctx, deniedResult);
        eventStream.emit(AgentEvent.toolResult(id, toolCall.getName(), "权限拒绝"));
        progressTracker.onToolCallComplete(toolCall.getName(), false, 0);
    }

    // ==================== 状态管理 ====================

    /**
     * 获取智能体状态。
     *
     * @return 状态映射
     */
    public Map<String, Object> getState() {
        return Collections.unmodifiableMap(agentState);
    }

    /**
     * 重置智能体，清空上下文和状态。
     */
    public void reset() {
        context.clear();
        agentState.clear();
        log.info("智能体 [{}] 已重置", name);
    }

    /**
     * 关闭智能体，释放资源。
     */
    public void shutdown() {
        context.clear();
        agentState.clear();
        log.info("智能体 [{}] 已关闭", name);
    }

    // ==================== Getter ====================

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 追加内容到系统提示词。
     * <p>
     * 用于团队模式下动态注入团队工具描述，使领导者感知自身具备团队管理能力。
     * </p>
     *
     * @param additional 要追加的提示词内容
     */
    public void appendToSystemPrompt(String additional) {
        if (additional == null || additional.isBlank()) {
            return;
        }
        if (this.systemPrompt == null || this.systemPrompt.isBlank()) {
            this.systemPrompt = additional;
        } else {
            this.systemPrompt = this.systemPrompt + "\n\n" + additional;
        }
        log.info("智能体 [{}] 系统提示词已更新（追加 {} 字符）", name, additional.length());
    }

    public MiddlewareChain getMiddlewareChain() {
        return middlewareChain;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public MCPClient getMcpClient() {
        return mcpClient;
    }

    public CredentialProvider getCredentialProvider() {
        return credentialProvider;
    }

    public PermissionEngine getPermissionEngine() {
        return permissionEngine;
    }

    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    public List<Msg> getContext() {
        return Collections.unmodifiableList(context);
    }

    public String getProviderName() {
        return providerName;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations 必须为正");
        }
        this.maxIterations = maxIterations;
    }

    /**
     * 注入运行时限制引用。
     * <p>
     * 由主应用装配时调用。{@code buildMessages} 会从中读取
     * {@code iterationWarnRemaining} / {@code replyBudgetTokens} /
     * {@code tokenBudgetWarnPercent}，决定是否注入预算告警。
     * </p>
     */
    public void setLimits(com.demo.agentscope.config.AgentLimits limits) {
        this.limits = limits;
    }

    /**
     * 设置智能上下文管理器。
     */
    public void setContextManager(com.demo.agentscope.context.ContextManager contextManager) {
        this.contextManager = contextManager;
        log.info("智能体 [{}] 已启用智能上下文管理器", name);
    }

    /**
     * 获取智能上下文管理器。
     */
    public com.demo.agentscope.context.ContextManager getContextManager() {
        return contextManager;
    }

    // ==================== 运行时配置 ====================

    /**
     * 注入系统提示词生成器与股票工具开关状态。
     * <p>
     * 由主应用装配时调用，使后续 {@link #regenerateSystemPrompt} 能在 REPL /config set 后
     * 基于最新 {@link com.demo.agentscope.config.AgentLimits} 重建系统提示词。
     * </p>
     */
    public void setSystemPromptGenerator(SystemPromptGenerator generator, boolean stockEnabled) {
        this.systemPromptGenerator = generator;
        this.stockToolsEnabled = stockEnabled;
    }

    /**
     * 重建系统提示词（由 REPL /config set 触发，经主应用透传）。
     *
     * @param stockEnabled 当前股票工具开关
     * @param limits       当前运行时限制
     */
    public void regenerateSystemPrompt(boolean stockEnabled,
                                        com.demo.agentscope.config.AgentLimits limits) {
        this.stockToolsEnabled = stockEnabled;
        if (systemPromptGenerator != null && limits != null) {
            String refreshed = systemPromptGenerator.generate(stockEnabled, limits);
            this.systemPrompt = refreshed;
            if (contextManager != null) {
                contextManager.setSystemPrompt(refreshed);
            }
            log.info("智能体 [{}] 系统提示词已重建（stock={}, maxIterations={}）",
                    name, stockEnabled, limits.getMaxIterations());
        } else {
            log.debug("智能体 [{}] 未注入 SystemPromptGenerator，跳过 regenerateSystemPrompt", name);
        }
    }

    /**
     * 注入 Token 预算中间件引用（供 buildMessages 在接近预算时注入告警）。
     */
    public void setReplyBudgetMiddleware(
            com.demo.agentscope.middleware.ReplyBudgetControlMiddleware middleware) {
        this.replyBudgetMiddleware = middleware;
    }

    public com.demo.agentscope.middleware.ReplyBudgetControlMiddleware getReplyBudgetMiddleware() {
        return replyBudgetMiddleware;
    }

    /**
     * 透传更新工具结果摘要阈值（由 REPL /config set 触发，经 MCPClient 传播）。
     */
    public void setToolResultSummaryLimits(int threshold, int maxLength) {
        if (mcpClient == null) {
            return;
        }
        mcpClient.updateToolResultSummarizerLimits(threshold, maxLength);
    }
}
