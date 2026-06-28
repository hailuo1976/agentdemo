package com.demo.agentscope.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * MCP 服务器连接。
 * <p>
 * 通过 JSON-RPC 2.0 协议（stdin/stdout）与 MCP 服务器子进程通信。
 * 支持连接初始化、工具发现和工具调用等核心操作。
 * </p>
 */
public class McpServerConnection {

    private static final Logger log = LoggerFactory.getLogger(McpServerConnection.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    /** 服务器名称 */
    private final String serverName;

    /** 启动命令 */
    private final String command;

    /** 命令参数 */
    private final List<String> args;

    /** 子进程 */
    private Process process;

    /** 输入流读取器 */
    private BufferedReader reader;

    /** 连接状态 */
    private volatile boolean connected;

    /** 请求ID自增序列 */
    private int requestId;

    public McpServerConnection(String serverName, String command, List<String> args) {
        this.serverName = serverName;
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : List.of();
        this.connected = false;
        this.requestId = 0;
    }

    /**
     * 连接到 MCP 服务器。
     * <p>
     * 启动子进程并发送 initialize 握手请求。
     * </p>
     *
     * @throws Exception 连接或初始化失败时抛出
     */
    public void connect() throws Exception {
        List<String> commandParts = new ArrayList<>();
        commandParts.add(command);
        commandParts.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(commandParts);
        pb.redirectErrorStream(true);
        process = pb.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        sendInitialize();
        connected = true;
        log.info("已连接到 MCP 服务器: {}", serverName);
    }

    /**
     * 发送初始化握手请求。
     */
    private void sendInitialize() throws Exception {
        Map<String, Object> params = Map.of(
                "protocolVersion", MCP_PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agentscope-demo", "version", "2.0.0")
        );
        String response = sendRequest("initialize", params);
        log.info("MCP 服务器 [{}] 初始化完成: {}", serverName, response);
        sendNotification("notifications/initialized", Map.of());
    }

    /**
     * 发现服务器上可用的工具列表。
     *
     * @return 工具信息列表
     * @throws Exception 请求或解析失败时抛出
     */
    public List<MCPClient.ToolInfo> discoverTools() throws Exception {
        List<MCPClient.ToolInfo> tools = new ArrayList<>();
        if (!connected) {
            return tools;
        }

        String response = sendRequest("tools/list", Map.of());
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response);
            JsonNode toolsNode = root.path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    String name = toolNode.path("name").asText();
                    String description = toolNode.path("description").asText();
                    tools.add(new MCPClient.ToolInfo(name, description, serverName));
                }
            }
        } catch (Exception e) {
            log.warn("解析工具列表失败，服务器: {}: {}", serverName, e.getMessage());
        }
        return tools;
    }

    /**
     * 调用服务器上的工具。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 工具调用结果字符串
     * @throws Exception 调用失败时抛出
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        if (!connected) {
            throw new IllegalStateException("未连接到 MCP 服务器: " + serverName);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());

        String response = sendRequest("tools/call", params);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response);
            JsonNode contentNode = root.path("content");
            if (contentNode.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : contentNode) {
                    if ("text".equals(item.path("type").asText())) {
                        sb.append(item.path("text").asText());
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("解析工具结果失败，服务器: {}: {}", serverName, e.getMessage());
        }
        return response;
    }

    /**
     * 发送 JSON-RPC 2.0 请求。
     */
    private String sendRequest(String method, Map<String, Object> params) throws Exception {
        int id = ++requestId;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        String jsonLine = OBJECT_MAPPER.writeValueAsString(request);
        log.debug("发送 MCP 请求到 [{}]: {}", serverName, jsonLine);

        process.getOutputStream().write((jsonLine + "\n").getBytes());
        process.getOutputStream().flush();

        String responseLine = reader.readLine();
        if (responseLine == null) {
            throw new IllegalStateException("MCP 服务器无响应: " + serverName);
        }
        log.debug("收到 MCP 响应来自 [{}]: {}", serverName, responseLine);
        return responseLine;
    }

    /**
     * 发送 JSON-RPC 2.0 通知（无响应）。
     */
    private void sendNotification(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);

        String jsonNotification = OBJECT_MAPPER.writeValueAsString(notification);
        process.getOutputStream().write((jsonNotification + "\n").getBytes());
        process.getOutputStream().flush();
    }

    /**
     * 断开与 MCP 服务器的连接。
     */
    public void disconnect() {
        connected = false;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
        log.info("已断开 MCP 服务器连接: {}", serverName);
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isConnected() {
        return connected;
    }
}
