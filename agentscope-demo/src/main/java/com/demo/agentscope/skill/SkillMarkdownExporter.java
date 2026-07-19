package com.demo.agentscope.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Skill ↔ Markdown（YAML frontmatter + 正文）互转器。
 * <p>
 * 用于 {@code skill_export} / {@code skill_import} 两个工具的序列化层。
 * </p>
 *
 * <h3>导出格式</h3>
 * <pre>
 * ---
 * id: sk_xxx
 * name: 股票龙头筛选
 * slug: stock-leader-screen
 * tags:
 *   - 股票
 * status: PUBLISHED
 * version: 3
 * createdAt: 2026-07-19T08:00:00Z
 * updatedAt: 2026-07-19T10:30:00Z
 * publishedAt: 2026-07-19T09:00:00Z
 * useCount: 42
 * ---
 *
 * # 股票龙头筛选
 *
 * ## 描述
 * ...
 *
 * ## 实施步骤
 * 1. ...
 *
 * ## 应用场景
 * - ...
 *
 * ## 成功案例
 * - ...
 *
 * ## 相关资源
 * - ...
 * </pre>
 */
public class SkillMarkdownExporter {

    private SkillMarkdownExporter() {}

    /** body 中各段落的标题（中文）。 */
    public static final String SECTION_DESCRIPTION = "描述";
    public static final String SECTION_STEPS = "实施步骤";
    public static final String SECTION_CASES = "应用场景";
    public static final String SECTION_SUCCESS_CASES = "成功案例";
    public static final String SECTION_RESOURCES = "相关资源";

    /**
     * 把一个 Skill 导出为 Markdown 字符串。
     */
    public static String exportSkill(Skill skill) {
        Objects.requireNonNull(skill, "skill");
        Map<String, Object> fm = buildFrontmatter(skill);
        String body = buildBody(skill);
        return SkillFrontmatterParser.serialize(fm, body);
    }

    /**
     * 从 Markdown 文本反向构造 Skill。
     * <p>不会填充 createdAt/updatedAt（若 frontmatter 缺失），由调用方决定是否补默认值。</p>
     */
    public static Skill importSkill(String md) {
        Objects.requireNonNull(md, "md");
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        Skill s = new Skill();

        // frontmatter -> 字段
        s.setId(getStr(p.frontmatter(), "id"));
        s.setSlug(getStr(p.frontmatter(), "slug"));
        s.setName(getStr(p.frontmatter(), "name"));
        s.setStatus(parseStatus(getStr(p.frontmatter(), "status")));
        String verStr = getStr(p.frontmatter(), "version");
        if (verStr != null) {
            try { s.setVersion(Integer.parseInt(verStr.trim())); } catch (NumberFormatException ignored) {}
        }
        s.setAuthor(getStr(p.frontmatter(), "author"));
        s.setVisibility(getStr(p.frontmatter(), "visibility"));
        s.setCreatedAt(parseInstant(getStr(p.frontmatter(), "createdAt")));
        s.setUpdatedAt(parseInstant(getStr(p.frontmatter(), "updatedAt")));
        s.setPublishedAt(parseInstant(getStr(p.frontmatter(), "publishedAt")));
        s.setLastUsedAt(parseInstant(getStr(p.frontmatter(), "lastUsedAt")));
        String useCountStr = getStr(p.frontmatter(), "useCount");
        if (useCountStr != null) {
            try { s.setUseCount(Integer.parseInt(useCountStr.trim())); } catch (NumberFormatException ignored) {}
        }
        s.setTags(getStringList(p.frontmatter(), "tags"));

        // body -> 各段落
        parseBody(p.body(), s);

        return s;
    }

    /**
     * 推荐的导出文件名（基于 slug，带 {@code .md} 后缀）。
     */
    public static String suggestFileName(Skill skill) {
        Objects.requireNonNull(skill, "skill");
        String slug = skill.getSlug();
        if (slug == null || slug.isBlank()) {
            slug = skill.getName() != null ? SkillStore.slugify(skill.getName()) : "skill";
        }
        return slug + ".md";
    }

    // ==================== 内部：导出 ====================

    private static Map<String, Object> buildFrontmatter(Skill skill) {
        Map<String, Object> fm = new LinkedHashMap<>();
        if (skill.getId() != null) fm.put("id", skill.getId());
        if (skill.getSlug() != null) fm.put("slug", skill.getSlug());
        if (skill.getName() != null) fm.put("name", skill.getName());
        if (skill.getTags() != null && !skill.getTags().isEmpty()) {
            fm.put("tags", new ArrayList<>(skill.getTags()));
        }
        if (skill.getStatus() != null) fm.put("status", skill.getStatus().name());
        fm.put("version", skill.getVersion());
        if (skill.getAuthor() != null) fm.put("author", skill.getAuthor());
        if (skill.getVisibility() != null) fm.put("visibility", skill.getVisibility());
        if (skill.getCreatedAt() != null) fm.put("createdAt", skill.getCreatedAt().toString());
        if (skill.getUpdatedAt() != null) fm.put("updatedAt", skill.getUpdatedAt().toString());
        if (skill.getPublishedAt() != null) fm.put("publishedAt", skill.getPublishedAt().toString());
        if (skill.getLastUsedAt() != null) fm.put("lastUsedAt", skill.getLastUsedAt().toString());
        fm.put("useCount", skill.getUseCount());
        return fm;
    }

    private static String buildBody(Skill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(skill.getName() != null ? skill.getName() : "").append("\n\n");

        appendSection(sb, SECTION_DESCRIPTION, skill.getDescription(), false);
        appendNumberedSection(sb, SECTION_STEPS, skill.getSteps());
        appendSection(sb, SECTION_CASES, skill.getCases(), true);
        appendSection(sb, SECTION_SUCCESS_CASES, skill.getSuccessCases(), true);
        appendSection(sb, SECTION_RESOURCES, skill.getResources(), true);
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, String content, boolean asBulletList) {
        if (content == null || content.isBlank()) return;
        sb.append("## ").append(title).append("\n");
        if (asBulletList) {
            for (String line : content.split("\n")) {
                if (!line.isBlank()) sb.append("- ").append(line.trim()).append("\n");
            }
        } else {
            sb.append(content.stripTrailing()).append("\n");
        }
        sb.append("\n");
    }

    private static void appendSection(StringBuilder sb, String title, List<String> items, boolean asBulletList) {
        if (items == null || items.isEmpty()) return;
        sb.append("## ").append(title).append("\n");
        if (asBulletList) {
            for (String it : items) {
                if (it != null && !it.isBlank()) sb.append("- ").append(it.trim()).append("\n");
            }
        } else {
            for (String it : items) {
                if (it != null) sb.append(it.stripTrailing()).append("\n");
            }
        }
        sb.append("\n");
    }

    private static void appendNumberedSection(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) return;
        sb.append("## ").append(title).append("\n");
        int i = 1;
        for (String it : items) {
            if (it != null && !it.isBlank()) {
                sb.append(i++).append(". ").append(it.trim()).append("\n");
            }
        }
        sb.append("\n");
    }

    // ==================== 内部：导入 ====================

    private static void parseBody(String body, Skill skill) {
        if (body == null || body.isBlank()) return;
        // 切分到 H2 段落
        String[] parts = body.split("(?m)^##\\s+");
        // parts[0] 是第一个 ## 之前的内容（含 # 标题）
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int newlineIdx = part.indexOf('\n');
            String heading = newlineIdx < 0 ? part.trim() : part.substring(0, newlineIdx).trim();
            String content = newlineIdx < 0 ? "" : part.substring(newlineIdx + 1).trim();
            switch (heading) {
                case SECTION_DESCRIPTION -> skill.setDescription(content);
                case SECTION_STEPS -> skill.setSteps(parseNumberedOrBulleted(content));
                case SECTION_CASES -> skill.setCases(parseBulleted(content));
                case SECTION_SUCCESS_CASES -> skill.setSuccessCases(parseBulleted(content));
                case SECTION_RESOURCES -> skill.setResources(parseBulleted(content));
                default -> { /* 忽略未知段落 */ }
            }
        }

        // # 一级标题若未提供 name，用标题兜底
        if (skill.getName() == null) {
            String h1 = extractH1(body);
            if (h1 != null) skill.setName(h1);
        }
    }

    private static String extractH1(String body) {
        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return null;
    }

    private static List<String> parseNumberedOrBulleted(String content) {
        List<String> out = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String text = stripListMarker(trimmed);
            if (text != null && !text.isEmpty()) out.add(text);
        }
        return out;
    }

    private static List<String> parseBulleted(String content) {
        List<String> out = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String text = stripListMarker(trimmed);
            if (text != null && !text.isEmpty()) out.add(text);
        }
        return out;
    }

    private static String stripListMarker(String line) {
        if (line.startsWith("- ")) return line.substring(2).trim();
        if (line.startsWith("* ")) return line.substring(2).trim();
        // numbered: "1. xxx"
        int dot = line.indexOf(". ");
        if (dot > 0 && line.substring(0, dot).matches("\\d+")) {
            return line.substring(dot + 2).trim();
        }
        if (line.equals("-") || line.equals("*")) return "";
        return line; // 无 marker，整行作为内容
    }

    private static String getStr(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object item : (List<Object>) v) {
                if (item != null) out.add(String.valueOf(item));
            }
            return out;
        }
        return new ArrayList<>();
    }

    private static SkillStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try { return SkillStatus.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s.trim()); }
        catch (Exception e) { return null; }
    }
}
