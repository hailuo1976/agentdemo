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

    /** 智能体状态（等价于 AgentState） */
    private final Map<String, Object> agentState;

    /** 提供商名称 */
    private final String providerName;

    /** 最大工具调用迭代次数 */
    private int maxIterations;

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
                List<Msg> messages = buildMessages();

                // 上下文压缩:工具密集对话时,把早期 tool_call.arguments / tool_result.content
                // 替换为 stub,避免 8K+ 字符的 Python 脚本被每轮重发(参考 Claude Code microCompact)
                // 注意:必须在 buildMessages() 之后调用 —— messages 是 context 的别名视图,
                // 修改 context 内的 block 会被下一轮 buildMessages() 反映到请求体
                int compactedCount = com.demo.agentscope.context.MicroCompactor.compactIfNeeded(context);

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
                    if (decision == com.demo.agentscope.permission.PermissionDecision.DENY) {
                        log.warn("工具 [{}] 被权限引擎拒绝", toolCall.getName());
                        ContentBlock.ToolResultBlock deniedResult = new ContentBlock.ToolResultBlock(
                                toolCall.getId(), "权限拒绝: 工具 " + toolCall.getName() + " 被禁止执行", true);
                        Msg toolResultMsg = buildToolResultMsg(deniedResult);
                        context.add(toolResultMsg);
                        middlewareChain.fireToolResult(ctx, deniedResult);
                        eventStream.emit(AgentEvent.toolResult(id, toolCall.getName(), "权限拒绝"));
                        progressTracker.onToolCallComplete(toolCall.getName(), false, 0);
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
                String warning = "已达到最大迭代次数 (" + maxIterations + ")，无法完成所有工具调用";
                log.warn("智能体 [{}]: {}", name, warning);
                finalResponse = new Msg(UUID.randomUUID().toString(), "assistant",
                        List.of(new ContentBlock.TextBlock(warning)));
                context.add(finalResponse);
            }
        } catch (Exception e) {
            log.error("智能体 [{}] 回复过程异常", name, e);
            progressTracker.onError(e.getMessage());
            finalResponse = new Msg(UUID.randomUUID().toString(), "assistant",
                    List.of(new ContentBlock.TextBlock("处理过程中发生错误: " + e.getMessage())));
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
     * 流式回复，边执行边发射事件。
     *
     * @param userInput 用户输入文本
     * @return 事件流
     */
    public EventStream replyStream(String userInput) {
        log.info("智能体 [{}] 流式回复开始", name);

        // 创建上下文和事件流
        AgentContext ctx = new AgentContext(id, UUID.randomUUID().toString(), "default");
        ctx.setReplyStartTime(System.currentTimeMillis());
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
                List<Msg> messages = buildMessages();
                List<MCPClient.ToolInfo> tools = mcpClient.listTools();

                middlewareChain.fireModelCall(ctx, new Msg(UUID.randomUUID().toString(), "user",
                        List.of(new ContentBlock.TextBlock("[流式模型调用]"))));

                Msg response = chatModel.chat(providerName, messages, tools);
                middlewareChain.fireModelCallEnd(ctx, response);
                context.add(response);

                // 发射文本和思考事件
                for (ContentBlock block : response.getContent()) {
                    if (block instanceof ContentBlock.TextBlock textBlock) {
                        eventStream.emit(AgentEvent.textBlock(id, textBlock.getText()));
                    } else if (block instanceof ContentBlock.ThinkingBlock thinkingBlock) {
                        eventStream.emit(AgentEvent.thinkingBlock(id, thinkingBlock.getText()));
                    } else if (block instanceof ContentBlock.ToolCallBlock toolCallBlock) {
                        Map<String, Object> args = parseToolArguments(toolCallBlock.getArguments());
                        eventStream.emit(AgentEvent.toolCall(id, toolCallBlock.getName(), args));
                    }
                }

                if (!response.hasToolCalls()) {
                    break;
                }

                // 执行工具
                for (ContentBlock.ToolCallBlock toolCall : response.getToolCalls()) {
                    middlewareChain.fireToolCall(ctx, toolCall);

                    Map<String, Object> toolArgs = parseToolArguments(toolCall.getArguments());
                    MCPClient.ToolResult toolResult = mcpClient.executeTool(toolCall.getName(), toolArgs);

                    ContentBlock.ToolResultBlock resultBlock = toolResult.toToolResultBlock(toolCall.getId());
                    Msg toolResultMsg = buildToolResultMsg(resultBlock);
                    context.add(toolResultMsg);

                    middlewareChain.fireToolResult(ctx, resultBlock);
                    String resultContent = toolResult.isSuccess() ? toolResult.getOutput() : toolResult.getError();
                    eventStream.emit(AgentEvent.toolResult(id, toolCall.getName(), resultContent));
                }
            }
        } catch (Exception e) {
            log.error("智能体 [{}] 流式回复异常", name, e);
            eventStream.emit(AgentEvent.error(id, e.getMessage()));
        }

        middlewareChain.fireReplyEnd(ctx, eventStream);
        eventStream.emit(AgentEvent.replyEnd(id));

        return eventStream;
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建发送给模型的消息列表（包含系统提示词和上下文历史）。
     */
    private List<Msg> buildMessages() {
        // 如果启用了智能上下文管理器，使用它来构建上下文
        if (contextManager != null) {
            String lastUserQuery = getLastUserQuery();
            return contextManager.buildContext(lastUserQuery, context);
        }

        // 否则使用简单的上下文构建方式
        List<Msg> messages = new ArrayList<>();

        // 系统提示词
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            Msg systemMsg = new Msg(UUID.randomUUID().toString(), "system",
                    List.of(new ContentBlock.TextBlock(systemPrompt)));
            messages.add(systemMsg);
        }

        // 对话历史
        messages.addAll(context);
        return messages;
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
        this.maxIterations = maxIterations;
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
}
