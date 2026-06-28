package com.demo.agentscope.agent;

import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.credential.DefaultCredentialProvider;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionEngine;
import com.demo.agentscope.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 智能体团队工具注册与调用测试。
 * <p>
 * 验证 registerTeamTools() 注册的 4 个团队工具能被 MCPClient 发现和执行。
 * </p>
 */
@DisplayName("智能体团队工具测试")
class AgentTeamTest {

    private MCPClient mcpClient;
    private AgentTeam team;
    private Agent leader;

    @BeforeEach
    void setUp() {
        mcpClient = new MCPClient();
        mcpClient.initialize();

        PermissionEngine permissionEngine = new PermissionEngine();
        WorkspaceManager workspaceManager = new WorkspaceManager();
        CredentialProvider credentialProvider = new DefaultCredentialProvider();

        ChatModel chatModel = new ChatModel(credentialProvider);
        leader = new Agent("leader", "你是团队领导者",
                chatModel, mcpClient, credentialProvider, permissionEngine,
                workspaceManager, "openai");

        team = new AgentTeam(leader, chatModel, mcpClient,
                credentialProvider, permissionEngine, workspaceManager, "openai");
    }

    @Test
    @DisplayName("registerTeamTools 后 MCPClient 应包含 4 个团队工具")
    void testTeamToolsRegistered() {
        team.registerTeamTools();

        List<MCPClient.ToolInfo> tools = mcpClient.listTools();
        List<String> toolNames = tools.stream().map(MCPClient.ToolInfo::name).toList();

        assertTrue(toolNames.contains("agent_create"), "应包含 agent_create 工具");
        assertTrue(toolNames.contains("agent_message"), "应包含 agent_message 工具");
        assertTrue(toolNames.contains("agent_list"), "应包含 agent_list 工具");
        assertTrue(toolNames.contains("team_dissolve"), "应包含 team_dissolve 工具");
    }

    @Test
    @DisplayName("registerTeamTools 后领导者系统提示词应包含团队描述")
    void testLeaderSystemPromptUpdated() {
        String originalPrompt = leader.getSystemPrompt();
        assertFalse(originalPrompt.contains("团队管理工具"));

        team.registerTeamTools();

        String updatedPrompt = leader.getSystemPrompt();
        assertTrue(updatedPrompt.contains("团队管理工具"), "提示词应包含团队管理工具描述");
        assertTrue(updatedPrompt.contains("agent_create"), "提示词应包含 agent_create");
        assertTrue(updatedPrompt.contains("team_dissolve"), "提示词应包含 team_dissolve");
    }

    @Test
    @DisplayName("agent_create 工具应创建工作者")
    void testAgentCreateTool() {
        team.registerTeamTools();

        MCPClient.ToolResult result = mcpClient.executeTool("agent_create",
                java.util.Map.of("name", "researcher", "system_prompt", "你是研究员"));

        assertTrue(result.isSuccess(), "agent_create 应成功: " + result.getError());
        assertTrue(result.getOutput().contains("researcher"), "输出应包含工作者名称");
        assertEquals(1, team.getWorkers().size(), "团队应有 1 个工作者");
        assertNotNull(team.getWorkers().get("researcher"), "researcher 工作者应存在");
    }

    @Test
    @DisplayName("agent_list 工具应列出工作者")
    void testAgentListTool() {
        team.registerTeamTools();
        team.createWorker("analyst", "你是分析师");
        team.createWorker("writer", "你是写作员");

        MCPClient.ToolResult result = mcpClient.executeTool("agent_list", java.util.Map.of());

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("analyst"));
        assertTrue(result.getOutput().contains("writer"));
    }

    @Test
    @DisplayName("agent_list 空团队应返回提示信息")
    void testAgentListEmpty() {
        team.registerTeamTools();

        MCPClient.ToolResult result = mcpClient.executeTool("agent_list", java.util.Map.of());

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("没有工作者"));
    }

    @Test
    @DisplayName("agent_message 向不存在的工作者发送消息应返回错误")
    void testAgentMessageToNonExistentWorker() {
        team.registerTeamTools();

        MCPClient.ToolResult result = mcpClient.executeTool("agent_message",
                java.util.Map.of("worker_name", "ghost", "message", "hello"));

        assertTrue(result.isSuccess()); // 工具执行本身成功，但输出包含不存在提示
        assertTrue(result.getOutput().contains("不存在"));
    }

    @Test
    @DisplayName("team_dissolve 工具应解散团队")
    void testTeamDissolveTool() {
        team.registerTeamTools();
        team.createWorker("temp", "临时工作者");

        MCPClient.ToolResult result = mcpClient.executeTool("team_dissolve", java.util.Map.of());

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("已解散"));
        assertFalse(team.isActive(), "团队应已解散");
        assertTrue(team.getWorkers().isEmpty(), "工作者应已清空");
    }

    @Test
    @DisplayName("agent_create 名称不能为空")
    void testAgentCreateEmptyName() {
        team.registerTeamTools();

        MCPClient.ToolResult result = mcpClient.executeTool("agent_create",
                java.util.Map.of("name", "", "system_prompt", "测试"));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("错误"));
    }

    @Test
    @DisplayName("重复创建同名工作者返回已有实例")
    void testCreateDuplicateWorker() {
        team.registerTeamTools();

        mcpClient.executeTool("agent_create",
                java.util.Map.of("name", "dup", "system_prompt", "测试1"));
        MCPClient.ToolResult result2 = mcpClient.executeTool("agent_create",
                java.util.Map.of("name", "dup", "system_prompt", "测试2"));

        assertTrue(result2.isSuccess());
        assertEquals(1, team.getWorkers().size(), "不应创建重复工作者");
    }

    @Test
    @DisplayName("团队工具应有参数 schema")
    void testTeamToolsHaveParameters() {
        team.registerTeamTools();

        List<MCPClient.ToolInfo> tools = mcpClient.listTools();
        for (MCPClient.ToolInfo tool : tools) {
            if (tool.name().equals("agent_create")) {
                assertNotNull(tool.parametersJson());
                assertFalse(tool.parametersJson().isBlank());
                assertTrue(tool.parametersJson().contains("name"));
                assertTrue(tool.parametersJson().contains("system_prompt"));
            }
        }
    }
}
