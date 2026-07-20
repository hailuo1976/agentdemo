package com.demo.agentscope.memory;

import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.mcp.MCPClient.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryToolService} 端到端测试。
 * <p>
 * 通过真实 {@link MCPClient} 注册 6 个 memory_* 工具，再用 {@code executeTool} 调用，
 * 验证：(1) 6 个工具名都已注册 (2) store→recall→list→promote→delete→stats 全链路
 * (3) 异常路径返回 {@code status=error} 或 {@code ToolResult.isSuccess()=false}。
 * </p>
 * <p>
 * 测试覆盖矩阵：每个工具一个 happy path + 一个错误参数路径（summary 空 / id 不存在 / scope 非法 等）。
 * </p>
 */
class MemoryToolServiceTest {

    @TempDir
    Path tmp;

    private MCPClient mcpClient;
    private ShortTermMemory shortTermMemory;
    private LongTermMemory longTermMemory;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setup() {
        shortTermMemory = new ShortTermMemory(tmp.resolve("short_term"), 100, Duration.ofDays(7));
        longTermMemory = new LongTermMemory(tmp.resolve("long_term"), 1000);
        MemoryToolService service = new MemoryToolService(shortTermMemory, longTermMemory);
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

    // ==================== 6 个工具均已注册 ====================

    @Test
    @DisplayName("6 个 memory_* 工具均已注册到 MCPClient")
    void allToolsRegistered() {
        var toolNames = mcpClient.listTools().stream().map(t -> t.name()).toList();
        for (String n : MemoryConstants.TOOL_NAMES) {
            assertTrue(toolNames.contains(n), "listTools 缺少: " + n);
        }
    }

    // ==================== memory_store ====================

    @Test
    @DisplayName("store short scope 写入短期记忆，返回 m_ 前缀 id")
    void storeShortScope() throws Exception {
        JsonNode res = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "用户偏好：中文交流",
                "entities", List.of("用户", "语言"),
                "key_findings", List.of("用户母语是中文"),
                "importance", 0.8,
                "scope", "short"));
        assertEquals("ok", res.get("status").asText());
        assertEquals("stored", res.get("action").asText());
        String id = res.get("id").asText();
        assertTrue(id.startsWith("m_"), "短期记忆 id 应以 m_ 开头: " + id);
        assertEquals("short", res.get("scope").asText());
        // 真的落盘到 ShortTermMemory
        assertNotNull(shortTermMemory.getById(id));
        assertEquals(1, shortTermMemory.size());
    }

    @Test
    @DisplayName("store long scope 写入长期记忆，返回 lt_ 前缀 id")
    void storeLongScope() throws Exception {
        JsonNode res = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "用户是 Java 开发者",
                "scope", "long",
                "importance", 0.9));
        assertEquals("ok", res.get("status").asText());
        String id = res.get("id").asText();
        assertTrue(id.startsWith("lt_"), "长期记忆 id 应以 lt_ 开头: " + id);
        assertNotNull(longTermMemory.getById(id));
    }

    @Test
    @DisplayName("store 默认 scope=short")
    void storeDefaultScope() throws Exception {
        JsonNode res = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "无 scope 字段的记忆"));
        assertEquals("short", res.get("scope").asText());
    }

    @Test
    @DisplayName("store 缺 summary 报错")
    void storeMissingSummary() {
        ToolResult r = mcpClient.executeTool(MemoryConstants.TOOL_MEMORY_STORE,
                args("scope", "short"));
        assertFalse(r.isSuccess());
        assertNotNull(r.getError());
    }

    @Test
    @DisplayName("store scope 非法报错")
    void storeInvalidScope() {
        ToolResult r = mcpClient.executeTool(MemoryConstants.TOOL_MEMORY_STORE,
                args("summary", "test", "scope", "bogus"));
        assertFalse(r.isSuccess());
    }

    // ==================== memory_recall ====================

    @Test
    @DisplayName("recall 按 summary 关键词命中")
    void recallBySummary() throws Exception {
        run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "用户喜欢 Python", "scope", "short"));
        run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "不相关记忆", "scope", "short"));

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_RECALL,
                args("query", "python", "scope", "short"));
        assertEquals("ok", res.get("status").asText());
        assertEquals(1, res.get("count").asInt());
        assertTrue(res.get("items").get(0).get("summary").asText().contains("Python"));
    }

    @Test
    @DisplayName("recall scope=both 合并短期+长期")
    void recallBothScopes() throws Exception {
        run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "短期 Python 记忆", "scope", "short"));
        run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "长期 Python 记忆", "scope", "long"));

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_RECALL,
                args("query", "python", "scope", "both"));
        assertEquals(2, res.get("count").asInt());
    }

    @Test
    @DisplayName("recall 默认 scope=both、limit=5")
    void recallDefaults() throws Exception {
        for (int i = 0; i < 10; i++) {
            run(MemoryConstants.TOOL_MEMORY_STORE, args(
                    "summary", "python 记忆 #" + i, "scope", "short"));
        }
        JsonNode res = run(MemoryConstants.TOOL_MEMORY_RECALL,
                args("query", "python"));
        assertEquals(5, res.get("count").asInt());
    }

    @Test
    @DisplayName("recall 缺 query 报错")
    void recallMissingQuery() {
        ToolResult r = mcpClient.executeTool(MemoryConstants.TOOL_MEMORY_RECALL, args());
        assertFalse(r.isSuccess());
    }

    // ==================== memory_list ====================

    @Test
    @DisplayName("list 按 timestamp desc 返回，count 正确")
    void listReturnsSortedByTimestampDesc() throws Exception {
        // sleep 一点以保证 timestamp 有差异
        run(MemoryConstants.TOOL_MEMORY_STORE, args("summary", "first", "scope", "short"));
        Thread.sleep(15);
        run(MemoryConstants.TOOL_MEMORY_STORE, args("summary", "second", "scope", "short"));

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_LIST, args("scope", "short"));
        assertEquals("ok", res.get("status").asText());
        assertEquals(2, res.get("count").asInt());
        // 第二条更晚写入应排在前
        assertEquals("second", res.get("items").get(0).get("summary").asText());
    }

    @Test
    @DisplayName("list scope=long 只返回长期")
    void listLongScope() throws Exception {
        run(MemoryConstants.TOOL_MEMORY_STORE, args("summary", "s1", "scope", "short"));
        run(MemoryConstants.TOOL_MEMORY_STORE, args("summary", "l1", "scope", "long"));

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_LIST, args("scope", "long"));
        assertEquals(1, res.get("count").asInt());
        assertEquals("l1", res.get("items").get(0).get("summary").asText());
    }

    @Test
    @DisplayName("list limit 截断生效")
    void listLimitTruncates() throws Exception {
        for (int i = 0; i < 5; i++) {
            run(MemoryConstants.TOOL_MEMORY_STORE, args("summary", "n" + i, "scope", "short"));
        }
        JsonNode res = run(MemoryConstants.TOOL_MEMORY_LIST, args("scope", "short", "limit", 2));
        assertEquals(2, res.get("count").asInt());
    }

    // ==================== memory_promote ====================

    @Test
    @DisplayName("promote 把短期记忆升级为长期（max(importance, 0.7)）")
    void promoteHappy() throws Exception {
        JsonNode stored = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "待升级", "importance", 0.4, "scope", "short"));
        String shortId = stored.get("id").asText();

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_PROMOTE, args("id", shortId));
        assertEquals("ok", res.get("status").asText());
        assertEquals("promoted", res.get("action").asText());
        assertEquals(shortId, res.get("fromShortId").asText());
        String longId = res.get("toLongId").asText();
        assertTrue(longId.startsWith("lt_"));
        // 抬高到 ≥0.7
        assertTrue(res.get("importance").asDouble() >= 0.7);
        // 长期 store 里能查到
        assertNotNull(longTermMemory.getById(longId));
        // 原短期记忆保留（不自动删除）
        assertNotNull(shortTermMemory.getById(shortId));
    }

    @Test
    @DisplayName("promote 已 ≥0.7 的 importance 不变")
    void promoteKeepsHighImportance() throws Exception {
        JsonNode stored = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "已经够重要", "importance", 0.95, "scope", "short"));
        String shortId = stored.get("id").asText();

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_PROMOTE, args("id", shortId));
        assertEquals(0.95, res.get("importance").asDouble(), 0.0001);
    }

    @Test
    @DisplayName("promote 不存在的短期 id 报错")
    void promoteMissingId() {
        ToolResult r = mcpClient.executeTool(MemoryConstants.TOOL_MEMORY_PROMOTE,
                args("id", "m_does_not_exist"));
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("promote 缺 id 报错")
    void promoteMissingParam() {
        ToolResult r = mcpClient.executeTool(MemoryConstants.TOOL_MEMORY_PROMOTE, args());
        assertFalse(r.isSuccess());
    }

    // ==================== memory_delete ====================

    @Test
    @DisplayName("delete short 删除短期记忆")
    void deleteShort() throws Exception {
        JsonNode stored = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "待删除", "scope", "short"));
        String id = stored.get("id").asText();

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_DELETE,
                args("id", id, "scope", "short"));
        assertEquals("ok", res.get("status").asText());
        assertEquals("deleted", res.get("action").asText());
        assertNull(shortTermMemory.getById(id));
    }

    @Test
    @DisplayName("delete long 删除长期记忆")
    void deleteLong() throws Exception {
        JsonNode stored = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "待删除长期", "scope", "long"));
        String id = stored.get("id").asText();

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_DELETE,
                args("id", id, "scope", "long"));
        assertEquals("ok", res.get("status").asText());
        assertNull(longTermMemory.getById(id));
    }

    @Test
    @DisplayName("delete 不存在的 id 报错")
    void deleteMissingId() {
        ToolResult r = mcpClient.executeTool(MemoryConstants.TOOL_MEMORY_DELETE,
                args("id", "lt_nope", "scope", "long"));
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("delete scope 非法报错")
    void deleteInvalidScope() {
        ToolResult r = mcpClient.executeTool(MemoryConstants.TOOL_MEMORY_DELETE,
                args("id", "whatever", "scope", "bogus"));
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("delete 缺 scope 报错")
    void deleteMissingScope() {
        ToolResult r = mcpClient.executeTool(MemoryConstants.TOOL_MEMORY_DELETE,
                args("id", "whatever"));
        assertFalse(r.isSuccess());
    }

    // ==================== memory_stats ====================

    @Test
    @DisplayName("stats 返回 status=ok 与 count 摘要")
    void statsReturnsOk() throws Exception {
        run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "短记忆 A", "scope", "short"));
        run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "长记忆 B", "scope", "long"));

        JsonNode res = run(MemoryConstants.TOOL_MEMORY_STATS, args());
        assertEquals("ok", res.get("status").asText());
        assertEquals(1, res.get("shortTermCount").asInt());
        assertEquals(1, res.get("longTermCount").asInt());
    }

    @Test
    @DisplayName("stats 在空记忆时也正常（不抛异常）")
    void statsEmptyStore() throws Exception {
        JsonNode res = run(MemoryConstants.TOOL_MEMORY_STATS, args());
        assertEquals("ok", res.get("status").asText());
        assertEquals(0, res.get("shortTermCount").asInt());
        assertEquals(0, res.get("longTermCount").asInt());
    }

    // ==================== 端到端 ====================

    @Test
    @DisplayName("端到端：store → recall → promote → list → delete → stats")
    void endToEnd() throws Exception {
        // 1) store 两条
        JsonNode s1 = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "用户喜欢 Java", "importance", 0.6, "scope", "short"));
        JsonNode s2 = run(MemoryConstants.TOOL_MEMORY_STORE, args(
                "summary", "用户喜欢 Python", "importance", 0.9, "scope", "short"));
        assertEquals(2, shortTermMemory.size());

        // 2) recall 关键词
        JsonNode recalled = run(MemoryConstants.TOOL_MEMORY_RECALL,
                args("query", "python", "scope", "short"));
        assertEquals(1, recalled.get("count").asInt());

        // 3) promote 一条
        String promoteId = s2.get("id").asText();
        JsonNode promoted = run(MemoryConstants.TOOL_MEMORY_PROMOTE, args("id", promoteId));
        assertEquals(1, longTermMemory.size());

        // 4) list both 应同时看到 short + long
        JsonNode listed = run(MemoryConstants.TOOL_MEMORY_LIST, args("scope", "both"));
        assertEquals(3, listed.get("count").asInt()); // 2 short + 1 long

        // 5) delete 原 short
        JsonNode deleted = run(MemoryConstants.TOOL_MEMORY_DELETE,
                args("id", promoteId, "scope", "short"));
        assertEquals("ok", deleted.get("status").asText());
        assertEquals(1, shortTermMemory.size());

        // 6) stats 仍正常
        JsonNode stats = run(MemoryConstants.TOOL_MEMORY_STATS, args());
        assertEquals("ok", stats.get("status").asText());
        assertEquals(1, stats.get("shortTermCount").asInt());
        assertEquals(1, stats.get("longTermCount").asInt());
    }
}
