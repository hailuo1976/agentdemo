package com.demo.agentscope.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * E2B 云沙箱工作空间。
 * <p>
 * 基于 E2B (Code Interpreter SDK) 的云沙箱工作空间实现，
 * 提供完全隔离的云端执行环境。
 * 当前为桩实现，所有方法均抛出 UnsupportedOperationException，
 * 完整实现需要 E2B SDK 依赖及 API Key 配置。
 * </p>
 */
public class E2BWorkspace implements Workspace {

    private static final Logger log = LoggerFactory.getLogger(E2BWorkspace.class);

    /** E2B 沙箱ID */
    private String sandboxId;

    /** E2B API Key */
    private final String apiKey;

    public E2BWorkspace(String apiKey) {
        this.apiKey = apiKey;
        log.warn("E2B 工作空间为桩实现，需要 E2B SDK 和 API Key");
    }

    @Override
    public void initialize() {
        log.warn("E2B 工作空间不可用: 需要 E2B SDK 依赖");
        throw new UnsupportedOperationException("E2B workspace requires E2B SDK");
    }

    @Override
    public String readFile(String path) {
        throw new UnsupportedOperationException("E2B workspace requires E2B SDK");
    }

    @Override
    public void writeFile(String path, String content) {
        throw new UnsupportedOperationException("E2B workspace requires E2B SDK");
    }

    @Override
    public void editFile(String path, String oldText, String newText) {
        throw new UnsupportedOperationException("E2B workspace requires E2B SDK");
    }

    @Override
    public List<String> listFiles(String dir) {
        throw new UnsupportedOperationException("E2B workspace requires E2B SDK");
    }

    @Override
    public CommandResult executeCommand(String command) {
        throw new UnsupportedOperationException("E2B workspace requires E2B SDK");
    }

    @Override
    public void cleanup() {
        log.warn("E2B 工作空间不可用，清理操作跳过");
        throw new UnsupportedOperationException("E2B workspace requires E2B SDK");
    }

    @Override
    public String getType() {
        return "e2b";
    }

    public String getSandboxId() {
        return sandboxId;
    }
}
