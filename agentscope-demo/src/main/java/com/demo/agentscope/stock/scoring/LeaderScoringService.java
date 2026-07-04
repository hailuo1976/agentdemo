package com.demo.agentscope.stock.scoring;

import com.demo.agentscope.stock.model.LeaderScoreResult;
import com.demo.agentscope.stock.model.StockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 龙头评分服务。
 * 实现多因子加权评分算法，行业内 z-score 归一化。
 */
public class LeaderScoringService {

    private static final Logger log = LoggerFactory.getLogger(LeaderScoringService.class);

    /**
     * 计算行业内全部股票的龙头评分。
     */
    public List<LeaderScoreResult> computeLeaderScores(List<StockEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 提取各维度原始数据
        List<Double> marketCaps = new ArrayList<>();
        List<Double> revenues = new ArrayList<>();
        List<Double> roes = new ArrayList<>();
        List<Double> brands = new ArrayList<>();

        for (StockEntity entity : entities) {
            marketCaps.add(entity.getMarketCap());
            revenues.add(entity.getRevenueGrowth());
            roes.add(entity.getRoe());
            brands.add(heuristicBrandScore(entity));
        }

        // 2. z-score 归一化（log 抑制长尾）
        List<Double> capScores = toScore(normalize(logTransform(marketCaps)));
        List<Double> revScores = toScore(normalize(logTransform(revenues)));
        List<Double> roeScores = toScore(normalize(roes));
        List<Double> brandScores = toScore(normalize(brands));

        // 3. 加权求和
        List<LeaderScoreResult> results = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            StockEntity entity = entities.get(i);
            double totalScore = LeaderScoreResult.WEIGHT_MARKET_CAP * capScores.get(i)
                              + LeaderScoreResult.WEIGHT_REVENUE * revScores.get(i)
                              + LeaderScoreResult.WEIGHT_ROE * roeScores.get(i)
                              + LeaderScoreResult.WEIGHT_BRAND * brandScores.get(i);

            LeaderScoreResult result = new LeaderScoreResult(
                entity.getCode(),
                totalScore,
                capScores.get(i),
                revScores.get(i),
                roeScores.get(i),
                brandScores.get(i)
            );
            results.add(result);

            // 更新 StockEntity
            entity.setLeaderScore(totalScore);
            Map<String, Double> scoreComponents = new HashMap<>();
            scoreComponents.put("market_cap_score", capScores.get(i));
            scoreComponents.put("revenue_score", revScores.get(i));
            scoreComponents.put("roe_score", roeScores.get(i));
            scoreComponents.put("brand_score", brandScores.get(i));
            entity.setScoreComponents(scoreComponents);
        }

        // 4. 按总分降序排序
        results.sort((a, b) -> Double.compare(b.getTotalScore(), a.getTotalScore()));
        return results;
    }

    /**
     * z-score 归一化。
     */
    private List<Double> normalize(List<Double> values) {
        if (values.isEmpty()) return values;

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double std = Math.sqrt(values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average().orElse(0));

        if (std == 0) return values.stream().map(v -> 0.0).toList();

        return values.stream()
            .map(v -> (v - mean) / std)
            .toList();
    }

    /**
     * 对数变换（抑制长尾效应）。
     */
    private List<Double> logTransform(List<Double> values) {
        return values.stream()
            .map(v -> Math.log(Math.max(v, 1)))
            .toList();
    }

    /**
     * z-score 转为 0-100 分。
     * ±3σ 映射到 [0, 100]
     */
    private List<Double> toScore(List<Double> zScores) {
        return zScores.stream()
            .map(z -> Math.max(0, Math.min(100, 50 + z * 16.67)))
            .toList();
    }

    /**
     * 品牌度启发式评分。
     */
    private double heuristicBrandScore(StockEntity entity) {
        double score = 0;

        // 名称含"中国"/"中华"
        if (entity.getName() != null && (entity.getName().contains("中国") || entity.getName().contains("中华"))) {
            score += 5;
        }

        // 市值越大，品牌度越高（简化处理）
        if (entity.getMarketCap() > 1000) { // 大于1000亿
            score += 30;
        } else if (entity.getMarketCap() > 500) {
            score += 20;
        } else if (entity.getMarketCap() > 100) {
            score += 10;
        }

        return score;
    }
}
