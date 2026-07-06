package com.demo.agentscope.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 代码执行管理器。
 * <p>
 * 提供 Python 代码执行、Shell 命令执行和 pip 包安装能力。
 * 所有执行都经过安全检查，并受超时限制。
 * 执行工作目录设为指定的工作空间，网络访问允许。
 * </p>
 *
 * <h3>安全策略</h3>
 * <pre>
 * 执行超时: 30秒（可配置）
 * 禁止操作: rm -rf /、操作系统目录、关机/重启、修改系统用户/权限
 * 工作目录: 工作空间目录
 * 网络访问: 允许（用于 API 调用、爬虫等）
 * </pre>
 */
public class CodeExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionManager.class);

    /** 默认执行超时（秒） */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    /** Python 可执行文件名 */
    private static final String PYTHON_CMD = resolvePythonCommand();

    /** pip 可执行文件名 */
    private static final String PIP_CMD = resolvePipCommand();

    /** 安全检查器 */
    private final CommandSafetyChecker safetyChecker;

    /** 执行工作目录 */
    private final Path workingDirectory;

    /** 执行超时（秒；volatile：REPL /config set 可运行期修改） */
    private volatile long timeoutSeconds;

    public CodeExecutionManager(Path workingDirectory) {
        this(workingDirectory, DEFAULT_TIMEOUT_SECONDS);
    }

    public CodeExecutionManager(Path workingDirectory, long timeoutSeconds) {
        this.workingDirectory = workingDirectory != null ? workingDirectory : Path.of(".");
        this.workingDirectory.toFile().mkdirs();
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.safetyChecker = new CommandSafetyChecker();
        log.info("代码执行管理器已初始化: workDir={}, timeout={}s, python={}", this.workingDirectory, timeoutSeconds, PYTHON_CMD);
    }

    /**
     * 运行期更新执行超时（由 REPL /config set 经主应用透传）。
     *
     * @param seconds 新的超时秒数，&lt;=0 将被忽略
     */
    public void updateTimeoutSeconds(long seconds) {
        if (seconds <= 0) {
            log.debug("updateTimeoutSeconds 收到非正值 {}，忽略", seconds);
            return;
        }
        long old = this.timeoutSeconds;
        this.timeoutSeconds = seconds;
        if (old != seconds) {
            log.info("代码执行超时已更新: {}s → {}s", old, seconds);
        }
    }

    // ==================== 执行方法 ====================

    /**
     * 执行 Python 代码。
     * <p>
     * 将代码写入临时文件，使用 python3 执行，捕获 stdout/stderr。
     * </p>
     *
     * @param code Python 代码
     * @return 执行结果
     */
    public ExecutionResult executePython(String code) {
        log.info("执行 Python 代码 ({}字符)", code != null ? code.length() : 0);

        // 安全检查
        CommandSafetyChecker.SafetyResult safety = safetyChecker.checkPythonCode(code);
        if (!safety.isSafe()) {
            log.warn("Python 代码安全检查未通过: {}", safety.getReason());
            return ExecutionResult.blocked(safety.getReason());
        }

        // 写入临时文件
        Path tempFile;
        try {
            tempFile = Files.createTempFile("agent_exec_", ".py");
            Files.writeString(tempFile, code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ExecutionResult.error("创建临时文件失败: " + e.getMessage());
        }

        try {
            return executeProcess(new String[]{PYTHON_CMD, tempFile.toString()}, "python");
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 执行 Shell 命令。
     * <p>
     * 使用 /bin/sh -c 执行命令，捕获 stdout/stderr。
     * </p>
     *
     * @param command Shell 命令
     * @return 执行结果
     */
    public ExecutionResult executeCommand(String command) {
        log.info("执行 Shell 命令: {}", command);

        // 安全检查
        CommandSafetyChecker.SafetyResult safety = safetyChecker.check(command);
        if (!safety.isSafe()) {
            log.warn("Shell 命令安全检查未通过: {}", safety.getReason());
            return ExecutionResult.blocked(safety.getReason());
        }

        return executeProcess("/bin/sh", "-c", command, "shell");
    }

    /**
     * 安装 Python 第三方库。
     * <p>
     * 使用 pip3 install 安装指定包。
     * </p>
     *
     * @param packageName 包名
     * @return 安装结果
     */
    public ExecutionResult installPackage(String packageName) {
        log.info("安装 Python 包: {}", packageName);

        if (packageName == null || packageName.isBlank()) {
            return ExecutionResult.error("包名不能为空");
        }

        // 对包名做基本安全检查（防止命令注入）
        if (packageName.contains(";") || packageName.contains("|") || packageName.contains("&")) {
            return ExecutionResult.error("包名包含非法字符: " + packageName);
        }

        return executeProcess(PIP_CMD, "install", packageName, "pip");
    }

    // ==================== 核心执行逻辑 ====================

    /**
     * 执行外部进程。
     *
     * @param command 命令及参数
     * @param label   执行标签（用于日志）
     * @return 执行结果
     */
    private ExecutionResult executeProcess(String[] command, String label) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(false);

        Process process = null;
        try {
            process = pb.start();

            // 读取 stdout 和 stderr
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            // 等待进程完成，带超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[{}] 执行超时（{}秒），已强制终止", label, timeoutSeconds);
                return ExecutionResult.timeout(stdout, stderr, timeoutSeconds);
            }

            int exitCode = process.exitValue();
            log.debug("[{}] 执行完成，exitCode={}", label, exitCode);

            return new ExecutionResult(exitCode, stdout, stderr, false, false, null);

        } catch (IOException e) {
            log.error("[{}] 进程启动失败: {}", label, e.getMessage());
            return ExecutionResult.error("进程启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.error("执行被中断: " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 执行外部进程（可变参数重载）。
     */
    private ExecutionResult executeProcess(String... commandParts) {
        String label = commandParts[0];
        return executeProcess(commandParts, label);
    }

    /**
     * 执行外部进程（指定标签）。
     */
    private ExecutionResult executeProcess(String command, String arg, String label) {
        return executeProcess(new String[]{command, arg}, label);
    }

    /**
     * 读取进程输出流。
     */
    private String readStream(java.io.InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        }
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    /**
     * 检测可用的 Python 命令。
     */
    private static String resolvePythonCommand() {
        for (String cmd : new String[]{"python3", "python"}) {
            if (isCommandAvailable(cmd)) {
                return cmd;
            }
        }
        log.warn("未找到 Python 可执行文件，默认使用 python3");
        return "python3";
    }

    /**
     * 检测可用的 pip 命令。
     */
    private static String resolvePipCommand() {
        for (String cmd : new String[]{"pip3", "pip"}) {
            if (isCommandAvailable(cmd)) {
                return cmd;
            }
        }
        log.warn("未找到 pip 可执行文件，默认使用 pip3");
        return "pip3";
    }

    /**
     * 检查命令是否可用。
     */
    private static boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", "command -v " + command).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public CommandSafetyChecker getSafetyChecker() {
        return safetyChecker;
    }

    public static String getPythonCommand() {
        return PYTHON_CMD;
    }

    public static String getPipCommand() {
        return PIP_CMD;
    }

    // ==================== 执行结果 ====================

    /**
     * 代码执行结果。
     */
    public static class ExecutionResult {

        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final boolean timedOut;
        private final boolean blocked;
        private final String errorMessage;

        public ExecutionResult(int exitCode, String stdout, String stderr,
                               boolean timedOut, boolean blocked, String errorMessage) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.timedOut = timedOut;
            this.blocked = blocked;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return !blocked && !timedOut && errorMessage == null && exitCode == 0;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public boolean isBlocked() {
            return blocked;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public static ExecutionResult timeout(String stdout, String stderr, long timeout) {
            return new ExecutionResult(-1, stdout, stderr, true, false,
                    "执行超时（" + timeout + "秒）");
        }

        public static ExecutionResult blocked(String reason) {
            return new ExecutionResult(-1, "", "", false, true, "安全检查未通过: " + reason);
        }

        public static ExecutionResult error(String message) {
            return new ExecutionResult(-1, "", "", false, false, message);
        }

        @Override
        public String toString() {
            if (blocked) {
                return "[被拦截] " + errorMessage;
            }
            if (timedOut) {
                return "[超时] " + errorMessage + "\nstdout: " + stdout + "\nstderr: " + stderr;
            }
            if (errorMessage != null) {
                return "[错误] " + errorMessage;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[exitCode=").append(exitCode).append("]");
            if (!stdout.isEmpty()) {
                sb.append("\nstdout:\n").append(stdout);
            }
            if (!stderr.isEmpty()) {
                sb.append("\nstderr:\n").append(stderr);
            }
            return sb.toString();
        }
    }
}
