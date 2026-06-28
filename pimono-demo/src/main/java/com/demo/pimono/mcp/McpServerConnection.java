package com.demo.pimono.mcp;

import com.demo.pimono.agent.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class McpServerConnection {

    private static final Logger log = LoggerFactory.getLogger(McpServerConnection.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    private final String serverName;
    private final String command;
    private final List<String> args;
    private Process process;
    private BufferedReader reader;
    private volatile boolean connected;
    private int requestId;

    public McpServerConnection(String serverName, String command, List<String> args) {
        this.serverName = serverName;
        this.command = command;
        this.args = args != null ? args : List.of();
        this.connected = false;
        this.requestId = 0;
    }

    public void connect() throws Exception {
        List<String> commandParts = new ArrayList<>();
        commandParts.add(command);
        commandParts.addAll(this.args);

        ProcessBuilder pb = new ProcessBuilder(commandParts);
        pb.redirectErrorStream(true);
        process = pb.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        sendInitialize();
        connected = true;
        log.info("Connected to MCP server: {}", serverName);
    }

    private void sendInitialize() throws Exception {
        Map<String, Object> params = Map.of(
                "protocolVersion", MCP_PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "pimono-demo", "version", "1.0.0")
        );
        String response = sendRequest("initialize", params);
        log.info("MCP server {} initialized: {}", serverName, response);
        sendNotification("notifications/initialized", Map.of());
    }

    public List<ToolDefinition> discoverTools() throws Exception {
        List<ToolDefinition> tools = new ArrayList<>();
        if (!connected) return tools;

        String response = sendRequest("tools/list", Map.of());
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode toolsNode = root.path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    String name = toolNode.path("name").asText();
                    String description = toolNode.path("description").asText();
                    JsonNode inputSchema = toolNode.path("inputSchema");
                    tools.add(new ToolDefinition(name, description, inputSchema, serverName));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse tools from server {}: {}", serverName, e.getMessage());
        }
        return tools;
    }

    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server: " + serverName);
        }

        Map<String, Object> params = new java.util.HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);

        String response = sendRequest("tools/call", params);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentNode = root.path("content");
            if (contentNode.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : contentNode) {
                    if (item.path("type").asText().equals("text")) {
                        sb.append(item.path("text").asText());
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool result from server {}: {}", serverName, e.getMessage());
        }
        return response;
    }

    private String sendRequest(String method, Map<String, Object> params) throws Exception {
        int id = ++requestId;
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        String jsonLine = objectMapper.writeValueAsString(request);
        log.debug("Sending MCP request to {}: {}", serverName, jsonLine);

        process.getOutputStream().write((jsonLine + "\n").getBytes());
        process.getOutputStream().flush();

        String responseLine = reader.readLine();
        if (responseLine == null) {
            throw new IllegalStateException("No response from MCP server: " + serverName);
        }
        log.debug("Received MCP response from {}: {}", serverName, responseLine);
        return responseLine;
    }

    private void sendNotification(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> notification = new java.util.LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);

        String jsonNotification = objectMapper.writeValueAsString(notification);
        process.getOutputStream().write((jsonNotification + "\n").getBytes());
        process.getOutputStream().flush();
    }

    public void disconnect() {
        connected = false;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    public String getServerName() { return serverName; }
    public boolean isConnected() { return connected; }
}
