package com.demo.agentscope.permission;

/**
 * 权限拒绝异常。
 * <p>
 * 当权限引擎判定工具调用被拒绝（DENY）时抛出此异常，
 * 用于中断工具执行流程并通知上层调用方。
 * 携带被拒绝的工具名称和拒绝原因信息。
 * </p>
 */
public class PermissionDeniedException extends RuntimeException {

    /** 被拒绝的工具名称 */
    private final String toolName;

    /** 拒绝原因 */
    private final String reason;

    public PermissionDeniedException(String toolName, String reason) {
        super(String.format("权限拒绝: toolName=%s, reason=%s", toolName, reason));
        this.toolName = toolName;
        this.reason = reason;
    }

    public String getToolName() {
        return toolName;
    }

    public String getReason() {
        return reason;
    }
}
