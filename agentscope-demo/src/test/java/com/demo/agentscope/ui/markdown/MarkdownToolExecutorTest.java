package com.demo.agentscope.ui.markdown;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownToolExecutor} 的单元测试。
 * <p>
 * 通过捕获 {@link System#out} 验证渲染结果被正确打印；通过返回值验证状态 JSON。
 * </p>
 */
class MarkdownToolExecutorTest {

    private ByteArrayOutputStream capturedOut;
    private PrintStream originalOut;

    @BeforeEach
    void redirectStdout() {
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    @DisplayName("缺失 content 返回 error 状态")
    void missingContentReturnsError() throws Exception {
        MarkdownToolExecutor exec = new MarkdownToolExecutor();
        String result = exec.execute(Map.of());
        assertTrue(result.contains("\"status\":\"error\""));
        assertTrue(result.contains("content"));
    }

    @Test
    @DisplayName("空白 content 返回 error 状态")
    void blankContentReturnsError() throws Exception {
        MarkdownToolExecutor exec = new MarkdownToolExecutor();
        String result = exec.execute(Map.of("content", "   "));
        assertTrue(result.contains("\"status\":\"error\""));
    }

    @Test
    @DisplayName("合法 content 打印渲染结果并返回 ok 状态")
    void validContentPrintsAndReturnsOk() throws Exception {
        MarkdownToolExecutor exec = new MarkdownToolExecutor();
        String md = "# Title\n\n- one\n- two";
        String result = exec.execute(Map.of("content", md));
        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"bytes\""));
        assertTrue(result.contains("\"lines\""));
        String printed = capturedOut.toString();
        assertTrue(printed.contains("Title"));
        assertTrue(printed.contains("one"));
        assertTrue(printed.contains("two"));
    }

    @Test
    @DisplayName("theme=light 不抛异常")
    void lightThemeRuns() throws Exception {
        MarkdownToolExecutor exec = new MarkdownToolExecutor();
        String result = exec.execute(Map.of(
                "content", "# Hi",
                "theme", "light"));
        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"theme\":\"light\""));
    }
}
