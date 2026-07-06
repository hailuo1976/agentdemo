package com.demo.agentscope.agent;

import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionEngine;
import com.demo.agentscope.team.Artifact;
import com.demo.agentscope.team.ArtifactContent;
import com.demo.agentscope.team.ArtifactException;
import com.demo.agentscope.team.ArtifactManager;
import com.demo.agentscope.team.ArtifactSummary;
import com.demo.agentscope.team.SharedKnowledgeBase;
import com.demo.agentscope.team.TeamDissolutionPermissionService;
import com.demo.agentscope.ui.TeamProgressTracker;
import com.demo.agentscope.ui.VerbosityLevel;
import com.demo.agentscope.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Leader-Worker 智能体团队。
 * <p>
 * 采用领导者-工作者模式组织多智能体协作：
 * <ul>
 *   <li>领导者（leader）负责任务规划和分发，拥有4个特殊团队工具</li>
 *   <li>工作者（workers）负责具体任务执行，由领导者按需创建</li>
 * </ul>
 * 领导者的4个团队工具：
 * <ol>
 *   <li>{@code team_create} - 创建团队</li>
 *   <li>{@code agent_create} - 创建工作者智能体</li>
 *   <li>{@code agent_message} - 向工作者发送消息</li>
 *   <li>{@code team_dissolve} - 解散团队</li>
 * </ol>
 * </p>
 */
public class AgentTeam {

    private static final Logger log = LoggerFactory.getLogger(AgentTeam.class);

    /** 团队ID */
    private final String teamId;

    /** 领导者智能体 */
    private final Agent leader;

    /** 工作者智能体映射，key 为智能体名称 */
    private final Map<String, Agent> workers;

    /** 团队是否活跃 */
    private volatile boolean active;

    /** 共享组件引用（用于创建工作者） */
    private final ChatModel chatModel;
    private final MCPClient mcpClient;
    private final CredentialProvider credentialProvider;
    private final PermissionEngine permissionEngine;
    private final WorkspaceManager workspaceManager;
    private final String providerName;

    /** 团队进度跟踪器 */
    private final TeamProgressTracker progressTracker;

    /** 用于并行 worker 调度的有界线程池（避免占用公共 ForkJoinPool 阻塞 LLM 调用） */
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-team-worker");
        t.setDaemon(true);
        return t;
    });

    /** 团队共享知识库（可选） */
    private SharedKnowledgeBase knowledgeBase;

    /** 团队解散权限控制服务 */
    private final TeamDissolutionPermissionService dissolutionPermissionService;

    /**
     * 团队 artifact 管理服务（可选）。非 null 时，4 个 artifact 工具会被注册到 leader，
     * 并通过 worker MCPClient 克隆流到 worker。
     */
    private ArtifactManager artifactManager;

    /**
     * 当前正在调度 worker 的 agent 名字（worker 自己的名字；leader 调用工具时为 leader 名字）。
     * artifact 工具据此识别 sender / requester。
     */
    private final ThreadLocal<String> currentAgentName = new ThreadLocal<>();

    /**
     * 工作者系统提示词前置基础规范：注入 {@link SystemPrompts#TOOL_CALL_NORMS} 与 worker 专属工作守则，
     * 弥补 leader LLM 生成 prompt 时漏掉的 path 必填等工具调用硬性约束。
     */
    private static final String WORKER_BASELINE_PROMPT = """
            你是一个智能体工作者，与团队领导者协作完成任务。

            """
            + SystemPrompts.TOOL_CALL_NORMS + """

            ## 工作守则

            1. 需要文件操作时，永远显式指定 path，绝不省略。如果用户未给路径，根据任务上下文推断一个有意义的文件名。
            2. 优先使用工具解决问题，而非请求用户手动操作。
            3. 失败时分析错误并修正，不要无脑重试。
            4. 完成任务后用简洁中文总结结果。

            ## 重要产出共享
            当被要求产出代码、报告、JSON、CSV、Markdown 等结构化内容时，**优先**调用 share_file 把结果作为文件发送给请求者，而不是塞进 agent_message 的纯文本（长内容会被截断、二进制完全无法传）。
            完成后通过 agent_message 告知接收者 artifactId，让其用 get_artifact 取回。
            工具调用时系统会自动识别你的名字，作为 sender。
            """;

    /**
     * 构造智能体团队。
     *
     * @param leader            领导者智能体
     * @param chatModel         聊天模型客户端
     * @param mcpClient         MCP 客户端
     * @param credentialProvider 凭证提供者
     * @param permissionEngine   权限引擎
     * @param workspaceManager   工作空间管理器
     * @param providerName       提供商名称
     */
    public AgentTeam(Agent leader, ChatModel chatModel, MCPClient mcpClient,
                     CredentialProvider credentialProvider, PermissionEngine permissionEngine,
                     WorkspaceManager workspaceManager, String providerName) {
        this(leader, chatModel, mcpClient, credentialProvider, permissionEngine,
                workspaceManager, providerName, null, null);
    }

    /**
     * 构造智能体团队（带解散权限控制）。
     *
     * @param leader                       领导者智能体
     * @param chatModel                    聊天模型客户端
     * @param mcpClient                    MCP 客户端
     * @param credentialProvider           凭证提供者
     * @param permissionEngine             权限引擎
     * @param workspaceManager             工作空间管理器
     * @param providerName                 提供商名称
     * @param dissolutionPermissionService 团队解散权限控制服务（可选）
     */
    public AgentTeam(Agent leader, ChatModel chatModel, MCPClient mcpClient,
                     CredentialProvider credentialProvider, PermissionEngine permissionEngine,
                     WorkspaceManager workspaceManager, String providerName,
                     TeamDissolutionPermissionService dissolutionPermissionService) {
        this(leader, chatModel, mcpClient, credentialProvider, permissionEngine,
                workspaceManager, providerName, dissolutionPermissionService, null);
    }

    /**
     * 构造智能体团队（带解散权限控制 + artifact 管理服务）。
     *
     * @param leader                       领导者智能体
     * @param chatModel                    聊天模型客户端
     * @param mcpClient                    MCP 客户端
     * @param credentialProvider           凭证提供者
     * @param permissionEngine             权限引擎
     * @param workspaceManager             工作空间管理器
     * @param providerName                 提供商名称
     * @param dissolutionPermissionService 团队解散权限控制服务（可选）
     * @param artifactManager              artifact 管理服务（可选，null 时不注册 artifact 工具）
     */
    public AgentTeam(Agent leader, ChatModel chatModel, MCPClient mcpClient,
                     CredentialProvider credentialProvider, PermissionEngine permissionEngine,
                     WorkspaceManager workspaceManager, String providerName,
                     TeamDissolutionPermissionService dissolutionPermissionService,
                     ArtifactManager artifactManager) {
        this.teamId = UUID.randomUUID().toString();
        this.leader = leader;
        this.workers = new LinkedHashMap<>();
        this.active = true;
        this.chatModel = chatModel;
        this.mcpClient = mcpClient;
        this.credentialProvider = credentialProvider;
        this.permissionEngine = permissionEngine;
        this.workspaceManager = workspaceManager;
        this.providerName = providerName;
        this.progressTracker = new TeamProgressTracker(teamId, leader.getName(), VerbosityLevel.fromEnv());
        this.dissolutionPermissionService = dissolutionPermissionService;
        this.artifactManager = artifactManager;

        log.info("智能体团队已创建: teamId={}, leader={}, artifact={}",
                teamId, leader.getName(), artifactManager != null ? "enabled" : "disabled");
    }

    // ==================== 核心方法 ====================

    /**
     * 处理用户输入，委托给领导者智能体回复。
     * <p>
     * 领导者根据用户输入判断是否需要创建工作者或分配任务。
     * 团队工具通过扩展领导者的 MCP 客户端工具集实现。
     * </p>
     *
     * @param userInput 用户输入文本
     * @return 领导者的回复消息
     */
    public Msg reply(String userInput) {
        if (!active) {
            return Msg.assistantText("团队已解散，无法处理请求");
        }

        log.info("团队 [{}] 收到用户输入，委托给领导者 [{}]", teamId, leader.getName());
        return leader.reply(userInput);
    }

    /**
     * 创建并注册一个工作者智能体。
     * <p>
     * 工作者使用独立的 MCPClient，不包含团队管理工具，
     * 防止工作者误操作团队管理功能（如 team_dissolve）。
     * </p>
     *
     * @param name         工作者名称
     * @param systemPrompt 工作者系统提示词
     * @return 新创建的工作者智能体
     */
    public Agent createWorker(String name, String systemPrompt) {
        if (!active) {
            throw new IllegalStateException("团队已解散，无法创建工作者");
        }

        if (workers.containsKey(name)) {
            log.warn("工作者 [{}] 已存在，返回已有实例", name);
            return workers.get(name);
        }

        // 为工作者创建独立的 MCPClient，不包含团队管理工具
        MCPClient workerMcpClient = new MCPClient();
        workerMcpClient.initialize();

        // 从领导者的 MCPClient 复制工具，排除团队管理工具
        Set<String> teamTools = Set.of("agent_create", "agent_message", "agent_list", "team_dissolve", "agent_message_parallel");
        mcpClient.copyToolsTo(workerMcpClient, teamTools);

        String workerSystemPrompt = WORKER_BASELINE_PROMPT + "\n\n" + systemPrompt;

        Agent worker = new Agent(
                name,
                workerSystemPrompt,
                chatModel,
                workerMcpClient,  // 使用独立的 MCPClient
                credentialProvider,
                permissionEngine,
                workspaceManager,
                providerName
        );

        workers.put(name, worker);
        log.info("团队 [{}] 创建工作者: name={}, id={} (独立MCPClient)", teamId, name, worker.getId());

        // 进度跟踪：创建工作者
        progressTracker.onLeaderCreateWorker(name, "工作者");

        return worker;
    }

    /**
     * 向指定工作者发送消息并获取回复。
     *
     * @param workerName 工作者名称
     * @param message    消息内容
     * @return 工作者的回复消息
     */
    public Msg sendMessageToWorker(String workerName, String message) {
        Agent worker = workers.get(workerName);
        if (worker == null) {
            log.warn("工作者 [{}] 不存在", workerName);
            return Msg.assistantText("工作者 " + workerName + " 不存在");
        }

        // 进度跟踪：分配任务
        progressTracker.onLeaderAssignTask(workerName, message);
        progressTracker.onAgentCommunication(leader.getName(), workerName, message);
        progressTracker.onWorkerStart(workerName, message);

        long startTime = System.currentTimeMillis();
        boolean success = true;
        Msg reply;
        try {
            // 设置 ThreadLocal，让 worker 调用的 artifact 工具能识别当前 worker 名字
            currentAgentName.set(workerName);
            reply = worker.replySyncFromStream(message);
        } catch (Exception e) {
            success = false;
            log.error("工作者 [{}] 执行异常: {}", workerName, e.getMessage(), e);
            reply = Msg.assistantText(String.format(
                    "工作者 %s 执行过程中发生异常：%s。任务未完成，请重新分配或调整指令。",
                    workerName, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            currentAgentName.remove();
        }

        long duration = System.currentTimeMillis() - startTime;
        progressTracker.onWorkerComplete(workerName, success, duration);

        // 自动保存工作者知识到共享知识库
        if (knowledgeBase != null) {
            String replyText = reply.getTextContent();
            if (replyText != null && !replyText.isEmpty()) {
                // 提取关键发现（简单策略：取前200字符作为摘要）
                String summary = replyText.length() > 200 ? replyText.substring(0, 200) + "..." : replyText;
                List<String> tags = List.of(workerName, "task_result");
                knowledgeBase.add(workerName, summary, tags, 0.8);
                log.debug("工作者 [{}] 知识已保存到共享知识库", workerName);
            }
        }

        return reply;
    }

    /**
     * 并行向多个工作者发送消息并收集回复。
     * <p>
     * 使用 CompletableFuture 实现并行执行，所有工作者同时开始工作，
     * 显著减少总耗时。
     * </p>
     *
     * @param tasks 任务映射，key 为工作者名称，value 为消息内容
     * @return 结果映射，key 为工作者名称，value 为回复消息
     */
    public Map<String, Msg> sendMessageToWorkersParallel(Map<String, String> tasks) {
        if (!active) {
            throw new IllegalStateException("团队已解散，无法执行任务");
        }

        log.info("团队 [{}] 开始并行执行 {} 个任务", teamId, tasks.size());
        long startTime = System.currentTimeMillis();

        // 创建 CompletableFuture 列表
        List<CompletableFuture<Map.Entry<String, Msg>>> futures = new ArrayList<>();

        for (Map.Entry<String, String> task : tasks.entrySet()) {
            String workerName = task.getKey();
            String message = task.getValue();

            CompletableFuture<Map.Entry<String, Msg>> future =
                CompletableFuture.supplyAsync(() -> {
                    Msg reply = sendMessageToWorker(workerName, message);
                    return Map.entry(workerName, reply);
                }, workerExecutor);
            futures.add(future);
        }

        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 收集结果
        Map<String, Msg> results = new LinkedHashMap<>();
        for (CompletableFuture<Map.Entry<String, Msg>> future : futures) {
            try {
                Map.Entry<String, Msg> entry = future.get();
                results.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.error("并行任务执行失败", e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("团队 [{}] 并行任务完成，耗时 {}ms", teamId, duration);

        return results;
    }

    /**
     * 解散团队，关闭所有工作者智能体。
     */
    public void dissolve() {
        log.info("团队 [{}] 正在解散...", teamId);
        for (Map.Entry<String, Agent> entry : workers.entrySet()) {
            try {
                entry.getValue().shutdown();
                log.debug("工作者 [{}] 已关闭", entry.getKey());
            } catch (Exception e) {
                log.warn("关闭工作者 [{}] 异常", entry.getKey(), e);
            }
        }
        workers.clear();
        active = false;
        workerExecutor.shutdownNow();
        if (artifactManager != null) {
            artifactManager.archiveAll(Instant.now());
        }
        log.info("团队 [{}] 已解散", teamId);
    }

    // ==================== 团队工具注册 ====================

    /**
     * 注册团队管理工具到领导者的 MCP 客户端，并更新领导者系统提示词。
     * <p>
     * 注册 4 个团队工具，使领导者能通过 LLM 自主创建工作者、分发任务、收集结果：
     * <ol>
     *   <li>{@code agent_create} — 创建工作者智能体（参数: name, system_prompt）</li>
     *   <li>{@code agent_message} — 向工作者发送消息并获取回复（参数: worker_name, message）</li>
     *   <li>{@code agent_list} — 列出所有工作者及状态（无参数）</li>
     *   <li>{@code team_dissolve} — 解散团队（无参数）</li>
     * </ol>
     * 同时将团队工具描述注入领导者系统提示词，使 LLM 感知自身具备团队管理能力。
     * </p>
     */
    public void registerTeamTools() {
        // agent_create: 创建工作者
        String agentCreateParams = """
                {"type":"object","properties":{"name":{"type":"string","description":"工作者名称，需唯一"},"system_prompt":{"type":"string","description":"工作者的系统提示词，定义其角色和职责"}},"required":["name","system_prompt"]}""";
        mcpClient.registerCustomTool("agent_create",
                "创建一个工作者智能体来执行子任务",
                agentCreateParams,
                (args) -> {
                    String workerName = String.valueOf(args.getOrDefault("name", ""));
                    String workerPrompt = String.valueOf(args.getOrDefault("system_prompt", ""));
                    if (workerName.isBlank()) {
                        return "错误: 工作者名称不能为空";
                    }
                    Agent worker = createWorker(workerName, workerPrompt);
                    return "工作者 [" + workerName + "] 已创建，ID: " + worker.getId();
                });

        // agent_message: 向工作者发送消息
        String agentMessageParams = """
                {"type":"object","properties":{"worker_name":{"type":"string","description":"目标工作者名称"},"message":{"type":"string","description":"发送给工作者的消息内容"}},"required":["worker_name","message"]}""";
        mcpClient.registerCustomTool("agent_message",
                "向指定工作者发送消息并获取其回复",
                agentMessageParams,
                (args) -> {
                    String workerName = String.valueOf(args.getOrDefault("worker_name", ""));
                    String message = String.valueOf(args.getOrDefault("message", ""));
                    Msg reply = sendMessageToWorker(workerName, message);
                    // 提取回复文本
                    StringBuilder sb = new StringBuilder();
                    for (ContentBlock block : reply.getContent()) {
                        if (block instanceof ContentBlock.TextBlock textBlock) {
                            sb.append(textBlock.getText());
                        }
                    }
                    return "工作者 [" + workerName + "] 回复:\n" + sb;
                });

        // agent_list: 列出所有工作者
        String agentListParams = """
                {"type":"object","properties":{},"required":[]}""";
        mcpClient.registerCustomTool("agent_list",
                "列出团队中所有工作者及其状态",
                agentListParams,
                (args) -> {
                    if (workers.isEmpty()) {
                        return "当前团队没有工作者";
                    }
                    StringBuilder sb = new StringBuilder("团队工作者列表:\n");
                    for (Map.Entry<String, Agent> entry : workers.entrySet()) {
                        sb.append("- ").append(entry.getKey())
                          .append(" (ID: ").append(entry.getValue().getId()).append(")\n");
                    }
                    return sb.toString().trim();
                });

        // team_dissolve: 解散团队（受权限控制）
        String teamDissolveParams = """
                {"type":"object","properties":{"requester_id":{"type":"string","description":"请求解散团队的用户ID，用于权限验证"}},"required":["requester_id"]}""";
        mcpClient.registerCustomTool("team_dissolve",
                "解散当前团队，关闭所有工作者。需要指定 requester_id 进行权限验证，团队成员不能解散自己所在的团队。",
                teamDissolveParams,
                (args) -> {
                    String requesterId = String.valueOf(args.getOrDefault("requester_id", ""));
                    if (requesterId.isBlank()) {
                        return "错误: 必须提供 requester_id 参数以进行权限验证";
                    }

                    // 执行权限验证
                    TeamDissolutionPermissionService.DissolutionPermissionResult result =
                            checkDissolutionPermission(requesterId);

                    if (!result.isAllowed()) {
                        return "权限验证失败: " + result.getReason();
                    }

                    // 权限验证通过，执行解散
                    int count = workers.size();
                    dissolve();
                    return "团队已解散，关闭了 " + count + " 个工作者";
                });

        // agent_message_parallel: 并行向多个工作者发送消息
        String agentMessageParallelParams = """
                {"type":"object","properties":{"tasks":{"type":"object","description":"任务映射，key为工作者名称，value为消息内容","additionalProperties":{"type":"string"}}},"required":["tasks"]}""";
        mcpClient.registerCustomTool("agent_message_parallel",
                "并行向多个工作者发送消息，所有工作者同时开始工作，显著减少总耗时",
                agentMessageParallelParams,
                (args) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tasksObj = (Map<String, Object>) args.getOrDefault("tasks", Map.of());
                    Map<String, String> tasks = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : tasksObj.entrySet()) {
                        tasks.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                    
                    Map<String, Msg> results = sendMessageToWorkersParallel(tasks);
                    
                    // 格式化结果
                    StringBuilder sb = new StringBuilder("并行任务执行结果:\n\n");
                    for (Map.Entry<String, Msg> entry : results.entrySet()) {
                        sb.append("【").append(entry.getKey()).append("】\n");
                        for (ContentBlock block : entry.getValue().getContent()) {
                            if (block instanceof ContentBlock.TextBlock textBlock) {
                                sb.append(textBlock.getText());
                            }
                        }
                        sb.append("\n\n");
                    }
                    return sb.toString().trim();
                });

        // 将团队工具描述注入领导者系统提示词
        leader.appendToSystemPrompt(getTeamToolsDescription());
        int toolCount = 5;
        if (artifactManager != null) {
            registerArtifactTools();
            toolCount += 4;
        }
        log.info("团队 [{}] 已注册 {} 个团队工具并更新领导者提示词", teamId, toolCount);
    }

    // ==================== Artifact 工具注册 ====================

    /**
     * 注册 4 个 artifact 团队工具：share_file / list_artifacts / get_artifact / mark_artifact_read。
     * 通过 leader 的 MCPClient 注册；worker 通过 copyToolsTo 自动获得（excludeTools 集合不包含这 4 个工具）。
     * <p>
     * 工具内通过 currentAgentName ThreadLocal 识别调用者：
     * <ul>
     *   <li>leader 直接调用时，ThreadLocal 未设置 → 默认 leader.getName()</li>
     *   <li>worker 调用时，sendMessageToWorker 已经把 workerName set 进 ThreadLocal</li>
     * </ul>
     * </p>
     */
    private void registerArtifactTools() {
        // share_file —— 发送文件
        String shareFileParams = """
                {"type":"object","properties":{"filename":{"type":"string","description":"原始文件名（含扩展），扩展名必须在白名单：txt/md/json/csv/py/java/js/ts/go/sh/yaml/yml/xml/html/png/jpg/jpeg/gif/pdf/log"},"recipients":{"type":"array","items":{"type":"string"},"minItems":1,"description":"接收者 agentName 列表；'leader' 表示领导者"},"content":{"type":"string","description":"文件内容（UTF-8 文本或 base64 编码的二进制，由 encoding 字段决定）"},"encoding":{"type":"string","enum":["utf8","base64"],"default":"utf8"},"mimeType":{"type":"string","description":"可选 mime 类型；省略时按扩展名推断"},"description":{"type":"string","description":"对产出内容的简要描述（建议 ≤200 字）"},"tags":{"type":"array","items":{"type":"string"},"description":"可选标签"}},"required":["filename","recipients","content"]}""";
        mcpClient.registerCustomTool("share_file",
                "将一份产出以文件形式发送给团队内其他成员。文件内容、校验和、元数据会被持久化；接收方可通过 get_artifact 读取。状态自动初始化为 SENT。",
                shareFileParams,
                (args) -> {
                    String sender = resolveCurrentAgent();
                    String filename = String.valueOf(args.getOrDefault("filename", ""));
                    @SuppressWarnings("unchecked")
                    List<Object> recipsObj = (List<Object>) args.getOrDefault("recipients", Collections.emptyList());
                    if (recipsObj.isEmpty()) {
                        return "错误: recipients 不能为空";
                    }
                    List<String> recipients = new ArrayList<>();
                    for (Object o : recipsObj) {
                        recipients.add(String.valueOf(o));
                    }
                    String encoding = String.valueOf(args.getOrDefault("encoding", "utf8"));
                    String contentStr = String.valueOf(args.getOrDefault("content", ""));
                    byte[] content;
                    try {
                        content = decodeContent(contentStr, encoding);
                    } catch (IllegalArgumentException e) {
                        return "错误: " + e.getMessage();
                    }
                    if (content.length == 0) {
                        return "错误: content 不能为空";
                    }
                    String mimeType = args.containsKey("mimeType") ? String.valueOf(args.get("mimeType")) : null;
                    String description = args.containsKey("description") ? String.valueOf(args.get("description")) : null;
                    @SuppressWarnings("unchecked")
                    List<String> tags = (List<String>) args.get("tags");
                    try {
                        Artifact a = artifactManager.send(sender, recipients, filename, mimeType,
                                content, description, tags);
                        return String.format("已发送 artifact: %s%n文件名: %s%n大小: %d bytes%n校验和: %s%n接收者: %s%n状态: 全部 SENT",
                                a.getArtifactId(), a.getFilename(), a.getSizeBytes(),
                                a.getChecksum(), a.getRecipients());
                    } catch (ArtifactException e) {
                        return "错误: " + e.getMessage();
                    }
                });

        // list_artifacts —— 列出可见 artifact
        String listArtifactsParams = """
                {"type":"object","properties":{"status":{"type":"string","enum":["SENT","RECEIVED","READ"],"description":"可选，按状态过滤"},"tag":{"type":"string","description":"可选，按标签过滤"},"sender":{"type":"string","description":"可选，按发送者过滤"},"limit":{"type":"integer","minimum":1,"maximum":100,"default":20}},"required":[]}""";
        mcpClient.registerCustomTool("list_artifacts",
                "列出当前调用者可见的 artifact（作为 sender 或 recipient）。支持按状态、标签、发送者过滤。",
                listArtifactsParams,
                (args) -> {
                    String requester = resolveCurrentAgent();
                    ArtifactManager.ArtifactFilter filter = new ArtifactManager.ArtifactFilter();
                    if (args.containsKey("status")) filter.status(String.valueOf(args.get("status")));
                    if (args.containsKey("tag")) filter.tag(String.valueOf(args.get("tag")));
                    if (args.containsKey("sender")) filter.sender(String.valueOf(args.get("sender")));
                    if (args.containsKey("limit")) {
                        try {
                            filter.limit(Integer.parseInt(String.valueOf(args.get("limit"))));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    List<ArtifactSummary> list = artifactManager.list(requester, filter);
                    if (list.isEmpty()) {
                        return "没有可见的 artifact";
                    }
                    StringBuilder sb = new StringBuilder("| ID | 文件名 | 发送者 | 大小 | 状态(对你) | 创建时间 |\\n");
                    sb.append("|----|--------|--------|------|-----------|----------|\\n");
                    for (ArtifactSummary s : list) {
                        sb.append(String.format("| %s | %s | %s | %d | %s | %s |\\n",
                                s.getArtifactId(),
                                s.getFilename(),
                                s.getSender(),
                                s.getSizeBytes(),
                                s.getStatus(),
                                s.getCreatedAt() != null ? s.getCreatedAt().toString().substring(0, 16) : "-"));
                    }
                    return sb.toString().trim();
                });

        // get_artifact —— 接收文件
        String getArtifactParams = """
                {"type":"object","properties":{"artifactId":{"type":"string","description":"artifact ID"},"encoding":{"type":"string","enum":["utf8","base64"],"default":"utf8","description":"返回内容的编码；二进制建议 base64"}},"required":["artifactId"]}""";
        mcpClient.registerCustomTool("get_artifact",
                "读取一个 artifact 的内容。仅对 recipient 或 sender 可用；自动校验 sha256；成功后状态推进为 RECEIVED。",
                getArtifactParams,
                (args) -> {
                    String requester = resolveCurrentAgent();
                    String artifactId = String.valueOf(args.getOrDefault("artifactId", ""));
                    String encoding = String.valueOf(args.getOrDefault("encoding", "utf8"));
                    try {
                        ArtifactContent c = artifactManager.receive(requester, artifactId);
                        Artifact a = c.getArtifact();
                        String body;
                        if ("base64".equalsIgnoreCase(encoding)) {
                            body = Base64.getEncoder().encodeToString(c.getBytes());
                        } else {
                            body = new String(c.getBytes(), StandardCharsets.UTF_8);
                        }
                        return String.format("[artifact: %s from %s, %d bytes, sha256 verified]%n%s",
                                a.getFilename(), a.getSender(), a.getSizeBytes(), body);
                    } catch (ArtifactException e) {
                        return "错误: " + e.getMessage();
                    }
                });

        // mark_artifact_read —— 标记已读
        String markReadParams = """
                {"type":"object","properties":{"artifactId":{"type":"string","description":"artifact ID"}},"required":["artifactId"]}""";
        mcpClient.registerCustomTool("mark_artifact_read",
                "将一个 artifact 标记为已读（READ）。仅 recipient 可调用。",
                markReadParams,
                (args) -> {
                    String requester = resolveCurrentAgent();
                    String artifactId = String.valueOf(args.getOrDefault("artifactId", ""));
                    try {
                        artifactManager.markRead(requester, artifactId);
                        return "已标记 " + artifactId + " 为 READ";
                    } catch (ArtifactException e) {
                        return "错误: " + e.getMessage();
                    }
                });
    }

    /**
     * 解析当前调用 agent 名字：优先 ThreadLocal；未设置（leader 直接调用）时返回 leader 名字。
     */
    private String resolveCurrentAgent() {
        String name = currentAgentName.get();
        return name != null ? name : leader.getName();
    }

    /**
     * 测试辅助：在当前线程上设置当前 agent 名字（模拟 worker dispatch 上下文）。
     * 调用方应在 finally 中调用 {@link #clearCurrentAgentForTest()} 清除。
     */
    void setCurrentAgentForTest(String name) {
        if (name != null) {
            currentAgentName.set(name);
        } else {
            currentAgentName.remove();
        }
    }

    /**
     * 测试辅助：清除当前线程的 agent 名字。
     */
    void clearCurrentAgentForTest() {
        currentAgentName.remove();
    }

    /**
     * 解码 content 字段：utf8 直接转字节，base64 解码。
     */
    private static byte[] decodeContent(String content, String encoding) {
        if (content == null || content.isEmpty()) {
            return new byte[0];
        }
        if ("base64".equalsIgnoreCase(encoding)) {
            try {
                return Base64.getDecoder().decode(content);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("base64 解码失败: " + e.getMessage());
            }
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    // ==================== 团队工具描述 ====================

    /**
     * 获取团队工具描述列表，用于注入到领导者的系统提示词中。
     * <p>
     * 团队工具以自然语言描述形式注入到领导者的系统提示词中，
     * 领导者通过调用对应的 MCP 内置工具来执行团队操作。
     * </p>
     *
     * @return 团队工具描述字符串
     */
    public String getTeamToolsDescription() {
        String base = """
                你现在是一个团队的领导者，拥有以下团队管理工具：

                1. agent_create - 创建一个工作者智能体来执行子任务
                   参数: {"name": "工作者名称", "system_prompt": "工作者的角色和职责描述"}

                2. agent_message - 向指定工作者发送消息并获取其回复
                   参数: {"worker_name": "工作者名称", "message": "消息内容"}

                3. agent_list - 列出团队中所有工作者
                   参数: {}

                4. team_dissolve - 解散当前团队，关闭所有工作者（需权限验证）
                   参数: {"requester_id": "请求者用户ID"}
                   注意: 团队成员不能解散自己所在的团队，只有指定的外部管理员才能执行解散

                当任务需要分工协作时，你应该：
                - 用 agent_create 创建具有不同角色的工作者（如研究员、分析师、写作员）
                - 用 agent_message 向工作者分发子任务并收集结果
                - 用 agent_list 查看当前工作者状态
                - 任务完成后用 team_dissolve 解散团队
                - 综合各工作者的结果，给出最终答案
                """;
        if (artifactManager != null) {
            base += """

                    ## 文件传递（artifact）

                    当工作者产出代码、报告、JSON、CSV、图片等结构化或大块内容时，**优先**让工作者用 share_file 以文件形式发回，而不是塞进 agent_message 的纯文本（长内容会被截断、二进制无法传）。

                    1. share_file(filename, recipients, content, encoding?, mimeType?, description?, tags?) — 发送文件
                       参数: {"filename":"报告文件名", "recipients":["leader"], "content":"文件内容", "encoding":"utf8|base64"}
                    2. list_artifacts(status?, tag?, sender?, limit?) — 列出可见 artifact
                    3. get_artifact(artifactId, encoding?) — 接收并校验文件（自动推进到 RECEIVED）
                    4. mark_artifact_read(artifactId) — 标记已读

                    文件传递支持 sha256 完整性校验和 SENT/RECEIVED/READ 状态跟踪；接收者限定在 recipients 名单。
                    工具调用时系统自动识别调用者名字作为 sender / requester。
                    """;
        }
        return base;
    }

    // ==================== 状态查询 ====================

    /**
     * 获取团队状态信息。
     *
     * @return 状态映射
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("teamId", teamId);
        status.put("active", active);
        status.put("leaderName", leader.getName());
        status.put("leaderId", leader.getId());
        status.put("workerCount", workers.size());

        List<Map<String, String>> workerInfo = new ArrayList<>();
        for (Map.Entry<String, Agent> entry : workers.entrySet()) {
            Map<String, String> info = new LinkedHashMap<>();
            info.put("name", entry.getKey());
            info.put("id", entry.getValue().getId());
            workerInfo.add(info);
        }
        status.put("workers", workerInfo);

        return status;
    }

    // ==================== 权限控制 ====================

    /**
     * 检查解散权限。
     * <p>
     * 验证请求者是否有权解散团队。团队成员（包括领导者）不能解散自己所在的团队，
     * 只有指定的外部用户才能发起解散请求。
     * </p>
     *
     * @param requesterId 请求解散的用户ID
     * @return 权限验证结果
     */
    public TeamDissolutionPermissionService.DissolutionPermissionResult checkDissolutionPermission(
            String requesterId) {

        // 如果没有配置权限服务，默认允许（向后兼容）
        if (dissolutionPermissionService == null) {
            log.warn("团队解散权限服务未配置，默认允许解散请求");
            return TeamDissolutionPermissionService.DissolutionPermissionResult.allow();
        }

        // 收集团队成员ID（领导者 + 所有工作者）
        Set<String> teamMemberIds = new HashSet<>();
        teamMemberIds.add(leader.getId());
        for (Agent worker : workers.values()) {
            teamMemberIds.add(worker.getId());
        }

        // 委托给权限服务进行验证
        return dissolutionPermissionService.checkDissolutionPermission(
                requesterId, teamId, teamMemberIds);
    }

    /**
     * 获取团队解散权限服务。
     *
     * @return 权限服务实例
     */
    public TeamDissolutionPermissionService getDissolutionPermissionService() {
        return dissolutionPermissionService;
    }

    // ==================== Getter ====================

    public String getTeamId() {
        return teamId;
    }

    public Agent getLeader() {
        return leader;
    }

    public Map<String, Agent> getWorkers() {
        return Collections.unmodifiableMap(workers);
    }

    public boolean isActive() {
        return active;
    }

    public TeamProgressTracker getProgressTracker() {
        return progressTracker;
    }

    /**
     * 设置团队共享知识库。
     */
    public void setKnowledgeBase(SharedKnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
        log.info("团队 [{}] 已启用共享知识库", teamId);
    }

    /**
     * 获取团队共享知识库。
     */
    public SharedKnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    /**
     * 获取 artifact 管理服务（可能为 null）。
     */
    public ArtifactManager getArtifactManager() {
        return artifactManager;
    }

    /**
     * 装配 artifact 管理服务。必须在 {@link #registerTeamTools()} 之前调用。
     * <p>
     * 若团队构造时未传入 ArtifactManager，调用此方法可后注入；
     * 之后 {@link #registerTeamTools()} 会注册 4 个 artifact 工具到 leader。
     * </p>
     */
    public void setArtifactManager(ArtifactManager artifactManager) {
        if (this.artifactManager != null) {
            throw new IllegalStateException("artifactManager 已设置，不可重复装配");
        }
        this.artifactManager = Objects.requireNonNull(artifactManager, "artifactManager");
        log.info("团队 [{}] 已装配 ArtifactManager", teamId);
    }
}
