package com.demo.agentscope.filepermission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * 文件权限管理器。
 * <p>
 * 核心权限验证组件，根据 {@link FilePermissionConfig} 中预设的策略，
 * 对代理的文件读写请求进行权限校验。
 * </p>
 *
 * <h3>验证流程</h3>
 * <pre>
 * 1. 路径安全检查（防止路径遍历攻击）
 * 2. 黑名单路径检查（deniedPaths — 最高优先级，直接拒绝）
 * 3. 扩展名黑名单检查（deniedExtensions）
 * 4. 扩展名白名单检查（allowedExtensions — 非空时仅允许列出的扩展名）
 * 5. 白名单路径检查（allowedReadPaths / allowedWritePaths）
 * 6. 文件大小限制检查（maxFileSizeBytes — 仅对写入操作）
 * 7. 默认策略判定（defaultPolicy: ALLOW_ALL / DENY_ALL）
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * FilePermissionConfig config = new FilePermissionConfig.Builder()
 *     .allowRead("data/**")
 *     .allowWrite("output/**")
 *     .denyPath("data/secrets/**")
 *     .denyExtension("exe")
 *     .maxFileSize(10 * 1024 * 1024)  // 10MB
 *     .defaultPolicy(FilePermissionConfig.DefaultPolicy.DENY_ALL)
 *     .build();
 *
 * FilePermissionManager manager = new FilePermissionManager(baseDir, config);
 * FilePermissionResult result = manager.checkRead("data/report.txt");
 * if (result.isAllowed()) {
 *     // 执行文件读取
 * }
 * }</pre>
 */
public class FilePermissionManager {

    private static final Logger log = LoggerFactory.getLogger(FilePermissionManager.class);

    /** 工作空间根目录 */
    private final Path baseDir;

    /** 权限配置 */
    private final FilePermissionConfig config;

    /** 文件访问日志记录器 */
    private final FileAccessLogger logger;

    public FilePermissionManager(Path baseDir, FilePermissionConfig config) {
        this(baseDir, config, new FileAccessLogger());
    }

    public FilePermissionManager(Path baseDir, FilePermissionConfig config, FileAccessLogger logger) {
        this.baseDir = Objects.requireNonNull(baseDir, "根目录不能为null").toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "权限配置不能为null");
        this.logger = Objects.requireNonNull(logger, "日志记录器不能为null");
        log.info("文件权限管理器已初始化: baseDir={}, defaultPolicy={}", this.baseDir, config.getDefaultPolicy());
    }

    /**
     * 校验读取权限。
     *
     * @param relativePath 相对路径
     * @return 权限验证结果
     */
    public FilePermissionResult checkRead(String relativePath) {
        return checkPermission(relativePath, FileOperation.READ, config.getAllowedReadPaths());
    }

    /**
     * 校验写入权限。
     *
     * @param relativePath 相对路径
     * @return 权限验证结果
     */
    public FilePermissionResult checkWrite(String relativePath) {
        return checkPermission(relativePath, FileOperation.WRITE, config.getAllowedWritePaths());
    }

    /**
     * 校验编辑权限（等同于写入权限）。
     *
     * @param relativePath 相对路径
     * @return 权限验证结果
     */
    public FilePermissionResult checkEdit(String relativePath) {
        return checkPermission(relativePath, FileOperation.EDIT, config.getAllowedWritePaths());
    }

    /**
     * 校验删除权限（等同于写入权限）。
     *
     * @param relativePath 相对路径
     * @return 权限验证结果
     */
    public FilePermissionResult checkDelete(String relativePath) {
        return checkPermission(relativePath, FileOperation.DELETE, config.getAllowedWritePaths());
    }

    /**
     * 校验列目录权限。
     * <p>
     * 等同于读取权限，但对目录做容器匹配增强：若路径本身不匹配白名单，
     * 但其子内容（path/**）有读权限，则允许列出该目录。
     * 例如 allowRead("allowed/**") 时，列出 "allowed" 目录本身也应允许。
     * </p>
     *
     * @param relativePath 相对路径
     * @return 权限验证结果
     */
    public FilePermissionResult checkList(String relativePath) {
        FilePermissionResult result = checkPermission(relativePath, FileOperation.LIST, config.getAllowedReadPaths());
        if (result.isAllowed()) {
            return result;
        }
        // 目录容器匹配：allowed/** 应允许列出 allowed 目录
        return checkPermission(relativePath + "/**", FileOperation.LIST, config.getAllowedReadPaths());
    }

    /**
     * 核心权限校验逻辑。
     *
     * @param relativePath  相对路径
     * @param operation     文件操作类型
     * @param allowedPaths  对应操作的白名单路径列表
     * @return 权限验证结果
     */
    private FilePermissionResult checkPermission(String relativePath, FileOperation operation,
                                                List<PathPattern> allowedPaths) {
        // 1. 路径安全检查（防止路径遍历）
        try {
            PathSecurityUtil.resolveSecure(baseDir, relativePath);
        } catch (SecurityException e) {
            logger.logDenied(getOperator(), operation, relativePath, e.getMessage());
            return FilePermissionResult.deny(e.getMessage());
        }

        // 2. 黑名单路径检查（最高优先级）
        for (PathPattern denied : config.getDeniedPaths()) {
            if (denied.matches(relativePath)) {
                String reason = "路径在黑名单中: " + denied.getPattern();
                logger.logDenied(getOperator(), operation, relativePath, reason);
                return FilePermissionResult.deny(reason);
            }
        }

        // 3. 扩展名黑名单检查
        String extension = PathSecurityUtil.getExtension(relativePath);
        if (!extension.isEmpty() && config.getDeniedExtensions().contains(extension)) {
            String reason = "扩展名被禁止: " + extension;
            logger.logDenied(getOperator(), operation, relativePath, reason);
            return FilePermissionResult.deny(reason);
        }

        // 4. 扩展名白名单检查（非空时仅允许列出的扩展名）
        if (!config.getAllowedExtensions().isEmpty()
                && !extension.isEmpty()
                && !config.getAllowedExtensions().contains(extension)) {
            String reason = "扩展名不在白名单中: " + extension;
            logger.logDenied(getOperator(), operation, relativePath, reason);
            return FilePermissionResult.deny(reason);
        }

        // 5. 白名单路径检查
        for (PathPattern allowed : allowedPaths) {
            if (allowed.matches(relativePath)) {
                logger.logAllowed(getOperator(), operation, relativePath);
                return FilePermissionResult.allow();
            }
        }

        // 6. 默认策略判定
        if (config.getDefaultPolicy() == FilePermissionConfig.DefaultPolicy.ALLOW_ALL) {
            logger.logAllowed(getOperator(), operation, relativePath);
            return FilePermissionResult.allow();
        }

        // DENY_ALL：不在白名单中的路径全部拒绝
        String reason = "路径不在授权范围内: " + relativePath;
        logger.logDenied(getOperator(), operation, relativePath, reason);
        return FilePermissionResult.deny(reason);
    }

    /**
     * 获取当前操作者标识。
     * <p>实际应用中可从线程上下文获取 agentId，此处返回固定标识。</p>
     *
     * @return 操作者标识
     */
    private String getOperator() {
        return "agent";
    }

    /**
     * 校验文件大小是否在限制范围内。
     *
     * @param fileSize 文件大小（字节）
     * @return true 如果在限制范围内
     */
    public boolean isFileSizeAllowed(long fileSize) {
        long limit = config.getMaxFileSizeBytes();
        return limit <= 0 || fileSize <= limit;
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public FilePermissionConfig getConfig() {
        return config;
    }

    public FileAccessLogger getLogger() {
        return logger;
    }
}
