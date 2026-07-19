package com.demo.agentscope.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillStore} 单元测试。
 * <p>
 * 覆盖：create/load/update+版本归档/publish/deprecate/incrementUse/reloadFromDisk/concurrentWrites。
 * </p>
 */
class SkillStoreTest {

    @TempDir
    Path tmp;
    private SkillStore store;

    @BeforeEach
    void setup() {
        store = new SkillStore(tmp.resolve("skills"));
    }

    @AfterEach
    void teardown() {
        // @TempDir 自动清理
    }

    private Skill sampleSkill(String name, String desc) {
        Skill s = new Skill();
        s.setName(name);
        s.setDescription(desc);
        s.setTags(new ArrayList<>(List.of("测试", "stock")));
        s.setSteps(new ArrayList<>(List.of("step1", "step2")));
        return s;
    }

    @Test
    @DisplayName("create 生成 id/slug/version/createdAt 并写 manifest + index")
    void createPersistsAndInitializes() {
        Skill s = sampleSkill("股票龙头筛选", "行业龙头筛选流程");
        Skill created = store.create(s);

        assertNotNull(created.getId());
        assertTrue(created.getId().startsWith("sk_"));
        assertNotNull(created.getSlug());
        assertEquals(1, created.getVersion());
        assertEquals(SkillStatus.DRAFT, created.getStatus());
        assertEquals(0, created.getUseCount());
        assertNotNull(created.getCreatedAt());
        // manifest 文件存在
        assertTrue(Files.exists(store.getManifestsDir().resolve(created.getId() + ".json")));
        // index.json 存在
        assertTrue(Files.exists(store.getIndexPath()));
    }

    @Test
    @DisplayName("create 名字为空抛 Invalid")
    void createRejectsBlankName() {
        Skill s = new Skill();
        s.setName("");
        s.setDescription("desc");
        assertThrows(SkillStoreException.Invalid.class, () -> store.create(s));
    }

    @Test
    @DisplayName("create description 为空抛 Invalid")
    void createRejectsBlankDesc() {
        Skill s = new Skill();
        s.setName("name");
        s.setDescription("  ");
        assertThrows(SkillStoreException.Invalid.class, () -> store.create(s));
    }

    @Test
    @DisplayName("create 名字超过 80 字符抛 Invalid")
    void createRejectsLongName() {
        Skill s = new Skill();
        s.setName("x".repeat(81));
        s.setDescription("desc");
        assertThrows(SkillStoreException.Invalid.class, () -> store.create(s));
    }

    @Test
    @DisplayName("load 找不到抛 NotFound")
    void loadNotFound() {
        assertThrows(SkillStoreException.NotFound.class, () -> store.load("sk_nonexistent"));
    }

    @Test
    @DisplayName("findBySlug 能找到 slug 匹配的技能")
    void findBySlugFinds() {
        Skill s = sampleSkill("行业龙头", "描述");
        Skill created = store.create(s);
        Skill bySlug = store.findBySlug(created.getSlug());
        assertEquals(created.getId(), bySlug.getId());
    }

    @Test
    @DisplayName("findBySlug 找不到抛 NotFound")
    void findBySlugNotFound() {
        assertThrows(SkillStoreException.NotFound.class, () -> store.findBySlug("does-not-exist"));
    }

    @Test
    @DisplayName("update 自增版本并归档旧版本")
    void updateIncrementsVersionAndArchives() {
        Skill s = store.create(sampleSkill("A", "desc"));
        String id = s.getId();

        Skill patch = new Skill();
        patch.setDescription("updated desc");
        patch.setTags(new ArrayList<>(List.of("new-tag")));
        Skill updated = store.update(id, patch);

        assertEquals(2, updated.getVersion());
        assertEquals("updated desc", updated.getDescription());
        assertTrue(updated.getTags().contains("new-tag"));

        // 旧版本 v1 已归档
        List<SkillStore.VersionEntry> versions = store.listVersions(id);
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0).version());

        Skill v1Snapshot = store.readVersion(id, 1);
        assertEquals(1, v1Snapshot.getVersion());
        assertEquals("desc", v1Snapshot.getDescription());
    }

    @Test
    @DisplayName("publish 将 DRAFT 转为 PUBLISHED 并写入 publishedAt")
    void publishTransitionsStatus() {
        Skill s = store.create(sampleSkill("A", "desc"));
        assertEquals(SkillStatus.DRAFT, s.getStatus());
        Skill published = store.publish(s.getId());
        assertEquals(SkillStatus.PUBLISHED, published.getStatus());
        assertNotNull(published.getPublishedAt());
    }

    @Test
    @DisplayName("重复 publish 幂等（publishedAt 不变）")
    void publishIdempotent() {
        Skill s = store.create(sampleSkill("A", "desc"));
        Skill p1 = store.publish(s.getId());
        Instant t1 = p1.getPublishedAt();
        Skill p2 = store.publish(s.getId());
        assertEquals(t1, p2.getPublishedAt());
    }

    @Test
    @DisplayName("deprecate 转为 DEPRECATED")
    void deprecateTransitionsStatus() {
        Skill s = store.create(sampleSkill("A", "desc"));
        Skill d = store.deprecate(s.getId());
        assertEquals(SkillStatus.DEPRECATED, d.getStatus());
    }

    @Test
    @DisplayName("incrementUse 增加 useCount 并更新 lastUsedAt")
    void incrementUseBumpsCount() {
        Skill s = store.create(sampleSkill("A", "desc"));
        assertNull(s.getLastUsedAt());
        store.incrementUse(s.getId());
        store.incrementUse(s.getId());
        Skill reloaded = store.load(s.getId());
        assertEquals(2, reloaded.getUseCount());
        assertNotNull(reloaded.getLastUsedAt());
    }

    @Test
    @DisplayName("incrementUse 在技能不存在时返回 null 而不抛异常")
    void incrementUseMissingReturnsNull() {
        assertNull(store.incrementUse("sk_does_not_exist"));
    }

    @Test
    @DisplayName("reloadFromDisk 从磁盘重建缓存")
    void reloadFromDisk() {
        Skill s = store.create(sampleSkill("A", "desc"));
        store.publish(s.getId());

        // 新建一个 store 实例（模拟重启）
        SkillStore fresh = new SkillStore(tmp.resolve("skills"));
        Skill loaded = fresh.load(s.getId());
        assertEquals(s.getName(), loaded.getName());
        assertEquals(SkillStatus.PUBLISHED, loaded.getStatus());
        assertEquals(1, loaded.getVersion());
    }

    @Test
    @DisplayName("index.json 缺失时从 manifests 自动重建")
    void rebuildsIndexWhenMissing() {
        store.create(sampleSkill("A", "desc"));
        // 删掉 index.json，强制重建
        assertDoesNotThrow(() -> Files.deleteIfExists(store.getIndexPath()));

        SkillStore fresh = new SkillStore(tmp.resolve("skills"));
        assertEquals(1, fresh.listAll().size());
        assertTrue(Files.exists(fresh.getIndexPath())); // 重建后 index.json 应已写回
    }

    @Test
    @DisplayName("purge 物理删除技能 + 版本目录 + index 条目")
    void purgeRemovesAll() {
        Skill s = store.create(sampleSkill("A", "desc"));
        store.update(s.getId(), new Skill()); // 制造一个历史版本
        String id = s.getId();

        store.purge(id);
        assertFalse(store.exists(id));
        assertFalse(Files.exists(store.getManifestsDir().resolve(id + ".json")));
        assertFalse(Files.isDirectory(store.getVersionsDir().resolve(id)));
    }

    @Test
    @DisplayName("purge 不存在的技能抛 NotFound")
    void purgeMissingThrows() {
        assertThrows(SkillStoreException.NotFound.class, () -> store.purge("sk_nope"));
    }

    @Test
    @DisplayName("slugify 处理 CJK 与特殊字符")
    void slugifyHandlesCjk() {
        String slug = SkillStore.slugify("股票 龙头.筛选/Tools");
        // CJK 转 _，空格/点/斜杠转 -
        assertFalse(slug.matches(".*[^\sa-z0-9_-].*".replace("\\s", "")));
        assertTrue(slug.length() <= 60);
        assertEquals("skill", SkillStore.slugify(""));
        assertEquals("skill", SkillStore.slugify(null));
    }

    @Test
    @DisplayName("listVersions 多版本按版本号升序")
    void listVersionsAscending() {
        Skill s = store.create(sampleSkill("A", "desc"));
        for (int i = 0; i < 3; i++) {
            Skill patch = new Skill();
            patch.setDescription("v" + (i + 2));
            store.update(s.getId(), patch);
        }
        List<SkillStore.VersionEntry> versions = store.listVersions(s.getId());
        assertEquals(3, versions.size());
        assertEquals(1, versions.get(0).version());
        assertEquals(3, versions.get(2).version());
    }

    @Test
    @DisplayName("appendAccess 写 JSONL；readAccessLog 读回")
    void accessLogRoundTrip() {
        SkillAccessEntry e1 = new SkillAccessEntry(Instant.now(), "sk_a", "get", null, "tester");
        SkillAccessEntry e2 = new SkillAccessEntry(Instant.now(), null, "list", null, "tester");
        SkillAccessEntry e3 = new SkillAccessEntry(Instant.now(), null, "search", "股票", "tester");
        store.appendAccess(e1);
        store.appendAccess(e2);
        store.appendAccess(e3);

        List<SkillAccessEntry> entries = store.readAccessLog();
        assertEquals(3, entries.size());
        assertEquals("sk_a", entries.get(0).skillId());
        assertEquals("list", entries.get(1).action());
        assertEquals("股票", entries.get(2).query());
    }

    @Test
    @DisplayName("readAccessLog 在日志不存在时返回空列表")
    void readAccessLogEmptyWhenAbsent() {
        assertTrue(store.readAccessLog().isEmpty());
    }

    @Test
    @DisplayName("readAccessLog 容忍坏行（不抛异常）")
    void readAccessLogToleratesBadLines() throws Exception {
        Files.writeString(store.getAccessLog(),
                "{\"timestamp\":\"" + Instant.now() + "\",\"skillId\":\"sk_x\",\"action\":\"get\"}\n"
                        + "this-is-not-json\n"
                        + "{\"action\":\"search\",\"query\":\"x\"}\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        List<SkillAccessEntry> entries = store.readAccessLog();
        // 第 3 行 timestamp 缺失但 compact ctor 会填 now，仍可解析 → 2 条合法
        assertEquals(2, entries.size());
    }

    @Test
    @DisplayName("并发 create 不丢数据（ReentrantLock 保护）")
    void concurrentCreateSafe() throws Exception {
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    store.create(sampleSkill("skill-" + idx, "desc-" + idx));
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, errors.get());
        assertEquals(n, store.listAll().size());

        // 重新加载，确认 manifest 数 = cache 数 = n（无丢、无重复）
        SkillStore fresh = new SkillStore(tmp.resolve("skills"));
        assertEquals(n, fresh.listAll().size());
    }

    @Test
    @DisplayName("listSummaries 字段完整")
    void listSummariesFields() {
        Skill s = store.create(sampleSkill("X", "desc"));
        List<SkillStore.SkillSummary> summaries = store.listSummaries();
        assertEquals(1, summaries.size());
        SkillStore.SkillSummary sum = summaries.get(0);
        assertEquals(s.getId(), sum.id);
        assertEquals(s.getName(), sum.name);
        assertEquals(s.getVersion(), sum.version);
        assertEquals(s.getStatus(), sum.status);
    }
}
