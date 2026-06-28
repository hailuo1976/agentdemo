package com.demo.agentscope.permission;

/**
 * 权限模式枚举。
 * <p>
 * 定义权限引擎的全局运行模式，不同模式对工具调用有不同的默认策略：
 * <ul>
 *   <li>EXPLORE - 探索模式，只读访问，拒绝所有写入操作</li>
 *   <li>DONT_ASK - 静默拒绝模式，将所有 ASK 决策自动降级为 DENY</li>
 *   <li>BYPASS - 旁路模式，跳过所有权限检查</li>
 * </ul>
 * </p>
 */
public enum PermissionMode {

    /** 探索模式：只读访问，拒绝写入操作 */
    EXPLORE,

    /** 静默拒绝模式：自动将 ASK 决策降级为 DENY */
    DONT_ASK,

    /** 旁路模式：跳过所有权限检查 */
    BYPASS
}
