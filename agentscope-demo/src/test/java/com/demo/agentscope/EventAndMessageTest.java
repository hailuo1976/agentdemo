package com.demo.agentscope;

import com.demo.agentscope.event.AgentEvent;
import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.event.EventType;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件系统与消息模型测试。
 */
@DisplayName("事件系统与消息模型测试")
class EventAndMessageTest {

    private static final String AGENT_ID = "test-agent";

    // ==================== AgentEvent 工厂方法测试 ====================

    @Test
    @DisplayName("AgentEvent.textBlock 创建 TEXT_BLOCK 类型事件")
    void textBlockCreatesCorrectEventType() {
        AgentEvent event = AgentEvent.textBlock(AGENT_ID, "hello");
        assertEquals(EventType.TEXT_BLOCK, event.getType());
        assertEquals("hello", event.getData("content", String.class));
        assertEquals(AGENT_ID, event.getAgentId());
        assertNotNull(event.getId());
        assertNotNull(event.getTimestamp());
    }

    @Test
    @DisplayName("AgentEvent.thinkingBlock 创建 THINKING_BLOCK 类型事件")
    void thinkingBlockCreatesCorrectEventType() {
        AgentEvent event = AgentEvent.thinkingBlock(AGENT_ID, "thinking...");
        assertEquals(EventType.THINKING_BLOCK, event.getType());
        assertEquals("thinking...", event.getData("content", String.class));
    }

    @Test
    @DisplayName("AgentEvent.toolCall 创建 TOOL_CALL 类型事件")
    void toolCallCreatesCorrectEventType() {
        Map<String, Object> args = Map.of("city", "Beijing");
        AgentEvent event = AgentEvent.toolCall(AGENT_ID, "get_weather", args);
        assertEquals(EventType.TOOL_CALL, event.getType());
        assertEquals("get_weather", event.getData("toolName", String.class));
        assertNotNull(event.getData("arguments", Map.class));
    }

    @Test
    @DisplayName("AgentEvent.toolResult 创建 TOOL_RESULT 类型事件")
    void toolResultCreatesCorrectEventType() {
        AgentEvent event = AgentEvent.toolResult(AGENT_ID, "get_weather", "sunny");
        assertEquals(EventType.TOOL_RESULT, event.getType());
        assertEquals("get_weather", event.getData("toolName", String.class));
        assertEquals("sunny", event.getData("result", String.class));
    }

    @Test
    @DisplayName("AgentEvent.replyStart/replyEnd 创建正确类型事件")
    void replyLifecycleEvents() {
        AgentEvent start = AgentEvent.replyStart(AGENT_ID);
        AgentEvent end = AgentEvent.replyEnd(AGENT_ID);
        assertEquals(EventType.REPLY_START, start.getType());
        assertEquals(EventType.REPLY_END, end.getType());
    }

    @Test
    @DisplayName("AgentEvent.modelCallStart/modelCallEnd 创建正确类型事件并携带数据")
    void modelCallEvents() {
        AgentEvent start = AgentEvent.modelCallStart(AGENT_ID, "gpt-4o-mini");
        assertEquals(EventType.MODEL_CALL_START, start.getType());
        assertEquals("gpt-4o-mini", start.getData("modelName", String.class));

        AgentEvent end = AgentEvent.modelCallEnd(AGENT_ID, "gpt-4o-mini", 100, 50);
        assertEquals(EventType.MODEL_CALL_END, end.getType());
        assertEquals(100, end.getData("promptTokens", Integer.class));
        assertEquals(50, end.getData("completionTokens", Integer.class));
    }

    @Test
    @DisplayName("AgentEvent.error 创建 ERROR 类型事件")
    void errorEvent() {
        AgentEvent event = AgentEvent.error(AGENT_ID, "something went wrong");
        assertEquals(EventType.ERROR, event.getType());
        assertEquals("something went wrong", event.getData("message", String.class));
    }

    @Test
    @DisplayName("AgentEvent.getData 返回不可变数据视图")
    void eventDataIsUnmodifiable() {
        AgentEvent event = AgentEvent.textBlock(AGENT_ID, "test");
        Map<String, Object> data = event.getData();
        assertThrows(UnsupportedOperationException.class, () -> data.put("key", "value"));
    }

    // ==================== EventStream 测试 ====================

    private EventStream eventStream;

    @BeforeEach
    void setUp() {
        eventStream = new EventStream(AGENT_ID);
    }

    @Test
    @DisplayName("EventStream emit 和 size 正常工作")
    void emitAndSize() {
        assertTrue(eventStream.isEmpty());
        eventStream.emit(AgentEvent.textBlock(AGENT_ID, "hello"));
        assertEquals(1, eventStream.size());
        eventStream.emit(AgentEvent.textBlock(AGENT_ID, "world"));
        assertEquals(2, eventStream.size());
    }

    @Test
    @DisplayName("EventStream emit null 事件不增加大小")
    void emitNullDoesNotIncreaseSize() {
        eventStream.emit(null);
        assertTrue(eventStream.isEmpty());
        assertEquals(0, eventStream.size());
    }

    @Test
    @DisplayName("EventStream replay 将 TEXT_BLOCK 和 TOOL_CALL 事件重建为 Msg")
    void replayProducesMsgWithContentBlocks() {
        eventStream.emit(AgentEvent.textBlock(AGENT_ID, "Hello"));
        Map<String, Object> args = Map.of("city", "Beijing");
        eventStream.emit(AgentEvent.toolCall(AGENT_ID, "get_weather", args));
        eventStream.emit(AgentEvent.toolResult(AGENT_ID, "get_weather", "sunny, 22°C"));

        Msg msg = eventStream.replay();

        assertEquals("assistant", msg.getRole());
        assertNotNull(msg.getId());
        assertEquals(3, msg.getContent().size());

        // 第一个内容块应该是 TextBlock
        ContentBlock block0 = msg.getContent().get(0);
        assertInstanceOf(ContentBlock.TextBlock.class, block0);
        assertEquals("Hello", ((ContentBlock.TextBlock) block0).getText());

        // 第二个内容块应该是 ToolCallBlock
        ContentBlock block1 = msg.getContent().get(1);
        assertInstanceOf(ContentBlock.ToolCallBlock.class, block1);
        assertEquals("get_weather", ((ContentBlock.ToolCallBlock) block1).getName());

        // 第三个内容块应该是 ToolResultBlock
        ContentBlock block2 = msg.getContent().get(2);
        assertInstanceOf(ContentBlock.ToolResultBlock.class, block2);
        assertEquals("sunny, 22°C", ((ContentBlock.ToolResultBlock) block2).getContent());
    }

    @Test
    @DisplayName("EventStream replay 汇总 MODEL_CALL_END 的 token 用量")
    void replayAggregatesTokenUsage() {
        eventStream.emit(AgentEvent.modelCallEnd(AGENT_ID, "gpt-4o-mini", 100, 50));
        eventStream.emit(AgentEvent.modelCallEnd(AGENT_ID, "gpt-4o-mini", 200, 80));

        Msg msg = eventStream.replay();

        Msg.TokenUsage usage = msg.getUsage();
        assertEquals(300, usage.getPromptTokens());
        assertEquals(130, usage.getCompletionTokens());
        assertEquals(430, usage.getTotalTokens());
    }

    @Test
    @DisplayName("EventStream toText 拼接所有文本事件")
    void toTextConcatenatesTextEvents() {
        eventStream.emit(AgentEvent.textBlock(AGENT_ID, "Hello"));
        eventStream.emit(AgentEvent.textBlock(AGENT_ID, "World"));
        eventStream.emit(AgentEvent.toolCall(AGENT_ID, "some_tool", Map.of()));

        String text = eventStream.toText();
        assertEquals("Hello\nWorld", text);
    }

    @Test
    @DisplayName("EventStream getEventsByType 按类型筛选事件")
    void getEventsByTypeFiltersCorrectly() {
        eventStream.emit(AgentEvent.textBlock(AGENT_ID, "text1"));
        eventStream.emit(AgentEvent.toolCall(AGENT_ID, "tool1", Map.of()));
        eventStream.emit(AgentEvent.textBlock(AGENT_ID, "text2"));

        List<AgentEvent> textEvents = eventStream.getEventsByType(EventType.TEXT_BLOCK);
        assertEquals(2, textEvents.size());

        List<AgentEvent> toolEvents = eventStream.getEventsByType(EventType.TOOL_CALL);
        assertEquals(1, toolEvents.size());
    }

    @Test
    @DisplayName("EventStream clear 清空所有事件")
    void clearRemovesAllEvents() {
        eventStream.emit(AgentEvent.textBlock(AGENT_ID, "hello"));
        eventStream.clear();
        assertTrue(eventStream.isEmpty());
    }

    // ==================== ContentBlock 子类测试 ====================

    @Test
    @DisplayName("TextBlock 类型为 text，文本内容正确")
    void textBlockTypeAndContent() {
        ContentBlock.TextBlock block = new ContentBlock.TextBlock("hello");
        assertEquals("text", block.getType());
        assertEquals("hello", block.getText());
    }

    @Test
    @DisplayName("TextBlock 接受 null 文本，转为空字符串")
    void textBlockNullTextBecomesEmpty() {
        ContentBlock.TextBlock block = new ContentBlock.TextBlock(null);
        assertEquals("", block.getText());
    }

    @Test
    @DisplayName("ToolCallBlock 包含 id、name 和 arguments")
    void toolCallBlockProperties() {
        ContentBlock.ToolCallBlock block = new ContentBlock.ToolCallBlock("call_0", "get_weather", "{\"city\":\"BJ\"}");
        assertEquals("tool_call", block.getType());
        assertEquals("call_0", block.getId());
        assertEquals("get_weather", block.getName());
        assertEquals("{\"city\":\"BJ\"}", block.getArguments());
    }

    @Test
    @DisplayName("ToolResultBlock 包含 toolCallId、content 和 isError")
    void toolResultBlockProperties() {
        ContentBlock.ToolResultBlock block = new ContentBlock.ToolResultBlock("call_0", "sunny", false);
        assertEquals("tool_result", block.getType());
        assertEquals("call_0", block.getToolCallId());
        assertEquals("sunny", block.getContent());
        assertFalse(block.isError());
    }

    @Test
    @DisplayName("ToolResultBlock 错误结果 isError 为 true")
    void toolResultBlockError() {
        ContentBlock.ToolResultBlock block = new ContentBlock.ToolResultBlock("call_1", "permission denied", true);
        assertTrue(block.isError());
    }

    @Test
    @DisplayName("ThinkingBlock 类型为 thinking，文本内容正确")
    void thinkingBlockTypeAndContent() {
        ContentBlock.ThinkingBlock block = new ContentBlock.ThinkingBlock("let me think...");
        assertEquals("thinking", block.getType());
        assertEquals("let me think...", block.getText());
    }

    @Test
    @DisplayName("HintBlock 类型为 hint，文本内容正确")
    void hintBlockTypeAndContent() {
        ContentBlock.HintBlock block = new ContentBlock.HintBlock("please confirm");
        assertEquals("hint", block.getType());
        assertEquals("please confirm", block.getText());
    }

    // ==================== Msg 测试 ====================

    @Test
    @DisplayName("Msg 构造与 getter 正常工作")
    void msgCreationAndGetters() {
        List<ContentBlock> blocks = List.of(new ContentBlock.TextBlock("hello"));
        Msg.TokenUsage usage = new Msg.TokenUsage(10, 5);
        Instant now = Instant.now();

        Msg msg = new Msg("msg-1", "assistant", blocks, usage, now, Map.of("key", "value"));

        assertEquals("msg-1", msg.getId());
        assertEquals("assistant", msg.getRole());
        assertEquals(1, msg.getContent().size());
        assertEquals(usage, msg.getUsage());
        assertEquals(now, msg.getTimestamp());
        assertEquals("value", msg.getMetadata("key"));
    }

    @Test
    @DisplayName("Msg 简易构造（无 usage 和 metadata）")
    void msgSimpleConstructor() {
        List<ContentBlock> blocks = List.of(new ContentBlock.TextBlock("hi"));
        Msg msg = new Msg("msg-2", "user", blocks);

        assertEquals("msg-2", msg.getId());
        assertEquals("user", msg.getRole());
        assertNotNull(msg.getUsage());
        assertEquals(0, msg.getUsage().getTotalTokens());
        assertNotNull(msg.getTimestamp());
    }

    @Test
    @DisplayName("Msg.getTextContent 拼接所有文本块")
    void msgGetTextContent() {
        Msg msg = new Msg("msg-3", "assistant", List.of(
                new ContentBlock.TextBlock("Hello"),
                new ContentBlock.ThinkingBlock("thinking..."),
                new ContentBlock.TextBlock("World")
        ));

        assertEquals("Hello\nWorld", msg.getTextContent());
    }

    @Test
    @DisplayName("Msg.hasToolCalls 和 getToolCalls 正常工作")
    void msgToolCalls() {
        Msg msgWithTools = new Msg("msg-4", "assistant", List.of(
                new ContentBlock.TextBlock("calling tool"),
                new ContentBlock.ToolCallBlock("call_0", "get_weather", "{}")
        ));

        assertTrue(msgWithTools.hasToolCalls());
        assertEquals(1, msgWithTools.getToolCalls().size());
        assertEquals("get_weather", msgWithTools.getToolCalls().get(0).getName());
    }

    @Test
    @DisplayName("Msg 无工具调用时 hasToolCalls 返回 false")
    void msgNoToolCalls() {
        Msg msg = new Msg("msg-5", "assistant", List.of(
                new ContentBlock.TextBlock("just text")
        ));

        assertFalse(msg.hasToolCalls());
        assertTrue(msg.getToolCalls().isEmpty());
    }

    @Test
    @DisplayName("Msg.addContentBlock 支持链式调用")
    void msgAddContentBlock() {
        Msg msg = new Msg("msg-6", "assistant", List.of());
        msg.addContentBlock(new ContentBlock.TextBlock("first"))
                .addContentBlock(new ContentBlock.TextBlock("second"));

        assertEquals(2, msg.getContent().size());
        assertEquals("first\nsecond", msg.getTextContent());
    }

    @Test
    @DisplayName("Msg.usage 的 TokenUsage 统计正确")
    void msgTokenUsage() {
        Msg.TokenUsage usage = new Msg.TokenUsage(150, 75);
        assertEquals(150, usage.getPromptTokens());
        assertEquals(75, usage.getCompletionTokens());
        assertEquals(225, usage.getTotalTokens());
    }

    @Test
    @DisplayName("Msg 内容列表为不可变视图")
    void msgContentIsUnmodifiable() {
        Msg msg = new Msg("msg-7", "assistant", List.of(new ContentBlock.TextBlock("hi")));
        assertThrows(UnsupportedOperationException.class, () -> msg.getContent().add(new ContentBlock.TextBlock("extra")));
    }

    @Test
    @DisplayName("Msg metadata 的 set 和 get")
    void msgMetadataOperations() {
        Msg msg = new Msg("msg-8", "assistant", List.of());
        msg.setMetadata("customKey", "customValue");
        assertEquals("customValue", msg.getMetadata("customKey"));
        assertNull(msg.getMetadata("nonExistent"));
    }
}
