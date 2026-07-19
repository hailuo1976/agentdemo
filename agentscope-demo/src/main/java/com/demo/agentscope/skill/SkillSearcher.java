package com.demo.agentscope.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 文本评分搜索器。
 * <p>
 * 评分规则（参照 plan，多维度加和）：
 * </p>
 * <ul>
 *   <li>name 含 query（ignoreCase）        +100</li>
 *   <li>tag 完全等于 query                 +50</li>
 *   <li>tag 含 query                       +20</li>
 *   <li>description 含 query               +10</li>
 *   <li>steps 任一含 query                 +5</li>
 *   <li>PUBLISHED 额外加分                 +30</li>
 * </ul>
 * 降序排，相同分按 useCount → updatedAt 降序。
 *
 * <h3>兜底（参照 Claude Code {@code HistorySearchDialog.tsx}）</h3>
 * 若精确评分无任何命中（score=0），用两指针子序列匹配（subsequence）对所有技能二次打分，
 * 取前 {@code limit} 条作为「拼写容错」结果，返回结果 {@link Result#fallback} 标识为 "subsequence"。
 */
public final class SkillSearcher {

    private SkillSearcher() {}

    /** 单条搜索结果。 */
    public record Result(Skill skill, int score, String fallback) {
        public Result {
            fallback = fallback != null ? fallback : "exact";
        }
    }

    /**
     * 在候选技能集中按 query 检索，返回前 {@code limit} 条。
     * @param candidates 全量技能（通常来自 {@link SkillStore#listAll()}）
     * @param query      查询字符串（null/blank 时返回空）
     * @param limit      最大返回数量；&lt;=0 视为不限
     */
    public static List<Result> search(List<Skill> candidates, String query, int limit) {
        Objects.requireNonNull(candidates, "candidates");
        if (query == null || query.isBlank()) return new ArrayList<>();
        String q = query.trim().toLowerCase();

        // 第一桶：精确子串评分
        List<Result> scored = new ArrayList<>();
        for (Skill s : candidates) {
            int score = scoreExact(s, q);
            if (score > 0) scored.add(new Result(s, score, "exact"));
        }
        if (!scored.isEmpty()) {
            scored.sort(RESULT_COMPARATOR);
            return applyLimit(scored, limit);
        }

        // 第二桶：子序列兜底（拼写容错）
        List<Result> subseq = new ArrayList<>();
        for (Skill s : candidates) {
            int score = scoreSubsequence(s, q);
            if (score > 0) subseq.add(new Result(s, score, "subsequence"));
        }
        subseq.sort(RESULT_COMPARATOR);
        return applyLimit(subseq, limit);
    }

    // 注意：不能使用 comparingInt(...).reversed().thenComparingInt(...).reversed() 链式，
    // 末尾的 reversed() 会反转整个链（包括前面的 score 顺序）。改用 Comparator.reverseOrder()
    // 作为 comparing 的第二参数，每段独立反转。
    private static final Comparator<Result> RESULT_COMPARATOR =
            Comparator.comparing(Result::score, Comparator.reverseOrder())
                    .thenComparing(r -> r.skill().getUseCount(), Comparator.reverseOrder())
                    .thenComparing(r -> r.skill().getUpdatedAt(),
                            Comparator.nullsLast(Comparator.reverseOrder()));

    private static List<Result> applyLimit(List<Result> list, int limit) {
        if (limit <= 0 || list.size() <= limit) return list;
        return new ArrayList<>(list.subList(0, limit));
    }

    private static int scoreExact(Skill s, String qLower) {
        int score = 0;
        if (s.getName() != null && s.getName().toLowerCase().contains(qLower)) score += 100;
        if (s.getTags() != null) {
            for (String tag : s.getTags()) {
                if (tag == null) continue;
                String t = tag.toLowerCase();
                if (t.equals(qLower)) score += 50;
                else if (t.contains(qLower)) score += 20;
            }
        }
        if (s.getDescription() != null && s.getDescription().toLowerCase().contains(qLower)) score += 10;
        if (s.getSteps() != null) {
            for (String step : s.getSteps()) {
                if (step != null && step.toLowerCase().contains(qLower)) {
                    score += 5;
                    break;
                }
            }
        }
        if (s.getStatus() == SkillStatus.PUBLISHED) score += 30;
        return score;
    }

    /** 两指针子序列匹配（O(n)）：query 的字符按顺序出现在字段中即可。 */
    private static int scoreSubsequence(Skill s, String qLower) {
        int best = 0;
        if (s.getName() != null && isSubsequence(qLower, s.getName().toLowerCase())) best = Math.max(best, 30);
        if (s.getTags() != null) {
            for (String tag : s.getTags()) {
                if (tag != null && isSubsequence(qLower, tag.toLowerCase())) {
                    best = Math.max(best, 15);
                    break;
                }
            }
        }
        if (s.getDescription() != null && isSubsequence(qLower, s.getDescription().toLowerCase())) {
            best = Math.max(best, 8);
        }
        return best;
    }

    private static boolean isSubsequence(String needle, String haystack) {
        int i = 0;
        for (int j = 0; i < needle.length() && j < haystack.length(); j++) {
            if (needle.charAt(i) == haystack.charAt(j)) i++;
        }
        return i == needle.length();
    }
}
