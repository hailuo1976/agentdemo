package com.demo.pimono.context;

import java.time.Instant;
import java.util.*;

public class Context {

    private String systemPrompt;
    private final List<Message> messages;
    private final Map<String, Object> metadata;

    public Context() {
        this.messages = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    public Context(String systemPrompt) {
        this();
        this.systemPrompt = systemPrompt;
    }

    public void addUserMessage(String content) {
        messages.add(new Message("user", content, Instant.now()));
    }

    public void addAssistantMessage(String content) {
        messages.add(new Message("assistant", content, Instant.now()));
    }

    public void addToolResultMessage(String toolCallId, String toolName, String content, boolean isError) {
        Message msg = new Message("tool", content, Instant.now());
        msg.setToolCallId(toolCallId);
        msg.setToolName(toolName);
        msg.setError(isError);
        messages.add(msg);
    }

    public void addAssistantToolCallMessage(String content, List<ToolCallEntry> toolCalls) {
        Message msg = new Message("assistant", content != null ? content : "", Instant.now());
        msg.setToolCalls(toolCalls);
        messages.add(msg);
    }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public List<Message> getMessages() { return Collections.unmodifiableList(messages); }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public void setMetadata(String key, Object value) { metadata.put(key, value); }

    public int getMessageCount() { return messages.size(); }

    public void clearMessages() { messages.clear(); }

    public void removeFirstMessage() {
        if (!messages.isEmpty()) {
            messages.remove(0);
        }
    }

    public List<Message> getRecentMessages(int count) {
        int start = Math.max(0, messages.size() - count);
        return Collections.unmodifiableList(messages.subList(start, messages.size()));
    }

    public List<SimpleMessage> toSimpleMessages() {
        List<SimpleMessage> result = new ArrayList<>();
        for (Message msg : messages) {
            result.add(new SimpleMessage(msg.getRole(), msg.getContent()));
        }
        return result;
    }



    public static class Message {
        private final String role;
        private final String content;
        private final Instant timestamp;
        private String toolCallId;
        private String toolName;
        private boolean error;
        private List<ToolCallEntry> toolCalls;

        public Message(String role, String content, Instant timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
        public Instant getTimestamp() { return timestamp; }
        public String getToolCallId() { return toolCallId; }
        public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public boolean isError() { return error; }
        public void setError(boolean error) { this.error = error; }
        public List<ToolCallEntry> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<ToolCallEntry> toolCalls) { this.toolCalls = toolCalls; }
    }

    public static class ToolCallEntry {
        private final String id;
        private final String name;
        private final String arguments;

        public ToolCallEntry(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getArguments() { return arguments; }
    }

    public static class SimpleMessage {
        private final String role;
        private final String content;

        public SimpleMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}
