package com.demo.agentscope.skill;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * access.log 聚合统计器。
 * <p>
 * 全量读取 {@link SkillStore#readAccessLog()} → 按 {@code period} 过滤 → 生成：
 * </p>
 * <ul>
 *   <li>{@link Overview}：技能总数 / 各状态数 / 总访问次数</li>
 *   <li>{@code byDay}：{@code Map<String, Long>}，key={@code YYYY-MM-DD}（参照 Claude Code {@code statsCache.ts:toDateString}，字典序=时序）</li>
 *   <li>{@code topSkills}：按 skillId 计数 Top 10（参照 {@code exampleCommands.ts:countAndSortItems}）</li>
 *   <li>{@code topActions}：按 action（get/search/list/...）计数</li>
 * </ul>
 *
 * <h3>Period 过滤</h3>
 * <ul>
 *   <li>{@code "7d"} —— 仅保留最近 7 天（含今天）</li>
 *   <li>{@code "30d"} —— 仅保留最近 30 天</li>
 *   <li>{@code "all"} 或其他 —— 不过滤</li>
 * </ul>
 */
public final class SkillStatsAggregator {

    private SkillStatsAggregator() {}

    /** 统计周期。 */
    public enum Period { P7D, P30D, ALL;

        public static Period parse(String s) {
            if (s == null) return ALL;
            return switch (s.trim().toLowerCase()) {
                case "7d" -> P7D;
                case "30d" -> P30D;
                default -> ALL;
            };
        }
        public String label() { return name().toLowerCase().replace("p", ""); }
    }

    /** 总览数据。 */
    public record Overview(int totalSkills, int draft, int published, int deprecated, int totalAccesses) {}

    /** 单条 Top 技能/动作计数。 */
    public record CountEntry(String key, long count) {}

    /** 完整统计结果。 */
    public record Result(
            Period period,
            Overview overview,
            Map<String, Long> byDay,
            List<CountEntry> topSkills,
            List<CountEntry> topActions
    ) {}

    /** 聚合入口。 */
    public static Result aggregate(List<Skill> allSkills, List<SkillAccessEntry> accessLog, Period period) {
        int total = allSkills.size();
        int draft = 0, published = 0, deprecated = 0;
        for (Skill s : allSkills) {
            if (s.getStatus() == null) continue;
            switch (s.getStatus()) {
                case DRAFT -> draft++;
                case PUBLISHED -> published++;
                case DEPRECATED -> deprecated++;
            }
        }

        List<SkillAccessEntry> filtered = filterByPeriod(accessLog, period);
        int totalAccesses = filtered.size();

        Map<String, Long> byDay = groupByDay(filtered);
        List<CountEntry> topSkills = topN(filtered.stream()
                .filter(e -> e.skillId() != null)
                .collect(Collectors.groupingBy(SkillAccessEntry::skillId, Collectors.counting())), 10);
        List<CountEntry> topActions = topN(filtered.stream()
                .filter(e -> e.action() != null)
                .collect(Collectors.groupingBy(SkillAccessEntry::action, Collectors.counting())), 10);

        return new Result(period,
                new Overview(total, draft, published, deprecated, totalAccesses),
                byDay, topSkills, topActions);
    }

    private static List<SkillAccessEntry> filterByPeriod(List<SkillAccessEntry> log, Period period) {
        if (period == Period.ALL) return log;
        int days = period == Period.P7D ? 7 : 30;
        Instant cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        return log.stream()
                .filter(e -> e.timestamp() != null && !e.timestamp().isBefore(cutoff))
                .toList();
    }

    private static Map<String, Long> groupByDay(List<SkillAccessEntry> log) {
        // key=YYYY-MM-DD，保持插入时序后排序输出
        Map<String, Long> raw = log.stream()
                .filter(e -> e.timestamp() != null)
                .collect(Collectors.groupingBy(
                        e -> e.timestamp().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        Collectors.counting()));
        // 字典序 = 时序
        return raw.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private static List<CountEntry> topN(Map<String, Long> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String,Long>>comparingLong(Map.Entry::getValue).reversed())
                .limit(n)
                .map(e -> new CountEntry(e.getKey(), e.getValue()))
                .toList();
    }
}
