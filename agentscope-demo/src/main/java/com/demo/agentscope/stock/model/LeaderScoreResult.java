package com.demo.agentscope.stock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 龙头评分结果，包含各维度子分。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeaderScoreResult {
    
    // 权重配置（从 workspace/config/scoring_weights.json 加载）
    public static final double WEIGHT_MARKET_CAP = 0.40;
    public static final double WEIGHT_REVENUE = 0.25;
    public static final double WEIGHT_ROE = 0.20;
    public static final double WEIGHT_BRAND = 0.15;
    
    @JsonProperty("code")
    private String code;
    
    @JsonProperty("total_score")
    private double totalScore;          // 总分（0-100）
    
    @JsonProperty("market_cap_score")
    private double marketCapScore;      // 市值分
    
    @JsonProperty("revenue_score")
    private double revenueScore;        // 营收分
    
    @JsonProperty("roe_score")
    private double roeScore;            // ROE分
    
    @JsonProperty("brand_score")
    private double brandScore;          // 品牌度分
    
    // 默认构造函数
    public LeaderScoreResult() {
    }
    
    public LeaderScoreResult(String code, double totalScore, double marketCapScore, 
                            double revenueScore, double roeScore, double brandScore) {
        this.code = code;
        this.totalScore = totalScore;
        this.marketCapScore = marketCapScore;
        this.revenueScore = revenueScore;
        this.roeScore = roeScore;
        this.brandScore = brandScore;
    }
    
    // Getters and Setters
    public String getCode() {
        return code;
    }
    
    public void setCode(String code) {
        this.code = code;
    }
    
    public double getTotalScore() {
        return totalScore;
    }
    
    public void setTotalScore(double totalScore) {
        this.totalScore = totalScore;
    }
    
    public double getMarketCapScore() {
        return marketCapScore;
    }
    
    public void setMarketCapScore(double marketCapScore) {
        this.marketCapScore = marketCapScore;
    }
    
    public double getRevenueScore() {
        return revenueScore;
    }
    
    public void setRevenueScore(double revenueScore) {
        this.revenueScore = revenueScore;
    }
    
    public double getRoeScore() {
        return roeScore;
    }
    
    public void setRoeScore(double roeScore) {
        this.roeScore = roeScore;
    }
    
    public double getBrandScore() {
        return brandScore;
    }
    
    public void setBrandScore(double brandScore) {
        this.brandScore = brandScore;
    }
}
