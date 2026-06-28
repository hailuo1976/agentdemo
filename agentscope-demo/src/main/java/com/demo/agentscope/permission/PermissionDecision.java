package com.demo.agentscope.permission;

/**
 * 权限决策枚举。
 * <p>
 * 表示权限引擎对一次工具调用的判定结果，共有三种状态：
 * <ul>
 *   <li>ALLOW - 允许执行</li>
 *   <li>DENY - 拒绝执行</li>
 *   <li>ASK - 需要人工确认</li>
 * </ul>
 * </p>
 */
public enum PermissionDecision {

    /** 允许执行 */
    ALLOW,

    /** 拒绝执行 */
    DENY,

    /** 需要人工确认 */
    ASK
}
