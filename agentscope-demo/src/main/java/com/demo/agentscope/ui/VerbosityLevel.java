package com.demo.agentscope.ui;

/**
 * 界面信息详细程度级别。
 * <p>
 * 控制用户界面展示的信息量，从最简洁到最详细。
 * </p>
 */
public enum VerbosityLevel {

    /** 极简模式：仅显示最终结果 */
    MINIMAL("极简", "仅显示最终结果"),

    /** 标准模式：显示规划过程和关键里程碑（默认） */
    STANDARD("标准", "显示规划过程和关键里程碑"),

    /** 详细模式：显示所有执行细节和工具调用 */
    VERBOSE("详细", "显示所有执行细节和工具调用"),

    /** 调试模式：显示完整的内部状态和调试信息 */
    DEBUG("调试", "显示完整的内部状态和调试信息");

    private final String displayName;
    private final String description;

    VerbosityLevel(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 解析当前详细程度级别。
     * <p>
     * 优先级：系统属性 {@code verbosity.level}（REPL 运行时覆盖）&gt; 环境变量 {@code VERBOSITY} &gt; 默认 STANDARD。
     *
     * @return 详细程度级别
     */
    public static VerbosityLevel fromEnv() {
        String prop = System.getProperty("verbosity.level");
        if (prop != null && !prop.isBlank()) {
            try {
                return VerbosityLevel.valueOf(prop.toUpperCase());
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        String env = System.getenv("VERBOSITY");
        if (env == null || env.isBlank()) {
            return STANDARD;
        }
        try {
            return VerbosityLevel.valueOf(env.toUpperCase());
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }

    /**
     * 获取下一个级别（循环）。
     */
    public VerbosityLevel next() {
        VerbosityLevel[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
