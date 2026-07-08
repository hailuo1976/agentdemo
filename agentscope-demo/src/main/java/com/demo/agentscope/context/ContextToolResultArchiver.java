package com.demo.agentscope.context;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.tool.ToolOutputArchive;
import com.demo.agentscope.tool.ToolResultSummarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 上下文工具结果归档/摘要触发器。
 * <p>
 * 维护一个核心不变量：在 Agent.context 中，至多只有一条工具结果块处于 {@link #STATE_FULL}
 * 状态（最新一次工具调用），其余历史工具结果块都被就地改写为 {@link #STATE_SUMMARIZED} 状态。
 * </p>
 *
 * <h3>工作时机</h3>
 * <pre>
 *   新工具结果即将入 context 时：
 *     1. compactExistingToolResults(context)   ← 扫描旧的 FULL 块 → 改写为 SUMMARIZED
 *     2. archive.archive(...)                  ← 把本次原始输出持久化
 *     3. context.add(toolResultMsg)
 *     4. markAsFull(toolResultMsg, toolCallId) ← 把新加入的块标记为 FULL
 * </pre>
 *
 * <h3>状态标记位置</h3>
 * 因为 {@link ContentBlock.ToolResultBlock} 的字段全是 final，无法在块上加字段；
 * 状态通过 {@link Msg#getMetadata()} 携带：key = toolCallId，value = "FULL" / "SUMMARIZED"。
 *
 * <h3>与 MicroCompactor 的协调</h3>
 * {@code MicroCompactor} 会把旧工具块的 content 替换为 {@code [REDACTED]} stub，
 * 是"最终态"。本类一旦发现某块的 metadata 已是 SUMMARIZED，就不会二次摘要，避免冲突。
 */
public class ContextToolResultArchiver {

    private static final Logger log = LoggerFactory.getLogger(ContextToolResultArchiver.class);

    /** 元数据取值：工具结果保持完整原始内容。 */
    public static final String STATE_FULL = "FULL";

    /** 元数据取值：工具结果已被摘要。 */
    public static final String STATE_SUMMARIZED = "SUMMARIZED";

    private final ToolOutputArchive archive;
    private final ToolResultSummarizer summarizer;

    public ContextToolResultArchiver(ToolOutputArchive archive, ToolResultSummarizer summarizer) {
        this.archive = archive;
        this.summarizer = summarizer;
    }

    public ToolOutputArchive archive() {
        return archive;
    }

    /**
     * 扫描上下文，把所有标记为 {@link #STATE_FULL} 的历史工具结果块就地改写为摘要。
     * <p>
     * 调用时机：新工具结果即将 add 到 context 之前。此时 context 里若存在 FULL 块，
     * 必然是"老的"（即将被新结果取代），需要降级为摘要。
     * </p>
     * <p>
     * 安全保证：
     * <ul>
     *   <li>已 SUMMARIZED 的块不动（幂等、避免二次摘要）</li>
     *   <li>archive 里查不到原始输出时跳过（保护数据，不空指针）</li>
     *   <li>摘要失败不抛异常，保留原块</li>
     * </ul>
     * </p>
     *
     * @param context 当前上下文消息列表（就地修改）
     */
    public void compactExistingToolResults(List<Msg> context) {
        if (context == null || context.isEmpty()) {
            return;
        }
        int compacted = 0;
        for (Msg msg : context) {
            // 逐 block 扫描（一条 Msg 可能含多个 ToolResultBlock）
            List<ContentBlock> blocks = msg.getContent();
            for (int i = 0; i < blocks.size(); i++) {
                ContentBlock block = blocks.get(i);
                if (!(block instanceof ContentBlock.ToolResultBlock trb)) {
                    continue;
                }
                String toolCallId = trb.getToolCallId();
                Object state = msg.getMetadata(toolCallId);
                if (!STATE_FULL.equals(state)) {
                    // 已 SUMMARIZED 或无标记（历史数据），无标记的也不动，避免误伤
                    continue;
                }
                if (!rewriteToSummary(msg, i, trb, toolCallId)) {
                    continue;
                }
                compacted++;
            }
        }
        if (compacted > 0) {
            log.debug("上下文工具结果摘要：本次共降级 {} 个 FULL 块为 SUMMARIZED", compacted);
        }
    }

    /**
     * 标记新加入的工具结果消息为 {@link #STATE_FULL} 状态。
     * <p>
     * 调用时机：{@code context.add(toolResultMsg)} 之后立即调用，确保下一轮 compact 能识别。
     * </p>
     *
     * @param toolResultMsg 刚加入 context 的工具结果消息
     * @param toolCallId    对应的工具调用 ID
     */
    public void markAsFull(Msg toolResultMsg, String toolCallId) {
        if (toolResultMsg == null || toolCallId == null) {
            return;
        }
        toolResultMsg.setMetadata(toolCallId, STATE_FULL);
    }

    /**
     * 把指定位置的工具结果块改写为摘要版本。
     *
     * @return true 表示已改写；false 表示因原始数据丢失而跳过
     */
    private boolean rewriteToSummary(Msg msg, int blockIndex,
                                     ContentBlock.ToolResultBlock block, String toolCallId) {
        String fullOutput = archive.getFullOutput(toolCallId);
        if (fullOutput == null) {
            log.warn("归档中查不到 toolCallId={} 的原始输出，跳过摘要（保留原块）", toolCallId);
            return false;
        }
        ToolOutputArchive.ArchivedMeta meta = archive.getMeta(toolCallId);
        String toolName = meta != null ? meta.toolName() : "unknown";
        String summary = summarizer.summarize(toolName, fullOutput);
        String newContent = buildSummarizedPrefix(toolCallId, fullOutput.length(), meta) + summary;

        ContentBlock.ToolResultBlock newBlock = new ContentBlock.ToolResultBlock(
                toolCallId, newContent, block.isError());
        msg.replaceBlock(blockIndex, newBlock);
        msg.setMetadata(toolCallId, STATE_SUMMARIZED);
        return true;
    }

    /**
     * 构造摘要块的内容前缀，明确告知模型该块已摘要、原始长度、以及如何取回完整内容。
     */
    private static String buildSummarizedPrefix(String toolCallId, int fullLength,
                                                ToolOutputArchive.ArchivedMeta meta) {
        String toolNamePart = meta != null ? " | tool=" + meta.toolName() : "";
        return String.format(
                "[此工具结果已摘要%s | tool_call_id=%s | 原始 %d 字符 | 如需完整内容请调用 get_full_tool_output 工具]%n",
                toolNamePart, toolCallId, fullLength);
    }
}
