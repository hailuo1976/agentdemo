package com.demo.agentscope.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 权限引擎。
 * <p>
 * AgentScope 2.0 权限系统的核心组件，采用三引擎串联架构依次检查：
 * <ol>
 *   <li>规则引擎 - 匹配显式权限规则列表，命中则返回规则决策</li>
 *   <li>模式引擎 - 根据全局模式（EXPLORE/DONT_ASK/BYPASS）进行决策</li>
 *   <li>内置检查引擎 - 检测危险操作模式（如 rm -rf、/etc/ 路径等）</li>
 * </ol>
 * 三个引擎按顺序执行，任一引擎返回非 ALLOW 决策即终止链路并返回该决策。
 * </p>
 */
public class PermissionEngine {

    private static final Logger log = LoggerFactory.getLogger(PermissionEngine.class);

    /** 写入类工具名称前缀集合 */
    private static final Set<String> WRITE_TOOL_PREFIXES = Set.of(
            "write", "delete", "remove", "create", "update", "execute", "run", "bash", "shell"
    );

    /** 危险命令模式 */
    private static final List<String> DANGEROUS_COMMAND_PATTERNS = List.of(
            "rm -rf", "rm -r", "rmdir", "mkfs", "dd if=", ":(){ :|:& };:",
            "chmod 777", "chown root", "> /dev/sd", "format "
    );

    /** 危险路径模式 */
    private static final List<String> DANGEROUS_PATH_PATTERNS = List.of(
            "/etc/", "/root/", "/var/log/", "/usr/bin/", "/usr/sbin/",
            "/sbin/", "/boot/", "/proc/", "/sys/", "C:\\Windows\\", "C:\\Program Files\\"
    );

    /** 权限规则列表 */
    private final List<PermissionRule> rules;

    /** 全局权限模式 */
    private PermissionMode mode;

    /** 是否启用内置检查 */
    private boolean builtInChecksEnabled;

    public PermissionEngine() {
        this.rules = new CopyOnWriteArrayList<>();
        this.mode = PermissionMode.DONT_ASK;
        this.builtInChecksEnabled = true;
    }

    public PermissionEngine(PermissionMode mode, boolean builtInChecksEnabled) {
        this.rules = new CopyOnWriteArrayList<>();
        this.mode = mode != null ? mode : PermissionMode.DONT_ASK;
        this.builtInChecksEnabled = builtInChecksEnabled;
    }

    // ==================== 核心：权限检查 ====================

    /**
     * 对工具调用执行权限检查。
     * <p>
     * 按规则引擎 → 模式引擎 → 内置检查引擎的顺序依次执行，
     * 任一引擎返回非 ALLOW 决策即终止并返回。
     * </p>
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 权限决策结果
     */
    public PermissionDecision check(String toolName, Map<String, Object> args) {
        Objects.requireNonNull(toolName, "工具名称不能为null");

        // 引擎1：规则引擎
        PermissionDecision ruleDecision = checkRules(toolName);
        if (ruleDecision != null) {
            log.debug("规则引擎命中: toolName={}, decision={}", toolName, ruleDecision);
            return ruleDecision;
        }

        // 引擎2：模式引擎
        PermissionDecision modeDecision = checkMode(toolName, args);
        if (modeDecision != PermissionDecision.ALLOW) {
            log.debug("模式引擎拦截: toolName={}, mode={}, decision={}", toolName, mode, modeDecision);
            return modeDecision;
        }

        // 引擎3：内置检查引擎
        if (builtInChecksEnabled) {
            PermissionDecision builtInDecision = checkBuiltIn(toolName, args);
            if (builtInDecision != PermissionDecision.ALLOW) {
                log.debug("内置检查引擎拦截: toolName={}, decision={}", toolName, builtInDecision);
                return builtInDecision;
            }
        }

        // 所有引擎均通过，默认允许
        log.debug("权限检查通过: toolName={}", toolName);
        return PermissionDecision.ALLOW;
    }

    // ==================== 引擎1：规则引擎 ====================

    /**
     * 规则引擎：匹配显式权限规则。
     * <p>
     * 遍历规则列表，找到第一个工具名称匹配的规则并返回其决策。
     * 如果规则标记为 bypassImmune，则在 BYPASS 模式下依然生效。
     * </p>
     */
    private PermissionDecision checkRules(String toolName) {
        for (PermissionRule rule : rules) {
            if (rule.getToolName().equals(toolName)) {
                // bypassImmune 规则在任何模式下都生效
                if (mode == PermissionMode.BYPASS && !rule.isBypassImmune()) {
                    continue;
                }
                return rule.getAction();
            }
        }
        return null;
    }

    // ==================== 引擎2：模式引擎 ====================

    /**
     * 模式引擎：根据全局权限模式进行决策。
     * <ul>
     *   <li>EXPLORE - 如果工具修改状态则拒绝</li>
     *   <li>DONT_ASK - 无特殊拦截（ASK 降级在最终决策时处理）</li>
     *   <li>BYPASS - 跳过所有非免疫规则，直接允许</li>
     * </ul>
     */
    private PermissionDecision checkMode(String toolName, Map<String, Object> args) {
        if (mode == PermissionMode.BYPASS) {
            // 旁路模式：直接允许（bypassImmune 规则已在规则引擎中处理）
            return PermissionDecision.ALLOW;
        }

        if (mode == PermissionMode.EXPLORE) {
            // 探索模式：只读访问，拒绝所有写入类工具
            if (isWriteTool(toolName)) {
                return PermissionDecision.DENY;
            }
        }

        // DONT_ASK 模式下不拦截 ALLOW，但在最终结果中将 ASK 降级为 DENY
        return PermissionDecision.ALLOW;
    }

    /**
     * 判断工具是否为写入类工具。
     * <p>
     * 通过工具名称前缀和常见写入工具名称进行判断。
     * </p>
     */
    private boolean isWriteTool(String toolName) {
        String lower = toolName.toLowerCase();
        for (String prefix : WRITE_TOOL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 引擎3：内置检查引擎 ====================

    /**
     * 内置检查引擎：检测危险操作模式。
     * <p>
     * 检查工具参数中是否包含危险的命令模式（如 rm -rf）
     * 或危险的文件路径（如 /etc/），发现则返回 ASK 或 DENY。
     * </p>
     */
    private PermissionDecision checkBuiltIn(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return PermissionDecision.ALLOW;
        }

        // 检查所有参数值中是否存在危险模式
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value == null) continue;

            String strValue = value.toString();

            // 检查危险命令
            for (String pattern : DANGEROUS_COMMAND_PATTERNS) {
                if (strValue.contains(pattern)) {
                    log.warn("内置检查发现危险命令模式: pattern={}, toolName={}, arg={}",
                            pattern, toolName, entry.getKey());
                    return PermissionDecision.DENY;
                }
            }

            // 检查危险路径
            for (String pattern : DANGEROUS_PATH_PATTERNS) {
                if (strValue.contains(pattern)) {
                    log.warn("内置检查发现危险路径模式: pattern={}, toolName={}, arg={}",
                            pattern, toolName, entry.getKey());
                    return PermissionDecision.ASK;
                }
            }
        }

        return PermissionDecision.ALLOW;
    }

    // ==================== 规则管理 ====================

    /**
     * 添加权限规则。
     *
     * @param rule 权限规则
     */
    public void addRule(PermissionRule rule) {
        if (rule != null) {
            rules.add(rule);
            log.debug("权限规则已添加: {}", rule);
        }
    }

    /**
     * 设置全局权限模式。
     *
     * @param mode 权限模式
     */
    public void setMode(PermissionMode mode) {
        this.mode = mode != null ? mode : PermissionMode.DONT_ASK;
        log.info("权限模式已切换: mode={}", this.mode);
    }

    /**
     * 移除指定工具名称的权限规则。
     *
     * @param toolName 工具名称
     */
    public void removeRule(String toolName) {
        boolean removed = rules.removeIf(rule -> rule.getToolName().equals(toolName));
        if (removed) {
            log.debug("权限规则已移除: toolName={}", toolName);
        }
    }

    // ==================== Getter ====================

    public PermissionMode getMode() {
        return mode;
    }

    public boolean isBuiltInChecksEnabled() {
        return builtInChecksEnabled;
    }

    public void setBuiltInChecksEnabled(boolean builtInChecksEnabled) {
        this.builtInChecksEnabled = builtInChecksEnabled;
    }

    public List<PermissionRule> getRules() {
        return Collections.unmodifiableList(rules);
    }
}
