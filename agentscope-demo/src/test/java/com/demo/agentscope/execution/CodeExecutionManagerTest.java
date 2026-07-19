package com.demo.agentscope.execution;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代码执行管理器集成测试。
 * <p>
 * 验证 Python 代码执行、Shell 命令执行和包安装的实际功能。
 * </p>
 */
@DisplayName("代码执行管理器测试")
class CodeExecutionManagerTest {

    private CodeExecutionManager manager;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("exec-test");
        tempDir.toFile().deleteOnExit();
        manager = new CodeExecutionManager(tempDir, 30);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
    }

    // ===== Python 执行测试 =====

    @Test
    @DisplayName("执行简单 Python 代码")
    void testExecuteSimplePython() {
        CodeExecutionManager.ExecutionResult result = manager.executePython("print('Hello, World!')");
        assertTrue(result.isSuccess(), "Python 执行应成功: " + result);
        assertTrue(result.getStdout().contains("Hello, World!"));
    }

    @Test
    @DisplayName("执行 Python 数学计算")
    void testExecutePythonMath() {
        CodeExecutionManager.ExecutionResult result = manager.executePython(
                "x = 10\ny = 20\nprint(x + y)");
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().trim().contains("30"));
    }

    @Test
    @DisplayName("Python 代码有语法错误时返回非零退出码")
    void testPythonSyntaxError() {
        CodeExecutionManager.ExecutionResult result = manager.executePython("print('unclosed)");
        assertFalse(result.isSuccess());
        assertFalse(result.getStderr().isEmpty());
    }

    @Test
    @DisplayName("Python 多行代码执行")
    void testPythonMultiline() {
        String code = """
                import math
                result = math.sqrt(144)
                print(f"sqrt(144) = {result}")
                """;
        CodeExecutionManager.ExecutionResult result = manager.executePython(code);
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("12"));
    }

    // ===== Shell 命令执行测试 =====

    @Test
    @DisplayName("执行简单 Shell 命令")
    void testExecuteSimpleCommand() {
        CodeExecutionManager.ExecutionResult result = manager.executeCommand("echo 'hello shell'");
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("hello shell"));
    }

    @Test
    @DisplayName("Shell 命令返回当前目录")
    void testExecutePwd() {
        CodeExecutionManager.ExecutionResult result = manager.executeCommand("pwd");
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains(tempDir.toString()));
    }

    @Test
    @DisplayName("Shell 命令创建文件")
    void testCreateFileViaShell() {
        manager.executeCommand("echo test > output.txt");
        CodeExecutionManager.ExecutionResult result = manager.executeCommand("cat output.txt");
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("test"));
    }

    // ===== 安全检查测试 =====

    @Test
    @DisplayName("危险 Shell 命令应被拦截")
    void testDangerousCommandBlocked() {
        CodeExecutionManager.ExecutionResult result = manager.executeCommand("rm -rf /");
        assertTrue(result.isBlocked());
        assertFalse(result.getErrorMessage().isEmpty(), "blocked 结果应有错误说明");
    }

    @Test
    @DisplayName("危险 Python 代码应被拦截")
    void testDangerousPythonBlocked() {
        CodeExecutionManager.ExecutionResult result = manager.executePython("os.system('rm -rf /')");
        assertTrue(result.isBlocked());
    }

    @Test
    @DisplayName("安全检查不影响正常命令执行")
    void testSafeCommandNotBlocked() {
        CodeExecutionManager.ExecutionResult result = manager.executeCommand("ls -la");
        assertFalse(result.isBlocked());
        assertTrue(result.isSuccess());
    }

    // ===== 包安装测试 =====

    @Test
    @DisplayName("包名包含非法字符应被拒绝")
    void testInvalidPackageName() {
        CodeExecutionManager.ExecutionResult result = manager.installPackage("requests; rm -rf /");
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("空包名应被拒绝")
    void testEmptyPackageName() {
        CodeExecutionManager.ExecutionResult result = manager.installPackage("");
        assertFalse(result.isSuccess());
    }

    // ===== 工具方法测试 =====

    @Test
    @DisplayName("检测 Python 命令")
    void testPythonCommand() {
        assertNotNull(CodeExecutionManager.getPythonCommand());
        assertNotNull(CodeExecutionManager.getPipCommand());
    }

    @Test
    @DisplayName("工作目录正确设置")
    void testWorkingDirectory() {
        assertEquals(tempDir.toAbsolutePath(), manager.getWorkingDirectory().toAbsolutePath());
    }

    @Test
    @DisplayName("超时配置正确")
    void testTimeoutConfig() {
        assertEquals(30, manager.getTimeoutSeconds());
    }

    // ===== 流式回调测试 =====

    @Test
    @DisplayName("executePython 带回调时实时推送 stdout 逐行")
    void testStreamCallbackReceivesLines() {
        String code = "import time\nfor i in range(3):\n    print(f'line-{i}')\n    time.sleep(0.01)";
        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();
        CodeExecutionManager.OutputLineCallback cb = (stream, line) -> {
            if ("stdout".equals(stream)) stdoutLines.add(line);
            else stderrLines.add(line);
        };

        CodeExecutionManager.ExecutionResult result = manager.executePython(code, cb);

        assertTrue(result.isSuccess(), "执行应成功: " + result);
        assertEquals(3, stdoutLines.size(), "回调应收到 3 行 stdout");
        assertEquals("line-0", stdoutLines.get(0));
        assertEquals("line-1", stdoutLines.get(1));
        assertEquals("line-2", stdoutLines.get(2));
        // 最终 result 的 stdout 仍应完整保留
        assertTrue(result.getStdout().contains("line-0"));
        assertTrue(result.getStdout().contains("line-2"));
    }

    @Test
    @DisplayName("executePython 带回调时 stderr 流也回调")
    void testStreamCallbackReceivesStderr() {
        // 主动写一行 stderr
        String code = "import sys\nsys.stderr.write('warn-here\\n')";
        List<String> stderrLines = new ArrayList<>();
        CodeExecutionManager.OutputLineCallback cb = (stream, line) -> {
            if ("stderr".equals(stream)) stderrLines.add(line);
        };

        CodeExecutionManager.ExecutionResult result = manager.executePython(code, cb);

        // Python 进程本身退出码 0；我们只验证 stderr 回调
        assertTrue(stderrLines.stream().anyMatch(l -> l.contains("warn-here")),
                "stderr 回调应包含 warn-here: " + stderrLines);
        assertTrue(result.getStderr().contains("warn-here"));
    }

    @Test
    @DisplayName("callback 为 null 时退化为非流式（兼容）")
    void testNullCallbackDegradesGracefully() {
        CodeExecutionManager.ExecutionResult result = manager.executePython("print('ok')", null);
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("ok"));
    }
}
