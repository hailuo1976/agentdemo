package com.demo.agentscope.permission;

import java.util.Objects;

/**
 * 权限规则。
 * <p>
 * 表示一条显式的权限规则，用于匹配特定工具名称并给出决策。
 * 当工具名称匹配时，引擎将直接返回该规则的决策结果（含 rule.reason）。
 * bypassImmune 标记表示该规则不会被 BYPASS 模式覆盖，
 * 即使在旁路模式下依然生效（适用于不可绕过的安全规则）。
 * </p>
 */
public class PermissionRule {

    /** 工具名称（支持精确匹配） */
    private final String toolName;

    /** 权限决策（DENY/ASK 时携带 reason；ALLOW 时为单例） */
    private final PermissionDecision action;

    /** 是否免疫 BYPASS 模式（即使旁路模式下该规则依然生效） */
    private final boolean bypassImmune;

    /**
     * 等价于 {@code PermissionRule(toolName, action, reason, false)}。
     */
    public PermissionRule(String toolName, PermissionDecision action, String reason) {
        this(toolName, action, reason, false);
    }

    /**
     * 当 {@code action} 本身已携带 reason（由 {@link PermissionDecision#deny} /
     * {@link PermissionDecision#ask} 构造）时使用。
     */
    public PermissionRule(String toolName, PermissionDecision action, boolean bypassImmune) {
        this(toolName, action, null, bypassImmune);
    }

    public PermissionRule(String toolName, PermissionDecision action, String reason, boolean bypassImmune) {
        this.toolName = Objects.requireNonNull(toolName, "工具名称不能为null");
        Objects.requireNonNull(action, "权限决策不能为null");
        // 若 action 没有 reason 而 rule 提供了 reason，则把 reason 注入到决策中
        // 这样调用方只需读 decision.getReason() 即可，无需再读 rule.getReason()
        if (action.getReason() == null && reason != null && !reason.isEmpty()) {
            if (action.isDenied()) {
                this.action = PermissionDecision.deny(reason);
            } else if (action.isAsk()) {
                this.action = PermissionDecision.ask(reason);
            } else {
                this.action = action;
            }
        } else {
            this.action = action;
        }
        this.bypassImmune = bypassImmune;
    }

    // ==================== Getter ====================

    public String getToolName() {
        return toolName;
    }

    public PermissionDecision getAction() {
        return action;
    }

    public boolean isBypassImmune() {
        return bypassImmune;
    }

    @Override
    public String toString() {
        return "PermissionRule{toolName='" + toolName + "', action=" + action +
                ", bypassImmune=" + bypassImmune + "}";
    }
}
