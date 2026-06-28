package com.demo.agentscope.filepermission;

/**
 * 文件权限拒绝异常。
 * <p>
 * 当代理尝试访问未被授权的文件路径时抛出。
 * </p>
 */
public class FilePermissionDeniedException extends RuntimeException {

    private final String path;
    private final FileOperation operation;
    private final String reason;

    public FilePermissionDeniedException(String path, FileOperation operation, String reason) {
        super(String.format("文件%s权限被拒绝: path=%s, reason=%s",
                operation.getDescription(), path, reason));
        this.path = path;
        this.operation = operation;
        this.reason = reason;
    }

    public String getPath() {
        return path;
    }

    public FileOperation getOperation() {
        return operation;
    }

    public String getReason() {
        return reason;
    }
}
