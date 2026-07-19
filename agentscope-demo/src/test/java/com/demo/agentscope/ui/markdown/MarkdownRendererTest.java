package com.demo.agentscope.ui.markdown;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownRenderer} 的纯渲染测试。
 * <p>
 * 不验证 ANSI 转义序列的精确位置（过于脆弱），只断言关键结构特征：
 * 是否包含期望的可见文字、是否应用了颜色、解析是否不抛异常等。
 * </p>
 */
class MarkdownRendererTest {

    private String render(String md) {
        return MarkdownRenderer.render(md, "dark", 80);
    }

    @Test
    @DisplayName("空输入返回空串")
    void emptyInputReturnsEmpty() {
        assertTrue(render("").isEmpty());
        assertTrue(render(null).isEmpty());
    }

    @Test
    @DisplayName("ATX 标题加粗且保留文本")
    void headingRendered() {
        String out = render("# Hello World");
        assertTrue(out.contains("Hello World"));
        assertTrue(out.contains("#"));
        assertTrue(out.contains("\u001B[1m")); // BOLD
    }

    @Test
    @DisplayName("段落 + 行内格式：bold/code/strike 保留原文")
    void paragraphWithInline() {
        String out = render("This is **bold** and `code` and ~~strike~~ text");
        assertTrue(out.contains("bold"));
        assertTrue(out.contains("code"));
        assertTrue(out.contains("strike"));
        assertTrue(out.contains("text"));
    }

    @Test
    @DisplayName("无序列表 + task list")
    void unorderedAndTaskList() {
        String out = render("""
                - 第一项
                - 第二项
                - [ ] 未完成
                - [x] 已完成
                """);
        assertTrue(out.contains("第一项"));
        assertTrue(out.contains("第二项"));
        assertTrue(out.contains("未完成"));
        assertTrue(out.contains("已完成"));
        assertTrue(out.contains("[x]"));
        assertTrue(out.contains("[ ]"));
    }

    @Test
    @DisplayName("有序列表保留序号")
    void orderedList() {
        String out = render("""
                1. 步骤一
                2. 步骤二
                3. 步骤三
                """);
        assertTrue(out.contains("1."));
        assertTrue(out.contains("步骤一"));
        assertTrue(out.contains("步骤三"));
    }

    @Test
    @DisplayName("围栏代码块带边框与高亮")
    void fencedCodeBlock() {
        String out = render("""
                ```java
                public class Hello {
                    private int value = 42;
                }
                ```
                """);
        assertTrue(out.contains("public"));
        assertTrue(out.contains("class"));
        assertTrue(out.contains("Hello"));
        assertTrue(out.contains("42"));
        // 边框字符
        assertTrue(out.contains("│"));
        assertTrue(out.contains("┌") || out.contains("─"));
    }

    @Test
    @DisplayName("表格左右对齐与表头")
    void tableRendering() {
        String out = render("""
                | 名称 | 数量 |
                |:-----|----:|
                | 苹果 | 10 |
                | 香蕉 | 100 |
                """);
        assertTrue(out.contains("名称"));
        assertTrue(out.contains("数量"));
        assertTrue(out.contains("苹果"));
        assertTrue(out.contains("香蕉"));
        assertTrue(out.contains("│"));
        assertTrue(out.contains("┌"));
        assertTrue(out.contains("└"));
    }

    @Test
    @DisplayName("链接与图片渲染保留信息")
    void linksAndImages() {
        String out = render("""
                这是一个 [链接文本](https://example.com) 行内。

                ![替代文字](https://example.com/image.png)
                """);
        assertTrue(out.contains("链接文本"));
        assertTrue(out.contains("https://example.com"));
        assertTrue(out.contains("替代文字"));
        assertTrue(out.contains("[图片:"));
    }

    @Test
    @DisplayName("分隔线渲染")
    void horizontalRule() {
        String out = render("第一段\n\n---\n\n第二段");
        assertTrue(out.contains("第一段"));
        assertTrue(out.contains("第二段"));
        assertTrue(out.contains("─"));
    }

    @Test
    @DisplayName("纯文本透传（无格式符号）")
    void plainTextPassesThrough() {
        String out = render("这是普通的一行文字");
        assertTrue(out.contains("这是普通的一行文字"));
        // 不应该含 BOLD 等
        assertFalse(out.contains("**"));
    }
}
