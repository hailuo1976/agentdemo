package com.demo.agentscope.session;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionRecovery 单元测试。
 * <p>
 * 覆盖核心场景：round-trip 重建上下文、agentState 恢复、中断检测、损坏行容错。
 * </p>
 */
@DisplayName("SessionRecovery 单元测试")
class SessionRecoveryTest {

    @Test
    @DisplayName("round-trip：log 写入 → load 重建，消息列表应与原始一致")
    void roundTripReconstructsContext(@TempDir Path tmp) throws Exception {
        SessionLogger logger = new SessionLogger(tmp, "sess-rt");
        // 模拟一轮完整对话
        logger.logReplyStart("sess-rt", "你好");
        logger.logModelCallEnd(new Msg("r1", "assistant",
                List.of(new ContentBlock.TextBlock("你好，有什么可以帮你？")),
                new Msg.TokenUsage(50, 20), null, null));
        logger.logReplyEnd(Map.of("totalIterations", 1));
        logger.close();

        SessionRecovery recovery = new SessionRecovery(tmp);
        SessionRecovery.RecoveredSession recovered = recovery.load("sess-rt");

        assertNotNull(recovered);
        // REPLY_START → 1 user Msg；LLM_RESPONSE → 1 assistant Msg；REPLY_END 不建 Msg
        assertEquals(2, recovered.messages().size(),
                "应重建出 2 条消息（1 user + 1 assistant）");

        // 第一条：user
        Msg userMsg = recovered.messages().get(0);
        assertEquals("user", userMsg.getRole());
        String userText = extractText(userMsg);
        assertEquals("你好", userText);

        // 第二条：assistant
        Msg asstMsg = recovered.messages().get(1);
        assertEquals("assistant", asstMsg.getRole());
        String asstText = extractText(asstMsg);
        assertTrue(asstText.contains("你好"), "assistant 文本应被恢复: " + asstText);
        assertNotNull(asstMsg.getUsage(), "TokenUsage 应被恢复");
        assertEquals(50, asstMsg.getUsage().getPromptTokens());
        assertEquals(20, asstMsg.getUsage().getCompletionTokens());

        assertFalse(recovered.hasIncompleteTurn(), "完整 REPLY_END 结尾不应判定为中断");
    }

    @Test
    @DisplayName("load 应恢复最后一条 REPLY_END 中的 agentState")
    void recoversAgentState(@TempDir Path tmp) throws Exception {
        SessionLogger logger = new SessionLogger(tmp, "sess-state");
        logger.logReplyStart("sess-state", "test");
        logger.logReplyEnd(Map.of("totalIterations", 3, "lastReplyTime", "2026-07-12T10:00:00Z"));
        logger.close();

        SessionRecovery recovery = new SessionRecovery(tmp);
        SessionRecovery.RecoveredSession recovered = recovery.load("sess-state");

        assertNotNull(recovered.agentState(), "agentState 不应为 null");
        assertEquals(3, recovered.agentState().get("totalIterations"));
        assertEquals("2026-07-12T10:00:00Z", recovered.agentState().get("lastReplyTime"));
    }

    @Test
    @DisplayName("最后一条 entry 非 REPLY_END/ERROR 时应识别为中断")
    void detectsIncompleteTurn(@TempDir Path tmp) throws Exception {
        SessionLogger logger = new SessionLogger(tmp, "sess-incomplete");
        logger.logReplyStart("sess-incomplete", "继续工作");
        // 模拟中断：只写到 LLM_RESPONSE，没有 REPLY_END
        logger.logModelCallEnd(new Msg("r1", "assistant",
                List.of(new ContentBlock.TextBlock("正在处理...")),
                null, null, null));
        logger.close();

        SessionRecovery recovery = new SessionRecovery(tmp);
        SessionRecovery.RecoveredSession recovered = recovery.load("sess-incomplete");

        assertTrue(recovered.hasIncompleteTurn(),
                "最后一条为 LLM_RESPONSE 应判定为中断");
        assertNotNull(recovered.lastEntryUuid(), "lastEntryUuid 不应为 null");
    }

    @Test
    @DisplayName("损坏的 JSONL 行应被跳过（部分恢复优于全失败）")
    void handlesCorruptJsonLine(@TempDir Path tmp) throws Exception {
        // 手工构造一个包含损坏行的会话文件
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Path file = sessionsDir.resolve("sess-corrupt.jsonl");

        List<String> lines = List.of(
                // 合法的 REPLY_START
                "{\"uuid\":\"u1\",\"parentUuid\":null,\"type\":\"REPLY_START\","
                        + "\"timestamp\":\"2026-07-12T10:00:00Z\",\"sessionId\":\"sess-corrupt\","
                        + "\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}",
                // 损坏行（截断的 JSON）
                "{This is not valid JSON",
                // 合法的 LLM_RESPONSE
                "{\"uuid\":\"u3\",\"parentUuid\":\"u1\",\"type\":\"LLM_RESPONSE\","
                        + "\"timestamp\":\"2026-07-12T10:00:01Z\",\"sessionId\":\"sess-corrupt\","
                        + "\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"world\"}]}"
        );
        Files.write(file, lines);

        SessionRecovery recovery = new SessionRecovery(tmp);
        SessionRecovery.RecoveredSession recovered = recovery.load("sess-corrupt");

        // 损坏行被跳过，应重建出 2 条合法消息
        assertEquals(2, recovered.messages().size(),
                "损坏行应被跳过，合法行仍应恢复");
        assertEquals("hello", extractText(recovered.messages().get(0)));
        assertEquals("world", extractText(recovered.messages().get(1)));
    }

    /** 辅助：从 Msg 中提取首个 TextBlock 的文本 */
    private static String extractText(Msg msg) {
        if (msg.getContent() == null) return "";
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof ContentBlock.TextBlock t) {
                return t.getText();
            }
        }
        return "";
    }
}
