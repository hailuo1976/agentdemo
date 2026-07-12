package com.demo.agentscope.session;

import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.middleware.AgentContext;
import com.demo.agentscope.middleware.MiddlewareChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionLoggingMiddleware 单元测试。
 */
@DisplayName("SessionLoggingMiddleware 单元测试")
class SessionLoggingMiddlewareTest {

    private static final String AGENT_ID = "mw-agent";
    private static final String SESSION_ID = "session-mw";

    private AgentContext ctx;
    private MiddlewareChain chain;

    @BeforeEach
    void setUp() {
        ctx = new AgentContext(AGENT_ID, SESSION_ID, "user-1");
        chain = new MiddlewareChain();
    }

    @Test
    @DisplayName("6 个 hook 全部触发时，JSONL 文件应包含全部条目类型")
    void allHooksDelegatedToLogger(@TempDir Path tmp) throws Exception {
        SessionLogger logger = new SessionLogger(tmp, SESSION_ID);
        chain.add(new SessionLoggingMiddleware(logger));

        ctx.setAttribute("userInput", "你好");
        chain.fireReplyStart(ctx);

        Msg request = new Msg("req-1", "user",
                List.of(new ContentBlock.TextBlock("[模型调用请求]")));
        chain.fireModelCall(ctx, request);

        Msg response = new Msg("resp-1", "assistant",
                List.of(new ContentBlock.TextBlock("hi"),
                        new ContentBlock.ToolCallBlock("call_1", "search", "{\"q\":\"a\"}")),
                new Msg.TokenUsage(100, 30), null, null);
        chain.fireModelCallEnd(ctx, response);

        ContentBlock.ToolCallBlock tc = new ContentBlock.ToolCallBlock("call_1", "search", "{\"q\":\"a\"}");
        chain.fireToolCall(ctx, tc);

        ContentBlock.ToolResultBlock tr = new ContentBlock.ToolResultBlock("call_1", "result-text", false);
        chain.fireToolResult(ctx, tr);

        Map<String, Object> state = Map.of("totalIterations", 1);
        ctx.setAttribute("agentState", state);
        chain.fireReplyEnd(ctx, new EventStream(AGENT_ID));

        logger.close();

        Path file = tmp.resolve("sessions").resolve(SESSION_ID + ".jsonl");
        List<String> lines = Files.readAllLines(file);
        // REPLY_START + LLM_REQUEST + LLM_RESPONSE + TOOL_CALL + TOOL_RESULT + REPLY_END = 6
        assertEquals(6, lines.size(), "应记录 6 个 hook 事件");

        // 验证首条用户输入正确捕获
        String first = lines.get(0);
        assertTrue(first.contains("\"text\":\"你好\""),
                "REPLY_START 应记录 userInput 原文: " + first);

        // 验证 REPLY_END 中的 agentState
        String last = lines.get(lines.size() - 1);
        assertTrue(last.contains("totalIterations"),
                "REPLY_END 应包含 agentState 快照: " + last);
    }

    @Test
    @DisplayName("当 SessionLogger 指向无效路径时，中间件不向调用方抛异常")
    void middlewareDoesNotThrowOnLoggerError(@TempDir Path tmp) {
        // baseDir 指向一个普通文件，触发 IOException
        Path blocker = tmp.resolve("blocker-file");
        try {
            Files.createFile(blocker);
        } catch (Exception ignored) {}

        SessionLogger logger = new SessionLogger(blocker, "bad-session");
        SessionLoggingMiddleware middleware = new SessionLoggingMiddleware(logger);
        chain.add(middleware);

        ctx.setAttribute("userInput", "hello");

        // 所有 hook 都不应抛
        assertDoesNotThrow(() -> chain.fireReplyStart(ctx));
        assertDoesNotThrow(() -> chain.fireModelCall(ctx,
                new Msg("r", "user", List.of(new ContentBlock.TextBlock("x")))));
        assertDoesNotThrow(() -> chain.fireModelCallEnd(ctx,
                new Msg("r", "assistant", List.of(new ContentBlock.TextBlock("y")))));
        assertDoesNotThrow(() -> chain.fireToolCall(ctx,
                new ContentBlock.ToolCallBlock("c1", "t", "{}")));
        assertDoesNotThrow(() -> chain.fireToolResult(ctx,
                new ContentBlock.ToolResultBlock("c1", "out", false)));
        assertDoesNotThrow(() -> chain.fireReplyEnd(ctx, new EventStream(AGENT_ID)));

        logger.close();
    }
}
