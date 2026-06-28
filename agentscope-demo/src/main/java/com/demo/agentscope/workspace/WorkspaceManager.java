package com.demo.agentscope.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作空间管理器。
 * <p>
 * 按三元组（userId, agentId, sessionId）管理工作空间实例，
 * 支持创建、获取、移除和列举工作空间。
 * 采用工厂方法模式，根据类型参数创建对应的工作空间实现。
 * </p>
 */
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    /** 工作空间注册表，key 为三元组拼接的标识 */
    private final Map<String, Workspace> registry;

    /** 默认本地工作空间根目录 */
    private static final String DEFAULT_BASE_DIR = System.getProperty("user.dir");

    public WorkspaceManager() {
        this.registry = new ConcurrentHashMap<>();
    }

    /**
     * 获取指定用户/智能体/会话的工作空间。
     * <p>
     * 如果对应的工作空间已存在则返回，否则返回 null。
     * </p>
     *
     * @param userId    用户ID
     * @param agentId   智能体ID
     * @param sessionId 会话ID
     * @return 工作空间实例，不存在则返回 null
     */
    public Workspace getWorkspace(String userId, String agentId, String sessionId) {
        String key = buildKey(userId, agentId, sessionId);
        Workspace ws = registry.get(key);
        if (ws != null) {
            log.debug("获取工作空间: key={}, type={}", key, ws.getType());
        } else {
            log.debug("工作空间不存在: key={}", key);
        }
        return ws;
    }

    /**
     * 创建工作空间。
     * <p>
     * 根据类型参数创建对应的工作空间实现：
     * <ul>
     *   <li>"local" - 本地文件系统工作空间</li>
     *   <li>"docker" - Docker 容器工作空间</li>
     *   <li>"e2b" - E2B 云沙箱工作空间</li>
     * </ul>
     * 如果同 key 的工作空间已存在，直接返回已有实例。
     * </p>
     *
     * @param type      工作空间类型（"local"/"docker"/"e2b"）
     * @param userId    用户ID
     * @param agentId   智能体ID
     * @param sessionId 会话ID
     * @return 创建的或已存在的工作空间实例
     */
    public Workspace createWorkspace(String type, String userId, String agentId, String sessionId) {
        String key = buildKey(userId, agentId, sessionId);

        // 已存在则直接返回
        Workspace existing = registry.get(key);
        if (existing != null) {
            log.debug("工作空间已存在，直接返回: key={}, type={}", key, existing.getType());
            return existing;
        }

        // 根据类型创建
        Workspace workspace = doCreate(type, userId, agentId, sessionId);

        // 注册并初始化
        registry.put(key, workspace);
        workspace.initialize();

        log.info("工作空间已创建: key={}, type={}", key, type);
        return workspace;
    }

    /**
     * 移除并清理工作空间。
     *
     * @param userId    用户ID
     * @param agentId   智能体ID
     * @param sessionId 会话ID
     */
    public void removeWorkspace(String userId, String agentId, String sessionId) {
        String key = buildKey(userId, agentId, sessionId);
        Workspace removed = registry.remove(key);
        if (removed != null) {
            try {
                removed.cleanup();
                log.info("工作空间已移除并清理: key={}, type={}", key, removed.getType());
            } catch (Exception e) {
                log.warn("工作空间清理异常: key={}", key, e);
            }
        } else {
            log.debug("工作空间不存在，跳过移除: key={}", key);
        }
    }

    /**
     * 获取所有已注册的工作空间。
     *
     * @return 工作空间列表
     */
    public List<Workspace> getAllWorkspaces() {
        return new ArrayList<>(registry.values());
    }

    /**
     * 获取已注册工作空间数量。
     *
     * @return 工作空间数量
     */
    public int size() {
        return registry.size();
    }

    // ==================== 内部方法 ====================

    /**
     * 工厂方法：根据类型创建工作空间实例。
     */
    private Workspace doCreate(String type, String userId, String agentId, String sessionId) {
        String effectiveType = type != null ? type.toLowerCase() : "local";

        return switch (effectiveType) {
            case "local" -> {
                // 本地工作空间：为每个会话创建独立子目录
                String baseDir = DEFAULT_BASE_DIR + "/workspace/" + userId + "/" + agentId + "/" + sessionId;
                yield new LocalWorkspace(baseDir);
            }
            case "docker" -> new DockerWorkspace("ubuntu:22.04");
            case "e2b" -> new E2BWorkspace(null);
            default -> throw new IllegalArgumentException("不支持的工作空间类型: " + type);
        };
    }

    /**
     * 构建工作空间注册表键。
     */
    private String buildKey(String userId, String agentId, String sessionId) {
        return userId + ":" + agentId + ":" + sessionId;
    }
}
