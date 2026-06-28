package com.demo.agentscope.filepermission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 文件访问日志记录器。
 * <p>
 * 记录所有文件访问行为，包括允许和拒绝的操作。
 * 支持按操作类型、路径、允许状态查询历史日志。
 * 使用线程安全的 CopyOnWriteArrayList 存储日志条目。
 * </p>
 */
public class FileAccessLogger {

    private static final Logger log = LoggerFactory.getLogger(FileAccessLogger.class);

    /** 最大日志条目数（防止内存溢出） */
    private static final int MAX_ENTRIES = 10000;

    /** 日志条目列表（线程安全） */
    private final List<FileAccessLogEntry> entries;

    /** 自增序列号 */
    private final AtomicLong sequenceCounter;

    public FileAccessLogger() {
        this.entries = new CopyOnWriteArrayList<>();
        this.sequenceCounter = new AtomicLong(0);
    }

    /**
     * 记录一次文件访问。
     *
     * @param operator  操作者标识
     * @param operation 文件操作类型
     * @param path      访问路径
     * @param allowed   是否允许
     * @param reason    结果说明
     */
    public void log(String operator, FileOperation operation, String path,
                    boolean allowed, String reason) {
        long seq = sequenceCounter.incrementAndGet();
        FileAccessLogEntry entry = new FileAccessLogEntry(
                seq, operator, operation, path, allowed, reason, Instant.now()
        );

        entries.add(entry);

        // 超出上限时移除最旧的条目
        if (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }

        // 同时输出到 SLF4J 日志
        if (allowed) {
            log.info("文件访问 [允许] {} | {} | {} | {}", operator, operation, path, reason);
        } else {
            log.warn("文件访问 [拒绝] {} | {} | {} | {}", operator, operation, path, reason);
        }
    }

    /**
     * 记录允许的文件访问。
     */
    public void logAllowed(String operator, FileOperation operation, String path) {
        log(operator, operation, path, true, "权限验证通过");
    }

    /**
     * 记录拒绝的文件访问。
     */
    public void logDenied(String operator, FileOperation operation, String path, String reason) {
        log(operator, operation, path, false, reason);
    }

    /**
     * 获取所有日志条目。
     *
     * @return 不可变的日志条目列表
     */
    public List<FileAccessLogEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * 按操作类型查询日志。
     *
     * @param operation 文件操作类型
     * @return 匹配的日志条目列表
     */
    public List<FileAccessLogEntry> findByOperation(FileOperation operation) {
        return entries.stream()
                .filter(e -> e.getOperation() == operation)
                .collect(Collectors.toList());
    }

    /**
     * 按路径前缀查询日志。
     *
     * @param pathPrefix 路径前缀
     * @return 匹配的日志条目列表
     */
    public List<FileAccessLogEntry> findByPathPrefix(String pathPrefix) {
        return entries.stream()
                .filter(e -> e.getPath().startsWith(pathPrefix))
                .collect(Collectors.toList());
    }

    /**
     * 查询被拒绝的访问日志。
     *
     * @return 被拒绝的日志条目列表
     */
    public List<FileAccessLogEntry> findDenied() {
        return entries.stream()
                .filter(e -> !e.isAllowed())
                .collect(Collectors.toList());
    }

    /**
     * 获取最近的 N 条日志。
     *
     * @param count 条目数量
     * @return 最近的日志条目列表
     */
    public List<FileAccessLogEntry> getRecent(int count) {
        int size = entries.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(entries.subList(start, size));
    }

    /**
     * 获取日志总条数。
     *
     * @return 日志条数
     */
    public int size() {
        return entries.size();
    }

    /**
     * 清空所有日志。
     */
    public void clear() {
        entries.clear();
        sequenceCounter.set(0);
        log.info("文件访问日志已清空");
    }
}
