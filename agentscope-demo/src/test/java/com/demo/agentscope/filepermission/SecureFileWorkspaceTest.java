package com.demo.agentscope.filepermission;

import com.demo.agentscope.workspace.LocalWorkspace;
import com.demo.agentscope.workspace.Workspace;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全文件工作空间集成测试。
 * <p>
 * 验证 SecureFileWorkspace 装饰器与 LocalWorkspace 的集成，
 * 确保权限验证拦截未授权操作、放行授权操作。
 * </p>
 */
@DisplayName("安全文件工作空间集成测试")
class SecureFileWorkspaceTest {

    private Path tempDir;
    private SecureFileWorkspace secureWorkspace;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("secure-ws-test");
        tempDir.toFile().deleteOnExit();

        FilePermissionConfig config = new FilePermissionConfig.Builder()
                .allowReadWrite("allowed/**")
                .allowRead("readonly/**")
                .denyPath("**/.env")
                .denyExtension("exe")
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.DENY_ALL)
                .build();

        FilePermissionManager manager = new FilePermissionManager(tempDir, config);
        Workspace local = new LocalWorkspace(tempDir.toString());
        local.initialize();
        secureWorkspace = new SecureFileWorkspace(local, manager);
    }

    @AfterEach
    void tearDown() throws IOException {
        secureWorkspace.cleanup();
        // 清理临时目录
        Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
    }

    @Test
    @DisplayName("允许在授权目录写入文件")
    void testWriteAllowedPath() {
        assertDoesNotThrow(() -> secureWorkspace.writeFile("allowed/test.txt", "hello"));
        assertEquals("hello", secureWorkspace.readFile("allowed/test.txt"));
    }

    @Test
    @DisplayName("拒绝在只读目录写入文件")
    void testWriteReadOnlyDirectory() {
        // 先在 readonly 目录创建文件（通过直接操作底层或调整）
        secureWorkspace.writeFile("allowed/setup.txt", "data");
        // readonly 目录只允许读
        assertThrows(FilePermissionDeniedException.class,
                () -> secureWorkspace.writeFile("readonly/file.txt", "data"),
                "只读目录不应允许写入");
    }

    @Test
    @DisplayName("拒绝在未授权目录写入文件")
    void testWriteUnauthorizedPath() {
        assertThrows(FilePermissionDeniedException.class,
                () -> secureWorkspace.writeFile("forbidden/file.txt", "data"),
                "未授权目录不应允许写入");
    }

    @Test
    @DisplayName("拒绝读取 .env 文件")
    void testReadEnvFile() {
        assertThrows(FilePermissionDeniedException.class,
                () -> secureWorkspace.readFile("allowed/.env"),
                ".env 文件应被拒绝读取");
    }

    @Test
    @DisplayName("拒绝写入 .exe 文件")
    void testWriteExeFile() {
        assertThrows(FilePermissionDeniedException.class,
                () -> secureWorkspace.writeFile("allowed/program.exe", "binary"),
                "exe 扩展名应被拒绝");
    }

    @Test
    @DisplayName("拒绝路径遍历攻击")
    void testPathTraversalBlocked() {
        assertThrows(FilePermissionDeniedException.class,
                () -> secureWorkspace.readFile("../../etc/passwd"),
                "路径遍历应被拦截");
    }

    @Test
    @DisplayName("允许列授权目录")
    void testListAllowedDirectory() {
        secureWorkspace.writeFile("allowed/a.txt", "1");
        secureWorkspace.writeFile("allowed/b.txt", "2");
        var files = secureWorkspace.listFiles("allowed");
        assertFalse(files.isEmpty(), "应能列出授权目录文件");
    }

    @Test
    @DisplayName("拒绝列未授权目录")
    void testListUnauthorizedDirectory() {
        assertThrows(FilePermissionDeniedException.class,
                () -> secureWorkspace.listFiles("forbidden"),
                "未授权目录不应允许列出");
    }

    @Test
    @DisplayName("拒绝操作应记录日志")
    void testDeniedOperationLogged() {
        try {
            secureWorkspace.readFile("forbidden/secret.txt");
        } catch (FilePermissionDeniedException ignored) {
            // 预期异常
        }
        var deniedLogs = secureWorkspace.getAccessLogger().findDenied();
        assertFalse(deniedLogs.isEmpty(), "拒绝操作应记录在日志中");
        assertEquals("forbidden/secret.txt", deniedLogs.get(deniedLogs.size() - 1).getPath());
    }

    @Test
    @DisplayName("编辑文件需写入权限")
    void testEditRequiresWritePermission() {
        secureWorkspace.writeFile("allowed/editable.txt", "old content");
        assertDoesNotThrow(() ->
                secureWorkspace.editFile("allowed/editable.txt", "old", "new"));
        assertEquals("new content", secureWorkspace.readFile("allowed/editable.txt"));

        // 只读目录不允许编辑
        assertThrows(FilePermissionDeniedException.class,
                () -> secureWorkspace.editFile("readonly/file.txt", "a", "b"));
    }

    @Test
    @DisplayName("工作空间类型标识应带 secure 前缀")
    void testTypeIdentifier() {
        assertEquals("secure-local", secureWorkspace.getType());
    }

    @Test
    @DisplayName("文件大小超限应被拒绝")
    void testFileSizeExceeded() {
        // 创建一个大小限制为 10 字节的配置
        FilePermissionConfig smallConfig = new FilePermissionConfig.Builder()
                .allowReadWrite("allowed/**")
                .maxFileSize(10)
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.ALLOW_ALL)
                .build();
        FilePermissionManager smallManager = new FilePermissionManager(tempDir, smallConfig);
        Workspace local = new LocalWorkspace(tempDir.toString());
        SecureFileWorkspace smallWs = new SecureFileWorkspace(local, smallManager);

        // 写入超过限制的内容
        String bigContent = "this content is way too long for the limit";
        assertThrows(FilePermissionDeniedException.class,
                () -> smallWs.writeFile("allowed/big.txt", bigContent),
                "超过大小限制的文件应被拒绝");
    }
}
