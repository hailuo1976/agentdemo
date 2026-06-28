package com.demo.pimono;

import com.demo.pimono.context.Context;
import com.demo.pimono.context.ContextManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    private ContextManager contextManager;

    @BeforeEach
    void setUp() {
        contextManager = new ContextManager();
    }

    @Test
    @DisplayName("Should add user message to context")
    void shouldAddUserMessage() {
        contextManager.addUserMessage("Hello");
        Context context = contextManager.getCurrentContext();
        assertEquals(1, context.getMessageCount());
        assertEquals("user", context.getMessages().get(0).getRole());
        assertEquals("Hello", context.getMessages().get(0).getContent());
    }

    @Test
    @DisplayName("Should add assistant message to context")
    void shouldAddAssistantMessage() {
        contextManager.addAssistantMessage("Hi there!");
        Context context = contextManager.getCurrentContext();
        assertEquals(1, context.getMessageCount());
        assertEquals("assistant", context.getMessages().get(0).getRole());
    }

    @Test
    @DisplayName("Should add tool result to context")
    void shouldAddToolResult() {
        contextManager.addToolResult("tc-1", "get_weather", "Sunny, 22C", false);
        Context context = contextManager.getCurrentContext();
        assertEquals(1, context.getMessageCount());
        assertEquals("tool", context.getMessages().get(0).getRole());
        assertEquals("get_weather", context.getMessages().get(0).getToolName());
    }

    @Test
    @DisplayName("Should add assistant tool call to context")
    void shouldAddAssistantToolCall() {
        java.util.List<Context.ToolCallEntry> toolCalls = java.util.List.of(
                new Context.ToolCallEntry("tc-1", "get_weather", "{\"city\":\"Beijing\"}")
        );
        contextManager.addAssistantToolCall(null, toolCalls);
        Context context = contextManager.getCurrentContext();
        assertEquals(1, context.getMessageCount());
        assertNotNull(context.getMessages().get(0).getToolCalls());
        assertEquals(1, context.getMessages().get(0).getToolCalls().size());
    }

    @Test
    @DisplayName("Should maintain message order in context")
    void shouldMaintainMessageOrder() {
        contextManager.addUserMessage("First");
        contextManager.addAssistantMessage("Second");
        contextManager.addUserMessage("Third");

        Context context = contextManager.getCurrentContext();
        assertEquals(3, context.getMessageCount());
        assertEquals("user", context.getMessages().get(0).getRole());
        assertEquals("assistant", context.getMessages().get(1).getRole());
        assertEquals("user", context.getMessages().get(2).getRole());
    }

    @Test
    @DisplayName("Should clear context")
    void shouldClearContext() {
        contextManager.addUserMessage("Test");
        contextManager.addAssistantMessage("Response");
        contextManager.clear();

        assertEquals(0, contextManager.getCurrentContext().getMessageCount());
    }

    @Test
    @DisplayName("Should preserve system prompt after clear")
    void shouldPreserveSystemPromptAfterClear() {
        String originalPrompt = contextManager.getCurrentContext().getSystemPrompt();
        contextManager.addUserMessage("Test");
        contextManager.clear();

        assertEquals(originalPrompt, contextManager.getCurrentContext().getSystemPrompt());
    }

    @Test
    @DisplayName("Should store and retrieve session state")
    void shouldStoreAndRetrieveSessionState() {
        contextManager.setSessionState("key1", "value1");
        contextManager.setSessionState("key2", 42);

        assertEquals("value1", contextManager.getSessionState("key1"));
        assertEquals(42, contextManager.getSessionState("key2"));
    }

    @Test
    @DisplayName("Should convert to simple messages")
    void shouldConvertToSimpleMessages() {
        contextManager.addUserMessage("Hello");
        contextManager.addAssistantMessage("Hi");

        Context context = contextManager.getCurrentContext();
        var simpleMessages = context.toSimpleMessages();
        assertEquals(2, simpleMessages.size());
        assertEquals("user", simpleMessages.get(0).getRole());
        assertEquals("assistant", simpleMessages.get(1).getRole());
    }
}
