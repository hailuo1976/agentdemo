package com.demo.agentscope.ui;

import com.demo.agentscope.agent.Agent;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.session.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /context} REPL 命令处理器。
 * <p>
 * 八个子命令：{@code view / edit / delete / save / load / trim / status / undo}。
 * 仅由用户在 REPL 中调用，LLM 无法通过 MCP 工具触发——保留对话上下文审计轨迹。
 * </p>
 * <p>
 * 语义约束（已锁定）：
 * <ul>
 *   <li><b>仅用户调用</b>：不新增 MCP 工具、不写权限规则。</li>
 *   <li><b>仅手动截断</b>：{@code trim} 显式触发，不自动截断（避免与 ContextCompressionMiddleware 冲突）。</li>
 *   <li><b>命令行内联参数</b>：长文本用 {@code \n} 字面量转义换行。</li>
 * </ul>
 * </p>
 * <p>
 * Undo 策略：每次 mutation（edit/delete/trim/load）前写单槽快照；
 * {@code undo} 读取快照恢复。连续 undo 恢复到同一快照。
 * </p>
 */
public class ContextCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ContextCommandHandler.class);

    /** 默认 trim 保留条数 */
    private static final int DEFAULT_TRIM_KEEP = 10;

    private ContextCommandHandler() {}

    /**
     * 处理 {@code /context} 输入。
     *
     * @param input       完整 REPL 输入行
     * @param agent       当前 Agent
     * @param workspaceDir workspace 根目录（用于定位 conversations/）
     * @return true 表示该输入已被 /context 消费；false 表示不是 /context 命令
     */
    public static boolean handle(String input, Agent agent, Path workspaceDir) {
        if (input == null) return false;
        String trimmed = input.trim();
        // 大小写不敏感地匹配命令头
        if (!trimmed.toLowerCase().startsWith("/context")) {
            return false;
        }
        // 必须是 /context 后紧跟空格/EOL 或紧接着下一个字符就是子命令分隔
        String rest = trimmed.substring("/context".length());
        // 在分发前缓存 workspaceDir，供内部子命令（edit/delete/trim）的快照写入使用
        currentWorkspaceDir = workspaceDir;
        if (rest.isEmpty()) {
            view(agent, new String[0]);
            return true;
        }
        if (rest.charAt(0) != ' ' && rest.charAt(0) != '\t') {
            return false;
        }

        String[] parts = rest.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String[] args;
        if (parts.length > 1) {
            // 对 edit 子命令做特殊处理：保留 <text> 段的完整原样（含空格、\n 字面量）
            if ("edit".equals(sub)) {
                return handleEdit(agent, parts[1]);
            }
            args = parts[1].split("\\s+");
        } else {
            args = new String[0];
        }

        switch (sub) {
            case "view" -> view(agent, args);
            case "delete" -> delete(agent, args);
            case "save" -> save(agent, workspaceDir, args);
            case "load" -> load(agent, workspaceDir, args);
            case "trim" -> trim(agent, args);
            case "status" -> status(agent);
            case "system" -> system(agent, args);
            case "undo" -> undo(agent, workspaceDir);
            case "edit" -> {
                // parts.length == 1，未提供 text 参数
                System.out.println("  " + AnsiColors.YELLOW + "用法: /context edit <index> <new text>"
                        + AnsiColors.RESET);
            }
            default -> {
                System.out.println("  " + AnsiColors.YELLOW + "未知子命令: " + sub + AnsiColors.RESET);
                printUsage();
            }
        }
        return true;
    }

    // ==================== 子命令实现 ====================

    private static void view(Agent agent, String[] args) {
        List<Msg> context = agent.getContext();
        if (context.isEmpty()) {
            System.out.println("  " + AnsiColors.DIM + "（上下文为空）" + AnsiColors.RESET);
            return;
        }
        if (args.length == 0) {
            ConsoleUI.printContextView(context);
            return;
        }
        Integer idx = parseIndex(args[0]);
        if (idx == null) return;
        if (idx < 0 || idx >= context.size()) {
            System.out.println("  " + AnsiColors.YELLOW + "索引越界: " + idx
                    + "（当前长度 " + context.size() + "）" + AnsiColors.RESET);
            return;
        }
        ConsoleUI.printContextDetail(context.get(idx), idx);
    }

    private static boolean handleEdit(Agent agent, String argText) {
        // 把 "0 新文本" 拆成 index + 剩余完整文本
        String[] parts = argText.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isEmpty()) {
            System.out.println("  " + AnsiColors.YELLOW + "用法: /context edit <index> <new text>"
                    + AnsiColors.RESET);
            return true;
        }
        Integer idx = parseIndex(parts[0]);
        if (idx == null) return true;

        List<Msg> context = agent.getContext();
        if (idx < 0 || idx >= context.size()) {
            System.out.println("  " + AnsiColors.YELLOW + "索引越界: " + idx
                    + "（当前长度 " + context.size() + "）" + AnsiColors.RESET);
            return true;
        }

        Msg original = context.get(idx);
        // 拒绝 system/tool 角色
        String role = original.getRole();
        if (!"user".equals(role) && !"assistant".equals(role)) {
            System.out.println("  " + AnsiColors.YELLOW + "仅可编辑 user/assistant 消息（当前: " + role + "）"
                    + AnsiColors.RESET);
            return true;
        }
        // 必须含至少一个 TextBlock
        int textBlockIdx = -1;
        for (int i = 0; i < original.getContent().size(); i++) {
            if (original.getContent().get(i) instanceof ContentBlock.TextBlock) {
                textBlockIdx = i;
                break;
            }
        }
        if (textBlockIdx < 0) {
            System.out.println("  " + AnsiColors.YELLOW + "消息 " + idx + " 无可编辑文本块"
                    + AnsiColors.RESET);
            return true;
        }

        // \n 字面量 → 真换行
        String newText = unescapeNewline(parts[1]);

        // 构造新 Msg：同 id/role/timestamp，替换第一个 TextBlock，其他 block 保留
        List<ContentBlock> newBlocks = new ArrayList<>(original.getContent());
        newBlocks.set(textBlockIdx, new ContentBlock.TextBlock(newText));
        Msg newMsg = new Msg(original.getId(), original.getRole(), newBlocks,
                original.getUsage(), original.getTimestamp(), original.getMetadata());

        snapshotBeforeMutation(agent, workspaceDirOrNull());
        try {
            agent.replaceMessage(idx, newMsg);
            System.out.println("  " + AnsiColors.GREEN + "✓ 已替换消息 [" + idx + "] 的文本块"
                    + AnsiColors.RESET);
        } catch (IndexOutOfBoundsException e) {
            System.out.println("  " + AnsiColors.YELLOW + e.getMessage() + AnsiColors.RESET);
        }
        return true;
    }

    private static void delete(Agent agent, String[] args) {
        if (args.length == 0) {
            System.out.println("  " + AnsiColors.YELLOW + "用法: /context delete <index>"
                    + AnsiColors.RESET);
            return;
        }
        Integer idx = parseIndex(args[0]);
        if (idx == null) return;
        List<Msg> context = agent.getContext();
        if (idx < 0 || idx >= context.size()) {
            System.out.println("  " + AnsiColors.YELLOW + "索引越界: " + idx
                    + "（当前长度 " + context.size() + "）" + AnsiColors.RESET);
            return;
        }
        snapshotBeforeMutation(agent, workspaceDirOrNull());
        try {
            agent.deleteMessage(idx);
            System.out.println("  " + AnsiColors.GREEN + "✓ 已删除消息 [" + idx + "]"
                    + "（剩余 " + agent.getContext().size() + " 条）" + AnsiColors.RESET);
        } catch (IndexOutOfBoundsException e) {
            System.out.println("  " + AnsiColors.YELLOW + e.getMessage() + AnsiColors.RESET);
        }
    }

    private static void save(Agent agent, Path workspaceDir, String[] args) {
        if (args.length == 0) {
            System.out.println("  " + AnsiColors.YELLOW + "用法: /context save <name>"
                    + AnsiColors.RESET);
            return;
        }
        String name = args[0];
        boolean force = args.length > 1 && "--force".equals(args[1]);
        ConversationStore store = new ConversationStore(workspaceDir);
        try {
            var path = store.save(name, agent.getContext(), force);
            System.out.println("  " + AnsiColors.GREEN + "✓ 已保存 " + agent.getContext().size()
                    + " 条消息 → " + path + AnsiColors.RESET);
        } catch (IllegalArgumentException e) {
            System.out.println("  " + AnsiColors.YELLOW + e.getMessage() + AnsiColors.RESET);
        } catch (IllegalStateException e) {
            System.out.println("  " + AnsiColors.YELLOW + e.getMessage() + AnsiColors.RESET);
        } catch (RuntimeException e) {
            System.out.println("  " + AnsiColors.YELLOW + "保存失败: " + e.getMessage()
                    + AnsiColors.RESET);
        }
    }

    private static void load(Agent agent, Path workspaceDir, String[] args) {
        if (args.length == 0) {
            System.out.println("  " + AnsiColors.YELLOW + "用法: /context load <name>"
                    + AnsiColors.RESET);
            return;
        }
        String name = args[0];
        ConversationStore store = new ConversationStore(workspaceDir);
        List<Msg> loaded;
        try {
            loaded = store.load(name);
        } catch (IllegalArgumentException e) {
            System.out.println("  " + AnsiColors.YELLOW + e.getMessage() + AnsiColors.RESET);
            return;
        } catch (RuntimeException e) {
            System.out.println("  " + AnsiColors.YELLOW + "加载失败: " + e.getMessage()
                    + AnsiColors.RESET);
            return;
        }

        snapshotBeforeMutation(agent, workspaceDir);
        agent.restoreContext(loaded);
        System.out.println("  " + AnsiColors.GREEN + "✓ 已加载 " + loaded.size() + " 条消息（替换当前上下文）"
                + AnsiColors.RESET);
    }

    private static void trim(Agent agent, String[] args) {
        int keep = DEFAULT_TRIM_KEEP;
        if (args.length > 0) {
            if (args[0].startsWith("keep=")) {
                try {
                    keep = Integer.parseInt(args[0].substring("keep=".length()));
                } catch (NumberFormatException e) {
                    System.out.println("  " + AnsiColors.YELLOW + "非法 keep 值: " + args[0]
                            + AnsiColors.RESET);
                    return;
                }
            } else {
                try {
                    keep = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    System.out.println("  " + AnsiColors.YELLOW + "非法参数: " + args[0]
                            + "（用法: /context trim [keep=N]）" + AnsiColors.RESET);
                    return;
                }
            }
        }
        if (keep <= 0) {
            System.out.println("  " + AnsiColors.YELLOW + "keep 必须 > 0" + AnsiColors.RESET);
            return;
        }

        snapshotBeforeMutation(agent, workspaceDirOrNull());
        int beforeSize = agent.getContext().size();
        try {
            agent.trimContext(keep);
        } catch (RuntimeException e) {
            System.out.println("  " + AnsiColors.YELLOW + "裁剪失败: " + e.getMessage()
                    + AnsiColors.RESET);
            return;
        }
        int afterSize = agent.getContext().size();
        System.out.println("  " + AnsiColors.GREEN + "✓ 上下文裁剪完成：" + beforeSize + " → "
                + afterSize + " 条" + AnsiColors.RESET);
    }

    private static void status(Agent agent) {
        List<Msg> context = agent.getContext();
        int tokens = Msg.sumEstimatedTokens(context);
        String sys = agent.getSystemPrompt();
        int sysChars = sys != null ? sys.length() : -1;
        boolean cmEnabled = agent.getContextManager() != null;
        ConsoleUI.printContextStatus(context.size(), tokens, sysChars, cmEnabled);
    }

    /**
     * 显示系统提示词。
     * <p>
     * 裸 {@code /context system} 显示完整内容；{@code /context system short}
     * 只显示前 500 字符 + 字符统计摘要，避免大 prompt 刷屏。
     * </p>
     */
    private static void system(Agent agent, String[] args) {
        String sys = agent.getSystemPrompt();
        if (sys == null || sys.isEmpty()) {
            System.out.println("  " + AnsiColors.DIM + "（未设置系统提示词）" + AnsiColors.RESET);
            return;
        }
        boolean shortMode = args.length > 0 && "short".equalsIgnoreCase(args[0]);
        if (shortMode && sys.length() > 500) {
            System.out.println("  " + AnsiColors.DIM + "(前 500 字符 / 共 "
                    + sys.length() + " 字符；去掉 short 参数看完整内容)" + AnsiColors.RESET);
            System.out.println();
            System.out.println(sys.substring(0, 500));
            System.out.println("  " + AnsiColors.DIM + "...("
                    + (sys.length() - 500) + " 字符省略)" + AnsiColors.RESET);
            return;
        }
        System.out.println("  " + AnsiColors.DIM + "(" + sys.length() + " 字符)"
                + AnsiColors.RESET);
        System.out.println();
        System.out.println(sys);
    }

    private static void undo(Agent agent, Path workspaceDir) {
        ConversationStore store = new ConversationStore(workspaceDir);
        if (!store.snapshotExists()) {
            System.out.println("  " + AnsiColors.YELLOW + "无可恢复的快照（单槽空）" + AnsiColors.RESET);
            return;
        }
        List<Msg> snap = store.loadSnapshot();
        if (snap == null) {
            System.out.println("  " + AnsiColors.YELLOW + "读取快照失败" + AnsiColors.RESET);
            return;
        }
        agent.restoreContext(snap);
        System.out.println("  " + AnsiColors.GREEN + "✓ 已恢复最近一次 mutation 前的上下文（"
                + snap.size() + " 条消息）" + AnsiColors.RESET);
    }

    // ==================== 辅助 ====================

    /**
     * mutation 前写单槽快照。
     * <p>
     * workspaceDir 为 null 时跳过（单测场景常见）。生产路径总是传入有效目录。
     * </p>
     */
    private static void snapshotBeforeMutation(Agent agent, Path workspaceDir) {
        if (workspaceDir == null) {
            log.debug("workspaceDir 为 null，跳过快照写入");
            return;
        }
        try {
            new ConversationStore(workspaceDir).snapshot(agent.getContext());
        } catch (RuntimeException e) {
            log.warn("写入快照失败（mutation 仍会继续）: {}", e.getMessage());
        }
    }

    /**
     * 当前实例级别的 workspaceDir 缓存。
     * <p>
     * 因为 {@link #handle} 是 static，而子命令分发是链式调用，需要把 workspaceDir
     * 通过 ThreadLocal 或字段传递——这里选择简单的静态字段，单线程 REPL 足够安全。
     * </p>
     */
    private static volatile Path currentWorkspaceDir;

    /**
     * 设置当前线程上下文的 workspace 目录，供子命令读取。
     * <p>
     * 也可以改为把 workspaceDir 贯穿所有子方法签名，但当前设计通过 static field 简化。
     * 调用方在 {@link #handle} 入口处设置一次，所有子命令都能读到。
     * </p>
     */
    private static Path workspaceDirOrNull() {
        return currentWorkspaceDir;
    }

    private static Integer parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            System.out.println("  " + AnsiColors.YELLOW + "非法索引: " + s + AnsiColors.RESET);
            return null;
        }
    }

    private static String unescapeNewline(String s) {
        return s.replace("\\n", "\n");
    }

    private static void printUsage() {
        System.out.println("  " + AnsiColors.DIM + "可用子命令：" + AnsiColors.RESET);
        System.out.println("    view [index] | edit <index> <text> | delete <index>");
        System.out.println("    save <name> | load <name> | trim [keep=N]");
        System.out.println("    status | system [short] | undo");
    }
}
