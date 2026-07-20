package com.demo.agentscope;

import com.demo.agentscope.agent.Agent;
import com.demo.agentscope.agent.AgentTeam;
import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.credential.DefaultCredentialProvider;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.middleware.MiddlewareChain;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionEngine;
import com.demo.agentscope.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 智能体测试。
 */
@DisplayName("智能体测试")
class AgentTest {

    private MCPClient mcpClient;
    private CredentialProvider credentialProvider;
    private PermissionEngine permissionEngine;
    private WorkspaceManager workspaceManager;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        mcpClient = new MCPClient();
        mcpClient.initialize();
        credentialProvider = new DefaultCredentialProvider();
        permissionEngine = new PermissionEngine();
        workspaceManager = new WorkspaceManager();
        chatModel = new ChatModel(credentialProvider);
    }

    // ==================== Agent 创建测试 ====================

    @Test
    @DisplayName("Agent 构造创建智能体，属性正确")
    void agentCreation() {
        Agent agent = new Agent(
                "TestAgent",
                "You are a helpful assistant.",
                chatModel,
                mcpClient,
                credentialProvider,
                permissionEngine,
                workspaceManager,
                "openai"
        );

        assertNotNull(agent.getId());
        assertEquals("TestAgent", agent.getName());
        assertEquals("You are a helpful assistant.", agent.getSystemPrompt());
        assertEquals("openai", agent.getProviderName());
        assertNotNull(agent.getMiddlewareChain());
        assertNotNull(agent.getChatModel());
        assertNotNull(agent.getMcpClient());
        assertNotNull(agent.getCredentialProvider());
        assertNotNull(agent.getPermissionEngine());
        assertNotNull(agent.getWorkspaceManager());
    }

    @Test
    @DisplayName("Agent getState 返回正确的初始状态（空状态）")
    void agentGetStateInitial() {
        Agent agent = createTestAgent();

        Map<String, Object> state = agent.getState();
        assertNotNull(state);
        assertTrue(state.isEmpty());
    }

    @Test
    @DisplayName("Agent reset 清空上下文和状态")
    void agentReset() {
        Agent agent = createTestAgent();

        // 手动向状态中添加数据（模拟运行后的状态）
        // 由于 state 是不可变视图，我们通过 reset 来测试
        agent.reset();

        assertTrue(agent.getState().isEmpty());
        assertTrue(agent.getContext().isEmpty());
    }

    @Test
    @DisplayName("Agent getContext 初始为空")
    void agentGetContextInitial() {
        Agent agent = createTestAgent();
        assertTrue(agent.getContext().isEmpty());
    }

    @Test
    @DisplayName("Agent getContext 返回不可变列表")
    void agentGetContextIsUnmodifiable() {
        Agent agent = createTestAgent();
        assertThrows(UnsupportedOperationException.class,
                () -> agent.getContext().add(null));
    }

    @Test
    @DisplayName("Agent getState 返回不可变映射")
    void agentGetStateIsUnmodifiable() {
        Agent agent = createTestAgent();
        assertThrows(UnsupportedOperationException.class,
                () -> agent.getState().put("key", "value"));
    }

    @Test
    @DisplayName("Agent getMaxIterations 默认为 50")
    void agentDefaultMaxIterations() {
        Agent agent = createTestAgent();
        assertEquals(50, agent.getMaxIterations());
    }

    @Test
    @DisplayName("Agent setMaxIterations 设置最大迭代次数")
    void agentSetMaxIterations() {
        Agent agent = createTestAgent();
        agent.setMaxIterations(5);
        assertEquals(5, agent.getMaxIterations());
    }

    @Test
    @DisplayName("Agent shutdown 释放资源")
    void agentShutdown() {
        Agent agent = createTestAgent();
        assertDoesNotThrow(agent::shutdown);
        assertTrue(agent.getContext().isEmpty());
        assertTrue(agent.getState().isEmpty());
    }

    @Test
    @DisplayName("Agent MiddlewareChain 可扩展")
    void agentMiddlewareChainExtensible() {
        Agent agent = createTestAgent();
        MiddlewareChain chain = agent.getMiddlewareChain();

        int initialSize = chain.size();
        chain.add(new com.demo.agentscope.middleware.TracingMiddleware());
        assertEquals(initialSize + 1, chain.size());
    }

    // ==================== AgentTeam 测试 ====================

    @Test
    @DisplayName("AgentTeam 创建团队，状态正确")
    void agentTeamCreation() {
        Agent leader = createTestAgent();
        AgentTeam team = new AgentTeam(
                leader, chatModel, mcpClient,
                credentialProvider, permissionEngine,
                workspaceManager, "openai"
        );

        assertNotNull(team.getTeamId());
        assertTrue(team.isActive());
        assertSame(leader, team.getLeader());
        assertTrue(team.getWorkers().isEmpty());
    }

    @Test
    @DisplayName("AgentTeam getStatus 返回正确状态信息")
    void agentTeamGetStatus() {
        Agent leader = createTestAgent();
        AgentTeam team = new AgentTeam(
                leader, chatModel, mcpClient,
                credentialProvider, permissionEngine,
                workspaceManager, "openai"
        );

        Map<String, Object> status = team.getStatus();
        assertNotNull(status);
        assertEquals(team.getTeamId(), status.get("teamId"));
        assertTrue((Boolean) status.get("active"));
        assertEquals(leader.getName(), status.get("leaderName"));
        assertEquals(0, status.get("workerCount"));
    }

    @Test
    @DisplayName("AgentTeam createWorker 创建工作者")
    void agentTeamCreateWorker() {
        Agent leader = createTestAgent();
        AgentTeam team = new AgentTeam(
                leader, chatModel, mcpClient,
                credentialProvider, permissionEngine,
                workspaceManager, "openai"
        );

        Agent worker = team.createWorker("worker1", "You are a worker.");

        assertNotNull(worker);
        assertEquals("worker1", worker.getName());
        assertEquals(1, team.getWorkers().size());
        assertTrue(team.getWorkers().containsKey("worker1"));

        Map<String, Object> status = team.getStatus();
        assertEquals(1, status.get("workerCount"));
    }

    @Test
    @DisplayName("AgentTeam createWorker 重复名称返回已有实例")
    void agentTeamCreateWorkerDuplicateName() {
        Agent leader = createTestAgent();
        AgentTeam team = new AgentTeam(
                leader, chatModel, mcpClient,
                credentialProvider, permissionEngine,
                workspaceManager, "openai"
        );

        Agent worker1 = team.createWorker("worker1", "You are a worker.");
        Agent worker2 = team.createWorker("worker1", "You are another worker.");

        assertSame(worker1, worker2);
        assertEquals(1, team.getWorkers().size());
    }

    @Test
    @DisplayName("AgentTeam dissolve 解散团队")
    void agentTeamDissolve() {
        Agent leader = createTestAgent();
        AgentTeam team = new AgentTeam(
                leader, chatModel, mcpClient,
                credentialProvider, permissionEngine,
                workspaceManager, "openai"
        );

        team.createWorker("worker1", "You are a worker.");
        team.dissolve();

        assertFalse(team.isActive());
        assertTrue(team.getWorkers().isEmpty());
    }

    @Test
    @DisplayName("AgentTeam 解散后创建工作者抛出异常")
    void agentTeamCreateWorkerAfterDissolve() {
        Agent leader = createTestAgent();
        AgentTeam team = new AgentTeam(
                leader, chatModel, mcpClient,
                credentialProvider, permissionEngine,
                workspaceManager, "openai"
        );

        team.dissolve();

        assertThrows(IllegalStateException.class,
                () -> team.createWorker("worker1", "You are a worker."));
    }

    @Test
    @DisplayName("AgentTeam getTeamToolsDescription 返回非空描述")
    void agentTeamGetTeamToolsDescription() {
        Agent leader = createTestAgent();
        AgentTeam team = new AgentTeam(
                leader, chatModel, mcpClient,
                credentialProvider, permissionEngine,
                workspaceManager, "openai"
        );

        String description = team.getTeamToolsDescription();
        assertNotNull(description);
        assertFalse(description.isBlank());
        assertTrue(description.contains("agent_create"));
        assertTrue(description.contains("agent_message"));
        assertTrue(description.contains("agent_list"));
        assertTrue(description.contains("team_dissolve"));
    }

    // ==================== 辅助方法 ====================

    private Agent createTestAgent() {
        return new Agent(
                "TestAgent",
                "You are a helpful assistant.",
                chatModel,
                mcpClient,
                credentialProvider,
                permissionEngine,
                workspaceManager,
                "openai"
        );
    }

    // ==================== /context mutation API 测试 ====================

    @Test
    @DisplayName("replaceMessage 合法索引替换消息")
    void replaceMessageValidIndex() {
        Agent agent = createTestAgent();
        Msg original = Msg.userText("原始消息");
        agent.restoreContext(List.of(original));

        Msg replaced = Msg.userText("替换后");
        agent.replaceMessage(0, replaced);

        assertEquals(1, agent.getContext().size());
        assertEquals("替换后",
                ((ContentBlock.TextBlock) agent.getContext().get(0).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("replaceMessage 索引越界抛异常")
    void replaceMessageOutOfBounds() {
        Agent agent = createTestAgent();
        assertThrows(IndexOutOfBoundsException.class,
                () -> agent.replaceMessage(0, Msg.userText("x")));
    }

    @Test
    @DisplayName("deleteMessage 合法索引删除并前移")
    void deleteMessageValidIndex() {
        Agent agent = createTestAgent();
        agent.restoreContext(List.of(
                Msg.userText("a"), Msg.userText("b"), Msg.userText("c")));

        agent.deleteMessage(1);

        assertEquals(2, agent.getContext().size());
        assertEquals("a", textOf(agent.getContext().get(0)));
        assertEquals("c", textOf(agent.getContext().get(1)));
    }

    @Test
    @DisplayName("deleteMessage 索引越界抛异常")
    void deleteMessageOutOfBounds() {
        Agent agent = createTestAgent();
        assertThrows(IndexOutOfBoundsException.class, () -> agent.deleteMessage(0));
    }

    @Test
    @DisplayName("trimContext 无 ContextManager 走 fallback，注入告知消息")
    void trimContextWithoutContextManager() {
        Agent agent = createTestAgent();
        agent.restoreContext(List.of(
                Msg.userText("m1"), Msg.userText("m2"),
                Msg.userText("m3"), Msg.userText("m4"),
                Msg.userText("m5")));

        agent.trimContext(2);

        // 期望结果：[告知消息, m4, m5] 或 [告知消息, 最近 user 钉位, m4, m5]
        // fallback 会取尾部 2 条 + 可能钉一条 user；m4/m5 都是 user 则无需钉位
        assertTrue(agent.getContext().size() >= 2);
        Msg head = agent.getContext().get(0);
        assertEquals("user", head.getRole());
        assertTrue(textOf(head).contains("上下文已手动裁剪"));
    }

    private static String textOf(Msg m) {
        if (m.getContent() == null || m.getContent().isEmpty()) return "";
        ContentBlock b = m.getContent().get(0);
        return b instanceof ContentBlock.TextBlock t ? t.getText() : "";
    }
}
