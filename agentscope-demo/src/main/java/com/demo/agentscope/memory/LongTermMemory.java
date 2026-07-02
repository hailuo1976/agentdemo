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
 * 长期记忆管理器。
 * <p>
 * 管理跨会话持久化记忆，包括用户偏好、领域知识、成功案例等。
 * 支持基于关键词和语义相似度的检索。
 * </p>
 */
public class LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);

    /** 记忆存储目录 */
    private final Path memoryDir;

    /** 内存中的记忆缓存 */
    private final Map<String, MemoryEntry> memoryCache;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /** 最大记忆条目数 */
    private final int maxEntries;

    /**
     * 构造长期记忆管理器。
     *
     * @param memoryDir  记忆存储目录
     * @param maxEntries 最大记忆条目数
     */
    public LongTermMemory(Path memoryDir, int maxEntries) {
        this.memoryDir = memoryDir;
        this.memoryCache = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.maxEntries = maxEntries;

        // 确保目录存在
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            log.error("创建长期记忆目录失败: {}", memoryDir, e);
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
        // 设置重要性（长期记忆默认较高）
        if (entry.getImportance() < 0.5) {
            entry.setImportance(0.7);
        }

        memoryCache.put(entry.getId(), entry);

        // 持久化到文件
        persistMemory(entry);

        // 检查是否需要清理
        if (memoryCache.size() > maxEntries) {
            cleanupLeastImportant();
        }

        log.debug("存储长期记忆: id={}, importance={}", entry.getId(), entry.getImportance());
    }

    /**
     * 从短期记忆提取重要信息到长期记忆。
     *
     * @param shortTermMemory 短期记忆
     * @param threshold       重要性阈值（超过此值的记忆会被提取）
     */
    public void extractFromShortTerm(ShortTermMemory shortTermMemory, double threshold) {
        List<MemoryEntry> shortTermMemories = shortTermMemory.getAll();

        int extractedCount = 0;
        for (MemoryEntry entry : shortTermMemories) {
            if (entry.getImportance() >= threshold) {
                // 转换为长期记忆
                MemoryEntry longTermEntry = new MemoryEntry(
                        "lt_" + entry.getId(),
                        MemoryEntry.MemoryType.LONG_TERM,
                        entry.getTimestamp(),
                        entry.getContent(),
                        entry.getImportance()
                );
                store(longTermEntry);
                extractedCount++;
            }
        }

        log.info("从短期记忆提取 {} 条重要记忆到长期记忆", extractedCount);
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
                    // 按重要性和访问时间综合排序
                    double scoreA = calculateScore(a);
                    double scoreB = calculateScore(b);
                    return Double.compare(scoreB, scoreA);
                })
                .limit(limit)
                .collect(Collectors.toList());

        // 记录访问
        results.forEach(MemoryEntry::recordAccess);

        log.debug("检索长期记忆: query='{}', 找到 {} 条", query, results.size());
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
     * 清理最不重要的记忆。
     */
    public void cleanup() {
        cleanupLeastImportant();
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

        log.info("已清空所有长期记忆");
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

            log.info("加载长期记忆: {} 条", memoryCache.size());
        } catch (IOException e) {
            log.error("加载长期记忆失败: {}", memoryDir, e);
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
            log.warn("加载长期记忆文件失败: {}", path, e);
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
            log.error("持久化长期记忆失败: {}", entry.getId(), e);
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
            log.warn("删除长期记忆文件失败: {}", file, e);
        }
    }

    /**
     * 清理最不重要的记忆。
     */
    private void cleanupLeastImportant() {
        List<String> idsToRemove = memoryCache.values().stream()
                .sorted(Comparator.comparing(MemoryEntry::getImportance)
                        .thenComparing(MemoryEntry::getLastAccessed))
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
        double recencyScore = 1.0 / (1.0 + Duration.between(entry.getLastAccessed(), Instant.now()).toDays());

        return importanceScore * 0.6 + accessScore * 0.2 + recencyScore * 0.2;
    }
}
