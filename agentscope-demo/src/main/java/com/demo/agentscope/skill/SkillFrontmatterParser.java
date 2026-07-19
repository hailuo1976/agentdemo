package com.demo.agentscope.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 极简 YAML frontmatter 解析/序列化器。
 * <p>
 * 仅支持 flat key:value 与 list，不处理嵌套对象。移植自 Claude Code
 * {@code frontmatterParser.ts} 的正则与 {@code quoteProblematicValues} 容错策略。
 * </p>
 *
 * <h3>格式</h3>
 * <pre>
 * ---
 * id: sk_xxx
 * name: 股票龙头筛选
 * tags:
 *   - 股票
 *   - 量化
 * status: PUBLISHED
 * ---
 *
 * # 技能正文（Markdown）
 * </pre>
 *
 * <h3>容错策略</h3>
 * 扫描 {@code key: value} 行，若 value 含 {@code : }（冒号空格）、{@code {}}、{@code []}、
 * {@code #}、{@code &}、{@code !}、{@code |>}、{@code @} 等 YAML 特殊字符且未加引号，
 * 自动用双引号包裹并转义内嵌双引号。重试一次解析；仍失败则按 raw text 收集（不抛异常）。
 */
public final class SkillFrontmatterParser {

    private SkillFrontmatterParser() {}

    /** frontmatter 起止标志。 */
    public static final String DELIMITER = "---";

    /** frontmatter 正则：{@code ^---\s*\n([\s\S]*?)---\s*\n?}（移植自 Claude Code frontmatterParser.ts:123）。 */
    public static final String FRONTMATTER_REGEX = "^---\\s*\\n([\\s\\S]*?)---\\s*\\n?";

    /** 值中含以下任意字符时触发自动加引号（YAML 保留字符）。注意 {@code :} 单独不算，仅 {@code ": "}（冒号空格）触发。 */
    private static final String DANGEROUS_CHARS = "{}#&!|>%@`\\";

    /**
     * 解析结果。
     *
     * @param frontmatter flat 键值对（list 值为 {@code List<String>}）
     * @param body        frontmatter 之后的正文（含分隔符前的换行已被截掉）；若无 frontmatter，body 为原文
     * @param rawFrontmatter 原始 frontmatter 文本（含 {@code ---} 行），无 frontmatter 时为 ""
     */
    public record Parsed(Map<String, Object> frontmatter, String body, String rawFrontmatter) {
        public Parsed {
            frontmatter = frontmatter != null ? frontmatter : new LinkedHashMap<>();
            body = body != null ? body : "";
            rawFrontmatter = rawFrontmatter != null ? rawFrontmatter : "";
        }
    }

    // ==================== 解析 ====================

    /**
     * 解析 Markdown 文本，分离 frontmatter 与正文。
     * <p>无 frontmatter 时返回 empty frontmatter + body=原文。</p>
     */
    public static Parsed parse(String md) {
        Objects.requireNonNull(md, "md");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(FRONTMATTER_REGEX, java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = p.matcher(md);
        if (!m.find()) {
            return new Parsed(new LinkedHashMap<>(), md, "");
        }
        String fmRaw = m.group(1);
        int bodyStart = m.end();
        String body = bodyStart < md.length() ? md.substring(bodyStart) : "";
        Map<String, Object> fm = parseFrontmatterBlock(fmRaw);
        return new Parsed(fm, body, m.group(0));
    }

    /**
     * 解析 frontmatter 文本块（不含 {@code ---} 行）。
     * <p>若直接解析失败，套用 {@code quoteProblematicValues} 重试一次；仍失败则降级为 raw 行收集。</p>
     */
    private static Map<String, Object> parseFrontmatterBlock(String block) {
        try {
            return parseFlat(block);
        } catch (RuntimeException ex) {
            // 重试：对可疑行自动加引号
            try {
                return parseFlat(quoteProblematicValues(block));
            } catch (RuntimeException ex2) {
                return parseRawFallback(block);
            }
        }
    }

    /**
     * 解析 flat key:value + list block。
     */
    private static Map<String, Object> parseFlat(String block) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] lines = block.split("\n", -1);
        String currentKey = null;
        for (String rawLine : lines) {
            if (rawLine == null) continue;
            // 保留缩进判断：list 项必须以空白字符 + "-" 开头
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isBlank()) continue;
            if (line.trim().startsWith("#")) continue; // 注释

            // list item
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                if (currentKey == null) {
                    throw new RuntimeException("list 项缺少 key: " + line);
                }
                String item = trimmed.length() > 1 ? trimmed.substring(2).trim() : "";
                item = unquote(item);
                Object existing = out.get(currentKey);
                List<String> list;
                if (existing instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> raw = (List<String>) existing;
                    list = raw;
                } else {
                    list = new ArrayList<>();
                    out.put(currentKey, list);
                }
                list.add(item);
                continue;
            }

            // key: value
            int colon = indexOfColon(line);
            if (colon < 0) {
                throw new RuntimeException("无法解析的行: " + line);
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (key.isEmpty()) {
                throw new RuntimeException("key 为空: " + line);
            }
            currentKey = key;
            if (value.isEmpty()) {
                // 可能是 list 起始（后续行是 - item），暂存为空 list
                out.putIfAbsent(key, new ArrayList<>());
            } else {
                out.put(key, unquote(value));
            }
        }
        return out;
    }

    /** 查找 key-value 分隔冒号（第一个未被引号包裹的 ":"）。 */
    private static int indexOfColon(String line) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ':' && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    /** 去除值两端的双引号/单引号并反转义。 */
    private static String unquote(String value) {
        if (value == null) return null;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    /**
     * 对含 YAML 特殊字符的 value 自动加双引号（移植自 Claude Code quoteProblematicValues）。
     */
    static String quoteProblematicValues(String block) {
        String[] lines = block.split("\n", -1);
        StringBuilder sb = new StringBuilder(block.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                sb.append(raw);
            } else {
                int colon = indexOfColon(line);
                if (colon < 0) {
                    sb.append(raw);
                } else {
                    String key = line.substring(0, colon);
                    String value = line.substring(colon + 1).trim();
                    if (!value.isEmpty() && !isAlreadyQuoted(value) && containsDangerousChar(value)) {
                        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
                        sb.append(key).append(": \"").append(escaped).append("\"");
                    } else {
                        sb.append(raw);
                    }
                }
            }
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private static boolean isAlreadyQuoted(String value) {
        return (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
                || (value.startsWith("'") && value.endsWith("'") && value.length() >= 2);
    }

    private static boolean containsDangerousChar(String value) {
        // 冒号空格（YAML map 嵌套）、花括号、方括号、注释符、锚点引用、块标量等
        if (value.contains(": ") || value.endsWith(":")) return true;
        for (int i = 0; i < value.length(); i++) {
            if (DANGEROUS_CHARS.indexOf(value.charAt(i)) >= 0) return true;
        }
        return false;
    }

    /**
     * 降级解析：容忍任何非法行，按 raw key:value（无 list）尽力而为。
     */
    private static Map<String, Object> parseRawFallback(String block) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String rawLine : block.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isBlank()) continue;
            int colon = indexOfColon(line);
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, unquote(value));
            }
        }
        return out;
    }

    // ==================== 序列化 ====================

    /**
     * 把 frontmatter + body 重新序列化为 Markdown 文本。
     * <p>list 字段输出为 YAML list 形态（每行 {@code - item}）；含特殊字符的 value 自动加双引号。</p>
     */
    public static String serialize(Map<String, Object> frontmatter, String body) {
        Objects.requireNonNull(frontmatter, "frontmatter");
        String bodyStr = body == null ? "" : body;
        StringBuilder sb = new StringBuilder();
        if (!frontmatter.isEmpty()) {
            sb.append(DELIMITER).append("\n");
            for (Map.Entry<String, Object> e : frontmatter.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                if (value instanceof List<?> list) {
                    sb.append(key).append(":");
                    if (list.isEmpty()) {
                        sb.append(" []");
                    } else {
                        for (Object item : list) {
                            sb.append("\n  - ").append(formatValue(String.valueOf(item)));
                        }
                    }
                    sb.append("\n");
                } else if (value == null) {
                    sb.append(key).append(": null\n");
                } else {
                    sb.append(key).append(": ").append(formatValue(String.valueOf(value))).append("\n");
                }
            }
            sb.append(DELIMITER).append("\n\n");
        }
        sb.append(bodyStr);
        return sb.toString();
    }

    /**
     * 序列化时的值格式化：含特殊字符自动加双引号，保证 round-trip。
     */
    private static String formatValue(String value) {
        if (value == null || value.isEmpty()) return "\"\"";
        if (containsDangerousChar(value)) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }
}
