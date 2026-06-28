package com.demo.agentscope.event;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件流。
 * <p>
 * 维护一个按时间顺序排列的事件列表，支持事件的发射、遍历和回放。
 * 回放（replay）功能可以将事件流中记录的所有事件重新构建为一条完整的
 * assistant 类型的 Msg 消息，用于事件溯源和状态恢复。
 * </p>
 */
public class EventStream {

    private static final Logger log = LoggerFactory.getLogger(EventStream.class);

    /** 事件列表，使用线程安全集合支持并发访问 */
    private final List<AgentEvent> events;

    /** 关联的智能体ID */
    private final String agentId;

    public EventStream(String agentId) {
        this.agentId = agentId;
        this.events = new CopyOnWriteArrayList<>();
    }

    /**
     * 发射（添加）一个事件到事件流中。
     *
     * @param event 智能体事件
     */
    public void emit(AgentEvent event) {
        if (event != null) {
            events.add(event);
            log.debug("事件发射: type={}, agentId={}, eventId={}",
                    event.getType(), event.getAgentId(), event.getId());
        }
    }

    /**
     * 获取事件流中所有事件的不可变视图。
     *
     * @return 事件列表
     */
    public List<AgentEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * 获取事件流中的事件数量。
     *
     * @return 事件数量
     */
    public int size() {
        return events.size();
    }

    /**
     * 判断事件流是否为空。
     *
     * @return 是否为空
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * 按事件类型筛选事件。
     *
     * @param type 事件类型
     * @return 匹配的事件列表
     */
    public List<AgentEvent> getEventsByType(EventType type) {
        List<AgentEvent> result = new ArrayList<>();
        for (AgentEvent event : events) {
            if (event.getType() == type) {
                result.add(event);
            }
        }
        return result;
    }

    /**
     * 回放事件流，重建为一条 assistant 类型的 Msg 消息。
     * <p>
     * 遍历所有事件，将 TEXT_BLOCK 转换为 TextBlock、THINKING_BLOCK 转换为 ThinkingBlock、
     * TOOL_CALL 转换为 ToolCallBlock、TOOL_RESULT 转换为 ToolResultBlock，
     * 并汇总模型调用的 token 用量。
     * </p>
     *
     * @return 重建的 Msg 对象
     */
    public Msg replay() {
        log.debug("开始回放事件流, 事件数={}, agentId={}", events.size(), agentId);

        List<ContentBlock> contentBlocks = new ArrayList<>();
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        Instant firstTimestamp = null;
        Instant lastTimestamp = null;
        Map<String, Object> metadata = new HashMap<>();

        int toolCallIndex = 0;

        for (AgentEvent event : events) {
            if (firstTimestamp == null) {
                firstTimestamp = event.getTimestamp();
            }
            lastTimestamp = event.getTimestamp();

            switch (event.getType()) {
                case TEXT_BLOCK -> {
                    String content = event.getData("content", String.class);
                    if (content != null) {
                        contentBlocks.add(new ContentBlock.TextBlock(content));
                    }
                }
                case THINKING_BLOCK -> {
                    String content = event.getData("content", String.class);
                    if (content != null) {
                        contentBlocks.add(new ContentBlock.ThinkingBlock(content));
                    }
                }
                case TOOL_CALL -> {
                    String toolName = event.getData("toolName", String.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = event.getData("arguments", Map.class);
                    String argsStr = "";
                    if (args != null && !args.isEmpty()) {
                        argsStr = args.toString();
                    }
                    String callId = "call_" + toolCallIndex++;
                    contentBlocks.add(new ContentBlock.ToolCallBlock(callId, toolName, argsStr));
                }
                case TOOL_RESULT -> {
                    String toolName = event.getData("toolName", String.class);
                    String result = event.getData("result", String.class);
                    String resultCallId = "call_" + Math.max(0, toolCallIndex - 1);
                    contentBlocks.add(new ContentBlock.ToolResultBlock(resultCallId, result, false));
                }
                case MODEL_CALL_END -> {
                    Integer promptTokens = event.getData("promptTokens", Integer.class);
                    Integer completionTokens = event.getData("completionTokens", Integer.class);
                    if (promptTokens != null) totalPromptTokens += promptTokens;
                    if (completionTokens != null) totalCompletionTokens += completionTokens;
                }
                case ERROR -> {
                    String message = event.getData("message", String.class);
                    if (message != null) {
                        contentBlocks.add(new ContentBlock.TextBlock("[ERROR] " + message));
                    }
                }
                default -> {
                    // 其他事件类型不影响消息内容重建，跳过
                }
            }
        }

        Msg.TokenUsage usage = new Msg.TokenUsage(totalPromptTokens, totalCompletionTokens);
        String msgId = UUID.randomUUID().toString();

        metadata.put("agentId", agentId);
        metadata.put("eventCount", events.size());
        if (firstTimestamp != null) {
            metadata.put("firstEventTimestamp", firstTimestamp);
        }
        if (lastTimestamp != null) {
            metadata.put("lastEventTimestamp", lastTimestamp);
        }

        log.debug("事件流回放完成: contentBlocks={}, totalTokens={}",
                contentBlocks.size(), usage.getTotalTokens());

        return new Msg(msgId, "assistant", contentBlocks, usage, lastTimestamp, metadata);
    }

    /**
     * 将事件流中所有文本内容拼接为字符串。
     * <p>
     * 提取 TEXT_BLOCK 事件的文本内容，按顺序拼接。
     * </p>
     *
     * @return 拼接后的文本内容
     */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent event : events) {
            if (event.getType() == EventType.TEXT_BLOCK) {
                String content = event.getData("content", String.class);
                if (content != null) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(content);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 清空事件流中的所有事件。
     */
    public void clear() {
        events.clear();
        log.debug("事件流已清空, agentId={}", agentId);
    }

    @Override
    public String toString() {
        return "EventStream{agentId='" + agentId + "', eventCount=" + events.size() + "}";
    }
}
