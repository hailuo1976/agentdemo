package com.demo.agentscope.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 中间结果管理器。
 * <p>
 * 管理计算中间结果的缓存，支持跨会话复用。
 * 结果持久化到文件系统，带 TTL 过期机制。
 * </p>
 */
public class IntermediateResultManager {

    private static final Logger log = LoggerFactory.getLogger(IntermediateResultManager.class);

    /** 缓存目录 */
    private final Path cacheDir;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /** 默认 TTL */
    private final Duration defaultTtl;

    /**
     * 构造中间结果管理器。
     *
     * @param cacheDir  缓存目录
     * @param defaultTtl 默认过期时间
     */
    public IntermediateResultManager(Path cacheDir, Duration defaultTtl) {
        this.cacheDir = cacheDir;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.defaultTtl = defaultTtl;

        // 确保目录存在
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            log.error("创建缓存目录失败: {}", cacheDir, e);
        }
    }

    /**
     * 保存中间结果。
     *
     * @param key   结果键
     * @param value 结果值
     */
    public void save(String key, Object value) {
        save(key, value, defaultTtl);
    }

    /**
     * 保存中间结果（指定 TTL）。
     *
     * @param key   结果键
     * @param value 结果值
     * @param ttl   过期时间
     */
    public void save(String key, Object value, Duration ttl) {
        CacheEntry entry = new CacheEntry(key, value, Instant.now(), ttl);
        Path file = cacheDir.resolve(sanitizeKey(key) + ".json");

        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(entry);
            Files.writeString(file, json);
            log.debug("保存中间结果: key={}, ttl={}", key, ttl);
        } catch (IOException e) {
            log.error("保存中间结果失败: {}", key, e);
        }
    }

    /**
     * 加载中间结果。
     *
     * @param key 结果键
     * @return 结果值（如果存在且未过期）
     */
    public Optional<Object> load(String key) {
        Path file = cacheDir.resolve(sanitizeKey(key) + ".json");

        try {
            String json = Files.readString(file);
            CacheEntry entry = objectMapper.readValue(json, CacheEntry.class);

            // 检查是否过期
            if (entry.isExpired()) {
                log.debug("中间结果已过期: key={}", key);
                Files.delete(file);
                return Optional.empty();
            }

            log.debug("加载中间结果: key={}", key);
            return Optional.of(entry.getValue());
        } catch (IOException e) {
            // 文件不存在或其他 IO 错误，统一处理
            log.debug("中间结果不存在或加载失败: key={}", key);
            return Optional.empty();
        }
    }

    /**
     * 列出所有可用的结果键。
     *
     * @return 结果键列表
     */
    public List<String> list() {
        try (Stream<Path> paths = Files.list(cacheDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(name -> name.substring(0, name.length() - 5)) // 去掉 .json
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("列出中间结果失败", e);
            return List.of();
        }
    }

    /**
     * 删除指定结果。
     *
     * @param key 结果键
     */
    public void delete(String key) {
        Path file = cacheDir.resolve(sanitizeKey(key) + ".json");
        try {
            Files.deleteIfExists(file);
            log.debug("删除中间结果: key={}", key);
        } catch (IOException e) {
            log.error("删除中间结果失败: {}", key, e);
        }
    }

    /**
     * 清理所有过期结果。
     */
    public void cleanup() {
        try (Stream<Path> paths = Files.list(cacheDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            String json = Files.readString(path);
                            CacheEntry entry = objectMapper.readValue(json, CacheEntry.class);
                            if (entry.isExpired()) {
                                Files.delete(path);
                                log.debug("清理过期中间结果: {}", path.getFileName());
                            }
                        } catch (IOException e) {
                            log.warn("清理中间结果失败: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.error("清理中间结果失败", e);
        }
    }

    /**
     * 清空所有结果。
     */
    public void clear() {
        try (Stream<Path> paths = Files.list(cacheDir)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除中间结果失败: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.error("清空中间结果失败", e);
        }
        log.info("已清空所有中间结果");
    }

    /**
     * 获取结果数量。
     *
     * @return 结果数量
     */
    public int size() {
        try (Stream<Path> paths = Files.list(cacheDir)) {
            return (int) paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .count();
        } catch (IOException e) {
            log.warn("统计缓存数量失败", e);
            return 0;
        }
    }

    /**
     * 清理键名（移除不安全字符）。
     */
    private String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * 缓存条目数据结构。
     */
    public static class CacheEntry {
        private String key;
        private Object value;
        private Instant createdAt;
        private Duration ttl;

        // 默认构造（JSON 反序列化）
        public CacheEntry() {}

        public CacheEntry(String key, Object value, Instant createdAt, Duration ttl) {
            this.key = key;
            this.value = value;
            this.createdAt = createdAt;
            this.ttl = ttl;
        }

        public String getKey() {
            return key;
        }

        public Object getValue() {
            return value;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Duration getTtl() {
            return ttl;
        }

        /**
         * 检查是否过期。
         */
        public boolean isExpired() {
            return Instant.now().isAfter(createdAt.plus(ttl));
        }
    }
}
