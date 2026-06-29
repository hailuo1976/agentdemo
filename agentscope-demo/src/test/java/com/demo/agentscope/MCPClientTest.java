package com.demo.agentscope;

import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.mcp.MCPConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 客户端测试。
 */
@DisplayName("MCP 客户端测试")
class MCPClientTest {

    private MCPClient client;

    @BeforeEach
    void setUp() {
        client = new MCPClient();
        client.initialize();
    }

    @Test
    @DisplayName("MCPClient initialize 初始化成功")
    void initializeSuccess() {
        assertTrue(client.isInitialized());
    }

    @Test
    @DisplayName("listTools 返回空列表（内置工具已移除）")
    void listToolsReturnsEmpty() {
        List<MCPClient.ToolInfo> tools = client.listTools();
        assertEquals(0, tools.size());
    }

    @Test
    @DisplayName("executeTool 未知工具返回错误")
    void executeToolUnknownReturnsError() {
        MCPClient.ToolResult result = client.executeTool("unknown_tool", Map.of());

        assertFalse(result.isSuccess());
        assertNull(result.getOutput());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("未找到工具"));
    }

    @Test
    @DisplayName("ToolResult toToolResultBlock 转换正确")
    void toolResultToToolResultBlock() {
        MCPClient.ToolResult result = new MCPClient.ToolResult(true, "测试输出", null);

        var block = result.toToolResultBlock("call_0");
        assertEquals("call_0", block.getToolCallId());
        assertFalse(block.isError());
        assertNotNull(block.getContent());
    }

    @Test
    @DisplayName("ToolResult 错误结果 toToolResultBlock isError 为 true")
    void toolResultErrorToToolResultBlock() {
        MCPClient.ToolResult errorResult = new MCPClient.ToolResult(false, null, "工具未找到");

        var block = errorResult.toToolResultBlock("call_1");
        assertTrue(block.isError());
        assertTrue(block.getContent().contains("工具未找到"));
    }

    @Test
    @DisplayName("shutdown 后客户端不再初始化")
    void shutdownClearsState() {
        client.shutdown();
        assertFalse(client.isInitialized());
        assertTrue(client.listTools().isEmpty());
    }

    // ==================== MCPConfig 密封接口测试 ====================

    @Test
    @DisplayName("StdioMCPConfig 创建并获取属性")
    void stdioMCPConfigCreation() {
        MCPConfig.StdioMCPConfig config = new MCPConfig.StdioMCPConfig(
                "weather-server", "npx", List.of("-y", "@modelcontextprotocol/server-weather"));

        assertEquals("weather-server", config.getName());
        assertEquals("npx", config.command());
        assertEquals(2, config.args().size());
        assertEquals("-y", config.args().get(0));
    }

    @Test
    @DisplayName("HttpMCPConfig 创建并获取属性")
    void httpMCPConfigCreation() {
        MCPConfig.HttpMCPConfig config = new MCPConfig.HttpMCPConfig(
                "remote-server", "https://mcp.example.com/sse", Map.of("Authorization", "Bearer token"));

        assertEquals("remote-server", config.getName());
        assertEquals("https://mcp.example.com/sse", config.url());
        assertEquals(1, config.headers().size());
        assertEquals("Bearer token", config.headers().get("Authorization"));
    }

    @Test
    @DisplayName("MCPConfig 类型模式匹配")
    void mcpConfigPatternMatching() {
        MCPConfig stdio = new MCPConfig.StdioMCPConfig("s1", "cmd", List.of());
        MCPConfig http = new MCPConfig.HttpMCPConfig("h1", "http://url", Map.of());

        assertTrue(stdio instanceof MCPConfig.StdioMCPConfig);
        assertTrue(http instanceof MCPConfig.HttpMCPConfig);

        if (stdio instanceof MCPConfig.StdioMCPConfig s) {
            assertEquals("cmd", s.command());
        }
        if (http instanceof MCPConfig.HttpMCPConfig h) {
            assertEquals("http://url", h.url());
        }
    }

    @Test
    @DisplayName("addConfig 添加 MCP 配置")
    void addConfig() {
        MCPClient newClient = new MCPClient();
        newClient.addConfig(new MCPConfig.StdioMCPConfig("test", "echo", List.of()));
        // 配置已添加但未初始化
        assertFalse(newClient.isInitialized());
    }
}
