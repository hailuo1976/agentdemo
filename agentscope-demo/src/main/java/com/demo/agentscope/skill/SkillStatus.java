package com.demo.agentscope.skill;

/**
 * 技能生命周期状态。
 * <ul>
 *   <li>{@link #DRAFT} —— 新建草稿，尚未发布。</li>
 *   <li>{@link #PUBLISHED} —— 已发布，可被检索与统计。</li>
 *   <li>{@link #DEPRECATED} —— 已废弃（软删除），不再出现在默认列表中。</li>
 * </ul>
 */
public enum SkillStatus {
    DRAFT,
    PUBLISHED,
    DEPRECATED
}
