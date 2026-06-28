package com.demo.pimono.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class LlmClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient httpClient;

    public LlmClient(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
        this.model = model;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public LlmResponse complete(LlmRequest request) throws LlmException {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("max_tokens", request.getMaxTokens());
            requestBody.put("temperature", request.getTemperature());

            ArrayNode messages = requestBody.putArray("messages");

            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                ObjectNode systemMsg = messages.addObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", request.getSystemPrompt());
            }

            for (ContextMessage msg : request.getMessages()) {
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", msg.getRole());
                msgNode.put("content", msg.getContent());
            }

            if (request.getTools() != null && !request.getTools().isEmpty()) {
                ArrayNode toolsArray = requestBody.putArray("tools");
                for (ToolSchema tool : request.getTools()) {
                    ObjectNode toolNode = toolsArray.addObject();
                    toolNode.put("type", "function");
                    ObjectNode functionNode = toolNode.putObject("function");
                    functionNode.put("name", tool.getName());
                    functionNode.put("description", tool.getDescription());
                    if (tool.getParameters() != null) {
                        functionNode.set("parameters", objectMapper.valueToTree(tool.getParameters()));
                    }
                }
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    throw new LlmException("API request failed with status " + response.code() + ": " + errorBody);
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);

                ArrayNode choices = (ArrayNode) root.get("choices");
                if (choices == null || choices.isEmpty()) {
                    throw new LlmException("No choices in response");
                }

                JsonNode messageNode = choices.get(0).get("message");
                String content = messageNode.has("content") && !messageNode.get("content").isNull()
                        ? messageNode.get("content").asText() : "";

                java.util.List<ToolCallInfo> toolCalls = new java.util.ArrayList<>();
                if (messageNode.has("tool_calls") && !messageNode.get("tool_calls").isNull()) {
                    for (JsonNode tc : messageNode.get("tool_calls")) {
                        String id = tc.get("id").asText();
                        String name = tc.get("function").get("name").asText();
                        String args = tc.get("function").get("arguments").asText();
                        toolCalls.add(new ToolCallInfo(id, name, args));
                    }
                }

                String finishReason = choices.get(0).has("finish_reason")
                        ? choices.get(0).get("finish_reason").asText() : "stop";

                int promptTokens = root.has("usage") && root.get("usage").has("prompt_tokens")
                        ? root.get("usage").get("prompt_tokens").asInt() : 0;
                int completionTokens = root.has("usage") && root.get("usage").has("completion_tokens")
                        ? root.get("usage").get("completion_tokens").asInt() : 0;

                return new LlmResponse(content, toolCalls, finishReason, promptTokens, completionTokens);
            }
        } catch (IOException e) {
            throw new LlmException("IO error during LLM call", e);
        }
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }

    public static class LlmRequest {
        private String systemPrompt;
        private java.util.List<ContextMessage> messages;
        private java.util.List<ToolSchema> tools;
        private int maxTokens = 2000;
        private double temperature = 0.7;

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public java.util.List<ContextMessage> getMessages() { return messages; }
        public void setMessages(java.util.List<ContextMessage> messages) { this.messages = messages; }
        public java.util.List<ToolSchema> getTools() { return tools; }
        public void setTools(java.util.List<ToolSchema> tools) { this.tools = tools; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    public static class LlmResponse {
        private final String content;
        private final java.util.List<ToolCallInfo> toolCalls;
        private final String finishReason;
        private final int promptTokens;
        private final int completionTokens;

        public LlmResponse(String content, java.util.List<ToolCallInfo> toolCalls, String finishReason,
                           int promptTokens, int completionTokens) {
            this.content = content;
            this.toolCalls = toolCalls;
            this.finishReason = finishReason;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        public String getContent() { return content; }
        public java.util.List<ToolCallInfo> getToolCalls() { return toolCalls; }
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
        public String getFinishReason() { return finishReason; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
    }

    public static class ToolCallInfo {
        private final String id;
        private final String name;
        private final String arguments;

        public ToolCallInfo(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getArguments() { return arguments; }
    }

    public static class ContextMessage {
        private final String role;
        private final String content;

        public ContextMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }

    public static class ToolSchema {
        private final String name;
        private final String description;
        private final java.util.Map<String, Object> parameters;

        public ToolSchema(String name, String description, java.util.Map<String, Object> parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public java.util.Map<String, Object> getParameters() { return parameters; }
    }
}
