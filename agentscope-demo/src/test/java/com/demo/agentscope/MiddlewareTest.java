package com.demo.agentscope;

import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.middleware.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中间件系统测试。
 */
@DisplayName("中间件系统测试")
class MiddlewareTest {

    private static final String AGENT_ID = "mw-agent";
    private static final String SESSION_ID = "session-1";
    private static final String USER_ID = "user-1";

    private AgentContext ctx;
    private MiddlewareChain chain;
    private EventStream eventStream;

    @BeforeEach
    void setUp() {
        ctx = new AgentContext(AGENT_ID, SESSION_ID, USER_ID);
        chain = new MiddlewareChain();
        eventStream = new EventStream(AGENT_ID);
    }

    // ==================== MiddlewareChain 测试 ====================

    @Test
    @DisplayName("MiddlewareChain fireReplyStart 按顺序执行所有中间件")
    void fireReplyStartExecutesAllMiddlewares() {
        List<String> order = new ArrayList<>();

        chain.add(new Middleware() {
            @Override
            public void onReplyStart(AgentContext ctx) {
                order.add("first");
            }
        });
        chain.add(new Middleware() {
            @Override
            public void onReplyStart(AgentContext ctx) {
                order.add("second");
            }
        });
        chain.add(new Middleware() {
            @Override
            public void onReplyStart(AgentContext ctx) {
                order.add("third");
            }
        });

        chain.fireReplyStart(ctx);

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    @DisplayName("MiddlewareChain fireModelCall/fireModelCallEnd 正常执行")
    void fireModelCallMethods() {
        List<String> calls = new ArrayList<>();
        Msg request = new Msg("req-1", "user", List.of(new ContentBlock.TextBlock("hi")));
        Msg response = new Msg("resp-1", "assistant", List.of(new ContentBlock.TextBlock("hello")));

        chain.add(new Middleware() {
            @Override
            public void onModelCall(AgentContext ctx, Msg req) {
                calls.add("onModelCall:" + req.getRole());
            }

            @Override
            public void onModelCallEnd(AgentContext ctx, Msg resp) {
                calls.add("onModelCallEnd:" + resp.getRole());
            }
        });

        chain.fireModelCall(ctx, request);
        chain.fireModelCallEnd(ctx, response);

        assertEquals(2, calls.size());
        assertEquals("onModelCall:user", calls.get(0));
        assertEquals("onModelCallEnd:assistant", calls.get(1));
    }

    @Test
    @DisplayName("MiddlewareChain fireToolCall/fireToolResult 正常执行")
    void fireToolMethods() {
        List<String> calls = new ArrayList<>();
        ContentBlock.ToolCallBlock toolCall = new ContentBlock.ToolCallBlock("call_0", "get_weather", "{}");
        ContentBlock.ToolResultBlock toolResult = new ContentBlock.ToolResultBlock("call_0", "sunny", false);

        chain.add(new Middleware() {
            @Override
            public void onToolCall(AgentContext ctx, ContentBlock.ToolCallBlock tc) {
                calls.add("onToolCall:" + tc.getName());
            }

            @Override
            public void onToolResult(AgentContext ctx, ContentBlock.ToolResultBlock tr) {
                calls.add("onToolResult:" + tr.getToolCallId());
            }
        });

        chain.fireToolCall(ctx, toolCall);
        chain.fireToolResult(ctx, toolResult);

        assertEquals(2, calls.size());
        assertEquals("onToolCall:get_weather", calls.get(0));
        assertEquals("onToolResult:call_0", calls.get(1));
    }

    @Test
    @DisplayName("MiddlewareChain fireReplyEnd 正常执行")
    void fireReplyEnd() {
        List<String> calls = new ArrayList<>();

        chain.add(new Middleware() {
            @Override
            public void onReplyEnd(AgentContext ctx, EventStream stream) {
                calls.add("onReplyEnd:" + stream.size());
            }
        });

        eventStream.emit(com.demo.agentscope.event.AgentEvent.textBlock(AGENT_ID, "test"));
        chain.fireReplyEnd(ctx, eventStream);

        assertEquals(1, calls.size());
        assertEquals("onReplyEnd:1", calls.get(0));
    }

    @Test
    @DisplayName("MiddlewareChain 中某个中间件异常不影响后续中间件执行")
    void chainContinuesAfterMiddlewareException() {
        List<String> calls = new ArrayList<>();

        chain.add(new Middleware() {
            @Override
            public void onReplyStart(AgentContext ctx) {
                calls.add("before-exception");
                throw new RuntimeException("test exception");
            }
        });
        chain.add(new Middleware() {
            @Override
            public void onReplyStart(AgentContext ctx) {
                calls.add("after-exception");
            }
        });

        chain.fireReplyStart(ctx);

        assertEquals(2, calls.size());
        assertTrue(calls.contains("before-exception"));
        assertTrue(calls.contains("after-exception"));
    }

    @Test
    @DisplayName("MiddlewareChain add 和 size 正常工作")
    void chainAddAndSize() {
        assertEquals(0, chain.size());
        chain.add(new TracingMiddleware());
        assertEquals(1, chain.size());
    }

    @Test
    @DisplayName("MiddlewareChain remove 移除中间件")
    void chainRemove() {
        Middleware mw = new TracingMiddleware();
        chain.add(mw);
        assertEquals(1, chain.size());
        chain.remove(mw);
        assertEquals(0, chain.size());
    }

    // ==================== TracingMiddleware 测试 ====================

    @Test
    @DisplayName("TracingMiddleware 追踪模型调用次数")
    void tracingMiddlewareModelCallCount() {
        TracingMiddleware tracing = new TracingMiddleware();
        chain.add(tracing);

        chain.fireReplyStart(ctx);

        Msg response = new Msg("resp-1", "assistant", List.of(new ContentBlock.TextBlock("hi")),
                new Msg.TokenUsage(100, 50), null, null);
        chain.fireModelCallEnd(ctx, response);
        chain.fireModelCallEnd(ctx, response);

        Map<String, Object> stats = tracing.getStats();
        assertEquals(2, stats.get("modelCallCount"));
    }

    @Test
    @DisplayName("TracingMiddleware 追踪工具调用次数")
    void tracingMiddlewareToolCallCount() {
        TracingMiddleware tracing = new TracingMiddleware();
        chain.add(tracing);

        chain.fireReplyStart(ctx);

        ContentBlock.ToolCallBlock toolCall1 = new ContentBlock.ToolCallBlock("c1", "tool_a", "{}");
        ContentBlock.ToolCallBlock toolCall2 = new ContentBlock.ToolCallBlock("c2", "tool_b", "{}");
        chain.fireToolCall(ctx, toolCall1);
        chain.fireToolCall(ctx, toolCall2);

        Map<String, Object> stats = tracing.getStats();
        assertEquals(2, stats.get("toolCallCount"));
    }

    @Test
    @DisplayName("TracingMiddleware 累计 token 用量")
    void tracingMiddlewareTotalTokens() {
        TracingMiddleware tracing = new TracingMiddleware();
        chain.add(tracing);

        chain.fireReplyStart(ctx);

        Msg resp1 = new Msg("r1", "assistant", List.of(), new Msg.TokenUsage(100, 50), null, null);
        Msg resp2 = new Msg("r2", "assistant", List.of(), new Msg.TokenUsage(200, 80), null, null);
        chain.fireModelCallEnd(ctx, resp1);
        chain.fireModelCallEnd(ctx, resp2);

        Map<String, Object> stats = tracing.getStats();
        assertEquals(430, stats.get("totalTokens")); // (100+50) + (200+80)
    }

    // ==================== ContextCompressionMiddleware 测试 ====================

    @Test
    @DisplayName("ContextCompressionMiddleware 未超阈值不压缩")
    void noCompressionBelowThreshold() {
        ContextCompressionMiddleware compression = new ContextCompressionMiddleware(50, 10);
        chain.add(compression);

        // 放入少量消息，不超过阈值
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(new Msg("m" + i, "user", List.of(new ContentBlock.TextBlock("msg " + i))));
        }
        ctx.setAttribute("contextMessages", messages);

        chain.fireReplyEnd(ctx, eventStream);

        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) ctx.getAttribute("contextMessages");
        assertEquals(10, result.size());
    }

    @Test
    @DisplayName("ContextCompressionMiddleware 超过阈值执行压缩")
    void compressionWhenThresholdExceeded() {
        ContextCompressionMiddleware compression = new ContextCompressionMiddleware(20, 5);
        chain.add(compression);

        List<Msg> messages = new ArrayList<>();
        // 1 条 system + 30 条非 system
        messages.add(new Msg("sys", "system", List.of(new ContentBlock.TextBlock("system prompt"))));
        for (int i = 0; i < 30; i++) {
            messages.add(new Msg("m" + i, "user", List.of(new ContentBlock.TextBlock("msg " + i))));
        }
        ctx.setAttribute("contextMessages", messages);

        chain.fireReplyEnd(ctx, eventStream);

        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) ctx.getAttribute("contextMessages");
        // 结果应为: 1 system + 1 压缩摘要 + 5 recent = 7
        assertTrue(result.size() < messages.size());
        assertEquals("system", result.get(0).getRole());
    }

    @Test
    @DisplayName("ContextCompressionMiddleware 上下文中无消息列表不报错")
    void noCompressionWithoutMessagesList() {
        ContextCompressionMiddleware compression = new ContextCompressionMiddleware(5, 2);
        chain.add(compression);

        // 不设置 contextMessages 属性
        assertDoesNotThrow(() -> chain.fireReplyEnd(ctx, eventStream));
    }

    // ==================== ReplyBudgetControlMiddleware 测试 ====================

    @Test
    @DisplayName("ReplyBudgetControlMiddleware 未超预算时正常通过")
    void budgetNotExceeded() {
        ReplyBudgetControlMiddleware budget = new ReplyBudgetControlMiddleware(10000);
        chain.add(budget);

        chain.fireReplyStart(ctx);

        Msg response = new Msg("r1", "assistant", List.of(),
                new Msg.TokenUsage(100, 50), null, null);
        assertDoesNotThrow(() -> chain.fireModelCallEnd(ctx, response));

        assertEquals(150, budget.getUsedTokens());
    }

    @Test
    @DisplayName("ReplyBudgetControlMiddleware 超过预算时降级为警告（不抛异常）")
    void budgetExceededDowngradedToWarning() {
        ReplyBudgetControlMiddleware budget = new ReplyBudgetControlMiddleware(100);
        chain.add(budget);

        chain.fireReplyStart(ctx);

        // 一次模型调用就超过预算
        Msg response = new Msg("r1", "assistant", List.of(),
                new Msg.TokenUsage(200, 100), null, null);

        // 不再抛异常，Agent 可继续 ReAct 循环（由 maxIterations 兜底）
        assertDoesNotThrow(() -> chain.fireModelCallEnd(ctx, response));
        assertEquals(300, budget.getUsedTokens());
    }

    @Test
    @DisplayName("BudgetExceededException 携带预算和实际值")
    void budgetExceptionContainsValues() {
        BudgetExceededException ex = new BudgetExceededException(100, 250);
        assertEquals(100, ex.getBudget());
        assertEquals(250, ex.getActual());
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("250"));
    }

    // ==================== AgentContext 测试 ====================

    @Test
    @DisplayName("AgentContext 属性设置和获取")
    void agentContextAttributes() {
        ctx.setAttribute("key1", "value1");
        ctx.setAttribute("key2", 42);

        assertEquals("value1", ctx.getAttribute("key1"));
        assertEquals(42, ctx.getAttribute("key2"));
        assertNull(ctx.getAttribute("nonExistent"));
    }

    @Test
    @DisplayName("AgentContext getAttribute 带默认值")
    void agentContextAttributeWithDefault() {
        ctx.setAttribute("existing", "yes");
        assertEquals("yes", ctx.getAttribute("existing", "default"));
        assertEquals("default", ctx.getAttribute("missing", "default"));
    }

    @Test
    @DisplayName("AgentContext 基本属性正确")
    void agentContextBasicProperties() {
        assertEquals(AGENT_ID, ctx.getAgentId());
        assertEquals(SESSION_ID, ctx.getSessionId());
        assertEquals(USER_ID, ctx.getUserId());
    }

    @Test
    @DisplayName("AgentContext replyStartTime 设置和获取")
    void agentContextReplyStartTime() {
        assertEquals(0, ctx.getReplyStartTime());
        ctx.setReplyStartTime(12345L);
        assertEquals(12345L, ctx.getReplyStartTime());
    }

    @Test
    @DisplayName("AgentContext setAttribute 不允许 null key")
    void agentContextAttributeNullKey() {
        assertThrows(NullPointerException.class, () -> ctx.setAttribute(null, "value"));
    }
}
