package com.demo.agentscope.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Msg.getEstimatedTokens() 单元测试。
 * <p>
 * 验证 token 估算正确计入所有 ContentBlock 类型:
 * TextBlock / ToolCallBlock.arguments / ToolResultBlock.content。
 * 这是阶段 1 的核心修复点,过去只数 TextBlock 严重低估工具密集对话。
 * </p>
 */
public class MsgEstimatedTokensTest {

    @Test
    void textBlockOnly_countsCharsCorrectly() {
        // 300 字符 = 100 token
        String text = "a".repeat(300);
        Msg msg = new Msg("id", "user",
                List.of(new ContentBlock.TextBlock(text)));

        assertEquals(100, msg.getEstimatedTokens(),
                "300 字符应估算为 100 token (chars/3)");
    }

    @Test
    void toolCallBlock_countsNameAndArguments() {
        // name="execute_python"(14) + arguments=300 = 314 字符 -> 104 token
        String args = "x".repeat(300);
        Msg msg = new Msg("id", "assistant",
                List.of(new ContentBlock.ToolCallBlock("tool_use_1", "execute_python", args)));

        assertEquals(104, msg.getEstimatedTokens(),
                "ToolCallBlock 应计入 name + arguments 字符");
    }

    @Test
    void toolResultBlock_countsContent() {
        // 600 字符 = 200 token
        String content = "y".repeat(600);
        Msg msg = new Msg("id", "tool",
                List.of(new ContentBlock.ToolResultBlock("tool_call_1", content, false)));

        assertEquals(200, msg.getEstimatedTokens(),
                "ToolResultBlock 应计入 content 字符");
    }

    @Test
    void mixedBlocks_aggregatesAllTypes() {
        // TextBlock 150 + ToolCall name 14 + args 300 + ToolResult 600 = 1064 字符 -> 354 token
        String text = "a".repeat(150);
        String args = "b".repeat(300);
        String result = "c".repeat(600);

        Msg userMsg = new Msg("u", "user", List.of(new ContentBlock.TextBlock(text)));
        Msg assistantMsg = new Msg("a", "assistant",
                List.of(new ContentBlock.ToolCallBlock("id1", "execute_python", args)));
        Msg toolMsg = new Msg("t", "tool",
                List.of(new ContentBlock.ToolResultBlock("id1", result, false)));

        int total = userMsg.getEstimatedTokens()
                + assistantMsg.getEstimatedTokens()
                + toolMsg.getEstimatedTokens();

        assertEquals(354, total, "三类 block 都应被计入 token 估算");
    }

    @Test
    void toolCallWithBigArgs_muchHigherThanTextOnlyEstimate() {
        // 模拟真实场景:8900 字符的 Python 脚本
        String bigArgs = "import pandas as pd\n" + "x".repeat(8900);
        Msg assistantMsg = new Msg("a", "assistant",
                List.of(new ContentBlock.ToolCallBlock("id1", "execute_python", bigArgs)));

        int tokens = assistantMsg.getEstimatedTokens();

        assertTrue(tokens > 2900,
                "8900+ 字符的 arguments 应被计入,估算值应 > 2900 token;实际=" + tokens);
    }

    @Test
    void emptyContent_returnsMinOne() {
        Msg msg = new Msg("id", "user", List.of());
        assertEquals(1, msg.getEstimatedTokens(),
                "空消息至少占 1 token");
    }

    @Test
    void nullArgumentsInToolCall_handledSafely() {
        Msg msg = new Msg("a", "assistant",
                List.of(new ContentBlock.ToolCallBlock("id1", "tool", null)));
        // 不应抛 NPE,name 长度 4 / 3 = 1
        assertEquals(1, msg.getEstimatedTokens());
    }

    @Test
    void nullContentInToolResult_handledSafely() {
        Msg msg = new Msg("t", "tool",
                List.of(new ContentBlock.ToolResultBlock("id1", null, false)));
        assertEquals(1, msg.getEstimatedTokens(),
                "null content 不应导致 NPE");
    }
}
