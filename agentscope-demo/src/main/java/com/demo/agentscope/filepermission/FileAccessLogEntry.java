package com.demo.agentscope.filepermission;

import java.time.Instant;
import java.util.Objects;

/**
 * 文件访问日志条目。
 * <p>
 * 记录一次文件访问的完整信息，包括操作者、操作类型、路径、结果、时间戳。
 * </p>
 */
public class FileAccessLogEntry {

    /** 自增序列号 */
    private final long sequence;

    /** 操作者标识（agentId / userId） */
    private final String operator;

    /** 文件操作类型 */
    private final FileOperation operation;

    /** 访问的文件路径 */
    private final String path;

    /** 是否允许访问 */
    private final boolean allowed;

    /** 结果说明（拒绝原因或成功备注） */
    private final String reason;

    /** 访问时间戳 */
    private final Instant timestamp;

    public FileAccessLogEntry(long sequence, String operator, FileOperation operation,
                               String path, boolean allowed, String reason, Instant timestamp) {
        this.sequence = sequence;
        this.operator = Objects.requireNonNull(operator, "操作者不能为null");
        this.operation = Objects.requireNonNull(operation, "操作类型不能为null");
        this.path = Objects.requireNonNull(path, "路径不能为null");
        this.allowed = allowed;
        this.reason = reason != null ? reason : "";
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }

    public long getSequence() {
        return sequence;
    }

    public String getOperator() {
        return operator;
    }

    public FileOperation getOperation() {
        return operation;
    }

    public String getPath() {
        return path;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s | %s | %s | %s | %s | %s",
                sequence,
                timestamp,
                operator,
                operation.getDescription(),
                path,
                allowed ? "允许" : "拒绝",
                reason);
    }
}
