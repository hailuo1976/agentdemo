package com.demo.agentscope.memory;

import java.util.List;

/**
 * 记忆管理工具常量。
 * <p>
 * 集中定义 6 个 MCP 工具名，便于在 {@code AgentScopeDemoApplication} 装配权限规则、
 * 在 {@link MemoryToolService} 注册工具、在系统提示词里引用。
 * </p>
 *
 * <h3>工具一览</h3>
 * <ul>
 *   <li>{@link #TOOL_MEMORY_STORE}    — LLM 主动写入记忆（短期/长期）</li>
 *   <li>{@link #TOOL_MEMORY_RECALL}   — 关键词检索，按 importance+recency 排序</li>
 *   <li>{@link #TOOL_MEMORY_LIST}     — 列出记忆摘要</li>
 *   <li>{@link #TOOL_MEMORY_PROMOTE}  — 短期 → 长期 升级</li>
 *   <li>{@link #TOOL_MEMORY_DELETE}   — 删除指定记忆</li>
 *   <li>{@link #TOOL_MEMORY_STATS}    — 使用统计（终端 Markdown 渲染）</li>
 * </ul>
 */
public final class MemoryConstants {

    private MemoryConstants() {}

    public static final String TOOL_MEMORY_STORE   = "memory_store";
    public static final String TOOL_MEMORY_RECALL  = "memory_recall";
    public static final String TOOL_MEMORY_LIST    = "memory_list";
    public static final String TOOL_MEMORY_PROMOTE = "memory_promote";
    public static final String TOOL_MEMORY_DELETE  = "memory_delete";
    public static final String TOOL_MEMORY_STATS   = "memory_stats";

    /** 6 个记忆管理工具名清单。 */
    public static final List<String> TOOL_NAMES = List.of(
            TOOL_MEMORY_STORE,
            TOOL_MEMORY_RECALL,
            TOOL_MEMORY_LIST,
            TOOL_MEMORY_PROMOTE,
            TOOL_MEMORY_DELETE,
            TOOL_MEMORY_STATS
    );
}
