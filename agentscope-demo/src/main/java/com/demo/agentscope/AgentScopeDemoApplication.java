package com.demo.agentscope;

import com.demo.agentscope.agent.Agent;
import com.demo.agentscope.agent.AgentTeam;
import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.credential.DefaultCredentialProvider;
import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.filepermission.FilePermissionConfig;
import com.demo.agentscope.filepermission.FilePermissionManager;
import com.demo.agentscope.filepermission.SecureFileWorkspace;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.mcp.MCPConfig;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.middleware.ContextCompressionMiddleware;
import com.demo.agentscope.middleware.MiddlewareChain;
import com.demo.agentscope.middleware.ReplyBudgetControlMiddleware;
import com.demo.agentscope.middleware.TracingMiddleware;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionDecision;
import com.demo.agentscope.permission.PermissionEngine;
import com.demo.agentscope.permission.PermissionMiddleware;
import com.demo.agentscope.permission.PermissionMode;
import com.demo.agentscope.permission.PermissionRule;
import com.demo.agentscope.ui.ConsoleUI;
import com.demo.agentscope.workspace.LocalWorkspace;
import com.demo.agentscope.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * AgentScope 2.0 演示应用入口。
 * <p>
 * 启动一个交互式 REPL 循环，演示智能体的核心功能：
 * 凭证管理、MCP 工具调用、权限控制、中间件链、工作空间管理及多智能体团队协作。
 * </p>
 *
 * <pre>
 * 启动流程：
 *   1. 打印横幅
 *   2. 创建凭证提供者，自动检测环境变量中的 API Key
 *   3. 确定主提供商（优先 dashscope，其次 openai）
 *   4. 创建 MCP 客户端，注册内置工具和外部服务器
 *   5. 创建权限引擎，配置默认规则
 *   6. 创建工作空间管理器
 *   7. 创建智能体，挂载中间件链
 *   8. 进入 REPL 主循环
 * </pre>
 */
public class AgentScopeDemoApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeDemoApplication.class);

    /** 智能体系统提示词 */
    private static final String SYSTEM_PROMPT = """
            你是 AgentScope 2.0 智能体助手。你可以通过调用工具来帮助用户完成各种任务。
            可用工具包括：
            - get_weather: 查询城市天气
            - calculate: 数学计算
            - search: 搜索信息
            - get_time: 获取当前时间
            - read_file: 读取文件内容（参数: path）
            - write_file: 写入文件（参数: path, content）
            - edit_file: 编辑文件（参数: path, old_text, new_text）
            - list_files: 列出目录内容（参数: dir）
            - execute_python: 执行 Python 代码并返回结果（参数: code，支持多行）
            - execute_command: 执行 Shell 命令并返回结果（参数: command）
            - install_package: 通过 pip 安装 Python 第三方库（参数: package）

            在回答问题时，如果需要获取实时信息或执行操作，请优先使用可用的工具。
            当用户要求读取或写入文件时，使用对应的文件工具。
            当需要计算、数据处理、网络请求或运行脚本时，使用 execute_python 执行代码并直接返回结果，不要只写代码让用户自己跑。
            当执行失败时，请分析错误原因，修正代码后重试。
            文件操作受权限管控，只能访问授权目录下的文件。
            代码执行受安全策略限制（禁止危险命令、30秒超时）。
            回答时请保持简洁、准确，使用中文回复。
            """;

    public static void main(String[] args) {
        // 1. 打印横幅
        ConsoleUI.printBanner();

        try {
            // 2. 创建凭证提供者，自动检测环境变量
            DefaultCredentialProvider credentialProvider = new DefaultCredentialProvider();
            log.info("凭证提供者已创建，可用提供商: {}", credentialProvider.getAvailableProviders());

            // 3. 确定主提供商（优先 dashscope，其次 openai）
            String primaryProvider = determinePrimaryProvider(credentialProvider);
            credentialProvider.setPrimaryProvider(primaryProvider);
            String modelName = credentialProvider.getModelName(primaryProvider);

            // 4. 创建 MCP 客户端
            MCPClient mcpClient = new MCPClient();
            // 从环境变量检测外部 MCP 服务器配置
            configureMCPServers(mcpClient);
            mcpClient.initialize();

            // 4.1 创建安全文件工作空间并注册文件读写工具
            SecureFileWorkspace secureFileWorkspace = createSecureFileWorkspace();
            mcpClient.registerFileTools(secureFileWorkspace);

            // 4.2 创建代码执行管理器并注册代码执行工具
            CodeExecutionManager executionManager = new CodeExecutionManager(
                    secureFileWorkspace.getPermissionManager().getBaseDir());
            mcpClient.registerCodeExecutionTools(executionManager);

            // 5. 创建权限引擎，配置默认规则
            PermissionEngine permissionEngine = createPermissionEngine();

            // 6. 创建工作空间管理器
            WorkspaceManager workspaceManager = new WorkspaceManager();

            // 7. 创建聊天模型客户端
            ChatModel chatModel = new ChatModel(credentialProvider);

            // 8. 创建智能体
            Agent agent = new Agent(
                    "AgentScope-2.0",
                    SYSTEM_PROMPT,
                    chatModel,
                    mcpClient,
                    credentialProvider,
                    permissionEngine,
                    workspaceManager,
                    primaryProvider
            );

            // 挂载中间件链
            MiddlewareChain chain = agent.getMiddlewareChain();
            chain.add(new TracingMiddleware());
            chain.add(new ContextCompressionMiddleware());
            chain.add(new PermissionMiddleware(permissionEngine));
            chain.add(new ReplyBudgetControlMiddleware());

            // 打印启动信息
            printStartupInfo(primaryProvider, modelName, mcpClient, permissionEngine);

            // 9. 进入 REPL 主循环
            runREPL(agent, credentialProvider, chatModel, mcpClient, permissionEngine, workspaceManager, primaryProvider);

        } catch (Exception e) {
            log.error("应用启动失败", e);
            ConsoleUI.printError("应用启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 确定主提供商。
     * <p>
     * 优先级：dashscope > openai > 其他可用提供商。
     * 如果没有任何可用提供商，则默认使用 openai（将在运行时因缺少 API Key 而报错）。
     * </p>
     */
    private static String determinePrimaryProvider(DefaultCredentialProvider credentialProvider) {
        List<String> available = credentialProvider.getAvailableProviders();
        log.debug("可用提供商列表: {}", available);

        if (available.contains("dashscope")) {
            return "dashscope";
        }
        if (available.contains("openai")) {
            return "openai";
        }
        if (!available.isEmpty()) {
            return available.get(0);
        }

        // 没有可用提供商，默认使用 openai
        log.warn("未检测到任何可用的 API Key，将使用 openai 作为默认提供商");
        return "openai";
    }

    /**
     * 从环境变量检测并配置外部 MCP 服务器。
     * <p>
     * 读取 MCP_SERVERS 环境变量，格式为 "command:arg1,arg2"，
     * 例如 "npx:-y,@modelcontextprotocol/server-filesystem,/tmp"。
     * </p>
     */
    private static void configureMCPServers(MCPClient mcpClient) {
        String mcpServers = System.getenv("MCP_SERVERS");
        if (mcpServers == null || mcpServers.isBlank()) {
            log.debug("未检测到 MCP_SERVERS 环境变量，跳过外部 MCP 配置");
            return;
        }

        try {
            String[] parts = mcpServers.split(":", 2);
            String command = parts[0].trim();
            List<String> args = new ArrayList<>();
            if (parts.length > 1) {
                for (String arg : parts[1].split(",")) {
                    String trimmed = arg.trim();
                    if (!trimmed.isEmpty()) {
                        args.add(trimmed);
                    }
                }
            }
            MCPConfig config = new MCPConfig.StdioMCPConfig("env-mcp-server", command, args);
            mcpClient.addConfig(config);
            log.info("已从环境变量添加 MCP 服务器: command={}, args={}", command, args);
        } catch (Exception e) {
            log.warn("解析 MCP_SERVERS 环境变量失败: {}", mcpServers, e);
        }
    }

    /**
     * 创建安全文件工作空间。
     * <p>
     * 配置文件权限策略：允许在工作空间目录下读写文件，
     * 但禁止访问 .env、secrets 等敏感路径，禁止 exe/sh 等可执行文件。
     * 工作空间根目录默认为当前目录下的 workspace 文件夹。
     * </p>
     *
     * @return 安全文件工作空间
     */
    private static SecureFileWorkspace createSecureFileWorkspace() {
        // 工作空间根目录
        String workspaceDir = System.getenv().getOrDefault("WORKSPACE_DIR", "workspace");
        Path baseDir = Path.of(workspaceDir).toAbsolutePath().normalize();

        // 创建根目录（如果不存在）
        baseDir.toFile().mkdirs();

        // 配置文件权限策略
        FilePermissionConfig config = new FilePermissionConfig.Builder()
                .allowReadWrite("**")
                .denyPath("**/.env")
                .denyPath("**/secrets/**")
                .denyPath("**/.git/**")
                .denyExtension("exe")
                .denyExtension("sh")
                .denyExtension("bat")
                .maxFileSize(10 * 1024 * 1024)  // 10MB
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.DENY_ALL)
                .build();

        FilePermissionManager permissionManager = new FilePermissionManager(baseDir, config);
        LocalWorkspace localWorkspace = new LocalWorkspace(baseDir.toString());
        localWorkspace.initialize();

        SecureFileWorkspace secureWorkspace = new SecureFileWorkspace(localWorkspace, permissionManager);
        log.info("安全文件工作空间已创建: baseDir={}, 权限策略={}", baseDir, config.getDefaultPolicy());
        return secureWorkspace;
    }

    /**
     * 创建权限引擎，配置默认权限规则。
     * <p>
     * 默认规则：
     * <ul>
     *   <li>ALLOW: get_weather, search, get_time, calculate - 安全工具</li>
     *   <li>ALLOW: read_file, write_file, edit_file, list_files - 文件工具
     *       （文件级权限由 SecureFileWorkspace 的 FilePermissionManager 管控）</li>
     *   <li>ALLOW: execute_python, execute_command, install_package - 代码执行工具
     *       （命令级安全由 CommandSafetyChecker 管控，30秒超时）</li>
     *   <li>ASK: bash - 需要人工确认的 Shell 工具</li>
     *   <li>DENY: 危险操作模式 - 内置引擎自动拦截</li>
     * </ul>
     * 默认模式为 DONT_ASK（ASK 决策自动降级为 DENY）。
     * </p>
     */
    private static PermissionEngine createPermissionEngine() {
        PermissionEngine engine = new PermissionEngine(PermissionMode.DONT_ASK, true);

        // 允许安全工具
        engine.addRule(new PermissionRule("get_weather", PermissionDecision.ALLOW, "天气查询为安全操作"));
        engine.addRule(new PermissionRule("search", PermissionDecision.ALLOW, "搜索为安全操作"));
        engine.addRule(new PermissionRule("get_time", PermissionDecision.ALLOW, "时间查询为安全操作"));
        engine.addRule(new PermissionRule("calculate", PermissionDecision.ALLOW, "计算为安全操作"));

        // 允许文件工具（文件级权限由 FilePermissionManager 管控）
        engine.addRule(new PermissionRule("read_file", PermissionDecision.ALLOW, "文件读取，路径权限由 FilePermissionManager 管控"));
        engine.addRule(new PermissionRule("write_file", PermissionDecision.ALLOW, "文件写入，路径权限由 FilePermissionManager 管控"));
        engine.addRule(new PermissionRule("edit_file", PermissionDecision.ALLOW, "文件编辑，路径权限由 FilePermissionManager 管控"));
        engine.addRule(new PermissionRule("list_files", PermissionDecision.ALLOW, "目录列表，路径权限由 FilePermissionManager 管控"));

        // 允许代码执行工具（命令级安全由 CommandSafetyChecker 管控）
        engine.addRule(new PermissionRule("execute_python", PermissionDecision.ALLOW, "Python 执行，危险操作由 CommandSafetyChecker 拦截"));
        engine.addRule(new PermissionRule("execute_command", PermissionDecision.ALLOW, "Shell 命令执行，危险操作由 CommandSafetyChecker 拦截"));
        engine.addRule(new PermissionRule("install_package", PermissionDecision.ALLOW, "pip 安装，包名经过安全检查"));

        // 需要确认的工具
        engine.addRule(new PermissionRule("bash", PermissionDecision.ASK, "Shell 命令需人工确认"));

        log.info("权限引擎已创建，模式={}，规则数={}", engine.getMode(), engine.getRules().size());
        return engine;
    }

    /**
     * 打印启动信息摘要。
     */
    private static void printStartupInfo(String provider, String model,
                                          MCPClient mcpClient, PermissionEngine permissionEngine) {
        ConsoleUI.printSuccess("AgentScope 2.0 初始化完成");
        ConsoleUI.printInfo("提供商: " + provider);
        ConsoleUI.printInfo("模型: " + model);
        ConsoleUI.printInfo("可用工具: " + mcpClient.listTools().size() + " 个");
        ConsoleUI.printInfo("权限模式: " + permissionEngine.getMode());
        ConsoleUI.printSeparator();
        ConsoleUI.printInfo("输入 help 查看可用命令，或直接输入问题开始对话");
        ConsoleUI.printSeparator();
    }

    /**
     * REPL 主循环。
     * <p>
     * 支持的命令：
     * <ul>
     *   <li>exit/quit - 退出应用</li>
     *   <li>history - 查看对话历史</li>
     *   <li>status - 查看智能体状态</li>
     *   <li>clear - 重置智能体</li>
     *   <li>team create - 创建智能体团队</li>
     *   <li>team status - 查看团队状态</li>
     *   <li>team dissolve - 解散团队</li>
     *   <li>tools - 列出可用工具</li>
     *   <li>events - 切换事件展示模式</li>
     *   <li>permission - 显示权限规则</li>
     *   <li>help - 显示帮助</li>
     *   <li>其他 - 作为用户输入发送给智能体</li>
     * </ul>
     * </p>
     */
    private static void runREPL(Agent agent,
                                CredentialProvider credentialProvider,
                                ChatModel chatModel,
                                MCPClient mcpClient,
                                PermissionEngine permissionEngine,
                                WorkspaceManager workspaceManager,
                                String providerName) {
        AgentTeam team = null;
        boolean showEvents = false;

        while (true) {
            String input = ConsoleUI.promptUser();
            if (input == null) {
                // 输入流结束
                break;
            }

            String trimmed = input.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // ---- 命令处理 ----

            if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
                // 退出应用
                ConsoleUI.printInfo("正在关闭...");
                if (team != null) {
                    team.dissolve();
                }
                agent.shutdown();
                mcpClient.shutdown();
                ConsoleUI.printSuccess("再见！");
                break;
            }

            if ("history".equalsIgnoreCase(trimmed)) {
                // 查看对话历史
                ConsoleUI.printHistory(agent.getContext());
                continue;
            }

            if ("status".equalsIgnoreCase(trimmed)) {
                // 查看智能体状态
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("名称", agent.getName());
                status.put("ID", agent.getId());
                status.put("提供商", agent.getProviderName());
                status.put("上下文消息数", agent.getContext().size());
                status.put("最大迭代次数", agent.getMaxIterations());
                status.put("中间件数", agent.getMiddlewareChain().size());
                status.putAll(agent.getState());
                ConsoleUI.printAgentStatus(status);
                continue;
            }

            if ("clear".equalsIgnoreCase(trimmed)) {
                // 重置智能体
                agent.reset();
                if (team != null) {
                    team.dissolve();
                    team = null;
                }
                ConsoleUI.printSuccess("智能体已重置，对话历史已清空");
                continue;
            }

            if ("team create".equalsIgnoreCase(trimmed)) {
                // 创建智能体团队
                if (team != null) {
                    ConsoleUI.printWarning("团队已存在，请先解散当前团队");
                    continue;
                }
                team = new AgentTeam(
                        agent, chatModel, mcpClient,
                        credentialProvider, permissionEngine, workspaceManager, providerName
                );
                // 将团队工具描述注入领导者系统提示词
                String teamToolsDesc = team.getTeamToolsDescription();
                ConsoleUI.printSuccess("智能体团队已创建");
                ConsoleUI.printInfo("领导者: " + agent.getName());
                ConsoleUI.printInfo("团队ID: " + team.getTeamId());
                ConsoleUI.printSeparator();
                ConsoleUI.printInfo("团队工具描述已注入领导者提示词");
                continue;
            }

            if ("team status".equalsIgnoreCase(trimmed)) {
                // 查看团队状态
                if (team == null) {
                    ConsoleUI.printWarning("当前没有活跃的团队，使用 team create 创建");
                } else {
                    ConsoleUI.printTeamStatus(team.getStatus());
                }
                continue;
            }

            if ("team dissolve".equalsIgnoreCase(trimmed)) {
                // 解散团队
                if (team == null) {
                    ConsoleUI.printWarning("当前没有活跃的团队");
                } else {
                    team.dissolve();
                    team = null;
                    ConsoleUI.printSuccess("团队已解散");
                }
                continue;
            }

            if ("tools".equalsIgnoreCase(trimmed)) {
                // 列出可用工具
                var tools = mcpClient.listTools();
                System.out.println();
                System.out.println("\u001B[1m  🔧 可用工具 (" + tools.size() + ")\u001B[0m");
                ConsoleUI.printSeparator();
                for (var tool : tools) {
                    System.out.println("  \u001B[36m• " + tool.name() + "\u001B[0m - " + tool.description()
                            + " [" + tool.server() + "]");
                }
                ConsoleUI.printSeparator();
                System.out.println();
                continue;
            }

            if ("events".equalsIgnoreCase(trimmed)) {
                // 切换事件展示模式
                showEvents = !showEvents;
                ConsoleUI.printInfo("事件展示模式: " + (showEvents ? "开启" : "关闭"));
                continue;
            }

            if ("permission".equalsIgnoreCase(trimmed)) {
                // 显示权限规则
                System.out.println();
                System.out.println("\u001B[1m  🔒 权限规则\u001B[0m");
                ConsoleUI.printSeparator();
                System.out.println("  模式: " + permissionEngine.getMode());
                System.out.println("  内置检查: " + (permissionEngine.isBuiltInChecksEnabled() ? "启用" : "禁用"));
                System.out.println();
                var rules = permissionEngine.getRules();
                for (PermissionRule rule : rules) {
                    String decisionIcon = switch (rule.getAction()) {
                        case ALLOW -> "\u001B[32m✓ ALLOW\u001B[0m";
                        case DENY -> "\u001B[31m✗ DENY\u001B[0m";
                        case ASK -> "\u001B[33m? ASK\u001B[0m";
                    };
                    System.out.println("  " + decisionIcon + "  " + rule.getToolName()
                            + " - " + rule.getReason()
                            + (rule.isBypassImmune() ? " [旁路免疫]" : ""));
                }
                ConsoleUI.printSeparator();
                System.out.println();
                continue;
            }

            if ("help".equalsIgnoreCase(trimmed)) {
                ConsoleUI.printHelp();
                continue;
            }

            // ---- 智能体对话 ----

            try {
                long startTime = System.currentTimeMillis();
                Msg response;

                if (team != null && team.isActive()) {
                    // 如果团队存在，委托给团队回复
                    response = team.reply(trimmed);
                } else {
                    // 否则由单个智能体回复
                    response = agent.reply(trimmed);
                }

                long duration = System.currentTimeMillis() - startTime;

                // 打印响应
                ConsoleUI.printAgentResponse(response);

                // 如果开启事件展示模式，打印追踪摘要
                if (showEvents) {
                    ConsoleUI.printInfo("响应耗时: " + duration + "ms");
                }

            } catch (Exception e) {
                log.error("处理用户输入异常: {}", trimmed, e);
                ConsoleUI.printError("处理失败: " + e.getMessage());
                ConsoleUI.printInfo("输入 help 查看可用命令");
            }
        }
    }
}
