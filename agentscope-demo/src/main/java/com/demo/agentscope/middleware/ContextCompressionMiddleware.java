package com.demo.agentscope.middleware;

import com.demo.agentscope.event.AgentEvent;
import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩中间件。
 * <p>
 * 在 onReplyEnd 时检查上下文消息数量，若超过阈值则执行结构化压缩：
 * 保留 system 角色消息 + 最近10条消息 + 一条压缩摘要消息。
 * 压缩摘要以 system 角色写入，包含 task_goal、current_state、
 * key_findings、next_steps 四个结构化字段。
 * </p>
 */
public class ContextCompressionMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressionMiddleware.class);

    /** 默认消息数量阈值 */
    private static final int DEFAULT_THRESHOLD = 40;

    /** 默认保留的最近消息数 */
    private static final int DEFAULT_KEEP_RECENT = 10;

    /** 消息数量阈值，超过此值触发压缩 */
    private final int threshold;

    /** 保留的最近消息数量 */
    private final int keepRecent;

    /** 上下文中消息列表的属性键 */
    private static final String CONTEXT_MESSAGES_KEY = "contextMessages";

    public ContextCompressionMiddleware() {
        this(DEFAULT_THRESHOLD);
    }

    public ContextCompressionMiddleware(int threshold) {
        this(threshold, DEFAULT_KEEP_RECENT);
    }

    public ContextCompressionMiddleware(int threshold, int keepRecent) {
        this.threshold = threshold;
        this.keepRecent = keepRecent;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onReplyEnd(AgentContext ctx, EventStream stream) {
        Object messagesObj = ctx.getAttribute(CONTEXT_MESSAGES_KEY);
        if (!(messagesObj instanceof List)) {
            log.debug("[ContextCompression] 上下文中无消息列表，跳过压缩检查");
            return;
        }

        List<Msg> messages = (List<Msg>) messagesObj;
        int originalSize = messages.size();

        if (originalSize <= threshold) {
            log.debug("[ContextCompression] 消息数量={} 未超过阈值={}，无需压缩",
                    originalSize, threshold);
            return;
        }

        log.info("[ContextCompression] 开始压缩 | agentId={}, originalSize={}, threshold={}, keepRecent={}",
                ctx.getAgentId(), originalSize, threshold, keepRecent);

        // 分离 system 消息和非 system 消息
        List<Msg> systemMsgs = new ArrayList<>();
        List<Msg> nonSystemMsgs = new ArrayList<>();
        for (Msg msg : messages) {
            if ("system".equals(msg.getRole())) {
                systemMsgs.add(msg);
            } else {
                nonSystemMsgs.add(msg);
            }
        }

        // 保留最近 keepRecent 条非 system 消息
        List<Msg> recentMsgs;
        if (nonSystemMsgs.size() > keepRecent) {
            int fromIndex = nonSystemMsgs.size() - keepRecent;
            recentMsgs = new ArrayList<>(nonSystemMsgs.subList(fromIndex, nonSystemMsgs.size()));
        } else {
            recentMsgs = new ArrayList<>(nonSystemMsgs);
        }

        // 需要压缩的旧消息
        List<Msg> oldMsgs = nonSystemMsgs.subList(0, nonSystemMsgs.size() - recentMsgs.size());

        // 生成压缩摘要
        Msg compressionSummary = generateCompressionSummary(ctx, oldMsgs);

        // 重建消息列表: system消息 + 压缩摘要 + 最近的非system消息
        List<Msg> compressed = new ArrayList<>(systemMsgs);
        compressed.add(compressionSummary);
        compressed.addAll(recentMsgs);

        // 替换上下文中的消息列表
        ctx.setAttribute(CONTEXT_MESSAGES_KEY, compressed);

        // 发射压缩事件
        stream.emit(AgentEvent.contextCompressed(ctx.getAgentId(), originalSize, compressed.size()));

        log.info("[ContextCompression] 压缩完成 | agentId={}, originalSize={}, compressedSize={}, " +
                        "saved={}",
                ctx.getAgentId(), originalSize, compressed.size(), originalSize - compressed.size());
    }

    /**
     * 根据旧消息生成结构化压缩摘要。
     *
     * @param ctx    智能体上下文
     * @param oldMsgs 待压缩的旧消息列表
     * @return 压缩摘要消息
     */
    private Msg generateCompressionSummary(AgentContext ctx, List<Msg> oldMsgs) {
        // 提取旧消息中的关键文本信息
        StringBuilder contentBuilder = new StringBuilder();
        for (Msg msg : oldMsgs) {
            String text = msg.getTextContent();
            if (!text.isEmpty()) {
                contentBuilder.append("[").append(msg.getRole()).append("] ")
                        .append(truncate(text, 200))
                        .append("\n");
            }
        }

        String rawContent = contentBuilder.toString();

        // 构建结构化压缩摘要
        String summaryText = "[上下文压缩摘要]\n" +
                "task_goal: 根据以下历史对话提取的任务目标\n" +
                "current_state: 当前任务执行状态\n" +
                "key_findings: 已取得的关键发现和结果\n" +
                "next_steps: 待执行的后续步骤\n" +
                "\n--- 压缩的原始内容 ---\n" +
                rawContent;

        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new ContentBlock.TextBlock(summaryText));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("compressed", true);
        metadata.put("originalMessageCount", oldMsgs.size());

        return new Msg(
                "compression_" + ctx.getAgentId() + "_" + System.currentTimeMillis(),
                "system",
                blocks,
                null,
                Instant.now(),
                metadata
        );
    }

    /**
     * 截断文本到指定最大长度。
     */
    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
