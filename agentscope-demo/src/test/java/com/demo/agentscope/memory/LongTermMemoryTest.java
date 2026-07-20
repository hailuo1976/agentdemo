package com.demo.agentscope.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LongTermMemory} 单元测试。
 * <p>
 * 重点覆盖新增的 {@code getById} / {@code delete} 公开方法；同时验证 store/recall 基础链路、
 * 以及 {@code store} 自动抬高 importance 至 0.5+ 的语义。
 * </p>
 */
class LongTermMemoryTest {

    @TempDir
    Path tmp;

    private LongTermMemory memory;

    @BeforeEach
    void setup() {
        memory = new LongTermMemory(tmp.resolve("long_term"), 1000);
    }

    private MemoryEntry sample(String id, String summary, double importance) {
        return sample(id, summary, importance, new ArrayList<>(List.of("Claude Code 是闭源 reference")));
    }

    private MemoryEntry sample(String id, String summary, double importance, List<String> keyFindings) {
        MemoryEntry.MemoryContent content = new MemoryEntry.MemoryContent(
                summary,
                new ArrayList<>(List.of("LLM", "Agent")),
                "架构调研",
                keyFindings,
                null);
        return MemoryEntry.create(id, MemoryEntry.MemoryType.LONG_TERM, content, importance);
    }

    @Test
    @DisplayName("store 后缓存命中、文件落盘")
    void storeCachesAndPersists() {
        memory.store(sample("lt_abc12345", "用户偏好：中文交流", 0.9));
        assertEquals(1, memory.size());
        assertTrue(java.nio.file.Files.exists(tmp.resolve("long_term/lt_abc12345.json")));
    }

    @Test
    @DisplayName("store 自动把 <0.5 的 importance 抬到 0.7")
    void storeBoostsLowImportance() {
        memory.store(sample("lt_low", "低重要性", 0.2));
        MemoryEntry got = memory.getById("lt_low");
        assertEquals(0.7, got.getImportance(), 0.0001);
    }

    @Test
    @DisplayName("store 不改变 ≥0.5 的 importance")
    void storeKeepsHighImportance() {
        memory.store(sample("lt_high", "高重要性", 0.85));
        assertEquals(0.85, memory.getById("lt_high").getImportance(), 0.0001);
    }

    @Test
    @DisplayName("getById 命中已存在条目")
    void getByIdHits() {
        memory.store(sample("lt_hit", "测试记忆", 0.7));
        MemoryEntry got = memory.getById("lt_hit");
        assertNotNull(got);
        assertEquals("测试记忆", got.getContent().getSummary());
    }

    @Test
    @DisplayName("getById 未命中返回 null（不抛异常）")
    void getByIdMissReturnsNull() {
        assertNull(memory.getById("lt_does_not_exist"));
    }

    @Test
    @DisplayName("getById 不计入访问次数（纯读）")
    void getByIdDoesNotBumpAccess() {
        memory.store(sample("lt_acc", "访问测试", 0.7));
        int before = memory.getById("lt_acc").getAccessCount();
        memory.getById("lt_acc");
        memory.getById("lt_acc");
        int after = memory.getById("lt_acc").getAccessCount();
        assertEquals(before, after);
    }

    @Test
    @DisplayName("delete 命中：缓存移除 + 文件删除")
    void deleteHitRemovesFromCacheAndDisk() {
        memory.store(sample("lt_del1", "待删除", 0.7));
        assertTrue(java.nio.file.Files.exists(tmp.resolve("long_term/lt_del1.json")));

        boolean removed = memory.delete("lt_del1");
        assertTrue(removed);
        assertEquals(0, memory.size());
        assertNull(memory.getById("lt_del1"));
        assertFalse(java.nio.file.Files.exists(tmp.resolve("long_term/lt_del1.json")));
    }

    @Test
    @DisplayName("delete 未命中返回 false（幂等）")
    void deleteMissReturnsFalse() {
        assertFalse(memory.delete("lt_nope"));
        assertFalse(memory.delete("lt_nope"));
    }

    @Test
    @DisplayName("delete 后再 store 同 id 可重新写入")
    void storeAfterDelete() {
        memory.store(sample("lt_resurrect", "第一版", 0.7));
        memory.delete("lt_resurrect");
        memory.store(sample("lt_resurrect", "第二版", 0.9));
        MemoryEntry got = memory.getById("lt_resurrect");
        assertNotNull(got);
        assertEquals("第二版", got.getContent().getSummary());
        assertEquals(0.9, got.getImportance(), 0.0001);
    }

    @Test
    @DisplayName("recall 关键词命中 summary/entities/keyFindings/taskContext")
    void recallMatches() {
        memory.store(sample("lt_r1", "Claude Code 架构分析", 0.9,
                new ArrayList<>(List.of("Claude Code 是闭源 reference"))));
        memory.store(sample("lt_r2", "无关记忆", 0.6,
                new ArrayList<>()));

        List<MemoryEntry> claude = memory.recall("claude", 5);
        assertEquals(1, claude.size());
        assertEquals("lt_r1", claude.get(0).getId());

        // entities 命中（"LLM" / "Agent" 在两条记忆里都有）
        List<MemoryEntry> agent = memory.recall("agent", 5);
        assertEquals(2, agent.size());
    }

    @Test
    @DisplayName("recall 增加访问计数")
    void recallBumpsAccessCount() {
        memory.store(sample("lt_acc2", "被检索的记忆", 0.95));
        int before = memory.getById("lt_acc2").getAccessCount();
        memory.recall("检索", 5);
        int after = memory.getById("lt_acc2").getAccessCount();
        assertEquals(before + 1, after);
    }

    @Test
    @DisplayName("重启场景：新实例从磁盘加载已有记忆")
    void reloadFromDisk() {
        memory.store(sample("lt_persist", "跨进程持久化", 0.9));
        LongTermMemory fresh = new LongTermMemory(tmp.resolve("long_term"), 1000);
        MemoryEntry got = fresh.getById("lt_persist");
        assertNotNull(got);
        assertEquals("跨进程持久化", got.getContent().getSummary());
    }

    @Test
    @DisplayName("extractFromShortTerm 把 ≥ threshold 的短期记忆搬到长期")
    void extractFromShortTerm() {
        ShortTermMemory stm = new ShortTermMemory(tmp.resolve("short_term"), 100,
                java.time.Duration.ofDays(7));
        MemoryEntry.MemoryContent c1 = new MemoryEntry.MemoryContent(
                "重要发现", new ArrayList<>(), "", new ArrayList<>(), null);
        MemoryEntry.MemoryContent c2 = new MemoryEntry.MemoryContent(
                "琐碎细节", new ArrayList<>(), "", new ArrayList<>(), null);
        stm.store(MemoryEntry.create("m_keep", MemoryEntry.MemoryType.SHORT_TERM, c1, 0.9));
        stm.store(MemoryEntry.create("m_skip", MemoryEntry.MemoryType.SHORT_TERM, c2, 0.2));

        memory.extractFromShortTerm(stm, 0.7);
        assertEquals(1, memory.size());
        assertNotNull(memory.getById("lt_m_keep"));
        assertNull(memory.getById("lt_m_skip"));
    }

    @Test
    @DisplayName("clear 清空缓存与磁盘文件")
    void clearWipesAll() {
        memory.store(sample("lt_c1", "a", 0.7));
        memory.store(sample("lt_c2", "b", 0.7));
        assertEquals(2, memory.size());
        memory.clear();
        assertEquals(0, memory.size());
        assertNull(memory.getById("lt_c1"));
        assertNull(memory.getById("lt_c2"));
    }
}
