package com.demo.agentscope.filepermission;

import org.junit.jupiter.api.*;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件权限管理器单元测试。
 * <p>
 * 验证权限验证逻辑的正确性和安全性，包括路径遍历防护、
 * 白名单/黑名单匹配、扩展名限制、默认策略等。
 * </p>
 */
@DisplayName("文件权限管理器测试")
class FilePermissionManagerTest {

    private Path baseDir;
    private FilePermissionManager manager;

    @BeforeEach
    void setUp() {
        baseDir = Path.of("/tmp/agent-workspace");

        FilePermissionConfig config = new FilePermissionConfig.Builder()
                .allowRead("data/**")
                .allowRead("config/*.json")
                .allowWrite("output/**")
                .allowWrite("logs/*.log")
                .denyPath("data/secrets/**")
                .denyPath("**/.env")
                .denyExtension("exe")
                .denyExtension("sh")
                .allowExtension("txt")
                .allowExtension("json")
                .allowExtension("log")
                .allowExtension("csv")
                .allowExtension("md")
                .maxFileSize(1024)  // 1KB
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.DENY_ALL)
                .build();

        manager = new FilePermissionManager(baseDir, config);
    }

    // ===== 白名单路径测试 =====

    @Test
    @DisplayName("允许读取白名单路径 data/report.txt")
    void testAllowReadWhitelistedPath() {
        FilePermissionResult result = manager.checkRead("data/report.txt");
        assertTrue(result.isAllowed(), "应允许读取 data 目录下的文件");
    }

    @Test
    @DisplayName("允许读取 config 目录下的 JSON 文件")
    void testAllowReadConfigJson() {
        FilePermissionResult result = manager.checkRead("config/app.json");
        assertTrue(result.isAllowed(), "应允许读取 config 下的 json 文件");
    }

    @Test
    @DisplayName("允许写入 output 目录下的文件")
    void testAllowWriteOutputPath() {
        FilePermissionResult result = manager.checkWrite("output/result.txt");
        assertTrue(result.isAllowed(), "应允许写入 output 目录");
    }

    @Test
    @DisplayName("允许写入 logs 目录下的 log 文件")
    void testAllowWriteLogPath() {
        FilePermissionResult result = manager.checkWrite("logs/app.log");
        assertTrue(result.isAllowed(), "应允许写入 logs 目录的 log 文件");
    }

    // ===== 默认策略 DENY_ALL 测试 =====

    @Test
    @DisplayName("拒绝读取未授权路径")
    void testDenyReadUnauthorizedPath() {
        FilePermissionResult result = manager.checkRead("other/file.txt");
        assertFalse(result.isAllowed(), "DENY_ALL 策略下应拒绝未授权路径");
        assertTrue(result.getReason().contains("不在授权范围"));
    }

    @Test
    @DisplayName("拒绝写入未授权路径")
    void testDenyWriteUnauthorizedPath() {
        FilePermissionResult result = manager.checkWrite("data/file.txt");
        assertFalse(result.isAllowed(), "data 目录只读，不允许写入");
    }

    // ===== 黑名单路径测试（最高优先级） =====

    @Test
    @DisplayName("拒绝读取黑名单路径 data/secrets/key.txt")
    void testDenyBlacklistedSecrets() {
        FilePermissionResult result = manager.checkRead("data/secrets/key.txt");
        assertFalse(result.isAllowed(), "黑名单路径应被拒绝");
        assertTrue(result.getReason().contains("黑名单"));
    }

    @Test
    @DisplayName("拒绝读取 .env 文件")
    void testDenyEnvFile() {
        FilePermissionResult result = manager.checkRead("data/.env");
        assertFalse(result.isAllowed(), ".env 文件应被拒绝");
        assertTrue(result.getReason().contains("黑名单"));
    }

    // ===== 路径遍历防护测试 =====

    @Test
    @DisplayName("拒绝路径遍历攻击 ../../etc/passwd")
    void testDenyPathTraversal() {
        FilePermissionResult result = manager.checkRead("../../etc/passwd");
        assertFalse(result.isAllowed(), "路径遍历攻击应被拒绝");
        assertTrue(result.getReason().contains(".."), "应提示目录跳转风险");
    }

    @Test
    @DisplayName("拒绝编码绕过的路径遍历")
    void testDenyEncodedPathTraversal() {
        FilePermissionResult result = manager.checkRead("data/../../etc/passwd");
        assertFalse(result.isAllowed(), "混合路径遍历应被拒绝");
    }

    @Test
    @DisplayName("拒绝 null 路径")
    void testDenyNullPath() {
        assertThrows(NullPointerException.class, () -> manager.checkRead(null),
                "null 路径应抛出空指针异常");
    }

    // ===== 扩展名限制测试 =====

    @Test
    @DisplayName("拒绝黑名单扩展名 .exe")
    void testDenyExeExtension() {
        FilePermissionResult result = manager.checkRead("data/program.exe");
        assertFalse(result.isAllowed(), "exe 扩展名应被拒绝");
        assertTrue(result.getReason().contains("扩展名被禁止"));
    }

    @Test
    @DisplayName("拒绝黑名单扩展名 .sh")
    void testDenyShExtension() {
        FilePermissionResult result = manager.checkRead("data/script.sh");
        assertFalse(result.isAllowed(), "sh 扩展名应被拒绝");
    }

    @Test
    @DisplayName("拒绝白名单外的扩展名")
    void testDenyUnwhitelistedExtension() {
        FilePermissionResult result = manager.checkRead("data/archive.zip");
        assertFalse(result.isAllowed(), "zip 扩展名不在白名单中应被拒绝");
        assertTrue(result.getReason().contains("扩展名不在白名单"));
    }

    @Test
    @DisplayName("允许白名单扩展名 .csv")
    void testAllowCsvExtension() {
        FilePermissionResult result = manager.checkRead("data/records.csv");
        assertTrue(result.isAllowed(), "csv 扩展名在白名单中应允许");
    }

    // ===== 文件大小限制测试 =====

    @Test
    @DisplayName("允许文件大小在限制内")
    void testAllowFileSizeWithinLimit() {
        assertTrue(manager.isFileSizeAllowed(512), "512 bytes 在 1024 上限内应允许");
    }

    @Test
    @DisplayName("拒绝超过大小限制的文件")
    void testDenyFileSizeExceeded() {
        assertFalse(manager.isFileSizeAllowed(2048), "2048 bytes 超过 1024 上限应拒绝");
    }

    @Test
    @DisplayName("大小限制为 0 时不限制")
    void testNoSizeLimit() {
        FilePermissionConfig noLimitConfig = new FilePermissionConfig.Builder()
                .allowRead("**")
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.ALLOW_ALL)
                .build();
        FilePermissionManager noLimitManager = new FilePermissionManager(baseDir, noLimitConfig);
        assertTrue(noLimitManager.isFileSizeAllowed(Long.MAX_VALUE), "0 上限表示不限制");
    }

    // ===== 默认策略 ALLOW_ALL 测试 =====

    @Test
    @DisplayName("ALLOW_ALL 策略下允许未在黑名单的任意路径")
    void testAllowAllPolicy() {
        FilePermissionConfig allowConfig = new FilePermissionConfig.Builder()
                .denyPath("**/.env")
                .denyExtension("exe")
                .allowExtension("txt")
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.ALLOW_ALL)
                .build();
        FilePermissionManager allowManager = new FilePermissionManager(baseDir, allowConfig);

        // 允许读取任意 txt
        assertTrue(allowManager.checkRead("anywhere/file.txt").isAllowed());
        // 仍拒绝黑名单
        assertFalse(allowManager.checkRead("anywhere/.env").isAllowed());
        // 仍拒绝黑名单扩展名
        assertFalse(allowManager.checkRead("anywhere/program.exe").isAllowed());
        // 拒绝白名单外的扩展名
        assertFalse(allowManager.checkRead("anywhere/file.zip").isAllowed());
    }

    // ===== 日志记录测试 =====

    @Test
    @DisplayName("允许的访问应记录日志")
    void testLogAllowedAccess() {
        manager.checkRead("data/report.txt");
        var logs = manager.getLogger().findByOperation(FileOperation.READ);
        assertFalse(logs.isEmpty(), "应有读取日志记录");
        assertTrue(logs.get(logs.size() - 1).isAllowed(), "最后一条日志应为允许");
    }

    @Test
    @DisplayName("拒绝的访问应记录日志")
    void testLogDeniedAccess() {
        manager.checkRead("../../etc/passwd");
        var deniedLogs = manager.getLogger().findDenied();
        assertFalse(deniedLogs.isEmpty(), "应有拒绝日志记录");
    }

    @Test
    @DisplayName("日志应按路径前缀查询")
    void testLogFindByPathPrefix() {
        manager.checkRead("data/report.txt");
        manager.checkRead("data/secrets/key.txt");
        var dataLogs = manager.getLogger().findByPathPrefix("data/");
        assertFalse(dataLogs.isEmpty(), "应能按路径前缀查询日志");
    }

    // ===== 操作类型区分测试 =====

    @Test
    @DisplayName("同一目录读允许但写拒绝")
    void testReadAllowedWriteDenied() {
        FilePermissionResult readResult = manager.checkRead("data/report.txt");
        FilePermissionResult writeResult = manager.checkWrite("data/report.txt");
        assertTrue(readResult.isAllowed(), "data 目录应允许读取");
        assertFalse(writeResult.isAllowed(), "data 目录不应允许写入");
    }

    @Test
    @DisplayName("列目录权限等同于读取权限")
    void testListPermission() {
        FilePermissionResult result = manager.checkList("data/subdir");
        assertTrue(result.isAllowed(), "列目录应与读取权限一致");
    }

    @Test
    @DisplayName("删除权限等同于写入权限")
    void testDeletePermission() {
        FilePermissionResult result = manager.checkDelete("data/report.txt");
        assertFalse(result.isAllowed(), "删除应与写入权限一致，data 不允许写入");
    }
}
