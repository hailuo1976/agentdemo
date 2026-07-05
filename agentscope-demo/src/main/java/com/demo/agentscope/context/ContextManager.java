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
    
    /** 最大上下文token数 */
    private static final int MAX_CONTEXT_TOKENS = 4000;

    /** 最大保留的最近消息数 */
    private static final int MAX_RECENT_MESSAGES = 10;

    /** 短期记忆检索数量 */
    private static final int SHORT_TERM_MEMORY_LIMIT = 3;

    /** 长期记忆检索数量 */
    private static final int LONG_TERM_MEMORY_LIMIT = 2;

    /** 短期记忆 */
    private final ShortTermMemory shortTermMemory;

    /** 长期记忆（可选） */
    private LongTermMemory longTermMemory;

    /** 系统提示词 */
    private String systemPrompt;

    /** 压缩摘要 */
    private String compressedSummary;

    public ContextManager(ShortTermMemory shortTermMemory, String systemPrompt) {
        this.shortTermMemory = shortTermMemory;
        this.systemPrompt = systemPrompt;
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
                List<MemoryEntry> shortTermMemories = shortTermMemory.recall(currentQuery, SHORT_TERM_MEMORY_LIMIT);
                for (MemoryEntry memory : shortTermMemories) {
                    String memoryText = formatMemoryAsText(memory, "短期");
                    systemContentBuilder.append(memoryText).append("\n");
                }
            }
            
            // 3.2 长期记忆
            if (longTermMemory != null) {
                List<MemoryEntry> longTermMemories = longTermMemory.recall(currentQuery, LONG_TERM_MEMORY_LIMIT);
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
        if (estimatedTokens > MAX_CONTEXT_TOKENS) {
            log.warn("上下文token数超限: {} > {}，触发压缩", estimatedTokens, MAX_CONTEXT_TOKENS);
            context = compressContext(context);
        }

        log.debug("构建上下文完成，消息数: {}, 估算token数: {}", context.size(), Msg.sumEstimatedTokens(context));
        return context;
    }
    
    /**
     * 压缩上下文。
     */
    private List<Msg> compressContext(List<Msg> context) {
        // 简单策略：保留系统消息和最近的消息
        List<Msg> compressed = new ArrayList<>();
        
        // 保留系统消息
        for (Msg msg : context) {
            if ("system".equals(msg.getRole())) {
                compressed.add(msg);
            }
        }
        
        // 保留最近的消息
        int recentCount = Math.min(MAX_RECENT_MESSAGES, context.size());
        int startIndex = Math.max(0, context.size() - recentCount);
        for (int i = startIndex; i < context.size(); i++) {
            Msg msg = context.get(i);
            if (!"system".equals(msg.getRole())) {
                compressed.add(msg);
            }
        }
        
        log.info("压缩上下文，从 {} 条压缩到 {} 条", context.size(), compressed.size());
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
