package com.demo.agentscope.stock.filter;

import com.demo.agentscope.stock.model.StockEntity;
import com.demo.agentscope.stock.model.StockFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 股票筛选服务。
 * 支持多维筛选条件任意组合。
 */
public class StockFilterService {

    private static final Logger log = LoggerFactory.getLogger(StockFilterService.class);

    /**
     * 应用多维筛选条件。
     */
    public List<StockEntity> applyFilters(List<StockEntity> entities, StockFilter filter) {
        if (entities == null || entities.isEmpty()) {
            return entities;
        }
        if (filter == null) {
            return entities;
        }

        return entities.stream()
            .filter(e -> matchesFilter(e, filter))
            .collect(Collectors.toList());
    }

    /**
     * 检查单只股票是否满足筛选条件。
     */
    private boolean matchesFilter(StockEntity entity, StockFilter filter) {
        // 市值区间
        if (filter.getMarketCapMin() != null && entity.getMarketCap() < filter.getMarketCapMin()) {
            return false;
        }
        if (filter.getMarketCapMax() != null && entity.getMarketCap() > filter.getMarketCapMax()) {
            return false;
        }

        // PE 区间
        if (filter.getPeMin() != null && entity.getPeTtm() < filter.getPeMin()) {
            return false;
        }
        if (filter.getPeMax() != null && entity.getPeTtm() > filter.getPeMax()) {
            return false;
        }

        // PB 区间
        if (filter.getPbMin() != null && entity.getPb() < filter.getPbMin()) {
            return false;
        }
        if (filter.getPbMax() != null && entity.getPb() > filter.getPbMax()) {
            return false;
        }

        // ROE 下限
        if (filter.getRoeMin() != null && entity.getRoe() < filter.getRoeMin()) {
            return false;
        }

        // ROE 连续 N 年达标
        if (filter.getRoeYears() != null && filter.getRoeYears() > 1) {
            List<Double> roeHistory = entity.getRoeHistory3y();
            if (roeHistory == null || roeHistory.size() < filter.getRoeYears()) {
                return false;
            }
            for (int i = 0; i < filter.getRoeYears(); i++) {
                if (roeHistory.get(i) < filter.getRoeMin()) {
                    return false;
                }
            }
        }

        // 营收增速下限
        if (filter.getRevenueGrowthMin() != null && entity.getRevenueGrowth() < filter.getRevenueGrowthMin()) {
            return false;
        }

        // 净利润增速下限
        if (filter.getProfitGrowthMin() != null && entity.getProfitGrowth() < filter.getProfitGrowthMin()) {
            return false;
        }

        // 排除 ST 股
        if (filter.getExcludeSt() != null && filter.getExcludeSt()) {
            if (entity.getName() != null && entity.getName().contains("ST")) {
                return false;
            }
        }

        return true;
    }
}
