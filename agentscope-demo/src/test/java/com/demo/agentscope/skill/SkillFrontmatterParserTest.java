package com.demo.agentscope.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillFrontmatterParser} 单元测试。
 * <p>
 * 覆盖：无 frontmatter / flat kv / list / 往返 / 非法行容忍 / CJK / 自动加引号。
 * </p>
 */
class SkillFrontmatterParserTest {

    @Test
    @DisplayName("无 frontmatter 时 body 为原文")
    void noFrontmatter() {
        String md = "# 标题\n\n正文";
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertTrue(p.frontmatter().isEmpty());
        assertEquals(md, p.body());
    }

    @Test
    @DisplayName("解析 flat key:value")
    void flatKv() {
        String md = "---\nname: 股票\nstatus: PUBLISHED\nversion: 3\n---\n\nbody";
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertEquals("股票", p.frontmatter().get("name"));
        assertEquals("PUBLISHED", p.frontmatter().get("status"));
        assertEquals("3", p.frontmatter().get("version"));
        assertEquals("body", p.body());
    }

    @Test
    @DisplayName("解析 list（缩进 + dash）")
    void listValue() {
        String md = "---\ntags:\n  - 股票\n  - 量化\n---\nbody";
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        Object tags = p.frontmatter().get("tags");
        assertTrue(tags instanceof List);
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) tags;
        assertEquals(List.of("股票", "量化"), list);
    }

    @Test
    @DisplayName("往返 round-trip：serialize → parse 应等价")
    void roundTrip() {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", "sk_1234_abcd1234");
        fm.put("name", "股票龙头筛选");
        fm.put("tags", List.of("股票", "量化"));
        fm.put("status", "PUBLISHED");
        fm.put("version", 3);
        fm.put("useCount", 42);
        String body = "# 股票龙头筛选\n\n## 描述\n用 select_industry_leaders 筛选行业龙头。\n";
        String md = SkillFrontmatterParser.serialize(fm, body);

        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertEquals("sk_1234_abcd1234", p.frontmatter().get("id"));
        assertEquals("股票龙头筛选", p.frontmatter().get("name"));
        assertEquals("PUBLISHED", p.frontmatter().get("status"));
        assertEquals("3", p.frontmatter().get("version"));
        assertTrue(p.frontmatter().get("tags") instanceof List);
        assertEquals(2, ((List<?>) p.frontmatter().get("tags")).size());
        assertTrue(p.body().startsWith("# 股票龙头筛选"));
        assertTrue(p.body().contains("select_industry_leaders"));
    }

    @Test
    @DisplayName("含 YAML 特殊字符的值自动加引号（CJK + 冒号空格）")
    void autoQuoteSpecialChars() {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("description", "行业: 龙头 # 注释"); // 冒号空格 + # 都是 YAML 特殊字符
        fm.put("note", "用 [tools] 与 {a: b} 表示");
        String md = SkillFrontmatterParser.serialize(fm, "");
        // 序列化后值两端应有双引号
        assertTrue(md.contains("description: \""));
        assertTrue(md.contains("note: \""));

        // 反过来解析能还原
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertEquals("行业: 龙头 # 注释", p.frontmatter().get("description"));
        assertEquals("用 [tools] 与 {a: b} 表示", p.frontmatter().get("note"));
    }

    @Test
    @DisplayName("quoteProblematicValues 对未加引号的特殊值加引号")
    void quoteProblematicValuesDirect() {
        String block = "name: a: b\ndesc: hello # world\nok: normal\n";
        String fixed = SkillFrontmatterParser.quoteProblematicValues(block);
        // 含冒号空格、# 的行被加引号
        assertTrue(fixed.contains("name: \"a: b\""));
        assertTrue(fixed.contains("desc: \"hello # world\""));
        assertTrue(fixed.contains("ok: normal"));
    }

    @Test
    @DisplayName("非法行（无 colon）触发降级 raw 解析（不抛异常）")
    void illegalLineFallback() {
        // 第二行不是 key:value 也不是 list item，会触发重试 + 降级
        String md = "---\nname: ok\nthis-line-has-no-colon\ntitle: 标题\n---\nbody";
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        // 至少能拿到 name 与 title
        assertEquals("ok", p.frontmatter().get("name"));
        assertEquals("标题", p.frontmatter().get("title"));
        assertEquals("body", p.body());
    }

    @Test
    @DisplayName("frontmatter 后无空行也能解析")
    void frontmatterNoTrailingNewline() {
        String md = "---\nname: x\n---\n# body";
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertEquals("x", p.frontmatter().get("name"));
        assertEquals("# body", p.body());
    }

    @Test
    @DisplayName("CJK key 与 value 正常解析")
    void cjkSupport() {
        String md = "---\n名称: 测试技能\n标签:\n  - 中文标签\n---\nbody";
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertEquals("测试技能", p.frontmatter().get("名称"));
        assertEquals(List.of("中文标签"), p.frontmatter().get("标签"));
    }

    @Test
    @DisplayName("单引号 / 双引号包裹的值会被去引号")
    void quotedValues() {
        String md = "---\na: \"hello world\"\nb: 'foo bar'\n---\nx";
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertEquals("hello world", p.frontmatter().get("a"));
        assertEquals("foo bar", p.frontmatter().get("b"));
    }

    @Test
    @DisplayName("注释行被跳过")
    void commentsSkipped() {
        String md = "---\n# 这是注释\nname: x\n---\nbody";
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertNull(p.frontmatter().get("这是注释"));
        assertEquals("x", p.frontmatter().get("name"));
    }

    @Test
    @DisplayName("空 list 输出 [] 占位")
    void emptyList() {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("tags", List.of());
        String md = SkillFrontmatterParser.serialize(fm, "");
        assertTrue(md.contains("tags: []"));
    }

    @Test
    @DisplayName("serialize 后再 parse，body 含 CJK 与代码块不破坏")
    void serializeThenParseBodyIntegrity() {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("name", "test");
        String body = "## 步骤\n\n```python\nprint('hello')\n```\n";
        String md = SkillFrontmatterParser.serialize(fm, body);
        SkillFrontmatterParser.Parsed p = SkillFrontmatterParser.parse(md);
        assertEquals("test", p.frontmatter().get("name"));
        assertTrue(p.body().contains("```python"));
        assertTrue(p.body().contains("print('hello')"));
    }
}
