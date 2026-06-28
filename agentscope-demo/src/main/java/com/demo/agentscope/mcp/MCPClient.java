package com.demo.agentscope.mcp;

import com.demo.agentscope.message.ContentBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 MCP 客户端。
 * <p>
 * 管理内置工具和外部 MCP 服务器连接，提供统一的工具注册、
 * 发现和执行接口。内置4个演示工具（get_weather、calculate、search、get_time），
 * 并支持通过 {@link MCPConfig} 动态添加外部 MCP 服务器。
 * </p>
 */
public class MCPClient {

    private static final Logger log = LoggerFactory.getLogger(MCPClient.class);

    /** MCP 服务器配置列表 */
    private final List<MCPConfig> configs;

    /** MCP 服务器连接映射，key 为服务器名称 */
    private final Map<String, McpServerConnection> connections;

    /** 内置工具注册表，key 为工具名称 */
    private final Map<String, BuiltinToolEntry> builtinTools;

    /** 所有已发现的工具信息（内置 + 外部） */
    private final List<ToolInfo> allToolInfos;

    /** 初始化状态 */
    private volatile boolean initialized;

    public MCPClient() {
        this.configs = new ArrayList<>();
        this.connections = new ConcurrentHashMap<>();
        this.builtinTools = new LinkedHashMap<>();
        this.allToolInfos = new ArrayList<>();
        this.initialized = false;
    }

    // ==================== 初始化 ====================

    /**
     * 初始化 MCP 客户端：注册内置工具并连接到已配置的服务器。
     */
    public void initialize() {
        log.info("初始化 MCP 客户端...");
        try {
            registerBuiltinTools();
            connectToConfiguredServers();
            initialized = true;
            log.info("MCP 客户端初始化完成，共 {} 个工具可用", allToolInfos.size());
        } catch (Exception e) {
            log.error("MCP 客户端初始化失败", e);
            throw new MCPInitException("MCP 初始化失败", e);
        }
    }

    /**
     * 注册内置工具。
     * <p>
     * 4个内置演示工具：get_weather、calculate、search、get_time。
     * </p>
     */
    public void registerBuiltinTools() {
        // get_weather - 获取城市天气
        registerBuiltin("get_weather", "获取指定城市的当前天气信息", (args) -> {
            String city = String.valueOf(args.getOrDefault("city", "Unknown"));
            String unit = String.valueOf(args.getOrDefault("unit", "celsius"));
            double temp = "fahrenheit".equalsIgnoreCase(unit) ? 72.0 : 22.0;
            return String.format(
                    "{\"city\":\"%s\",\"temperature\":%.1f,\"unit\":\"%s\",\"condition\":\"Partly Cloudy\",\"humidity\":58,\"wind_speed\":15}",
                    city, temp, unit);
        });

        // calculate - 数学表达式计算
        registerBuiltin("calculate", "计算数学表达式的结果（支持 +、-、*、/、括号和数字）", (args) -> {
            String expression = String.valueOf(args.getOrDefault("expression", "0"));
            try {
                double result = new SimpleExpressionEvaluator().evaluate(expression);
                return String.format("{\"result\":%.4f,\"expression\":\"%s\"}", result, expression);
            } catch (Exception e) {
                throw new RuntimeException("计算错误: " + e.getMessage(), e);
            }
        });

        // search - 搜索信息
        registerBuiltin("search", "搜索相关信息", (args) -> {
            String query = String.valueOf(args.getOrDefault("query", ""));
            return String.format(
                    "{\"query\":\"%s\",\"results\":[{\"title\":\"Search Result\",\"snippet\":\"Information about %s\"}]}",
                    query, query);
        });

        // get_time - 获取当前时间
        registerBuiltin("get_time", "获取当前时间", (args) -> {
            String timezone = String.valueOf(args.getOrDefault("timezone", "UTC"));
            return String.format("{\"timezone\":\"%s\",\"time\":\"%s\"}", timezone, ZonedDateTime.now().toString());
        });

        log.info("已注册 {} 个内置工具", builtinTools.size());
    }

    /**
     * 注册单个内置工具。
     */
    private void registerBuiltin(String name, String description, BuiltinToolExecutor executor) {
        builtinTools.put(name, new BuiltinToolEntry(name, description, executor));
        allToolInfos.add(new ToolInfo(name, description, "builtin"));
    }

    /**
     * 连接到所有已配置的 MCP 服务器。
     */
    private void connectToConfiguredServers() {
        for (MCPConfig config : configs) {
            if (config instanceof MCPConfig.StdioMCPConfig stdioConfig) {
                connectToStdioServer(stdioConfig);
            } else if (config instanceof MCPConfig.HttpMCPConfig httpConfig) {
                log.info("HTTP MCP 服务器暂不支持直接连接: {}", httpConfig.getName());
            }
        }
    }

    /**
     * 连接到 Stdio 型 MCP 服务器。
     */
    private void connectToStdioServer(MCPConfig.StdioMCPConfig config) {
        try {
            log.info("正在连接到 MCP 服务器: {}", config.getName());
            McpServerConnection connection = new McpServerConnection(
                    config.getName(), config.command(), config.args());
            connection.connect();
            connections.put(config.getName(), connection);

            List<ToolInfo> tools = connection.discoverTools();
            allToolInfos.addAll(tools);
            log.info("已连接到 MCP 服务器 [{}]，发现 {} 个工具", config.getName(), tools.size());
        } catch (Exception e) {
            log.error("连接 MCP 服务器失败: {}", config.getName(), e);
        }
    }

    // ==================== 工具执行 ====================

    /**
     * 执行指定名称的工具。
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 工具执行结果
     */
    public ToolResult executeTool(String toolName, Map<String, Object> args) {
        log.debug("执行工具: {}, 参数: {}", toolName, args);
        try {
            // 先查内置工具
            BuiltinToolEntry builtin = builtinTools.get(toolName);
            if (builtin != null) {
                String output = builtin.executor().execute(args);
                log.debug("内置工具 [{}] 执行成功", toolName);
                return new ToolResult(true, output, null);
            }

            // 再查外部服务器工具
            String serverName = findToolServer(toolName);
            if (serverName != null) {
                McpServerConnection connection = connections.get(serverName);
                if (connection != null && connection.isConnected()) {
                    String result = connection.callTool(toolName, args);
                    return new ToolResult(true, result, null);
                }
                return new ToolResult(false, null, "MCP 服务器未连接: " + serverName);
            }

            return new ToolResult(false, null, "未找到工具: " + toolName);
        } catch (Exception e) {
            log.error("工具 [{}] 执行异常", toolName, e);
            return new ToolResult(false, null, "工具执行错误: " + e.getMessage());
        }
    }

    /**
     * 查找工具所属的服务器名称。
     */
    private String findToolServer(String toolName) {
        for (ToolInfo info : allToolInfos) {
            if (info.name().equals(toolName) && !"builtin".equals(info.server())) {
                return info.server();
            }
        }
        return null;
    }

    // ==================== 配置与列表 ====================

    /**
     * 添加 MCP 服务器配置。
     *
     * @param config MCP 配置
     */
    public void addConfig(MCPConfig config) {
        if (config != null) {
            configs.add(config);
            log.debug("已添加 MCP 配置: {}", config.getName());
        }
    }

    /**
     * 获取所有可用工具信息列表。
     *
     * @return 工具信息列表
     */
    public List<ToolInfo> listTools() {
        return Collections.unmodifiableList(allToolInfos);
    }

    /**
     * 关闭 MCP 客户端，断开所有连接。
     */
    public void shutdown() {
        for (McpServerConnection conn : connections.values()) {
            try {
                conn.disconnect();
            } catch (Exception e) {
                log.warn("断开 MCP 服务器连接异常: {}", e.getMessage());
            }
        }
        connections.clear();
        builtinTools.clear();
        allToolInfos.clear();
        configs.clear();
        initialized = false;
        log.info("MCP 客户端已关闭");
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ==================== 内部类型 ====================

    /**
     * 工具信息记录。
     */
    public record ToolInfo(String name, String description, String server) {}

    /**
     * 工具执行结果。
     */
    public static class ToolResult {
        private final boolean success;
        private final String output;
        private final String error;

        public ToolResult(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public String getError() { return error; }

        /**
         * 将结果转换为 ContentBlock.ToolResultBlock。
         *
         * @param toolCallId 关联的工具调用ID
         * @return 工具结果内容块
         */
        public ContentBlock.ToolResultBlock toToolResultBlock(String toolCallId) {
            String content = success ? output : ("Error: " + error);
            return new ContentBlock.ToolResultBlock(toolCallId, content != null ? content : "", !success);
        }
    }

    /**
     * 内置工具条目。
     */
    private record BuiltinToolEntry(String name, String description, BuiltinToolExecutor executor) {}

    /**
     * 内置工具执行器函数式接口。
     */
    @FunctionalInterface
    private interface BuiltinToolExecutor {
        String execute(Map<String, Object> args) throws Exception;
    }

    /**
     * MCP 初始化异常。
     */
    public static class MCPInitException extends RuntimeException {
        public MCPInitException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ==================== SimpleExpressionEvaluator ====================

    /**
     * 简单数学表达式求值器（递归下降解析器）。
     * <p>
     * 支持 +、-、*、/、括号和数字的算术表达式求值。
     * </p>
     */
    public static class SimpleExpressionEvaluator {

        private String expr;
        private int pos;

        /**
         * 求值数学表达式。
         *
         * @param expression 数学表达式字符串
         * @return 计算结果
         */
        public double evaluate(String expression) {
            // 过滤非法字符
            String sanitized = expression.replaceAll("[^0-9+\\-*/.()\\s]", "");
            if (sanitized.isBlank()) {
                throw new IllegalArgumentException("无效的表达式: " + expression);
            }
            this.expr = sanitized.replaceAll("\\s+", "");
            this.pos = 0;

            double result = parseExpression();
            if (pos < expr.length()) {
                throw new IllegalArgumentException("表达式解析未完成，剩余: " + expr.substring(pos));
            }
            return result;
        }

        /** 解析加减法 */
        private double parseExpression() {
            double value = parseTerm();
            while (pos < expr.length()) {
                char c = expr.charAt(pos);
                if (c == '+') { pos++; value += parseTerm(); }
                else if (c == '-') { pos++; value -= parseTerm(); }
                else break;
            }
            return value;
        }

        /** 解析乘除法 */
        private double parseTerm() {
            double value = parseFactor();
            while (pos < expr.length()) {
                char c = expr.charAt(pos);
                if (c == '*') { pos++; value *= parseFactor(); }
                else if (c == '/') { pos++; value /= parseFactor(); }
                else break;
            }
            return value;
        }

        /** 解析因子：括号或数字 */
        private double parseFactor() {
            // 处理括号
            if (pos < expr.length() && expr.charAt(pos) == '(') {
                pos++; // 跳过 '('
                double value = parseExpression();
                if (pos < expr.length() && expr.charAt(pos) == ')') {
                    pos++; // 跳过 ')'
                }
                return value;
            }
            // 处理正负号
            int start = pos;
            if (pos < expr.length() && (expr.charAt(pos) == '-' || expr.charAt(pos) == '+')) {
                pos++;
            }
            // 解析数字
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("期望数字，位置: " + pos);
            }
            return Double.parseDouble(expr.substring(start, pos));
        }
    }
}
