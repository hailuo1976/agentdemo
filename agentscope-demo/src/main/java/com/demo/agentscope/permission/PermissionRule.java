package com.demo.agentscope.permission;

import java.util.Objects;

/**
 * 权限规则。
 * <p>
 * 表示一条显式的权限规则，用于匹配特定工具名称并给出决策。
 * 当工具名称匹配时，引擎将直接返回该规则的决策结果。
 * bypassImmune 标记表示该规则不会被 BYPASS 模式覆盖，
 * 即使在旁路模式下依然生效（适用于不可绕过的安全规则）。
 * </p>
 */
public class PermissionRule {

    /** 工具名称（支持精确匹配） */
    private final String toolName;

    /** 权限决策 */
    private final PermissionDecision action;

    /** 决策原因说明 */
    private final String reason;

    /** 是否免疫 BYPASS 模式（即使旁路模式下该规则依然生效） */
    private final boolean bypassImmune;

    public PermissionRule(String toolName, PermissionDecision action, String reason) {
        this(toolName, action, reason, false);
    }

    public PermissionRule(String toolName, PermissionDecision action, String reason, boolean bypassImmune) {
        this.toolName = Objects.requireNonNull(toolName, "工具名称不能为null");
        this.action = Objects.requireNonNull(action, "权限决策不能为null");
        this.reason = reason != null ? reason : "";
        this.bypassImmune = bypassImmune;
    }

    // ==================== Getter ====================

    public String getToolName() {
        return toolName;
    }

    public PermissionDecision getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public boolean isBypassImmune() {
        return bypassImmune;
    }

    @Override
    public String toString() {
        return "PermissionRule{toolName='" + toolName + "', action=" + action +
                ", reason='" + reason + "', bypassImmune=" + bypassImmune + "}";
    }
}
