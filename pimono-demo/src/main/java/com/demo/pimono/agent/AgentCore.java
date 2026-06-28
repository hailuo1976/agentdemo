package com.demo.pimono.agent;

import com.demo.pimono.ai.LlmClient;
import com.demo.pimono.context.Context;
import com.demo.pimono.context.ContextManager;
import com.demo.pimono.mcp.McpClientManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class AgentCore {

    private static final Logger log = LoggerFactory.getLogger(AgentCore.class);
    private static final int MAX_TOOL_ROUNDS = 50;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LlmClient llmClient;
    private final McpClientManager mcpManager;
    private final ContextManager contextManager;
    private final String modelName;
    private volatile boolean active;
    private int totalQueries;
    private int totalToolCalls;

    public AgentCore(String apiKey, String baseUrl, String modelName,
                     McpClientManager mcpManager, ContextManager contextManager) {
        this.llmClient = new LlmClient(apiKey, baseUrl, modelName);
        this.mcpManager = mcpManager;
        this.contextManager = contextManager;
        this.modelName = modelName;
        this.active = true;
        this.totalQueries = 0;
        this.totalToolCalls = 0;
    }

    public String chat(String userInput) {
        if (!active) {
            return "Agent is not active.";
        }

        log.info("Processing chat input: {}", userInput);
        contextManager.addUserMessage(userInput);
        totalQueries++;

        try {
            Context context = contextManager.getCurrentContext();

            List<LlmClient.ToolSchema> toolSchemas = buildToolSchemas();

            LlmClient.LlmRequest request = new LlmClient.LlmRequest();
            request.setSystemPrompt(context.getSystemPrompt());
            request.setMessages(context.toSimpleMessages().stream()
                    .map(m -> new LlmClient.ContextMessage(m.getRole(), m.getContent()))
                    .toList());
            request.setTools(toolSchemas);
            request.setMaxTokens(2000);

            int round = 0;
            while (round < MAX_TOOL_ROUNDS) {
                round++;
                log.debug("Tool calling round {}", round);

                LlmClient.LlmResponse response = llmClient.complete(request);
                log.debug("LLM response: finishReason={}, hasToolCalls={}",
                        response.getFinishReason(), response.hasToolCalls());

                if (response.hasToolCalls()) {
                    List<Context.ToolCallEntry> toolCallEntries = new ArrayList<>();
                    for (LlmClient.ToolCallInfo tc : response.getToolCalls()) {
                        toolCallEntries.add(new Context.ToolCallEntry(tc.getId(), tc.getName(), tc.getArguments()));
                    }
                    contextManager.addAssistantToolCall(response.getContent(), toolCallEntries);

                    for (LlmClient.ToolCallInfo tc : response.getToolCalls()) {
                        log.info("Tool call: {} with args: {}", tc.getName(), tc.getArguments());
                        totalToolCalls++;

                        Map<String, Object> args;
                        try {
                            args = objectMapper.readValue(tc.getArguments(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            args = Map.of("input", tc.getArguments());
                        }

                        ToolResult result = mcpManager.executeTool(tc.getName(), args);
                        contextManager.addToolResult(tc.getId(), tc.getName(), result.toString(), !result.isSuccess());
                        log.info("Tool result: {} - {}", tc.getName(), result.isSuccess() ? "SUCCESS" : "ERROR");
                    }

                    context = contextManager.getCurrentContext();
                    request.setMessages(context.toSimpleMessages().stream()
                            .map(m -> new LlmClient.ContextMessage(m.getRole(), m.getContent()))
                            .toList());
                } else {
                    String finalContent = response.getContent();
                    if (finalContent == null || finalContent.isBlank()) {
                        finalContent = "I apologize, but I couldn't generate a response. Please try again.";
                    }
                    contextManager.addAssistantMessage(finalContent);
                    return finalContent;
                }
            }

            String fallback = "I've reached the maximum number of tool-calling rounds. Let me summarize what I've found so far.";
            contextManager.addAssistantMessage(fallback);
            return fallback;

        } catch (LlmClient.LlmException e) {
            log.error("LLM error during chat", e);
            contextManager.addAssistantMessage("Error: " + e.getMessage());
            return "I encountered an error: " + e.getMessage() + ". Please try again.";
        } catch (Exception e) {
            log.error("Unexpected error during chat", e);
            contextManager.addAssistantMessage("Error: " + e.getMessage());
            return "An unexpected error occurred: " + e.getMessage();
        }
    }

    private List<LlmClient.ToolSchema> buildToolSchemas() {
        List<LlmClient.ToolSchema> schemas = new ArrayList<>();
        for (ToolDefinition tool : mcpManager.getAllTools()) {
            Map<String, Object> params = new HashMap<>();
            params.put("type", "object");
            if (tool.getInputSchema() != null) {
                try {
                    params = objectMapper.convertValue(tool.getInputSchema(), Map.class);
                } catch (Exception e) {
                    log.warn("Failed to convert schema for tool {}: {}", tool.getName(), e.getMessage());
                }
            }
            schemas.add(new LlmClient.ToolSchema(tool.getName(), tool.getDescription(), params));
        }
        return schemas;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", active);
        status.put("model", modelName);
        status.put("totalQueries", totalQueries);
        status.put("totalToolCalls", totalToolCalls);
        status.put("contextMessages", contextManager.getCurrentContext().getMessageCount());
        status.put("availableTools", mcpManager.getToolNames());
        return status;
    }

    public void shutdown() {
        active = false;
        log.info("AgentCore shutdown. Total queries: {}, tool calls: {}", totalQueries, totalToolCalls);
    }
}
