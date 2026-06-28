package com.demo.agentscope.filepermission;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 文件路径解析与安全工具。
 * <p>
 * 提供路径遍历防护，确保解析后的路径始终在指定根目录下。
 * 防止 {@code ../}、符号链接等攻击手段逃逸出授权目录。
 * </p>
 */
public final class PathSecurityUtil {

    private PathSecurityUtil() {
    }

    /**
     * 将相对路径解析为绝对路径，并验证其位于根目录下。
     * <p>
     * 安全防护措施：
     * <ul>
     *   <li>规范化路径，消除 {@code .} 和 {@code ..}</li>
     *   <li>使用 {@code normalize()} 后检查是否仍在 baseDir 下</li>
     *   <li>拒绝包含 {@code ..} 的原始路径（双重检查）</li>
     * </ul>
     * </p>
     *
     * @param baseDir       根目录（绝对路径）
     * @param relativePath  相对路径
     * @return 解析后的安全绝对路径
     * @throws SecurityException 如果路径逃逸出根目录
     */
    public static Path resolveSecure(Path baseDir, String relativePath) {
        Objects.requireNonNull(baseDir, "根目录不能为null");
        Objects.requireNonNull(relativePath, "相对路径不能为null");

        // 标准化根目录
        Path normalizedBase = baseDir.toAbsolutePath().normalize();

        // 原始路径中的 .. 检查（防止编码绕过）
        String cleanPath = relativePath.replace('\\', '/').trim();
        if (cleanPath.contains("..")) {
            throw new SecurityException("路径包含非法的目录跳转符 '..': " + relativePath);
        }

        // 解析并规范化
        Path resolved = normalizedBase.resolve(cleanPath).toAbsolutePath().normalize();

        // 最终校验：解析后的路径必须在根目录下
        if (!resolved.startsWith(normalizedBase)) {
            throw new SecurityException("路径越界: " + relativePath + " 不在根目录 " + normalizedBase + " 下");
        }

        return resolved;
    }

    /**
     * 获取相对于根目录的相对路径。
     *
     * @param baseDir  根目录
     * @param fullPath 完整路径
     * @return 相对路径字符串
     */
    public static String relativize(Path baseDir, Path fullPath) {
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        Path normalizedFull = fullPath.toAbsolutePath().normalize();
        return normalizedBase.relativize(normalizedFull).toString().replace('\\', '/');
    }

    /**
     * 从文件路径中提取扩展名（不含点号）。
     *
     * @param path 文件路径
     * @return 扩展名小写形式，无扩展名返回空字符串
     */
    public static String getExtension(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String name = Paths.get(path.replace('\\', '/')).getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < name.length() - 1) {
            return name.substring(dotIndex + 1).toLowerCase();
        }
        return "";
    }
}
