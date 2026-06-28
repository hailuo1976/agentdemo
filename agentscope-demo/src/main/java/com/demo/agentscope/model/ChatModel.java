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
     * 流式聊天请求（演示模式：内部调用 chat 并包装为事件流）。
     *
     * @param providerName 提供商名称
     * @param messages     消息列表
     * @param tools        可用工具信息列表
     * @param agentId      智能体ID
     * @return 事件流
     */
    public EventStream chatStream(String providerName, List<Msg> messages,
                                  List<MCPClient.ToolInfo> tools, String agentId) {
        EventStream stream = new EventStream(agentId);
        Msg response = chat(providerName, messages, tools);

        // 将响应内容转换为事件流
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ContentBlock.TextBlock textBlock) {
                stream.emit(AgentEvent.textBlock(agentId, textBlock.getText()));
            } else if (block instanceof ContentBlock.ThinkingBlock thinkingBlock) {
                stream.emit(AgentEvent.thinkingBlock(agentId, thinkingBlock.getText()));
            } else if (block instanceof ContentBlock.ToolCallBlock toolCallBlock) {
                Map<String, Object> args = new HashMap<>();
                args.put("raw", toolCallBlock.getArguments());
                stream.emit(AgentEvent.toolCall(agentId, toolCallBlock.getName(), args));
            }
        }

        // 添加 token 用量事件
        if (response.getUsage() != null) {
            stream.emit(AgentEvent.modelCallEnd(agentId,
                    credentialProvider.getModelName(providerName),
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens()));
        }

        return stream;
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
}
