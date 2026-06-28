package com.demo.agentscope.filepermission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文件权限配置加载器。
 * <p>
 * 支持从 JSON 配置文件加载权限配置，提供开发人员通过配置文件
 * 设置代理文件访问权限范围的接口。
 * </p>
 *
 * <h3>配置文件格式（JSON）</h3>
 * <pre>{@code
 * {
 *   "defaultPolicy": "DENY_ALL",
 *   "allowedReadPaths": ["data/**", "config/*.json"],
 *   "allowedWritePaths": ["output/**"],
 *   "deniedPaths": ["data/secrets/**", "**&#47;.env"],
 *   "allowedExtensions": ["txt", "json", "csv", "md"],
 *   "deniedExtensions": ["exe", "sh", "bat"],
 *   "maxFileSizeBytes": 10485760
 * }
 * }</pre>
 */
public class FilePermissionConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(FilePermissionConfigLoader.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 JSON 字符串加载权限配置。
     *
     * @param json JSON 配置字符串
     * @return 权限配置对象
     * @throws IOException JSON 解析失败
     */
    public static FilePermissionConfig fromJson(String json) throws IOException {
        Objects.requireNonNull(json, "JSON 配置不能为null");
        JsonNode root = objectMapper.readTree(json);
        return fromJsonNode(root);
    }

    /**
     * 从 JSON 配置文件加载权限配置。
     *
     * @param configPath 配置文件路径
     * @return 权限配置对象
     * @throws IOException 文件读取或 JSON 解析失败
     */
    public static FilePermissionConfig fromFile(Path configPath) throws IOException {
        Objects.requireNonNull(configPath, "配置文件路径不能为null");
        log.info("加载文件权限配置: {}", configPath);
        String content = Files.readString(configPath);
        return fromJson(content);
    }

    /**
     * 从 JSON 配置文件加载权限配置。
     *
     * @param configPath 配置文件路径字符串
     * @return 权限配置对象
     * @throws IOException 文件读取或 JSON 解析失败
     */
    public static FilePermissionConfig fromFile(String configPath) throws IOException {
        return fromFile(Path.of(configPath));
    }

    /**
     * 从默认配置创建一个宽松的权限配置（允许所有读写）。
     *
     * @return 允许所有操作的权限配置
     */
    public static FilePermissionConfig permissive() {
        return new FilePermissionConfig.Builder()
                .allowReadWrite("**")
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.ALLOW_ALL)
                .build();
    }

    /**
     * 从默认配置创建一个严格的安全配置（拒绝所有，需显式授权）。
     *
     * @return 拒绝所有操作的权限配置
     */
    public static FilePermissionConfig strict() {
        return new FilePermissionConfig.Builder()
                .denyPath("**/.env")
                .denyPath("**/secrets/**")
                .denyExtension("exe")
                .denyExtension("sh")
                .defaultPolicy(FilePermissionConfig.DefaultPolicy.DENY_ALL)
                .build();
    }

    private static FilePermissionConfig fromJsonNode(JsonNode root) {
        FilePermissionConfig.Builder builder = new FilePermissionConfig.Builder();

        // 默认策略
        String policy = root.path("defaultPolicy").asText("DENY_ALL");
        builder.defaultPolicy(FilePermissionConfig.DefaultPolicy.valueOf(policy));

        // 允许读取路径
        for (String p : readStringArray(root, "allowedReadPaths")) {
            builder.allowRead(p);
        }

        // 允许写入路径
        for (String p : readStringArray(root, "allowedWritePaths")) {
            builder.allowWrite(p);
        }

        // 禁止路径
        for (String p : readStringArray(root, "deniedPaths")) {
            builder.denyPath(p);
        }

        // 允许扩展名
        for (String e : readStringArray(root, "allowedExtensions")) {
            builder.allowExtension(e);
        }

        // 禁止扩展名
        for (String e : readStringArray(root, "deniedExtensions")) {
            builder.denyExtension(e);
        }

        // 最大文件大小
        long maxSize = root.path("maxFileSizeBytes").asLong(0);
        if (maxSize > 0) {
            builder.maxFileSize(maxSize);
        }

        log.info("文件权限配置加载完成: policy={}", policy);
        return builder.build();
    }

    private static List<String> readStringArray(JsonNode parent, String fieldName) {
        List<String> result = new ArrayList<>();
        JsonNode arrayNode = parent.path(fieldName);
        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                result.add(item.asText());
            }
        }
        return result;
    }

    /**
     * 将权限配置序列化为 JSON 字符串。
     *
     * @param config 权限配置
     * @return JSON 字符串
     */
    public static String toJson(FilePermissionConfig config) {
        try {
            var root = objectMapper.createObjectNode();
            root.put("defaultPolicy", config.getDefaultPolicy().name());

            var readArr = root.putArray("allowedReadPaths");
            config.getAllowedReadPaths().forEach(p -> readArr.add(p.getPattern()));

            var writeArr = root.putArray("allowedWritePaths");
            config.getAllowedWritePaths().forEach(p -> writeArr.add(p.getPattern()));

            var deniedArr = root.putArray("deniedPaths");
            config.getDeniedPaths().forEach(p -> deniedArr.add(p.getPattern()));

            var allowedExtArr = root.putArray("allowedExtensions");
            config.getAllowedExtensions().forEach(allowedExtArr::add);

            var deniedExtArr = root.putArray("deniedExtensions");
            config.getDeniedExtensions().forEach(deniedExtArr::add);

            root.put("maxFileSizeBytes", config.getMaxFileSizeBytes());

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("权限配置序列化失败", e);
        }
    }
}
