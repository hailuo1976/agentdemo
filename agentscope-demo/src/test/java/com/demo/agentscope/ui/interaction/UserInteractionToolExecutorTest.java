package com.demo.agentscope.ui.interaction;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserInteractionToolExecutor} 单元测试。
 * <p>
 * 注入一个 mock {@link UserInteractionToolExecutor.InputReader}，
 * 把预设的若干次「用户输入」按顺序回放，验证不同模式下的分支与解析逻辑。
 * </p>
 */
class UserInteractionToolExecutorTest {

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

    private UserInteractionToolExecutor executorWith(String... inputs) {
        Deque<String> q = new ArrayDeque<>(List.of(inputs));
        return new UserInteractionToolExecutor(prompt -> q.poll(), new PrintStream(capturedOut, true));
    }

    @Test
    @DisplayName("choice 正常选择返回 ok 与正确 value")
    void choiceNormal() throws Exception {
        UserInteractionToolExecutor exec = executorWith("2");
        String json = exec.execute(Map.of(
                "prompt", "选一个",
                "mode", "choice",
                "options", List.of("苹果", "香蕉", "橘子")));
        assertTrue(json.contains("\"status\":\"ok\""));
        assertTrue(json.contains("\"value\":\"香蕉\""));
        assertTrue(json.contains("\"indices\":[1]"));
    }

    @Test
    @DisplayName("choice 输入 Other 返回 other 状态")
    void choiceOther() throws Exception {
        UserInteractionToolExecutor exec = executorWith("o", "自定义答案");
        String json = exec.execute(Map.of(
                "prompt", "选一个",
                "mode", "choice",
                "options", List.of("A", "B"),
                "allow_other", true));
        assertTrue(json.contains("\"status\":\"other\""));
        assertTrue(json.contains("自定义答案"));
    }

    @Test
    @DisplayName("multi 多选按逗号解析")
    void multiSelect() throws Exception {
        UserInteractionToolExecutor exec = executorWith("1,3");
        String json = exec.execute(Map.of(
                "prompt", "多选",
                "mode", "multi",
                "options", List.of("A", "B", "C")));
        assertTrue(json.contains("\"status\":\"ok\""));
        assertTrue(json.contains("\"indices\":[0,2]"));
        assertTrue(json.contains("\"value\":\"A, C\""));
    }

    @Test
    @DisplayName("fill 默认值兜底")
    void fillDefault() throws Exception {
        UserInteractionToolExecutor exec = executorWith("");
        String json = exec.execute(Map.of(
                "prompt", "你的名字",
                "mode", "fill",
                "default", "无名氏"));
        assertTrue(json.contains("\"status\":\"ok\""));
        assertTrue(json.contains("\"value\":\"无名氏\""));
    }

    @Test
    @DisplayName("fill 正常输入返回 value=输入")
    void fillInput() throws Exception {
        UserInteractionToolExecutor exec = executorWith("Alice");
        String json = exec.execute(Map.of(
                "prompt", "名字",
                "mode", "fill"));
        assertTrue(json.contains("\"status\":\"ok\""));
        assertTrue(json.contains("\"value\":\"Alice\""));
    }

    @Test
    @DisplayName("confirm yes 识别为 yes")
    void confirmYes() throws Exception {
        UserInteractionToolExecutor exec = executorWith("y");
        String json = exec.execute(Map.of(
                "prompt", "继续吗",
                "mode", "confirm"));
        assertTrue(json.contains("\"status\":\"ok\""));
        assertTrue(json.contains("\"value\":\"yes\""));
    }

    @Test
    @DisplayName("confirm no 识别为 no")
    void confirmNo() throws Exception {
        UserInteractionToolExecutor exec = executorWith("n");
        String json = exec.execute(Map.of(
                "prompt", "继续吗",
                "mode", "confirm"));
        assertTrue(json.contains("\"value\":\"no\""));
    }

    @Test
    @DisplayName("非法输入超过重试上限返回 cancelled")
    void invalidInputRetriesOut() throws Exception {
        UserInteractionToolExecutor exec = executorWith("xyz", "???", "!");
        String json = exec.execute(Map.of(
                "prompt", "选一个",
                "mode", "choice",
                "options", List.of("A", "B")));
        assertTrue(json.contains("\"status\":\"cancelled\""));
        assertTrue(json.contains("超过重试上限"));
    }

    @Test
    @DisplayName("choice 缺少 options 返回 error")
    void choiceMissingOptions() throws Exception {
        UserInteractionToolExecutor exec = executorWith("1");
        String json = exec.execute(Map.of(
                "prompt", "选一个",
                "mode", "choice"));
        assertTrue(json.contains("\"status\":\"error\""));
        assertTrue(json.contains("options"));
    }

    @Test
    @DisplayName("allow_cancel 时输入 c 返回 cancelled")
    void cancelByUser() throws Exception {
        UserInteractionToolExecutor exec = executorWith("c");
        String json = exec.execute(Map.of(
                "prompt", "选一个",
                "mode", "choice",
                "options", List.of("A", "B")));
        assertTrue(json.contains("\"status\":\"cancelled\""));
    }
}
