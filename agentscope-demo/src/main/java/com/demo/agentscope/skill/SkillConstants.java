package com.demo.agentscope.skill;

import java.util.List;

/**
 * 技能管理工具常量。
 * <p>
 * 集中定义 12 个 MCP 工具名，便于在 {@code AgentScopeDemoApplication} 装配权限规则、
 * 在 {@link SkillToolService} 注册工具、在系统提示词里引用。
 * </p>
 */
public final class SkillConstants {

    private SkillConstants() {}

    public static final String TOOL_SKILL_CREATE    = "skill_create";
    public static final String TOOL_SKILL_GET       = "skill_get";
    public static final String TOOL_SKILL_LIST      = "skill_list";
    public static final String TOOL_SKILL_UPDATE    = "skill_update";
    public static final String TOOL_SKILL_DELETE    = "skill_delete";
    public static final String TOOL_SKILL_SEARCH    = "skill_search";
    public static final String TOOL_SKILL_PUBLISH   = "skill_publish";
    public static final String TOOL_SKILL_DEPRECATE = "skill_deprecate";
    public static final String TOOL_SKILL_HISTORY   = "skill_history";
    public static final String TOOL_SKILL_EXPORT    = "skill_export";
    public static final String TOOL_SKILL_IMPORT    = "skill_import";
    public static final String TOOL_SKILL_STATS     = "skill_stats";

    /** 12 个技能管理工具名清单（按 plan 顺序）。 */
    public static final List<String> TOOL_NAMES = List.of(
            TOOL_SKILL_CREATE,
            TOOL_SKILL_GET,
            TOOL_SKILL_LIST,
            TOOL_SKILL_UPDATE,
            TOOL_SKILL_DELETE,
            TOOL_SKILL_SEARCH,
            TOOL_SKILL_PUBLISH,
            TOOL_SKILL_DEPRECATE,
            TOOL_SKILL_HISTORY,
            TOOL_SKILL_EXPORT,
            TOOL_SKILL_IMPORT,
            TOOL_SKILL_STATS
    );
}
