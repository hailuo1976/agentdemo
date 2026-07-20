package com.demo.agentscope.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆条目数据结构。
 * <p>
 * 表示一条记忆记录，包含内容、元数据和访问统计信息。
 * </p>
 */
public class MemoryEntry {

    /** 记忆唯一标识 */
    private String id;

    /** 记忆类型 */
    private MemoryType type;

    /** 创建时间戳 */
    private Instant timestamp;

    /** 记忆内容 */
    private MemoryContent content;

    /** 重要性评分 (0.0 - 1.0) */
    private double importance;

    /** 访问次数 */
    private int accessCount;

    /** 最后访问时间 */
    private Instant lastAccessed;

    /** 默认构造（JSON 反序列化） */
    public MemoryEntry() {}

    /**
     * 构造记忆条目。
     *
     * @param id        记忆ID
     * @param type      记忆类型
     * @param timestamp 时间戳
     * @param content   内容
     * @param importance 重要性
     */
    @JsonCreator
    public MemoryEntry(
            @JsonProperty("id") String id,
            @JsonProperty("type") MemoryType type,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("content") MemoryContent content,
            @JsonProperty("importance") double importance) {
        this.id = id;
        this.type = type;
        this.timestamp = timestamp;
        this.content = content;
        this.importance = importance;
        this.accessCount = 0;
        this.lastAccessed = timestamp;
    }

    /**
     * 静态工厂：便捷构造新记忆条目（自动填 timestamp/accessCount/lastAccessed）。
     * <p>
     * 用于 {@link MemoryToolService} 等主动写入场景，避免外部代码直接 new 再手动设置元字段。
     * </p>
     *
     * @param id         记忆ID
     * @param type       记忆类型
     * @param content    内容
     * @param importance 重要性 [0.0, 1.0]
     * @return 新建的记忆条目
     */
    public static MemoryEntry create(String id, MemoryType type,
                                     MemoryContent content, double importance) {
        Instant now = Instant.now();
        MemoryEntry e = new MemoryEntry(id, type, now, content, clampImportance(importance));
        return e;
    }

    private static double clampImportance(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    /**
     * 记录一次访问。
     */
    public void recordAccess() {
        this.accessCount++;
        this.lastAccessed = Instant.now();
    }

    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }

    public void setLastAccessed(Instant lastAccessed) {
        this.lastAccessed = lastAccessed;
    }

    public void setType(MemoryType type) {
        this.type = type;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public void setContent(MemoryContent content) {
        this.content = content;
    }

    public void setId(String id) {
        this.id = id;
    }

    // Getters

    public String getId() {
        return id;
    }

    public MemoryType getType() {
        return type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public MemoryContent getContent() {
        return content;
    }

    public double getImportance() {
        return importance;
    }

    public void setImportance(double importance) {
        this.importance = importance;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public Instant getLastAccessed() {
        return lastAccessed;
    }

    /**
     * 记忆内容结构。
     */
    public static class MemoryContent {
        /** 摘要文本 */
        private final String summary;

        /** 关键实体列表 */
        private final List<String> entities;

        /** 任务上下文 */
        private final String taskContext;

        /** 关键发现 */
        private final List<String> keyFindings;

        /** 扩展元数据 */
        private final Map<String, Object> metadata;

        @JsonCreator
        public MemoryContent(
                @JsonProperty("summary") String summary,
                @JsonProperty("entities") List<String> entities,
                @JsonProperty("taskContext") String taskContext,
                @JsonProperty("keyFindings") List<String> keyFindings,
                @JsonProperty("metadata") Map<String, Object> metadata) {
            this.summary = summary;
            this.entities = entities != null ? entities : new ArrayList<>();
            this.taskContext = taskContext != null ? taskContext : "";
            this.keyFindings = keyFindings != null ? keyFindings : new ArrayList<>();
            this.metadata = metadata;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getEntities() {
            return entities;
        }

        public String getTaskContext() {
            return taskContext;
        }

        public List<String> getKeyFindings() {
            return keyFindings;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }

    /**
     * 记忆类型枚举。
     */
    public enum MemoryType {
        /** 工作记忆（内存） */
        WORKING,

        /** 短期记忆（会话级） */
        SHORT_TERM,

        /** 长期记忆（跨会话） */
        LONG_TERM
    }
}
