package com.demo.agentscope.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShortTermMemory} 单元测试。
 * <p>
 * 重点覆盖新增的 {@code getById} / {@code delete} 公开方法；同时验证 store/recall 基础链路。
 * </p>
 */
class ShortTermMemoryTest {

    @TempDir
    Path tmp;

    private ShortTermMemory memory;

    @BeforeEach
    void setup() {
        memory = new ShortTermMemory(tmp.resolve("short_term"), 100, Duration.ofDays(7));
    }

    private MemoryEntry sample(String id, String summary, double importance) {
        MemoryEntry.MemoryContent content = new MemoryEntry.MemoryContent(
                summary,
                new ArrayList<>(List.of("股票", "量化")),
                "回测任务",
                new ArrayList<>(List.of("龙头股筛选有效")),
                null);
        return MemoryEntry.create(id, MemoryEntry.MemoryType.SHORT_TERM, content, importance);
    }

    @Test
    @DisplayName("store 后缓存命中、文件落盘")
    void storeCachesAndPersists() {
        memory.store(sample("m_abc12345", "龙头股筛选策略", 0.6));
        assertEquals(1, memory.size());
        assertTrue(java.nio.file.Files.exists(tmp.resolve("short_term/m_abc12345.json")));
    }

    @Test
    @DisplayName("getById 命中已存在条目")
    void getByIdHits() {
        memory.store(sample("m_hit001", "测试记忆", 0.5));
        MemoryEntry got = memory.getById("m_hit001");
        assertNotNull(got);
        assertEquals("测试记忆", got.getContent().getSummary());
    }

    @Test
    @DisplayName("getById 未命中返回 null（不抛异常）")
    void getByIdMissReturnsNull() {
        assertNull(memory.getById("m_does_not_exist"));
    }

    @Test
    @DisplayName("getById 不计入访问次数（纯读）")
    void getByIdDoesNotBumpAccess() {
        memory.store(sample("m_acc1", "访问测试", 0.5));
        int before = memory.getById("m_acc1").getAccessCount();
        memory.getById("m_acc1");
        memory.getById("m_acc1");
        int after = memory.getById("m_acc1").getAccessCount();
        assertEquals(before, after);
    }

    @Test
    @DisplayName("delete 命中：缓存移除 + 文件删除")
    void deleteHitRemovesFromCacheAndDisk() {
        memory.store(sample("m_del1", "待删除", 0.5));
        assertTrue(java.nio.file.Files.exists(tmp.resolve("short_term/m_del1.json")));

        boolean removed = memory.delete("m_del1");
        assertTrue(removed);
        assertEquals(0, memory.size());
        assertNull(memory.getById("m_del1"));
        assertFalse(java.nio.file.Files.exists(tmp.resolve("short_term/m_del1.json")));
    }

    @Test
    @DisplayName("delete 未命中返回 false（幂等）")
    void deleteMissReturnsFalse() {
        assertFalse(memory.delete("m_nope"));
        assertFalse(memory.delete("m_nope")); // 再次删仍然 false，不抛异常
    }

    @Test
    @DisplayName("delete 后再 store 同 id 可重新写入")
    void storeAfterDelete() {
        memory.store(sample("m_resurrect", "第一版", 0.5));
        memory.delete("m_resurrect");
        memory.store(sample("m_resurrect", "第二版", 0.7));
        MemoryEntry got = memory.getById("m_resurrect");
        assertNotNull(got);
        assertEquals("第二版", got.getContent().getSummary());
        assertEquals(0.7, got.getImportance(), 0.0001);
    }

    @Test
    @DisplayName("recall 关键词命中 summary/entities/keyFindings/taskContext")
    void recallMatches() {
        memory.store(sample("m_r1", "用户偏好：喜欢 Python", 0.8));
        memory.store(sample("m_r2", "不相关记忆", 0.3));

        List<MemoryEntry> python = memory.recall("python", 5);
        assertEquals(1, python.size());
        assertEquals("m_r1", python.get(0).getId());

        // entities 命中
        List<MemoryEntry> stock = memory.recall("股票", 5);
        assertEquals(2, stock.size());
    }

    @Test
    @DisplayName("recall 空 query 返回全部（不大于 limit）")
    void recallEmptyQueryReturnsAll() {
        memory.store(sample("m_e1", "a", 0.5));
        memory.store(sample("m_e2", "b", 0.5));
        memory.store(sample("m_e3", "c", 0.5));
        List<MemoryEntry> res = memory.recall("", 2);
        assertEquals(2, res.size());
    }

    @Test
    @DisplayName("recall 增加访问计数")
    void recallBumpsAccessCount() {
        memory.store(sample("m_acc2", "被检索的记忆", 0.9));
        int before = memory.getById("m_acc2").getAccessCount();
        memory.recall("检索", 5);
        int after = memory.getById("m_acc2").getAccessCount();
        assertEquals(before + 1, after);
    }

    @Test
    @DisplayName("重启场景：新实例从磁盘加载已有记忆")
    void reloadFromDisk() {
        memory.store(sample("m_persist", "跨进程持久化", 0.8));
        // 模拟重启
        ShortTermMemory fresh = new ShortTermMemory(tmp.resolve("short_term"), 100, Duration.ofDays(7));
        MemoryEntry got = fresh.getById("m_persist");
        assertNotNull(got);
        assertEquals("跨进程持久化", got.getContent().getSummary());
    }

    @Test
    @DisplayName("clear 清空缓存与磁盘文件")
    void clearWipesAll() {
        memory.store(sample("m_c1", "a", 0.5));
        memory.store(sample("m_c2", "b", 0.5));
        assertEquals(2, memory.size());
        memory.clear();
        assertEquals(0, memory.size());
        assertNull(memory.getById("m_c1"));
        assertNull(memory.getById("m_c2"));
    }
}
