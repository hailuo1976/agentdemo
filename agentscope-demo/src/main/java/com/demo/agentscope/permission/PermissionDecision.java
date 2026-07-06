package com.demo.agentscope.permission;

import java.util.Objects;

/**
 * 权限决策。
 * <p>
 * 表示权限引擎对一次工具调用的判定结果，共有三种状态：
 * <ul>
 *   <li>ALLOW - 允许执行</li>
 *   <li>DENY - 拒绝执行</li>
 *   <li>ASK - 需要人工确认</li>
 * </ul>
 * </p>
 * <p>
 * 自 2026-07 改造：由 enum 升级为携带 {@code reason} 的不可变类，使权限引擎能够
 * 把"为何被拒/为何需人工确认"的具体原因（命中规则、危险模式、危险路径等）透传到
 * 调用方，最终写入 tool_result 让模型感知并据此修正参数。
 * </p>
 * <p>
 * 向后兼容：{@link #ALLOW} 单例常量保留，{@link #isAllowed()} / {@link #isDenied()} /
 * {@link #isAsk()} 语义与原 enum 一致，{@code == ALLOW} 比较仍可用；
 * DENY / ASK 必须经 {@link #deny(String)} / {@link #ask(String)} 工厂方法构造。
 * </p>
 */
public final class PermissionDecision {

    /** 决策类型 */
    public enum Type {
        /** 允许执行 */
        ALLOW,
        /** 拒绝执行 */
        DENY,
        /** 需要人工确认 */
        ASK
    }

    /** ALLOW 单例（reason 永远为 null） */
    public static final PermissionDecision ALLOW = new PermissionDecision(Type.ALLOW, null);

    private final Type type;
    private final String reason;

    private PermissionDecision(Type type, String reason) {
        this.type = Objects.requireNonNull(type, "type 不能为 null");
        this.reason = reason;
    }

    /**
     * 构造 DENY 决策。
     *
     * @param reason 拒绝原因（命中规则、危险模式等），用于告知模型如何修正
     * @return DENY 决策实例
     */
    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(Type.DENY, reason);
    }

    /**
     * 构造 ASK 决策。
     *
     * @param reason 需要人工确认的原因
     * @return ASK 决策实例
     */
    public static PermissionDecision ask(String reason) {
        return new PermissionDecision(Type.ASK, reason);
    }

    public boolean isAllowed() {
        return type == Type.ALLOW;
    }

    public boolean isDenied() {
        return type == Type.DENY;
    }

    public boolean isAsk() {
        return type == Type.ASK;
    }

    public Type getType() {
        return type;
    }

    /**
     * 获取决策原因（DENY/ASK 时由引擎填入具体说明；ALLOW 返回 null）。
     *
     * @return 原因文本，可能为 null
     */
    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PermissionDecision that)) return false;
        return type == that.type && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, reason);
    }

    @Override
    public String toString() {
        return reason != null ? type + "(" + reason + ")" : type.name();
    }
}
