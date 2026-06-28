package com.demo.pimono;

import com.demo.pimono.agent.AgentCore;
import com.demo.pimono.context.Context;
import com.demo.pimono.context.ContextManager;
import com.demo.pimono.mcp.McpClientManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentCoreTest {

    private AgentCore agentCore;
    private McpClientManager mcpManager;
    private ContextManager contextManager;

    @BeforeEach
    void setUp() {
        mcpManager = new McpClientManager();
        mcpManager.initialize();
        contextManager = new ContextManager();
        agentCore = new AgentCore("test-key", "", "gpt-4o-mini", mcpManager, contextManager);
    }

    @Test
    @DisplayName("Should return active status after initialization")
    void shouldReturnActiveStatus() {
        Map<String, Object> status = agentCore.getStatus();
        assertTrue((boolean) status.get("active"));
        assertEquals("gpt-4o-mini", status.get("model"));
        assertEquals(0, status.get("totalQueries"));
    }

    @Test
    @DisplayName("Should track total queries")
    void shouldTrackTotalQueries() {
        assertEquals(0, agentCore.getStatus().get("totalQueries"));
    }

    @Test
    @DisplayName("Should track available tools in status")
    void shouldTrackAvailableToolsInStatus() {
        Map<String, Object> status = agentCore.getStatus();
        assertNotNull(status.get("availableTools"));
        assertTrue(status.get("availableTools") instanceof java.util.List);
    }

    @Test
    @DisplayName("Should track context message count")
    void shouldTrackContextMessageCount() {
        Map<String, Object> status = agentCore.getStatus();
        assertEquals(0, status.get("contextMessages"));
    }

    @Test
    @DisplayName("Should deactivate after shutdown")
    void shouldDeactivateAfterShutdown() {
        agentCore.shutdown();
        Map<String, Object> status = agentCore.getStatus();
        assertFalse((boolean) status.get("active"));
    }

    @Test
    @DisplayName("Should return not active message after shutdown")
    void shouldReturnNotActiveMessageAfterShutdown() {
        agentCore.shutdown();
        String response = agentCore.chat("Hello");
        assertEquals("Agent is not active.", response);
    }
}
