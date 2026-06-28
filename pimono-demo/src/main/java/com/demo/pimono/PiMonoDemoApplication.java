package com.demo.pimono;

import com.demo.pimono.agent.AgentCore;
import com.demo.pimono.agent.ToolDefinition;
import com.demo.pimono.context.Context;
import com.demo.pimono.context.ContextManager;
import com.demo.pimono.mcp.McpClientManager;
import com.demo.pimono.ui.ConsoleUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PiMonoDemoApplication {

    private static final Logger log = LoggerFactory.getLogger(PiMonoDemoApplication.class);

    public static void main(String[] args) {
        ConsoleUI.printBanner();

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("ANTHROPIC_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            ConsoleUI.printError("No API key found. Please set OPENAI_API_KEY, ANTHROPIC_API_KEY, or DASHSCOPE_API_KEY environment variable.");
            System.exit(1);
        }

        String modelProvider = System.getenv().getOrDefault("MODEL_PROVIDER", "openai");
        String modelName = System.getenv().getOrDefault("MODEL_NAME", "gpt-4o-mini");
        String baseUrl = System.getenv().getOrDefault("MODEL_BASE_URL", "");

        ConsoleUI.printInfo("Initializing Pi Mono Demo...");
        ConsoleUI.printInfo("Model Provider: " + modelProvider);
        ConsoleUI.printInfo("Model: " + modelName);

        try {
            McpClientManager mcpManager = new McpClientManager();
            mcpManager.initialize();

            ContextManager contextManager = new ContextManager();

            AgentCore agentCore = new AgentCore(apiKey, baseUrl, modelName, mcpManager, contextManager);

            ConsoleUI.printSuccess("Pi Mono Demo initialized successfully!");
            ConsoleUI.printInfo("Available tools: " + String.join(", ", mcpManager.getToolNames()));
            ConsoleUI.printInfo("Type 'exit' to quit, 'history' for conversation, 'status' for agent status, 'help' for commands");
            ConsoleUI.printSeparator();

            while (true) {
                String userInput = ConsoleUI.promptUser();
                if (userInput == null || userInput.isBlank()) {
                    continue;
                }

                String trimmed = userInput.trim();
                if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
                    ConsoleUI.printInfo("Shutting down...");
                    agentCore.shutdown();
                    mcpManager.shutdown();
                    break;
                }
                if ("history".equalsIgnoreCase(trimmed)) {
                    Context ctx = contextManager.getCurrentContext();
                    ConsoleUI.printContextHistory(ctx);
                    continue;
                }
                if ("status".equalsIgnoreCase(trimmed)) {
                    ConsoleUI.printAgentStatus(agentCore.getStatus());
                    continue;
                }
                if ("clear".equalsIgnoreCase(trimmed)) {
                    contextManager.clear();
                    ConsoleUI.printSuccess("Context cleared.");
                    continue;
                }
                if ("tools".equalsIgnoreCase(trimmed)) {
                    List<ToolDefinition> tools = mcpManager.getAllTools();
                    ConsoleUI.printTools(tools);
                    continue;
                }
                if ("help".equalsIgnoreCase(trimmed)) {
                    ConsoleUI.printHelp();
                    continue;
                }

                try {
                    long startTime = System.currentTimeMillis();
                    String response = agentCore.chat(userInput);
                    long duration = System.currentTimeMillis() - startTime;
                    ConsoleUI.printAgentResponse(response, duration);
                } catch (Exception e) {
                    log.error("Error processing input", e);
                    ConsoleUI.printError("Error: " + e.getMessage());
                    ConsoleUI.printInfo("You can continue the conversation or type 'help' for available commands.");
                }
            }
        } catch (Exception e) {
            log.error("Failed to initialize application", e);
            ConsoleUI.printError("Initialization failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
