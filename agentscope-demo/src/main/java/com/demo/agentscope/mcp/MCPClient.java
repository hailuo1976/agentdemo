package com.demo.agentscope.mcp;

import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.filepermission.SecureFileWorkspace;
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
     * 内置工具已移除，仅保留文件操作、代码执行和团队工具等核心功能。
     * </p>
     */
    public void registerBuiltinTools() {
        log.info("内置工具注册完成（已移除演示工具）");
    }

    /**
     * 注册单个内置工具。
     */
    private void registerBuiltin(String name, String description, BuiltinToolExecutor executor) {
        registerBuiltin(name, description, "", executor);
    }

    /**
     * 注册单个内置工具（带参数 schema）。
     */
    private void registerBuiltin(String name, String description, String parametersJson,
                                  BuiltinToolExecutor executor) {
        builtinTools.put(name, new BuiltinToolEntry(name, description, executor));
        allToolInfos.add(new ToolInfo(name, description, "builtin", parametersJson));
    }

    /**
     * 注册自定义工具（公开接口，供外部组件如 AgentTeam 注入团队工具）。
     *
     * @param name           工具名称
     * @param description    工具描述
     * @param parametersJson 参数 schema（JSON 字符串）
     * @param executor       工具执行器
     */
    public void registerCustomTool(String name, String description, String parametersJson,
                                    BuiltinToolExecutor executor) {
        registerBuiltin(name, description, parametersJson, executor);
        log.info("已注册自定义工具: {}", name);
    }

    /** 安全文件工作空间（用于文件读写工具） */
    private SecureFileWorkspace fileWorkspace;

    /**
     * 注册文件读写工具，通过 SecureFileWorkspace 执行（受权限管控）。
     * <p>
     * 注册 4 个文件工具：read_file、write_file、edit_file、list_files。
     * 所有操作经过文件权限验证，拒绝未授权的路径访问。
     * </p>
     *
     * @param fileWorkspace 安全文件工作空间
     */
    public void registerFileTools(SecureFileWorkspace fileWorkspace) {
        this.fileWorkspace = Objects.requireNonNull(fileWorkspace, "文件工作空间不能为null");

        // 默认文件名：LLM 未传 path 时使用，避免空路径触发权限拒绝
        final String DEFAULT_FILE = "default.txt";
        final String DEFAULT_DIR = ".";

        String readFileParams = """
                {"type":"object","properties":{"path":{"type":"string","description":"要读取的文件路径（相对于工作空间根目录，不传则读取 default.txt）"}},"required":[]}""";
        registerBuiltin("read_file", "读取指定路径的文件内容", readFileParams, (args) -> {
            String raw = String.valueOf(args.getOrDefault("path", ""));
            String path = raw == null || raw.isBlank() ? DEFAULT_FILE : raw;
            try {
                return fileWorkspace.readFile(path);
            } catch (com.demo.agentscope.filepermission.FilePermissionDeniedException e) {
                throw new RuntimeException("文件读取被拒绝: " + e.getMessage(), e);
            }
        });

        String writeFileParams = """
                {"type":"object","properties":{"path":{"type":"string","description":"要写入的文件路径（不传则写入 default.txt）"},"content":{"type":"string","description":"文件内容"}},"required":["content"]}""";
        registerBuiltin("write_file", "将内容写入指定路径的文件", writeFileParams, (args) -> {
            String raw = String.valueOf(args.getOrDefault("path", ""));
            String path = raw == null || raw.isBlank() ? DEFAULT_FILE : raw;
            String content = String.valueOf(args.getOrDefault("content", ""));
            try {
                fileWorkspace.writeFile(path, content);
                return "文件写入成功: " + path;
            } catch (com.demo.agentscope.filepermission.FilePermissionDeniedException e) {
                throw new RuntimeException("文件写入被拒绝: " + e.getMessage(), e);
            }
        });

        String editFileParams = """
                {"type":"object","properties":{"path":{"type":"string","description":"要编辑的文件路径（不传则编辑 default.txt）"},"old_text":{"type":"string","description":"要替换的旧文本"},"new_text":{"type":"string","description":"替换后的新文本"}},"required":["old_text","new_text"]}""";
        registerBuiltin("edit_file", "编辑文件，替换指定文本", editFileParams, (args) -> {
            String raw = String.valueOf(args.getOrDefault("path", ""));
            String path = raw == null || raw.isBlank() ? DEFAULT_FILE : raw;
            String oldText = String.valueOf(args.getOrDefault("old_text", ""));
            String newText = String.valueOf(args.getOrDefault("new_text", ""));
            try {
                fileWorkspace.editFile(path, oldText, newText);
                return "文件编辑成功: " + path;
            } catch (com.demo.agentscope.filepermission.FilePermissionDeniedException e) {
                throw new RuntimeException("文件编辑被拒绝: " + e.getMessage(), e);
            }
        });

        String listFilesParams = """
                {"type":"object","properties":{"dir":{"type":"string","description":"要列出的目录路径（不传则列出工作空间根目录）"}},"required":[]}""";
        registerBuiltin("list_files", "列出指定目录下的文件和子目录", listFilesParams, (args) -> {
            String raw = String.valueOf(args.getOrDefault("dir", ""));
            String dir = raw == null || raw.isBlank() ? DEFAULT_DIR : raw;
            try {
                List<String> files = fileWorkspace.listFiles(dir);
                return String.join("\n", files);
            } catch (com.demo.agentscope.filepermission.FilePermissionDeniedException e) {
                throw new RuntimeException("目录列表被拒绝: " + e.getMessage(), e);
            }
        });

        log.info("已注册 4 个文件读写工具（受权限管控，未传 path 时使用默认位置）");
    }

    /**
     * 注册代码执行工具：execute_python、execute_command、install_package。
     * <p>
     * 所有执行经过安全检查（拦截危险命令）并受超时限制（默认30秒）。
     * 网络访问允许，工作目录为指定工作空间。
     * </p>
     *
     * @param executionManager 代码执行管理器
     */
    public void registerCodeExecutionTools(CodeExecutionManager executionManager) {
        Objects.requireNonNull(executionManager, "代码执行管理器不能为null");

        String pythonParams = """
                {"type":"object","properties":{"code":{"type":"string","description":"要执行的 Python 代码，支持多行"}},"required":["code"]}""";
        registerBuiltin("execute_python", "执行 Python 代码并返回输出结果（stdout + stderr）", pythonParams, (args) -> {
            String code = String.valueOf(args.getOrDefault("code", ""));
            CodeExecutionManager.ExecutionResult result = executionManager.executePython(code);
            return result.toString();
        });

        String commandParams = """
                {"type":"object","properties":{"command":{"type":"string","description":"要执行的 Shell 命令"}},"required":["command"]}""";
        registerBuiltin("execute_command", "执行 Shell 命令并返回输出结果（stdout + stderr）", commandParams, (args) -> {
            String command = String.valueOf(args.getOrDefault("command", ""));
            CodeExecutionManager.ExecutionResult result = executionManager.executeCommand(command);
            return result.toString();
        });

        String packageParams = """
                {"type":"object","properties":{"package":{"type":"string","description":"要安装的 Python 包名，如 requests"}},"required":["package"]}""";
        registerBuiltin("install_package", "通过 pip 安装 Python 第三方库", packageParams, (args) -> {
            String packageName = String.valueOf(args.getOrDefault("package", ""));
            CodeExecutionManager.ExecutionResult result = executionManager.installPackage(packageName);
            return result.toString();
        });

        log.info("已注册 3 个代码执行工具（execute_python/execute_command/install_package）");
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
     *
     * @param name           工具名称
     * @param description    工具描述
     * @param server         所属服务器
     * @param parametersJson 参数 schema（JSON 字符串，遵循 JSON Schema 规范）
     */
    public record ToolInfo(String name, String description, String server, String parametersJson) {

        /** 向后兼容：无参数 schema 的构造方法 */
        public ToolInfo(String name, String description, String server) {
            this(name, description, server, "");
        }
    }

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
     * <p>
     * 公开访问以便外部组件（如 AgentTeam）通过 {@link #registerCustomTool} 注入团队工具。
     * </p>
     */
    @FunctionalInterface
    public interface BuiltinToolExecutor {
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
