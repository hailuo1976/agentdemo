package com.demo.pimono;

import com.demo.pimono.mcp.McpClientManager;
import com.demo.pimono.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpClientManagerTest {

    private McpClientManager mcpManager;

    @BeforeEach
    void setUp() {
        mcpManager = new McpClientManager();
    }

    @Test
    @DisplayName("Should initialize with builtin tools")
    void shouldInitializeWithBuiltinTools() {
        mcpManager.initialize();
        assertTrue(mcpManager.isInitialized());
        assertTrue(mcpManager.getTotalToolCount() >= 5);
    }

    @Test
    @DisplayName("Should have all expected builtin tools")
    void shouldHaveAllExpectedBuiltinTools() {
        mcpManager.initialize();
        var toolNames = mcpManager.getToolNames();
        assertTrue(toolNames.contains("get_weather"));
        assertTrue(toolNames.contains("calculate"));
        assertTrue(toolNames.contains("search"));
        assertTrue(toolNames.contains("get_time"));
        assertTrue(toolNames.contains("translate"));
    }

    @Test
    @DisplayName("Should execute weather tool")
    void shouldExecuteWeatherTool() {
        mcpManager.initialize();
        ToolResult result = mcpManager.executeTool("get_weather", Map.of("city", "Shanghai"));
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Shanghai"));
        assertTrue(result.getOutput().contains("temperature"));
    }

    @Test
    @DisplayName("Should execute calculate tool")
    void shouldExecuteCalculateTool() {
        mcpManager.initialize();
        ToolResult result = mcpManager.executeTool("calculate", Map.of("expression", "10 + 20"));
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("30"));
    }

    @Test
    @DisplayName("Should execute search tool")
    void shouldExecuteSearchTool() {
        mcpManager.initialize();
        ToolResult result = mcpManager.executeTool("search", Map.of("query", "Java agent"));
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Java agent"));
    }

    @Test
    @DisplayName("Should execute translate tool")
    void shouldExecuteTranslateTool() {
        mcpManager.initialize();
        ToolResult result = mcpManager.executeTool("translate", Map.of("text", "Hello", "target_lang", "zh"));
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Hello"));
        assertTrue(result.getOutput().contains("zh"));
    }

    @Test
    @DisplayName("Should return error for unknown tool")
    void shouldReturnErrorForUnknownTool() {
        mcpManager.initialize();
        ToolResult result = mcpManager.executeTool("nonexistent", Map.of());
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("Should not be initialized before init call")
    void shouldNotBeInitializedBeforeInit() {
        assertFalse(mcpManager.isInitialized());
    }

    @Test
    @DisplayName("Should shutdown cleanly")
    void shouldShutdownCleanly() {
        mcpManager.initialize();
        mcpManager.shutdown();
        assertFalse(mcpManager.isInitialized());
    }

    @Test
    @DisplayName("Should list all tools with details")
    void shouldListAllToolsWithDetails() {
        mcpManager.initialize();
        var tools = mcpManager.getAllTools();
        assertFalse(tools.isEmpty());
        tools.forEach(tool -> {
            assertNotNull(tool.getName());
            assertNotNull(tool.getDescription());
        });
    }
}
