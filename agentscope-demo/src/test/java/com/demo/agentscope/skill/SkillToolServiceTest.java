package com.demo.agentscope.skill;

import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.mcp.MCPClient.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillToolService} 端到端测试。
 * <p>
 * 通过真实 {@link MCPClient} 注册 12 个工具，再用 {@code executeTool} 调用，
 * 验证：(1) 工具名都已注册 (2) create→get→list→update→publish→search→history→export→import→stats
 * 的全链路 (3) 异常路径返回 {@code status=error}。
 * </p>
 */
class SkillToolServiceTest {

    @TempDir
    Path tmp;
    private MCPClient mcpClient;
    private SkillManager manager;
    private SkillToolService service;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setup() {
        manager = new SkillManager(tmp.resolve("skills"), "tester");
        service = new SkillToolService(manager, tmp);
        mcpClient = new MCPClient();
        // 抬高摘要阈值，避免 list/stats 等返回较大 JSON 被自动摘要覆盖
        mcpClient.updateToolResultSummarizerLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);
        service.registerTools(mcpClient);
    }

    private Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private JsonNode run(String tool, Map<String, Object> args) throws Exception {
        ToolResult r = mcpClient.executeTool(tool, args);
        assertTrue(r.isSuccess(), "tool=" + tool + " 应成功，error=" + r.getError());
        assertNotNull(r.getOutput());
        return om.readTree(r.getOutput());
    }

    private String createSkill(String name, String desc, List<String> tags) throws Exception {
        JsonNode res = run(SkillConstants.TOOL_SKILL_CREATE, args(
                "name", name, "description", desc, "tags", tags));
        assertEquals("ok", res.get("status").asText());
        return res.get("id").asText();
    }

    @Test
    @DisplayName("create 返回的 skillStatus 为 DRAFT")
    void createReturnsSkillStatus() throws Exception {
        JsonNode res = run(SkillConstants.TOOL_SKILL_CREATE, args(
                "name", "测试", "description", "d"));
        assertEquals("ok", res.get("status").asText());
        assertEquals("DRAFT", res.get("skillStatus").asText());
    }

    @Test
    @DisplayName("12 个工具均已注册到 MCPClient")
    void allToolsRegistered() {
        for (String name : SkillConstants.TOOL_NAMES) {
            assertNotNull(mcpClient.executeTool(name, Map.of()), "工具未注册: " + name);
        }
        // 等价校验：listTools 包含全部
        var toolNames = mcpClient.listTools().stream().map(t -> t.name()).toList();
        for (String n : SkillConstants.TOOL_NAMES) {
            assertTrue(toolNames.contains(n), "listTools 缺少: " + n);
        }
    }

    @Test
    @DisplayName("create + get 往返")
    void createAndGet() throws Exception {
        String id = createSkill("股票龙头", "策略描述", List.of("量化", "股票"));
        JsonNode got = run(SkillConstants.TOOL_SKILL_GET, args("id", id));
        assertEquals(id, got.get("id").asText());
        assertEquals("股票龙头", got.get("name").asText());
        assertEquals("策略描述", got.get("description").asText());
        // tags 保持顺序
        assertEquals("量化", got.get("tags").get(0).asText());
    }

    @Test
    @DisplayName("list 返回 count + items")
    void listReturnsCount() throws Exception {
        createSkill("a", "d1", List.of());
        createSkill("b", "d2", List.of("股票"));
        JsonNode res = run(SkillConstants.TOOL_SKILL_LIST, args("tag", "股票"));
        assertEquals("ok", res.get("status").asText());
        assertEquals(1, res.get("count").asInt());
        assertEquals("b", res.get("items").get(0).get("name").asText());
    }

    @Test
    @DisplayName("list 默认 limit=50")
    void listDefaultLimit() throws Exception {
        for (int i = 0; i < 60; i++) createSkill("s" + i, "d", List.of());
        JsonNode res = run(SkillConstants.TOOL_SKILL_LIST, args());
        assertEquals(50, res.get("count").asInt());
    }

    @Test
    @DisplayName("update 自增 version 并更新字段")
    void updateIncrementsVersion() throws Exception {
        String id = createSkill("原名", "原描述", List.of());
        JsonNode res = run(SkillConstants.TOOL_SKILL_UPDATE, args(
                "id", id, "name", "新名", "description", "新描述"));
        assertEquals("ok", res.get("status").asText());
        assertEquals(2, res.get("version").asInt());
        assertEquals("新名", res.get("name").asText());
    }

    @Test
    @DisplayName("delete 无 confirm=true 报错")
    void deleteWithoutConfirm() {
        ToolResult r = mcpClient.executeTool(SkillConstants.TOOL_SKILL_DELETE,
                args("id", "any", "confirm", false));
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("delete confirm=true 软删除")
    void deleteWithConfirm() throws Exception {
        String id = createSkill("n", "d", List.of());
        JsonNode res = run(SkillConstants.TOOL_SKILL_DELETE, args("id", id, "confirm", true));
        assertEquals("ok", res.get("status").asText());
        // skillStatus 字段反映技能生命周期状态（与外层 status:"ok" 分离）
        assertEquals("DEPRECATED", res.get("skillStatus").asText());
        assertEquals(SkillStatus.DEPRECATED, manager.get(id).getStatus());
    }

    @Test
    @DisplayName("publish DRAFT → PUBLISHED")
    void publish() throws Exception {
        String id = createSkill("n", "d", List.of());
        JsonNode res = run(SkillConstants.TOOL_SKILL_PUBLISH, args("id", id));
        assertEquals("ok", res.get("status").asText());
        // okSummary 把 status 字段重命名了，所以这里看 status
        // 实际 OK summary 里第二个 status 是技能状态
        // 检查 store 里状态
        assertEquals(SkillStatus.PUBLISHED, manager.get(id).getStatus());
    }

    @Test
    @DisplayName("deprecate 任意 → DEPRECATED")
    void deprecate() throws Exception {
        String id = createSkill("n", "d", List.of());
        run(SkillConstants.TOOL_SKILL_DEPRECATE, args("id", id));
        assertEquals(SkillStatus.DEPRECATED, manager.get(id).getStatus());
    }

    @Test
    @DisplayName("search 返回带 score/fallback 字段")
    void searchReturnsScore() throws Exception {
        createSkill("股票A", "无关", List.of());
        createSkill("其他", "股票相关", List.of());
        JsonNode res = run(SkillConstants.TOOL_SKILL_SEARCH, args("query", "股票", "limit", 5));
        assertEquals("ok", res.get("status").asText());
        assertEquals(2, res.get("count").asInt());
        JsonNode first = res.get("items").get(0);
        assertTrue(first.has("score"));
        assertEquals("exact", first.get("fallback").asText());
        // 第一条应为 "股票A"（name 命中权重高）
        assertEquals("股票A", first.get("name").asText());
    }

    @Test
    @DisplayName("search 子序列兜底")
    void searchFallback() throws Exception {
        createSkill("gupiaolongtou", "d", List.of());
        JsonNode res = run(SkillConstants.TOOL_SKILL_SEARCH, args("query", "gplt"));
        assertEquals(1, res.get("count").asInt());
        assertEquals("subsequence", res.get("items").get(0).get("fallback").asText());
    }

    @Test
    @DisplayName("history 返回版本列表")
    void history() throws Exception {
        String id = createSkill("n", "d", List.of());
        run(SkillConstants.TOOL_SKILL_UPDATE, args("id", id, "description", "d2"));
        JsonNode res = run(SkillConstants.TOOL_SKILL_HISTORY, args("id", id));
        assertEquals("ok", res.get("status").asText());
        // v1 (归档) + v2 (当前) = 2 条
        assertEquals(2, res.get("count").asInt());
    }

    @Test
    @DisplayName("export 写出 .md 文件")
    void export() throws Exception {
        createSkill("股票A", "描述", List.of("量化"));
        Path exportDir = tmp.resolve("export_test");
        JsonNode res = run(SkillConstants.TOOL_SKILL_EXPORT, args("target_dir", exportDir.toString()));
        assertEquals("ok", res.get("status").asText());
        assertEquals(1, res.get("count").asInt());
        String fileName = res.get("files").get(0).asText();
        assertTrue(fileName.endsWith(".md"));
        assertTrue(Files.isRegularFile(exportDir.resolve(fileName)));
    }

    @Test
    @DisplayName("export + import 闭环")
    void exportImportLoop() throws Exception {
        createSkill("股票A", "描述", List.of("量化"));
        Path exportDir = tmp.resolve("export_loop");
        run(SkillConstants.TOOL_SKILL_EXPORT, args("target_dir", exportDir.toString()));
        // 切换到一个新的 manager 避免冲突
        SkillManager mgr2 = new SkillManager(tmp.resolve("skills_imported"), "tester2");
        SkillToolService svc2 = new SkillToolService(mgr2, tmp);
        MCPClient mc2 = new MCPClient();
        svc2.registerTools(mc2);
        ToolResult r = mc2.executeTool(SkillConstants.TOOL_SKILL_IMPORT,
                args("source_dir", exportDir.toString()));
        assertTrue(r.isSuccess(), r.getError());
        JsonNode res = om.readTree(r.getOutput());
        assertEquals("ok", res.get("status").asText());
        assertEquals(1, res.get("count").asInt());
        assertEquals("created", res.get("items").get(0).get("action").asText());
    }

    @Test
    @DisplayName("stats 渲染并返回摘要 JSON")
    void stats() throws Exception {
        String id = createSkill("n", "d", List.of());
        run(SkillConstants.TOOL_SKILL_GET, args("id", id));
        JsonNode res = run(SkillConstants.TOOL_SKILL_STATS, args("period", "all"));
        assertEquals("ok", res.get("status").asText());
        assertTrue(res.get("totalSkills").asInt() >= 1);
        assertTrue(res.get("totalAccesses").asInt() >= 2);
    }

    @Test
    @DisplayName("stats 容忍未知 period → ALL")
    void statsUnknownPeriod() throws Exception {
        JsonNode res = run(SkillConstants.TOOL_SKILL_STATS, args("period", "bogus"));
        assertEquals("ok", res.get("status").asText());
    }

    @Test
    @DisplayName("get 不存在的 id 报错（status=error）")
    void getNotFound() throws Exception {
        // SkillStore.load 抛 NotFound → executeTool 转 ToolResult(success=false)
        ToolResult r = mcpClient.executeTool(SkillConstants.TOOL_SKILL_GET,
                args("id", "sk_does_not_exist"));
        assertFalse(r.isSuccess());
        assertNotNull(r.getError());
    }

    @Test
    @DisplayName("create 缺 name 报错")
    void createMissingName() {
        ToolResult r = mcpClient.executeTool(SkillConstants.TOOL_SKILL_CREATE,
                args("description", "d"));
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("相对 target_dir 基于 basePath 解析")
    void exportRelativePath() throws Exception {
        createSkill("a", "d", List.of());
        // 相对路径 "rel_export" → tmp/rel_export
        JsonNode res = run(SkillConstants.TOOL_SKILL_EXPORT, args("target_dir", "rel_export"));
        assertEquals("ok", res.get("status").asText());
        Path expected = tmp.resolve("rel_export");
        assertTrue(Files.isDirectory(expected));
        assertEquals(expected.toString(), res.get("targetDir").asText());
    }

    @Test
    @DisplayName("ids 白名单过滤生效")
    void exportWithIds() throws Exception {
        String a = createSkill("a", "d", List.of());
        createSkill("b", "d", List.of());
        JsonNode res = run(SkillConstants.TOOL_SKILL_EXPORT,
                args("target_dir", tmp.resolve("byids").toString(), "ids", List.of(a)));
        assertEquals("ok", res.get("status").asText());
        assertEquals(1, res.get("count").asInt());
    }

    @Test
    @DisplayName("update 无任何 patch 字段也合法（version 仍自增）")
    void updateNoPatch() throws Exception {
        String id = createSkill("n", "d", List.of());
        JsonNode res = run(SkillConstants.TOOL_SKILL_UPDATE, args("id", id));
        assertEquals("ok", res.get("status").asText());
        assertEquals(2, res.get("version").asInt());
    }

    @Test
    @DisplayName("create 单值 tags 也兼容（非数组）")
    void createSingleTag() throws Exception {
        // 直接用字符串传 tags —— strList 容错为 1-element list
        JsonNode res = run(SkillConstants.TOOL_SKILL_CREATE,
                args("name", "n", "description", "d", "tags", "量化"));
        assertEquals("ok", res.get("status").asText());
        JsonNode tags = res.get("tags");
        assertEquals(1, tags.size());
        assertEquals("量化", tags.get(0).asText());
    }
    @SuppressWarnings("unused")
    private void unused() { new HashMap<>(); }
}
