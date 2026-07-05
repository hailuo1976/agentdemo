package com.demo.agentscope.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 本地工作空间。
 * <p>
 * 基于 java.io/java.nio 实现的本地文件系统工作空间。
 * 所有路径相对于 baseDir 解析，使用 ProcessBuilder 执行命令并支持超时控制。
 * </p>
 */
public class LocalWorkspace implements Workspace {

    private static final Logger log = LoggerFactory.getLogger(LocalWorkspace.class);

    /** 默认命令执行超时时间（秒） */
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    /** 工作空间根目录 */
    private final Path baseDir;

    /** 命令执行超时时间（秒） */
    private final long timeoutSeconds;

    public LocalWorkspace(String baseDir) {
        this(baseDir, DEFAULT_TIMEOUT_SECONDS);
    }

    public LocalWorkspace(String baseDir, long timeoutSeconds) {
        Objects.requireNonNull(baseDir, "工作空间根目录不能为null");
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    @Override
    public void initialize() {
        try {
            Files.createDirectories(baseDir);
            log.info("本地工作空间已初始化: baseDir={}", baseDir);
        } catch (IOException e) {
            throw new RuntimeException("工作空间初始化失败: " + baseDir, e);
        }
    }

    @Override
    public String readFile(String path) {
        Path resolved = resolvePath(path);
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            log.debug("文件读取成功: path={}, size={}", path, content.length());
            return content;
        } catch (NoSuchFileException e) {
            throw new RuntimeException("文件不存在: " + path, e);
        } catch (IOException e) {
            throw new RuntimeException("文件读取失败: " + path, e);
        }
    }

    @Override
    public void writeFile(String path, String content) {
        Path resolved = resolvePath(path);
        try {
            // 确保父目录存在
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content != null ? content : "", StandardCharsets.UTF_8);
            log.debug("文件写入成功: path={}, size={}", path, content != null ? content.length() : 0);
        } catch (IOException e) {
            throw new RuntimeException("文件写入失败: " + path, e);
        }
    }

    @Override
    public void editFile(String path, String oldText, String newText) {
        String content = readFile(path);
        if (!content.contains(oldText)) {
            throw new RuntimeException("文件编辑失败: 未找到指定文本, path=" + path);
        }
        String updated = content.replace(oldText, newText);
        writeFile(path, updated);
        log.debug("文件编辑成功: path={}", path);
    }

    @Override
    public List<String> listFiles(String dir) {
        Path resolved = resolvePath(dir);
        List<String> names = new ArrayList<>();

        if (!Files.exists(resolved)) {
            log.debug("目录不存在: dir={}", dir);
            return names;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(resolved)) {
            for (Path entry : stream) {
                names.add(entry.getFileName().toString());
            }
        } catch (IOException e) {
            throw new RuntimeException("目录列表获取失败: " + dir, e);
        }

        log.debug("目录列表获取成功: dir={}, count={}", dir, names.size());
        return names;
    }

    @Override
    public CommandResult executeCommand(String command) {
        log.debug("执行命令: command={}", command);

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(baseDir.toFile());
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // 分别读取标准输出和标准错误
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            int exitCode;
            if (finished) {
                exitCode = process.exitValue();
            } else {
                process.destroyForcibly();
                log.warn("命令执行超时: command={}, timeout={}s", command, timeoutSeconds);
                return new CommandResult(-1, stdout, "命令执行超时 (" + timeoutSeconds + "秒)");
            }

            log.debug("命令执行完成: exitCode={}, stdoutLen={}, stderrLen={}",
                    exitCode, stdout.length(), stderr.length());

            return new CommandResult(exitCode, stdout, stderr);
        } catch (IOException e) {
            throw new RuntimeException("命令执行失败: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("命令执行被中断: " + command, e);
        }
    }

    @Override
    public void cleanup() {
        log.info("本地工作空间清理: baseDir={}", baseDir);
        // 本地工作空间通常不删除文件，仅记录日志
    }

    @Override
    public String getType() {
        return "local";
    }

    /**
     * 解析路径为绝对路径。
     * <p>
     * 支持相对路径和绝对路径：
     * <ul>
     *   <li>相对路径：相对于 baseDir 解析</li>
     *   <li>绝对路径：直接使用（权限检查由 FilePermissionManager 负责）</li>
     * </ul>
     * </p>
     *
     * @param path 文件路径（相对或绝对）
     * @return 绝对路径
     */
    private Path resolvePath(String path) {
        Objects.requireNonNull(path, "路径不能为null");
        
        Path inputPath = Paths.get(path);
        Path resolved;
        
        if (inputPath.isAbsolute()) {
            // 绝对路径：直接使用
            resolved = inputPath.toAbsolutePath().normalize();
        } else {
            // 相对路径：相对于 baseDir 解析
            resolved = baseDir.resolve(path).toAbsolutePath().normalize();
            
            // 安全检查：防止路径遍历（仅对相对路径）
            if (!resolved.startsWith(baseDir)) {
                throw new RuntimeException("路径越界: " + path + " 不在工作空间目录下");
            }
        }

        return resolved;
    }

    /**
     * 获取工作空间根目录路径。
     *
     * @return 根目录路径
     */
    public Path getBaseDir() {
        return baseDir;
    }
}
