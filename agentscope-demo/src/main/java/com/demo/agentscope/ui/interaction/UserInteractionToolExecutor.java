package com.demo.agentscope.ui.interaction;

import com.demo.agentscope.mcp.MCPClient.BuiltinToolExecutor;
import com.demo.agentscope.ui.AnsiColors;
import com.demo.agentscope.ui.ConsoleUI;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ask_user} 内建工具执行器：同步阻塞地与用户交互，获取输入后返回结构化结果。
 *
 * <h2>支持模式</h2>
 * <ul>
 *   <li><strong>choice</strong>：单选。打印编号列表，用户输入序号。</li>
 *   <li><strong>multi</strong>：多选。打印带 checkbox 的列表，逗号分隔多个序号。</li>
 *   <li><strong>fill</strong>：填空。直接读一行。</li>
 *   <li><strong>confirm</strong>：确认。打印 (y/n)，循环直到识别。</li>
 * </ul>
 *
 * <p>通用能力：可选 Other（自定义输入）、可选取消、3 次重试上限。
 * 读取用户输入复用 {@link ConsoleUI#readLine(String)} —— JLine3 + UTF-8 + 回退路径。</p>
 */
public class UserInteractionToolExecutor implements BuiltinToolExecutor {

    public static final String TOOL_NAME = "ask_user";
    public static final String TOOL_DESCRIPTION = """
            与用户进行一次同步交互以澄清信息或获取确认。\
            支持四种模式：choice（单选）、multi（多选）、fill（填空）、confirm（确认）。\
            工具调用时会打印问题并等待用户输入；返回结构化 JSON 给调用方。\
            在意图不明确、需要用户确认关键操作、或需要从候选中选择时调用此工具。""";

    public static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "properties": {
                "prompt": {
                  "type": "string",
                  "description": "向用户展示的问题或提示文本"
                },
                "mode": {
                  "type": "string",
                  "enum": ["choice", "multi", "fill", "confirm"],
                  "description": "交互模式"
                },
                "options": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "choice/multi 模式下的选项列表"
                },
                "default": {
                  "type": "string",
                  "description": "可选默认值（用户直接回车时采用）"
                },
                "allow_other": {
                  "type": "boolean",
                  "default": false,
                  "description": "choice/multi 时是否允许用户自定义 Other 输入"
                },
                "allow_cancel": {
                  "type": "boolean",
                  "default": true,
                  "description": "是否允许用户取消（c / cancel / 空输入回退到 default）"
                },
                "placeholder": {
                  "type": "string",
                  "description": "fill 模式的提示文本"
                }
              },
              "required": ["prompt", "mode"]
            }
            """;

    /** 供测试注入 mock；默认委托至 ConsoleUI.readLine。 */
    @FunctionalInterface
    public interface InputReader {
        String read(String prompt);
    }

    private static final Set<String> YES_WORDS = Set.of(
            "y", "Y", "yes", "YES", "Yes", "ok", "OK", "好的", "是", "确认", "确认。"
    );
    private static final Set<String> NO_WORDS = Set.of(
            "n", "N", "no", "NO", "No", "cancel", "否", "不", "取消"
    );
    private static final Set<String> CANCEL_WORDS = Set.of(
            "c", "C", "cancel", "取消", "q", "quit", "exit"
    );
    private static final Set<String> OTHER_WORDS = Set.of("o", "O", "other", "其他");

    private static final int MAX_ATTEMPTS = 3;

    private final InputReader inputReader;
    private final PrintStream out;

    public UserInteractionToolExecutor() {
        this(ConsoleUI::readLine, System.out);
    }

    /** 测试用构造器：可注入输入源与输出流。 */
    public UserInteractionToolExecutor(InputReader inputReader, PrintStream out) {
        this.inputReader = inputReader == null ? ConsoleUI::readLine : inputReader;
        this.out = out == null ? System.out : out;
    }

    public static String parametersJson() {
        return PARAMETERS_JSON;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        if (args == null) {
            return InteractionResult.error("参数不能为空").toJson();
        }
        String prompt = stringArg(args, "prompt");
        if (prompt == null || prompt.isBlank()) {
            return InteractionResult.error("参数 prompt 必填").toJson();
        }
        String mode = stringArg(args, "mode");
        if (mode == null || mode.isBlank()) {
            return InteractionResult.error("参数 mode 必填").toJson();
        }
        String defaultValue = stringArg(args, "default");
        boolean allowOther = boolArg(args, "allow_other", false);
        boolean allowCancel = boolArg(args, "allow_cancel", true);

        return switch (mode) {
            case "choice", "multi" -> {
                List<String> options = stringListArg(args, "options");
                if (options == null || options.isEmpty()) {
                    yield InteractionResult.error(mode + " 模式需要非空 options").toJson();
                }
                if ("choice".equals(mode)) {
                    yield runChoice(prompt, options, defaultValue, allowOther, allowCancel);
                } else {
                    yield runMulti(prompt, options, defaultValue, allowOther, allowCancel);
                }
            }
            case "fill" -> runFill(prompt, defaultValue, allowCancel,
                    stringArg(args, "placeholder"));
            case "confirm" -> runConfirm(prompt, defaultValue, allowCancel);
            default -> InteractionResult.error("未知 mode：" + mode).toJson();
        };
    }

    // ==================== 单选 ====================

    private String runChoice(String prompt, List<String> options, String def,
                             boolean allowOther, boolean allowCancel) {
        out.print(AnsiColors.YELLOW + BANNER + AnsiColors.RESET + "\n");
        out.print(wrapPrompt(prompt) + "\n");
        for (int i = 0; i < options.size(); i++) {
            out.print("  " + AnsiColors.CYAN + (i + 1) + ")" + AnsiColors.RESET + " " + options.get(i) + "\n");
        }
        String hint = buildChoiceHint(allowOther, allowCancel, def);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String raw = readInput(hint);
            if (raw == null) {
                return cancelledOrDefault("choice", def, "EOF");
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                if (def != null && !def.isBlank()) {
                    int idx = options.indexOf(def);
                    return InteractionResult.ok("choice", def, "",
                            idx >= 0 ? List.of(idx) : List.of()).toJson();
                }
                if (allowCancel) {
                    return InteractionResult.cancelled("choice", "空输入").toJson();
                }
                out.print(retryMsg(attempt));
                continue;
            }
            if (CANCEL_WORDS.contains(trimmed) && allowCancel) {
                return InteractionResult.cancelled("choice", "用户取消").toJson();
            }
            if (OTHER_WORDS.contains(trimmed) && allowOther) {
                String other = readInput("请输入自定义内容: ");
                if (other == null || other.isBlank()) {
                    return InteractionResult.cancelled("choice", "Other 为空").toJson();
                }
                return InteractionResult.other("choice", other.trim()).toJson();
            }
            Integer idx = parseIndex(trimmed, options.size());
            if (idx != null) {
                return InteractionResult.ok("choice", options.get(idx), trimmed, List.of(idx)).toJson();
            }
            out.print(retryMsg(attempt));
        }
        return InteractionResult.cancelled("choice", "超过重试上限").toJson();
    }

    // ==================== 多选 ====================

    private String runMulti(String prompt, List<String> options, String def,
                            boolean allowOther, boolean allowCancel) {
        out.print(AnsiColors.YELLOW + BANNER + AnsiColors.RESET + "\n");
        out.print(wrapPrompt(prompt) + "\n");
        for (int i = 0; i < options.size(); i++) {
            out.print("  " + AnsiColors.DIM + "[ ]" + AnsiColors.RESET + " " + options.get(i) + "\n");
        }
        String hint = buildMultiHint(allowOther, allowCancel);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String raw = readInput(hint);
            if (raw == null) return cancelledOrDefault("multi", def, "EOF");
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                if (def != null && !def.isBlank()) {
                    return InteractionResult.ok("multi", def, "", List.of()).toJson();
                }
                if (allowCancel) return InteractionResult.cancelled("multi", "空输入").toJson();
                out.print(retryMsg(attempt));
                continue;
            }
            if (CANCEL_WORDS.contains(trimmed) && allowCancel) {
                return InteractionResult.cancelled("multi", "用户取消").toJson();
            }
            if (OTHER_WORDS.contains(trimmed) && allowOther) {
                String other = readInput("请输入自定义内容: ");
                if (other == null || other.isBlank()) {
                    return InteractionResult.cancelled("multi", "Other 为空").toJson();
                }
                return InteractionResult.other("multi", other.trim()).toJson();
            }
            List<Integer> indices = parseIndices(trimmed, options.size());
            if (!indices.isEmpty()) {
                List<String> values = new ArrayList<>();
                for (int i : indices) values.add(options.get(i));
                return InteractionResult.ok("multi", String.join(", ", values), trimmed, indices).toJson();
            }
            out.print(retryMsg(attempt));
        }
        return InteractionResult.cancelled("multi", "超过重试上限").toJson();
    }

    // ==================== 填空 ====================

    private String runFill(String prompt, String def, boolean allowCancel, String placeholder) {
        out.print(AnsiColors.YELLOW + BANNER + AnsiColors.RESET + "\n");
        out.print(wrapPrompt(prompt) + "\n");
        StringBuilder hint = new StringBuilder();
        if (placeholder != null && !placeholder.isBlank()) hint.append(placeholder);
        else if (def != null && !def.isBlank()) hint.append("（回车采用默认：").append(def).append("）");
        if (allowCancel) hint.append("  c=取消");
        if (hint.length() > 0) hint.append(" ");
        hint.append("▶ ");
        String raw = readInput(hint.toString());
        if (raw == null) return cancelledOrDefault("fill", def, "EOF");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            if (def != null && !def.isBlank()) {
                return InteractionResult.ok("fill", def, "", List.of()).toJson();
            }
            if (allowCancel) {
                return InteractionResult.cancelled("fill", "空输入").toJson();
            }
            return InteractionResult.ok("fill", "", "", List.of()).toJson();
        }
        if (CANCEL_WORDS.contains(trimmed) && allowCancel) {
            return InteractionResult.cancelled("fill", "用户取消").toJson();
        }
        return InteractionResult.ok("fill", trimmed, trimmed, List.of()).toJson();
    }

    // ==================== 确认 ====================

    private String runConfirm(String prompt, String def, boolean allowCancel) {
        out.print(AnsiColors.YELLOW + BANNER + AnsiColors.RESET + "\n");
        out.print(wrapPrompt(prompt) + "\n");
        String hint = allowCancel ? "(y/n, c=取消) ▶ " : "(y/n) ▶ ";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String raw = readInput(hint);
            if (raw == null) return cancelledOrDefault("confirm", def, "EOF");
            String trimmed = raw.trim().toLowerCase();
            if (trimmed.isEmpty()) {
                if (def != null) {
                    boolean yes = isYes(def);
                    return InteractionResult.ok("confirm", yes ? "yes" : "no", "", List.of()).toJson();
                }
                if (allowCancel) {
                    return InteractionResult.cancelled("confirm", "空输入").toJson();
                }
                out.print(retryMsg(attempt));
                continue;
            }
            if (CANCEL_WORDS.contains(trimmed) && allowCancel) {
                return InteractionResult.cancelled("confirm", "用户取消").toJson();
            }
            if (isYes(trimmed)) {
                return InteractionResult.ok("confirm", "yes", trimmed, List.of()).toJson();
            }
            if (isNo(trimmed)) {
                return InteractionResult.ok("confirm", "no", trimmed, List.of()).toJson();
            }
            out.print(retryMsg(attempt));
        }
        return InteractionResult.cancelled("confirm", "超过重试上限").toJson();
    }

    // ==================== 辅助 ====================

    private String readInput(String prompt) {
        try {
            return inputReader.read(prompt);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String cancelledOrDefault(String mode, String def, String reason) {
        if (def != null && !def.isBlank()) {
            return InteractionResult.ok(mode, def, "", List.of()).toJson();
        }
        return InteractionResult.cancelled(mode, reason).toJson();
    }

    private String retryMsg(int attempt) {
        return AnsiColors.RED + "  ✗ 无法识别，请重试（"
                + attempt + "/" + MAX_ATTEMPTS + "）" + AnsiColors.RESET + "\n";
    }

    private static String buildChoiceHint(boolean allowOther, boolean allowCancel, String def) {
        StringBuilder sb = new StringBuilder();
        sb.append("请输入序号");
        if (def != null && !def.isBlank()) sb.append("（回车采用默认）");
        if (allowOther) sb.append("，o=自定义");
        if (allowCancel) sb.append("，c=取消");
        sb.append(" ▶ ");
        return sb.toString();
    }

    private static String buildMultiHint(boolean allowOther, boolean allowCancel) {
        StringBuilder sb = new StringBuilder();
        sb.append("请输入序号（逗号分隔多选）");
        if (allowOther) sb.append("，o=自定义");
        if (allowCancel) sb.append("，c=取消");
        sb.append(" ▶ ");
        return sb.toString();
    }

    private String wrapPrompt(String prompt) {
        // 简单按行包装，不加额外缩进 —— prompt 可包含换行
        return AnsiColors.BOLD + prompt + AnsiColors.RESET;
    }

    private static Integer parseIndex(String s, int size) {
        try {
            int n = Integer.parseInt(s.trim());
            if (n >= 1 && n <= size) return n - 1;
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static List<Integer> parseIndices(String s, int size) {
        Set<Integer> set = new LinkedHashSet<>();
        for (String part : s.split("[,，\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                int n = Integer.parseInt(p);
                if (n >= 1 && n <= size) set.add(n - 1);
                else return List.of();
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return new ArrayList<>(set);
    }

    private static boolean isYes(String s) {
        if (s == null) return false;
        return YES_WORDS.contains(s) || s.equalsIgnoreCase("y") || s.equalsIgnoreCase("yes");
    }

    private static boolean isNo(String s) {
        if (s == null) return false;
        return NO_WORDS.contains(s);
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        Object v = args.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }

    private static List<String> stringListArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(o == null ? "" : String.valueOf(o));
            return out;
        }
        if (v instanceof String s) {
            // 允许字符串以逗号分隔
            return Arrays.stream(s.split("[,，]"))
                    .map(String::trim)
                    .filter(x -> !x.isEmpty())
                    .toList();
        }
        return null;
    }

    private static final String BANNER = "  ─── 交互式询问 ───";
}
