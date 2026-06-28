package com.demo.agentscope.filepermission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件权限配置加载器测试。
 * <p>
 * 验证从 JSON 配置文件和字符串加载权限配置的正确性。
 * </p>
 */
@DisplayName("文件权限配置加载器测试")
class FilePermissionConfigLoaderTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("从 JSON 字符串加载完整配置")
    void testFromJson() throws Exception {
        String json = """
                {
                  "defaultPolicy": "DENY_ALL",
                  "allowedReadPaths": ["data/**", "config/*.json"],
                  "allowedWritePaths": ["output/**"],
                  "deniedPaths": ["**/.env", "secrets/**"],
                  "allowedExtensions": ["txt", "json"],
                  "deniedExtensions": ["exe", "sh"],
                  "maxFileSizeBytes": 5242880
                }
                """;

        FilePermissionConfig config = FilePermissionConfigLoader.fromJson(json);

        assertEquals(FilePermissionConfig.DefaultPolicy.DENY_ALL, config.getDefaultPolicy());
        assertEquals(2, config.getAllowedReadPaths().size());
        assertEquals(1, config.getAllowedWritePaths().size());
        assertEquals(2, config.getDeniedPaths().size());
        assertEquals(2, config.getAllowedExtensions().size());
        assertEquals(2, config.getDeniedExtensions().size());
        assertEquals(5242880, config.getMaxFileSizeBytes());
    }

    @Test
    @DisplayName("从 JSON 文件加载配置")
    void testFromFile() throws Exception {
        Path tempFile = Files.createTempFile("perm-config", ".json");
        tempFile.toFile().deleteOnExit();

        String json = """
                {
                  "defaultPolicy": "ALLOW_ALL",
                  "deniedPaths": ["**/.env"],
                  "deniedExtensions": ["exe"]
                }
                """;
        Files.writeString(tempFile, json);

        FilePermissionConfig config = FilePermissionConfigLoader.fromFile(tempFile);

        assertEquals(FilePermissionConfig.DefaultPolicy.ALLOW_ALL, config.getDefaultPolicy());
        assertEquals(1, config.getDeniedPaths().size());
        assertEquals(1, config.getDeniedExtensions().size());
    }

    @Test
    @DisplayName("加载默认宽松配置")
    void testPermissiveConfig() {
        FilePermissionConfig config = FilePermissionConfigLoader.permissive();
        assertEquals(FilePermissionConfig.DefaultPolicy.ALLOW_ALL, config.getDefaultPolicy());
        assertTrue(config.getAllowedReadPaths().stream().anyMatch(p -> p.getPattern().equals("**")));
    }

    @Test
    @DisplayName("加载严格安全配置")
    void testStrictConfig() {
        FilePermissionConfig config = FilePermissionConfigLoader.strict();
        assertEquals(FilePermissionConfig.DefaultPolicy.DENY_ALL, config.getDefaultPolicy());
        assertFalse(config.getDeniedPaths().isEmpty());
        assertTrue(config.getDeniedExtensions().contains("exe"));
    }

    @Test
    @DisplayName("序列化配置为 JSON 后可还原")
    void testSerializeAndDeserialize() throws Exception {
        FilePermissionConfig original = new FilePermissionConfig.Builder()
                .allowRead("data/**")
                .allowWrite("output/**")
                .denyPath("**/.env")
                .denyExtension("exe")
                .allowExtension("txt")
                .maxFileSize(1024)
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.DENY_ALL)
                .build();

        String json = FilePermissionConfigLoader.toJson(original);
        FilePermissionConfig restored = FilePermissionConfigLoader.fromJson(json);

        assertEquals(original.getDefaultPolicy(), restored.getDefaultPolicy());
        assertEquals(1, restored.getAllowedReadPaths().size());
        assertEquals(1, restored.getAllowedWritePaths().size());
        assertEquals(1, restored.getDeniedPaths().size());
        assertTrue(restored.getDeniedExtensions().contains("exe"));
        assertTrue(restored.getAllowedExtensions().contains("txt"));
        assertEquals(1024, restored.getMaxFileSizeBytes());
    }

    @Test
    @DisplayName("加载最小配置（仅默认策略）")
    void testMinimalConfig() throws Exception {
        String json = """
                { "defaultPolicy": "ALLOW_ALL" }
                """;
        FilePermissionConfig config = FilePermissionConfigLoader.fromJson(json);
        assertEquals(FilePermissionConfig.DefaultPolicy.ALLOW_ALL, config.getDefaultPolicy());
        assertTrue(config.getAllowedReadPaths().isEmpty());
        assertTrue(config.getDeniedPaths().isEmpty());
    }

    @Test
    @DisplayName("null 配置输入应抛出异常")
    void testNullInput() {
        assertThrows(NullPointerException.class, () -> FilePermissionConfigLoader.fromJson(null));
        assertThrows(NullPointerException.class, () -> FilePermissionConfigLoader.fromFile((Path) null));
    }
}
