package com.demo.agentscope.execution;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
