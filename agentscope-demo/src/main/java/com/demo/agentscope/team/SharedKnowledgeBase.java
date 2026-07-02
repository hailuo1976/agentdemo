package com.demo.agentscope.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 团队共享知识库。
 * <p>
 * 支持团队成员（Leader和Worker）共享知识、经验和发现。
 * 知识条目包含贡献者、时间戳、内容、标签和置信度。
 * </p>
 */
public class SharedKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(SharedKnowledgeBase.class);

    /** 知识库存储目录 */
    private final Path knowledgeDir;

    /** 团队ID */
    private final String teamId;

    /** 知识条目缓存 */
    private final Map<String, KnowledgeEntry> knowledgeCache;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /** 最大知识条目数 */
    private final int maxEntries;

    /**
     * 构造共享知识库。
     *
     * @param knowledgeDir 知识库存储目录
     * @param teamId       团队ID
     * @param maxEntries   最大知识条目数
     */
    public SharedKnowledgeBase(Path knowledgeDir, String teamId, int maxEntries) {
        this.knowledgeDir = knowledgeDir;
        this.teamId = teamId;
        this.knowledgeCache = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.maxEntries = maxEntries;

        // 确保目录存在
        try {
            Files.createDirectories(knowledgeDir);
        } catch (IOException e) {
            log.error("创建知识库目录失败: {}", knowledgeDir, e);
        }

        // 加载已有知识
        loadKnowledge();
    }

    /**
     * 添加知识条目。
     *
     * @param contributor 贡献者名称
     * @param content     知识内容
     * @param tags        标签列表
     * @param confidence  置信度 (0.0 - 1.0)
     * @return 知识条目ID
     */
    public String add(String contributor, String content, List<String> tags, double confidence) {
        String id = "k_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        KnowledgeEntry entry = new KnowledgeEntry(id, contributor, content, tags, confidence);
        
        knowledgeCache.put(id, entry);
        persistKnowledge(entry);

        // 检查是否需要清理
        if (knowledgeCache.size() > maxEntries) {
            cleanupLowConfidence();
        }

        log.debug("添加知识: id={}, contributor={}, confidence={}", id, contributor, confidence);
        return id;
    }

    /**
     * 搜索知识。
     *
     * @param query 查询关键词
     * @param limit 最大返回数量
     * @return 匹配的知识列表
     */
    public List<KnowledgeEntry> search(String query, int limit) {
        List<KnowledgeEntry> results = knowledgeCache.values().stream()
                .filter(entry -> matchesQuery(entry, query))
                .sorted((a, b) -> {
                    // 按置信度和时间综合排序
                    double scoreA = calculateScore(a);
                    double scoreB = calculateScore(b);
                    return Double.compare(scoreB, scoreA);
                })
                .limit(limit)
                .collect(Collectors.toList());

        log.debug("搜索知识: query='{}', 找到 {} 条", query, results.size());
        return results;
    }

    /**
     * 获取所有知识。
     *
     * @return 所有知识条目
     */
    public List<KnowledgeEntry> getAll() {
        return new ArrayList<>(knowledgeCache.values());
    }

    /**
     * 获取指定贡献者的知识。
     *
     * @param contributor 贡献者名称
     * @return 该贡献者的知识列表
     */
    public List<KnowledgeEntry> getByContributor(String contributor) {
        return knowledgeCache.values().stream()
                .filter(entry -> contributor.equals(entry.getContributor()))
                .sorted(Comparator.comparing(KnowledgeEntry::getTimestamp).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 删除知识条目。
     *
     * @param id 知识条目ID
     */
    public void remove(String id) {
        knowledgeCache.remove(id);
        deleteKnowledgeFile(id);
        log.debug("删除知识: id={}", id);
    }

    /**
     * 清空知识库。
     */
    public void clear() {
        knowledgeCache.clear();

        // 删除所有知识文件
        try (Stream<Path> paths = Files.list(knowledgeDir)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除知识文件失败: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.error("清理知识库失败: {}", knowledgeDir, e);
        }

        log.info("已清空知识库");
    }

    /**
     * 获取知识数量。
     *
     * @return 知识条目数
     */
    public int size() {
        return knowledgeCache.size();
    }

    // ==================== 私有方法 ====================

    /**
     * 从文件加载知识。
     */
    private void loadKnowledge() {
        try {
            Files.list(knowledgeDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .forEach(this::loadKnowledgeFile);

            log.info("加载知识库: {} 条", knowledgeCache.size());
        } catch (IOException e) {
            log.error("加载知识库失败: {}", knowledgeDir, e);
        }
    }

    /**
     * 加载单个知识文件。
     */
    private void loadKnowledgeFile(Path path) {
        try {
            String json = Files.readString(path);
            KnowledgeEntry entry = objectMapper.readValue(json, KnowledgeEntry.class);
            knowledgeCache.put(entry.getId(), entry);
        } catch (IOException e) {
            log.warn("加载知识文件失败: {}", path, e);
        }
    }

    /**
     * 持久化知识到文件。
     */
    private void persistKnowledge(KnowledgeEntry entry) {
        Path file = knowledgeDir.resolve(entry.getId() + ".json");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(entry);
            Files.writeString(file, json);
        } catch (IOException e) {
            log.error("持久化知识失败: {}", entry.getId(), e);
        }
    }

    /**
     * 删除知识文件。
     */
    private void deleteKnowledgeFile(String id) {
        Path file = knowledgeDir.resolve(id + ".json");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除知识文件失败: {}", file, e);
        }
    }

    /**
     * 清理低置信度知识。
     */
    private void cleanupLowConfidence() {
        knowledgeCache.values().stream()
                .sorted(Comparator.comparing(KnowledgeEntry::getConfidence)
                        .thenComparing(KnowledgeEntry::getTimestamp))
                .limit(Math.max(0, knowledgeCache.size() - maxEntries))
                .map(KnowledgeEntry::getId)
                .forEach(id -> {
                    knowledgeCache.remove(id);
                    deleteKnowledgeFile(id);
                });
    }

    /**
     * 检查知识是否匹配查询。
     */
    private boolean matchesQuery(KnowledgeEntry entry, String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }

        String lowerQuery = query.toLowerCase();

        // 检查内容
        if (entry.getContent().toLowerCase().contains(lowerQuery)) {
            return true;
        }

        // 检查标签
        if (entry.getTags().stream()
                .anyMatch(tag -> tag.toLowerCase().contains(lowerQuery))) {
            return true;
        }

        // 检查贡献者
        if (entry.getContributor().toLowerCase().contains(lowerQuery)) {
            return true;
        }

        return false;
    }

    /**
     * 计算知识评分。
     */
    private double calculateScore(KnowledgeEntry entry) {
        double confidenceScore = entry.getConfidence();
        double recencyScore = 1.0 / (1.0 + java.time.Duration.between(entry.getTimestamp(), Instant.now()).toHours());

        return confidenceScore * 0.7 + recencyScore * 0.3;
    }

    // ==================== 知识条目数据结构 ====================

    /**
     * 知识条目。
     */
    public static class KnowledgeEntry {
        /** 知识ID */
        private String id;

        /** 贡献者名称 */
        private String contributor;

        /** 知识内容 */
        private String content;

        /** 标签列表 */
        private List<String> tags;

        /** 置信度 (0.0 - 1.0) */
        private double confidence;

        /** 时间戳 */
        private Instant timestamp;

        /** 默认构造（JSON 反序列化） */
        public KnowledgeEntry() {}

        /**
         * 构造知识条目。
         */
        public KnowledgeEntry(String id, String contributor, String content,
                             List<String> tags, double confidence) {
            this.id = id;
            this.contributor = contributor;
            this.content = content;
            this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
            this.confidence = confidence;
            this.timestamp = Instant.now();
        }

        // Getters
        public String getId() { return id; }
        public String getContributor() { return contributor; }
        public String getContent() { return content; }
        public List<String> getTags() { return tags; }
        public double getConfidence() { return confidence; }
        public Instant getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("Knowledge{id='%s', contributor='%s', confidence=%.2f, content='%s'}",
                    id, contributor, confidence, content.length() > 50 ? content.substring(0, 50) + "..." : content);
        }
    }
}
