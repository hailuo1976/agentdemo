package com.demo.agentscope.stock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 申万行业分类树节点。
 * 支持三级结构：一级（31个）→ 二级（134个）→ 三级（386个）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndustryNode {
    
    @JsonProperty("code")
    private String code;        // 行业代码（如 801080）
    
    @JsonProperty("name")
    private String name;        // 行业名称（如 "电子"）
    
    @JsonProperty("level")
    private int level;          // 层级（1/2/3）
    
    @JsonProperty("children")
    private List<IndustryNode> children; // 子行业
    
    @JsonProperty("stock_codes")
    private List<String> stockCodes; // 该行业下的股票代码列表
    
    // 默认构造函数
    public IndustryNode() {
        this.children = new ArrayList<>();
        this.stockCodes = new ArrayList<>();
    }
    
    public IndustryNode(String code, String name, int level) {
        this.code = code;
        this.name = name;
        this.level = level;
        this.children = new ArrayList<>();
        this.stockCodes = new ArrayList<>();
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
    
    public int getLevel() {
        return level;
    }
    
    public void setLevel(int level) {
        this.level = level;
    }
    
    public List<IndustryNode> getChildren() {
        return children;
    }
    
    public void setChildren(List<IndustryNode> children) {
        this.children = children;
    }
    
    public List<String> getStockCodes() {
        return stockCodes;
    }
    
    public void setStockCodes(List<String> stockCodes) {
        this.stockCodes = stockCodes;
    }
    
    /**
     * 添加子行业节点
     */
    public void addChild(IndustryNode child) {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        this.children.add(child);
    }
    
    /**
     * 添加股票代码
     */
    public void addStockCode(String stockCode) {
        if (this.stockCodes == null) {
            this.stockCodes = new ArrayList<>();
        }
        this.stockCodes.add(stockCode);
    }
}
