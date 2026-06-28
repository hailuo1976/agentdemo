package com.demo.agentscope.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令安全策略检查器。
 * <p>
 * 在执行 Shell 命令或 Python 代码前，检查是否包含危险操作。
 * 拦截以下高风险命令模式：
 * <ul>
 *   <li>递归强制删除根目录或家目录（rm -rf /、rm -rf ~）</li>
 *   <li>操作系统关键目录（/etc、/usr、/bin、/sbin、/boot、/sys、/proc）</li>
 *   <li>关机/重启命令（shutdown、reboot、halt、poweroff、init 0/6）</li>
 *   <li>修改系统用户/权限（useradd、userdel、passwd、chmod 777 /）</li>
 *   <li>Fork 炸弹（:(){ :|:&amp; };:）</li>
 *   <li>覆盖系统文件（/dev/sda、/dev/null 重定向到系统文件）</li>
 * </ul>
 * </p>
 */
public class CommandSafetyChecker {

    /** 危险命令正则模式列表 */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // 递归强制删除根目录或家目录
            Pattern.compile("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s+(/|~|\\$HOME)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("rm\\s+-rf\\s+/"),
            Pattern.compile("rm\\s+-rf\\s+~"),
            // 操作系统关键目录写入/删除
            Pattern.compile("(rm|mv|cp|chmod|chown)\\s+.*(/etc|/usr|/bin|/sbin|/boot|/sys|/proc|/var/log)\\b", Pattern.CASE_INSENSITIVE),
            // 关机/重启
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff|init\\s+[06])\\b", Pattern.CASE_INSENSITIVE),
            // 修改系统用户/权限
            Pattern.compile("\\b(useradd|userdel|usermod|groupdel|groupadd)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("chmod\\s+777\\s+/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpasswd\\b", Pattern.CASE_INSENSITIVE),
            // Fork 炸弹
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|\\s*&\\s*\\}\\s*;\\s*:"),
            // 覆盖块设备
            Pattern.compile(">\\s*/dev/sd[a-z]"),
            Pattern.compile("dd\\s+.*of=/dev/sd[a-z]", Pattern.CASE_INSENSITIVE),
            // mkfs 格式化磁盘
            Pattern.compile("\\bmkfs\\b", Pattern.CASE_INSENSITIVE),
            // 危险的 curl/wget 管道到 shell
            Pattern.compile("(curl|wget)\\s+.*\\|\\s*(sh|bash|zsh)\\b", Pattern.CASE_INSENSITIVE)
    );

    /** 安全检查结果 */
    public static class SafetyResult {
        private final boolean safe;
        private final String reason;

        private SafetyResult(boolean safe, String reason) {
            this.safe = safe;
            this.reason = reason;
        }

        public static SafetyResult safe() {
            return new SafetyResult(true, "安全检查通过");
        }

        public static SafetyResult dangerous(String reason) {
            return new SafetyResult(false, reason);
        }

        public boolean isSafe() {
            return safe;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * 检查命令是否安全。
     *
     * @param command 待检查的命令字符串
     * @return 安全检查结果
     */
    public SafetyResult check(String command) {
        if (command == null || command.isBlank()) {
            return SafetyResult.safe();
        }

        String normalized = command.trim();

        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return SafetyResult.dangerous("检测到危险命令模式: " + pattern.pattern());
            }
        }

        return SafetyResult.safe();
    }

    /**
     * 检查 Python 代码是否安全。
     * <p>
     * 主要检查 Python 代码中是否通过 os.system/subprocess 调用危险命令。
     * </p>
     *
     * @param code Python 代码
     * @return 安全检查结果
     */
    public SafetyResult checkPythonCode(String code) {
        if (code == null || code.isBlank()) {
            return SafetyResult.safe();
        }

        // 检查 Python 代码中调用的 shell 命令
        List<Pattern> pythonDangerousPatterns = List.of(
                Pattern.compile("os\\.system\\s*\\(\\s*[\"'].*rm\\s+-rf", Pattern.CASE_INSENSITIVE),
                Pattern.compile("subprocess\\..*rm\\s+-rf", Pattern.CASE_INSENSITIVE),
                Pattern.compile("os\\.system\\s*\\(\\s*[\"'].*(shutdown|reboot|halt)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("__import__\\s*\\(\\s*[\"']os[\"']\\s*\\).*\\.(system|exec)"),
                Pattern.compile("eval\\s*\\(\\s*compile\\s*\\(")
        );

        for (Pattern pattern : pythonDangerousPatterns) {
            if (pattern.matcher(code).find()) {
                return SafetyResult.dangerous("Python 代码中检测到危险操作: " + pattern.pattern());
            }
        }

        return SafetyResult.safe();
    }

    /**
     * 获取所有危险模式列表（用于文档展示）。
     *
     * @return 危险模式正则字符串列表
     */
    public List<String> getDangerousPatterns() {
        List<String> patterns = new ArrayList<>();
        for (Pattern p : DANGEROUS_PATTERNS) {
            patterns.add(p.pattern());
        }
        return patterns;
    }
}
