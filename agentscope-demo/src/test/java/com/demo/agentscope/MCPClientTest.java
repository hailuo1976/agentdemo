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
    @DisplayName("MCPClient initialize 注册内置工具")
    void initializeRegistersBuiltinTools() {
        assertTrue(client.isInitialized());
        assertFalse(client.listTools().isEmpty());
    }

    @Test
    @DisplayName("listTools 返回 4 个内置工具")
    void listToolsReturns4BuiltinTools() {
        List<MCPClient.ToolInfo> tools = client.listTools();
        assertEquals(4, tools.size());

        List<String> toolNames = tools.stream().map(MCPClient.ToolInfo::name).toList();
        assertTrue(toolNames.contains("get_weather"));
        assertTrue(toolNames.contains("calculate"));
        assertTrue(toolNames.contains("search"));
        assertTrue(toolNames.contains("get_time"));
    }

    @Test
    @DisplayName("executeTool get_weather 返回成功")
    void executeToolGetWeather() {
        MCPClient.ToolResult result = client.executeTool("get_weather",
                Map.of("city", "Beijing", "unit", "celsius"));

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("Beijing"));
        assertTrue(result.getOutput().contains("22.0"));
        assertNull(result.getError());
    }

    @Test
    @DisplayName("executeTool calculate 返回正确结果")
    void executeToolCalculate() {
        MCPClient.ToolResult result = client.executeTool("calculate",
                Map.of("expression", "2+3*4"));

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("14"));
    }

    @Test
    @DisplayName("executeTool calculate 复杂表达式")
    void executeToolCalculateComplex() {
        MCPClient.ToolResult result = client.executeTool("calculate",
                Map.of("expression", "(10+5)*3"));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("45"));
    }

    @Test
    @DisplayName("executeTool search 返回成功")
    void executeToolSearch() {
        MCPClient.ToolResult result = client.executeTool("search",
                Map.of("query", "AgentScope"));

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("AgentScope"));
    }

    @Test
    @DisplayName("executeTool get_time 返回成功")
    void executeToolGetTime() {
        MCPClient.ToolResult result = client.executeTool("get_time",
                Map.of("timezone", "UTC"));

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("UTC"));
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
        MCPClient.ToolResult result = client.executeTool("get_weather",
                Map.of("city", "Beijing"));

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

    // ==================== SimpleExpressionEvaluator 测试 ====================

    @Test
    @DisplayName("SimpleExpressionEvaluator 基本算术运算")
    void expressionEvaluator() {
        MCPClient.SimpleExpressionEvaluator evaluator = new MCPClient.SimpleExpressionEvaluator();

        assertEquals(7.0, evaluator.evaluate("3+4"), 0.001);
        assertEquals(12.0, evaluator.evaluate("3*4"), 0.001);
        assertEquals(2.0, evaluator.evaluate("6/3"), 0.001);
        assertEquals(-1.0, evaluator.evaluate("3-4"), 0.001);
    }

    @Test
    @DisplayName("SimpleExpressionEvaluator 括号运算")
    void expressionEvaluatorWithParentheses() {
        MCPClient.SimpleExpressionEvaluator evaluator = new MCPClient.SimpleExpressionEvaluator();
        assertEquals(14.0, evaluator.evaluate("2*(3+4)"), 0.001);
        assertEquals(10.0, evaluator.evaluate("(2+3)*2"), 0.001);
    }
}
