package com.demo.agentscope.context;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.memory.ShortTermMemory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 压缩逻辑的回归测试。
 * <p>
 * 焦点：tool_call ↔ tool_result 双向配对完整性。OpenAI/GLM 协议要求：
 * <ul>
 *   <li>每个 assistant.tool_calls[*].id 必须有对应的 tool 结果消息</li>
 *   <li>每个 tool 消息必须引用窗口内某个 assistant 的 tool_call.id</li>
 * </ul>
 * 这一组测试覆盖之前导致 HTTP 400 "messages 参数非法" 的两类压缩切断：
 * 孤儿 tool（旧 bug）和不完整 multi-tool assistant（新发现）。
 * </p>
 */
public class ContextManagerCompressionTest {

    private static Msg user(String text) {
        return new Msg(UUID.randomUUID().toString(), "user",
                List.of(new ContentBlock.TextBlock(text)));
    }

    /** 单条约 800 token 估算的填充消息，确保少量消息也能撑过 MAX_CONTEXT_TOKENS(4000)。 */
    private static Msg fatUser(String label) {
        String pad = "x".repeat(3200);  // ~800 tokens
        return new Msg(UUID.randomUUID().toString(), "user",
                List.of(new ContentBlock.TextBlock(label + ":" + pad)));
    }

    private static Msg assistantWithTools(String text, String... toolCallIds) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            blocks.add(new ContentBlock.TextBlock(text));
        }
        for (int i = 0; i < toolCallIds.length; i += 2) {
            blocks.add(new ContentBlock.ToolCallBlock(
                    toolCallIds[i], "execute_python", toolCallIds[i + 1]));
        }
        return new Msg(UUID.randomUUID().toString(), "assistant", blocks);
    }

    private static Msg toolResult(String toolCallId, String content) {
        return new Msg(UUID.randomUUID().toString(), "tool",
                List.of(new ContentBlock.ToolResultBlock(toolCallId, content, false)));
    }

    private static ContextManager newManager() {
        // MAX_CONTEXT_TOKENS=4000 / MAX_RECENT_MESSAGES=10 走默认值
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("stm-test");
            ShortTermMemory stm = new ShortTermMemory(tmp, 100, java.time.Duration.ofHours(1));
            return new ContextManager(stm, "system-prompt");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 当窗口起点切断了一个 multi-tool assistant 与其部分 tool 结果时，
     * 压缩必须把这个 assistant 整条丢弃（不能保留半截 tool_calls），
     * 同时丢弃窗口内残留的、对应已丢弃 assistant 的 tool 消息。
     */
    @Test
    void dropsAssistantWhenSomeToolResultsCutOff() {
        // 构造历史：assistant 带 2 个 tool_call，但只有 tool_call_A 的结果在历史里，
        // tool_call_B 的结果缺失。窗口内会留下"半个 assistant"，
        // 压缩必须把这条 assistant 整条丢弃，同时丢弃残留的 tool 消息。
        List<Msg> history = new ArrayList<>();
        history.add(assistantWithTools("我先查天气再算数",
                "call_A", "{\"city\":\"beijing\"}",
                "call_B", "{\"expr\":\"1+1\"}"));
        history.add(toolResult("call_A", "晴天"));
        // call_B 没有结果（模拟被切断或丢失）
        // 后续追加 8 条大消息，迫使 buildContext 触发压缩（>4000 tokens）
        for (int i = 0; i < 8; i++) {
            history.add(fatUser("filler-" + i));
        }

        List<Msg> built = newManager().buildContext("current", history);

        // 不应出现 assistant 带 call_A 或 call_B 的消息（被整条丢弃）
        for (Msg m : built) {
            if ("assistant".equals(m.getRole())) {
                for (ContentBlock.ToolCallBlock tc : m.getToolCalls()) {
                    assertNotEquals("call_A", tc.getId());
                    assertNotEquals("call_B", tc.getId());
                }
            }
            // 也不应出现孤儿 tool 消息
            if ("tool".equals(m.getRole())) {
                for (ContentBlock block : m.getContent()) {
                    if (block instanceof ContentBlock.ToolResultBlock tr) {
                        assertNotEquals("call_A", tr.getToolCallId());
                        assertNotEquals("call_B", tr.getToolCallId());
                    }
                }
            }
        }
    }

    /**
     * 完整的 multi-tool assistant（所有 tool_calls 都有窗口内 tool 结果）必须保留。
     */
    @Test
    void keepsCompleteMultiToolAssistant() {
        List<Msg> history = new ArrayList<>();
        // 先填 9 条大消息占满窗口
        for (int i = 0; i < 9; i++) {
            history.add(fatUser("filler-" + i));
        }
        // 第 10、11、12、13 条：assistant + 两个 tool 结果（都在最后 10 条窗口内）
        history.add(assistantWithTools("两件事一起办",
                "call_X", "{\"q\":\"a\"}",
                "call_Y", "{\"q\":\"b\"}"));
        history.add(toolResult("call_X", "X结果"));
        history.add(toolResult("call_Y", "Y结果"));
        // 再加一条触发 >4000 token
        history.add(fatUser("tail"));

        List<Msg> built = newManager().buildContext("current", history);

        boolean foundCompleteAssistant = built.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .flatMap(m -> m.getToolCalls().stream())
                .anyMatch(tc -> "call_X".equals(tc.getId()));
        assertTrue(foundCompleteAssistant, "完整的 multi-tool assistant 必须保留");

        long keptResults = built.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .flatMap(m -> m.getContent().stream())
                .filter(b -> b instanceof ContentBlock.ToolResultBlock)
                .map(b -> ((ContentBlock.ToolResultBlock) b).getToolCallId())
                .filter(id -> "call_X".equals(id) || "call_Y".equals(id))
                .count();
        assertEquals(2, keptResults, "两个 tool 结果必须都保留");
    }

    /**
     * 当窗口起点恰好在 multi-tool assistant 之后但部分 tool 结果之前，
     * assistant 被保留（有完整 tool_calls），但只应保留与该 assistant 匹配的 tool 结果。
     */
    @Test
    void keepsAssistantAndAllPairedResultsWhenAllInWindow() {
        List<Msg> history = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            history.add(fatUser("filler-" + i));
        }
        history.add(assistantWithTools("三件事",
                "c1", "{}", "c2", "{}", "c3", "{}"));
        history.add(toolResult("c1", "r1"));
        history.add(toolResult("c2", "r2"));
        history.add(toolResult("c3", "r3"));

        List<Msg> built = newManager().buildContext("current", history);

        Msg kept = built.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .findFirst()
                .orElse(null);
        assertNotNull(kept);
        assertEquals(3, kept.getToolCalls().size());

        long keptResults = built.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .flatMap(m -> m.getContent().stream())
                .filter(b -> b instanceof ContentBlock.ToolResultBlock)
                .map(b -> ((ContentBlock.ToolResultBlock) b).getToolCallId())
                .filter(id -> id.equals("c1") || id.equals("c2") || id.equals("c3"))
                .count();
        assertEquals(3, keptResults);
    }

    /**
     * 当 user 消息被工具调用序列挤出最近 N 条窗口时，压缩必须把它钉回，
     * 否则 messages 序列变成 system→assistant→tool→... 没有 user 角色，
     * GLM/OpenAI 协议会拒绝（HTTP 400 code=1214）。
     */
    @Test
    void pinsLatestUserMessageWhenOutOfWindow() {
        List<Msg> history = new ArrayList<>();
        // 第 0 条：user（即将被挤出窗口）
        history.add(user("请帮我连续完成 5 个工具任务"));
        // 接下来 12 条 assistant+tool 对话（超过 MAX_RECENT_MESSAGES=10，user 会被挤出窗口）
        for (int i = 0; i < 12; i++) {
            String callId = "pin_call_" + i;
            history.add(assistantWithTools("第" + i + "步",
                    callId, "{\"step\":" + i + "}"));
            history.add(toolResult(callId, "完成" + i));
        }
        // 加几条大消息确保触发压缩
        for (int i = 0; i < 3; i++) {
            history.add(fatUser("tail-" + i));
        }

        List<Msg> built = newManager().buildContext("current", history);

        boolean hasUser = built.stream().anyMatch(m -> "user".equals(m.getRole()));
        assertTrue(hasUser, "user 消息必须被钉住保留（避免序列非法）");

        // 同时仍要保证所有 assistant/tool 配对完整
        java.util.Set<String> assistantCallIds = built.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .flatMap(m -> m.getToolCalls().stream())
                .map(ContentBlock.ToolCallBlock::getId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> toolResultIds = built.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .flatMap(m -> m.getContent().stream())
                .filter(b -> b instanceof ContentBlock.ToolResultBlock)
                .map(b -> ((ContentBlock.ToolResultBlock) b).getToolCallId())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(assistantCallIds, toolResultIds, "assistant.tool_calls 与 tool 结果必须双向配对");
    }

    /**
     * 压缩丢弃消息后，必须注入一条 user 角色的告知消息，让模型知道上文已被裁剪。
     * 否则模型对早期信息缺失一无所知，会产生信息偏差（参考 Claude Code
     * getCompactUserSummaryMessage 的设计意图）。
     */
    @Test
    void injectsCompactNoticeAfterCompression() {
        List<Msg> history = new ArrayList<>();
        // 9 条大消息 + 2 条尾巴，超过 MAX_RECENT_MESSAGES(10) 且超 4000 token
        for (int i = 0; i < 9; i++) {
            history.add(fatUser("filler-" + i));
        }
        history.add(user("早期用户消息"));
        history.add(fatUser("tail"));

        List<Msg> built = newManager().buildContext("current", history);

        // 至少存在一条 user 消息包含压缩告知关键字
        boolean hasNotice = built.stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(Msg::getTextContent)
                .anyMatch(t -> t.contains("上下文窗口限制") && t.contains("压缩丢弃"));
        assertTrue(hasNotice, "压缩后必须注入告知模型上下文已被裁剪的 user 消息");
    }
}
