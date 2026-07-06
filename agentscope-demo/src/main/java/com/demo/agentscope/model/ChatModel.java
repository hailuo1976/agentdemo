package com.demo.agentscope.model;

import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.event.AgentEvent;
import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * LLM 聊天模型客户端。
 * <p>
 * 封装 OpenAI 兼容的 chat/completions API 调用，将 HTTP 响应
 * 解析为 {@link Msg} 对象，内容以 {@link ContentBlock} 列表表示。
 * 支持文本回复、工具调用和思考链（reasoning）内容块的解析。
 * </p>
 */
public class ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ChatModel.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    /** max_tokens 默认值（与 AgentLimits 默认一致）。运行期通过 {@link #setMaxOutputTokens} 覆盖。 */
    private int maxOutputTokens = 8192;
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /** LLM HTTP 超时（秒）。默认值与 {@link com.demo.agentscope.config.AgentLimits} 保持一致。 */
    private long connectTimeoutSeconds = 30;
    private long readTimeoutSeconds = 300;  // 加长默认：覆盖模型推理静默期
    private long writeTimeoutSeconds = 30;

    /** LLM 调用遇到 SocketTimeoutException 时的最大重试次数（0 表示不重试）。 */
    private int maxRetries = 2;

    /** OkHttp 客户端（运行期可通过 {@link #rebuildHttpClient} 重建以应用新超时） */
    private OkHttpClient httpClient;

    /** 凭证提供者 */
    private final CredentialProvider credentialProvider;

    public ChatModel(CredentialProvider credentialProvider) {
        this.credentialProvider = credentialProvider;
        this.httpClient = buildHttpClient();
    }

    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /**
     * 应用 LLM HTTP 超时配置。若任一值发生变化，重建 OkHttp 客户端。
     * <p>
     * 由 {@code AgentScopeDemoApplication} 装配时从 {@link com.demo.agentscope.config.AgentLimits} 传入；
     * REPL {@code /config set llmReadTimeoutSeconds=N} 后会再次调用以即时生效。
     * </p>
     */
    public void setTimeouts(long connectSeconds, long readSeconds, long writeSeconds) {
        boolean changed = this.connectTimeoutSeconds != connectSeconds
                || this.readTimeoutSeconds != readSeconds
                || this.writeTimeoutSeconds != writeSeconds;
        this.connectTimeoutSeconds = connectSeconds;
        this.readTimeoutSeconds = readSeconds;
        this.writeTimeoutSeconds = writeSeconds;
        if (changed) {
            // 旧 client 的连接池和调度器需关闭；新请求会用新 client
            OkHttpClient old = this.httpClient;
            this.httpClient = buildHttpClient();
            if (old != null) {
                old.dispatcher().executorService().shutdown();
                old.connectionPool().evictAll();
            }
            log.info("LLM HTTP 超时已更新: connect={}s, read={}s, write={}s",
                    connectSeconds, readSeconds, writeSeconds);
        }
    }

    /** 设置 LLM 调用的最大重试次数（仅针对 SocketTimeoutException，且仅限未产生输出时）。 */
    public void setMaxRetries(int maxRetries) {
        if (maxRetries >= 0) {
            this.maxRetries = maxRetries;
        }
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * 设置单次 LLM 调用的 max_tokens 上限。
     * <p>
     * 由 {@code AgentScopeDemoApplication} 装配时从 {@link com.demo.agentscope.config.AgentLimits#getMaxOutputTokens()}
     * 传入；REPL {@code /config set maxOutputTokens=N} 后会再次调用以即时生效。
     * </p>
     */
    public void setMaxOutputTokens(int maxOutputTokens) {
        if (maxOutputTokens > 0) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * 发送聊天请求，返回模型响应消息。
     *
     * @param providerName 提供商名称（如 "openai"、"dashscope"）
     * @param messages     消息列表
     * @param tools        可用工具信息列表
     * @return 模型响应消息（包含文本/工具调用/思考内容块）
     */
    public Msg chat(String providerName, List<Msg> messages, List<MCPClient.ToolInfo> tools) {
        String apiKey = credentialProvider.getApiKey(providerName);
        String baseUrl = credentialProvider.getBaseUrl(providerName);
        String modelName = credentialProvider.getModelName(providerName);

        if (apiKey == null) {
            throw new IllegalStateException("提供商 [" + providerName + "] API Key 未配置");
        }
        if (baseUrl == null) {
            throw new IllegalStateException("提供商 [" + providerName + "] Base URL 未配置");
        }

        // 非流式调用：SocketTimeoutException 时整次重试（无外部副作用）
        int maxAttempts = Math.max(1, maxRetries + 1);
        IOException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ObjectNode requestBody = buildRequestBody(modelName, messages, tools);
                String url = baseUrl + "/chat/completions";

                if (attempt > 1) {
                    log.info("[chat] 重试 {}/{} (provider={}, model={})",
                            attempt - 1, maxRetries, providerName, modelName);
                } else {
                    log.debug("发送聊天请求: provider={}, model={}, url={}", providerName, modelName, url);
                }

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "无响应体";
                        if (response.code() >= 400 && response.code() < 500) {
                            log.warn("LLM 4xx 拒绝, messages 序列摘要:\n{}", summarizeMessagesForLog(messages));
                        }
                        throw new IOException("API 请求失败: status=" + response.code() + ", body=" + body);
                    }
                    String responseBody = response.body() != null ? response.body().string() : "{}";
                    return parseResponse(responseBody);
                }
            } catch (IOException e) {
                lastError = e;
                if (!isReadTimeout(e) || attempt >= maxAttempts) {
                    break;
                }
                // 指数退避：2s, 4s, 8s...
                backoffSleep(attempt);
            }
        }
        log.error("聊天请求异常: provider={}, model={}", providerName, modelName, lastError);
        return Msg.assistantText("请求失败: " + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    /** 判断异常链是否含 SocketTimeoutException（read timed out）。 */
    private static boolean isReadTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 指数退避：第 attempt 次重试前 sleep 2^attempt 秒。 */
    private static void backoffSleep(int attempt) {
        long sleepMillis = (1L << attempt) * 1000L;  // 2s, 4s, 8s...
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 发送聊天请求并自动处理工具调用响应。
     * <p>
     * 如果模型响应中包含工具调用，将其原样返回（由 Agent 层处理工具执行循环）。
     * </p>
     *
     * @param providerName 提供商名称
     * @param messages     消息列表
     * @param tools        可用工具信息列表
     * @return 模型响应消息（可能包含工具调用）
     */
    public Msg chatWithToolCalls(String providerName, List<Msg> messages, List<MCPClient.ToolInfo> tools) {
        return chat(providerName, messages, tools);
    }

    /**
     * 流式聊天请求（SSE，token 级实时返回）。
     * <p>
     * 同步阻塞读取 SSE 流，每解析出一个事件（TEXT_DELTA / THINKING_DELTA / TOOL_CALL /
     * MODEL_CALL_END）就立即通过 {@link StreamSink#onEvent(AgentEvent)} 上推到调用方。
     * 这避免了"先全部读完再回放"的两阶段模式，使控制台能真正 token 级渲染。
     * </p>
     *
     * @param providerName 提供商名称
     * @param messages     消息列表
     * @param tools        可用工具信息列表
     * @param agentId      智能体ID
     * @param sink         实时事件下沉（由 Agent 提供，负责转发到外层 EventStream 和进度跟踪器）
     */
    public void chatStream(String providerName, List<Msg> messages,
                           List<MCPClient.ToolInfo> tools, String agentId,
                           StreamSink sink) {
        String apiKey = credentialProvider.getApiKey(providerName);
        String baseUrl = credentialProvider.getBaseUrl(providerName);
        String modelName = credentialProvider.getModelName(providerName);

        if (apiKey == null) {
            sink.onEvent(AgentEvent.error(agentId, "提供商 [" + providerName + "] API Key 未配置"));
            sink.onEvent(AgentEvent.modelCallEnd(agentId, modelName, 0, 0));
            return;
        }

        ObjectNode requestBody = buildRequestBody(modelName, messages, tools);
        requestBody.put("stream", true);
        requestBody.putObject("stream_options").put("include_usage", true);

        String url = baseUrl + "/chat/completions";

        // 状态：每次重试前需重置（部分输出必须丢弃，避免重复/拼接错乱）
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new TreeMap<>();
        StringBuilder textBuffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        int[] usage = {0, 0};
        // finish_reason[length] 检测：流结束后通知调用方 tool_call 参数可能被截断
        boolean[] lengthTruncated = {false};
        // 是否已向 sink 发射过 delta：决定能否安全重试（已发射则重试会导致重复输出）
        boolean[] emitted = {false};

        IOException lastError = null;
        boolean streamCompleted = false;
        int maxAttempts = Math.max(1, maxRetries + 1);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                // 重试前重置上一轮的部分输出
                toolCallAccumulators.clear();
                textBuffer.setLength(0);
                reasoningBuffer.setLength(0);
                usage[0] = 0;
                usage[1] = 0;
                lengthTruncated[0] = false;
                emitted[0] = false;
                log.info("[chatStream] 超时重试 {}/{} (provider={}, model={}, 尚未发射 delta)",
                        attempt - 1, maxRetries, providerName, modelName);
            } else {
                log.debug("发送流式聊天请求: provider={}, model={}, url={}", providerName, modelName, url);
            }

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "无响应体";
                    // 协议拒绝时打印 messages 序列摘要，便于定位是哪条消息触发 400
                    if (response.code() >= 400 && response.code() < 500) {
                        log.warn("LLM 4xx 拒绝, messages 序列摘要:\n{}", summarizeMessagesForLog(messages));
                    }
                    throw new IOException("API 流式请求失败: status=" + response.code() + ", body=" + body);
                }
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                response.body().byteStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        if (!line.startsWith("data:")) continue;
                        String payload = line.substring(5).trim();
                        if ("[DONE]".equals(payload)) break;
                        parseStreamFrame(payload, agentId, toolCallAccumulators,
                                textBuffer, reasoningBuffer, usage, lengthTruncated, emitted, sink);
                    }
                }
                streamCompleted = true;
                break;
            } catch (IOException e) {
                lastError = e;
                boolean canRetry = isReadTimeout(e) && attempt < maxAttempts && !emitted[0];
                if (!canRetry) {
                    // 不可重试：已发射 delta（重试会导致重复输出）/ 非超时 / 用尽重试次数
                    if (isReadTimeout(e) && emitted[0]) {
                        log.warn("流式请求超时但已发射 delta，无法安全重试（重试会导致重复输出）: provider={}",
                                providerName);
                    }
                    log.error("流式聊天请求异常: provider={}, model={}", providerName, modelName, e);
                    sink.onEvent(AgentEvent.error(agentId, "流式请求失败: " + e.getMessage()));
                    break;
                }
                backoffSleep(attempt);
            }
        }

        // 流结束：聚合 tool_calls（arguments 分片已完整）+ 发射 MODEL_CALL_END。
        // 仅在流成功读完时发射 tool_calls/OUTPUT_TRUNCATED；失败路径下累积器可能含半截分片。
        if (streamCompleted && lengthTruncated[0]) {
            log.warn("模型输出达到 max_tokens={} 上限被截断（finish_reason=length），" +
                    "tool_call 参数可能不完整；下一轮将注入 user 消息告知模型",
                    maxOutputTokens);
            sink.onEvent(AgentEvent.outputTruncated(agentId, usage[1], maxOutputTokens));
        }
        if (streamCompleted) {
            for (ToolCallAccumulator acc : toolCallAccumulators.values()) {
                Map<String, Object> args = new HashMap<>();
                args.put("id", acc.id);
                args.put("name", acc.name);
                args.put("arguments", acc.arguments.toString());
                args.put("raw", acc.arguments.toString());
                sink.onEvent(AgentEvent.toolCall(agentId, acc.name, args));
            }
        }
        sink.onEvent(AgentEvent.modelCallEnd(agentId, modelName, usage[0], usage[1]));
    }

    /**
     * 解析 SSE 单帧 data 负载，delta 事件实时上推到 sink。
     * {@code finish_reason=length} 写入 lengthTruncated[0]，由 chatStream 在流结束后统一发射事件。
     * 任何 text/thinking delta 上推都会把 emitted[0] 置 true，用于 chatStream 判断是否可安全重试。
     */
    private void parseStreamFrame(String payload, String agentId,
                                   Map<Integer, ToolCallAccumulator> toolCallAccumulators,
                                   StringBuilder textBuffer, StringBuilder reasoningBuffer,
                                   int[] usage, boolean[] lengthTruncated, boolean[] emitted,
                                   StreamSink sink) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode choice0 = choices.get(0);
                JsonNode delta = choice0.path("delta");

                // 文本 delta —— 实时上推
                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    textBuffer.append(content);
                    sink.onEvent(AgentEvent.textDelta(agentId, content));
                    emitted[0] = true;
                }

                // 思考链 delta（DeepSeek / GLM 等支持）—— 实时上推
                String reasoning = delta.path("reasoning_content").asText("");
                if (!reasoning.isEmpty()) {
                    reasoningBuffer.append(reasoning);
                    sink.onEvent(AgentEvent.thinkingDelta(agentId, reasoning));
                    emitted[0] = true;
                }

                // 工具调用 delta（按 index 拼接 arguments 分片）
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        int idx = tc.path("index").asInt(0);
                        ToolCallAccumulator acc = toolCallAccumulators
                                .computeIfAbsent(idx, k -> new ToolCallAccumulator());
                        if (acc.id == null) {
                            String id = tc.path("id").asText(null);
                            if (id != null) acc.id = id;
                        }
                        JsonNode fn = tc.path("function");
                        if (acc.name == null) {
                            String name = fn.path("name").asText(null);
                            if (name != null) acc.name = name;
                        }
                        String argsFragment = fn.path("arguments").asText("");
                        if (!argsFragment.isEmpty()) {
                            acc.arguments.append(argsFragment);
                        }
                    }
                }

                // finish_reason：流末帧才携带。length 表示因 max_tokens 截断
                String finishReason = choice0.path("finish_reason").asText(null);
                if ("length".equals(finishReason)) {
                    lengthTruncated[0] = true;
                }
            }

            // 最后一帧的 usage（stream_options.include_usage=true 时）
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage[0] = usageNode.path("prompt_tokens").asInt(0);
                usage[1] = usageNode.path("completion_tokens").asInt(0);
            }
        } catch (Exception e) {
            log.warn("解析 SSE 帧失败: {}", payload, e);
        }
    }

    /** 流式工具调用累积器：按 index 聚合分片。 */
    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    // ==================== 请求构建 ====================

    /**
     * 构建 OpenAI 兼容的请求体。
     */
    private ObjectNode buildRequestBody(String modelName, List<Msg> messages,
                                         List<MCPClient.ToolInfo> tools) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", modelName);
        body.put("max_tokens", maxOutputTokens);
        body.put("temperature", DEFAULT_TEMPERATURE);

        // 构建消息数组
        ArrayNode messagesArray = body.putArray("messages");
        for (Msg msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.getRole());

            // 处理工具结果消息
            if ("tool".equals(msg.getRole())) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ContentBlock.ToolResultBlock resultBlock) {
                        msgNode.put("content", resultBlock.getContent());
                        msgNode.put("tool_call_id", resultBlock.getToolCallId());
                    }
                }
                continue;
            }

            // 处理包含工具调用的 assistant 消息
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                // 设置文本内容
                String textContent = msg.getTextContent();
                if (!textContent.isEmpty()) {
                    msgNode.put("content", textContent);
                } else {
                    msgNode.putNull("content");
                }

                // 添加工具调用
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ContentBlock.ToolCallBlock toolCall : msg.getToolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", toolCall.getId());
                    tcNode.put("type", "function");

                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", toolCall.getName());
                    functionNode.put("arguments", toolCall.getArguments());
                }
                continue;
            }

            // 普通消息：拼接所有文本
            StringBuilder contentBuilder = new StringBuilder();
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ContentBlock.TextBlock textBlock) {
                    if (!contentBuilder.isEmpty()) {
                        contentBuilder.append("\n");
                    }
                    contentBuilder.append(textBlock.getText());
                } else if (block instanceof ContentBlock.ThinkingBlock thinkingBlock) {
                    if (!contentBuilder.isEmpty()) {
                        contentBuilder.append("\n");
                    }
                    contentBuilder.append("[思考] ").append(thinkingBlock.getText());
                }
            }
            msgNode.put("content", contentBuilder.toString());
        }

        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (MCPClient.ToolInfo toolInfo : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");

                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", toolInfo.name());
                functionNode.put("description", toolInfo.description());

                // 使用工具提供的参数 schema，为空则用默认空 schema
                ObjectNode paramsNode = functionNode.putObject("parameters");
                String paramsJson = toolInfo.parametersJson();
                if (paramsJson != null && !paramsJson.isBlank()) {
                    try {
                        paramsNode.setAll(OBJECT_MAPPER.readValue(paramsJson, ObjectNode.class));
                    } catch (Exception e) {
                        log.warn("解析工具 [{}] 参数 schema 失败，使用默认空 schema", toolInfo.name());
                        paramsNode.put("type", "object");
                        paramsNode.putObject("properties");
                        paramsNode.putArray("required");
                    }
                } else {
                    paramsNode.put("type", "object");
                    paramsNode.putObject("properties");
                    paramsNode.putArray("required");
                }
            }
        }

        return body;
    }

    // ==================== 响应解析 ====================

    /**
     * 解析 OpenAI 兼容的 chat/completions 响应。
     */
    private Msg parseResponse(String responseBody) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;

        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode choices = root.path("choices");

            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.path("message");

                // 解析文本内容
                JsonNode contentNode = message.path("content");
                if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                    String content = contentNode.asText();
                    if (content != null && !content.isEmpty()) {
                        contentBlocks.add(new ContentBlock.TextBlock(content));
                    }
                }

                // 解析思考内容（reasoning_content 字段，部分模型支持）
                JsonNode reasoningNode = message.path("reasoning_content");
                if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
                    String reasoning = reasoningNode.asText();
                    if (reasoning != null && !reasoning.isEmpty()) {
                        contentBlocks.add(new ContentBlock.ThinkingBlock(reasoning));
                    }
                }

                // 解析工具调用
                JsonNode toolCallsNode = message.path("tool_calls");
                if (toolCallsNode.isArray()) {
                    for (JsonNode toolCallNode : toolCallsNode) {
                        String id = toolCallNode.path("id").asText("call_" + UUID.randomUUID());
                        String name = toolCallNode.path("function").path("name").asText();
                        String arguments = toolCallNode.path("function").path("arguments").asText("{}");
                        contentBlocks.add(new ContentBlock.ToolCallBlock(id, name, arguments));
                    }
                }
            }

            // 解析 token 用量
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode()) {
                promptTokens = usageNode.path("prompt_tokens").asInt(0);
                completionTokens = usageNode.path("completion_tokens").asInt(0);
            }
        } catch (Exception e) {
            log.warn("解析 API 响应异常，将原始内容作为文本返回", e);
            contentBlocks.add(new ContentBlock.TextBlock(responseBody));
        }

        Msg.TokenUsage usage = new Msg.TokenUsage(promptTokens, completionTokens);
        return new Msg(UUID.randomUUID().toString(), "assistant", contentBlocks, usage, Instant.now(), null);
    }

    /**
     * 把 messages 列表精简描述用于错误日志：
     * 每条只显示 role + 关键 id + 内容长度，避免打印完整 prompt。
     * 用于诊断 LLM 协议拒绝（HTTP 400 messages 参数非法）。
     */
    private String summarizeMessagesForLog(List<Msg> messages) {
        if (messages == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            sb.append(String.format("[%d] role=%s", i, m.getRole()));
            if ("assistant".equals(m.getRole()) && m.hasToolCalls()) {
                List<String> ids = new ArrayList<>();
                for (ContentBlock.ToolCallBlock tc : m.getToolCalls()) {
                    ids.add(tc.getId() + ":" + tc.getName());
                }
                sb.append(" tool_calls=[").append(String.join(",", ids)).append("]");
            }
            if ("tool".equals(m.getRole())) {
                List<String> ids = new ArrayList<>();
                for (ContentBlock block : m.getContent()) {
                    if (block instanceof ContentBlock.ToolResultBlock tr) {
                        ids.add(tr.getToolCallId());
                    }
                }
                sb.append(" tool_call_ids=[").append(String.join(",", ids)).append("]");
            }
            // 内容长度（避免打印完整 prompt）
            int len = 0;
            for (ContentBlock b : m.getContent()) {
                if (b instanceof ContentBlock.TextBlock t) len += t.getText().length();
                else if (b instanceof ContentBlock.ToolCallBlock tc) len += tc.getArguments() == null ? 0 : tc.getArguments().length();
                else if (b instanceof ContentBlock.ToolResultBlock tr) len += tr.getContent() == null ? 0 : tr.getContent().length();
            }
            sb.append(" contentChars=").append(len);
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
