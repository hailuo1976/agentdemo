package com.demo.pimono.agent;

public class ToolResult {

    private final boolean success;
    private final String output;
    private final String errorMessage;

    private ToolResult(boolean success, String output, String errorMessage) {
        this.success = success;
        this.output = output;
        this.errorMessage = errorMessage;
    }

    public static ToolResult success(String output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult error(String errorMessage) {
        return new ToolResult(false, null, errorMessage);
    }

    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return success ? output : "ERROR: " + errorMessage;
    }
}
