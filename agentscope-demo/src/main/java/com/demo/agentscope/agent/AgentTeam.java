package com.demo.agentscope.agent;

import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionEngine;
import com.demo.agentscope.ui.TeamProgressTracker;
import com.demo.agentscope.ui.VerbosityLevel;
import com.demo.agentscope.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

        log.info("智能体团队已创建: teamId={}, leader={}", teamId, leader.getName());
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
            return new Msg(UUID.randomUUID().toString(), "assistant",
                    List.of(new ContentBlock.TextBlock("团队已解散，无法处理请求")));
        }

        log.info("团队 [{}] 收到用户输入，委托给领导者 [{}]", teamId, leader.getName());
        return leader.reply(userInput);
    }

    /**
     * 创建并注册一个工作者智能体。
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

        Agent worker = new Agent(
                name,
                systemPrompt,
                chatModel,
                mcpClient,
                credentialProvider,
                permissionEngine,
                workspaceManager,
                providerName
        );

        workers.put(name, worker);
        log.info("团队 [{}] 创建工作者: name={}, id={}", teamId, name, worker.getId());

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
            return new Msg(UUID.randomUUID().toString(), "assistant",
                    List.of(new ContentBlock.TextBlock("工作者 " + workerName + " 不存在")));
        }

        // 进度跟踪：分配任务
        progressTracker.onLeaderAssignTask(workerName, message);
        progressTracker.onAgentCommunication(leader.getName(), workerName, message);
        progressTracker.onWorkerStart(workerName, message);

        long startTime = System.currentTimeMillis();
        Msg reply = worker.reply(message);

        long duration = System.currentTimeMillis() - startTime;
        progressTracker.onWorkerComplete(workerName, true, duration);

        return reply;
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

        // team_dissolve: 解散团队
        String teamDissolveParams = """
                {"type":"object","properties":{},"required":[]}""";
        mcpClient.registerCustomTool("team_dissolve",
                "解散当前团队，关闭所有工作者",
                teamDissolveParams,
                (args) -> {
                    int count = workers.size();
                    dissolve();
                    return "团队已解散，关闭了 " + count + " 个工作者";
                });

        // 将团队工具描述注入领导者系统提示词
        leader.appendToSystemPrompt(getTeamToolsDescription());
        log.info("团队 [{}] 已注册 4 个团队工具并更新领导者提示词", teamId);
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
        return """
                你现在是一个团队的领导者，拥有以下团队管理工具：

                1. agent_create - 创建一个工作者智能体来执行子任务
                   参数: {"name": "工作者名称", "system_prompt": "工作者的角色和职责描述"}

                2. agent_message - 向指定工作者发送消息并获取其回复
                   参数: {"worker_name": "工作者名称", "message": "消息内容"}

                3. agent_list - 列出团队中所有工作者
                   参数: {}

                4. team_dissolve - 解散当前团队，关闭所有工作者
                   参数: {}

                当任务需要分工协作时，你应该：
                - 用 agent_create 创建具有不同角色的工作者（如研究员、分析师、写作员）
                - 用 agent_message 向工作者分发子任务并收集结果
                - 用 agent_list 查看当前工作者状态
                - 任务完成后用 team_dissolve 解散团队
                - 综合各工作者的结果，给出最终答案
                """;
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
}
