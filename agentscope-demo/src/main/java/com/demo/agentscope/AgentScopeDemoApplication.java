package com.demo.agentscope;

import com.demo.agentscope.agent.Agent;
import com.demo.agentscope.agent.AgentTeam;
import com.demo.agentscope.agent.SystemPrompts;
import com.demo.agentscope.cache.IntermediateResultManager;
import com.demo.agentscope.config.AgentLimits;
import com.demo.agentscope.context.ContextManager;
import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.credential.DefaultCredentialProvider;
import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.filepermission.FilePermissionConfig;
import com.demo.agentscope.filepermission.FilePermissionManager;
import com.demo.agentscope.filepermission.SecureFileWorkspace;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.mcp.MCPConfig;
import com.demo.agentscope.memory.LongTermMemory;
import com.demo.agentscope.memory.ShortTermMemory;
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
import com.demo.agentscope.session.SessionLogger;
import com.demo.agentscope.session.SessionLoggingMiddleware;
import com.demo.agentscope.session.SessionRecovery;
import com.demo.agentscope.session.SessionRecovery.RecoveredSession;
import com.demo.agentscope.session.SessionRecovery.SessionSummary;
import com.demo.agentscope.ui.ConsoleUI;
import com.demo.agentscope.ui.VerbosityLevel;
import com.demo.agentscope.stock.StockToolService;
import com.demo.agentscope.stock.data.AkShareDataSource;
import com.demo.agentscope.stock.data.StockDataService;
import com.demo.agentscope.stock.data.TuShareDataSource;
import com.demo.agentscope.stock.filter.StockFilterService;
import com.demo.agentscope.team.ArtifactManager;
import com.demo.agentscope.stock.industry.IndustryService;
import com.demo.agentscope.stock.scoring.LeaderScoringService;
import com.demo.agentscope.workspace.LocalWorkspace;
import com.demo.agentscope.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.Locale;

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

    /** 股票工具功能开关（默认关闭，避免污染非股票场景的模型推理）。 */
    private static final boolean STOCK_TOOLS_ENABLED =
            Boolean.parseBoolean(System.getenv().getOrDefault("STOCK_TOOLS_ENABLED", "false"));

    /** 股票工具名称清单，用于运行期 /stock off 反注册。 */
    private static final List<String> STOCK_TOOL_NAMES = List.of(
            "list_industries", "select_industry_leaders",
            "get_stock_detail", "update_stock_data");

    /** 系统提示词中追加的股票工具描述，便于 /stock on 时动态注入。 */
    private static final String STOCK_PROMPT_ADDENDUM = """

            股票研究工具：
            - list_industries: 列出申万行业分类树（参数: level, parent）
            - select_industry_leaders: 按行业筛选龙头股（参数: industry, level, top_n, filters）
            - get_stock_detail: 查询单只股票的完整指标（参数: code, force_refresh）
            - update_stock_data: 刷新股票数据（参数: scope, code/industry, data_type, force）

            股票数据来源于 akshare（主）+ tushare（备），带缓存机制（行情1h、基本面24h、行业排名7d）。
            龙头评分基于市值、营收、ROE、品牌度多因子加权计算，行业内归一化到 0-100 分。
            """;

    /**
     * 构建系统提示词。
     *
     * @param stockEnabled 是否启用股票工具，启用时追加股票工具描述与数据源说明
     * @param limits       运行时限制（迭代/token 预算/超时等），用于动态拼装「运行时约束」段
     */
    private static String buildSystemPrompt(boolean stockEnabled, AgentLimits limits) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是 AgentScope 2.0 智能体助手。你可以通过调用工具来帮助用户完成各种任务。

                """);
        sb.append(SystemPrompts.TOOL_CALL_NORMS);
        sb.append("""

                ## 工作守则

                1. 需要文件操作时，永远显式指定 path，绝不省略。如果用户未给路径，根据任务上下文推断一个有意义的文件名。
                2. 当用户要求读取或写入文件时，使用对应的文件工具。
                3. 当需要计算、数据处理、网络请求或运行脚本时，使用 execute_python 执行代码并直接返回结果，不要只写代码让用户自己跑。
                4. 当执行失败时，请分析错误原因，修正代码后重试，不要无脑重试。
                5. 文件操作受权限管控，只能访问授权目录下的文件。
                6. 代码执行受安全策略限制（禁止危险命令、超时见下方约束）。
                """);
        sb.append(buildRuntimeConstraints(limits));
        sb.append("""

                ## 工具结果处理规则
                - 上下文中除最新一条外，所有工具结果都会被自动摘要（块前缀含「此工具结果已摘要」与 tool_call_id）。
                - 原始完整内容已归档，需要查看时调用 `get_full_tool_output` 工具，参数为 `tool_call_id`。
                - 仅在确实需要完整细节（精确定位行号、核查数据、二次分析）时才取回全量，避免不必要的上下文膨胀。
                """);
        if (stockEnabled) {
            sb.append(STOCK_PROMPT_ADDENDUM);
        }
        sb.append("回答时请保持简洁、准确，使用中文回复。\n");
        return sb.toString();
    }

    /**
     * 拼装「运行时约束」段，所有数值取自 {@link AgentLimits}，确保单一来源。
     * <p>
     * 设计原因：把这些约束写进系统提示词，模型才能在触达上限前主动收敛、
     * 或换用允许的工具/路径，而不是反复试错浪费预算。
     * </p>
     */
    private static String buildRuntimeConstraints(AgentLimits limits) {
        return String.format(Locale.ROOT, """

                ## 运行时约束（请主动管理预算）

                - **迭代上限**：%d 轮工具调用。剩余约 %d 轮时会收到预算告警，请评估能否在剩余轮次内收敛；若不能，请总结当前进度与未完成项。
                - **Token 预算**：单次回复约 %d tokens，上下文窗口约 %d tokens。超预算会强制终止本轮回复。
                - **工具结果摘要**：单条工具结果超过 %d 字符会被自动摘要（前缀含「已被自动摘要」标记），如需精确数据请缩小查询范围重新调用。
                - **文件大小上限**：%s；危险扩展名（exe/sh/bat 等）与 .env、secrets、.git 路径会被拒绝。
                - **命令执行**：单条命令超时 %d 秒；workspace 操作超时 %d 秒；危险模式（rm -rf、curl|sh 等）会被权限引擎拒绝，tool_result 会携带具体原因。
                - **权限拒绝处理**：被拒绝后请阅读 tool_result 中的原因，调整参数或换用允许的工具；不要重复尝试同一参数。
                """,
                limits.getMaxIterations(),
                limits.getIterationWarnRemaining(),
                limits.getReplyBudgetTokens(),
                limits.getMaxContextTokens(),
                limits.getToolResultSummaryThreshold(),
                limits.getMaxFileSizeBytes() > 0 ? limits.getMaxFileSizeBytes() + " bytes" : "不限",
                limits.getCommandTimeoutSeconds(),
                limits.getWorkspaceTimeoutSeconds());
    }

    public static void main(String[] args) {
        // 1. 打印横幅
        ConsoleUI.printBanner();

        try {
            // 1.1 加载运行时限制（代码默认值 → 环境变量覆盖；REPL /config 之后还能再覆盖）
            AgentLimits limits = new AgentLimits().loadFromEnv();
            log.info("运行时限制已加载: {}", limits);

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
            SecureFileWorkspace secureFileWorkspace = createSecureFileWorkspace(limits);
            mcpClient.registerFileTools(secureFileWorkspace);

            // 4.2 创建代码执行管理器并注册代码执行工具
            CodeExecutionManager executionManager = new CodeExecutionManager(
                    secureFileWorkspace.getPermissionManager().getBaseDir(),
                    limits.getCommandTimeoutSeconds());
            mcpClient.registerCodeExecutionTools(executionManager);

            // 4.3 创建中间结果管理器并注册缓存工具
            Path workspaceDir = secureFileWorkspace.getPermissionManager().getBaseDir();
            IntermediateResultManager cacheManager = new IntermediateResultManager(
                    workspaceDir.resolve("cache/intermediate"),
                    Duration.ofHours(24));
            mcpClient.registerCacheTools(cacheManager);

            // 4.3.1 工具输出归档器 + get_full_tool_output 内置工具
            com.demo.agentscope.tool.ToolOutputArchive toolOutputArchive =
                    new com.demo.agentscope.tool.ToolOutputArchive(
                            workspaceDir.resolve("cache/tool_outputs"));
            String getFullToolOutputSchema =
                    "{\"type\":\"object\",\"properties\":{\"tool_call_id\":{\"type\":\"string\",\"description\":\"要提取完整输出的工具调用 ID\"}},\"required\":[\"tool_call_id\"]}";
            mcpClient.registerCustomTool(
                    "get_full_tool_output",
                    "提取某个工具结果的完整原始内容（上下文中只保留了摘要时使用）",
                    getFullToolOutputSchema,
                    toolArgs -> {
                        Object raw = toolArgs.get("tool_call_id");
                        String toolCallId = raw != null ? String.valueOf(raw) : "";
                        if (toolCallId.isBlank()) {
                            return "错误：tool_call_id 不能为空";
                        }
                        String full = toolOutputArchive.getFullOutput(toolCallId);
                        if (full != null) {
                            return full;
                        }
                        java.util.Map<String, ?> available = toolOutputArchive.listAll();
                        return available.isEmpty()
                                ? "[当前没有已归档的工具输出]"
                                : "[未找到 tool_call_id=" + toolCallId + " 对应的归档输出。可用的 ID："
                                        + available.keySet() + "]";
                    });

            // 4.4 股票分析工具（受 STOCK_TOOLS_ENABLED 开关控制，默认关闭）
            TuShareDataSource tuShareSource = STOCK_TOOLS_ENABLED ? new TuShareDataSource(executionManager) : null;
            if (STOCK_TOOLS_ENABLED) {
                IndustryService industryService = new IndustryService(executionManager,
                        workspaceDir.resolve("cache"));
                industryService.initialize();

                AkShareDataSource akShareSource = new AkShareDataSource(executionManager);
                StockDataService stockDataService = new StockDataService(
                        workspaceDir.resolve("cache/stocks"),
                        List.of(akShareSource, tuShareSource),
                        industryService);

                LeaderScoringService scoringService = new LeaderScoringService();
                StockFilterService filterService = new StockFilterService();

                StockToolService stockToolService = new StockToolService(
                        industryService,
                        stockDataService,
                        scoringService,
                        filterService);
                stockToolService.registerTools(mcpClient);
                log.info("股票分析工具已启用 ({} 个工具)", STOCK_TOOL_NAMES.size());
            } else {
                log.info("股票分析工具已禁用 (STOCK_TOOLS_ENABLED=false，可通过 /stock on 或环境变量启用)");
            }

            // 5. 创建权限引擎，配置默认规则
            PermissionEngine permissionEngine = createPermissionEngine(STOCK_TOOLS_ENABLED);

            // 6. 创建工作空间管理器
            WorkspaceManager workspaceManager = new WorkspaceManager();

            // 7. 创建聊天模型客户端
            ChatModel chatModel = new ChatModel(credentialProvider);
            chatModel.setMaxOutputTokens(limits.getMaxOutputTokens());
            chatModel.setTimeouts(
                    limits.getLlmConnectTimeoutSeconds(),
                    limits.getLlmReadTimeoutSeconds(),
                    limits.getLlmWriteTimeoutSeconds());
            chatModel.setMaxRetries(limits.getLlmMaxRetries());

            // 8. 创建智能体
            String systemPrompt = buildSystemPrompt(STOCK_TOOLS_ENABLED, limits);
            Agent agent = new Agent(
                    "AgentScope-2.0",
                    systemPrompt,
                    chatModel,
                    mcpClient,
                    credentialProvider,
                    permissionEngine,
                    workspaceManager,
                    primaryProvider
            );
            // 同步迭代上限到 Agent（Agent 内部仍保留独立字段，便于 REPL /config 即时生效）
            agent.setMaxIterations(limits.getMaxIterations());
            agent.setLimits(limits);

            // 8.0 装配「最新全量 + 历史摘要」工具结果归档机制
            com.demo.agentscope.context.ContextToolResultArchiver toolResultArchiver =
                    new com.demo.agentscope.context.ContextToolResultArchiver(
                            toolOutputArchive, mcpClient.getToolResultSummarizer());
            agent.setToolResultArchiver(toolResultArchiver);

            // 8.1 创建短期记忆管理器
            ShortTermMemory shortTermMemory = new ShortTermMemory(
                    workspaceDir.resolve("memory/short_term"),
                    1000,  // 最大记忆条目数
                    Duration.ofDays(7)  // 保留7天
            );

            // 8.2 创建长期记忆管理器
            LongTermMemory longTermMemory = new LongTermMemory(
                    workspaceDir.resolve("memory/long_term"),
                    5000  // 最大记忆条目数
            );

            // 8.3 创建智能上下文管理器并集成到智能体
            ContextManager contextManager = new ContextManager(
                    shortTermMemory, systemPrompt,
                    limits.getMaxContextTokens(),
                    limits.getMaxRecentMessages(),
                    limits.getShortTermMemoryLimit(),
                    limits.getLongTermMemoryLimit());
            contextManager.setLongTermMemory(longTermMemory);
            contextManager.setMicroCompactorLimits(
                    limits.getMicroCompactorKeepRecent(),
                    limits.getMicroCompactorTriggerToolCount());
            agent.setContextManager(contextManager);
            // 注入 SystemPromptGenerator，供 /config set 后 regenerateSystemPrompt 回调
            agent.setSystemPromptGenerator(
                    (stockEnabled, lim) -> buildSystemPrompt(stockEnabled, lim),
                    STOCK_TOOLS_ENABLED);

            // 挂载中间件链
            MiddlewareChain chain = agent.getMiddlewareChain();
            chain.add(new TracingMiddleware());
            chain.add(new ContextCompressionMiddleware());
            chain.add(new PermissionMiddleware(permissionEngine));
            ReplyBudgetControlMiddleware replyBudgetMiddleware =
                    new ReplyBudgetControlMiddleware(limits.getReplyBudgetTokens());
            chain.add(replyBudgetMiddleware);
            agent.setReplyBudgetMiddleware(replyBudgetMiddleware);
            agent.setToolResultSummaryLimits(
                    limits.getToolResultSummaryThreshold(),
                    limits.getToolResultSummaryMaxLength());

            // 会话日志：每个进程启动生成一个新 sessionId，所有交互写入 JSONL
            String sessionId = UUID.randomUUID().toString();
            SessionLogger sessionLogger = new SessionLogger(workspaceDir, sessionId);
            SessionLoggingMiddleware sessionLoggingMiddleware = new SessionLoggingMiddleware(sessionLogger);
            chain.add(sessionLoggingMiddleware);

            // 打印启动信息
            printStartupInfo(primaryProvider, modelName, mcpClient, permissionEngine, STOCK_TOOLS_ENABLED);

            // 显示当前详细程度
            VerbosityLevel verbosity = VerbosityLevel.fromEnv();
            ConsoleUI.printInfo("界面详细程度: " + verbosity.getDisplayName() + " - " + verbosity.getDescription());
            ConsoleUI.printInfo("可通过 verbosity 命令调整详细程度，或设置环境变量 VERBOSITY=MINIMAL|STANDARD|VERBOSE|DEBUG");
            ConsoleUI.printInfo("会话ID: " + sessionId.substring(0, 8) + "（使用 /sessions 查看历史，/resume <id> 恢复）");

            // 9. 进入 REPL 主循环
            java.util.concurrent.atomic.AtomicBoolean stockEnabled =
                    new java.util.concurrent.atomic.AtomicBoolean(STOCK_TOOLS_ENABLED);
            runREPL(agent, credentialProvider, chatModel, mcpClient, permissionEngine, workspaceManager, primaryProvider,
                    stockEnabled, secureFileWorkspace, executionManager, workspaceDir, limits,
                    contextManager, replyBudgetMiddleware, sessionLoggingMiddleware);

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
    private static SecureFileWorkspace createSecureFileWorkspace(AgentLimits limits) {
        // 工作空间根目录
        String workspaceDir = System.getenv().getOrDefault("WORKSPACE_DIR", "workspace");
        Path baseDir = Path.of(workspaceDir).toAbsolutePath().normalize();

        // 创建根目录（如果不存在）
        baseDir.toFile().mkdirs();

        // 单文件大小上限：limits 为 0（默认值）时退化为 10MB（保持旧行为）
        long maxFileSize = limits.getMaxFileSizeBytes() > 0
                ? limits.getMaxFileSizeBytes()
                : 10L * 1024 * 1024;

        // 配置文件权限策略
        FilePermissionConfig config = new FilePermissionConfig.Builder()
                .allowReadWrite("**")
                .allowAbsoluteRead("/tmp/**")
                .denyPath("**/.env")
                .denyPath("**/secrets/**")
                .denyPath("**/.git/**")
                .denyExtension("exe")
                .denyExtension("sh")
                .denyExtension("bat")
                .maxFileSize(maxFileSize)
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.DENY_ALL)
                .build();

        FilePermissionManager permissionManager = new FilePermissionManager(baseDir, config);
        LocalWorkspace localWorkspace = new LocalWorkspace(baseDir.toString(), limits.getWorkspaceTimeoutSeconds());
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
    private static PermissionEngine createPermissionEngine(boolean stockEnabled) {
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

        // 允许团队管理工具（leader 自主编排 worker）
        engine.addRule(new PermissionRule("agent_create", PermissionDecision.ALLOW, "创建工作者智能体"));
        engine.addRule(new PermissionRule("agent_message", PermissionDecision.ALLOW, "向工作者发送消息"));
        engine.addRule(new PermissionRule("agent_list", PermissionDecision.ALLOW, "列出团队工作者"));
        engine.addRule(new PermissionRule("team_dissolve", PermissionDecision.ALLOW, "解散团队"));

        // 允许团队 artifact 工具（leader/worker 文件形式传递产出）
        engine.addRule(new PermissionRule("share_file", PermissionDecision.ALLOW, "团队内文件传递"));
        engine.addRule(new PermissionRule("list_artifacts", PermissionDecision.ALLOW, "列出可见 artifact"));
        engine.addRule(new PermissionRule("get_artifact", PermissionDecision.ALLOW, "接收 artifact（含 sha256 校验）"));
        engine.addRule(new PermissionRule("mark_artifact_read", PermissionDecision.ALLOW, "标记 artifact 已读"));

        // 允许股票工具（受 STOCK_TOOLS_ENABLED 控制）
        if (stockEnabled) {
            addStockPermissionRules(engine);
        }

        // 需要确认的工具
        engine.addRule(new PermissionRule("bash",
                com.demo.agentscope.permission.PermissionDecision.ask("Shell 命令需人工确认"),
                "Shell 命令需人工确认"));

        log.info("权限引擎已创建，模式={}，规则数={}", engine.getMode(), engine.getRules().size());
        return engine;
    }

    /**
     * 打印启动信息摘要。
     */
    private static void printStartupInfo(String provider, String model,
                                          MCPClient mcpClient, PermissionEngine permissionEngine,
                                          boolean stockEnabled) {
        ConsoleUI.printSuccess("AgentScope 2.0 初始化完成");
        ConsoleUI.printInfo("提供商: " + provider);
        ConsoleUI.printInfo("模型: " + model);
        ConsoleUI.printInfo("可用工具: " + mcpClient.listTools().size() + " 个");
        ConsoleUI.printInfo("权限模式: " + permissionEngine.getMode());
        ConsoleUI.printInfo("股票工具: " + (stockEnabled ? "已启用" : "已禁用（/stock on 开启）"));
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
                                String providerName,
                                java.util.concurrent.atomic.AtomicBoolean stockEnabled,
                                SecureFileWorkspace secureFileWorkspace,
                                CodeExecutionManager executionManager,
                                Path workspaceDir,
                                AgentLimits limits,
                                ContextManager contextManager,
                                ReplyBudgetControlMiddleware replyBudgetMiddleware,
                                SessionLoggingMiddleware sessionLoggingMiddleware) {
        AgentTeam team = null;
        boolean showEvents = false;

        while (true) {
            String input;
            try {
                input = ConsoleUI.promptUser();
            } catch (Throwable t) {
                // promptUser 内部已有容错，但兜底：任何漏网的异常都不应击穿 REPL
                log.error("读取用户输入时发生未预期异常", t);
                ConsoleUI.printError("输入读取异常: " + t.getClass().getSimpleName()
                        + " — 请重新输入");
                continue;
            }
            if (input == null) {
                // 输入流结束（EOF / Ctrl+D / Ctrl+C）
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

            if ("sessions".equalsIgnoreCase(trimmed) || "/sessions".equalsIgnoreCase(trimmed)) {
                // 列出所有已保存的会话
                SessionRecovery recovery = new SessionRecovery(workspaceDir);
                List<SessionSummary> sessions = recovery.listSessions();
                if (sessions.isEmpty()) {
                    ConsoleUI.printInfo("暂无已保存的会话");
                } else {
                    System.out.println();
                    System.out.println("\u001B[1m  📜 已保存的会话 (" + sessions.size() + ")\u001B[0m");
                    ConsoleUI.printSeparator();
                    for (SessionSummary s : sessions) {
                        String shortId = s.sessionId().length() >= 8
                                ? s.sessionId().substring(0, 8) : s.sessionId();
                        String time = s.lastActivity() != null ? s.lastActivity() : "?";
                        String preview = s.preview() != null && !s.preview().isEmpty()
                                ? s.preview() : "(无预览)";
                        System.out.println("  \u001B[36m" + shortId + "\u001B[0m  "
                                + "\u001B[2m" + time + "\u001B[0m  "
                                + "msg=" + s.userMessageCount() + "  "
                                + "\u001B[3m" + preview + "\u001B[0m");
                    }
                    ConsoleUI.printSeparator();
                    ConsoleUI.printInfo("使用 /resume <短ID> 恢复指定会话");
                }
                continue;
            }

            if (trimmed.toLowerCase().startsWith("/resume")
                    || trimmed.toLowerCase().startsWith("resume")) {
                String[] parts = trimmed.split("\\s+", 2);
                if (parts.length < 2 || parts[1].isBlank()) {
                    ConsoleUI.printError("用法: /resume <会话ID或短ID>");
                    continue;
                }
                String idPrefix = parts[1].trim();

                SessionRecovery recovery = new SessionRecovery(workspaceDir);
                // 短 ID 前缀匹配
                List<SessionSummary> all = recovery.listSessions();
                String matchedId = null;
                for (SessionSummary s : all) {
                    if (s.sessionId().startsWith(idPrefix)) {
                        if (matchedId != null) {
                            ConsoleUI.printError("短 ID '" + idPrefix + "' 匹配多个会话，请用更长前缀");
                            matchedId = null;
                            break;
                        }
                        matchedId = s.sessionId();
                    }
                }
                if (matchedId == null) {
                    // 尝试作为完整 ID 加载
                    matchedId = idPrefix;
                }

                try {
                    RecoveredSession recovered = recovery.load(matchedId);
                    // 重置智能体并恢复上下文
                    agent.reset();
                    agent.restoreContext(recovered.messages());

                    // 关闭旧 SessionLogger，新建一个续写到同一文件
                    SessionLogger oldLogger = sessionLoggingMiddleware.getSessionLogger();
                    String newSessionId = recovered.sessionId();
                    SessionLogger newLogger = new SessionLogger(
                            workspaceDir, newSessionId, recovered.lastEntryUuid());

                    // 替换中间件链中的 SessionLoggingMiddleware
                    MiddlewareChain chain = agent.getMiddlewareChain();
                    chain.remove(sessionLoggingMiddleware);
                    SessionLoggingMiddleware newMiddleware = new SessionLoggingMiddleware(newLogger);
                    chain.add(newMiddleware);
                    // 更新局部引用，使后续 reply() 走新中间件
                    sessionLoggingMiddleware = newMiddleware;

                    try { oldLogger.close(); } catch (Exception ignored) {}

                    // 用户反馈
                    ConsoleUI.printSuccess("会话已恢复: " + newSessionId.substring(0, 8));
                    ConsoleUI.printInfo("恢复消息数: " + recovered.messages().size()
                            + "，entry 数: " + recovered.totalEntries());
                    if (recovered.firstTimestamp() != null) {
                        ConsoleUI.printInfo("会话时间: " + recovered.firstTimestamp()
                                + " → " + recovered.lastTimestamp());
                    }
                    if (recovered.hasIncompleteTurn()) {
                        ConsoleUI.printWarning("⚠ 上一轮对话未正常结束（可能中断），新输入将继续此对话");
                    }
                    if (recovered.agentState() != null && !recovered.agentState().isEmpty()) {
                        ConsoleUI.printInfo("智能体状态快照: " + recovered.agentState());
                    }
                } catch (IllegalArgumentException ex) {
                    ConsoleUI.printError("会话不存在或无法加载: " + ex.getMessage());
                }
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
                // 装配 ArtifactManager 并注入团队（artifact 存储根复用 secureFileWorkspace 的 baseDir）
                Path teamWorkspaceRoot = secureFileWorkspace.getPermissionManager().getBaseDir();
                ArtifactManager artifactManager = new ArtifactManager(teamWorkspaceRoot, team.getTeamId());
                team.setArtifactManager(artifactManager);
                // 注册团队工具到 MCPClient 并更新领导者系统提示词
                team.registerTeamTools();
                ConsoleUI.printSuccess("智能体团队已创建，领导者已获得团队管理工具");
                ConsoleUI.printInfo("领导者: " + agent.getName());
                ConsoleUI.printInfo("团队ID: " + team.getTeamId());
                ConsoleUI.printSeparator();
                ConsoleUI.printInfo("可用团队工具: agent_create / agent_message / agent_list / team_dissolve / share_file / list_artifacts / get_artifact / mark_artifact_read");
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
                    String decisionIcon = switch (rule.getAction().getType()) {
                        case ALLOW -> "\u001B[32m✓ ALLOW\u001B[0m";
                        case DENY -> "\u001B[31m✗ DENY\u001B[0m";
                        case ASK -> "\u001B[33m? ASK\u001B[0m";
                    };
                    System.out.println("  " + decisionIcon + "  " + rule.getToolName()
                            + " - " + rule.getAction().getReason()
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

            if (trimmed.toLowerCase().startsWith("verbosity")) {
                // 调整详细程度
                String[] parts = trimmed.split("\\s+", 2);
                if (parts.length == 1) {
                    // 无参数：循环切换
                    VerbosityLevel current = VerbosityLevel.fromEnv();
                    VerbosityLevel next = current.next();
                    System.setProperty("verbosity.level", next.name());
                    ConsoleUI.printInfo("界面详细程度已切换: " + current.getDisplayName() + " → " + next.getDisplayName());
                    ConsoleUI.printInfo(next.getDescription());
                } else {
                    // 指定级别
                    try {
                        VerbosityLevel level = VerbosityLevel.valueOf(parts[1].toUpperCase());
                        System.setProperty("verbosity.level", level.name());
                        ConsoleUI.printSuccess("界面详细程度已设置为: " + level.getDisplayName());
                        ConsoleUI.printInfo(level.getDescription());
                    } catch (IllegalArgumentException e) {
                        ConsoleUI.printError("无效的级别: " + parts[1]);
                        ConsoleUI.printInfo("可选: MINIMAL / STANDARD / VERBOSE / DEBUG");
                    }
                }
                continue;
            }

            if (trimmed.toLowerCase().startsWith("/config")) {
                String[] parts = trimmed.split("\\s+", 3);
                if (parts.length == 1) {
                    // 列出所有运行时限制
                    System.out.println();
                    System.out.println("\u001B[1m  ⚙️ 运行时限制\u001B[0m");
                    ConsoleUI.printSeparator();
                    System.out.printf("  maxIterations = %d (迭代上限)%n", limits.getMaxIterations());
                    System.out.printf("  replyBudgetTokens = %d (单次回复 token 预算)%n", limits.getReplyBudgetTokens());
                    System.out.printf("  maxOutputTokens = %d (单次 LLM 调用 max_tokens)%n", limits.getMaxOutputTokens());
                    System.out.printf("  iterationWarnRemaining = %d (剩余多少轮开始告警)%n", limits.getIterationWarnRemaining());
                    System.out.printf("  tokenBudgetWarnPercent = %d%% (token 预算告警阈值)%n", limits.getTokenBudgetWarnPercent());
                    System.out.printf("  maxContextTokens = %d (上下文窗口)%n", limits.getMaxContextTokens());
                    System.out.printf("  maxRecentMessages = %d (压缩保留消息数)%n", limits.getMaxRecentMessages());
                    System.out.printf("  shortTermMemoryLimit = %d%n", limits.getShortTermMemoryLimit());
                    System.out.printf("  longTermMemoryLimit = %d%n", limits.getLongTermMemoryLimit());
                    System.out.printf("  microCompactorKeepRecent = %d%n", limits.getMicroCompactorKeepRecent());
                    System.out.printf("  microCompactorTriggerToolCount = %d%n", limits.getMicroCompactorTriggerToolCount());
                    System.out.printf("  toolResultSummaryThreshold = %d chars%n", limits.getToolResultSummaryThreshold());
                    System.out.printf("  toolResultSummaryMaxLength = %d chars%n", limits.getToolResultSummaryMaxLength());
                    System.out.printf("  commandTimeoutSeconds = %d%n", limits.getCommandTimeoutSeconds());
                    System.out.printf("  workspaceTimeoutSeconds = %d%n", limits.getWorkspaceTimeoutSeconds());
                    System.out.printf("  maxFileSizeBytes = %d (0=沿用默认10MB)%n", limits.getMaxFileSizeBytes());
                    System.out.printf("  llmReadTimeoutSeconds = %d (LLM 流式读取超时)%n", limits.getLlmReadTimeoutSeconds());
                    System.out.printf("  llmConnectTimeoutSeconds = %d%n", limits.getLlmConnectTimeoutSeconds());
                    System.out.printf("  llmWriteTimeoutSeconds = %d%n", limits.getLlmWriteTimeoutSeconds());
                    System.out.printf("  llmMaxRetries = %d (SocketTimeoutException 自动重试次数)%n", limits.getLlmMaxRetries());
                    ConsoleUI.printSeparator();
                    ConsoleUI.printInfo("用法: /config set key=value  （运行期即时生效，覆盖 env 与默认值）");
                } else if ("set".equals(parts[1]) && parts.length == 3) {
                    try {
                        limits.apply(parts[2]);
                        applyLimitsToRuntime(agent, contextManager, replyBudgetMiddleware,
                                executionManager, chatModel, workspaceDir, limits);
                        ConsoleUI.printSuccess("已更新: " + parts[2]);
                    } catch (Exception e) {
                        ConsoleUI.printError("设置失败: " + e.getMessage());
                    }
                } else {
                    ConsoleUI.printError("用法: /config 或 /config set key=value");
                }
                continue;
            }

            if (trimmed.toLowerCase().startsWith("/stock")) {
                String arg = trimmed.substring("/stock".length()).trim().toLowerCase();
                if (arg.isEmpty()) {
                    ConsoleUI.printInfo("当前股票工具状态: " + (stockEnabled.get() ? "开启" : "关闭"));
                    ConsoleUI.printInfo("用法: /stock on | /stock off");
                    continue;
                }
                switch (arg) {
                    case "on" -> {
                        if (stockEnabled.get()) {
                            ConsoleUI.printInfo("股票工具已处于开启状态");
                        } else {
                            enableStockTools(mcpClient, permissionEngine, agent, secureFileWorkspace,
                                    executionManager, workspaceDir);
                            stockEnabled.set(true);
                            ConsoleUI.printSuccess("股票分析工具已开启（" + STOCK_TOOL_NAMES.size() + " 个工具）");
                        }
                    }
                    case "off" -> {
                        if (!stockEnabled.get()) {
                            ConsoleUI.printInfo("股票工具已处于关闭状态");
                        } else {
                            for (String tool : STOCK_TOOL_NAMES) {
                                mcpClient.unregisterTool(tool);
                                permissionEngine.removeRule(tool);
                            }
                            stockEnabled.set(false);
                            ConsoleUI.printSuccess("股票分析工具已关闭");
                            ConsoleUI.printInfo("提示: 系统提示词中的股票说明需重启后清除");
                        }
                    }
                    default -> ConsoleUI.printError("无效参数: " + arg + "（用法: /stock on | /stock off）");
                }
                continue;
            }

            // ---- 智能体对话 ----

            try {
                long startTime = System.currentTimeMillis();

                if (team != null && team.isActive()) {
                    // 团队模式：仍走非流式（团队内多智能体协作暂不流式）
                    Msg response = team.reply(trimmed);
                    ConsoleUI.printAgentResponse(response);
                } else {
                    // 单智能体：走流式路径，token 级实时回显
                    EventStream stream = agent.replyStream(trimmed);

                    // 流路径下由 progressTracker.onTextDelta 已实时输出文本
                    // 若需要事件回放视图，可在此打印
                    if (showEvents) {
                        ConsoleUI.printEventStream(stream);
                    }
                }

                long duration = System.currentTimeMillis() - startTime;
                if (showEvents) {
                    ConsoleUI.printInfo("响应耗时: " + duration + "ms");
                }

            } catch (Throwable e) {
                // Throwable 而非 Exception：捕获 NoClassDefFoundError 等链接期错误，
                // 避免 REPL 主循环被任何意外错误击穿。agent/LLM/工具链任何一环
                // 抛错都仅终止本轮 reply，下一轮用户输入仍可继续。
                log.error("处理用户输入异常: {}", trimmed, e);
                ConsoleUI.printError("处理失败: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                ConsoleUI.printInfo("输入 help 查看可用命令，或继续输入下一轮对话");
            }
        }
    }

    /**
     * 运行期启用股票分析工具：构造数据源、注册工具、添加权限规则、追加系统提示词。
     */
    private static void enableStockTools(MCPClient mcpClient, PermissionEngine permissionEngine, Agent agent,
                                          SecureFileWorkspace secureFileWorkspace,
                                          CodeExecutionManager executionManager, Path workspaceDir) {
        IndustryService industryService = new IndustryService(executionManager,
                workspaceDir.resolve("cache"));
        industryService.initialize();

        AkShareDataSource akShareSource = new AkShareDataSource(executionManager);
        TuShareDataSource tuShareSource = new TuShareDataSource(executionManager);
        StockDataService stockDataService = new StockDataService(
                workspaceDir.resolve("cache/stocks"),
                List.of(akShareSource, tuShareSource),
                industryService);

        StockToolService stockToolService = new StockToolService(
                industryService,
                stockDataService,
                new LeaderScoringService(),
                new StockFilterService());
        stockToolService.registerTools(mcpClient);

        addStockPermissionRules(permissionEngine);

        agent.appendToSystemPrompt(STOCK_PROMPT_ADDENDUM);
        log.info("运行期已启用股票分析工具");
    }

    /**
     * 添加股票工具的 ALLOW 规则。启动期与运行期共用，避免规则散落多处。
     */
    private static void addStockPermissionRules(PermissionEngine engine) {
        for (String tool : STOCK_TOOL_NAMES) {
            engine.addRule(new PermissionRule(tool, PermissionDecision.ALLOW, "股票分析工具"));
        }
    }

    /**
     * REPL /config set 之后调用，把变更同步到所有持引用/数值快照的组件。
     * <p>
     * 持 AgentLimits 引用的组件会自动看到新值；持具体数值快照的组件
     * （ContextManager 的 maxContextTokens/maxRecentMessages、ReplyBudgetControlMiddleware
     * 的 budget 等）需要显式 setter 刷新。
     * </p>
     */
    private static void applyLimitsToRuntime(Agent agent,
                                              ContextManager contextManager,
                                              ReplyBudgetControlMiddleware replyBudgetMiddleware,
                                              CodeExecutionManager executionManager,
                                              ChatModel chatModel,
                                              Path workspaceDir,
                                              AgentLimits limits) {
        if (agent != null) {
            agent.setMaxIterations(limits.getMaxIterations());
            agent.regenerateSystemPrompt(STOCK_TOOLS_ENABLED, limits);
        }
        if (contextManager != null) {
            contextManager.updateLimits(
                    limits.getMaxContextTokens(),
                    limits.getMaxRecentMessages(),
                    limits.getShortTermMemoryLimit(),
                    limits.getLongTermMemoryLimit(),
                    limits.getMicroCompactorKeepRecent(),
                    limits.getMicroCompactorTriggerToolCount());
        }
        if (replyBudgetMiddleware != null) {
            replyBudgetMiddleware.updateBudget(limits.getReplyBudgetTokens());
        }
        if (executionManager != null) {
            executionManager.updateTimeoutSeconds(limits.getCommandTimeoutSeconds());
        }
        if (chatModel != null) {
            chatModel.setMaxOutputTokens(limits.getMaxOutputTokens());
            chatModel.setTimeouts(
                    limits.getLlmConnectTimeoutSeconds(),
                    limits.getLlmReadTimeoutSeconds(),
                    limits.getLlmWriteTimeoutSeconds());
            chatModel.setMaxRetries(limits.getLlmMaxRetries());
        }
        if (agent != null) {
            agent.setToolResultSummaryLimits(
                    limits.getToolResultSummaryThreshold(),
                    limits.getToolResultSummaryMaxLength());
        }
    }
}
