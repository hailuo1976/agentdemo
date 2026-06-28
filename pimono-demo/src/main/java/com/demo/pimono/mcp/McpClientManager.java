package com.demo.pimono.mcp;

import com.demo.pimono.agent.ToolDefinition;
import com.demo.pimono.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, McpServerConnection> connections;
    private final Map<String, List<ToolDefinition>> availableTools;
    private volatile boolean initialized;

    public McpClientManager() {
        this.connections = new ConcurrentHashMap<>();
        this.availableTools = new ConcurrentHashMap<>();
        this.initialized = false;
    }

    public void initialize() {
        log.info("Initializing MCP Client Manager...");
        try {
            registerBuiltinTools();
            initialized = true;
            log.info("MCP Client Manager initialized. {} tools available.", getTotalToolCount());
        } catch (Exception e) {
            log.error("Failed to initialize MCP Client Manager", e);
            throw new McpInitException("MCP initialization failed", e);
        }
    }

    public void connectToServer(String serverName, String command, List<String> args) {
        try {
            log.info("Connecting to MCP server: {}", serverName);
            McpServerConnection connection = new McpServerConnection(serverName, command, args);
            connection.connect();
            connections.put(serverName, connection);

            List<ToolDefinition> tools = connection.discoverTools();
            availableTools.put(serverName, tools);
            log.info("Connected to MCP server: {}. {} tools discovered.", serverName, tools.size());
        } catch (Exception e) {
            log.error("Failed to connect to MCP server: {}", serverName, e);
            throw new McpConnectionException("Failed to connect: " + serverName, e);
        }
    }

    private void registerBuiltinTools() {
        List<ToolDefinition> builtinTools = new ArrayList<>();

        ObjectNode weatherSchema = objectMapper.createObjectNode();
        weatherSchema.put("type", "object");
        ObjectNode weatherProps = weatherSchema.putObject("properties");
        weatherProps.putObject("city").put("type", "string").put("description", "City name");
        weatherProps.putObject("unit").put("type", "string").put("description", "Temperature unit");
        builtinTools.add(new ToolDefinition("get_weather", "Get current weather for a city", weatherSchema, "builtin"));

        ObjectNode calcSchema = objectMapper.createObjectNode();
        calcSchema.put("type", "object");
        ObjectNode calcProps = calcSchema.putObject("properties");
        calcProps.putObject("expression").put("type", "string").put("description", "Math expression");
        builtinTools.add(new ToolDefinition("calculate", "Evaluate a mathematical expression", calcSchema, "builtin"));

        ObjectNode searchSchema = objectMapper.createObjectNode();
        searchSchema.put("type", "object");
        ObjectNode searchProps = searchSchema.putObject("properties");
        searchProps.putObject("query").put("type", "string").put("description", "Search query");
        builtinTools.add(new ToolDefinition("search", "Search for information", searchSchema, "builtin"));

        ObjectNode timeSchema = objectMapper.createObjectNode();
        timeSchema.put("type", "object");
        ObjectNode timeProps = timeSchema.putObject("properties");
        timeProps.putObject("timezone").put("type", "string").put("description", "Timezone");
        builtinTools.add(new ToolDefinition("get_time", "Get current time", timeSchema, "builtin"));

        ObjectNode translateSchema = objectMapper.createObjectNode();
        translateSchema.put("type", "object");
        ObjectNode translateProps = translateSchema.putObject("properties");
        translateProps.putObject("text").put("type", "string").put("description", "Text to translate");
        translateProps.putObject("target_lang").put("type", "string").put("description", "Target language");
        builtinTools.add(new ToolDefinition("translate", "Translate text to another language", translateSchema, "builtin"));

        availableTools.put("builtin", builtinTools);
        log.info("Registered {} builtin tools", builtinTools.size());
    }

    public ToolResult executeTool(String toolName, Map<String, Object> arguments) {
        log.info("Executing tool: {} with args: {}", toolName, arguments);
        try {
            String serverName = findToolServer(toolName);
            if (serverName == null) {
                return ToolResult.error("Tool not found: " + toolName);
            }

            if ("builtin".equals(serverName)) {
                return executeBuiltinTool(toolName, arguments);
            }

            McpServerConnection connection = connections.get(serverName);
            if (connection == null) {
                return ToolResult.error("MCP server not connected: " + serverName);
            }

            String result = connection.callTool(toolName, arguments);
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("Error executing tool: {}", toolName, e);
            return ToolResult.error("Tool execution error: " + e.getMessage());
        }
    }

    private ToolResult executeBuiltinTool(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "get_weather" -> {
                String city = (String) arguments.getOrDefault("city", "Unknown");
                String unit = (String) arguments.getOrDefault("unit", "celsius");
                double temp = "fahrenheit".equalsIgnoreCase(unit) ? 72.0 : 22.0;
                yield ToolResult.success(String.format(
                        "{\"city\":\"%s\",\"temperature\":%.1f,\"unit\":\"%s\",\"condition\":\"Partly Cloudy\",\"humidity\":58,\"wind_speed\":15}",
                        city, temp, unit));
            }
            case "calculate" -> {
                String expression = (String) arguments.getOrDefault("expression", "0");
                try {
                    double result = evaluateExpression(expression);
                    yield ToolResult.success(String.format("{\"result\":%.4f,\"expression\":\"%s\"}", result, expression));
                } catch (Exception e) {
                    yield ToolResult.error("Calculation error: " + e.getMessage());
                }
            }
            case "search" -> {
                String query = (String) arguments.getOrDefault("query", "");
                yield ToolResult.success(String.format(
                        "{\"query\":\"%s\",\"results\":[{\"title\":\"Search Result\",\"snippet\":\"Information about %s\"}]}",
                        query, query));
            }
            case "get_time" -> {
                String timezone = (String) arguments.getOrDefault("timezone", "UTC");
                yield ToolResult.success(String.format("{\"timezone\":\"%s\",\"time\":\"%s\"}", timezone, java.time.ZonedDateTime.now().toString()));
            }
            case "translate" -> {
                String text = (String) arguments.getOrDefault("text", "");
                String targetLang = (String) arguments.getOrDefault("target_lang", "en");
                yield ToolResult.success(String.format("{\"original\":\"%s\",\"translated\":\"[Translation of %s to %s]\",\"target_lang\":\"%s\"}",
                        text, text, targetLang, targetLang));
            }
            default -> ToolResult.error("Unknown builtin tool: " + toolName)
                ;
        };
    }

    private double evaluateExpression(String expression) {
        String sanitized = expression.replaceAll("[^0-9+\\-*/.()\\s]", "");
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("Invalid expression");
        }
        try {
            return new SimpleExpressionEvaluator().evaluate(sanitized);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to evaluate: " + expression);
        }
    }

    private static class SimpleExpressionEvaluator {
        private final String expr;
        private int pos;

        SimpleExpressionEvaluator() { this.expr = ""; this.pos = 0; }

        double evaluate(String expression) {
            pos = 0;
            String e = expression.replaceAll("\\s+", "");
            double result = new Object() {
                double parse() {
                    double v = parseTerm();
                    while (pos < e.length()) {
                        char c = e.charAt(pos);
                        if (c == '+') { pos++; v += parseTerm(); }
                        else if (c == '-') { pos++; v -= parseTerm(); }
                        else break;
                    }
                    return v;
                }
                double parseTerm() {
                    double v = parseFactor();
                    while (pos < e.length()) {
                        char c = e.charAt(pos);
                        if (c == '*') { pos++; v *= parseFactor(); }
                        else if (c == '/') { pos++; v /= parseFactor(); }
                        else break;
                    }
                    return v;
                }
                double parseFactor() {
                    if (pos < e.length() && e.charAt(pos) == '(') {
                        pos++;
                        double v = parse();
                        pos++;
                        return v;
                    }
                    int start = pos;
                    if (pos < e.length() && (e.charAt(pos) == '-' || e.charAt(pos) == '+')) pos++;
                    while (pos < e.length() && (Character.isDigit(e.charAt(pos)) || e.charAt(pos) == '.')) pos++;
                    return Double.parseDouble(e.substring(start, pos));
                }
            }.parse();
            return result;
        }
    }

    private String findToolServer(String toolName) {
        for (Map.Entry<String, List<ToolDefinition>> entry : availableTools.entrySet()) {
            for (ToolDefinition tool : entry.getValue()) {
                if (tool.getName().equals(toolName)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    public List<ToolDefinition> getAllTools() {
        List<ToolDefinition> all = new ArrayList<>();
        for (List<ToolDefinition> tools : availableTools.values()) {
            all.addAll(tools);
        }
        return all;
    }

    public List<String> getToolNames() {
        return getAllTools().stream().map(ToolDefinition::getName).toList();
    }

    public int getTotalToolCount() {
        return availableTools.values().stream().mapToInt(List::size).sum();
    }

    public boolean isInitialized() { return initialized; }

    public void shutdown() {
        for (McpServerConnection conn : connections.values()) {
            try {
                conn.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting MCP server: {}", e.getMessage());
            }
        }
        connections.clear();
        availableTools.clear();
        initialized = false;
    }

    public static class McpInitException extends RuntimeException {
        public McpInitException(String message, Throwable cause) { super(message, cause); }
    }

    public static class McpConnectionException extends RuntimeException {
        public McpConnectionException(String message, Throwable cause) { super(message, cause); }
    }
}
