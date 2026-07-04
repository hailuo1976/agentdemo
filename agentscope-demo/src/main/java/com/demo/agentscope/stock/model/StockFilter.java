package com.demo.agentscope.stock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 多维筛选条件，支持任意组合。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockFilter {
    
    @JsonProperty("market_cap_min")
    private Double marketCapMin;
    
    @JsonProperty("market_cap_max")
    private Double marketCapMax;
    
    @JsonProperty("pe_min")
    private Double peMin;
    
    @JsonProperty("pe_max")
    private Double peMax;
    
    @JsonProperty("pb_min")
    private Double pbMin;
    
    @JsonProperty("pb_max")
    private Double pbMax;
    
    @JsonProperty("roe_min")
    private Double roeMin;
    
    @JsonProperty("roe_years")
    private Integer roeYears;           // ROE连续达标年数
    
    @JsonProperty("revenue_growth_min")
    private Double revenueGrowthMin;
    
    @JsonProperty("profit_growth_min")
    private Double profitGrowthMin;
    
    @JsonProperty("exclude_st")
    private Boolean excludeSt = true;   // 默认排除ST股
    
    // 默认构造函数
    public StockFilter() {
    }
    
    // Getters and Setters
    public Double getMarketCapMin() {
        return marketCapMin;
    }
    
    public void setMarketCapMin(Double marketCapMin) {
        this.marketCapMin = marketCapMin;
    }
    
    public Double getMarketCapMax() {
        return marketCapMax;
    }
    
    public void setMarketCapMax(Double marketCapMax) {
        this.marketCapMax = marketCapMax;
    }
    
    public Double getPeMin() {
        return peMin;
    }
    
    public void setPeMin(Double peMin) {
        this.peMin = peMin;
    }
    
    public Double getPeMax() {
        return peMax;
    }
    
    public void setPeMax(Double peMax) {
        this.peMax = peMax;
    }
    
    public Double getPbMin() {
        return pbMin;
    }
    
    public void setPbMin(Double pbMin) {
        this.pbMin = pbMin;
    }
    
    public Double getPbMax() {
        return pbMax;
    }
    
    public void setPbMax(Double pbMax) {
        this.pbMax = pbMax;
    }
    
    public Double getRoeMin() {
        return roeMin;
    }
    
    public void setRoeMin(Double roeMin) {
        this.roeMin = roeMin;
    }
    
    public Integer getRoeYears() {
        return roeYears;
    }
    
    public void setRoeYears(Integer roeYears) {
        this.roeYears = roeYears;
    }
    
    public Double getRevenueGrowthMin() {
        return revenueGrowthMin;
    }
    
    public void setRevenueGrowthMin(Double revenueGrowthMin) {
        this.revenueGrowthMin = revenueGrowthMin;
    }
    
    public Double getProfitGrowthMin() {
        return profitGrowthMin;
    }
    
    public void setProfitGrowthMin(Double profitGrowthMin) {
        this.profitGrowthMin = profitGrowthMin;
    }
    
    public Boolean getExcludeSt() {
        return excludeSt;
    }
    
    public void setExcludeSt(Boolean excludeSt) {
        this.excludeSt = excludeSt;
    }
    
    /**
     * Builder模式，支持链式调用
     */
    public static class Builder {
        private final StockFilter filter;
        
        public Builder() {
            this.filter = new StockFilter();
        }
        
        public Builder marketCapMin(Double marketCapMin) {
            filter.setMarketCapMin(marketCapMin);
            return this;
        }
        
        public Builder marketCapMax(Double marketCapMax) {
            filter.setMarketCapMax(marketCapMax);
            return this;
        }
        
        public Builder peMin(Double peMin) {
            filter.setPeMin(peMin);
            return this;
        }
        
        public Builder peMax(Double peMax) {
            filter.setPeMax(peMax);
            return this;
        }
        
        public Builder pbMin(Double pbMin) {
            filter.setPbMin(pbMin);
            return this;
        }
        
        public Builder pbMax(Double pbMax) {
            filter.setPbMax(pbMax);
            return this;
        }
        
        public Builder roeMin(Double roeMin) {
            filter.setRoeMin(roeMin);
            return this;
        }
        
        public Builder roeYears(Integer roeYears) {
            filter.setRoeYears(roeYears);
            return this;
        }
        
        public Builder revenueGrowthMin(Double revenueGrowthMin) {
            filter.setRevenueGrowthMin(revenueGrowthMin);
            return this;
        }
        
        public Builder profitGrowthMin(Double profitGrowthMin) {
            filter.setProfitGrowthMin(profitGrowthMin);
            return this;
        }
        
        public Builder excludeSt(Boolean excludeSt) {
            filter.setExcludeSt(excludeSt);
            return this;
        }
        
        public StockFilter build() {
            return filter;
        }
    }
}
