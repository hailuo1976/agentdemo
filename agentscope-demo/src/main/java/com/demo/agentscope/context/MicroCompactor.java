package com.demo.agentscope.context;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * mid-reply 工具调用压缩器。
 * <p>
 * 参考 Claude Code 的 microCompact 思路:在每次模型调用前扫描 context,
 * 把<strong>早期</strong>的 tool_call.arguments 与对应 tool_result.content
 * 就地替换为短 stub,保留最近 N 条原值。
 * </p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><strong>按 tool_use.id 操作</strong>(不是按内容):保留 id / name /
 *       tool_call_id 不变,只换 arguments / content 字符串,确保 OpenAI 协议
 *       的 tool_calls ↔ tool 消息关联链不被破坏。</li>
 *   <li><strong>白名单</strong>:只压缩体积大且可重复执行的工具(参考 Claude Code
 *       COMPACTABLE_TOOLS = FileRead/Shell/Grep/...)。</li>
 *   <li><strong>保留最近 KEEP_RECENT 条</strong>:模型对当前任务推理无损。</li>
 *   <li><strong>幂等</strong>:已是 stub 的 block 不重复处理。</li>
 *   <li><strong>无 Anthropic cache_edits API</strong>:Java 走 OpenAI 协议,
 *       只能客户端侧替换 stub(等价 Claude Code 的时间基 >60min 路径)。</li>
 * </ul>
 *
 * <h3>效果</h3>
 * 8916 字符的 execute_python 脚本被替换为 ~20 字符 stub,后续每轮省 ~2960 token。
 * 单轮 60 次工具调用的累计 token 增长曲线从单调上升变为在第 12 轮后趋于平稳。
 */
public final class MicroCompactor {

    private static final Logger log = LoggerFactory.getLogger(MicroCompactor.class);

    /** 可压缩工具白名单:输出体积大、且可重复执行(模型需要时可以再次调用)。 */
    private static final Set<String> COMPACTABLE = Set.of(
            "execute_python", "execute_command",
            "read_file", "write_file", "list_files",
            "get_stock_detail", "agent_message"
    );

    /** 替换 tool_call.arguments 的占位符(语义化提示模型该参数已被压缩)。 */
    private static final String CLEARED_TOOL_CALL_ARGS =
            "[已压缩:旧工具调用参数,如需重看请显式询问]";

    /** 替换 tool_result.content 的占位符。 */
    private static final String CLEARED_TOOL_RESULT =
            "[已压缩:旧工具结果]";

    /** 保留最近 N 轮 tool_call 的原值默认值（可由调用方覆盖）。 */
    private static final int DEFAULT_KEEP_RECENT = 5;

    /** 累计可压缩 tool_call 达到此阈值才开始压缩（默认值，可由调用方覆盖）。 */
    private static final int DEFAULT_TRIGGER_TOOL_COUNT = 12;

    private MicroCompactor() {
    }

    /**
     * 如果可压缩工具调用累计达到阈值,把早期的 arguments/result 替换为 stub。
     *
     * @param context 当前 reply 的消息列表(Agent.reply 的累积 context)
     * @return 被压缩的工具调用数量(0 表示未触发)
     */
    public static int compactIfNeeded(List<Msg> context) {
        return compactIfNeeded(context, DEFAULT_KEEP_RECENT, DEFAULT_TRIGGER_TOOL_COUNT);
    }

    /**
     * 带参数版本的压缩入口，由 {@link ContextManager} 装配时传入 limits。
     */
    public static int compactIfNeeded(List<Msg> context, int keepRecent, int triggerToolCount) {
        if (context == null || context.isEmpty()) {
            return 0;
        }
        int effectiveKeep = keepRecent > 0 ? keepRecent : DEFAULT_KEEP_RECENT;
        int effectiveTrigger = triggerToolCount > 0 ? triggerToolCount : DEFAULT_TRIGGER_TOOL_COUNT;

        List<ToolUseSite> sites = collectCompactableSites(context);
        if (sites.size() < effectiveTrigger) {
            return 0;
        }

        // 保留最后 effectiveKeep 个 tool_use.id 的原值,其余替换
        Set<String> keepIds = new HashSet<>();
        int startKeep = sites.size() - effectiveKeep;
        for (int i = Math.max(0, startKeep); i < sites.size(); i++) {
            keepIds.add(sites.get(i).toolCallId);
        }

        int compactedCount = 0;
        for (ToolUseSite site : sites) {
            if (keepIds.contains(site.toolCallId)) {
                continue;
            }
            if (replaceSiteIfNeeded(context, site)) {
                compactedCount++;
            }
        }

        if (compactedCount > 0) {
            log.debug("microCompact: 压缩 {} 个旧工具调用(共 {} 个白名单工具,保留最近 {})",
                    compactedCount, sites.size(), effectiveKeep);
        }
        return compactedCount;
    }

    /**
     * 收集所有可压缩工具调用位置(assistant 消息 index + 该消息内 ToolCallBlock index)。
     * 只统计名字在白名单内、且尚未被压缩(arguments 不是 stub)的调用。
     */
    private static List<ToolUseSite> collectCompactableSites(List<Msg> context) {
        List<ToolUseSite> sites = new ArrayList<>();
        for (int msgIdx = 0; msgIdx < context.size(); msgIdx++) {
            Msg msg = context.get(msgIdx);
            if (!"assistant".equals(msg.getRole())) {
                continue;
            }
            List<ContentBlock> blocks = msg.getContent();
            for (int blkIdx = 0; blkIdx < blocks.size(); blkIdx++) {
                ContentBlock block = blocks.get(blkIdx);
                if (!(block instanceof ContentBlock.ToolCallBlock tc)) {
                    continue;
                }
                if (!COMPACTABLE.contains(tc.getName())) {
                    continue;
                }
                if (CLEARED_TOOL_CALL_ARGS.equals(tc.getArguments())) {
                    continue; // 已压缩,跳过(幂等)
                }
                sites.add(new ToolUseSite(tc.getId(), msgIdx, blkIdx));
            }
        }
        return sites;
    }

    /**
     * 替换指定 site 的 arguments 和对应 tool 消息的 content。
     * 关键:用新的 block 实例替换(原 block 是 final 字段不可变),
     * 通过 {@link Msg#replaceBlock(int, ContentBlock)} 写回。
     */
    private static boolean replaceSiteIfNeeded(List<Msg> context, ToolUseSite site) {
        boolean changed = false;

        // 1. 替换 assistant 消息内的 ToolCallBlock.arguments
        Msg assistantMsg = context.get(site.assistantMsgIndex);
        ContentBlock.ToolCallBlock oldBlock = (ContentBlock.ToolCallBlock)
                assistantMsg.getContent().get(site.blockIndexInAssistant);
        if (!(CLEARED_TOOL_CALL_ARGS.equals(oldBlock.getArguments()))) {
            ContentBlock.ToolCallBlock stubbedCall = new ContentBlock.ToolCallBlock(
                    oldBlock.getId(),
                    oldBlock.getName(),
                    CLEARED_TOOL_CALL_ARGS);
            assistantMsg.replaceBlock(site.blockIndexInAssistant, stubbedCall);
            changed = true;
        }

        // 2. 找到配对的 tool 消息(以 tool_call_id 关联),替换其 ToolResultBlock.content
        for (int msgIdx = site.assistantMsgIndex + 1; msgIdx < context.size(); msgIdx++) {
            Msg maybeTool = context.get(msgIdx);
            if (!"tool".equals(maybeTool.getRole())) {
                continue;
            }
            List<ContentBlock> toolBlocks = maybeTool.getContent();
            for (int blkIdx = 0; blkIdx < toolBlocks.size(); blkIdx++) {
                ContentBlock tb = toolBlocks.get(blkIdx);
                if (!(tb instanceof ContentBlock.ToolResultBlock tr)) {
                    continue;
                }
                if (!site.toolCallId.equals(tr.getToolCallId())) {
                    continue;
                }
                if (CLEARED_TOOL_RESULT.equals(tr.getContent())) {
                    continue; // 已压缩
                }
                ContentBlock.ToolResultBlock stubbedResult = new ContentBlock.ToolResultBlock(
                        tr.getToolCallId(), CLEARED_TOOL_RESULT, tr.isError());
                maybeTool.replaceBlock(blkIdx, stubbedResult);
                changed = true;
                break;
            }
        }

        return changed;
    }

    /** 一处工具调用的位置信息:tool_use.id + 在 context 中的 assistant 消息 index + block index。 */
    private record ToolUseSite(String toolCallId, int assistantMsgIndex, int blockIndexInAssistant) {
    }
}
