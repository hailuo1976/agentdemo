package com.demo.agentscope.stock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 股票核心实体，承载完整的股票指标数据。
 * 用于龙头评分、多维筛选、详情展示。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockEntity {
    
    // 基础信息
    @JsonProperty("code")
    private String code;              // 6位股票代码
    
    @JsonProperty("name")
    private String name;              // 股票名称
    
    @JsonProperty("industry_l1")
    private String industryL1;        // 申万一级行业
    
    @JsonProperty("industry_l2")
    private String industryL2;        // 申万二级行业
    
    @JsonProperty("industry_l3")
    private String industryL3;        // 申万三级行业
    
    // 市场指标
    @JsonProperty("market_cap")
    private double marketCap;         // 总市值（亿元）
    
    @JsonProperty("pe_ttm")
    private double peTtm;             // TTM市盈率
    
    @JsonProperty("pb")
    private double pb;                // 市净率
    
    @JsonProperty("roe")
    private double roe;               // 净资产收益率（%）
    
    @JsonProperty("roe_history_3y")
    private List<Double> roeHistory3y; // 近3年ROE历史
    
    // 增长指标
    @JsonProperty("revenue_growth")
    private double revenueGrowth;     // 营收增速（%）
    
    @JsonProperty("profit_growth")
    private double profitGrowth;      // 净利润增速（%）
    
    // 评分相关
    @JsonProperty("leader_score")
    private double leaderScore;       // 龙头评分（0-100）
    
    @JsonProperty("score_components")
    private Map<String, Double> scoreComponents; // 各维度子分
    
    // 元数据
    @JsonProperty("is_ex_right")
    private boolean isExRight;        // 是否除权除息期
    
    @JsonProperty("data_source")
    private String dataSource;        // 数据源（akshare/tushare/cache）
    
    @JsonProperty("updated_at")
    private Instant updatedAt;        // 数据更新时间
    
    @JsonProperty("data_freshness")
    private String dataFreshness;     // 新鲜度等级
    
    // 默认构造函数（Jackson需要）
    public StockEntity() {
    }
    
    public StockEntity(String code, String name) {
        this.code = code;
        this.name = name;
    }
    
    // Getters and Setters
    public String getCode() {
        return code;
    }
    
    public void setCode(String code) {
        this.code = code;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getIndustryL1() {
        return industryL1;
    }
    
    public void setIndustryL1(String industryL1) {
        this.industryL1 = industryL1;
    }
    
    public String getIndustryL2() {
        return industryL2;
    }
    
    public void setIndustryL2(String industryL2) {
        this.industryL2 = industryL2;
    }
    
    public String getIndustryL3() {
        return industryL3;
    }
    
    public void setIndustryL3(String industryL3) {
        this.industryL3 = industryL3;
    }
    
    public double getMarketCap() {
        return marketCap;
    }
    
    public void setMarketCap(double marketCap) {
        this.marketCap = marketCap;
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
    
    public List<Double> getRoeHistory3y() {
        return roeHistory3y;
    }
    
    public void setRoeHistory3y(List<Double> roeHistory3y) {
        this.roeHistory3y = roeHistory3y;
    }
    
    public double getRevenueGrowth() {
        return revenueGrowth;
    }
    
    public void setRevenueGrowth(double revenueGrowth) {
        this.revenueGrowth = revenueGrowth;
    }
    
    public double getProfitGrowth() {
        return profitGrowth;
    }
    
    public void setProfitGrowth(double profitGrowth) {
        this.profitGrowth = profitGrowth;
    }
    
    public double getLeaderScore() {
        return leaderScore;
    }
    
    public void setLeaderScore(double leaderScore) {
        this.leaderScore = leaderScore;
    }
    
    public Map<String, Double> getScoreComponents() {
        return scoreComponents;
    }
    
    public void setScoreComponents(Map<String, Double> scoreComponents) {
        this.scoreComponents = scoreComponents;
    }
    
    public boolean isExRight() {
        return isExRight;
    }
    
    public void setExRight(boolean exRight) {
        isExRight = exRight;
    }
    
    public String getDataSource() {
        return dataSource;
    }
    
    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public String getDataFreshness() {
        return dataFreshness;
    }
    
    public void setDataFreshness(String dataFreshness) {
        this.dataFreshness = dataFreshness;
    }
}
