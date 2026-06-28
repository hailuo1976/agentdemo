package com.demo.pimono.ui;

import com.demo.pimono.agent.ToolDefinition;
import com.demo.pimono.context.Context;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class ConsoleUI {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String CYAN = "\u001B[36m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void printBanner() {
        System.out.println();
        System.out.println(MAGENTA + BOLD + "  ╔══════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║                                                          ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║          🧠  Pi Mono Demo Application  🧠               ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║                                                          ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║    Unified LLM API • Agent Runtime • MCP Protocol        ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║                                                          ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ╚══════════════════════════════════════════════════════════╝" + RESET);
        System.out.println();
    }

    public static String promptUser(java.util.Scanner scanner) {
        System.out.print(BLUE + "  You ▶ " + RESET);
        System.out.flush();
        if (!scanner.hasNextLine()) return null;
        return scanner.nextLine();
    }

    public static void printAgentResponse(String response, long durationMs) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        System.out.println();
        System.out.println(GREEN + "  Agent ◀ [" + timestamp + "] (" + durationMs + "ms)" + RESET);
        System.out.println(GREEN + "  ┌──────────────────────────────────────────────────" + RESET);
        for (String line : response.split("\n")) {
            System.out.println(GREEN + "  │ " + RESET + line);
        }
        System.out.println(GREEN + "  └──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    public static void printInfo(String message) {
        System.out.println(CYAN + "  ℹ " + RESET + message);
    }

    public static void printSuccess(String message) {
        System.out.println(GREEN + "  ✓ " + RESET + message);
    }

    public static void printError(String message) {
        System.out.println(RED + "  ✗ " + RESET + message);
    }

    public static void printWarning(String message) {
        System.out.println(YELLOW + "  ⚠ " + RESET + message);
    }

    public static void printSeparator() {
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
    }

    public static void printContextHistory(Context context) {
        List<Context.Message> messages = context.getMessages();
        System.out.println();
        System.out.println(BOLD + "  📋 Context History (" + messages.size() + " messages)" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);

        if (context.getSystemPrompt() != null) {
            System.out.println("  " + YELLOW + "⚙️ [system] " + RESET + context.getSystemPrompt());
        }

        for (Context.Message msg : messages) {
            String icon = switch (msg.getRole()) {
                case "user" -> BLUE + "👤" + RESET;
                case "assistant" -> GREEN + "🤖" + RESET;
                case "tool" -> CYAN + "🔧" + RESET;
                default -> "  ";
            };
            String content = msg.getContent().length() > 80
                    ? msg.getContent().substring(0, 80) + "..."
                    : msg.getContent();
            System.out.println("  " + icon + " [" + msg.getTimestamp().toString().substring(11, 19) + "] " + content);

            if (msg.getToolCalls() != null) {
                for (Context.ToolCallEntry tc : msg.getToolCalls()) {
                    System.out.println("    " + CYAN + "↳ Tool: " + tc.getName() + RESET);
                }
            }
        }
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    public static void printAgentStatus(Map<String, Object> status) {
        System.out.println();
        System.out.println(BOLD + "  📊 Agent Status" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        for (Map.Entry<String, Object> entry : status.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("availableTools".equals(key) && value instanceof List<?> tools) {
                System.out.println("  🔧 " + key + ": " + String.join(", ", (List<String>) tools));
            } else {
                System.out.println("  📌 " + key + ": " + value);
            }
        }
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    public static void printTools(List<ToolDefinition> tools) {
        System.out.println();
        System.out.println(BOLD + "  🔧 Available Tools (" + tools.size() + ")" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        for (ToolDefinition tool : tools) {
            System.out.println("  " + CYAN + "• " + tool.getName() + RESET + " - " + tool.getDescription());
        }
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    public static void printHelp() {
        System.out.println();
        System.out.println(BOLD + "  📖 Available Commands" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println("  " + CYAN + "exit/quit" + RESET + "  - Exit the application");
        System.out.println("  " + CYAN + "history" + RESET + "   - View conversation context history");
        System.out.println("  " + CYAN + "status" + RESET + "    - Check agent status and statistics");
        System.out.println("  " + CYAN + "tools" + RESET + "     - List available tools");
        System.out.println("  " + CYAN + "clear" + RESET + "     - Clear conversation context");
        System.out.println("  " + CYAN + "help" + RESET + "      - Show this help message");
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }
}
