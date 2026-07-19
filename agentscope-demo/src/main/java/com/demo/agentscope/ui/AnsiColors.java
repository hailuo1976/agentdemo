package com.demo.agentscope.ui;

/**
 * 共享 ANSI 转义序列常量。
 * <p>
 * 供 {@link ConsoleUI}、Markdown 渲染器、交互式工具等所有需要着色的组件复用。
 * 保持常量名简短、值集中维护，避免散落的 {@code "\u001B[...m"} 字面量。
 * </p>
 */
public final class AnsiColors {

    public static final String RESET = "\u001B[0m";
    public static final String GREEN = "\u001B[32m";
    public static final String BLUE = "\u001B[34m";
    public static final String YELLOW = "\u001B[33m";
    public static final String RED = "\u001B[31m";
    public static final String CYAN = "\u001B[36m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";

    // 扩展样式 —— 新增组件可用
    public static final String UNDERLINE = "\u001B[4m";
    public static final String REVERSE = "\u001B[7m";
    public static final String BLACK = "\u001B[30m";
    public static final String WHITE = "\u001B[37m";
    public static final String BG_BLACK = "\u001B[40m";
    public static final String BG_WHITE = "\u001B[47m";

    private AnsiColors() {
        // 常量类禁止实例化
    }
}
