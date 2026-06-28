package com.demo.pimono.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);
    private static final int MAX_CONTEXT_MESSAGES = 50;

    private final Context currentContext;
    private final Map<String, Object> sessionState;

    public ContextManager() {
        this.currentContext = new Context("You are a helpful AI assistant. Use the available tools when needed to answer questions accurately.");
        this.sessionState = new ConcurrentHashMap<>();
    }

    public Context getCurrentContext() {
        return currentContext;
    }

    public void addUserMessage(String content) {
        currentContext.addUserMessage(content);
        trimContext();
        log.debug("Added user message to context");
    }

    public void addAssistantMessage(String content) {
        currentContext.addAssistantMessage(content);
        trimContext();
        log.debug("Added assistant message to context");
    }

    public void addToolResult(String toolCallId, String toolName, String result, boolean isError) {
        currentContext.addToolResultMessage(toolCallId, toolName, result, isError);
        trimContext();
        log.debug("Added tool result for {}: {}", toolName, isError ? "ERROR" : "SUCCESS");
    }

    public void addAssistantToolCall(String content, java.util.List<Context.ToolCallEntry> toolCalls) {
        currentContext.addAssistantToolCallMessage(content, toolCalls);
        trimContext();
        log.debug("Added assistant tool call message with {} calls", toolCalls.size());
    }

    public void setSessionState(String key, Object value) {
        sessionState.put(key, value);
    }

    public Object getSessionState(String key) {
        return sessionState.get(key);
    }

    public void clear() {
        currentContext.clearMessages();
        sessionState.clear();
        log.info("Context cleared");
    }

    private void trimContext() {
        while (currentContext.getMessageCount() > MAX_CONTEXT_MESSAGES) {
            currentContext.removeFirstMessage();
        }
    }
}
