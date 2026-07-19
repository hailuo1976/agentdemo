package com.demo.agentscope.skill;

import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.ui.ConsoleUI;
import com.demo.agentscope.ui.markdown.MarkdownRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理工具装配入口。
 * <p>
 * 把 {@link SkillManager} 的业务 API 包装成 12 个 MCP 工具注册到 {@link MCPClient}，
 * 仿照 {@code StockToolService} 的「服务层 + registerTools(MCPClient)」范式。
 * </p>
 *
 * <h3>返回值约定</h3>
 * <ul>
 *   <li>每个工具返回紧凑 JSON 串（{@code {"status":"ok", ...}} 或 {@code {"status":"error",...}}）</li>
 *   <li>{@code skill_stats} 同时把渲染好的 Markdown 打印到 stdout（仿照 {@code MarkdownToolExecutor}），返回值只带摘要</li>
 *   <li>异常一律 catch 在执行器内部，转 {@code status=error}，不让框架感知</li>
 * </ul>
 *
 * <h3>路径解析</h3>
 * {@code target_dir} / {@code source_dir} 支持相对路径（相对于 {@code basePath}，通常为 {@code workspace/}）。
 */
public class SkillToolService {

    private static final Logger log = LoggerFactory.getLogger(SkillToolService.class);

    private final SkillManager manager;
    private final Path basePath;
    private final ObjectMapper objectMapper;

    public SkillToolService(SkillManager manager) {
        this(manager, Paths.get("workspace"));
    }

    public SkillToolService(SkillManager manager, Path basePath) {
        this.manager = manager;
        this.basePath = basePath != null ? basePath : Paths.get("workspace");
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** 把 12 个工具注册到 MCPClient。 */
    public void registerTools(MCPClient mcpClient) {
        registerCreate(mcpClient);
        registerGet(mcpClient);
        registerList(mcpClient);
        registerUpdate(mcpClient);
        registerDelete(mcpClient);
        registerSearch(mcpClient);
        registerPublish(mcpClient);
        registerDeprecate(mcpClient);
        registerHistory(mcpClient);
        registerExport(mcpClient);
        registerImport(mcpClient);
        registerStats(mcpClient);
        log.info("技能管理工具已注册到 MCPClient（{} 个）", SkillConstants.TOOL_NAMES.size());
    }

    // ==================== 工具实现 ====================

    private void registerCreate(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_CREATE,
                "创建一个新的技能条目（status=DRAFT, version=1）。技能用于沉淀 Agent 的可复用经验。",
                SkillToolSchemas.CREATE,
                args -> {
                    Skill input = new Skill();
                    input.setName(str(args, "name"));
                    input.setDescription(str(args, "description"));
                    input.setTags(strList(args, "tags"));
                    input.setSteps(strList(args, "steps"));
                    input.setCases(strList(args, "cases"));
                    input.setSuccessCases(strList(args, "successCases"));
                    input.setResources(strList(args, "resources"));
                    Skill created = manager.create(input);
                    return okSummary(created);
                });
    }

    private void registerGet(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_GET,
                "按 ID 读取技能完整内容（同时自增 useCount）。",
                SkillToolSchemas.GET,
                args -> {
                    String id = str(args, "id");
                    Skill s = manager.get(id);
                    return toJson(s);
                });
    }

    private void registerList(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_LIST,
                "列出技能（支持 tag/status 过滤与 limit 截断），返回摘要列表。",
                SkillToolSchemas.LIST,
                args -> {
                    String tag = strOrNull(args, "tag");
                    String status = strOrNull(args, "status");
                    int limit = intOr(args, "limit", 50);
                    List<SkillStore.SkillSummary> list = manager.list(tag, status, limit);
                    Map<String, Object> out = new HashMap<>();
                    out.put("status", "ok");
                    out.put("count", list.size());
                    out.put("items", list);
                    return toJson(out);
                });
    }

    private void registerUpdate(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_UPDATE,
                "更新技能字段（version 自增，旧版归档）。传入字段覆盖，未传字段保留。",
                SkillToolSchemas.UPDATE,
                args -> {
                    String id = str(args, "id");
                    Skill patch = new Skill();
                    if (args.containsKey("name")) patch.setName(strOrNull(args, "name"));
                    if (args.containsKey("description")) patch.setDescription(strOrNull(args, "description"));
                    if (args.containsKey("tags")) patch.setTags(strList(args, "tags"));
                    if (args.containsKey("steps")) patch.setSteps(strList(args, "steps"));
                    if (args.containsKey("cases")) patch.setCases(strList(args, "cases"));
                    if (args.containsKey("successCases")) patch.setSuccessCases(strList(args, "successCases"));
                    if (args.containsKey("resources")) patch.setResources(strList(args, "resources"));
                    Skill updated = manager.update(id, patch);
                    return okSummary(updated);
                });
    }

    private void registerDelete(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_DELETE,
                "软删除技能（status → DEPRECATED）。必须传 confirm=true 才执行。",
                SkillToolSchemas.DELETE,
                args -> {
                    String id = str(args, "id");
                    boolean confirm = boolOr(args, "confirm", false);
                    Skill d = manager.delete(id, confirm);
                    return okSummary(d);
                });
    }

    private void registerSearch(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_SEARCH,
                "按 query 检索技能（评分排序：name > tag > description > steps，PUBLISHED 额外加分）。无精确命中时自动子序列兜底。",
                SkillToolSchemas.SEARCH,
                args -> {
                    String query = str(args, "query");
                    String tag = strOrNull(args, "tag");
                    int limit = intOr(args, "limit", 10);
                    List<SkillSearcher.Result> results = manager.search(query, tag, limit);
                    List<Map<String, Object>> items = new ArrayList<>(results.size());
                    for (SkillSearcher.Result r : results) {
                        Map<String, Object> row = new HashMap<>();
                        Skill s = r.skill();
                        row.put("id", s.getId());
                        row.put("name", s.getName());
                        row.put("slug", s.getSlug());
                        row.put("status", s.getStatus() == null ? null : s.getStatus().name());
                        row.put("tags", s.getTags());
                        row.put("useCount", s.getUseCount());
                        row.put("score", r.score());
                        row.put("fallback", r.fallback());
                        items.add(row);
                    }
                    Map<String, Object> out = new HashMap<>();
                    out.put("status", "ok");
                    out.put("count", items.size());
                    out.put("items", items);
                    return toJson(out);
                });
    }

    private void registerPublish(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_PUBLISH,
                "发布技能：DRAFT → PUBLISHED。",
                SkillToolSchemas.PUBLISH,
                args -> {
                    String id = str(args, "id");
                    Skill s = manager.publish(id);
                    return okSummary(s);
                });
    }

    private void registerDeprecate(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_DEPRECATE,
                "弃用技能：任意状态 → DEPRECATED。",
                SkillToolSchemas.DEPRECATE,
                args -> {
                    String id = str(args, "id");
                    Skill s = manager.deprecate(id);
                    return okSummary(s);
                });
    }

    private void registerHistory(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_HISTORY,
                "查看技能的版本历史。",
                SkillToolSchemas.HISTORY,
                args -> {
                    String id = str(args, "id");
                    List<SkillStore.VersionEntry> versions = manager.history(id);
                    Map<String, Object> out = new HashMap<>();
                    out.put("status", "ok");
                    out.put("id", id);
                    out.put("count", versions.size());
                    out.put("versions", versions);
                    return toJson(out);
                });
    }

    private void registerExport(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_EXPORT,
                "把技能导出为 Markdown 文件（含 YAML frontmatter）到目标目录。",
                SkillToolSchemas.EXPORT,
                args -> {
                    Path targetDir = resolvePath(str(args, "target_dir"));
                    String tag = strOrNull(args, "tag");
                    List<String> ids = strList(args, "ids");
                    List<String> written = manager.exportTo(targetDir, tag, ids);
                    Map<String, Object> out = new HashMap<>();
                    out.put("status", "ok");
                    out.put("targetDir", targetDir.toString());
                    out.put("count", written.size());
                    out.put("files", written);
                    return toJson(out);
                });
    }

    private void registerImport(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_IMPORT,
                "从源目录扫描 .md 文件并导入为技能。",
                SkillToolSchemas.IMPORT,
                args -> {
                    Path sourceDir = resolvePath(str(args, "source_dir"));
                    List<Map<String, String>> rows = manager.importFrom(sourceDir);
                    Map<String, Object> out = new HashMap<>();
                    out.put("status", "ok");
                    out.put("sourceDir", sourceDir.toString());
                    out.put("count", rows.size());
                    out.put("items", rows);
                    return toJson(out);
                });
    }

    private void registerStats(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                SkillConstants.TOOL_SKILL_STATS,
                "技能使用统计（含每日访问量柱状图、Top 技能与动作分布），渲染到终端。",
                SkillToolSchemas.STATS,
                args -> {
                    String period = strOr(args, "period", "all");
                    SkillStatsAggregator.Result r = manager.stats(period);
                    String md = manager.renderStatsMarkdown(r);
                    // 渲染到 stdout（仿 MarkdownToolExecutor 模式）
                    int width = ConsoleUI.getTerminalWidth();
                    try {
                        String rendered = MarkdownRenderer.render(md, "dark", width);
                        System.out.print(rendered);
                    } catch (Throwable t) {
                        // 渲染失败 fallback 到纯文本
                        System.out.print(md);
                    }
                    // 返回短摘要
                    return "{\"status\":\"ok\",\"period\":\""
                            + escape(r.period().label())
                            + "\",\"totalSkills\":" + r.overview().totalSkills()
                            + ",\"totalAccesses\":" + r.overview().totalAccesses() + "}";
                });
    }

    // ==================== 工具函数 ====================

    /** 为 create/update/publish 等返回紧凑摘要 JSON（避免回传完整 description/steps，减少 token 占用）。 */
    private String okSummary(Skill s) {
        Map<String, Object> out = new HashMap<>();
        out.put("status", "ok");
        out.put("id", s.getId());
        out.put("slug", s.getSlug());
        out.put("name", s.getName());
        // 注意：技能状态用 skillStatus 而非 status，避免与外层 "status":"ok" 冲突
        out.put("skillStatus", s.getStatus() == null ? null : s.getStatus().name());
        out.put("version", s.getVersion());
        out.put("tags", s.getTags());
        out.put("useCount", s.getUseCount());
        return toJson(out);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error\":\"序列化失败: "
                    + escape(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "\"}";
        }
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) throw new SkillStoreException.Invalid("缺少必填参数: " + key);
        return String.valueOf(v).trim();
    }

    private static String strOr(Map<String, Object> args, String key, String defaultVal) {
        Object v = args.get(key);
        return v == null ? defaultVal : String.valueOf(v).trim();
    }

    private static String strOrNull(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int intOr(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static boolean boolOr(Map<String, Object> args, String key, boolean defaultVal) {
        Object v = args.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static List<String> strList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return new ArrayList<>();
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        // 单值也兼容
        List<String> out = new ArrayList<>(1);
        out.add(String.valueOf(v));
        return out;
    }

    /** 解析路径：绝对路径原样返回；相对路径基于 {@link #basePath} 解析。 */
    private Path resolvePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SkillStoreException.Invalid("路径参数为空");
        }
        Path p = Paths.get(raw.trim());
        return p.isAbsolute() ? p : basePath.resolve(p).normalize();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
