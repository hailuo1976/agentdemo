package com.demo.agentscope.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 技能服务层：编排 {@link SkillStore} / {@link SkillSearcher} /
 * {@link SkillStatsAggregator} / {@link SkillMarkdownExporter}，对外提供业务级 API。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>字段校验与 slug 规范化</li>
 *   <li>access.log 自动写入（每次读/写/搜索/列表都记一条）</li>
 *   <li>list 过滤（tag/status）与 limit 截断</li>
 *   <li>search 包装：返回带 {@code fallback} 标识的结果</li>
 *   <li>export/import：批量写/读 .md 文件</li>
 *   <li>stats：调用 {@link SkillStatsAggregator} + 构造 Markdown 文本</li>
 * </ul>
 *
 * <h3>actor 字段</h3>
 * 业务侧不强制 RBAC，仅把调用方（如 agent 名）作为 {@code actor} 透传到 access.log，
 * 便于审计与未来扩展。
 */
public class SkillManager {

    private final SkillStore store;
    private final String defaultActor;

    public SkillManager(Path skillsRoot) {
        this(skillsRoot, "agent");
    }

    public SkillManager(Path skillsRoot, String defaultActor) {
        this.store = new SkillStore(skillsRoot);
        this.defaultActor = defaultActor != null ? defaultActor : "agent";
    }

    public SkillManager(SkillStore store, String defaultActor) {
        this.store = Objects.requireNonNull(store, "store");
        this.defaultActor = defaultActor != null ? defaultActor : "agent";
    }

    public SkillStore getStore() { return store; }

    // ==================== CRUD ====================

    public Skill create(Skill input) {
        normalizeOnCreate(input);
        Skill created = store.create(input);
        append("create", created.getId(), null);
        return created;
    }

    public Skill get(String id) {
        Skill s = store.load(id);
        store.incrementUse(id);
        append("get", id, null);
        return s;
    }

    public Skill update(String id, Skill patch) {
        if (patch == null) patch = new Skill();
        Skill updated = store.update(id, patch);
        append("update", id, null);
        return updated;
    }

    /** 软删除（status → DEPRECATED）。需要 {@code confirm=true} 才执行。 */
    public Skill delete(String id, boolean confirm) {
        if (!confirm) {
            throw new SkillStoreException.Invalid("删除技能需要 confirm=true（软删除到 DEPRECATED）");
        }
        Skill d = store.deprecate(id);
        append("delete", id, null);
        return d;
    }

    public Skill publish(String id) {
        Skill s = store.publish(id);
        append("publish", id, null);
        return s;
    }

    public Skill deprecate(String id) {
        Skill s = store.deprecate(id);
        append("deprecate", id, null);
        return s;
    }

    public List<SkillStore.VersionEntry> history(String id) {
        Skill current = store.load(id); // 触发 NotFound
        append("history", id, null);
        // listVersions 只返回 versions/{id}/ 下归档的旧版本；
        // 把当前 manifest 也作为一条合成 VersionEntry 加在末尾，让用户看到完整版本序列。
        List<SkillStore.VersionEntry> archived = store.listVersions(id);
        List<SkillStore.VersionEntry> out = new ArrayList<>(archived);
        out.add(new SkillStore.VersionEntry(current.getVersion(), null, current.getUpdatedAt()));
        return out;
    }

    // ==================== list / search ====================

    public List<SkillStore.SkillSummary> list(String tag, String statusStr, int limit) {
        List<SkillStore.SkillSummary> all = store.listSummaries();
        List<SkillStore.SkillSummary> filtered = new ArrayList<>();
        SkillStatus statusFilter = parseStatus(statusStr);
        for (SkillStore.SkillSummary s : all) {
            if (statusFilter != null && s.status != statusFilter) continue;
            if (tag != null && !tag.isBlank() && (s.tags == null || !s.tags.contains(tag))) continue;
            filtered.add(s);
        }
        if (limit > 0 && filtered.size() > limit) {
            filtered = new ArrayList<>(filtered.subList(0, limit));
        }
        append("list", null, null);
        return filtered;
    }

    public List<SkillSearcher.Result> search(String query, String tag, int limit) {
        List<Skill> all = store.listAll();
        // 先按 tag 过滤再交给 Searcher
        List<Skill> candidates;
        if (tag != null && !tag.isBlank()) {
            candidates = new ArrayList<>();
            for (Skill s : all) {
                if (s.getTags() != null && s.getTags().contains(tag)) candidates.add(s);
            }
        } else {
            candidates = all;
        }
        List<SkillSearcher.Result> results = SkillSearcher.search(candidates, query, limit);
        append("search", null, query);
        return results;
    }

    // ==================== export / import ====================

    /**
     * 把符合条件的技能导出为 .md 文件到 {@code targetDir}。
     * @param targetDir 目标目录（不存在则创建）
     * @param tag       可选 tag 过滤；null/blank 导全部
     * @param ids       可选 id 白名单；非空时只导这些 id
     * @return 已导出的文件路径列表（相对路径便于回显）
     */
    public List<String> exportTo(Path targetDir, String tag, List<String> ids) throws IOException {
        Objects.requireNonNull(targetDir, "targetDir");
        Files.createDirectories(targetDir);
        List<Skill> targets = new ArrayList<>();
        for (Skill s : store.listAll()) {
            if (ids != null && !ids.isEmpty()) {
                if (s.getId() == null || !ids.contains(s.getId())) continue;
            }
            if (tag != null && !tag.isBlank()) {
                if (s.getTags() == null || !s.getTags().contains(tag)) continue;
            }
            targets.add(s);
        }

        List<String> written = new ArrayList<>();
        for (Skill s : targets) {
            String fileName = SkillMarkdownExporter.suggestFileName(s);
            // 防止文件名冲突
            Path target = targetDir.resolve(fileName);
            int dup = 1;
            while (Files.exists(target)) {
                String stem = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
                target = targetDir.resolve(stem + "-" + (dup++) + ".md");
            }
            String md = SkillMarkdownExporter.exportSkill(s);
            Files.writeString(target, md, StandardCharsets.UTF_8);
            written.add(target.getFileName().toString());
        }
        append("export", null, null);
        return written;
    }

    /**
     * 从 {@code sourceDir} 扫描所有 .md 文件并 upsert。
     * @return 每个文件的处理结果（{@code {file, action, id?}}）
     */
    public List<Map<String, String>> importFrom(Path sourceDir) throws IOException {
        Objects.requireNonNull(sourceDir, "sourceDir");
        if (!Files.isDirectory(sourceDir)) {
            throw new SkillStoreException.IO("sourceDir 不是目录: " + sourceDir, null);
        }
        List<Map<String, String>> results = new ArrayList<>();
        try (Stream<Path> stream = Files.list(sourceDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                  .sorted()
                  .forEach(p -> {
                      Map<String, String> row = new java.util.LinkedHashMap<>();
                      row.put("file", p.getFileName().toString());
                      try {
                          String md = Files.readString(p, StandardCharsets.UTF_8);
                          Skill parsed = SkillMarkdownExporter.importSkill(md);
                          if (parsed.getName() == null || parsed.getName().isBlank()) {
                              row.put("action", "skipped");
                              row.put("reason", "name 缺失");
                          } else {
                              Skill created = store.create(parsed);
                              row.put("action", "created");
                              row.put("id", created.getId());
                          }
                      } catch (SkillStoreException e) {
                          row.put("action", "error");
                          row.put("reason", e.getMessage());
                      } catch (IOException e) {
                          row.put("action", "error");
                          row.put("reason", "读取失败: " + e.getMessage());
                      }
                      results.add(row);
                  });
        }
        append("import", null, null);
        return results;
    }

    // ==================== stats ====================

    public SkillStatsAggregator.Result stats(String periodStr) {
        SkillStatsAggregator.Period period = SkillStatsAggregator.Period.parse(periodStr);
        SkillStatsAggregator.Result r =
                SkillStatsAggregator.aggregate(store.listAll(), store.readAccessLog(), period);
        append("stats", null, null);
        return r;
    }

    /** 把 stats 结果构造为 Markdown 文本（用于 {@code skill_stats} 终端渲染）。 */
    public String renderStatsMarkdown(SkillStatsAggregator.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 技能统计\n\n");

        SkillStatsAggregator.Overview o = r.overview();
        sb.append("## 总览\n\n");
        sb.append("| 指标 | 值 |\n");
        sb.append("|---|---|\n");
        sb.append("| 周期 | ").append(r.period().label()).append(" |\n");
        sb.append("| 技能总数 | ").append(o.totalSkills()).append(" |\n");
        sb.append("| DRAFT | ").append(o.draft()).append(" |\n");
        sb.append("| PUBLISHED | ").append(o.published()).append(" |\n");
        sb.append("| DEPRECATED | ").append(o.deprecated()).append(" |\n");
        sb.append("| 访问总数 | ").append(o.totalAccesses()).append(" |\n\n");

        // byDay ASCII 柱状图
        if (!r.byDay().isEmpty()) {
            sb.append("## 每日访问量\n\n");
            sb.append("```\n");
            long max = r.byDay().values().stream().mapToLong(Long::longValue).max().orElse(1L);
            for (Map.Entry<String, Long> e : r.byDay().entrySet()) {
                int barLen = max > 0 ? (int) Math.ceil(e.getValue() * 20.0 / max) : 0;
                StringBuilder bar = new StringBuilder();
                for (int i = 0; i < barLen; i++) bar.append('#');
                sb.append(String.format("%s  %-20s %d%n", e.getKey(), bar, e.getValue()));
            }
            sb.append("```\n\n");
        }

        if (!r.topSkills().isEmpty()) {
            sb.append("## Top 技能\n\n");
            sb.append("| 技能 ID | 访问次数 |\n");
            sb.append("|---|---|\n");
            for (SkillStatsAggregator.CountEntry e : r.topSkills()) {
                sb.append("| ").append(e.key()).append(" | ").append(e.count()).append(" |\n");
            }
            sb.append("\n");
        }

        if (!r.topActions().isEmpty()) {
            sb.append("## 动作分布\n\n");
            sb.append("| 动作 | 次数 |\n");
            sb.append("|---|---|\n");
            for (SkillStatsAggregator.CountEntry e : r.topActions()) {
                sb.append("| ").append(e.key()).append(" | ").append(e.count()).append(" |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ==================== 内部 ====================

    private void normalizeOnCreate(Skill input) {
        if (input == null) throw new SkillStoreException.Invalid("input 不能为空");
        if (input.getName() == null || input.getName().isBlank()) {
            throw new SkillStoreException.Invalid("name 不能为空");
        }
        if (input.getName().length() > 80) {
            throw new SkillStoreException.Invalid("name 超过 80 字符上限");
        }
        if (input.getDescription() == null || input.getDescription().isBlank()) {
            throw new SkillStoreException.Invalid("description 不能为空");
        }
        if (input.getTags() != null) {
            // 去重 + trim
            List<String> dedup = new ArrayList<>();
            for (String t : input.getTags()) {
                if (t == null) continue;
                String trimmed = t.trim();
                if (!trimmed.isEmpty() && !dedup.contains(trimmed)) dedup.add(trimmed);
            }
            input.setTags(dedup);
        }
    }

    private void append(String action, String skillId, String query) {
        store.appendAccess(new SkillAccessEntry(Instant.now(), skillId, action, query, defaultActor));
    }

    private static SkillStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try { return SkillStatus.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
