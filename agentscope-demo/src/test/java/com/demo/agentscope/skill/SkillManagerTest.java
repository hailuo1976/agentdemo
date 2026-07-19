package com.demo.agentscope.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillManager} 单元测试。
 * <p>
 * 覆盖：字段校验 / slug 规范化 / list 过滤 / 软删除确认 / 搜索权重 / stats 时间窗 / export+import 往返。
 * </p>
 */
class SkillManagerTest {

    @TempDir
    Path tmp;
    private SkillManager mgr;

    @BeforeEach
    void setup() {
        mgr = new SkillManager(tmp.resolve("skills"), "tester");
    }

    private Skill newSkill(String name, String desc, String... tags) {
        Skill s = new Skill();
        s.setName(name);
        s.setDescription(desc);
        if (tags.length > 0) s.setTags(new ArrayList<>(List.of(tags)));
        return s;
    }

    // ==================== 字段校验 ====================

    @Test
    @DisplayName("create 拒绝 name 为空")
    void createRejectsBlankName() {
        Skill s = newSkill("", "描述");
        assertThrows(SkillStoreException.Invalid.class, () -> mgr.create(s));
    }

    @Test
    @DisplayName("create 拒绝 name 超过 80 字符上限")
    void createRejectsTooLongName() {
        Skill s = newSkill("a".repeat(81), "描述");
        assertThrows(SkillStoreException.Invalid.class, () -> mgr.create(s));
    }

    @Test
    @DisplayName("create 拒绝 description 为空")
    void createRejectsBlankDescription() {
        Skill s = newSkill("name", "   ");
        assertThrows(SkillStoreException.Invalid.class, () -> mgr.create(s));
    }

    @Test
    @DisplayName("create 自动去重 + trim tags")
    void createDedupTags() {
        Skill s = newSkill("n", "d", "  股票  ", "股票", "量化");
        Skill created = mgr.create(s);
        assertEquals(List.of("股票", "量化"), created.getTags());
    }

    @Test
    @DisplayName("create 生成 id 与 slug 并写入 access.log")
    void createGeneratesIdAndLogs() {
        Skill created = mgr.create(newSkill("股票筛选", "描述", "量化"));
        assertNotNull(created.getId());
        assertNotNull(created.getSlug());
        assertEquals("股票筛选", created.getName());
        // access.log 应该有 create 一条
        List<SkillAccessEntry> log = mgr.getStore().readAccessLog();
        assertTrue(log.stream().anyMatch(e -> "create".equals(e.action())));
    }

    // ==================== get / update / 软删除 ====================

    @Test
    @DisplayName("get 触发 incrementUse 并写入 access.log")
    void getIncrementsUse() {
        Skill s = mgr.create(newSkill("n", "d"));
        int before = s.getUseCount();
        Skill fetched = mgr.get(s.getId());
        assertEquals(before + 1, fetched.getUseCount());
        // access.log 应有 create + get
        List<SkillAccessEntry> log = mgr.getStore().readAccessLog();
        assertTrue(log.stream().anyMatch(e -> "get".equals(e.action())));
    }

    @Test
    @DisplayName("update 自增 version")
    void updateIncrementsVersion() {
        Skill s = mgr.create(newSkill("n", "d"));
        Skill patch = new Skill();
        patch.setDescription("new desc");
        Skill updated = mgr.update(s.getId(), patch);
        assertEquals(2, updated.getVersion());
        assertEquals("new desc", updated.getDescription());
    }

    @Test
    @DisplayName("delete 无 confirm=true 抛异常")
    void deleteRequiresConfirm() {
        Skill s = mgr.create(newSkill("n", "d"));
        assertThrows(SkillStoreException.Invalid.class, () -> mgr.delete(s.getId(), false));
    }

    @Test
    @DisplayName("delete 带 confirm=true 软删除到 DEPRECATED")
    void deleteSoftDeletes() {
        Skill s = mgr.create(newSkill("n", "d"));
        Skill deleted = mgr.delete(s.getId(), true);
        assertEquals(SkillStatus.DEPRECATED, deleted.getStatus());
    }

    @Test
    @DisplayName("publish 与 deprecate 切换状态")
    void publishAndDeprecate() {
        Skill s = mgr.create(newSkill("n", "d"));
        Skill pub = mgr.publish(s.getId());
        assertEquals(SkillStatus.PUBLISHED, pub.getStatus());
        Skill dep = mgr.deprecate(s.getId());
        assertEquals(SkillStatus.DEPRECATED, dep.getStatus());
    }

    // ==================== list / search ====================

    @Test
    @DisplayName("list 支持 tag 与 status 过滤、limit 截断")
    void listFiltering() {
        mgr.create(newSkill("s1", "d1", "量化"));
        mgr.create(newSkill("s2", "d2", "股票"));
        mgr.create(newSkill("s3", "d3", "量化"));
        // 全部
        assertEquals(3, mgr.list(null, null, 0).size());
        // tag 过滤
        assertEquals(2, mgr.list("量化", null, 0).size());
        // limit 截断
        assertEquals(1, mgr.list(null, null, 1).size());
        // 不存在的 tag
        assertEquals(0, mgr.list("不存在", null, 0).size());
    }

    @Test
    @DisplayName("list 按 status 过滤")
    void listByStatus() {
        Skill a = mgr.create(newSkill("a", "d"));
        mgr.create(newSkill("b", "d"));
        mgr.publish(a.getId());
        assertEquals(2, mgr.list(null, null, 0).size());
        assertEquals(1, mgr.list(null, "PUBLISHED", 0).size());
        assertEquals(1, mgr.list(null, "DRAFT", 0).size());
    }

    @Test
    @DisplayName("list 容忍非法 status 字符串（返回不过滤）")
    void listIgnoresBogusStatus() {
        mgr.create(newSkill("a", "d"));
        mgr.create(newSkill("b", "d"));
        assertEquals(2, mgr.list(null, "BOGUS", 0).size());
    }

    @Test
    @DisplayName("search name 命中权重高于 description 命中")
    void searchNameBeatsDescription() {
        // 一个 name 含 "股票" 一个 description 含 "股票"
        Skill nameHit = new Skill();
        nameHit.setName("股票龙头");
        nameHit.setDescription("无关描述");
        Skill descHit = new Skill();
        descHit.setName("其他");
        descHit.setDescription("这是一个股票策略");
        mgr.create(nameHit);
        mgr.create(descHit);
        List<SkillSearcher.Result> r = mgr.search("股票", null, 10);
        assertEquals(2, r.size());
        assertEquals("股票龙头", r.get(0).skill().getName());
        assertTrue(r.get(0).score() > r.get(1).score());
    }

    @Test
    @DisplayName("search PUBLISHED 额外加分")
    void searchPublishedBonus() {
        Skill a = mgr.create(newSkill("股票A", "d"));
        mgr.create(newSkill("股票B", "d"));
        mgr.publish(a.getId());
        List<SkillSearcher.Result> r = mgr.search("股票", null, 10);
        assertEquals("股票A", r.get(0).skill().getName());
    }

    @Test
    @DisplayName("search tag 预过滤生效")
    void searchWithTagPrefilter() {
        mgr.create(newSkill("股票1", "d", "量化"));
        mgr.create(newSkill("股票2", "d", "股票"));
        List<SkillSearcher.Result> r = mgr.search("股票", "量化", 10);
        assertEquals(1, r.size());
        assertEquals("股票1", r.get(0).skill().getName());
    }

    @Test
    @DisplayName("search 子序列兜底返回 fallback 标识")
    void searchSubsequenceFallback() {
        // 没有任何精确命中 → 子序列兜底
        mgr.create(newSkill("gupiaolongtou", "d")); // 拼音也能匹配子序列
        List<SkillSearcher.Result> r = mgr.search("gplt", null, 10);
        assertFalse(r.isEmpty());
        assertEquals("subsequence", r.get(0).fallback());
    }

    @Test
    @DisplayName("search 空 query 返回空")
    void searchEmptyQuery() {
        mgr.create(newSkill("a", "d"));
        assertEquals(0, mgr.search("", null, 10).size());
        assertEquals(0, mgr.search("   ", null, 10).size());
    }

    // ==================== history ====================

    @Test
    @DisplayName("history 列出版本")
    void historyListsVersions() {
        Skill s = mgr.create(newSkill("n", "d"));
        Skill patch = new Skill();
        patch.setDescription("d2");
        mgr.update(s.getId(), patch);
        List<SkillStore.VersionEntry> h = mgr.history(s.getId());
        assertEquals(2, h.size());
    }

    @Test
    @DisplayName("history 不存在的 id 抛 NotFound")
    void historyNotFound() {
        assertThrows(SkillStoreException.NotFound.class, () -> mgr.history("sk_does_not_exist"));
    }

    // ==================== export / import ====================

    @Test
    @DisplayName("export + import 往返：导出 md → 重新导入为新建技能")
    void exportImportRoundTrip() throws IOException {
        Skill s = mgr.create(newSkill("股票龙头", "策略描述", "量化", "股票"));
        Path exportDir = tmp.resolve("export");
        List<String> files = mgr.exportTo(exportDir, null, null);
        assertEquals(1, files.size());
        assertTrue(files.get(0).endsWith(".md"));
        assertTrue(Files.isRegularFile(exportDir.resolve(files.get(0))));

        // 重新导入到新 workspace
        SkillManager mgr2 = new SkillManager(tmp.resolve("skills2"), "tester2");
        List<Map<String, String>> results = mgr2.importFrom(exportDir);
        assertEquals(1, results.size());
        assertEquals("created", results.get(0).get("action"));
        assertNotNull(results.get(0).get("id"));
        Skill restored = mgr2.get(results.get(0).get("id"));
        assertEquals("股票龙头", restored.getName());
        assertEquals("策略描述", restored.getDescription());
        // 标签去重后顺序一致
        assertTrue(restored.getTags().containsAll(List.of("量化", "股票")));
    }

    @Test
    @DisplayName("export 时 tag 过滤")
    void exportWithTagFilter() throws IOException {
        mgr.create(newSkill("a", "d", "量化"));
        mgr.create(newSkill("b", "d", "股票"));
        Path exportDir = tmp.resolve("export");
        List<String> files = mgr.exportTo(exportDir, "量化", null);
        assertEquals(1, files.size());
    }

    @Test
    @DisplayName("export 时 ids 白名单过滤")
    void exportWithIdsFilter() throws IOException {
        Skill a = mgr.create(newSkill("a", "d"));
        mgr.create(newSkill("b", "d"));
        Path exportDir = tmp.resolve("export");
        List<String> files = mgr.exportTo(exportDir, null, List.of(a.getId()));
        assertEquals(1, files.size());
    }

    @Test
    @DisplayName("export 同名 slug 自动加序号防冲突")
    void exportDedupFileName() throws IOException {
        mgr.create(newSkill("同名", "d1"));
        mgr.create(newSkill("同名", "d2"));
        Path exportDir = tmp.resolve("export");
        List<String> files = mgr.exportTo(exportDir, null, null);
        assertEquals(2, files.size());
        // 一个 base.md 一个 base-1.md
        assertTrue(files.stream().anyMatch(f -> f.endsWith(".md") && !f.contains("-")));
        assertTrue(files.stream().anyMatch(f -> f.contains("-1")));
    }

    @Test
    @DisplayName("import 跳过 name 缺失的 md 文件")
    void importSkipsMissingName() throws IOException {
        Path exportDir = tmp.resolve("export");
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("bogus.md"), "## 描述\n没有 frontmatter 也没有 H1\n");
        SkillManager mgr2 = new SkillManager(tmp.resolve("skills2"), "tester2");
        List<Map<String, String>> results = mgr2.importFrom(exportDir);
        assertEquals(1, results.size());
        assertEquals("skipped", results.get(0).get("action"));
    }

    @Test
    @DisplayName("import 不存在的目录抛 IO")
    void importMissingDir() {
        SkillStoreException ex = assertThrows(SkillStoreException.IO.class,
                () -> mgr.importFrom(tmp.resolve("does-not-exist")));
        assertNotNull(ex.getMessage());
    }

    // ==================== stats ====================

    @Test
    @DisplayName("stats ALL 返回全部 access.log 计数")
    void statsAll() throws InterruptedException {
        Skill a = mgr.create(newSkill("a", "d"));
        mgr.get(a.getId());
        mgr.get(a.getId());
        Thread.sleep(10); // 确保时间戳有差异
        SkillStatsAggregator.Result r = mgr.stats("all");
        assertEquals(SkillStatsAggregator.Period.ALL, r.period());
        assertTrue(r.overview().totalSkills() >= 1);
        // create + get*2 + ...
        assertTrue(r.overview().totalAccesses() >= 3);
    }

    @Test
    @DisplayName("stats 7d 只保留近 7 天访问")
    void stats7d() {
        Skill a = mgr.create(newSkill("a", "d"));
        mgr.get(a.getId());
        SkillStatsAggregator.Result r = mgr.stats("7d");
        // 今天创建的应该都在窗口内
        assertTrue(r.overview().totalAccesses() >= 2);
    }

    @Test
    @DisplayName("renderStatsMarkdown 输出包含总览/柱状图/Top 技能段")
    void renderStatsMarkdown() {
        Skill a = mgr.create(newSkill("a", "d"));
        mgr.get(a.getId());
        SkillStatsAggregator.Result r = mgr.stats("all");
        String md = mgr.renderStatsMarkdown(r);
        assertTrue(md.contains("# 技能统计"));
        assertTrue(md.contains("## 总览"));
        assertTrue(md.contains("技能总数"));
        // byDay 当天应有数据，渲染出 fenced code block
        if (!r.byDay().isEmpty()) {
            assertTrue(md.contains("## 每日访问量"));
            assertTrue(md.contains("```"));
        }
        if (!r.topSkills().isEmpty()) {
            assertTrue(md.contains("## Top 技能"));
        }
        if (!r.topActions().isEmpty()) {
            assertTrue(md.contains("## 动作分布"));
        }
    }

    @Test
    @DisplayName("stats period 解析：未知字符串 → ALL")
    void statsPeriodParseUnknown() {
        SkillStatsAggregator.Result r = mgr.stats("bogus");
        assertEquals(SkillStatsAggregator.Period.ALL, r.period());
    }

    @Test
    @DisplayName("stats period 解析：null → ALL")
    void statsPeriodParseNull() {
        SkillStatsAggregator.Result r = mgr.stats(null);
        assertEquals(SkillStatsAggregator.Period.ALL, r.period());
    }

    @Test
    @DisplayName("stats period 解析：30d / 7d 大小写无关")
    void statsPeriodParseCaseInsensitive() {
        assertEquals(SkillStatsAggregator.Period.P7D, SkillStatsAggregator.Period.parse("7D"));
        assertEquals(SkillStatsAggregator.Period.P30D, SkillStatsAggregator.Period.parse(" 30d "));
        assertEquals(SkillStatsAggregator.Period.ALL, SkillStatsAggregator.Period.parse(""));
    }

    // ==================== 默认 actor ====================

    @Test
    @DisplayName("SkillManager(Path) 默认 actor=agent")
    void defaultManagerActor() {
        SkillManager m = new SkillManager(tmp.resolve("skills_default"));
        Skill s = m.create(newSkill("n", "d"));
        List<SkillAccessEntry> log = m.getStore().readAccessLog();
        assertEquals("agent", log.get(0).actor());
    }

    @Test
    @DisplayName("SkillManager(null actor) 退化为 agent")
    void nullActorFallback() {
        SkillManager m = new SkillManager(tmp.resolve("skills_null"), null);
        Skill s = m.create(newSkill("n", "d"));
        List<SkillAccessEntry> log = m.getStore().readAccessLog();
        assertEquals("agent", log.get(0).actor());
    }

    @Test
    @DisplayName("自定义 actor 透传到 access.log")
    void customActor() {
        Skill s = mgr.create(newSkill("n", "d"));
        List<SkillAccessEntry> log = mgr.getStore().readAccessLog();
        assertEquals("tester", log.get(0).actor());
    }
}
