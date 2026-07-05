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

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /** OkHttp 客户端 */
    private final OkHttpClient httpClient;

    /** 凭证提供者 */
    private final CredentialProvider credentialProvider;

    public ChatModel(CredentialProvider credentialProvider) {
        this.credentialProvider = credentialProvider;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
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

        try {
            ObjectNode requestBody = buildRequestBody(modelName, messages, tools);
            String url = baseUrl + "/chat/completions";

            log.debug("发送聊天请求: provider={}, model={}, url={}", providerName, modelName, url);

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
            log.error("聊天请求异常: provider={}, model={}", providerName, modelName, e);
            // 返回错误消息
            return new Msg(UUID.randomUUID().toString(), "assistant",
                    List.of(new ContentBlock.TextBlock("请求失败: " + e.getMessage())));
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
        log.debug("发送流式聊天请求: provider={}, model={}, url={}", providerName, modelName, url);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
                .build();

        // 工具调用增量累积器：按 index 拼接 arguments 分片
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new TreeMap<>();
        StringBuilder textBuffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        int[] usage = {0, 0};

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
                            textBuffer, reasoningBuffer, usage, sink);
                }
            }
        } catch (IOException e) {
            log.error("流式聊天请求异常: provider={}, model={}", providerName, modelName, e);
            sink.onEvent(AgentEvent.error(agentId, "流式请求失败: " + e.getMessage()));
        }

        // 流结束：聚合 tool_calls（arguments 分片已完整）+ 发射 MODEL_CALL_END
        for (ToolCallAccumulator acc : toolCallAccumulators.values()) {
            Map<String, Object> args = new HashMap<>();
            args.put("id", acc.id);
            args.put("name", acc.name);
            args.put("arguments", acc.arguments.toString());
            args.put("raw", acc.arguments.toString());
            sink.onEvent(AgentEvent.toolCall(agentId, acc.name, args));
        }
        sink.onEvent(AgentEvent.modelCallEnd(agentId, modelName, usage[0], usage[1]));
    }

    /**
     * 解析 SSE 单帧 data 负载，delta 事件实时上推到 sink。
     */
    private void parseStreamFrame(String payload, String agentId,
                                   Map<Integer, ToolCallAccumulator> toolCallAccumulators,
                                   StringBuilder textBuffer, StringBuilder reasoningBuffer,
                                   int[] usage, StreamSink sink) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).path("delta");

                // 文本 delta —— 实时上推
                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    textBuffer.append(content);
                    sink.onEvent(AgentEvent.textDelta(agentId, content));
                }

                // 思考链 delta（DeepSeek / GLM 等支持）—— 实时上推
                String reasoning = delta.path("reasoning_content").asText("");
                if (!reasoning.isEmpty()) {
                    reasoningBuffer.append(reasoning);
                    sink.onEvent(AgentEvent.thinkingDelta(agentId, reasoning));
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
        body.put("max_tokens", DEFAULT_MAX_TOKENS);
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
