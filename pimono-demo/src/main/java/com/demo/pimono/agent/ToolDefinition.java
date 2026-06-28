package com.demo.pimono.agent;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolDefinition {

    private final String name;
    private final String description;
    private final JsonNode inputSchema;
    private final String serverName;

    public ToolDefinition(String name, String description, JsonNode inputSchema, String serverName) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.serverName = serverName;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public JsonNode getInputSchema() { return inputSchema; }
    public String getServerName() { return serverName; }

    @Override
    public String toString() {
        return name + ": " + description + " [" + serverName + "]";
    }
}
