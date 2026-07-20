package com.demo.agentscope.memory;

import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.ui.ConsoleUI;
import com.demo.agentscope.ui.markdown.MarkdownRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆管理工具装配入口。
 * <p>
 * 把 {@link ShortTermMemory} / {@link LongTermMemory} 的 API 包装成 6 个 MCP 工具注册到 {@link MCPClient}，
 * 仿照 {@code SkillToolService} 的「服务层 + registerTools(MCPClient)」范式。写入路径仅靠 LLM 主动调用
 * —— 不加 middleware 自动摘要，避免每次回复多一次 LLM 调用。
 * </p>
 *
 * <h3>返回值约定</h3>
 * <ul>
 *   <li>每个工具返回紧凑 JSON 串（{@code {"status":"ok", ...}} 或 {@code {"status":"error",...}}）</li>
 *   <li>{@code memory_stats} 同时把渲染好的 Markdown 打印到 stdout，返回值只带摘要</li>
 *   <li>异常一律 catch 在执行器内部，转 {@code status=error}，不让框架感知</li>
 * </ul>
 *
 * <h3>ID 规范</h3>
 * <ul>
 *   <li>短期记忆：{@code m_<uuid前8位>}</li>
 *   <li>长期记忆：{@code lt_<uuid前8位>}</li>
 * </ul>
 */
public class MemoryToolService {

    private static final Logger log = LoggerFactory.getLogger(MemoryToolService.class);

    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ObjectMapper objectMapper;

    public MemoryToolService(ShortTermMemory shortTermMemory, LongTermMemory longTermMemory) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** 把 6 个工具注册到 MCPClient。 */
    public void registerTools(MCPClient mcpClient) {
        registerStore(mcpClient);
        registerRecall(mcpClient);
        registerList(mcpClient);
        registerPromote(mcpClient);
        registerDelete(mcpClient);
        registerStats(mcpClient);
        log.info("记忆管理工具已注册到 MCPClient（{} 个）", MemoryConstants.TOOL_NAMES.size());
    }

    // ==================== 工具实现 ====================

    private void registerStore(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                MemoryConstants.TOOL_MEMORY_STORE,
                "写入一条记忆（跨会话经验/用户偏好/关键发现）。短期用 scope=short，长期偏好/领域知识用 scope=long。",
                MemoryToolSchemas.STORE,
                args -> {
                    String summary = str(args, "summary");
                    List<String> keyFindings = strList(args, "key_findings");
                    List<String> entities = strList(args, "entities");
                    String taskContext = strOr(args, "task_context", "");
                    double importance = dblOr(args, "importance", 0.5);
                    String scope = strOr(args, "scope", "short").toLowerCase();

                    MemoryEntry.MemoryType type;
                    MemoryEntry entry;
                    if ("long".equals(scope)) {
                        type = MemoryEntry.MemoryType.LONG_TERM;
                        String id = "lt_" + shortUuid();
                        MemoryEntry.MemoryContent content = buildContent(summary, entities, taskContext, keyFindings);
                        entry = MemoryEntry.create(id, type, content, importance);
                        longTermMemory.store(entry);
                    } else if ("short".equals(scope)) {
                        type = MemoryEntry.MemoryType.SHORT_TERM;
                        String id = "m_" + shortUuid();
                        MemoryEntry.MemoryContent content = buildContent(summary, entities, taskContext, keyFindings);
                        entry = MemoryEntry.create(id, type, content, importance);
                        shortTermMemory.store(entry);
                    } else {
                        throw new IllegalArgumentException("scope 取值必须是 short|long，实际: " + scope);
                    }
                    return okSummary(entry, scope);
                });
    }

    private void registerRecall(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                MemoryConstants.TOOL_MEMORY_RECALL,
                "按 query 关键词检索记忆（匹配 summary/entities/keyFindings/taskContext），综合 importance+access+recency 排序。",
                MemoryToolSchemas.RECALL,
                args -> {
                    String query = str(args, "query");
                    String scope = strOr(args, "scope", "both").toLowerCase();
                    int limit = intOr(args, "limit", 5);

                    List<MemoryEntry> merged = new ArrayList<>();
                    if (scope.equals("short") || scope.equals("both")) {
                        merged.addAll(shortTermMemory.recall(query, limit));
                    }
                    if (scope.equals("long") || scope.equals("both")) {
                        merged.addAll(longTermMemory.recall(query, limit));
                    }
                    if (merged.size() > limit) {
                        merged = merged.subList(0, limit);
                    }
                    return okList("recall", merged, limit);
                });
    }

    private void registerList(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                MemoryConstants.TOOL_MEMORY_LIST,
                "列出记忆摘要（按 timestamp desc），支持 scope 过滤与 limit 截断。",
                MemoryToolSchemas.LIST,
                args -> {
                    String scope = strOr(args, "scope", "both").toLowerCase();
                    int limit = intOr(args, "limit", 20);

                    List<MemoryEntry> merged = new ArrayList<>();
                    if (scope.equals("short") || scope.equals("both")) {
                        merged.addAll(shortTermMemory.getAll());
                    }
                    if (scope.equals("long") || scope.equals("both")) {
                        merged.addAll(longTermMemory.getAll());
                    }
                    merged.sort(Comparator.comparing(MemoryEntry::getTimestamp).reversed());
                    if (limit > 0 && merged.size() > limit) {
                        merged = new ArrayList<>(merged.subList(0, limit));
                    }
                    return okList("list", merged, limit);
                });
    }

    private void registerPromote(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                MemoryConstants.TOOL_MEMORY_PROMOTE,
                "把短期记忆升级为长期记忆（保留原 id 但加 lt_ 前缀，原短期记忆保留）。",
                MemoryToolSchemas.PROMOTE,
                args -> {
                    String id = str(args, "id");
                    MemoryEntry src = shortTermMemory.getById(id);
                    if (src == null) {
                        throw new IllegalArgumentException("短期记忆不存在: " + id);
                    }
                    String newId = "lt_" + shortUuid();
                    MemoryEntry promoted = MemoryEntry.create(
                            newId,
                            MemoryEntry.MemoryType.LONG_TERM,
                            src.getContent(),
                            Math.max(src.getImportance(), 0.7));
                    longTermMemory.store(promoted);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("status", "ok");
                    out.put("action", "promoted");
                    out.put("fromShortId", id);
                    out.put("toLongId", newId);
                    out.put("importance", promoted.getImportance());
                    return toJson(out);
                });
    }

    private void registerDelete(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                MemoryConstants.TOOL_MEMORY_DELETE,
                "按 ID 删除一条记忆（必填 scope 区分短期/长期）。",
                MemoryToolSchemas.DELETE,
                args -> {
                    String id = str(args, "id");
                    String scope = str(args, "scope").toLowerCase();
                    boolean removed;
                    if ("short".equals(scope)) {
                        removed = shortTermMemory.delete(id);
                    } else if ("long".equals(scope)) {
                        removed = longTermMemory.delete(id);
                    } else {
                        throw new IllegalArgumentException("scope 取值必须是 short|long，实际: " + scope);
                    }
                    if (!removed) {
                        throw new IllegalArgumentException("记忆不存在: id=" + id + ", scope=" + scope);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("status", "ok");
                    out.put("action", "deleted");
                    out.put("id", id);
                    out.put("scope", scope);
                    return toJson(out);
                });
    }

    private void registerStats(MCPClient mcpClient) {
        mcpClient.registerCustomTool(
                MemoryConstants.TOOL_MEMORY_STATS,
                "记忆使用统计（短期/长期分别 count、平均重要性、最常访问 Top5、最近 7 天写入数），渲染到终端。",
                MemoryToolSchemas.STATS,
                args -> {
                    String md = renderStatsMarkdown();
                    int width = ConsoleUI.getTerminalWidth();
                    try {
                        String rendered = MarkdownRenderer.render(md, "dark", width);
                        System.out.print(rendered);
                    } catch (Throwable t) {
                        System.out.print(md);
                    }
                    int shortCount = shortTermMemory.size();
                    int longCount = longTermMemory.size();
                    return "{\"status\":\"ok\",\"shortTermCount\":" + shortCount
                            + ",\"longTermCount\":" + longCount + "}";
                });
    }

    // ==================== 渲染 ====================

    private String renderStatsMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 记忆统计\n\n");

        sb.append("## 总览\n\n");
        sb.append("| 指标 | 短期记忆 | 长期记忆 |\n");
        sb.append("|---|---|---|\n");
        sb.append("| 条目数 | ").append(shortTermMemory.size())
          .append(" | ").append(longTermMemory.size()).append(" |\n");
        sb.append("| 平均重要性 | ").append(String.format("%.2f", avgImportance(shortTermMemory.getAll())))
          .append(" | ").append(String.format("%.2f", avgImportance(longTermMemory.getAll()))).append(" |\n");
        sb.append("| 访问总数 | ").append(totalAccess(shortTermMemory.getAll()))
          .append(" | ").append(totalAccess(longTermMemory.getAll())).append(" |\n\n");

        List<MemoryEntry> all = new ArrayList<>();
        all.addAll(shortTermMemory.getAll());
        all.addAll(longTermMemory.getAll());
        all.sort(Comparator.comparingInt(MemoryEntry::getAccessCount).reversed());
        if (!all.isEmpty()) {
            sb.append("## Top 访问记忆\n\n");
            sb.append("| ID | 摘要 | scope | 访问次数 |\n");
            sb.append("|---|---|---|---|\n");
            int n = Math.min(5, all.size());
            for (int i = 0; i < n; i++) {
                MemoryEntry e = all.get(i);
                String scope = e.getType() == MemoryEntry.MemoryType.LONG_TERM ? "long" : "short";
                sb.append("| ").append(e.getId())
                  .append(" | ").append(truncate(safeSummary(e), 40))
                  .append(" | ").append(scope)
                  .append(" | ").append(e.getAccessCount()).append(" |\n");
            }
            sb.append("\n");
        }

        // 最近 7 天写入数
        Map<String, Long> byDay = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        for (int i = 6; i >= 0; i--) {
            byDay.put(today.minusDays(i).toString(), 0L);
        }
        for (MemoryEntry e : all) {
            if (e.getTimestamp() == null) continue;
            LocalDate d = LocalDate.ofInstant(e.getTimestamp(), ZoneId.systemDefault());
            String key = d.toString();
            if (byDay.containsKey(key)) {
                byDay.merge(key, 1L, Long::sum);
            }
        }
        sb.append("## 最近 7 天写入数\n\n");
        sb.append("```\n");
        long max = byDay.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        for (Map.Entry<String, Long> e : byDay.entrySet()) {
            int barLen = max > 0 ? (int) Math.ceil(e.getValue() * 20.0 / max) : 0;
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < barLen; i++) bar.append('#');
            sb.append(String.format("%s  %-20s %d%n", e.getKey(), bar, e.getValue()));
        }
        sb.append("```\n");
        return sb.toString();
    }

    // ==================== 工具函数 ====================

    private MemoryEntry.MemoryContent buildContent(String summary, List<String> entities,
                                                   String taskContext, List<String> keyFindings) {
        return new MemoryEntry.MemoryContent(
                summary,
                entities != null ? entities : new ArrayList<>(),
                taskContext != null ? taskContext : "",
                keyFindings != null ? keyFindings : new ArrayList<>(),
                null);
    }

    private String okSummary(MemoryEntry e, String scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("action", "stored");
        out.put("id", e.getId());
        out.put("scope", scope);
        out.put("type", e.getType() == null ? null : e.getType().name());
        out.put("importance", e.getImportance());
        out.put("summary", truncate(safeSummary(e), 80));
        return toJson(out);
    }

    private String okList(String action, List<MemoryEntry> entries, int limit) {
        List<Map<String, Object>> items = new ArrayList<>(entries.size());
        for (MemoryEntry e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("scope", e.getType() == MemoryEntry.MemoryType.LONG_TERM ? "long" : "short");
            row.put("summary", truncate(safeSummary(e), 80));
            row.put("importance", e.getImportance());
            row.put("accessCount", e.getAccessCount());
            row.put("timestamp", e.getTimestamp() == null ? null : e.getTimestamp().toString());
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("action", action);
        out.put("count", items.size());
        out.put("items", items);
        return toJson(out);
    }

    private static String safeSummary(MemoryEntry e) {
        if (e == null || e.getContent() == null || e.getContent().getSummary() == null) {
            return "";
        }
        return e.getContent().getSummary();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static double avgImportance(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0.0;
        double sum = 0.0;
        for (MemoryEntry e : entries) sum += e.getImportance();
        return sum / entries.size();
    }

    private static long totalAccess(List<MemoryEntry> entries) {
        if (entries == null) return 0L;
        long sum = 0L;
        for (MemoryEntry e : entries) sum += e.getAccessCount();
        return sum;
    }

    private static String shortUuid() {
        String u = UUID.randomUUID().toString().replace("-", "");
        return u.substring(0, 8);
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
        if (v == null) throw new IllegalArgumentException("缺少必填参数: " + key);
        return String.valueOf(v).trim();
    }

    private static String strOr(Map<String, Object> args, String key, String defaultVal) {
        Object v = args.get(key);
        if (v == null) return defaultVal;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultVal : s;
    }

    private static int intOr(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static double dblOr(Map<String, Object> args, String key, double defaultVal) {
        Object v = args.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return defaultVal; }
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
        List<String> out = new ArrayList<>(1);
        out.add(String.valueOf(v));
        return out;
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
