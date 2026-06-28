package com.demo.agentscope.filepermission;

import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路径安全工具单元测试。
 * <p>
 * 重点验证路径遍历防护机制的安全性。
 * </p>
 */
@DisplayName("路径安全工具测试")
class PathSecurityUtilTest {

    private Path baseDir;

    @BeforeEach
    void setUp() {
        baseDir = Paths.get("/tmp/workspace");
    }

    @Test
    @DisplayName("正常相对路径解析成功")
    void testNormalPath() {
        Path resolved = PathSecurityUtil.resolveSecure(baseDir, "data/file.txt");
        assertTrue(resolved.startsWith(baseDir));
        assertTrue(resolved.toString().endsWith("data/file.txt"));
    }

    @Test
    @DisplayName("拒绝 ../ 路径遍历攻击")
    void testPathTraversalAttack() {
        assertThrows(SecurityException.class,
                () -> PathSecurityUtil.resolveSecure(baseDir, "../../etc/passwd"),
                "应拒绝包含 .. 的路径");
    }

    @Test
    @DisplayName("拒绝混合路径遍历攻击")
    void testMixedPathTraversal() {
        assertThrows(SecurityException.class,
                () -> PathSecurityUtil.resolveSecure(baseDir, "data/../../etc/passwd"),
                "应拒绝混合 .. 的路径");
    }

    @Test
    @DisplayName("拒绝深层路径遍历攻击")
    void testDeepPathTraversal() {
        assertThrows(SecurityException.class,
                () -> PathSecurityUtil.resolveSecure(baseDir, "a/b/c/../../../d"),
                "应拒绝深层 .. 的路径");
    }

    @Test
    @DisplayName("Windows 风格反斜杠路径遍历应被拒绝")
    void testWindowsPathTraversal() {
        assertThrows(SecurityException.class,
                () -> PathSecurityUtil.resolveSecure(baseDir, "..\\..\\windows\\system32"),
                "应拒绝反斜杠路径遍历");
    }

    @Test
    @DisplayName("提取文件扩展名")
    void testGetExtension() {
        assertEquals("txt", PathSecurityUtil.getExtension("file.txt"));
        assertEquals("json", PathSecurityUtil.getExtension("data/config.JSON"));
        assertEquals("csv", PathSecurityUtil.getExtension("records.csv"));
        assertEquals("", PathSecurityUtil.getExtension("noextension"));
        assertEquals("", PathSecurityUtil.getExtension(""));
        assertEquals("", PathSecurityUtil.getExtension(null));
    }

    @Test
    @DisplayName("相对路径计算")
    void testRelativize() {
        Path full = baseDir.resolve("data/file.txt").normalize();
        String relative = PathSecurityUtil.relativize(baseDir, full);
        assertTrue(relative.contains("data"));
    }

    @Test
    @DisplayName("null 参数应抛出异常")
    void testNullParameters() {
        assertThrows(NullPointerException.class,
                () -> PathSecurityUtil.resolveSecure(null, "file.txt"));
        assertThrows(NullPointerException.class,
                () -> PathSecurityUtil.resolveSecure(baseDir, null));
    }
}
