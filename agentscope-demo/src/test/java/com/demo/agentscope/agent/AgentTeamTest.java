package com.demo.agentscope.agent;

import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.credential.DefaultCredentialProvider;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionEngine;
import com.demo.agentscope.team.ArtifactManager;
import com.demo.agentscope.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 智能体团队工具注册与调用测试。
 * <p>
 * 验证 registerTeamTools() 注册的团队工具能被 MCPClient 发现和执行，包括 artifact 工具端到端流程。
 * </p>
 */
@DisplayName("智能体团队工具测试")
class AgentTeamTest {

    private MCPClient mcpClient;
    private AgentTeam team;
    private Agent leader;
    private Path tempWorkspaceRoot;

    @BeforeEach
    void setUp() throws Exception {
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

        // 为 artifact 测试准备临时工作目录
        tempWorkspaceRoot = Files.createTempDirectory("agent-team-test");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (team != null) {
            team.clearCurrentAgentForTest();
        }
        if (tempWorkspaceRoot != null && Files.exists(tempWorkspaceRoot)) {
            try (var stream = Files.walk(tempWorkspaceRoot)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
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

        MCPClient.ToolResult result = mcpClient.executeTool("team_dissolve",
                java.util.Map.of("requester_id", "external_admin"));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("已解散"));
        assertFalse(team.isActive(), "团队应已解散");
        assertTrue(team.getWorkers().isEmpty(), "工作者应已清空");
    }

    @Test
    @DisplayName("team_dissolve 缺少 requester_id 应返回错误")
    void testTeamDissolveMissingRequesterId() {
        team.registerTeamTools();

        MCPClient.ToolResult result = mcpClient.executeTool("team_dissolve", java.util.Map.of());

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("错误"), "缺少 requester_id 应返回错误提示");
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

    // ==================== Artifact 工具端到端 ====================

    /**
     * 装配 ArtifactManager 并注册团队工具，返回创建好的 worker 名字以便测试。
     */
    private void setupWithArtifactManager() {
        ArtifactManager mgr = new ArtifactManager(tempWorkspaceRoot, team.getTeamId());
        team.setArtifactManager(mgr);
        team.registerTeamTools();
    }

    @Test
    @DisplayName("装配 ArtifactManager 后应注册 4 个 artifact 工具")
    void testArtifactToolsRegistered() {
        setupWithArtifactManager();

        List<String> toolNames = mcpClient.listTools().stream()
                .map(MCPClient.ToolInfo::name).toList();
        assertTrue(toolNames.contains("share_file"), "应包含 share_file");
        assertTrue(toolNames.contains("list_artifacts"), "应包含 list_artifacts");
        assertTrue(toolNames.contains("get_artifact"), "应包含 get_artifact");
        assertTrue(toolNames.contains("mark_artifact_read"), "应包含 mark_artifact_read");
    }

    @Test
    @DisplayName("leader 通过 share_file 发送文件后能 list_artifacts 看到")
    void testLeaderShareAndList() {
        setupWithArtifactManager();

        // leader 直接调用工具（currentAgentName 未设置 → resolveCurrentAgent 返回 leader 名字）
        MCPClient.ToolResult sendResult = mcpClient.executeTool("share_file",
                Map.of(
                        "filename", "report.json",
                        "recipients", List.of("worker-1"),
                        "content", "{\"k\":1}",
                        "description", "test report"
                ));

        assertTrue(sendResult.isSuccess(), "share_file 应成功: " + sendResult.getError());
        assertTrue(sendResult.getOutput().contains("art_"), "输出应包含 artifactId");
        assertTrue(sendResult.getOutput().contains("report.json"));

        // list 应能看到这条记录
        MCPClient.ToolResult listResult = mcpClient.executeTool("list_artifacts", Map.of());
        assertTrue(listResult.isSuccess());
        assertTrue(listResult.getOutput().contains("report.json"));
        // leader 是 sender，状态列应为 SENT
        assertTrue(listResult.getOutput().contains("SENT"));
    }

    @Test
    @DisplayName("worker 通过 get_artifact 接收文件并推进到 RECEIVED")
    void testWorkerReceiveArtifact() {
        setupWithArtifactManager();

        // leader 发送
        MCPClient.ToolResult sendResult = mcpClient.executeTool("share_file",
                Map.of(
                        "filename", "data.csv",
                        "recipients", List.of("worker-1"),
                        "content", "a,b\n1,2"
                ));
        assertTrue(sendResult.isSuccess());

        // 从 send 输出里提取 artifactId
        String artifactId = extractArtifactId(sendResult.getOutput());
        assertNotNull(artifactId, "应能提取 artifactId");

        // 模拟 worker-1 调用 get_artifact
        team.setCurrentAgentForTest("worker-1");
        MCPClient.ToolResult getResult = mcpClient.executeTool("get_artifact",
                Map.of("artifactId", artifactId));

        assertTrue(getResult.isSuccess(), "get_artifact 应成功: " + getResult.getError());
        assertTrue(getResult.getOutput().contains("data.csv"));
        assertTrue(getResult.getOutput().contains("sha256 verified")
                || getResult.getOutput().contains("verified"), "应通过 sha256 校验");
        assertTrue(getResult.getOutput().contains("a,b"), "应包含文件内容");

        // 再次 list（从 worker-1 视角）应显示 RECEIVED
        MCPClient.ToolResult listResult = mcpClient.executeTool("list_artifacts", Map.of());
        assertTrue(listResult.getOutput().contains("RECEIVED"));
    }

    @Test
    @DisplayName("非 recipient 调用 get_artifact 应被拒绝")
    void testNonRecipientDenied() {
        setupWithArtifactManager();

        // leader 发给 worker-1
        MCPClient.ToolResult sendResult = mcpClient.executeTool("share_file",
                Map.of(
                        "filename", "secret.txt",
                        "recipients", List.of("worker-1"),
                        "content", "top secret"
                ));
        String artifactId = extractArtifactId(sendResult.getOutput());

        // 模拟 worker-2（非 recipient）调用
        team.setCurrentAgentForTest("worker-2");
        MCPClient.ToolResult getResult = mcpClient.executeTool("get_artifact",
                Map.of("artifactId", artifactId));

        // 工具执行 isSuccess=true（MCP 协议层成功），但输出应包含权限拒绝信息
        assertTrue(getResult.isSuccess());
        assertTrue(getResult.getOutput().contains("无权")
                || getResult.getOutput().contains("权限")
                || getResult.getOutput().contains("拒绝")
                || getResult.getOutput().contains("错误"), "应拒绝非 recipient: " + getResult.getOutput());
    }

    @Test
    @DisplayName("mark_artifact_read 推进状态到 READ")
    void testMarkReadTransitionsToRead() {
        setupWithArtifactManager();

        // leader 发给 worker-1
        MCPClient.ToolResult sendResult = mcpClient.executeTool("share_file",
                Map.of(
                        "filename", "note.md",
                        "recipients", List.of("worker-1"),
                        "content", "# hello"
                ));
        String artifactId = extractArtifactId(sendResult.getOutput());

        // worker-1 标记已读
        team.setCurrentAgentForTest("worker-1");
        MCPClient.ToolResult markResult = mcpClient.executeTool("mark_artifact_read",
                Map.of("artifactId", artifactId));
        assertTrue(markResult.isSuccess());
        assertTrue(markResult.getOutput().contains("READ"));

        // list 应显示 READ
        MCPClient.ToolResult listResult = mcpClient.executeTool("list_artifacts", Map.of());
        assertTrue(listResult.getOutput().contains("READ"));
    }

    @Test
    @DisplayName("dissolve 后 artifact 被归档（dissolvedAt 非空）")
    void testDissolveArchivesArtifacts() {
        setupWithArtifactManager();

        // 发送一个 artifact
        mcpClient.executeTool("share_file",
                Map.of(
                        "filename", "final.json",
                        "recipients", List.of("worker-1"),
                        "content", "{}"
                ));
        ArtifactManager mgr = team.getArtifactManager();
        assertNotNull(mgr);

        // 解散团队
        team.dissolve();

        // 验证归档：list 仍可见（sender=leader 视角）
        // 重建一个新的 ArtifactManager 模拟重启
        ArtifactManager reloaded = new ArtifactManager(tempWorkspaceRoot, team.getTeamId());
        List<com.demo.agentscope.team.ArtifactSummary> artifacts =
                reloaded.list("leader", null);
        assertEquals(1, artifacts.size());
        // 通过 list 拿到的 artifactId 查 metadata 验证 dissolvedAt
        String artifactId = artifacts.get(0).getArtifactId();
        com.demo.agentscope.team.Artifact meta = reloaded.getMetadata("leader", artifactId);
        assertNotNull(meta.getDissolvedAt(), "dissolvedAt 应非空");
    }

    @Test
    @DisplayName("未装配 ArtifactManager 时不注册 artifact 工具（向后兼容）")
    void testNoArtifactManagerBackwardCompat() {
        // 不调用 setArtifactManager，直接 registerTeamTools
        team.registerTeamTools();

        List<String> toolNames = mcpClient.listTools().stream()
                .map(MCPClient.ToolInfo::name).toList();
        assertFalse(toolNames.contains("share_file"), "未装配时不应注册 share_file");
        assertFalse(toolNames.contains("list_artifacts"), "未装配时不应注册 list_artifacts");
    }

    /**
     * 从工具输出里提取形如 "art_xxx_yyy" 的 artifactId。
     */
    private static String extractArtifactId(String output) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(art_\\d+_[0-9a-f]{8})");
        java.util.regex.Matcher m = p.matcher(output);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
