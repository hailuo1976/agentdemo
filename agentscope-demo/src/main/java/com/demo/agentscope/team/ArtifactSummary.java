package com.demo.agentscope.team;

import java.time.Instant;

/**
 * list_artifacts 返回的精简视图。不暴露文件内容，仅元数据 + 调用者视角的状态。
 * <p>
 * 设计动机：list 通常返回多条，避免把 tags/extra/recipientStatus 全量序列化到结果。
 */
public class ArtifactSummary {

    private final String artifactId;
    private final String sender;
    private final String filename;
    private final String format;
    private final long sizeBytes;
    private final String checksum;
    private final String description;
    private final java.util.List<String> tags;
    private final String status;       // 调用者视角：SENT/RECEIVED/READ
    private final Instant createdAt;

    public ArtifactSummary(String artifactId, String sender, String filename, String format,
                           long sizeBytes, String checksum, String description,
                           java.util.List<String> tags, String status, Instant createdAt) {
        this.artifactId = artifactId;
        this.sender = sender;
        this.filename = filename;
        this.format = format;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.description = description;
        this.tags = tags != null ? java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tags))
                                 : java.util.Collections.emptyList();
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getArtifactId() { return artifactId; }
    public String getSender() { return sender; }
    public String getFilename() { return filename; }
    public String getFormat() { return format; }
    public long getSizeBytes() { return sizeBytes; }
    public String getChecksum() { return checksum; }
    public String getDescription() { return description; }
    public java.util.List<String> getTags() { return tags; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
