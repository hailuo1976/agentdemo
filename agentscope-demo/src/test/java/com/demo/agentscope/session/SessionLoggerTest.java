package com.demo.agentscope.session;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionLogger 单元测试。
 */
@DisplayName("SessionLogger 单元测试")
class SessionLoggerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("写入完整 reply 周期生成有效 JSONL 并维持 parentUuid 链")
    void writesValidJsonl(@TempDir Path tmp) throws Exception {
        Path sessionsRoot = tmp.resolve("workspace");
        SessionLogger logger = new SessionLogger(sessionsRoot, "sess-001");

        logger.logReplyStart("sess-001", "写个冒泡排序");
        Msg llmResp = new Msg("resp-1", "assistant", List.of(
                new ContentBlock.TextBlock("我来帮你"),
                new ContentBlock.ToolCallBlock("call_0", "write_file", "{\"path\":\"bub.py\"}")
        ), new Msg.TokenUsage(500, 120), null, null);
        logger.logModelCallEnd(llmResp);
        logger.logToolCall(new ContentBlock.ToolCallBlock("call_0", "write_file", "{\"path\":\"bub.py\"}"));
        logger.logToolResult(new ContentBlock.ToolResultBlock("call_0", "文件已写入", false));
        logger.logReplyEnd(Map.of("totalIterations", 1));
        logger.close();

        Path file = sessionsRoot.resolve("sessions").resolve("sess-001.jsonl");
        assertTrue(Files.exists(file), "JSONL 文件应存在");

        List<String> lines = Files.readAllLines(file);
        assertEquals(5, lines.size(), "应写入 5 条 entry");

        // 解析每行，验证字段
        List<SessionEntry> entries = new ArrayList<>();
        for (String line : lines) {
            entries.add(objectMapper.readValue(line, SessionEntry.class));
        }

        assertNull(entries.get(0).parentUuid(), "首条 parentUuid 应为 null");
        for (int i = 1; i < entries.size(); i++) {
            assertEquals(entries.get(i - 1).uuid(), entries.get(i).parentUuid(),
                    "第 " + i + " 条的 parentUuid 应等于前一条的 uuid");
        }

        assertEquals(SessionEntry.EntryType.REPLY_START, entries.get(0).type());
        assertEquals(SessionEntry.EntryType.LLM_RESPONSE, entries.get(1).type());
        assertEquals(SessionEntry.EntryType.TOOL_CALL, entries.get(2).type());
        assertEquals(SessionEntry.EntryType.TOOL_RESULT, entries.get(3).type());
        assertEquals(SessionEntry.EntryType.REPLY_END, entries.get(4).type());

        assertEquals("sess-001", entries.get(0).sessionId());
    }

    @Test
    @DisplayName("logModelCallEnd 捕获 TokenUsage")
    void logModelCallEndCapturesUsage(@TempDir Path tmp) throws Exception {
        SessionLogger logger = new SessionLogger(tmp, "sess-usage");
        Msg response = new Msg("r1", "assistant",
                List.of(new ContentBlock.TextBlock("hello")),
                new Msg.TokenUsage(800, 200), null, null);
        logger.logModelCallEnd(response);
        logger.close();

        Path file = tmp.resolve("sessions").resolve("sess-usage.jsonl");
        String line = Files.readString(file).trim();
        SessionEntry entry = objectMapper.readValue(line, SessionEntry.class);

        assertNotNull(entry.usage(), "usage 不能为 null");
        assertEquals(800, entry.usage().promptTokens());
        assertEquals(200, entry.usage().completionTokens());
    }

    @Test
    @DisplayName("IO 错误不向调用方抛异常（仅记日志）")
    void fileOpsErrorHandling(@TempDir Path tmp) {
        // 指向一个已存在的普通文件作为 baseDir，使 createDirectories 失败
        Path blocker = tmp.resolve("blocker");
        try {
            Files.createFile(blocker);
        } catch (Exception ignored) {}

        // 不应抛异常
        SessionLogger logger = new SessionLogger(blocker, "sess-bad");
        logger.logReplyStart("sess-bad", "hello");
        logger.close();
        // 唯一断言：到这里没抛异常就通过
    }

    @Test
    @DisplayName("多个工具调用按执行顺序线性链接 parentUuid")
    void multipleToolCallsChainCorrectly(@TempDir Path tmp) throws Exception {
        SessionLogger logger = new SessionLogger(tmp, "sess-multi");

        // LLM_RESPONSE 带 3 个 tool_call
        Msg response = new Msg("r1", "assistant", List.of(
                new ContentBlock.ToolCallBlock("c1", "t1", "{}"),
                new ContentBlock.ToolCallBlock("c2", "t2", "{}"),
                new ContentBlock.ToolCallBlock("c3", "t3", "{}")
        ), null, null, null);
        logger.logModelCallEnd(response);
        // 3 对 TOOL_CALL / TOOL_RESULT（按 Agent.reply() 的执行顺序）
        for (int i = 1; i <= 3; i++) {
            logger.logToolCall(new ContentBlock.ToolCallBlock("c" + i, "t" + i, "{}"));
            logger.logToolResult(new ContentBlock.ToolResultBlock("c" + i, "ok" + i, false));
        }
        logger.close();

        Path file = tmp.resolve("sessions").resolve("sess-multi.jsonl");
        List<SessionEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) {
                entries.add(objectMapper.readValue(line, SessionEntry.class));
            }
        }

        // 1 LLM_RESPONSE + 3 (TOOL_CALL + TOOL_RESULT) = 7
        assertEquals(7, entries.size());
        assertNull(entries.get(0).parentUuid());
        for (int i = 1; i < entries.size(); i++) {
            assertEquals(entries.get(i - 1).uuid(), entries.get(i).parentUuid(),
                    "第 " + i + " 条 parentUuid 链断");
        }
    }
}
