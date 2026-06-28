package com.demo.agentscope;

import com.demo.agentscope.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作空间测试。
 */
@DisplayName("工作空间测试")
class WorkspaceTest {

    @TempDir
    Path tempDir;

    private LocalWorkspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new LocalWorkspace(tempDir.toString());
        workspace.initialize();
    }

    @AfterEach
    void tearDown() {
        try {
            workspace.cleanup();
        } catch (Exception ignored) {
        }
    }

    // ==================== LocalWorkspace 测试 ====================

    @Test
    @DisplayName("LocalWorkspace 创建后类型为 local")
    void localWorkspaceType() {
        assertEquals("local", workspace.getType());
    }

    @Test
    @DisplayName("LocalWorkspace 写入和读取文件")
    void writeFileAndReadFile() {
        workspace.writeFile("test.txt", "Hello, World!");
        String content = workspace.readFile("test.txt");
        assertEquals("Hello, World!", content);
    }

    @Test
    @DisplayName("LocalWorkspace 读取不存在的文件抛出异常")
    void readFileNotFound() {
        assertThrows(RuntimeException.class, () -> workspace.readFile("nonexistent.txt"));
    }

    @Test
    @DisplayName("LocalWorkspace 写入文件到子目录")
    void writeFileToSubdirectory() {
        workspace.writeFile("subdir/nested.txt", "nested content");
        String content = workspace.readFile("subdir/nested.txt");
        assertEquals("nested content", content);
    }

    @Test
    @DisplayName("LocalWorkspace listFiles 列出目录文件")
    void listFiles() {
        workspace.writeFile("file1.txt", "a");
        workspace.writeFile("file2.txt", "b");

        List<String> files = workspace.listFiles(".");
        assertFalse(files.isEmpty());
        assertTrue(files.contains("file1.txt"));
        assertTrue(files.contains("file2.txt"));
    }

    @Test
    @DisplayName("LocalWorkspace listFiles 不存在的目录返回空列表")
    void listFilesNonExistentDir() {
        List<String> files = workspace.listFiles("nonexistent_dir");
        assertTrue(files.isEmpty());
    }

    @Test
    @DisplayName("LocalWorkspace editFile 替换文本")
    void editFile() {
        workspace.writeFile("edit.txt", "Hello World");
        workspace.editFile("edit.txt", "Hello", "Goodbye");
        assertEquals("Goodbye World", workspace.readFile("edit.txt"));
    }

    @Test
    @DisplayName("LocalWorkspace editFile 未找到文本抛出异常")
    void editFileNotFound() {
        workspace.writeFile("edit.txt", "Hello World");
        assertThrows(RuntimeException.class,
                () -> workspace.editFile("edit.txt", "NotFound", "replacement"));
    }

    @Test
    @DisplayName("LocalWorkspace 路径遍历攻击防护")
    void pathTraversalProtection() {
        assertThrows(RuntimeException.class,
                () -> workspace.readFile("../../etc/passwd"));
    }

    @Test
    @DisplayName("LocalWorkspace executeCommand 执行简单命令")
    void executeCommand() {
        Workspace.CommandResult result = workspace.executeCommand("echo hello");
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("hello"));
    }

    @Test
    @DisplayName("LocalWorkspace getBaseDir 返回正确的根目录")
    void getBaseDir() {
        assertNotNull(workspace.getBaseDir());
        assertTrue(workspace.getBaseDir().toString().contains(tempDir.getFileName().toString()));
    }

    @Test
    @DisplayName("LocalWorkspace cleanup 正常执行不报错")
    void cleanupDoesNotThrow() {
        assertDoesNotThrow(() -> workspace.cleanup());
    }

    // ==================== WorkspaceManager 测试 ====================

    @Test
    @DisplayName("WorkspaceManager 创建和获取 local 工作空间")
    void workspaceManagerCreateAndGet() {
        WorkspaceManager manager = new WorkspaceManager();
        Workspace ws = manager.createWorkspace("local", "user1", "agent1", "session1");

        assertNotNull(ws);
        assertEquals("local", ws.getType());

        // 再次获取应该返回同一实例
        Workspace ws2 = manager.getWorkspace("user1", "agent1", "session1");
        assertSame(ws, ws2);
    }

    @Test
    @DisplayName("WorkspaceManager 创建已存在的工作空间返回已有实例")
    void workspaceManagerCreateExisting() {
        WorkspaceManager manager = new WorkspaceManager();
        Workspace ws1 = manager.createWorkspace("local", "user1", "agent1", "session1");
        Workspace ws2 = manager.createWorkspace("local", "user1", "agent1", "session1");
        assertSame(ws1, ws2);
    }

    @Test
    @DisplayName("WorkspaceManager getWorkspace 不存在时返回 null")
    void workspaceManagerGetNonExistent() {
        WorkspaceManager manager = new WorkspaceManager();
        assertNull(manager.getWorkspace("no-user", "no-agent", "no-session"));
    }

    @Test
    @DisplayName("WorkspaceManager removeWorkspace 清理工作空间")
    void workspaceManagerRemoveWorkspace() {
        WorkspaceManager manager = new WorkspaceManager();
        manager.createWorkspace("local", "user1", "agent1", "session1");
        assertEquals(1, manager.size());

        manager.removeWorkspace("user1", "agent1", "session1");
        assertEquals(0, manager.size());
        assertNull(manager.getWorkspace("user1", "agent1", "session1"));
    }

    @Test
    @DisplayName("WorkspaceManager getAllWorkspaces 返回所有工作空间")
    void workspaceManagerGetAll() {
        WorkspaceManager manager = new WorkspaceManager();
        manager.createWorkspace("local", "user1", "agent1", "s1");
        manager.createWorkspace("local", "user2", "agent2", "s2");

        List<Workspace> all = manager.getAllWorkspaces();
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("WorkspaceManager 不支持的类型抛出异常")
    void workspaceManagerUnsupportedType() {
        WorkspaceManager manager = new WorkspaceManager();
        assertThrows(IllegalArgumentException.class,
                () -> manager.createWorkspace("invalid_type", "u", "a", "s"));
    }

    // ==================== DockerWorkspace 测试 ====================

    @Test
    @DisplayName("DockerWorkspace initialize 抛出 UnsupportedOperationException")
    void dockerWorkspaceThrowsOnInitialize() {
        DockerWorkspace docker = new DockerWorkspace("ubuntu:22.04");
        assertEquals("docker", docker.getType());
        assertEquals("ubuntu:22.04", docker.getImage());
        assertThrows(UnsupportedOperationException.class, docker::initialize);
    }

    @Test
    @DisplayName("DockerWorkspace readFile 抛出 UnsupportedOperationException")
    void dockerWorkspaceThrowsOnReadFile() {
        DockerWorkspace docker = new DockerWorkspace("ubuntu:22.04");
        assertThrows(UnsupportedOperationException.class, () -> docker.readFile("test.txt"));
    }

    @Test
    @DisplayName("DockerWorkspace writeFile 抛出 UnsupportedOperationException")
    void dockerWorkspaceThrowsOnWriteFile() {
        DockerWorkspace docker = new DockerWorkspace("ubuntu:22.04");
        assertThrows(UnsupportedOperationException.class, () -> docker.writeFile("test.txt", "content"));
    }

    @Test
    @DisplayName("DockerWorkspace cleanup 抛出 UnsupportedOperationException")
    void dockerWorkspaceThrowsOnCleanup() {
        DockerWorkspace docker = new DockerWorkspace("ubuntu:22.04");
        assertThrows(UnsupportedOperationException.class, docker::cleanup);
    }

    // ==================== E2BWorkspace 测试 ====================

    @Test
    @DisplayName("E2BWorkspace initialize 抛出 UnsupportedOperationException")
    void e2bWorkspaceThrowsOnInitialize() {
        E2BWorkspace e2b = new E2BWorkspace(null);
        assertEquals("e2b", e2b.getType());
        assertThrows(UnsupportedOperationException.class, e2b::initialize);
    }

    @Test
    @DisplayName("E2BWorkspace readFile 抛出 UnsupportedOperationException")
    void e2bWorkspaceThrowsOnReadFile() {
        E2BWorkspace e2b = new E2BWorkspace("fake-key");
        assertThrows(UnsupportedOperationException.class, () -> e2b.readFile("test.txt"));
    }

    @Test
    @DisplayName("E2BWorkspace writeFile 抛出 UnsupportedOperationException")
    void e2bWorkspaceThrowsOnWriteFile() {
        E2BWorkspace e2b = new E2BWorkspace("fake-key");
        assertThrows(UnsupportedOperationException.class, () -> e2b.writeFile("test.txt", "content"));
    }

    @Test
    @DisplayName("E2BWorkspace cleanup 抛出 UnsupportedOperationException")
    void e2bWorkspaceThrowsOnCleanup() {
        E2BWorkspace e2b = new E2BWorkspace(null);
        assertThrows(UnsupportedOperationException.class, e2b::cleanup);
    }

    // ==================== CommandResult 测试 ====================

    @Test
    @DisplayName("CommandResult isSuccess 退出码为 0 时返回 true")
    void commandResultSuccess() {
        Workspace.CommandResult result = new Workspace.CommandResult(0, "output", "");
        assertTrue(result.isSuccess());
        assertEquals("output", result.getStdout());
        assertEquals("", result.getStderr());
    }

    @Test
    @DisplayName("CommandResult isSuccess 退出码非 0 时返回 false")
    void commandResultFailure() {
        Workspace.CommandResult result = new Workspace.CommandResult(1, "", "error");
        assertFalse(result.isSuccess());
        assertEquals(1, result.getExitCode());
    }
}
