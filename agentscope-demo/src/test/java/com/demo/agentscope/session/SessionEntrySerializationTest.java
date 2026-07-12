package com.demo.agentscope.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionEntry 序列化 / BlockDto 转换测试。
 */
@DisplayName("SessionEntry 序列化与 BlockDto 转换")
class SessionEntrySerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("SessionEntry JSON 序列化 → 反序列化 round-trip 无损")
    void serializeDeserializeRoundTrip() throws Exception {
        SessionEntry original = new SessionEntry(
                "uuid-1",
                null,
                SessionEntry.EntryType.REPLY_START,
                "2026-07-12T10:00:00.123Z",
                "sess-rt",
                "user",
                List.of(new SessionEntry.BlockDto(
                        "text", "hello world",
                        null, null, null, null, null, null, null)),
                null,
                Map.of("k", "v"),
                null);

        String json = objectMapper.writeValueAsString(original);
        SessionEntry restored = objectMapper.readValue(json, SessionEntry.class);

        assertEquals(original.uuid(), restored.uuid());
        assertEquals(original.parentUuid(), restored.parentUuid());
        assertEquals(original.type(), restored.type());
        assertEquals(original.timestamp(), restored.timestamp());
        assertEquals(original.sessionId(), restored.sessionId());
        assertEquals(original.role(), restored.role());
        assertEquals(original.agentState().get("k"), restored.agentState().get("k"));
        assertEquals(1, restored.content().size());
        assertEquals("text", restored.content().get(0).type());
        assertEquals("hello world", restored.content().get(0).text());
    }

    @Test
    @DisplayName("LLM_RESPONSE entry 应能完整承载 6 种 BlockDto 类型")
    void llmResponseCarriesAllBlockTypes() throws Exception {
        // 构造一条包含多种 block 的 LLM_RESPONSE
        List<SessionEntry.BlockDto> blocks = List.of(
                new SessionEntry.BlockDto("text", "thinking...",
                        null, null, null, null, null, null, null),
                new SessionEntry.BlockDto("tool_call", null,
                        "call_1", "search", "{\"q\":\"x\"}",
                        null, null, null, null),
                new SessionEntry.BlockDto("tool_result", null,
                        "call_1", null, null,
                        "result text", false, null, null),
                new SessionEntry.BlockDto("thinking", "internal reasoning",
                        null, null, null, null, null, null, null),
                new SessionEntry.BlockDto("hint", "system hint",
                        null, null, null, null, null, null, null),
                new SessionEntry.BlockDto("data", null,
                        null, null, null, null, null,
                        "image/png", "aGVsbG8="));  // "hello" in base64

        SessionEntry entry = new SessionEntry(
                "uuid-2", "uuid-1",
                SessionEntry.EntryType.LLM_RESPONSE,
                "2026-07-12T10:00:01Z",
                "sess-blocks",
                "assistant",
                blocks,
                new SessionEntry.TokenUsageDto(100, 50),
                null, null);

        String json = objectMapper.writeValueAsString(entry);
        SessionEntry restored = objectMapper.readValue(json, SessionEntry.class);

        assertEquals(6, restored.content().size(),
                "全部 6 种 BlockDto 应能 round-trip");

        // 校验关键字段
        assertEquals("text", restored.content().get(0).type());
        assertEquals("thinking...", restored.content().get(0).text());

        assertEquals("tool_call", restored.content().get(1).type());
        assertEquals("call_1", restored.content().get(1).toolCallId());
        assertEquals("search", restored.content().get(1).toolName());
        assertEquals("{\"q\":\"x\"}", restored.content().get(1).arguments());

        assertEquals("tool_result", restored.content().get(2).type());
        assertEquals("result text", restored.content().get(2).content());
        assertEquals(false, restored.content().get(2).isError());

        assertEquals("thinking", restored.content().get(3).type());
        assertEquals("internal reasoning", restored.content().get(3).text());

        assertEquals("hint", restored.content().get(4).type());
        assertEquals("system hint", restored.content().get(4).text());

        assertEquals("data", restored.content().get(5).type());
        assertEquals("image/png", restored.content().get(5).mimeType());
        assertEquals("aGVsbG8=", restored.content().get(5).dataBase64());

        // usage 也应完整恢复
        assertNotNull(restored.usage());
        assertEquals(100, restored.usage().promptTokens());
        assertEquals(50, restored.usage().completionTokens());
    }
}
