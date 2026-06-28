package com.demo.agentscope.filepermission;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 路径匹配模式。
 * <p>
 * 支持 glob 通配符语法，例如：
 * <ul>
 *   <li>{@code **&#47;*.txt} - 匹配任意目录下的 txt 文件</li>
 *   <li>{@code data/**} - 匹配 data 目录及其所有子目录内容</li>
 *   <li>{@code config/app.json} - 精确匹配</li>
 * </ul>
 * </p>
 */
public class PathPattern {

    /** 原始模式字符串 */
    private final String pattern;

    /** glob 路径匹配器 */
    private final PathMatcher matcher;

    public PathPattern(String pattern) {
        this.pattern = Objects.requireNonNull(pattern, "路径模式不能为null").trim();
        if (this.pattern.isEmpty()) {
            throw new IllegalArgumentException("路径模式不能为空");
        }
        // 使用 glob 语法构建匹配器
        this.matcher = Paths.get("").getFileSystem().getPathMatcher("glob:" + this.pattern);
    }

    /**
     * 判断给定的相对路径是否匹配此模式。
     *
     * @param relativePath 相对路径字符串
     * @return 是否匹配
     */
    public boolean matches(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        Path path = Paths.get(relativePath.replace('\\', '/'));
        return matcher.matches(path);
    }

    /**
     * 判断给定的路径是否匹配此模式。
     *
     * @param path 路径对象
     * @return 是否匹配
     */
    public boolean matches(Path path) {
        if (path == null) {
            return false;
        }
        return matcher.matches(path);
    }

    public String getPattern() {
        return pattern;
    }

    @Override
    public String toString() {
        return pattern;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathPattern that = (PathPattern) o;
        return pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern);
    }
}
