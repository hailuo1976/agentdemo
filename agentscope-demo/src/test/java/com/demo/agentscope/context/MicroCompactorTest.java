package com.demo.agentscope.context;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MicroCompactor 单元测试。
 * <p>
 * 验证 mid-reply 工具调用压缩器的核心契约:
 * <ul>
 *   <li>触发阈值后才生效,保留最近 N 条原值</li>
 *   <li>按 tool_use.id 替换 arguments/result 为 stub,id 链不被破坏</li>
 *   <li>幂等:二次调用不再压缩</li>
 *   <li>非白名单工具不被压缩</li>
 * </ul>
 * </p>
 */
public class MicroCompactorTest {

    private static final String COMPACTABLE_TOOL = "execute_python";
    private static final String NON_COMPACTABLE_TOOL = "unknown_tool";

    @Test
    void belowTriggerCount_doesNothing() {
        List<Msg> context = buildContextWithToolCalls(COMPACTABLE_TOOL, 5);

        int compacted = MicroCompactor.compactIfNeeded(context);

        assertEquals(0, compacted, "未达 TRIGGER_TOOL_COUNT=12 不应压缩");
        assertAllToolCallsUntouched(context);
    }

    @Test
    void atTriggerCount_compactsAllButRecent() {
        // 刚好 12 个,保留最近 5 个,应压缩 7 个
        List<Msg> context = buildContextWithToolCalls(COMPACTABLE_TOOL, 12);

        int compacted = MicroCompactor.compactIfNeeded(context);

        assertEquals(7, compacted, "应压缩 7 个早期工具调用");

        // 验证最近 5 个的 arguments 与 result 未被替换
        List<ToolCallSite> sites = collectToolCallSites(context);
        List<ToolCallSite> last5 = sites.subList(sites.size() - 5, sites.size());
        for (ToolCallSite site : last5) {
            assertFalse(isStubbedCall(site.assistantCall),
                    "最近 5 个 tool_call.arguments 应保留原值");
        }
    }

    @Test
    void preservesIdChain_afterCompaction() {
        List<Msg> context = buildContextWithToolCalls(COMPACTABLE_TOOL, 15);
        List<ToolCallSite> allSites = collectToolCallSites(context);

        // 记录所有原始 id
        List<String> originalIds = allSites.stream()
                .map(s -> s.assistantCall.getId()).toList();

        MicroCompactor.compactIfNeeded(context);

        // id 链应保持不变(assistant.tool_call.id + tool.tool_call_id)
        List<ToolCallSite> afterSites = collectToolCallSites(context);
        assertEquals(originalIds.size(), afterSites.size());
        for (int i = 0; i < originalIds.size(); i++) {
            assertEquals(originalIds.get(i), afterSites.get(i).assistantCall.getId(),
                    "tool_use.id 不应被改变");
            assertEquals(originalIds.get(i), afterSites.get(i).toolResult.getToolCallId(),
                    "tool_call_id 关联不应被破坏");
        }
    }

    @Test
    void idempotent_secondCallIsNoop() {
        List<Msg> context = buildContextWithToolCalls(COMPACTABLE_TOOL, 15);
        int firstCompacted = MicroCompactor.compactIfNeeded(context);

        // 二次调用:已是 stub 的不应再处理
        int secondCompacted = MicroCompactor.compactIfNeeded(context);

        assertTrue(firstCompacted > 0, "首次调用应压缩");
        assertEquals(0, secondCompacted, "二次调用应为 0(幂等)");
    }

    @Test
    void nonWhitelistTool_notCompacted() {
        List<Msg> context = buildContextWithToolCalls(NON_COMPACTABLE_TOOL, 20);

        int compacted = MicroCompactor.compactIfNeeded(context);

        assertEquals(0, compacted, "非白名单工具不应压缩");
        assertAllToolCallsUntouched(context);
    }

    @Test
    void emptyOrNullContext_safe() {
        assertEquals(0, MicroCompactor.compactIfNeeded(null));
        assertEquals(0, MicroCompactor.compactIfNeeded(List.of()));
        assertEquals(0, MicroCompactor.compactIfNeeded(new ArrayList<>()));
    }

    // ==================== 辅助方法 ====================

    private static List<Msg> buildContextWithToolCalls(String toolName, int count) {
        List<Msg> ctx = new ArrayList<>();
        ctx.add(userMsg("开始"));

        for (int i = 0; i < count; i++) {
            String id = "tool_use_" + i;
            String args = "{\"code\": \"print('hello world " + i + "')" + repeatChar('x', 1000) + "\"}";

            ContentBlock.ToolCallBlock call = new ContentBlock.ToolCallBlock(id, toolName, args);
            ctx.add(new Msg(UUID.randomUUID().toString(), "assistant", List.of(call)));

            ContentBlock.ToolResultBlock result = new ContentBlock.ToolResultBlock(
                    id, "result " + i + ": " + repeatChar('y', 2000), false);
            ctx.add(new Msg(UUID.randomUUID().toString(), "tool", List.of(result)));
        }
        return ctx;
    }

    private static void assertAllToolCallsUntouched(List<Msg> context) {
        for (ToolCallSite site : collectToolCallSites(context)) {
            assertFalse(isStubbedCall(site.assistantCall),
                    "tool_call 不应有 stub 标记");
            assertFalse(MicroCompactorTest.isStubbedResult(site.toolResult),
                    "tool_result 不应有 stub 标记");
        }
    }

    private static boolean isStubbedCall(ContentBlock.ToolCallBlock call) {
        return call.getArguments() != null && call.getArguments().startsWith("[已压缩");
    }

    private static boolean isStubbedResult(ContentBlock.ToolResultBlock result) {
        return result.getContent() != null && result.getContent().startsWith("[已压缩");
    }

    private static List<ToolCallSite> collectToolCallSites(List<Msg> context) {
        List<ToolCallSite> sites = new ArrayList<>();
        for (int i = 0; i < context.size(); i++) {
            Msg msg = context.get(i);
            if (!"assistant".equals(msg.getRole())) continue;
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ContentBlock.ToolCallBlock tc) {
                    ContentBlock.ToolResultBlock result = findResultFor(context, i, tc.getId());
                    sites.add(new ToolCallSite(tc, result));
                }
            }
        }
        return sites;
    }

    private static ContentBlock.ToolResultBlock findResultFor(List<Msg> context, int fromIdx, String toolCallId) {
        for (int i = fromIdx + 1; i < context.size(); i++) {
            Msg msg = context.get(i);
            if (!"tool".equals(msg.getRole())) continue;
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ContentBlock.ToolResultBlock tr
                        && toolCallId.equals(tr.getToolCallId())) {
                    return tr;
                }
            }
        }
        throw new AssertionError("未找到 tool_call_id=" + toolCallId + " 对应的 tool result");
    }

    private static Msg userMsg(String text) {
        return new Msg(UUID.randomUUID().toString(), "user",
                List.of(new ContentBlock.TextBlock(text)));
    }

    private static String repeatChar(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    private record ToolCallSite(ContentBlock.ToolCallBlock assistantCall,
                                ContentBlock.ToolResultBlock toolResult) {
    }
}
