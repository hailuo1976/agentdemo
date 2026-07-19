package com.demo.agentscope.ui.markdown;

import com.demo.agentscope.ui.AnsiColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端 Markdown 渲染器：把 Markdown 文本渲染成带 ANSI 颜色与对齐的字符串。
 *
 * <h2>支持范围</h2>
 * <ul>
 *   <li>块级：ATX 标题、围栏代码块、无序/有序列表（含 task list）、表格、引用块、分隔线、段落</li>
 *   <li>行内：{@code `code`}、{@code **bold**}、{@code *italic*}、{@code ~~strike~~}、{@code [text](url)}、{@code ![alt](url)}</li>
 *   <li>代码高亮：java / python / bash / json / markdown（基于关键字 + 正则，非完整 lex）</li>
 *   <li>主题：dark（默认）/ light（主要影响边框样式与代码块底色）</li>
 * </ul>
 *
 * <p>设计取向：零外部依赖、单文件、正确处理 CJK 全角宽度。
 * 不追求 CommonMark 完全合规；聚焦于 Agent 输出场景下最常见的语法元素。</p>
 */
public class MarkdownRenderer {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*\\S.*)?$");
    private static final Pattern HR_PATTERN = Pattern.compile("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^(\\s*)([-*+]|\\d+\\.)\\s+(.+)$");
    private static final Pattern TASK_PATTERN = Pattern.compile("^\\[([ xX])]\\s+(.*)$");
    private static final Pattern FENCE_BACKTICK = Pattern.compile("^ {0,3}(`{3,})(\\w*)\\s*$");
    private static final Pattern FENCE_TILDE = Pattern.compile("^ {0,3}(~{3,})(\\w*)\\s*$");
    private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile("^\\s*\\|?(\\s*:?-{2,}:?\\s*\\|\\s*)+:?-{2,}:?\\s*\\|?\\s*$");
    private static final Pattern ANSI_STRIP = Pattern.compile("\u001B\\[[0-9;]*m");

    private final boolean lightTheme;
    private final int width;

    public MarkdownRenderer(String theme, int width) {
        this.lightTheme = "light".equalsIgnoreCase(theme == null ? "" : theme.trim());
        this.width = width > 0 ? width : 80;
    }

    /** 便捷静态入口。 */
    public static String render(String content, String theme, int width) {
        return new MarkdownRenderer(theme, width).render(content);
    }

    /** 渲染整段 Markdown 文本。 */
    public String render(String content) {
        if (content == null || content.isEmpty()) return "";
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            if (line.isBlank()) {
                out.append("\n");
                i++;
                continue;
            }

            String fenceChar = matchFence(line);
            if (fenceChar != null) {
                i = renderFencedCode(lines, i, fenceChar, out);
                continue;
            }

            Matcher hm = HEADING_PATTERN.matcher(line);
            if (hm.matches()) {
                int level = hm.group(1).length();
                String text = hm.group(2) == null ? "" : hm.group(2).trim();
                out.append(renderHeading(level, text)).append("\n");
                i++;
                continue;
            }

            if (HR_PATTERN.matcher(line).matches()) {
                out.append(renderHR()).append("\n");
                i++;
                continue;
            }

            // Table: 必须是含 | 的行，且下一行是分隔行
            if (line.indexOf('|') >= 0 && i + 1 < lines.length
                    && TABLE_SEPARATOR_PATTERN.matcher(lines[i + 1]).matches()
                    && hasPipes(line)) {
                i = renderTable(lines, i, out);
                continue;
            }

            if (LIST_ITEM_PATTERN.matcher(line).matches()) {
                i = renderListBlock(lines, i, out);
                continue;
            }

            if (line.trim().startsWith(">")) {
                i = renderBlockquote(lines, i, out);
                continue;
            }

            i = renderParagraph(lines, i, out);
        }
        // 去掉末尾多余空行
        return stripTrailingNewlines(out.toString()) + "\n";
    }

    // ==================== 块级：标题 ====================

    private String renderHeading(int level, String text) {
        String prefix = "#".repeat(level) + " ";
        String content = renderInline(text);
        // 不同级别的视觉强度
        return switch (level) {
            case 1 -> AnsiColors.BOLD + AnsiColors.MAGENTA + prefix + content + AnsiColors.RESET
                    + "\n" + AnsiColors.DIM + "─".repeat(Math.min(width - 1, 60)) + AnsiColors.RESET;
            case 2 -> AnsiColors.BOLD + AnsiColors.BLUE + prefix + content + AnsiColors.RESET
                    + "\n" + AnsiColors.DIM + "─".repeat(Math.min(width - 1, 40)) + AnsiColors.RESET;
            case 3 -> AnsiColors.BOLD + AnsiColors.CYAN + prefix + content + AnsiColors.RESET;
            default -> AnsiColors.BOLD + prefix + content + AnsiColors.RESET;
        };
    }

    private String renderHR() {
        return AnsiColors.DIM + "─".repeat(Math.min(width - 1, 60)) + AnsiColors.RESET;
    }

    // ==================== 块级：围栏代码 ====================

    private int renderFencedCode(String[] lines, int start, String fenceChar, StringBuilder out) {
        Pattern fence = "`".equals(fenceChar) ? FENCE_BACKTICK : FENCE_TILDE;
        Matcher sm = fence.matcher(lines[start]);
        String lang = sm.matches() && sm.group(2) != null ? sm.group(2).toLowerCase() : "";
        int fenceLen = sm.matches() ? sm.group(1).length() : 3;

        List<String> body = new ArrayList<>();
        int i = start + 1;
        while (i < lines.length) {
            Matcher cm = fence.matcher(lines[i]);
            if (cm.matches() && cm.group(1).length() >= fenceLen) {
                break;
            }
            body.add(lines[i]);
            i++;
        }
        // i 指向闭合围栏；若无闭合则到末尾
        if (i < lines.length) i++;

        out.append(renderCodeBlock(body, lang)).append("\n");
        return i;
    }

    private String renderCodeBlock(List<String> body, String lang) {
        String border = lightTheme ? AnsiColors.DIM : AnsiColors.BLUE;
        StringBuilder sb = new StringBuilder();
        String top = border + "┌─ " + (lang.isEmpty() ? "code" : lang) + " "
                + "─".repeat(Math.max(1, Math.min(width - 1, 50) - lang.length() - 6))
                + AnsiColors.RESET;
        sb.append(top).append("\n");
        for (String line : body) {
            sb.append(border).append("│ ").append(AnsiColors.RESET)
                    .append(highlightLine(line, lang)).append("\n");
        }
        sb.append(border).append("└"
                + "─".repeat(Math.max(1, Math.min(width - 1, 59))) + AnsiColors.RESET);
        return sb.toString();
    }

    // ==================== 块级：列表 ====================

    private int renderListBlock(String[] lines, int start, StringBuilder out) {
        int i = start;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                // 空行：若是下一个非空行仍是列表项则继续，否则结束
                int j = i + 1;
                while (j < lines.length && lines[j].isBlank()) j++;
                if (j < lines.length && LIST_ITEM_PATTERN.matcher(lines[j]).matches()) {
                    out.append("\n");
                    i = j;
                    continue;
                }
                break;
            }
            Matcher lm = LIST_ITEM_PATTERN.matcher(line);
            if (!lm.matches()) {
                // 非列表项：可能是列表项的续行（缩进更深）则当段落处理；否则结束
                if (looksLikeContinuation(line)) {
                    out.append(renderInline(line.stripTrailing())).append("\n");
                    i++;
                    continue;
                }
                break;
            }
            String indent = lm.group(1);
            String marker = lm.group(2);
            String rest = lm.group(3);
            int depth = indent.length() / 2;
            String prefix = "  ".repeat(depth);

            Matcher tm = TASK_PATTERN.matcher(rest);
            if (tm.matches()) {
                boolean checked = !tm.group(1).isBlank();
                String box = checked
                        ? AnsiColors.GREEN + "[x]" + AnsiColors.RESET
                        : AnsiColors.DIM + "[ ]" + AnsiColors.RESET;
                out.append(prefix).append(box).append(" ").append(renderInline(tm.group(2))).append("\n");
            } else if (marker.matches("\\d+\\.")) {
                out.append(prefix)
                        .append(AnsiColors.YELLOW).append(marker).append(AnsiColors.RESET)
                        .append(" ").append(renderInline(rest)).append("\n");
            } else {
                out.append(prefix)
                        .append(AnsiColors.CYAN).append(marker).append(AnsiColors.RESET)
                        .append(" ").append(renderInline(rest)).append("\n");
            }
            i++;
        }
        return i;
    }

    private boolean looksLikeContinuation(String line) {
        return line.startsWith("  ") || line.startsWith("\t");
    }

    // ==================== 块级：引用块 ====================

    private int renderBlockquote(String[] lines, int start, StringBuilder out) {
        int i = start;
        StringBuilder content = new StringBuilder();
        while (i < lines.length) {
            String line = lines[i];
            if (!line.trim().startsWith(">")) break;
            String stripped = line.replaceFirst("^\\s*>\\s?", "");
            content.append(stripped).append("\n");
            i++;
        }
        String rendered = renderInline(content.toString().stripTrailing());
        for (String l : rendered.split("\n")) {
            out.append(AnsiColors.DIM).append("│ ").append(l).append(AnsiColors.RESET).append("\n");
        }
        return i;
    }

    // ==================== 块级：段落 ====================

    private int renderParagraph(String[] lines, int start, StringBuilder out) {
        StringBuilder para = new StringBuilder();
        int i = start;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) break;
            if (matchFence(line) != null) break;
            if (HEADING_PATTERN.matcher(line).matches()) break;
            if (HR_PATTERN.matcher(line).matches()) break;
            if (LIST_ITEM_PATTERN.matcher(line).matches()) break;
            if (line.trim().startsWith(">")) break;
            if (line.indexOf('|') >= 0 && i + 1 < lines.length
                    && TABLE_SEPARATOR_PATTERN.matcher(lines[i + 1]).matches()
                    && hasPipes(line)) break;
            if (para.length() > 0) para.append(" ");
            para.append(line.strip());
            i++;
        }
        if (para.length() > 0) {
            out.append(renderInline(para.toString())).append("\n");
        }
        return i;
    }

    // ==================== 块级：表格 ====================

    private int renderTable(String[] lines, int start, StringBuilder out) {
        List<String> rawRows = new ArrayList<>();
        int i = start;
        // 表格直到空行或非表格行
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank() || line.indexOf('|') < 0) break;
            // 第二行必须是分隔行；后续行若是分隔行形状也允许（很少见）但只采纳第一条作为对齐说明
            rawRows.add(line);
            i++;
            // 若当前位置是分隔行之后，再继续吃数据行；遇空行/非|行结束
            if (rawRows.size() >= 2 && !lines[i - 1].matches(".*\\|.*")) break;
        }
        if (rawRows.size() < 2) {
            // 退化：当段落处理
            return renderParagraph(lines, start, out);
        }

        List<String[]> cells = new ArrayList<>();
        for (String r : rawRows) {
            cells.add(splitTableRow(r));
        }
        String[] alignments = parseAlignments(rawRows.get(1), cells.get(0).length);

        // 第一行是表头，第二行是分隔（已用），其余是数据
        String[] header = cells.get(0);
        List<String[]> dataRows = new ArrayList<>();
        for (int k = 2; k < cells.size(); k++) dataRows.add(cells.get(k));

        // 计算列宽（基于 display width，剥离 ANSI）
        int cols = header.length;
        int[] colWidths = new int[cols];
        for (int c = 0; c < cols; c++) {
            colWidths[c] = displayWidth(stripAnsi(header[c]));
        }
        for (String[] row : dataRows) {
            for (int c = 0; c < cols && c < row.length; c++) {
                colWidths[c] = Math.max(colWidths[c], displayWidth(stripAnsi(row[c])));
            }
        }

        // 渲染
        String borderColor = AnsiColors.CYAN;
        out.append(borderColor).append(buildTableBorder(colWidths, "┌", "┬", "┐")).append(AnsiColors.RESET).append("\n");
        out.append(renderTableRow(header, colWidths, alignments, true)).append("\n");
        out.append(borderColor).append(buildTableBorder(colWidths, "├", "┼", "┤")).append(AnsiColors.RESET).append("\n");
        for (String[] row : dataRows) {
            out.append(renderTableRow(padRow(row, cols), colWidths, alignments, false)).append("\n");
        }
        out.append(borderColor).append(buildTableBorder(colWidths, "└", "┴", "┘")).append(AnsiColors.RESET);
        return i;
    }

    private String renderTableRow(String[] cells, int[] widths, String[] aligns, boolean header) {
        StringBuilder sb = new StringBuilder();
        String sep = AnsiColors.CYAN + "│" + AnsiColors.RESET;
        sb.append(sep);
        for (int c = 0; c < widths.length; c++) {
            String cell = c < cells.length ? cells[c] : "";
            String rendered = header
                    ? AnsiColors.BOLD + renderInline(cell) + AnsiColors.RESET
                    : renderInline(cell);
            int dw = displayWidth(stripAnsi(cell));
            int pad = Math.max(0, widths[c] - dw);
            String align = c < aligns.length ? aligns[c] : "left";
            sb.append(" ");
            switch (align) {
                case "right" -> sb.append(" ".repeat(pad)).append(rendered);
                case "center" -> {
                    int left = pad / 2;
                    int right = pad - left;
                    sb.append(" ".repeat(left)).append(rendered).append(" ".repeat(right));
                }
                default -> sb.append(rendered).append(" ".repeat(pad));
            }
            sb.append(" ").append(sep);
        }
        return sb.toString();
    }

    private String buildTableBorder(int[] widths, String left, String mid, String right) {
        StringBuilder sb = new StringBuilder(left);
        for (int c = 0; c < widths.length; c++) {
            sb.append("─".repeat(widths[c] + 2));
            sb.append(c == widths.length - 1 ? right : mid);
        }
        return sb.toString();
    }

    private static String[] splitTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        String[] stripped = new String[parts.length];
        for (int i = 0; i < parts.length; i++) stripped[i] = parts[i].trim();
        return stripped;
    }

    private static String[] parseAlignments(String sepLine, int cols) {
        String[] cells = splitTableRow(sepLine);
        String[] aligns = new String[cols];
        for (int i = 0; i < cols && i < cells.length; i++) {
            String c = cells[i].trim();
            boolean left = c.startsWith(":");
            boolean right = c.endsWith(":");
            if (left && right) aligns[i] = "center";
            else if (right) aligns[i] = "right";
            else aligns[i] = "left";
        }
        return aligns;
    }

    private static String[] padRow(String[] row, int cols) {
        if (row.length == cols) return row;
        String[] out = new String[cols];
        System.arraycopy(row, 0, out, 0, Math.min(row.length, cols));
        for (int i = row.length; i < cols; i++) out[i] = "";
        return out;
    }

    private static boolean hasPipes(String line) {
        return line.indexOf('|') >= 0;
    }

    // ==================== 行内渲染 ====================

    String renderInline(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            // 图片 ![alt](url)
            if (c == '!' && i + 1 < text.length() && text.charAt(i + 1) == '[') {
                int close = findMatching(text, i + 1, '[', ']');
                if (close > 0 && close + 1 < text.length() && text.charAt(close + 1) == '(') {
                    int end = text.indexOf(')', close + 2);
                    if (end > 0) {
                        String alt = text.substring(i + 2, close);
                        String url = text.substring(close + 2, end);
                        out.append(AnsiColors.DIM).append("[图片: ")
                                .append(alt).append("](").append(url).append(")")
                                .append(AnsiColors.RESET);
                        i = end + 1;
                        continue;
                    }
                }
            }

            // 链接 [text](url)
            if (c == '[') {
                int close = findMatching(text, i, '[', ']');
                if (close > 0 && close + 1 < text.length() && text.charAt(close + 1) == '(') {
                    int end = text.indexOf(')', close + 2);
                    if (end > 0) {
                        String linkText = text.substring(i + 1, close);
                        String url = text.substring(close + 2, end);
                        out.append(renderInline(linkText))
                                .append(" ").append(AnsiColors.CYAN).append("(")
                                .append(url).append(")").append(AnsiColors.RESET);
                        i = end + 1;
                        continue;
                    }
                }
            }

            // 行内代码 `code`
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    out.append(AnsiColors.CYAN).append(text, i + 1, end).append(AnsiColors.RESET);
                    i = end + 1;
                    continue;
                }
            }

            // 粗体 **text**
            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > 0) {
                    out.append(AnsiColors.BOLD)
                            .append(renderInline(text.substring(i + 2, end)))
                            .append(AnsiColors.RESET);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '_' && i + 1 < text.length() && text.charAt(i + 1) == '_') {
                int end = text.indexOf("__", i + 2);
                if (end > 0) {
                    out.append(AnsiColors.BOLD)
                            .append(renderInline(text.substring(i + 2, end)))
                            .append(AnsiColors.RESET);
                    i = end + 2;
                    continue;
                }
            }

            // 删除线 ~~text~~
            if (c == '~' && i + 1 < text.length() && text.charAt(i + 1) == '~') {
                int end = text.indexOf("~~", i + 2);
                if (end > 0) {
                    out.append(AnsiColors.DIM)
                            .append(renderInline(text.substring(i + 2, end)))
                            .append(AnsiColors.RESET);
                    i = end + 2;
                    continue;
                }
            }

            // 斜体 *text* 或 _text_
            if ((c == '*' || c == '_')) {
                int end = text.indexOf(c, i + 1);
                if (end > i + 1) {
                    out.append(AnsiColors.UNDERLINE)
                            .append(renderInline(text.substring(i + 1, end)))
                            .append(AnsiColors.RESET);
                    i = end + 1;
                    continue;
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 在 text 中从 from 开始查找配对的闭合字符（不考虑嵌套）。 */
    private static int findMatching(String text, int from, char open, char close) {
        // 简化版：只找下一个 close 字符
        return text.indexOf(close, from + 1);
    }

    // ==================== 代码高亮 ====================

    private String highlightLine(String line, String lang) {
        if (line == null || line.isEmpty()) return "";
        if (lang == null || lang.isBlank()) return escapeBackslash(line);
        return switch (lang) {
            case "java" -> CodeHighlighter.highlight(line, CodeHighlighter.JAVA);
            case "python", "py" -> CodeHighlighter.highlight(line, CodeHighlighter.PYTHON);
            case "bash", "sh", "shell", "zsh" -> CodeHighlighter.highlight(line, CodeHighlighter.BASH);
            case "json" -> CodeHighlighter.highlight(line, CodeHighlighter.JSON);
            case "markdown", "md" -> CodeHighlighter.highlight(line, CodeHighlighter.MARKDOWN);
            default -> escapeBackslash(line);
        };
    }

    private static String escapeBackslash(String s) {
        return s; // 终端无需 HTML 转义
    }

    // ==================== 显示宽度（CJK 全角近似） ====================

    static int displayWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += cjkWidth(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    private static int cjkWidth(int cp) {
        // 简化版 wcwidth：覆盖常见 CJK 区块
        if (cp == 0) return 0;
        if (cp < 0x20 || (cp >= 0x7F && cp < 0xA0)) return 0;
        if (cp >= 0x1100 && (
                cp <= 0x115F
                        || (cp >= 0x2E80 && cp <= 0x303E)
                        || (cp >= 0x3041 && cp <= 0x33FF)
                        || (cp >= 0x3400 && cp <= 0x4DBF)
                        || (cp >= 0x4E00 && cp <= 0x9FFF)
                        || (cp >= 0xA000 && cp <= 0xA4CF)
                        || (cp >= 0xAC00 && cp <= 0xD7A3)
                        || (cp >= 0xF900 && cp <= 0xFAFF)
                        || (cp >= 0xFE30 && cp <= 0xFE4F)
                        || (cp >= 0xFF00 && cp <= 0xFF60)
                        || (cp >= 0xFFE0 && cp <= 0xFFE6)
                        || (cp >= 0x20000 && cp <= 0x2FFFD)
                        || (cp >= 0x30000 && cp <= 0x3FFFD))) return 2;
        return 1;
    }

    static String stripAnsi(String s) {
        return ANSI_STRIP.matcher(s).replaceAll("");
    }

    // ==================== Fence 匹配 ====================

    private static String matchFence(String line) {
        if (FENCE_BACKTICK.matcher(line).matches()) return "`";
        if (FENCE_TILDE.matcher(line).matches()) return "~";
        return null;
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n')) end--;
        return s.substring(0, end);
    }

    // ==================== 代码高亮器（内部类） ====================

    /**
     * 简易代码高亮器：基于关键字集合 + 正则识别注释/字符串/数字。
     * 非完整 lexer；适合单行着色场景。
     */
    static final class CodeHighlighter {

        static final Set<String> JAVA = Set.of(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                "char", "class", "const", "continue", "default", "do", "double",
                "else", "enum", "extends", "final", "finally", "float", "for",
                "goto", "if", "implements", "import", "instanceof", "int",
                "interface", "long", "native", "new", "null", "package", "private",
                "protected", "public", "return", "short", "static", "strictfp",
                "super", "switch", "synchronized", "this", "throw", "throws",
                "transient", "try", "void", "volatile", "while", "var", "record",
                "sealed", "permits", "yield", "true", "false"
        );

        static final Set<String> PYTHON = Set.of(
                "False", "None", "True", "and", "as", "assert", "async", "await",
                "break", "class", "continue", "def", "del", "elif", "else",
                "except", "finally", "for", "from", "global", "if", "import",
                "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
                "return", "try", "while", "with", "yield", "self", "cls"
        );

        static final Set<String> BASH = Set.of(
                "if", "then", "else", "elif", "fi", "case", "esac", "for", "while",
                "do", "done", "function", "in", "return", "local", "export",
                "unset", "set", "shift", "source", "echo", "printf", "exit",
                "cd", "pwd", "ls", "cp", "mv", "rm", "mkdir", "rmdir", "cat",
                "grep", "sed", "awk", "find", "chmod", "chown", "sudo"
        );

        static final Set<String> JSON = Set.of(
                "true", "false", "null"
        );

        static final Set<String> MARKDOWN = Set.of();

        private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
        private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?[fFdDlL]?\\b");
        private static final Pattern ANNOTATION = Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*");

        static String highlight(String line, Set<String> keywords) {
            if (line.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);

                // 行注释
                if (isLineCommentStart(line, i)) {
                    out.append(AnsiColors.DIM).append(line.substring(i)).append(AnsiColors.RESET);
                    break;
                }

                // 字符串（双引号）
                if (c == '"') {
                    int end = findStringEnd(line, i, '"');
                    if (end > 0) {
                        out.append(AnsiColors.GREEN).append(line, i, end + 1).append(AnsiColors.RESET);
                        i = end + 1;
                        continue;
                    }
                }
                // 字符串（单引号）
                if (c == '\'') {
                    int end = findStringEnd(line, i, '\'');
                    if (end > 0) {
                        out.append(AnsiColors.GREEN).append(line, i, end + 1).append(AnsiColors.RESET);
                        i = end + 1;
                        continue;
                    }
                }

                // 注解（Java/Python）
                if (c == '@' && i + 1 < line.length() && Character.isJavaIdentifierStart(line.charAt(i + 1))) {
                    Matcher am = ANNOTATION.matcher(line.substring(i));
                    if (am.lookingAt()) {
                        out.append(AnsiColors.MAGENTA).append(am.group()).append(AnsiColors.RESET);
                        i += am.group().length();
                        continue;
                    }
                }

                // 数字
                if (Character.isDigit(c)) {
                    Matcher nm = NUMBER.matcher(line.substring(i));
                    if (nm.lookingAt()) {
                        out.append(AnsiColors.CYAN).append(nm.group()).append(AnsiColors.RESET);
                        i += nm.group().length();
                        continue;
                    }
                }

                // 标识符 / 关键字
                if (Character.isJavaIdentifierStart(c)) {
                    Matcher im = IDENT.matcher(line.substring(i));
                    if (im.lookingAt()) {
                        String word = im.group();
                        if (keywords.contains(word)) {
                            out.append(AnsiColors.YELLOW).append(word).append(AnsiColors.RESET);
                        } else {
                            out.append(word);
                        }
                        i += word.length();
                        continue;
                    }
                }

                // JSON 特殊值（在 JSON 模式下，true/false/null 已在 keywords 中）
                out.append(c);
                i++;
            }
            return out.toString();
        }

        private static boolean isLineCommentStart(String line, int i) {
            return i + 1 < line.length()
                    && line.charAt(i) == '/'
                    && (line.charAt(i + 1) == '/' || line.charAt(i + 1) == '*');
        }

        private static int findStringEnd(String line, int start, char quote) {
            int j = start + 1;
            while (j < line.length()) {
                char c = line.charAt(j);
                if (c == '\\') {
                    j += 2;
                    continue;
                }
                if (c == quote) return j;
                j++;
            }
            return -1;
        }
    }
}
