package com.demo.agentscope.filepermission;

/**
 * 文件权限验证结果。
 */
public class FilePermissionResult {

    private final boolean allowed;
    private final String reason;

    private FilePermissionResult(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    public static FilePermissionResult allow() {
        return new FilePermissionResult(true, "权限验证通过");
    }

    public static FilePermissionResult deny(String reason) {
        return new FilePermissionResult(false, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return allowed ? "ALLOWED" : "DENIED: " + reason;
    }
}
