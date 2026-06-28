package com.demo.agentscope.event;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 智能体事件。
 * <p>
 * AgentScope 2.0 事件系统的核心数据结构，每条事件记录了智能体在运行过程中
 * 发生的状态变化或交互行为。通过静态工厂方法便捷创建各类型事件。
 * </p>
 */
public class AgentEvent {

    /** 事件唯一标识 */
    private final String id;

    /** 事件类型 */
    private final EventType type;

    /** 事件发生时间 */
    private final Instant timestamp;

    /** 事件附加数据 */
    private final Map<String, Object> data;

    /** 产生事件的智能体ID */
    private final String agentId;

    private AgentEvent(String id, EventType type, Instant timestamp,
                       Map<String, Object> data, String agentId) {
        this.id = Objects.requireNonNull(id, "事件ID不能为null");
        this.type = Objects.requireNonNull(type, "事件类型不能为null");
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
        this.agentId = agentId;
    }

    // ==================== Getter ====================

    public String getId() {
        return id;
    }

    public EventType getType() {
        return type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    public String getAgentId() {
        return agentId;
    }

    /**
     * 从事件数据中获取指定键的值。
     *
     * @param key 数据键
     * @return 对应的值，不存在则返回null
     */
    public Object getData(String key) {
        return data.get(key);
    }

    /**
     * 从事件数据中获取指定键的值，带类型转换。
     *
     * @param key   数据键
     * @param clazz 目标类型
     * @param <T>   目标类型泛型
     * @return 对应的值，不存在或类型不匹配则返回null
     */
    @SuppressWarnings("unchecked")
    public <T> T getData(String key, Class<T> clazz) {
        Object value = data.get(key);
        if (value != null && clazz.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    // ==================== 静态工厂方法 ====================

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static AgentEvent create(EventType type, Map<String, Object> data, String agentId) {
        return new AgentEvent(newId(), type, Instant.now(), data, agentId);
    }

    /**
     * 创建回复开始事件。
     *
     * @param agentId 智能体ID
     * @return 回复开始事件
     */
    public static AgentEvent replyStart(String agentId) {
        return create(EventType.REPLY_START, null, agentId);
    }

    /**
     * 创建回复结束事件。
     *
     * @param agentId 智能体ID
     * @return 回复结束事件
     */
    public static AgentEvent replyEnd(String agentId) {
        return create(EventType.REPLY_END, null, agentId);
    }

    /**
     * 创建模型调用开始事件。
     *
     * @param agentId   智能体ID
     * @param modelName 模型名称
     * @return 模型调用开始事件
     */
    public static AgentEvent modelCallStart(String agentId, String modelName) {
        Map<String, Object> data = new HashMap<>();
        data.put("modelName", modelName);
        return create(EventType.MODEL_CALL_START, data, agentId);
    }

    /**
     * 创建模型调用结束事件。
     *
     * @param agentId         智能体ID
     * @param modelName       模型名称
     * @param promptTokens    输入token数
     * @param completionTokens 输出token数
     * @return 模型调用结束事件
     */
    public static AgentEvent modelCallEnd(String agentId, String modelName,
                                          int promptTokens, int completionTokens) {
        Map<String, Object> data = new HashMap<>();
        data.put("modelName", modelName);
        data.put("promptTokens", promptTokens);
        data.put("completionTokens", completionTokens);
        return create(EventType.MODEL_CALL_END, data, agentId);
    }

    /**
     * 创建文本内容块事件。
     *
     * @param agentId 智能体ID
     * @param content 文本内容
     * @return 文本块事件
     */
    public static AgentEvent textBlock(String agentId, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("content", content);
        return create(EventType.TEXT_BLOCK, data, agentId);
    }

    /**
     * 创建思考内容块事件。
     *
     * @param agentId 智能体ID
     * @param content 思考内容
     * @return 思考块事件
     */
    public static AgentEvent thinkingBlock(String agentId, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("content", content);
        return create(EventType.THINKING_BLOCK, data, agentId);
    }

    /**
     * 创建工具调用事件。
     *
     * @param agentId  智能体ID
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 工具调用事件
     */
    public static AgentEvent toolCall(String agentId, String toolName, Map<String, Object> args) {
        Map<String, Object> data = new HashMap<>();
        data.put("toolName", toolName);
        data.put("arguments", args != null ? args : Collections.emptyMap());
        return create(EventType.TOOL_CALL, data, agentId);
    }

    /**
     * 创建工具结果事件。
     *
     * @param agentId  智能体ID
     * @param toolName 工具名称
     * @param result   工具执行结果
     * @return 工具结果事件
     */
    public static AgentEvent toolResult(String agentId, String toolName, String result) {
        Map<String, Object> data = new HashMap<>();
        data.put("toolName", toolName);
        data.put("result", result);
        return create(EventType.TOOL_RESULT, data, agentId);
    }

    /**
     * 创建需要用户确认事件。
     *
     * @param agentId 智能体ID
     * @param message 确认提示信息
     * @return 用户确认事件
     */
    public static AgentEvent requireUserConfirm(String agentId, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("message", message);
        return create(EventType.REQUIRE_USER_CONFIRM, data, agentId);
    }

    /**
     * 创建需要外部执行事件。
     *
     * @param agentId  智能体ID
     * @param command  外部执行命令
     * @return 外部执行事件
     */
    public static AgentEvent requireExternalExecution(String agentId, String command) {
        Map<String, Object> data = new HashMap<>();
        data.put("command", command);
        return create(EventType.REQUIRE_EXTERNAL_EXECUTION, data, agentId);
    }

    /**
     * 创建错误事件。
     *
     * @param agentId 智能体ID
     * @param message 错误信息
     * @return 错误事件
     */
    public static AgentEvent error(String agentId, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("message", message);
        return create(EventType.ERROR, data, agentId);
    }

    /**
     * 创建上下文压缩事件。
     *
     * @param agentId      智能体ID
     * @param originalSize 原始消息数
     * @param newSize      压缩后消息数
     * @return 上下文压缩事件
     */
    public static AgentEvent contextCompressed(String agentId, int originalSize, int newSize) {
        Map<String, Object> data = new HashMap<>();
        data.put("originalSize", originalSize);
        data.put("newSize", newSize);
        return create(EventType.CONTEXT_COMPRESSED, data, agentId);
    }

    /**
     * 创建权限检查事件。
     *
     * @param agentId     智能体ID
     * @param permission  权限标识
     * @param granted     是否授权
     * @return 权限检查事件
     */
    public static AgentEvent permissionCheck(String agentId, String permission, boolean granted) {
        Map<String, Object> data = new HashMap<>();
        data.put("permission", permission);
        data.put("granted", granted);
        return create(EventType.PERMISSION_CHECK, data, agentId);
    }

    /**
     * 创建权限询问事件。
     *
     * @param agentId    智能体ID
     * @param permission 权限标识
     * @param reason     询问原因
     * @return 权限询问事件
     */
    public static AgentEvent permissionAsk(String agentId, String permission, String reason) {
        Map<String, Object> data = new HashMap<>();
        data.put("permission", permission);
        data.put("reason", reason);
        return create(EventType.PERMISSION_ASK, data, agentId);
    }

    /**
     * 创建工作空间操作事件。
     *
     * @param agentId    智能体ID
     * @param operation  操作类型
     * @param path       操作路径
     * @return 工作空间操作事件
     */
    public static AgentEvent workspaceOperation(String agentId, String operation, String path) {
        Map<String, Object> data = new HashMap<>();
        data.put("operation", operation);
        data.put("path", path);
        return create(EventType.WORKSPACE_OPERATION, data, agentId);
    }

    /**
     * 创建智能体团队创建事件。
     *
     * @param agentId 智能体ID
     * @param teamId  团队ID
     * @param purpose 团队目标
     * @return 团队创建事件
     */
    public static AgentEvent agentTeamCreate(String agentId, String teamId, String purpose) {
        Map<String, Object> data = new HashMap<>();
        data.put("teamId", teamId);
        data.put("purpose", purpose);
        return create(EventType.AGENT_TEAM_CREATE, data, agentId);
    }

    /**
     * 创建智能体创建事件。
     *
     * @param agentId      创建者智能体ID
     * @param newAgentId   新智能体ID
     * @param agentType    智能体类型
     * @return 智能体创建事件
     */
    public static AgentEvent agentCreate(String agentId, String newAgentId, String agentType) {
        Map<String, Object> data = new HashMap<>();
        data.put("newAgentId", newAgentId);
        data.put("agentType", agentType);
        return create(EventType.AGENT_CREATE, data, agentId);
    }

    /**
     * 创建智能体消息事件。
     *
     * @param agentId       发送方智能体ID
     * @param targetAgentId 接收方智能体ID
     * @param message       消息内容
     * @return 智能体消息事件
     */
    public static AgentEvent agentMessage(String agentId, String targetAgentId, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("targetAgentId", targetAgentId);
        data.put("message", message);
        return create(EventType.AGENT_MESSAGE, data, agentId);
    }

    /**
     * 创建团队解散事件。
     *
     * @param agentId 智能体ID
     * @param teamId  团队ID
     * @return 团队解散事件
     */
    public static AgentEvent teamDissolve(String agentId, String teamId) {
        Map<String, Object> data = new HashMap<>();
        data.put("teamId", teamId);
        return create(EventType.TEAM_DISSOLVE, data, agentId);
    }

    /**
     * 创建回复预算超限事件。
     *
     * @param agentId 智能体ID
     * @param budget  预算值
     * @param actual  实际消耗
     * @return 预算超限事件
     */
    public static AgentEvent replyBudgetExceeded(String agentId, int budget, int actual) {
        Map<String, Object> data = new HashMap<>();
        data.put("budget", budget);
        data.put("actual", actual);
        return create(EventType.REPLY_BUDGET_EXCEEDED, data, agentId);
    }

    @Override
    public String toString() {
        return "AgentEvent{id='" + id + "', type=" + type +
                ", timestamp=" + timestamp + ", agentId='" + agentId + "'}";
    }
}
