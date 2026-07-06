package com.demo.agentscope.team;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Artifact 数据模型 —— 团队成员间通过文件形式传递的重要产出。
 * <p>
 * 每条 Artifact 包含：标识（artifactId/teamId/sender/recipients）、文件元数据（filename/path/format/mime/size/checksum）、
 * 描述与标签、per-recipient 状态跟踪（SENT/RECEIVED/READ）、时间戳。Jackson 可序列化，持久化为 JSON manifest。
 * </p>
 * <p>
 * 状态机：创建时所有 recipient 置 SENT；recipient 调 get_artifact → RECEIVED；recipient 调 mark_artifact_read → READ。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Artifact {

    /** 接收者状态枚举字符串常量 */
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_READ = "READ";

    /** artifact 唯一 ID，形如 art_{ts}_{uuid8} */
    private String artifactId;

    /** 所属团队 ID */
    private String teamId;

    /** 发送者 agentName */
    private String sender;

    /** 接收者 agentName 列表（"leader" 表示 leader） */
    private List<String> recipients;

    /** 原始文件名（含扩展） */
    private String filename;

    /** 实际存储相对路径（相对 workspace 根） */
    private String storedPath;

    /** 扩展名小写 */
    private String format;

    /** MIME 类型 */
    private String mimeType;

    /** 文件字节数 */
    private long sizeBytes;

    /** sha256 校验和，形如 "sha256:{hex}" */
    private String checksum;

    /** 发送者的描述 */
    private String description;

    /** 自由标签 */
    private List<String> tags;

    /** 自由元数据扩展 */
    private Map<String, Object> extra;

    /** per-recipient 状态映射：agentName -> SENT/RECEIVED/READ */
    private Map<String, String> recipientStatus;

    /** 创建时间 */
    private Instant createdAt;

    /** 团队解散时统一打的时间戳；null 表示未归档 */
    private Instant dissolvedAt;

    /** 默认构造（Jackson 反序列化用） */
    public Artifact() {
    }

    /**
     * 全参构造。
     *
     * @throws IllegalArgumentException 若 recipients 为空、checksum 格式非法、sizeBytes 为负
     */
    public Artifact(String artifactId, String teamId, String sender, List<String> recipients,
                    String filename, String storedPath, String format, String mimeType,
                    long sizeBytes, String checksum, String description, List<String> tags,
                    Map<String, Object> extra, Map<String, String> recipientStatus,
                    Instant createdAt, Instant dissolvedAt) {
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId 不能为 null");
        this.teamId = Objects.requireNonNull(teamId, "teamId 不能为 null");
        this.sender = Objects.requireNonNull(sender, "sender 不能为 null");
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("recipients 不能为空");
        }
        this.recipients = new ArrayList<>(recipients);
        this.filename = Objects.requireNonNull(filename, "filename 不能为 null");
        this.storedPath = Objects.requireNonNull(storedPath, "storedPath 不能为 null");
        this.format = Objects.requireNonNull(format, "format 不能为 null");
        this.mimeType = mimeType;
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负：" + sizeBytes);
        }
        this.sizeBytes = sizeBytes;
        if (checksum == null || !checksum.startsWith("sha256:")) {
            throw new IllegalArgumentException("checksum 必须形如 sha256:{hex}：" + checksum);
        }
        this.checksum = checksum;
        this.description = description;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.extra = extra != null ? new HashMap<>(extra) : new HashMap<>();
        this.recipientStatus = recipientStatus != null ? new HashMap<>(recipientStatus) : new HashMap<>();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.dissolvedAt = dissolvedAt;
    }

    // ---- getters / setters ----

    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }

    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public List<String> getRecipients() { return Collections.unmodifiableList(recipients); }
    public void setRecipients(List<String> recipients) {
        this.recipients = recipients != null ? new ArrayList<>(recipients) : new ArrayList<>();
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) {
        this.extra = extra != null ? new HashMap<>(extra) : new HashMap<>();
    }

    /**
     * 返回 per-recipient 状态映射的可变副本（内部更新用）。
     */
    @JsonProperty("recipientStatus")
    public Map<String, String> getRecipientStatus() {
        return recipientStatus;
    }

    public void setRecipientStatus(Map<String, String> recipientStatus) {
        this.recipientStatus = recipientStatus != null ? new HashMap<>(recipientStatus) : new HashMap<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getDissolvedAt() { return dissolvedAt; }
    public void setDissolvedAt(Instant dissolvedAt) { this.dissolvedAt = dissolvedAt; }

    /**
     * 判断 requester 是否对该 artifact 有访问权限（sender 或 recipient）。
     */
    public boolean isAccessibleBy(String requester) {
        if (requester == null) return false;
        if (requester.equals(sender)) return true;
        return recipients.stream().anyMatch(r -> r.equals(requester));
    }

    @Override
    public String toString() {
        return String.format("Artifact{id='%s', filename='%s', sender='%s', size=%d, format=%s}",
                artifactId, filename, sender, sizeBytes, format);
    }
}
