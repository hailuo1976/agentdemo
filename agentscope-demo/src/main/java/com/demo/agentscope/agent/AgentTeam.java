package com.demo.agentscope.agent;

import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionEngine;
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
        return worker.reply(message);
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

    // ==================== 团队工具定义 ====================

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
                你是一个团队的领导者，拥有以下团队管理工具：

                1. team_create - 创建一个新的协作团队
                   参数: {"purpose": "团队目标描述"}

                2. agent_create - 创建一个工作者智能体
                   参数: {"name": "工作者名称", "system_prompt": "工作者的系统提示词"}

                3. agent_message - 向指定工作者发送消息并获取回复
                   参数: {"worker_name": "工作者名称", "message": "消息内容"}

                4. team_dissolve - 解散当前团队
                   参数: {}

                当任务需要分工协作时，你应该创建工作者来并行处理子任务。
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
}
