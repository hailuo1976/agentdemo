package com.demo.agentscope.context;

import com.demo.agentscope.memory.LongTermMemory;
import com.demo.agentscope.memory.MemoryEntry;
import com.demo.agentscope.memory.ShortTermMemory;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 智能上下文管理器。
 * <p>
 * 负责构建和优化发送给LLM的上下文，包括：
 * - 系统提示词
 * - 压缩摘要
 * - 相关记忆（短期 + 长期）
 * - 最近对话
 * </p>
 */
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    /** 默认最大上下文 token 数（构造器未指定时使用）。 */
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 40000;

    /** 最大保留的最近消息数（构造器未指定时使用）。 */
    private static final int DEFAULT_MAX_RECENT_MESSAGES = 10;

    /** 短期记忆检索数量（构造器未指定时使用）。 */
    private static final int DEFAULT_SHORT_TERM_MEMORY_LIMIT = 3;

    /** 长期记忆检索数量（构造器未指定时使用）。 */
    private static final int DEFAULT_LONG_TERM_MEMORY_LIMIT = 2;

    /** 短期记忆 */
    private final ShortTermMemory shortTermMemory;

    /** 长期记忆（可选） */
    private LongTermMemory longTermMemory;

    /** 系统提示词 */
    private String systemPrompt;

    /** 压缩摘要 */
    private String compressedSummary;

    /** 触发压缩的 token 阈值（运行期可通过 {@link #updateLimits} 修改）。 */
    private int maxContextTokens;

    /** 滑动窗口保留的最近消息数。 */
    private int maxRecentMessages;

    /** 短期记忆召回条数。 */
    private int shortTermMemoryLimit;

    /** 长期记忆召回条数。 */
    private int longTermMemoryLimit;

    /** MicroCompactor 保留的最近工具调用数。 */
    private int microCompactorKeepRecent;

    /** MicroCompactor 触发压缩的工具调用次数。 */
    private int microCompactorTriggerToolCount;

    public ContextManager(ShortTermMemory shortTermMemory, String systemPrompt) {
        this(shortTermMemory, systemPrompt, DEFAULT_MAX_CONTEXT_TOKENS);
    }

    /**
     * @param maxContextTokens 触发压缩的 token 阈值，&lt;=0 时回退到默认值
     */
    public ContextManager(ShortTermMemory shortTermMemory, String systemPrompt, int maxContextTokens) {
        this(shortTermMemory, systemPrompt, maxContextTokens,
                DEFAULT_MAX_RECENT_MESSAGES,
                DEFAULT_SHORT_TERM_MEMORY_LIMIT,
                DEFAULT_LONG_TERM_MEMORY_LIMIT);
    }

    /**
     * 全参数构造器，所有数值均由 {@link com.demo.agentscope.config.AgentLimits} 提供。
     */
    public ContextManager(ShortTermMemory shortTermMemory, String systemPrompt,
                           int maxContextTokens, int maxRecentMessages,
                           int shortTermMemoryLimit, int longTermMemoryLimit) {
        this.shortTermMemory = shortTermMemory;
        this.systemPrompt = systemPrompt;
        this.maxContextTokens = maxContextTokens > 0 ? maxContextTokens : DEFAULT_MAX_CONTEXT_TOKENS;
        this.maxRecentMessages = maxRecentMessages > 0 ? maxRecentMessages : DEFAULT_MAX_RECENT_MESSAGES;
        this.shortTermMemoryLimit = Math.max(0, shortTermMemoryLimit);
        this.longTermMemoryLimit = Math.max(0, longTermMemoryLimit);
        // MicroCompactor 默认值，可由 setMicroCompactorLimits 覆盖
        this.microCompactorKeepRecent = 5;
        this.microCompactorTriggerToolCount = 12;
    }

    /**
     * 运行期更新所有限制（由 REPL /config set 触发）。
     */
    public void updateLimits(int maxContextTokens, int maxRecentMessages,
                              int shortTermMemoryLimit, int longTermMemoryLimit,
                              int microCompactorKeepRecent, int microCompactorTriggerToolCount) {
        if (maxContextTokens > 0) this.maxContextTokens = maxContextTokens;
        if (maxRecentMessages > 0) this.maxRecentMessages = maxRecentMessages;
        if (shortTermMemoryLimit >= 0) this.shortTermMemoryLimit = shortTermMemoryLimit;
        if (longTermMemoryLimit >= 0) this.longTermMemoryLimit = longTermMemoryLimit;
        if (microCompactorKeepRecent > 0) this.microCompactorKeepRecent = microCompactorKeepRecent;
        if (microCompactorTriggerToolCount > 0) this.microCompactorTriggerToolCount = microCompactorTriggerToolCount;
        log.debug("ContextManager 限制已刷新: maxContextTokens={}, maxRecentMessages={}, stm={}, ltm={}, mc.keep={}, mc.trigger={}",
                this.maxContextTokens, this.maxRecentMessages, this.shortTermMemoryLimit,
                this.longTermMemoryLimit, this.microCompactorKeepRecent, this.microCompactorTriggerToolCount);
    }

    /**
     * 单独设置 MicroCompactor 参数（启动期装配时使用）。
     */
    public void setMicroCompactorLimits(int keepRecent, int triggerToolCount) {
        if (keepRecent > 0) this.microCompactorKeepRecent = keepRecent;
        if (triggerToolCount > 0) this.microCompactorTriggerToolCount = triggerToolCount;
    }

    public int getMicroCompactorKeepRecent() { return microCompactorKeepRecent; }

    public int getMicroCompactorTriggerToolCount() { return microCompactorTriggerToolCount; }

    /**
     * 替换系统提示词（由 Agent.regenerateSystemPrompt 触发）。
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        log.debug("ContextManager 系统提示词已更新，长度: {}",
                systemPrompt != null ? systemPrompt.length() : 0);
    }

    /**
     * 设置长期记忆。
     */
    public void setLongTermMemory(LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
        log.info("上下文管理器已启用长期记忆");
    }

    /**
     * 设置压缩摘要。
     */
    public void setCompressedSummary(String summary) {
        this.compressedSummary = summary;
        log.debug("设置压缩摘要，长度: {}", summary != null ? summary.length() : 0);
    }

    /**
     * 清空压缩摘要。
     */
    public void clear() {
        compressedSummary = null;
        log.info("上下文已清空");
    }
    
    /**
     * 构建完整的上下文。
     *
     * @param currentQuery       当前用户查询
     * @param conversationHistory Agent的实际对话历史（来自 context 列表）
     */
    public List<Msg> buildContext(String currentQuery, List<Msg> conversationHistory) {
        List<Msg> context = new ArrayList<>();
        
        // 构建合并的系统消息内容（所有 system 内容合并为一条）
        StringBuilder systemContentBuilder = new StringBuilder();
        
        // 1. 添加系统提示词
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            systemContentBuilder.append(systemPrompt).append("\n\n");
        }

        // 2. 添加压缩摘要
        if (compressedSummary != null && !compressedSummary.isEmpty()) {
            systemContentBuilder.append("[历史摘要]\n").append(compressedSummary).append("\n\n");
        }

        // 3. 检索并添加相关记忆（短期 + 长期）
        if (currentQuery != null) {
            // 3.1 短期记忆
            if (shortTermMemory != null) {
                List<MemoryEntry> shortTermMemories = shortTermMemory.recall(currentQuery, shortTermMemoryLimit);
                for (MemoryEntry memory : shortTermMemories) {
                    String memoryText = formatMemoryAsText(memory, "短期");
                    systemContentBuilder.append(memoryText).append("\n");
                }
            }

            // 3.2 长期记忆
            if (longTermMemory != null) {
                List<MemoryEntry> longTermMemories = longTermMemory.recall(currentQuery, longTermMemoryLimit);
                for (MemoryEntry memory : longTermMemories) {
                    String memoryText = formatMemoryAsText(memory, "长期");
                    systemContentBuilder.append(memoryText).append("\n");
                }
            }
        }
        
        // 添加合并后的系统消息（只有一条）
        if (systemContentBuilder.length() > 0) {
            Msg systemMsg = new Msg(
                UUID.randomUUID().toString(),
                "system",
                List.of(new ContentBlock.TextBlock(systemContentBuilder.toString().trim()))
            );
            context.add(systemMsg);
        }
        
        // 4. 添加实际对话历史（来自 Agent 的 context 列表）
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            context.addAll(conversationHistory);
        }
        
        // 5. 估算token数，如果超限则进一步压缩
        int estimatedTokens = Msg.sumEstimatedTokens(context);
        if (estimatedTokens > maxContextTokens) {
            int preCount = context.size();
            log.warn("上下文token数超限: {} > {}，触发压缩", estimatedTokens, maxContextTokens);
            List<Msg> compressed = compressContext(context);

            // 把压缩后的非 system 消息回写到源历史列表（即 Agent.context），
            // 让下一轮 buildContext 从压缩后状态开始，而不是反复从原始超长历史重压。
            // 参考 Claude Code compact_boundary 思路：压缩后只保留 boundary 之后的消息。
            if (conversationHistory != null) {
                List<Msg> historySurvivors = new ArrayList<>();
                for (Msg m : compressed) {
                    if (!"system".equals(m.getRole())) {
                        historySurvivors.add(m);
                    }
                }
                conversationHistory.clear();
                conversationHistory.addAll(historySurvivors);
            }

            // 告知模型上下文已被压缩，避免它对早期信息缺失产生误判。
            // 参考 Claude Code 的 getCompactUserSummaryMessage：注入一条 user 角色说明消息。
            // 不调用额外 LLM 做摘要（成本/延迟考量），只声明窗口外消息已被丢弃。
            int totalDropped = preCount - compressed.size();
            Msg compactNotice = new Msg(
                UUID.randomUUID().toString(),
                "user",
                List.of(new ContentBlock.TextBlock(buildCompactNotice(totalDropped, compressed.size())))
            );
            compressed.add(1, compactNotice);  // 插在 system 之后、其它消息之前

            context = compressed;
            log.info("已注入压缩提示消息（被丢弃 {} 条，保留 {} 条）", totalDropped, compressed.size());
        }

        log.debug("构建上下文完成，消息数: {}, 估算token数: {}", context.size(), Msg.sumEstimatedTokens(context));
        return context;
    }
    
    /**
     * 压缩上下文。
     * <p>
     * 策略：保留 system 消息 + 最近 N 条非 system 消息，但必须保证
     * tool_call ↔ tool_result <b>双向</b>配对完整。OpenAI/GLM 协议要求：
     * <ul>
     *   <li>每个 assistant.tool_calls[*].id 必须有对应的 tool 结果消息存在</li>
     *   <li>每个 tool 消息必须引用窗口内某个 assistant 的 tool_call.id</li>
     * </ul>
     * 任一方向切断都会触发 HTTP 400 "messages 参数非法"。
     * 单向检查（只丢弃孤儿 tool）不足以应对 assistant 携带多个 tool_call
     * 但窗口只保留了部分结果的情形。
     * </p>
     * <p>
     * 此方法直接压缩当前 Agent 上下文，不修改 Agent 的 context 列表。返回的是
     * 全新列表（含 system 消息），调用方负责把它写回（或用于只读计算）。
     * </p>
     *
     * @param keepRecentOverride 滑动窗口大小；&lt;=0 时回退到 {@link #maxRecentMessages}
     * @return 压缩后的消息列表（含 system 消息与可能被钉入的 user 消息）
     */
    public List<Msg> compressContext(int keepRecentOverride) {
        // 此方法供 Agent.trimContext / /context trim 显式触发使用。
        // 为了不破坏现有 buildContext 的隐式调用路径，内部沿用既有的私有
        // compressContext(List) 算法，通过临时切换窗口大小实现参数化。
        // 由于此处没有 Agent 的 context 引用，调用方（Agent.trimContext）
        // 负责把 Agent.context 传入并接收返回值。
        throw new UnsupportedOperationException("请使用 compressContext(List, int)");
    }

    /**
     * 带窗口覆盖的压缩入口。供 Agent.trimContext 委托使用。
     *
     * @param context             待压缩的完整消息列表（含 system）
     * @param keepRecentOverride  滑动窗口大小；&lt;=0 时回退到 {@link #maxRecentMessages}
     */
    public List<Msg> compressContext(List<Msg> context, int keepRecentOverride) {
        int effective = keepRecentOverride > 0 ? keepRecentOverride : maxRecentMessages;
        return doCompress(context, effective);
    }

    /**
     * 兼容旧入口：直接用 {@link #maxRecentMessages} 作为窗口大小。
     */
    private List<Msg> compressContext(List<Msg> context) {
        return doCompress(context, maxRecentMessages);
    }

    private List<Msg> doCompress(List<Msg> context, int keepRecent) {
        List<Msg> compressed = new ArrayList<>();

        // 保留系统消息
        for (Msg msg : context) {
            if ("system".equals(msg.getRole())) {
                compressed.add(msg);
            }
        }

        // 计算窗口起点
        int recentCount = Math.min(keepRecent, context.size());
        int startIndex = Math.max(0, context.size() - recentCount);

        // 钉住最近的 user 消息：GLM/OpenAI 协议要求 messages 序列必须含 user 角色。
        // 多轮工具调用会让 user 消息被挤出最近 N 条窗口，压缩后会变成
        // system→assistant→tool→... 的非法序列，触发 HTTP 400 "messages 参数非法"。
        // 此时把窗口外最近的一条 user 消息钉在窗口之前，保证序列合法。
        boolean hasUserInWindow = false;
        for (int i = startIndex; i < context.size(); i++) {
            if ("user".equals(context.get(i).getRole())) {
                hasUserInWindow = true;
                break;
            }
        }
        Msg pinnedUser = null;
        if (!hasUserInWindow) {
            for (int i = startIndex - 1; i >= 0; i--) {
                if ("user".equals(context.get(i).getRole())) {
                    pinnedUser = context.get(i);
                    break;
                }
            }
            if (pinnedUser != null) {
                compressed.add(pinnedUser);
            }
        }

        // 第一遍：收集窗口内 tool_call_id → tool 消息索引（取首次出现位置）
        java.util.Map<String, Integer> toolResultIdxByCallId = new java.util.HashMap<>();
        for (int i = startIndex; i < context.size(); i++) {
            Msg msg = context.get(i);
            if ("tool".equals(msg.getRole())) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ContentBlock.ToolResultBlock tr) {
                        if (tr.getToolCallId() != null
                                && !toolResultIdxByCallId.containsKey(tr.getToolCallId())) {
                            toolResultIdxByCallId.put(tr.getToolCallId(), i);
                        }
                    }
                }
            }
        }

        // 第二遍：判定哪些 assistant 是"完整"的（所有 tool_calls 都有窗口内的 tool 结果），
        // 同时建立 tool_call_id → assistant 索引映射，用于 tool 消息的反向校验
        java.util.Set<Integer> completeAssistantIndices = new java.util.HashSet<>();
        java.util.Map<String, Integer> assistantIdxByToolCallId = new java.util.HashMap<>();
        for (int i = startIndex; i < context.size(); i++) {
            Msg msg = context.get(i);
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                boolean allSatisfied = true;
                for (ContentBlock.ToolCallBlock tc : msg.getToolCalls()) {
                    assistantIdxByToolCallId.put(tc.getId(), i);
                    if (!toolResultIdxByCallId.containsKey(tc.getId())) {
                        allSatisfied = false;
                    }
                }
                if (allSatisfied) {
                    completeAssistantIndices.add(i);
                }
            }
        }

        // 第三遍：输出。assistant 完整才保留；tool 消息的对应 assistant 完整才保留
        int droppedIncomplete = 0;
        int droppedOrphans = 0;
        for (int i = startIndex; i < context.size(); i++) {
            Msg msg = context.get(i);
            if ("system".equals(msg.getRole())) {
                continue;  // 已在 compressed 头部添加
            }
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                if (completeAssistantIndices.contains(i)) {
                    compressed.add(msg);
                } else {
                    droppedIncomplete++;
                }
                continue;
            }
            if ("tool".equals(msg.getRole())) {
                boolean keepTool = false;
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ContentBlock.ToolResultBlock tr) {
                        Integer assistantIdx = assistantIdxByToolCallId.get(tr.getToolCallId());
                        if (assistantIdx != null && completeAssistantIndices.contains(assistantIdx)) {
                            keepTool = true;
                            break;
                        }
                    }
                }
                if (keepTool) {
                    compressed.add(msg);
                } else {
                    droppedOrphans++;
                }
                continue;
            }
            compressed.add(msg);
        }

        log.info("压缩上下文，从 {} 条压缩到 {} 条（丢弃 {} 条不完整 assistant，{} 条孤儿 tool 消息{}）",
                context.size(), compressed.size(), droppedIncomplete, droppedOrphans,
                pinnedUser != null ? "，钉住 1 条窗口外 user 消息" : "");
        return compressed;
    }
    
    /**
     * 格式化记忆为文本。
     */
    private String formatMemoryAsText(MemoryEntry memory, String memoryType) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(memoryType).append("记忆]\n");

        MemoryEntry.MemoryContent content = memory.getContent();
        sb.append("摘要: ").append(content.getSummary()).append("\n");

        if (content.getKeyFindings() != null && !content.getKeyFindings().isEmpty()) {
            sb.append("关键发现: ").append(String.join(", ", content.getKeyFindings())).append("\n");
        }

        if (content.getEntities() != null && !content.getEntities().isEmpty()) {
            sb.append("相关实体: ").append(String.join(", ", content.getEntities())).append("\n");
        }

        return sb.toString();
    }

    /**
     * 构建注入到上下文的压缩告知文本。
     * <p>
     * 当 {@link #compressContext} 丢弃消息后，模型对早期对话一无所知，容易产生
     * 信息偏差（误以为没说过某事 / 重复问已确认过的细节）。注入一条 user 角色
     * 的说明消息，明确告诉模型：上文已因窗口限制被裁剪，如需具体细节请询问用户。
     * </p>
     * <p>
     * 参考 Claude Code {@code getCompactUserSummaryMessage} 的设计：用 user 角色
     * 承载压缩告知，与正常对话消息同序存在，避免模型把说明当成系统约束。
     * 不同之处：本实现不调用额外 LLM 生成摘要（成本/延迟考量），仅声明裁剪事实。
     * </p>
     *
     * @param droppedMessages 被丢弃的消息数
     * @param keptMessages    保留的消息数（含 system）
     */
    private static String buildCompactNotice(int droppedMessages, int keptMessages) {
        return "（系统提示：上文的 " + droppedMessages + " 条较早对话消息已因上下文窗口限制被压缩丢弃，"
                + "当前仅保留最近的 " + keptMessages + " 条消息。如需引用更早的具体内容（代码片段、"
                + "错误信息、用户已确认的细节等），请明确向我询问，我会重新提供。）";
    }
    
    /**
     * 估算token数:委托给 {@link Msg#sumEstimatedTokens(List)}。
     * <p>
     * 注意:必须把 ToolCallBlock.arguments 和 ToolResultBlock.content 一并计入,
     * 否则工具密集对话会严重低估 —— 工具调用的 Python 脚本参数体积才是大头。
     * </p>
     */
    private int estimateTokens(List<Msg> messages) {
        return Msg.sumEstimatedTokens(messages);
    }
}
