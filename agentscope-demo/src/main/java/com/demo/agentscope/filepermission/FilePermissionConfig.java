package com.demo.agentscope.filepermission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件访问权限配置。
 * <p>
 * 定义代理的文件访问权限范围，包括允许/禁止的路径模式、文件扩展名限制、
 * 文件大小限制等。支持通过编程 API 和 JSON 配置文件两种方式设置。
 * </p>
 *
 * <pre>
 * 配置优先级（从高到低）：
 * 1. 黑名单路径（deniedPaths）— 始终拒绝，不可被白名单覆盖
 * 2. 黑名单扩展名（deniedExtensions）— 始终拒绝
 * 3. 白名单路径（allowedReadPaths / allowedWritePaths）
 * 4. 白名单扩展名（allowedExtensions）— 非空时仅允许列出的扩展名
 * 5. 默认策略（defaultPolicy）
 * </pre>
 */
public class FilePermissionConfig {

    /** 默认策略 */
    public enum DefaultPolicy {
        /** 默认允许（除非在黑名单中） */
        ALLOW_ALL,
        /** 默认拒绝（除非在白名单中） */
        DENY_ALL
    }

    /** 允许读取的路径模式列表（相对路径） */
    private final List<PathPattern> allowedReadPaths;

    /** 允许写入的路径模式列表（相对路径） */
    private final List<PathPattern> allowedWritePaths;

    /** 允许读取的绝对路径模式列表 */
    private final List<PathPattern> allowedAbsoluteReadPaths;

    /** 允许写入的绝对路径模式列表 */
    private final List<PathPattern> allowedAbsoluteWritePaths;

    /** 禁止访问的路径模式列表（黑名单，优先级最高） */
    private final List<PathPattern> deniedPaths;

    /** 允许的文件扩展名（为空表示不限制） */
    private final Set<String> allowedExtensions;

    /** 禁止的文件扩展名 */
    private final Set<String> deniedExtensions;

    /** 最大文件大小（字节），0 表示不限制 */
    private final long maxFileSizeBytes;

    /** 默认策略 */
    private final DefaultPolicy defaultPolicy;

    private FilePermissionConfig(Builder builder) {
        this.allowedReadPaths = new ArrayList<>(builder.allowedReadPaths);
        this.allowedWritePaths = new ArrayList<>(builder.allowedWritePaths);
        this.allowedAbsoluteReadPaths = new ArrayList<>(builder.allowedAbsoluteReadPaths);
        this.allowedAbsoluteWritePaths = new ArrayList<>(builder.allowedAbsoluteWritePaths);
        this.deniedPaths = new ArrayList<>(builder.deniedPaths);
        this.allowedExtensions = new HashSet<>(builder.allowedExtensions);
        this.deniedExtensions = new HashSet<>(builder.deniedExtensions);
        this.maxFileSizeBytes = builder.maxFileSizeBytes;
        this.defaultPolicy = builder.defaultPolicy;
    }

    public List<PathPattern> getAllowedReadPaths() {
        return List.copyOf(allowedReadPaths);
    }

    public List<PathPattern> getAllowedWritePaths() {
        return List.copyOf(allowedWritePaths);
    }

    public List<PathPattern> getAllowedAbsoluteReadPaths() {
        return List.copyOf(allowedAbsoluteReadPaths);
    }

    public List<PathPattern> getAllowedAbsoluteWritePaths() {
        return List.copyOf(allowedAbsoluteWritePaths);
    }

    public List<PathPattern> getDeniedPaths() {
        return List.copyOf(deniedPaths);
    }

    public Set<String> getAllowedExtensions() {
        return Set.copyOf(allowedExtensions);
    }

    public Set<String> getDeniedExtensions() {
        return Set.copyOf(deniedExtensions);
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public DefaultPolicy getDefaultPolicy() {
        return defaultPolicy;
    }

    /**
     * 检查指定扩展名是否被允许。
     *
     * @param extension 文件扩展名（不含点，如 "txt"）
     * @return true 如果允许
     */
    public boolean isExtensionAllowed(String extension) {
        if (extension == null || extension.isBlank()) {
            return true; // 无扩展名的文件不限制
        }
        String ext = extension.toLowerCase().replace(".", "");
        if (deniedExtensions.contains(ext)) {
            return false;
        }
        if (allowedExtensions.isEmpty()) {
            return true; // 白名单为空，不限制
        }
        return allowedExtensions.contains(ext);
    }

    /**
     * 配置构建器。
     */
    public static class Builder {
        private final List<PathPattern> allowedReadPaths = new ArrayList<>();
        private final List<PathPattern> allowedWritePaths = new ArrayList<>();
        private final List<PathPattern> allowedAbsoluteReadPaths = new ArrayList<>();
        private final List<PathPattern> allowedAbsoluteWritePaths = new ArrayList<>();
        private final List<PathPattern> deniedPaths = new ArrayList<>();
        private final Set<String> allowedExtensions = new HashSet<>();
        private final Set<String> deniedExtensions = new HashSet<>();
        private long maxFileSizeBytes = 0;
        private DefaultPolicy defaultPolicy = DefaultPolicy.DENY_ALL;

        public Builder allowRead(String pattern) {
            allowedReadPaths.add(new PathPattern(pattern));
            return this;
        }

        public Builder allowWrite(String pattern) {
            allowedWritePaths.add(new PathPattern(pattern));
            return this;
        }

        public Builder allowReadWrite(String pattern) {
            allowedReadPaths.add(new PathPattern(pattern));
            allowedWritePaths.add(new PathPattern(pattern));
            return this;
        }

        public Builder allowAbsoluteRead(String absolutePath) {
            allowedAbsoluteReadPaths.add(new PathPattern(absolutePath));
            return this;
        }

        public Builder allowAbsoluteWrite(String absolutePath) {
            allowedAbsoluteWritePaths.add(new PathPattern(absolutePath));
            return this;
        }

        public Builder allowAbsoluteReadWrite(String absolutePath) {
            allowedAbsoluteReadPaths.add(new PathPattern(absolutePath));
            allowedAbsoluteWritePaths.add(new PathPattern(absolutePath));
            return this;
        }

        public Builder denyPath(String pattern) {
            deniedPaths.add(new PathPattern(pattern));
            return this;
        }

        public Builder allowExtension(String ext) {
            allowedExtensions.add(ext.toLowerCase().replace(".", ""));
            return this;
        }

        public Builder denyExtension(String ext) {
            deniedExtensions.add(ext.toLowerCase().replace(".", ""));
            return this;
        }

        public Builder maxFileSize(long bytes) {
            this.maxFileSizeBytes = bytes;
            return this;
        }

        public Builder defaultPolicy(DefaultPolicy policy) {
            this.defaultPolicy = policy;
            return this;
        }

        public FilePermissionConfig build() {
            return new FilePermissionConfig(this);
        }
    }

    @Override
    public String toString() {
        return "FilePermissionConfig{" +
                "allowedRead=" + allowedReadPaths +
                ", allowedWrite=" + allowedWritePaths +
                ", denied=" + deniedPaths +
                ", allowedExt=" + allowedExtensions +
                ", deniedExt=" + deniedExtensions +
                ", maxSize=" + maxFileSizeBytes +
                ", policy=" + defaultPolicy +
                '}';
    }
}
