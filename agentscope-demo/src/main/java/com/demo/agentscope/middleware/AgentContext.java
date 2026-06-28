package com.demo.agentscope.middleware;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体上下文。
 * <p>
 * 在中间件链中传递的上下文对象，携带智能体会话的元信息
 * 和可扩展的属性映射，支持并发安全地读写属性。
 * </p>
 */
public class AgentContext {

    /** 智能体ID */
    private final String agentId;

    /** 会话ID */
    private final String sessionId;

    /** 用户ID */
    private final String userId;

    /** 扩展属性，线程安全 */
    private final Map<String, Object> attributes;

    /** 回复开始时间（毫秒时间戳） */
    private long replyStartTime;

    public AgentContext(String agentId, String sessionId, String userId) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.attributes = new ConcurrentHashMap<>();
        this.replyStartTime = 0L;
    }

    // ==================== Getter ====================

    public String getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public long getReplyStartTime() {
        return replyStartTime;
    }

    public void setReplyStartTime(long replyStartTime) {
        this.replyStartTime = replyStartTime;
    }

    // ==================== 属性操作 ====================

    /**
     * 设置扩展属性。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        Objects.requireNonNull(key, "属性键不能为null");
        attributes.put(key, value);
    }

    /**
     * 获取扩展属性。
     *
     * @param key 属性键
     * @return 属性值，不存在则返回null
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * 获取扩展属性，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @param <T>          值类型
     * @return 属性值，不存在则返回默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, T defaultValue) {
        Object value = attributes.get(key);
        if (value != null) {
            return (T) value;
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return "AgentContext{agentId='" + agentId + "', sessionId='" + sessionId +
                "', userId='" + userId + "', attributes=" + attributes.size() + "}";
    }
}
