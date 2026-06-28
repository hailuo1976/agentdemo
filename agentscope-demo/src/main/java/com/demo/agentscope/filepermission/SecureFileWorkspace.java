package com.demo.agentscope.filepermission;

import com.demo.agentscope.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 安全文件工作空间（装饰器模式）。
 * <p>
 * 包装底层 {@link Workspace} 实现（如 LocalWorkspace），在每个文件操作前
 * 进行权限验证，并记录所有文件访问日志。被拒绝的操作会抛出
 * {@link FilePermissionDeniedException}，不会触及底层工作空间。
 * </p>
 *
 * <h3>设计模式</h3>
 * <p>使用装饰器模式，对底层 Workspace 透明增强，不修改其原有实现。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Workspace local = new LocalWorkspace("/workspace");
 * FilePermissionConfig config = FilePermissionConfigLoader.strict();
 * FilePermissionManager manager = new FilePermissionManager(
 *     Path.of("/workspace"), config);
 * Workspace secure = new SecureFileWorkspace(local, manager);
 *
 * // Agent 通过 secure 工作空间操作文件，所有操作受权限管控
 * String content = secure.readFile("data/report.txt");  // 权限验证通过才执行
 * }</pre>
 */
public class SecureFileWorkspace implements Workspace {

    private static final Logger log = LoggerFactory.getLogger(SecureFileWorkspace.class);

    /** 被装饰的底层工作空间 */
    private final Workspace delegate;

    /** 文件权限管理器 */
    private final FilePermissionManager permissionManager;

    public SecureFileWorkspace(Workspace delegate, FilePermissionManager permissionManager) {
        this.delegate = Objects.requireNonNull(delegate, "底层工作空间不能为null");
        this.permissionManager = Objects.requireNonNull(permissionManager, "权限管理器不能为null");
        log.info("安全文件工作空间已创建: delegate={}, baseDir={}",
                delegate.getClass().getSimpleName(), permissionManager.getBaseDir());
    }

    @Override
    public void initialize() {
        delegate.initialize();
        log.info("安全文件工作空间已初始化");
    }

    @Override
    public String readFile(String path) {
        FilePermissionResult result = permissionManager.checkRead(path);
        if (!result.isAllowed()) {
            throw new FilePermissionDeniedException(path, FileOperation.READ, result.getReason());
        }
        return delegate.readFile(path);
    }

    @Override
    public void writeFile(String path, String content) {
        FilePermissionResult result = permissionManager.checkWrite(path);
        if (!result.isAllowed()) {
            throw new FilePermissionDeniedException(path, FileOperation.WRITE, result.getReason());
        }
        // 文件大小限制检查
        long size = content != null ? content.getBytes().length : 0;
        if (!permissionManager.isFileSizeAllowed(size)) {
            String reason = "文件大小超过限制: " + size + " bytes (上限: "
                    + permissionManager.getConfig().getMaxFileSizeBytes() + ")";
            permissionManager.getLogger().logDenied("agent", FileOperation.WRITE, path, reason);
            throw new FilePermissionDeniedException(path, FileOperation.WRITE, reason);
        }
        delegate.writeFile(path, content);
    }

    @Override
    public void editFile(String path, String oldText, String newText) {
        FilePermissionResult result = permissionManager.checkEdit(path);
        if (!result.isAllowed()) {
            throw new FilePermissionDeniedException(path, FileOperation.EDIT, result.getReason());
        }
        delegate.editFile(path, oldText, newText);
    }

    @Override
    public List<String> listFiles(String dir) {
        FilePermissionResult result = permissionManager.checkList(dir);
        if (!result.isAllowed()) {
            throw new FilePermissionDeniedException(dir, FileOperation.LIST, result.getReason());
        }
        return delegate.listFiles(dir);
    }

    @Override
    public CommandResult executeCommand(String command) {
        // 命令执行不在文件权限管控范围内，直接委托
        // 实际生产环境应通过 PermissionEngine 做命令级权限控制
        return delegate.executeCommand(command);
    }

    @Override
    public void cleanup() {
        delegate.cleanup();
        log.info("安全文件工作空间已清理");
    }

    @Override
    public String getType() {
        return "secure-" + delegate.getType();
    }

    /**
     * 获取底层工作空间。
     *
     * @return 被装饰的工作空间
     */
    public Workspace getDelegate() {
        return delegate;
    }

    /**
     * 获取文件权限管理器。
     *
     * @return 权限管理器
     */
    public FilePermissionManager getPermissionManager() {
        return permissionManager;
    }

    /**
     * 获取文件访问日志记录器。
     *
     * @return 日志记录器
     */
    public FileAccessLogger getAccessLogger() {
        return permissionManager.getLogger();
    }
}
