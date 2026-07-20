package com.demo.agentscope.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 短期记忆管理器。
 * <p>
 * 管理会话级记忆，支持持久化到文件系统。
 * 记忆在会话结束后保留，可跨会话访问。
 * </p>
 */
public class ShortTermMemory {

    private static final Logger log = LoggerFactory.getLogger(ShortTermMemory.class);

    /** 记忆存储目录 */
    private final Path memoryDir;

    /** 内存中的记忆缓存 */
    private final Map<String, MemoryEntry> memoryCache;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /** 最大记忆条目数 */
    private final int maxEntries;

    /** 记忆保留时长 */
    private final Duration retention;

    /**
     * 构造短期记忆管理器。
     *
     * @param memoryDir  记忆存储目录
     * @param maxEntries 最大记忆条目数
     * @param retention  记忆保留时长
     */
    public ShortTermMemory(Path memoryDir, int maxEntries, Duration retention) {
        this.memoryDir = memoryDir;
        this.memoryCache = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.maxEntries = maxEntries;
        this.retention = retention;

        // 确保目录存在
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            log.error("创建记忆目录失败: {}", memoryDir, e);
        }

        // 加载已有记忆
        loadMemories();
    }

    /**
     * 存储记忆。
     *
     * @param entry 记忆条目
     */
    public void store(MemoryEntry entry) {
        memoryCache.put(entry.getId(), entry);

        // 持久化到文件
        persistMemory(entry);

        // 检查是否需要清理
        if (memoryCache.size() > maxEntries) {
            cleanupOldest();
        }

        log.debug("存储短期记忆: id={}, type={}", entry.getId(), entry.getType());
    }

    /**
     * 检索记忆。
     *
     * @param query 查询关键词
     * @param limit 最大返回数量
     * @return 匹配的记忆列表
     */
    public List<MemoryEntry> recall(String query, int limit) {
        List<MemoryEntry> results = memoryCache.values().stream()
                .filter(entry -> matchesQuery(entry, query))
                .sorted((a, b) -> {
                    // 按重要性、访问时间、访问次数综合排序
                    double scoreA = calculateScore(a);
                    double scoreB = calculateScore(b);
                    return Double.compare(scoreB, scoreA);
                })
                .limit(limit)
                .collect(Collectors.toList());

        // 记录访问
        results.forEach(MemoryEntry::recordAccess);

        log.debug("检索短期记忆: query='{}', 找到 {} 条", query, results.size());
        return results;
    }

    /**
     * 获取所有记忆。
     *
     * @return 所有记忆条目
     */
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(memoryCache.values());
    }

    /**
     * 按 ID 获取记忆条目（不走访问计数）。
     *
     * @param id 记忆ID
     * @return 记忆条目；若不存在返回 null
     */
    public MemoryEntry getById(String id) {
        return memoryCache.get(id);
    }

    /**
     * 按 ID 删除记忆条目（缓存 + 文件）。
     *
     * @param id 记忆ID
     * @return true 表示删除成功；false 表示 ID 不存在
     */
    public boolean delete(String id) {
        MemoryEntry removed = memoryCache.remove(id);
        if (removed == null) {
            return false;
        }
        deleteMemoryFile(id);
        log.debug("删除短期记忆: id={}", id);
        return true;
    }

    /**
     * 清理过期记忆。
     */
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retention);

        List<String> expiredIds = memoryCache.values().stream()
                .filter(entry -> entry.getLastAccessed().isBefore(cutoff))
                .map(MemoryEntry::getId)
                .collect(Collectors.toList());

        for (String id : expiredIds) {
            memoryCache.remove(id);
            deleteMemoryFile(id);
        }

        if (!expiredIds.isEmpty()) {
            log.info("清理过期短期记忆: {} 条", expiredIds.size());
        }
    }

    /**
     * 清空所有记忆。
     */
    public void clear() {
        memoryCache.clear();

        // 删除所有记忆文件
        try (Stream<Path> paths = Files.list(memoryDir)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除记忆文件失败: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.error("清理记忆目录失败: {}", memoryDir, e);
        }

        log.info("已清空所有短期记忆");
    }

    /**
     * 获取记忆数量。
     *
     * @return 记忆条目数
     */
    public int size() {
        return memoryCache.size();
    }

    // ==================== 私有方法 ====================

    /**
     * 从文件加载记忆。
     */
    private void loadMemories() {
        try (Stream<Path> paths = Files.list(memoryDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .forEach(this::loadMemoryFile);

            log.info("加载短期记忆: {} 条", memoryCache.size());
        } catch (IOException e) {
            log.error("加载记忆失败: {}", memoryDir, e);
        }
    }

    /**
     * 加载单个记忆文件。
     */
    private void loadMemoryFile(Path path) {
        try {
            String json = Files.readString(path);
            MemoryEntry entry = objectMapper.readValue(json, MemoryEntry.class);
            memoryCache.put(entry.getId(), entry);
        } catch (IOException e) {
            log.warn("加载记忆文件失败: {}", path, e);
        }
    }

    /**
     * 持久化记忆到文件。
     */
    private void persistMemory(MemoryEntry entry) {
        Path file = memoryDir.resolve(entry.getId() + ".json");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(entry);
            Files.writeString(file, json);
        } catch (IOException e) {
            log.error("持久化记忆失败: {}", entry.getId(), e);
        }
    }

    /**
     * 删除记忆文件。
     */
    private void deleteMemoryFile(String id) {
        Path file = memoryDir.resolve(id + ".json");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除记忆文件失败: {}", file, e);
        }
    }

    /**
     * 清理最旧的记忆。
     */
    private void cleanupOldest() {
        List<String> idsToRemove = memoryCache.values().stream()
                .sorted(Comparator.comparing(MemoryEntry::getLastAccessed))
                .limit(Math.max(0, memoryCache.size() - maxEntries))
                .map(MemoryEntry::getId)
                .toList();
        idsToRemove.forEach(id -> {
            memoryCache.remove(id);
            deleteMemoryFile(id);
        });
    }

    /**
     * 检查记忆是否匹配查询。
     */
    private boolean matchesQuery(MemoryEntry entry, String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }

        String lowerQuery = query.toLowerCase();

        // 检查摘要
        if (entry.getContent().getSummary().toLowerCase().contains(lowerQuery)) {
            return true;
        }

        // 检查实体
        if (entry.getContent().getEntities().stream()
                .anyMatch(e -> e.toLowerCase().contains(lowerQuery))) {
            return true;
        }

        // 检查关键发现
        if (entry.getContent().getKeyFindings().stream()
                .anyMatch(f -> f.toLowerCase().contains(lowerQuery))) {
            return true;
        }

        // 检查任务上下文
        if (entry.getContent().getTaskContext().toLowerCase().contains(lowerQuery)) {
            return true;
        }

        return false;
    }

    /**
     * 计算记忆评分。
     */
    private double calculateScore(MemoryEntry entry) {
        double importanceScore = entry.getImportance();
        double accessScore = Math.log(entry.getAccessCount() + 1);
        double recencyScore = 1.0 / (1.0 + Duration.between(entry.getLastAccessed(), Instant.now()).toHours());

        return importanceScore * 0.5 + accessScore * 0.2 + recencyScore * 0.3;
    }
}
