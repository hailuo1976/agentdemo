package com.demo.agentscope.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Docker 工作空间。
 * <p>
 * 基于 Docker 容器的工作空间实现，提供隔离的执行环境。
 * 当前为桩实现，所有方法均抛出 UnsupportedOperationException，
 * 完整实现需要 Docker 运行时环境支持。
 * </p>
 */
public class DockerWorkspace implements Workspace {

    private static final Logger log = LoggerFactory.getLogger(DockerWorkspace.class);

    /** Docker 镜像名称 */
    private final String image;

    /** 容器ID */
    private String containerId;

    public DockerWorkspace(String image) {
        this.image = image != null ? image : "ubuntu:22.04";
        log.warn("Docker 工作空间为桩实现，需要 Docker 运行时环境");
    }

    @Override
    public void initialize() {
        log.warn("Docker 工作空间不可用: 需要安装 Docker 运行时");
        throw new UnsupportedOperationException("Docker workspace requires Docker runtime");
    }

    @Override
    public String readFile(String path) {
        throw new UnsupportedOperationException("Docker workspace requires Docker runtime");
    }

    @Override
    public void writeFile(String path, String content) {
        throw new UnsupportedOperationException("Docker workspace requires Docker runtime");
    }

    @Override
    public void editFile(String path, String oldText, String newText) {
        throw new UnsupportedOperationException("Docker workspace requires Docker runtime");
    }

    @Override
    public List<String> listFiles(String dir) {
        throw new UnsupportedOperationException("Docker workspace requires Docker runtime");
    }

    @Override
    public CommandResult executeCommand(String command) {
        throw new UnsupportedOperationException("Docker workspace requires Docker runtime");
    }

    @Override
    public void cleanup() {
        log.warn("Docker 工作空间不可用，清理操作跳过");
        throw new UnsupportedOperationException("Docker workspace requires Docker runtime");
    }

    @Override
    public String getType() {
        return "docker";
    }

    public String getImage() {
        return image;
    }

    public String getContainerId() {
        return containerId;
    }
}
