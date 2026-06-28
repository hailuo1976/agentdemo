package com.demo.agentscope.workspace;

import java.util.List;

/**
 * 工作空间接口。
 * <p>
 * AgentScope 2.0 工作空间抽象，定义了智能体与执行环境交互的标准操作集合，
 * 包括文件读写、命令执行等。支持多种实现：本地文件系统（LocalWorkspace）、
 * Docker 容器（DockerWorkspace）和 E2B 云沙箱（E2BWorkspace）。
 * </p>
 */
public interface Workspace {

    /**
     * 命令执行结果。
     * <p>
     * 封装命令执行的退出码、标准输出和标准错误。
     * </p>
     */
    class CommandResult {

        /** 退出码，0 表示成功 */
        private final int exitCode;

        /** 标准输出内容 */
        private final String stdout;

        /** 标准错误内容 */
        private final String stderr;

        public CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
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

        public boolean isSuccess() {
            return exitCode == 0;
        }

        @Override
        public String toString() {
            return "CommandResult{exitCode=" + exitCode +
                    ", stdoutLen=" + stdout.length() +
                    ", stderrLen=" + stderr.length() + "}";
        }
    }

    /**
     * 初始化工作空间。
     * <p>在首次使用前调用，用于创建必要目录、建立连接等。</p>
     */
    void initialize();

    /**
     * 读取文件内容。
     *
     * @param path 文件路径（相对于工作空间根目录）
     * @return 文件内容字符串
     */
    String readFile(String path);

    /**
     * 写入文件。
     *
     * @param path    文件路径（相对于工作空间根目录）
     * @param content 文件内容
     */
    void writeFile(String path, String content);

    /**
     * 编辑文件，替换指定文本。
     *
     * @param path    文件路径（相对于工作空间根目录）
     * @param oldText 待替换的旧文本
     * @param newText 替换后的新文本
     */
    void editFile(String path, String oldText, String newText);

    /**
     * 列出目录下的文件。
     *
     * @param dir 目录路径（相对于工作空间根目录）
     * @return 文件/目录名称列表
     */
    List<String> listFiles(String dir);

    /**
     * 执行命令。
     *
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    CommandResult executeCommand(String command);

    /**
     * 清理工作空间资源。
     * <p>在工作空间不再使用时调用，用于释放资源、断开连接等。</p>
     */
    void cleanup();

    /**
     * 获取工作空间类型标识。
     *
     * @return 类型字符串（"local"/"docker"/"e2b"）
     */
    String getType();
}
