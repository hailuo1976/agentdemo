package com.demo.agentscope.stock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * 缓存条目，按数据类型分段存储，每段独立TTL。
 * 落盘到 workspace/cache/stocks/{code}.json
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CachedStockEntry {
    
    @JsonProperty("code")
    private String code;
    
    @JsonProperty("quote")
    private QuoteData quote;              // 实时行情（TTL: 1h）
    
    @JsonProperty("fundamental")
    private FundamentalData fundamental;  // 基本面（TTL: 24h）
    
    @JsonProperty("industry_rank")
    private IndustryRankData industryRank; // 行业排名（TTL: 7d）
    
    // 默认构造函数
    public CachedStockEntry() {
    }
    
    public CachedStockEntry(String code) {
        this.code = code;
    }
    
    /**
     * 检查指定数据类型是否过期。
     */
    public boolean isExpired(String dataType, Duration ttl) {
        Instant updateTime = switch (dataType) {
            case "quote" -> quote != null ? quote.getUpdatedAt() : null;
            case "fundamental" -> fundamental != null ? fundamental.getUpdatedAt() : null;
            case "industry_rank" -> industryRank != null ? industryRank.getUpdatedAt() : null;
            default -> null;
        };
        if (updateTime == null) return true;
        return Duration.between(updateTime, Instant.now()).compareTo(ttl) > 0;
    }
    
    // Getters and Setters
    public String getCode() {
        return code;
    }
    
    public void setCode(String code) {
        this.code = code;
    }
    
    public QuoteData getQuote() {
        return quote;
    }
    
    public void setQuote(QuoteData quote) {
        this.quote = quote;
    }
    
    public FundamentalData getFundamental() {
        return fundamental;
    }
    
    public void setFundamental(FundamentalData fundamental) {
        this.fundamental = fundamental;
    }
    
    public IndustryRankData getIndustryRank() {
        return industryRank;
    }
    
    public void setIndustryRank(IndustryRankData industryRank) {
        this.industryRank = industryRank;
    }
    
    /**
     * 实时行情数据
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QuoteData {
        @JsonProperty("price")
        private double price;
        
        @JsonProperty("change_pct")
        private double changePct;
        
        @JsonProperty("volume")
        private long volume;
        
        @JsonProperty("updated_at")
        private Instant updatedAt;
        
        @JsonProperty("data_source")
        private String dataSource; // akshare / tushare
        
        public QuoteData() {
        }
        
        public double getPrice() {
            return price;
        }
        
        public void setPrice(double price) {
            this.price = price;
        }
        
        public double getChangePct() {
            return changePct;
        }
        
        public void setChangePct(double changePct) {
            this.changePct = changePct;
        }
        
        public long getVolume() {
            return volume;
        }
        
        public void setVolume(long volume) {
            this.volume = volume;
        }
        
        public Instant getUpdatedAt() {
            return updatedAt;
        }
        
        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
        
        public String getDataSource() {
            return dataSource;
        }
        
        public void setDataSource(String dataSource) {
            this.dataSource = dataSource;
        }
    }
    
    /**
     * 基本面数据
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FundamentalData {
        @JsonProperty("pe_ttm")
        private double peTtm;
        
        @JsonProperty("pb")
        private double pb;
        
        @JsonProperty("roe")
        private double roe;
        
        @JsonProperty("market_cap")
        private double marketCap;
        
        @JsonProperty("updated_at")
        private Instant updatedAt;
        
        @JsonProperty("data_source")
        private String dataSource;
        
        public FundamentalData() {
        }
        
        public double getPeTtm() {
            return peTtm;
        }
        
        public void setPeTtm(double peTtm) {
            this.peTtm = peTtm;
        }
        
        public double getPb() {
            return pb;
        }
        
        public void setPb(double pb) {
            this.pb = pb;
        }
        
        public double getRoe() {
            return roe;
        }
        
        public void setRoe(double roe) {
            this.roe = roe;
        }
        
        public double getMarketCap() {
            return marketCap;
        }
        
        public void setMarketCap(double marketCap) {
            this.marketCap = marketCap;
        }
        
        public Instant getUpdatedAt() {
            return updatedAt;
        }
        
        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
        
        public String getDataSource() {
            return dataSource;
        }
        
        public void setDataSource(String dataSource) {
            this.dataSource = dataSource;
        }
    }
    
    /**
     * 行业排名数据
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndustryRankData {
        @JsonProperty("rank_in_l2")
        private int rankInL2;
        
        @JsonProperty("total_in_l2")
        private int totalInL2;
        
        @JsonProperty("updated_at")
        private Instant updatedAt;
        
        public IndustryRankData() {
        }
        
        public int getRankInL2() {
            return rankInL2;
        }
        
        public void setRankInL2(int rankInL2) {
            this.rankInL2 = rankInL2;
        }
        
        public int getTotalInL2() {
            return totalInL2;
        }
        
        public void setTotalInL2(int totalInL2) {
            this.totalInL2 = totalInL2;
        }
        
        public Instant getUpdatedAt() {
            return updatedAt;
        }
        
        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
