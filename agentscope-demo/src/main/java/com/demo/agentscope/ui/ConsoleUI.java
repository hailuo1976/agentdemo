package com.demo.agentscope.ui;

import com.demo.agentscope.event.AgentEvent;
import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.event.EventType;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * 控制台 UI 工具类。
 * <p>
 * 提供 AgentScope 2.0 演示应用的终端交互界面，支持 ANSI 颜色输出、
 * 横幅打印、用户提示、智能体响应展示、事件流打印等功能。
 * 所有方法均为静态方法，无需实例化。
 * </p>
 */
public class ConsoleUI {

    // ==================== 输入读取器（UTF-8 编码） ====================

    /**
     * JLine3 LineReader：接管终端 raw mode，按字符（码点）处理退格，
     * 绕开 macOS 终端 cooked mode 对 UTF-8 多字节字符退格回显的 bug。
     * <p>
     * 相比 BufferedReader，JLine3 自己负责行编辑和回显，
     * 不依赖终端的 cooked mode，从而彻底解决中英文退格不一致的问题。
     * </p>
     * <p>
     * 容错策略：类初始化失败（JLine 版本不匹配、终端 JNI 加载失败等）
     * 不会导致整个应用启动失败，而是退回到 BufferedReader。退格键回显在
     * 非 raw mode 终端上依赖系统 cooked mode，但应用核心功能（对话）不受影响。
     * </p>
     */
    private static final LineReader STDIN_READER;
    private static final Terminal STDIN_TERMINAL;
    private static final BufferedReader FALLBACK_READER;
    private static final boolean JLINE_AVAILABLE;

    static {
        Terminal terminal = null;
        LineReader reader = null;
        boolean jlineOk = false;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            reader = LineReaderBuilder.builder().terminal(terminal).build();
            // 预触发 BellType 类加载 —— JLine 3.25.1 的 beep() 方法在运行期
            // 第一次触发时才加载 BellType enum，shade 后偶发 NoClassDefFoundError。
            // 这里主动触碰一次，让 ClassLoader 在应用启动早期就解析依赖。
            // 注意：这里不调用 beep()，避免出现意外响声。
            try {
                Class.forName("org.jline.reader.impl.LineReaderImpl$BellType");
            } catch (ClassNotFoundException ignored) {
                // BellType 缺失：JLine 部分功能受损但 readLine 通常仍可用
            }
            jlineOk = true;
        } catch (Throwable e) {
            // Throwable 而非 Exception —— JLine 的 link 错误会抛 UnsatisfiedLinkError
            System.err.println("[ConsoleUI] JLine3 初始化失败，回退到 BufferedReader: " + e.getMessage());
        }
        STDIN_READER = reader;
        STDIN_TERMINAL = terminal;
        FALLBACK_READER = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        JLINE_AVAILABLE = jlineOk;
    }

    // ==================== ANSI 颜色常量（委托至 AnsiColors） ====================

    private static final String RESET = AnsiColors.RESET;
    private static final String GREEN = AnsiColors.GREEN;
    private static final String BLUE = AnsiColors.BLUE;
    private static final String YELLOW = AnsiColors.YELLOW;
    private static final String RED = AnsiColors.RED;
    private static final String CYAN = AnsiColors.CYAN;
    private static final String MAGENTA = AnsiColors.MAGENTA;
    private static final String BOLD = AnsiColors.BOLD;
    private static final String DIM = AnsiColors.DIM;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ConsoleUI() {
        // 工具类禁止实例化
    }

    // ==================== 横幅与提示 ====================

    /**
     * 打印 AgentScope 2.0 风格横幅及版本信息。
     */
    public static void printBanner() {
        System.out.println();
        System.out.println(MAGENTA + BOLD + "  ╔══════════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║                                                            ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║            🤖  AgentScope 2.0  🤖                          ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║                                                            ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║      Unified Agent • Middleware Chain • MCP Protocol        ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║              Permission Engine • Workspace                  ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║                                                            ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ║                    v2.0.0-demo                             ║" + RESET);
        System.out.println(MAGENTA + BOLD + "  ╚══════════════════════════════════════════════════════════════╝" + RESET);
        System.out.println();
    }

    /**
     * 显示用户输入提示符，读取用户输入。
     * <p>
     * 通过 JLine3 LineReader 接管终端输入，按字符（码点）处理退格，
     * 彻底解决中文退格时屏幕回显与实际缓冲不一致的问题。
     * </p>
     * <p>
     * 异常容错：JLine 的 readLine 在运行时可能因为终端状态错乱、信号、
     * 或 BellType 等内部类延迟加载失败抛出 NoClassDefFoundError /
     * IllegalStateException。此处捕获后回退到 BufferedReader，
     * 保证 REPL 主循环不会因为输入层异常整体崩溃。
     * </p>
     *
     * @return 用户输入的文本，若输入流结束则返回 null
     */
    public static String promptUser() {
        // 优先用 JLine
        if (JLINE_AVAILABLE && STDIN_READER != null) {
            try {
                return STDIN_READER.readLine(BLUE + "You ▶ " + RESET);
            } catch (UserInterruptException e) {
                // Ctrl+C：视为退出信号，走 REPL 的正常关闭路径
                return null;
            } catch (EndOfFileException e) {
                // Ctrl+D：输入流结束
                return null;
            } catch (Throwable e) {
                // JLine 运行期异常（如 BellType NoClassDefFoundError、
                // terminal disposed、信号打断等）：不向上抛，本次回退到
                // BufferedReader，下次循环再尝试 JLine。
                System.err.println("[ConsoleUI] JLine readLine 异常，本次回退到 BufferedReader: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        // 回退路径：BufferedReader
        System.out.print(BLUE + "You ▶ " + RESET);
        System.out.flush();
        try {
            String line = FALLBACK_READER.readLine();
            return line; // readLine() 在 EOF 时返回 null，自然映射
        } catch (IOException e) {
            System.err.println("[ConsoleUI] BufferedReader 读取失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== 公共输入与终端辅助（供工具复用） ====================

    /**
     * 带提示符读取一行用户输入，复用 REPL 的 JLine3 + UTF-8 回退路径。
     * <p>
     * 与 {@link #promptUser()} 相比：本方法允许传入任意提示符，供交互式工具
     * （如 {@code ask_user}）在 tool 执行期间同步阻塞读取用户输入。
     * </p>
     * <p>
     * 行为约定：
     * <ul>
     *   <li>读取成功 → 返回 trim 后的字符串（可能为空串）</li>
     *   <li>EOF（Ctrl+D）或 Ctrl+C → 返回 {@code null}，由调用方决定如何处理</li>
     *   <li>JLine 运行期异常 → 本次回退到 BufferedReader，仍失败则返回 {@code null}</li>
     * </ul>
     * </p>
     *
     * @param prompt 展示给用户的提示符（不含结尾空格；方法内不自动补）
     * @return 用户输入的一行；EOF 或读取失败时返回 {@code null}
     */
    public static String readLine(String prompt) {
        if (JLINE_AVAILABLE && STDIN_READER != null) {
            try {
                String raw = STDIN_READER.readLine(prompt);
                return raw == null ? null : raw.trim();
            } catch (UserInterruptException e) {
                return null;
            } catch (EndOfFileException e) {
                return null;
            } catch (Throwable e) {
                System.err.println("[ConsoleUI] JLine readLine 异常，本次回退到 BufferedReader: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        System.out.print(prompt);
        System.out.flush();
        try {
            String line = FALLBACK_READER.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            System.err.println("[ConsoleUI] BufferedReader 读取失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 返回当前终端的列宽，用于 Markdown 渲染、表格排版等。
     * <p>
     * 通过 JLine 的 {@link Terminal#getWidth()} 获取；若 JLine 不可用或返回非正值，
     * 回退到环境变量 {@code COLUMNS}，再不行就用 80。
     * </p>
     *
     * @return 终端列宽，始终为正整数
     */
    public static int getTerminalWidth() {
        if (STDIN_TERMINAL != null) {
            int w = STDIN_TERMINAL.getWidth();
            if (w > 0) return w;
        }
        String cols = System.getenv("COLUMNS");
        if (cols != null && !cols.isBlank()) {
            try {
                int w = Integer.parseInt(cols.trim());
                if (w > 0) return w;
            } catch (NumberFormatException ignored) {
                // 落到默认值
            }
        }
        return 80;
    }

    // ==================== 智能体响应 ====================

    /**
     * 打印智能体响应，展示文本块和工具调用（含计时信息）。
     *
     * @param response 智能体回复消息
     */
    public static void printAgentResponse(Msg response) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        System.out.println();
        System.out.println(GREEN + "  Agent ◀ [" + timestamp + "]" + RESET);
        System.out.println(GREEN + "  ┌──────────────────────────────────────────────────" + RESET);

        // 遍历内容块，分别展示文本和工具调用
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ContentBlock.TextBlock textBlock) {
                // 文本内容逐行展示
                for (String line : textBlock.getText().split("\n")) {
                    System.out.println(GREEN + "  │ " + RESET + line);
                }
            } else if (block instanceof ContentBlock.ThinkingBlock thinkingBlock) {
                // 思考过程用 DIM 样式展示
                System.out.println(DIM + "  │ [思考] " + thinkingBlock.getText() + RESET);
            } else if (block instanceof ContentBlock.ToolCallBlock toolCallBlock) {
                // 工具调用用 CYAN 高亮
                System.out.println(CYAN + "  │ 🔧 调用工具: " + toolCallBlock.getName() + RESET);
                if (toolCallBlock.getArguments() != null && !toolCallBlock.getArguments().isBlank()) {
                    System.out.println(CYAN + "  │    参数: " + toolCallBlock.getArguments() + RESET);
                }
            } else if (block instanceof ContentBlock.ToolResultBlock resultBlock) {
                // 工具结果
                String resultLabel = resultBlock.isError() ? "❌ 错误" : "✅ 结果";
                String content = resultBlock.getContent();
                if (content.length() > 120) {
                    content = content.substring(0, 120) + "...";
                }
                System.out.println(CYAN + "  │    " + resultLabel + ": " + content + RESET);
            }
        }

        // Token 用量展示
        Msg.TokenUsage usage = response.getUsage();
        if (usage != null && usage.getTotalTokens() > 0) {
            System.out.println(DIM + "  │ [tokens: prompt=" + usage.getPromptTokens()
                    + ", completion=" + usage.getCompletionTokens()
                    + ", total=" + usage.getTotalTokens() + "]" + RESET);
        }

        System.out.println(GREEN + "  └──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    // ==================== 事件流 ====================

    /**
     * 打印事件流中的所有事件，带图标区分类型。
     *
     * @param stream 事件流
     */
    public static void printEventStream(EventStream stream) {
        List<AgentEvent> events = stream.getEvents();
        System.out.println();
        System.out.println(BOLD + "  📡 Event Stream (" + events.size() + " events)" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);

        for (AgentEvent event : events) {
            String icon = eventIcon(event.getType());
            String summary = eventSummary(event);
            String time = event.getTimestamp().toString().substring(11, 19);
            System.out.println("  " + icon + " [" + time + "] " + summary);
        }

        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    /**
     * 根据事件类型返回图标。
     */
    private static String eventIcon(EventType type) {
        return switch (type) {
            case REPLY_START -> GREEN + "🚀" + RESET;
            case REPLY_END -> GREEN + "🏁" + RESET;
            case MODEL_CALL_START -> BLUE + "📡" + RESET;
            case MODEL_CALL_END -> BLUE + "📡" + RESET;
            case TEXT_BLOCK -> GREEN + "💬" + RESET;
            case TEXT_DELTA -> GREEN + "💬" + RESET;
            case THINKING_BLOCK -> YELLOW + "🧠" + RESET;
            case THINKING_DELTA -> YELLOW + "🧠" + RESET;
            case TOOL_CALL -> CYAN + "🔧" + RESET;
            case TOOL_RESULT -> CYAN + "⚙️" + RESET;
            case REQUIRE_USER_CONFIRM -> YELLOW + "❓" + RESET;
            case REQUIRE_EXTERNAL_EXECUTION -> MAGENTA + "⚡" + RESET;
            case ERROR -> RED + "❌" + RESET;
            case CONTEXT_COMPRESSED -> DIM + "📦" + RESET;
            case PERMISSION_CHECK -> YELLOW + "🔒" + RESET;
            case PERMISSION_ASK -> YELLOW + "🔐" + RESET;
            case WORKSPACE_OPERATION -> BLUE + "📁" + RESET;
            case AGENT_TEAM_CREATE -> MAGENTA + "👥" + RESET;
            case AGENT_CREATE -> MAGENTA + "🤖" + RESET;
            case AGENT_MESSAGE -> MAGENTA + "📨" + RESET;
            case TEAM_DISSOLVE -> RED + "💔" + RESET;
            case REPLY_BUDGET_EXCEEDED -> RED + "💰" + RESET;
            case OUTPUT_TRUNCATED -> RED + "✂️" + RESET;
        };
    }

    /**
     * 生成事件的简要描述。
     */
    private static String eventSummary(AgentEvent event) {
        return switch (event.getType()) {
            case REPLY_START -> "回复开始";
            case REPLY_END -> "回复结束";
            case MODEL_CALL_START -> "模型调用开始: " + event.getData("modelName", String.class);
            case MODEL_CALL_END -> {
                Integer pt = event.getData("promptTokens", Integer.class);
                Integer ct = event.getData("completionTokens", Integer.class);
                yield "模型调用完成: prompt=" + (pt != null ? pt : 0) + ", completion=" + (ct != null ? ct : 0);
            }
            case TEXT_BLOCK -> {
                String content = event.getData("content", String.class);
                yield "文本: " + (content != null && content.length() > 60 ? content.substring(0, 60) + "..." : content);
            }
            case TEXT_DELTA -> {
                String delta = event.getData("delta", String.class);
                yield "文本增量: " + (delta != null && delta.length() > 60 ? delta.substring(0, 60) + "..." : delta);
            }
            case THINKING_BLOCK -> {
                String content = event.getData("content", String.class);
                yield "思考: " + (content != null && content.length() > 60 ? content.substring(0, 60) + "..." : content);
            }
            case THINKING_DELTA -> {
                String delta = event.getData("delta", String.class);
                yield "思考增量: " + (delta != null && delta.length() > 60 ? delta.substring(0, 60) + "..." : delta);
            }
            case TOOL_CALL -> "工具调用: " + event.getData("toolName", String.class);
            case TOOL_RESULT -> "工具结果: " + event.getData("toolName", String.class);
            case REQUIRE_USER_CONFIRM -> "需要确认: " + event.getData("message", String.class);
            case REQUIRE_EXTERNAL_EXECUTION -> "外部执行: " + event.getData("command", String.class);
            case ERROR -> "错误: " + event.getData("message", String.class);
            case CONTEXT_COMPRESSED -> {
                Object orig = event.getData("originalSize");
                Object newSize = event.getData("newSize");
                yield "上下文压缩: " + orig + " → " + newSize;
            }
            case PERMISSION_CHECK -> "权限检查: " + event.getData("permission", String.class);
            case PERMISSION_ASK -> "权限询问: " + event.getData("permission", String.class);
            case WORKSPACE_OPERATION -> "工作空间: " + event.getData("operation", String.class);
            case AGENT_TEAM_CREATE -> "创建团队: " + event.getData("teamId", String.class);
            case AGENT_CREATE -> "创建智能体: " + event.getData("agentType", String.class);
            case AGENT_MESSAGE -> "智能体消息: → " + event.getData("targetAgentId", String.class);
            case TEAM_DISSOLVE -> "解散团队: " + event.getData("teamId", String.class);
            case REPLY_BUDGET_EXCEEDED -> {
                Object budget = event.getData("budget");
                Object actual = event.getData("actual");
                yield "预算超限: budget=" + budget + ", actual=" + actual;
            }
            case OUTPUT_TRUNCATED -> {
                Object ct = event.getData("completionTokens");
                Object mt = event.getData("maxTokens");
                yield "输出截断: completion=" + ct + ", maxTokens=" + mt;
            }
        };
    }

    // ==================== 彩色信息输出 ====================

    /**
     * 打印信息消息（青色）。
     */
    public static void printInfo(String message) {
        System.out.println(CYAN + "  ℹ " + RESET + message);
    }

    /**
     * 打印成功消息（绿色）。
     */
    public static void printSuccess(String message) {
        System.out.println(GREEN + "  ✓ " + RESET + message);
    }

    /**
     * 打印错误消息（红色）。
     */
    public static void printError(String message) {
        System.out.println(RED + "  ✗ " + RESET + message);
    }

    /**
     * 打印警告消息（黄色）。
     */
    public static void printWarning(String message) {
        System.out.println(YELLOW + "  ⚠ " + RESET + message);
    }

    // ==================== 分隔线 ====================

    /**
     * 打印虚线分隔线。
     */
    public static void printSeparator() {
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
    }

    // ==================== 历史记录 ====================

    /**
     * 打印对话历史，用角色图标区分不同角色。
     *
     * @param messages 消息历史列表
     */
    public static void printHistory(List<Msg> messages) {
        System.out.println();
        System.out.println(BOLD + "  📋 对话历史 (" + messages.size() + " 条消息)" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);

        for (Msg msg : messages) {
            String icon = switch (msg.getRole()) {
                case "system" -> YELLOW + "⚙️" + RESET;
                case "user" -> BLUE + "👤" + RESET;
                case "assistant" -> GREEN + "🤖" + RESET;
                case "tool" -> CYAN + "🔧" + RESET;
                default -> "  ";
            };

            String text = msg.getTextContent();
            if (text.isEmpty()) {
                // 尝试从非文本块中提取摘要
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ContentBlock.ToolCallBlock tc) {
                        text = "[工具调用: " + tc.getName() + "]";
                        break;
                    } else if (block instanceof ContentBlock.ToolResultBlock tr) {
                        text = "[工具结果" + (tr.isError() ? "(错误)" : "") + "]";
                        break;
                    }
                }
            }

            String display = text.length() > 80 ? text.substring(0, 80) + "..." : text;
            String time = msg.getTimestamp().toString().substring(11, 19);
            System.out.println("  " + icon + " [" + time + "] " + display);

            // 展示工具调用详情
            for (ContentBlock.ToolCallBlock tc : msg.getToolCalls()) {
                System.out.println("      " + CYAN + "↳ 🔧 " + tc.getName() + RESET);
            }
        }

        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    // ==================== 智能体状态 ====================

    /**
     * 打印智能体状态信息。
     *
     * @param status 状态映射
     */
    public static void printAgentStatus(Map<String, Object> status) {
        System.out.println();
        System.out.println(BOLD + "  📊 智能体状态" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        for (Map.Entry<String, Object> entry : status.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            System.out.println("  📌 " + key + ": " + value);
        }
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    // ==================== 权限询问 ====================

    /**
     * 打印权限询问提示。
     *
     * @param toolName 工具名称
     * @param reason   询问原因
     */
    public static void printPermissionAsk(String toolName, String reason) {
        System.out.println();
        System.out.println(YELLOW + BOLD + "  🔐 权限询问" + RESET);
        System.out.println(YELLOW + "  ┌──────────────────────────────────────────────────" + RESET);
        System.out.println(YELLOW + "  │ 🔧 工具: " + toolName + RESET);
        System.out.println(YELLOW + "  │ 📝 原因: " + reason + RESET);
        System.out.println(YELLOW + "  │" + RESET);
        System.out.println(YELLOW + "  │ 演示模式下自动拒绝（需人工确认）" + RESET);
        System.out.println(YELLOW + "  └──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    // ==================== 帮助信息 ====================

    /**
     * 显示可用命令帮助。
     */
    public static void printHelp() {
        System.out.println();
        System.out.println(BOLD + "  📖 可用命令" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println("  " + CYAN + "exit/quit" + RESET + "   - 退出应用");
        System.out.println("  " + CYAN + "history" + RESET + "    - 查看对话历史");
        System.out.println("  " + CYAN + "/sessions" + RESET + "  - 列出已保存的会话");
        System.out.println("  " + CYAN + "/resume <id>" + RESET + " - 恢复指定会话（支持短ID前缀）");
        System.out.println("  " + CYAN + "status" + RESET + "     - 查看智能体状态");
        System.out.println("  " + CYAN + "clear" + RESET + "      - 重置智能体（清空上下文）");
        System.out.println("  " + CYAN + "team create" + RESET + " - 创建智能体团队（含领导者）");
        System.out.println("  " + CYAN + "team status" + RESET + " - 查看团队状态");
        System.out.println("  " + CYAN + "team dissolve" + RESET + " - 解散团队");
        System.out.println("  " + CYAN + "tools" + RESET + "      - 列出可用的 MCP 工具");
        System.out.println("  " + CYAN + "events" + RESET + "     - 切换事件展示模式");
        System.out.println("  " + CYAN + "permission" + RESET + " - 显示权限规则");
        System.out.println("  " + CYAN + "verbosity" + RESET + "  - 调整界面信息详细程度");
        System.out.println("  " + CYAN + "/stock on|off" + RESET + " - 开启/关闭股票分析工具");
        System.out.println("  " + CYAN + "/config" + RESET + "      - 查看/设置运行时限制（/config set maxIterations=30）");
        System.out.println("  " + CYAN + "help" + RESET + "       - 显示此帮助信息");
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }

    // ==================== 团队状态 ====================

    /**
     * 打印团队状态信息。
     *
     * @param status 团队状态映射
     */
    public static void printTeamStatus(Map<String, Object> status) {
        System.out.println();
        System.out.println(BOLD + "  👥 团队状态" + RESET);
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        for (Map.Entry<String, Object> entry : status.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("workers".equals(key) && value instanceof List<?> workers) {
                System.out.println("  👤 " + key + ": " + workers.size() + " 个工作者");
                for (Object w : workers) {
                    if (w instanceof Map<?, ?> wMap) {
                        System.out.println("      - " + wMap.get("name") + " (id=" + wMap.get("id") + ")");
                    }
                }
            } else {
                System.out.println("  📌 " + key + ": " + value);
            }
        }
        System.out.println(DIM + "  ──────────────────────────────────────────────────" + RESET);
        System.out.println();
    }
}
