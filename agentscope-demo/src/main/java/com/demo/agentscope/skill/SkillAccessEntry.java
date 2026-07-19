package com.demo.agentscope.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;

/**
 * access.log 单行记录（JSONL）。
 * <p>
 * 由 {@link SkillStore#appendAccess} 在每次读取/检索/列表工具调用时写入，
 * 由 {@link SkillStatsAggregator} 全量扫描聚合。
 * </p>
 *
 * @param timestamp 事件发生时间
 * @param skillId   命中的技能 ID；list/search 等无具体技能的事件为 null
 * @param action    动作类型：get / search / list / create / update / publish / deprecate / delete / export / import / stats
 * @param query     search 时携带的查询字符串；其他动作为 null
 * @param actor     触发者标识（如 agent 名字）；MVP 阶段可为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"timestamp", "skillId", "action", "query", "actor"})
public record SkillAccessEntry(
        Instant timestamp,
        String skillId,
        String action,
        String query,
        String actor
) {
    public SkillAccessEntry {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
