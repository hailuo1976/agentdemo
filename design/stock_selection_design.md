# 股票选择功能详细设计文档

> 版本：v1.2
> 日期：2026-07-04
> 基于：`design/stock_selection_prd.md`
> 状态：已对齐代码实现（v1.1 → v1.2 修正点见附录 A，共 7 类约 20 处）

---

## 0. 设计概述

本文档基于 PRD 需求，按模块输出详细设计，包含：数据模型层、行业服务层、数据服务层、评分服务层、筛选服务层、工具集成层、系统集成层。

### 0.1 核心特性

1. **双数据源策略**：akshare（主）+ tushare（备），自动降级
2. **三级兜底初始化**：缓存 → akshare → tushare → 内置 31 个申万一级行业（见 §2.2）
3. **三级缓存**：行情1h、基本面24h、行业排名7d
4. **复合评分**：多因子加权（市值+营收+ROE+品牌度），行业内 z-score 归一化
5. **多维筛选**：PE/PB/ROE/营收增速/市值区间任意组合

### 0.2 分层架构

```
用户输入（自然语言）
    ↓
Agent.reply() — ReAct 循环
    ↓
PermissionMiddleware → 4 条 ALLOW 规则
    ↓
MCPClient → BuiltinToolExecutor（股票工具实现）
    ↓
StockToolService（工具入口）
    ├── IndustryService（行业树解析）
    ├── StockDataService（数据采集 + 缓存）
    │   ├── AkShareDataSource（主数据源）
    │   └── TuShareDataSource（备用数据源）
    ├── LeaderScoringService（评分算法）
    └── StockFilterService（多维筛选）
    ↓
execute_python（akshare/tushare 拉取） + LocalWorkspace（缓存读写）
```

---

## 1. 模块1：数据模型层

### 1.1 StockEntity（核心股票实体）

```java
package com.demo.agentscope.stock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 股票核心实体，承载完整的股票指标数据。
 * 用于龙头评分、多维筛选、详情展示。
 *
 * 实现要点（见 StockEntity.java:1-229）：
 * 1. 类级 @JsonIgnoreProperties(ignoreUnknown = true)，反序列化时忽略未知字段
 * 2. 所有字段使用 @JsonProperty 显式声明 JSON 名称（snake_case）
 * 3. 提供默认构造器 + StockEntity(String code, String name) 构造器
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockEntity {
    // 基础信息
    @JsonProperty("code")              private String code;              // 6位股票代码
    @JsonProperty("name")              private String name;              // 股票名称
    @JsonProperty("industry_l1")       private String industryL1;        // 申万一级行业
    @JsonProperty("industry_l2")       private String industryL2;        // 申万二级行业
    @JsonProperty("industry_l3")       private String industryL3;        // 申万三级行业

    // 市场指标
    @JsonProperty("market_cap")        private double marketCap;         // 总市值（亿元）
    @JsonProperty("pe_ttm")            private double peTtm;             // TTM市盈率
    @JsonProperty("pb")                private double pb;                // 市净率
    @JsonProperty("roe")               private double roe;               // 净资产收益率（%）
    @JsonProperty("roe_history_3y")    private List<Double> roeHistory3y; // 近3年ROE历史

    // 增长指标
    @JsonProperty("revenue_growth")    private double revenueGrowth;     // 营收增速（%）
    @JsonProperty("profit_growth")     private double profitGrowth;      // 净利润增速（%）

    // 评分相关
    @JsonProperty("leader_score")      private double leaderScore;       // 龙头评分（0-100）
    @JsonProperty("score_components")  private Map<String, Double> scoreComponents; // 各维度子分

    // 元数据
    @JsonProperty("is_ex_right")       private boolean isExRight;        // 是否除权除息期
    @JsonProperty("data_source")       private String dataSource;        // 数据源（akshare/tushare/cache）
    @JsonProperty("updated_at")        private Instant updatedAt;        // 数据更新时间
    @JsonProperty("data_freshness")    private String dataFreshness;     // 新鲜度等级

    // Getter/Setter 全部提供（此处省略）
}
```

### 1.2 CachedStockEntry（缓存条目）

```java
/**
 * 缓存条目，按数据类型分段存储，每段独立TTL。
 * 落盘到 workspace/cache/stocks/{code}.json
 *
 * 实现要点（见 CachedStockEntry.java:1-263）：
 * 1. 外层 + 三个内部类均带 @JsonIgnoreProperties(ignoreUnknown = true)
 * 2. 所有字段使用 @JsonProperty
 * 3. isExpired 通过 getter 访问 updatedAt（quote.getUpdatedAt()），
 *    而非直接字段访问，确保 JSON 反序列化后字段可达
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CachedStockEntry {
    @JsonProperty("code")           private String code;
    @JsonProperty("quote")          private QuoteData quote;              // 实时行情（TTL: 1h）
    @JsonProperty("fundamental")    private FundamentalData fundamental;  // 基本面（TTL: 24h）
    @JsonProperty("industry_rank")  private IndustryRankData industryRank; // 行业排名（TTL: 7d）

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QuoteData {
        @JsonProperty("price")        private double price;
        @JsonProperty("change_pct")   private double changePct;
        @JsonProperty("volume")       private long volume;
        @JsonProperty("updated_at")   private Instant updatedAt;
        @JsonProperty("data_source")  private String dataSource; // akshare / tushare
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FundamentalData {
        @JsonProperty("pe_ttm")       private double peTtm;
        @JsonProperty("pb")           private double pb;
        @JsonProperty("roe")          private double roe;
        @JsonProperty("market_cap")   private double marketCap;
        @JsonProperty("updated_at")   private Instant updatedAt;
        @JsonProperty("data_source")  private String dataSource;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndustryRankData {
        @JsonProperty("rank_in_l2")   private int rankInL2;
        @JsonProperty("total_in_l2")  private int totalInL2;
        @JsonProperty("updated_at")   private Instant updatedAt;
    }

    /**
     * 检查指定数据类型是否过期。
     * 注意：通过 getter 访问 updatedAt，规避序列化/反射下的字段可见性问题。
     */
    public boolean isExpired(String dataType, Duration ttl) {
        Instant updateTime = switch (dataType) {
            case "quote"         -> quote != null ? quote.getUpdatedAt() : null;
            case "fundamental"   -> fundamental != null ? fundamental.getUpdatedAt() : null;
            case "industry_rank" -> industryRank != null ? industryRank.getUpdatedAt() : null;
            default -> null;
        };
        if (updateTime == null) return true;
        return Duration.between(updateTime, Instant.now()).compareTo(ttl) > 0;
    }
}
```

### 1.3 IndustryNode（行业树节点）

```java
/**
 * 申万行业分类树节点。
 * 支持三级结构：一级（31个）→ 二级（134个）→ 三级（386个）
 *
 * 实现要点（见 IndustryNode.java:1-105）：
 * 1. @JsonIgnoreProperties(ignoreUnknown = true)，从容错 JSON 中解析
 * 2. 字段全部 @JsonProperty
 * 3. 提供两个构造器：默认构造器（初始化空列表）+ 参数化构造器
 * 4. 提供 addChild(IndustryNode) 与 addStockCode(String) 便捷方法
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndustryNode {
    @JsonProperty("code")        private String code;        // 行业代码（如 801080）
    @JsonProperty("name")        private String name;        // 行业名称（如 "电子"）
    @JsonProperty("level")       private int level;          // 层级（1/2/3）
    @JsonProperty("children")    private List<IndustryNode> children; // 子行业
    @JsonProperty("stock_codes") private List<String> stockCodes;     // 该行业下的股票代码列表

    public IndustryNode() {
        this.children = new ArrayList<>();
        this.stockCodes = new ArrayList<>();
    }

    public IndustryNode(String code, String name, int level) { /* ... */ }

    /** 添加子行业（自动初始化 children 列表） */
    public void addChild(IndustryNode child) {
        if (this.children == null) { this.children = new ArrayList<>(); }
        this.children.add(child);
    }

    /** 添加股票代码（自动初始化 stockCodes 列表） */
    public void addStockCode(String stockCode) {
        if (this.stockCodes == null) { this.stockCodes = new ArrayList<>(); }
        this.stockCodes.add(stockCode);
    }
}
```

### 1.4 StockFilter（筛选条件）

```java
/**
 * 多维筛选条件，支持任意组合。
 *
 * 实现要点（见 StockFilter.java:1-207）：
 * 1. @JsonIgnoreProperties(ignoreUnknown = true)
 * 2. 字段全部 @JsonProperty
 * 3. 完整 Builder 模式（11 个链式 setter）：
 *    marketCapMin/marketCapMax/peMin/peMax/pbMin/pbMax/
 *    roeMin/roeYears/revenueGrowthMin/profitGrowthMin/excludeSt
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockFilter {
    @JsonProperty("market_cap_min")    private Double marketCapMin;
    @JsonProperty("market_cap_max")    private Double marketCapMax;
    @JsonProperty("pe_min")            private Double peMin;
    @JsonProperty("pe_max")            private Double peMax;
    @JsonProperty("pb_min")            private Double pbMin;
    @JsonProperty("pb_max")            private Double pbMax;
    @JsonProperty("roe_min")           private Double roeMin;
    @JsonProperty("roe_years")         private Integer roeYears;          // ROE连续达标年数
    @JsonProperty("revenue_growth_min") private Double revenueGrowthMin;
    @JsonProperty("profit_growth_min") private Double profitGrowthMin;
    @JsonProperty("exclude_st")        private Boolean excludeSt = true;  // 默认排除ST股

    public static class Builder {
        private final StockFilter filter;
        public Builder() { this.filter = new StockFilter(); }
        public Builder marketCapMin(Double v)    { filter.setMarketCapMin(v);    return this; }
        public Builder marketCapMax(Double v)    { filter.setMarketCapMax(v);    return this; }
        public Builder peMin(Double v)           { filter.setPeMin(v);           return this; }
        public Builder peMax(Double v)           { filter.setPeMax(v);           return this; }
        public Builder pbMin(Double v)           { filter.setPbMin(v);           return this; }
        public Builder pbMax(Double v)           { filter.setPbMax(v);           return this; }
        public Builder roeMin(Double v)          { filter.setRoeMin(v);          return this; }
        public Builder roeYears(Integer v)       { filter.setRoeYears(v);        return this; }
        public Builder revenueGrowthMin(Double v){ filter.setRevenueGrowthMin(v);return this; }
        public Builder profitGrowthMin(Double v) { filter.setProfitGrowthMin(v); return this; }
        public Builder excludeSt(Boolean v)      { filter.setExcludeSt(v);       return this; }
        public StockFilter build() { return filter; }
    }
}
```

### 1.5 LeaderScoreResult（评分结果）

```java
/**
 * 龙头评分结果，包含各维度子分。
 *
 * 实现要点（见 LeaderScoreResult.java:1-98）：
 * 1. @JsonIgnoreProperties(ignoreUnknown = true)
 * 2. 权重为 public static final 常量（直接暴露给 LeaderScoringService 使用）
 * 3. **不存在 scoring_weights.json 配置文件**——权重为硬编码常量，
 *    如需调整需修改源码并重新编译
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeaderScoreResult {
    @JsonProperty("code")             private String code;
    @JsonProperty("total_score")      private double totalScore;      // 总分（0-100）
    @JsonProperty("market_cap_score") private double marketCapScore; // 市值分
    @JsonProperty("revenue_score")    private double revenueScore;   // 营收分
    @JsonProperty("roe_score")        private double roeScore;       // ROE分
    @JsonProperty("brand_score")      private double brandScore;     // 品牌度分

    // 权重常量（public，供 LeaderScoringService 直接引用）
    public static final double WEIGHT_MARKET_CAP = 0.40;
    public static final double WEIGHT_REVENUE    = 0.25;
    public static final double WEIGHT_ROE        = 0.20;
    public static final double WEIGHT_BRAND      = 0.15;
}
```

---

## 2. 模块2：行业服务层（IndustryService）

### 2.1 职责

负责申万行业分类树的加载、解析、查询。支持三级结构（一级31个、二级134个、三级386个），提供行业代码↔名称映射、父子关系查询、模糊匹配等能力。

### 2.2 核心类：IndustryService

```java
package com.demo.agentscope.stock.industry;

public class IndustryService {
    
    /** 行业树根节点（内存缓存） */
    private List<IndustryNode> industryTree;
    
    /** 行业代码→名称映射（快速查找） */
    private Map<String, String> codeToNameMap;
    
    /** 行业名称→代码映射（支持模糊匹配） */
    private Map<String, String> nameToCodeMap;
    
    /** 代码执行管理器 */
    private final CodeExecutionManager executionManager;
    
    /** 缓存目录 */
    private final Path cacheDir;
    
    /** Jackson ObjectMapper */
    private final ObjectMapper objectMapper;

    /**
     * 初始化：三级兜底策略（见 IndustryService.java:56-78）。
     *  1) workspace/cache/industry_tree.json 存在 → 直接加载本地缓存
     *  2) 缓存不存在 → 尝试 akshare 拉取；失败再尝试 tushare
     *  3) 以上全部失败 → 降级到内置 31 个申万一级行业（loadBuiltinIndustryTree）
     *
     * 任何一级失败都不阻断应用启动。
     */
    public void initialize() {
        Path treeFile = cacheDir.resolve("industry_tree.json");

        if (Files.exists(treeFile)) {
            try {
                loadFromCache(treeFile);
                log.info("行业树从缓存加载，共 {} 个一级行业", industryTree.size());
                return;
            } catch (Exception e) {
                log.warn("从缓存加载行业树失败: {}，将尝试重新拉取", e.getMessage());
            }
        }

        try {
            fetchAndCacheIndustryTree();
        } catch (Exception e) {
            log.error("行业树初始化失败，将使用内置行业数据: {}", e.getMessage());
            log.error("提示: 可通过 pip install akshare 或 pip install tushare 安装数据源后重试");
            loadBuiltinIndustryTree();
        }
    }

    /**
     * 加载内置的申万一级行业树（兜底数据，见 IndustryService.java:84-117）。
     * 当网络不可用且无缓存时使用，硬编码 31 个申万一级行业。
     */
    private void loadBuiltinIndustryTree() {
        // 31 个一级行业硬编码：801010 农林牧渔 / 801020 采掘 / ... / 801970 环保
        // 每个节点 level=1，children 为空列表
        // 同时填充 codeToNameMap 与 nameToCodeMap
    }
    
    /**
     * 从数据源拉取行业树并缓存。
     * 支持 akshare 和 tushare 两种数据源。
     */
    public void fetchAndCacheIndustryTree() {
        // 优先使用 akshare
        try {
            fetchFromAkShare();
            log.info("行业树从 akshare 拉取成功");
            return;
        } catch (Exception e) {
            log.warn("akshare 拉取失败: {}，尝试 tushare", e.getMessage());
        }
        
        // 降级到 tushare
        try {
            fetchFromTuShare();
            log.info("行业树从 tushare 拉取成功");
        } catch (Exception e) {
            log.error("tushare 拉取也失败", e);
            throw new RuntimeException("行业树初始化失败：akshare 和 tushare 均不可用");
        }
    }
    
    /**
     * 从 akshare 拉取行业树。
     * 注意：fetchFromAkShare / fetchFromTuShare 为 public，便于命令行/测试单独调用。
     */
    public void fetchFromAkShare() {
        String pythonCode = """
            import akshare as ak
            import json
            
            # 一级行业
            df_l1 = ak.stock_board_industry_name_em()
            l1_list = []
            for _, row in df_l1.iterrows():
                l1_list.append({
                    "code": str(row['板块代码']),
                    "name": row['板块名称'],
                    "level": 1,
                    "children": []
                })
            
            # 二级行业
            df_l2 = ak.stock_board_industry_name_em(symbol="二级")
            for _, row in df_l2.iterrows():
                l2_node = {
                    "code": str(row['板块代码']),
                    "name": row['板块名称'],
                    "level": 2,
                    "children": [],
                    "parent": row['上级行业']
                }
                # 找到对应的一级行业，添加为子节点
                for l1 in l1_list:
                    if l1['name'] == l2_node['parent']:
                        l1['children'].append(l2_node)
                        break
            
            print(json.dumps(l1_list, ensure_ascii=False))
            """;
        
        ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("akshare 调用失败: " + result.getStderr());
        }
        
        parseAndSave(result.getStdout());
    }
    
    /**
     * 从 tushare 拉取行业树。
     */
    public void fetchFromTuShare() {
        String pythonCode = """
            import tushare as ts
            import json
            import os
            
            # 从环境变量获取 tushare token
            token = os.getenv('TUSHARE_TOKEN')
            if not token:
                raise Exception("TUSHARE_TOKEN 环境变量未设置")
            
            pro = ts.pro_api(token)
            
            # 申万一级行业
            df_l1 = pro.index_classify(level='L1', src='SW2021')
            l1_list = []
            for _, row in df_l1.iterrows():
                l1_list.append({
                    "code": row['index_code'],
                    "name": row['industry_name'],
                    "level": 1,
                    "children": []
                })
            
            # 申万二级行业
            df_l2 = pro.index_classify(level='L2', src='SW2021')
            for _, row in df_l2.iterrows():
                l2_node = {
                    "code": row['index_code'],
                    "name": row['industry_name'],
                    "level": 2,
                    "children": [],
                    "parent_code": row['parent_code']
                }
                # 找到对应的一级行业
                for l1 in l1_list:
                    if l1['code'] == l2_node['parent_code']:
                        l1['children'].append(l2_node)
                        break
            
            print(json.dumps(l1_list, ensure_ascii=False))
            """;
        
        ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("tushare 调用失败: " + result.getStderr());
        }
        
        parseAndSave(result.getStdout());
    }
    
    /**
     * 解析行业树 JSON 并保存到缓存。
     */
    private void parseAndSave(String json) {
        try {
            industryTree = objectMapper.readValue(json, 
                new TypeReference<List<IndustryNode>>() {});
            
            // 构建索引
            buildIndex();
            
            // 保存到缓存文件
            Path treeFile = cacheDir.resolve("industry_tree.json");
            Files.createDirectories(treeFile.getParent());
            Files.writeString(treeFile, json);
            
            log.info("行业树已缓存到: {}", treeFile);
        } catch (IOException e) {
            throw new RuntimeException("行业树解析失败", e);
        }
    }
    
    /**
     * 构建内存索引（见 IndustryService.java:278-298）。
     * 注意：每一层 getChildren() 都做 null 判断，防御 JSON 反序列化后 children 为 null。
     */
    private void buildIndex() {
        codeToNameMap = new HashMap<>();
        nameToCodeMap = new HashMap<>();

        for (IndustryNode l1 : industryTree) {
            codeToNameMap.put(l1.getCode(), l1.getName());
            nameToCodeMap.put(l1.getName(), l1.getCode());

            if (l1.getChildren() == null) continue;
            for (IndustryNode l2 : l1.getChildren()) {
                codeToNameMap.put(l2.getCode(), l2.getName());
                nameToCodeMap.put(l2.getName(), l2.getCode());

                if (l2.getChildren() == null) continue;
                for (IndustryNode l3 : l2.getChildren()) {
                    codeToNameMap.put(l3.getCode(), l3.getName());
                    nameToCodeMap.put(l3.getName(), l3.getCode());
                }
            }
        }
    }

    /**
     * 列出指定层级的行业（见 IndustryService.java:303-337）。
     * 防御：industryTree 为空 / parent 为空 / parentNode 为空 / children 为 null。
     */
    public List<IndustryNode> listIndustries(String level, String parent) {
        if (industryTree == null || industryTree.isEmpty()) {
            return Collections.emptyList();
        }

        if ("l1".equals(level) || "all".equals(level)) {
            return industryTree;
        }

        if (parent == null || parent.isBlank()) {
            return Collections.emptyList();
        }

        // 找到父行业
        IndustryNode parentNode = findNodeByName(parent);
        if (parentNode == null) {
            return Collections.emptyList();
        }

        if ("l2".equals(level)) {
            return parentNode.getChildren() != null
                ? parentNode.getChildren() : Collections.emptyList();
        } else if ("l3".equals(level)) {
            List<IndustryNode> l3Nodes = new ArrayList<>();
            if (parentNode.getChildren() != null) {
                for (IndustryNode l2 : parentNode.getChildren()) {
                    if (l2.getChildren() != null) {
                        l3Nodes.addAll(l2.getChildren());
                    }
                }
            }
            return l3Nodes;
        }

        return Collections.emptyList();
    }

    /**
     * 根据行业名称（支持模糊匹配）查找行业节点（见 IndustryService.java:342-372）。
     * 防御：industryTree / name 为 null 时直接返回 null。
     */
    public IndustryNode findNodeByName(String name) {
        if (industryTree == null || name == null) return null;

        // 精确匹配
        for (IndustryNode l1 : industryTree) {
            if (l1.getName().equals(name)) return l1;
            if (l1.getChildren() == null) continue;
            for (IndustryNode l2 : l1.getChildren()) {
                if (l2.getName().equals(name)) return l2;
                if (l2.getChildren() == null) continue;
                for (IndustryNode l3 : l2.getChildren()) {
                    if (l3.getName().equals(name)) return l3;
                }
            }
        }

        // 模糊匹配（包含关键字）
        for (IndustryNode l1 : industryTree) {
            if (l1.getName().contains(name)) return l1;
            if (l1.getChildren() == null) continue;
            for (IndustryNode l2 : l1.getChildren()) {
                if (l2.getName().contains(name)) return l2;
                if (l2.getChildren() == null) continue;
                for (IndustryNode l3 : l2.getChildren()) {
                    if (l3.getName().contains(name)) return l3;
                }
            }
        }

        return null;
    }

    /**
     * 获取指定行业下的全部股票代码。
     */
    public List<String> getStockCodesByIndustry(String industryName, int level) {
        IndustryNode node = findNodeByName(industryName);
        if (node == null) {
            throw new IllegalArgumentException("行业不存在: " + industryName);
        }
        
        // 如果节点已有股票代码列表，直接返回
        if (node.getStockCodes() != null && !node.getStockCodes().isEmpty()) {
            return node.getStockCodes();
        }
        
        // 否则从数据源拉取
        return fetchStockCodesByIndustry(node);
    }
    
    /**
     * 从数据源拉取行业成分股。
     */
    private List<String> fetchStockCodesByIndustry(IndustryNode node) {
        // 优先 akshare
        try {
            return fetchFromAkShare(node);
        } catch (Exception e) {
            log.warn("akshare 拉取行业成分股失败: {}，尝试 tushare", e.getMessage());
        }
        
        // 降级 tushare
        try {
            return fetchFromTuShare(node);
        } catch (Exception e) {
            log.error("tushare 拉取也失败", e);
            throw new RuntimeException("获取行业成分股失败: " + node.getName());
        }
    }
    
    private List<String> fetchFromAkShare(IndustryNode node) {
        // 注意：使用 Java 的 String.replace("%s", value) 做参数替换，
        // 而非 Python 的 % 字符串格式化操作符——后者会与 JSON 中的 % 冲突。
        String pythonCode = """
            import akshare as ak
            import json

            df = ak.stock_board_industry_cons_em(symbol="%s")
            codes = df['代码'].tolist()
            print(json.dumps(codes))
            """;
        pythonCode = pythonCode.replace("%s", node.getName());

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("akshare 调用失败");
        }

        try {
            return objectMapper.readValue(result.getStdout(),
                new TypeReference<List<String>>() {});
        } catch (IOException e) {
            throw new RuntimeException("解析股票代码失败", e);
        }
    }

    private List<String> fetchFromTuShare(IndustryNode node) {
        String pythonCode = """
            import tushare as ts
            import json
            import os

            token = os.getenv('TUSHARE_TOKEN')
            pro = ts.pro_api(token)

            df = pro.index_member(index_code='%s')
            codes = df['member_code'].tolist()
            print(json.dumps(codes))
            """;
        pythonCode = pythonCode.replace("%s", node.getCode());

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("tushare 调用失败");
        }

        try {
            return objectMapper.readValue(result.getStdout(),
                new TypeReference<List<String>>() {});
        } catch (IOException e) {
            throw new RuntimeException("解析股票代码失败", e);
        }
    }
}
```

### 2.3 命令行初始化（**未实现**）

> **代码状态**：`IndustryTreeInitializer` 类在当前代码库中**不存在**（已通过全量扫描确认）。
> §2.2 的 `initialize()` 已经在 `AgentScopeDemoApplication` 启动时被自动调用，
> 内置兜底分支（`loadBuiltinIndustryTree`）也保证了离线场景下行业树可用。
> 因此本节描述的"独立命令行初始化工具"作为**后续可选项**保留，当前不要假设它已存在。

调用入口的等价做法（已实现）：在 `AgentScopeDemoApplication.main()` 中调用
`industryService.initialize()`，三级兜底会自动选择缓存/网络/内置数据。

历史设想的 API（**仅作设计参考，当前未落地**）：

```java
// 设想中的命令行入口（未实现）
public class IndustryTreeInitializer {
    public static void main(String[] args) {
        // --force：跳过缓存强制重拉；--source akshare|tushare：选择数据源
        // 内部直接调用 industryService.fetchFromAkShare() / fetchFromTuShare()
    }
}
```

**当前刷新行业树的替代方案**：删除 `workspace/cache/industry_tree.json`，
重启应用即自动触发 `fetchAndCacheIndustryTree()`。

---

## 3. 模块3：数据服务层（StockDataService）

### 3.1 职责

负责股票数据的采集、缓存、更新。支持双数据源（akshare + tushare），三级新鲜度（行情1h、基本面24h、行业排名7d），提供按需刷新、增量更新、降级到过期缓存等能力。

### 3.2 数据源接口

```java
/**
 * 股票数据源接口。
 * 支持 akshare 和 tushare 两种实现。
 */
public interface StockDataSource {

    /**
     * 数据源名称。
     */
    String getName();

    /**
     * 获取行情数据。
     */
    CachedStockEntry.QuoteData fetchQuote(String code) throws DataSourceException;

    /**
     * 获取基本面数据。
     */
    CachedStockEntry.FundamentalData fetchFundamental(String code) throws DataSourceException;

    /**
     * 获取行业排名数据。
     */
    CachedStockEntry.IndustryRankData fetchIndustryRank(String code) throws DataSourceException;

    /**
     * 是否可用（检查 token、网络等）。
     */
    boolean isAvailable();
}
```

> **实现说明**：所有数据段类型均为 `CachedStockEntry` 的内部类（`QuoteData`/`FundamentalData`/`IndustryRankData`）。
> `DataSourceException` 为自定义受检异常，由各数据源在调用失败时抛出，供 `StockDataService.fetchWithFallback` 捕获后切换数据源。

### 3.3 AkShare 数据源实现

```java
/**
 * AkShare 数据源实现。
 * 免费、无需 token、覆盖全，但接口偶发不稳定。
 */
public class AkShareDataSource implements StockDataSource {

    private static final Logger log = LoggerFactory.getLogger(AkShareDataSource.class);

    private final CodeExecutionManager executionManager;
    private final ObjectMapper objectMapper;

    public AkShareDataSource(CodeExecutionManager executionManager) {
        this.executionManager = executionManager;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "akshare";
    }

    @Override
    public CachedStockEntry.QuoteData fetchQuote(String code) throws DataSourceException {
        String pythonCode = """
            import akshare as ak
            import json
            from datetime import datetime

            df = ak.stock_zh_a_spot_em()
            row = df[df['代码'] == '%s']
            if row.empty:
                print(json.dumps({"error": "股票不存在"}))
            else:
                data = {
                    "price": float(row.iloc[0]['最新价']),
                    "change_pct": float(row.iloc[0]['涨跌幅']),
                    "volume": int(row.iloc[0]['成交量']),
                    "updated_at": datetime.now().isoformat(),
                    "data_source": "akshare"
                }
                print(json.dumps(data))
            """;
        // 注意：Java 侧用 String.replace("%s", code) 替换占位符（**不是** Python 的 % 格式化）
        pythonCode = pythonCode.replace("%s", code);

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new DataSourceException("akshare 调用失败: " + result.getStderr());
        }

        try {
            var node = objectMapper.readTree(result.getStdout());
            if (node.has("error")) {
                throw new DataSourceException("股票不存在: " + code);
            }

            CachedStockEntry.QuoteData quoteData = new CachedStockEntry.QuoteData();
            quoteData.setPrice(node.get("price").asDouble());
            quoteData.setChangePct(node.get("change_pct").asDouble());
            quoteData.setVolume(node.get("volume").asLong());
            quoteData.setUpdatedAt(Instant.parse(node.get("updated_at").asText()));
            quoteData.setDataSource("akshare");
            return quoteData;
        } catch (Exception e) {
            throw new DataSourceException("解析行情数据失败", e);
        }
    }

    @Override
    public CachedStockEntry.FundamentalData fetchFundamental(String code) throws DataSourceException {
        // 使用字符串拼接（**非** Python % 格式化）将 code 注入 Python 代码
        String pythonCode =
            "import akshare as ak\n"
            + "import json\n"
            + "from datetime import datetime\n"
            + "\n"
            + "df_indicator = ak.stock_a_indicator_lg(symbol='" + code + "')\n"
            + "pe_ttm = float(df_indicator.iloc[-1]['pe_ttm'])\n"
            + "pb = float(df_indicator.iloc[-1]['pb'])\n"
            + "\n"
            + "df_finance = ak.stock_financial_analysis_indicator(symbol='" + code + "')\n"
            + "roe = float(df_finance.iloc[0]['净资产收益率(%)'])\n"
            + "\n"
            + "df_spot = ak.stock_zh_a_spot_em()\n"
            + "row = df_spot[df_spot['代码'] == '" + code + "']\n"
            + "market_cap = float(row.iloc[0]['总市值']) / 100000000\n"
            + "\n"
            + "data = {\n"
            + "    \"pe_ttm\": pe_ttm, \"pb\": pb, \"roe\": roe,\n"
            + "    \"market_cap\": market_cap,\n"
            + "    \"updated_at\": datetime.now().isoformat(),\n"
            + "    \"data_source\": \"akshare\"\n"
            + "}\n"
            + "print(json.dumps(data))\n";

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new DataSourceException("akshare 调用失败: " + result.getStderr());
        }

        try {
            var node = objectMapper.readTree(result.getStdout());
            CachedStockEntry.FundamentalData fundamentalData = new CachedStockEntry.FundamentalData();
            fundamentalData.setPeTtm(node.get("pe_ttm").asDouble());
            fundamentalData.setPb(node.get("pb").asDouble());
            fundamentalData.setRoe(node.get("roe").asDouble());
            fundamentalData.setMarketCap(node.get("market_cap").asDouble());
            fundamentalData.setUpdatedAt(Instant.parse(node.get("updated_at").asText()));
            fundamentalData.setDataSource("akshare");
            return fundamentalData;
        } catch (Exception e) {
            throw new DataSourceException("解析基本面数据失败", e);
        }
    }

    @Override
    public CachedStockEntry.IndustryRankData fetchIndustryRank(String code) throws DataSourceException {
        // akshare 没有直接的行业排名接口，返回占位数据（rank=0, total=0）
        CachedStockEntry.IndustryRankData rankData = new CachedStockEntry.IndustryRankData();
        rankData.setRankInL2(0);
        rankData.setTotalInL2(0);
        rankData.setUpdatedAt(Instant.now());
        return rankData;
    }

    @Override
    public boolean isAvailable() {
        String checkCode = "import akshare; print('ok')";
        CodeExecutionManager.ExecutionResult result = executionManager.executePython(checkCode);
        return result.isSuccess();
    }
}
```

> **实现说明**（依据 `AkShareDataSource.java:1-146`）：
> - 持有独立的 `ObjectMapper` 实例，使用 Jackson `readTree` 解析 stdout；
> - 行情接口用 text block + `String.replace("%s", code)` 完成参数注入；
> - 基本面接口用字符串拼接构造 Python 代码（注：`净资产收益率(%)` 在 Java 字面量中写作 `(%)`，不存在 `%%` 转义）；
> - `fetchIndustryRank` 返回占位结果，无网络调用。

### 3.4 TuShare 数据源实现

```java
/**
 * TuShare 数据源实现。
 * 字段规范、稳定，但需要 token、积分制。
 */
public class TuShareDataSource implements StockDataSource {

    private static final Logger log = LoggerFactory.getLogger(TuShareDataSource.class);

    private final CodeExecutionManager executionManager;
    private final ObjectMapper objectMapper;
    private final String tushareToken;

    public TuShareDataSource(CodeExecutionManager executionManager) {
        this.executionManager = executionManager;
        this.objectMapper = new ObjectMapper();
        this.tushareToken = System.getenv("TUSHARE_TOKEN");
    }

    @Override
    public String getName() {
        return "tushare";
    }

    @Override
    public CachedStockEntry.QuoteData fetchQuote(String code) throws DataSourceException {
        String tsCode = convertToTuShareCode(code);
        String dateStr = LocalDate.now().toString().replace("-", "");
        // 字符串拼接构造 Python 代码（**非** Python % 格式化）
        String pythonCode =
            "import tushare as ts\n"
            + "import json\n"
            + "from datetime import datetime\n"
            + "import os\n"
            + "\n"
            + "token = os.getenv('TUSHARE_TOKEN')\n"
            + "pro = ts.pro_api(token)\n"
            + "\n"
            + "df = pro.daily(ts_code='" + tsCode + "', start_date='" + dateStr + "', end_date='" + dateStr + "')\n"
            + "if df.empty:\n"
            + "    print(json.dumps({\"error\": \"无数据\"}))\n"
            + "else:\n"
            + "    row = df.iloc[0]\n"
            + "    data = {\n"
            + "        \"price\": float(row['close']),\n"
            + "        \"change_pct\": float(row['pct_chg']),\n"
            + "        \"volume\": int(row['vol']),\n"
            + "        \"updated_at\": datetime.now().isoformat(),\n"
            + "        \"data_source\": \"tushare\"\n"
            + "    }\n"
            + "    print(json.dumps(data))\n";

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new DataSourceException("tushare 调用失败: " + result.getStderr());
        }

        try {
            var node = objectMapper.readTree(result.getStdout());
            if (node.has("error")) {
                throw new DataSourceException("无数据: " + code);
            }
            CachedStockEntry.QuoteData quoteData = new CachedStockEntry.QuoteData();
            quoteData.setPrice(node.get("price").asDouble());
            quoteData.setChangePct(node.get("change_pct").asDouble());
            quoteData.setVolume(node.get("volume").asLong());
            quoteData.setUpdatedAt(Instant.parse(node.get("updated_at").asText()));
            quoteData.setDataSource("tushare");
            return quoteData;
        } catch (Exception e) {
            throw new DataSourceException("解析行情数据失败", e);
        }
    }

    @Override
    public CachedStockEntry.FundamentalData fetchFundamental(String code) throws DataSourceException {
        String tsCode = convertToTuShareCode(code);
        String pythonCode =
            "import tushare as ts\n"
            + "import json\n"
            + "from datetime import datetime\n"
            + "import os\n"
            + "\n"
            + "token = os.getenv('TUSHARE_TOKEN')\n"
            + "pro = ts.pro_api(token)\n"
            + "\n"
            + "df = pro.daily_basic(ts_code='" + tsCode + "', fields='pe_ttm,pb,total_mv')\n"
            + "row = df.iloc[0]\n"
            + "\n"
            + "df_fina = pro.fina_indicator(ts_code='" + tsCode + "', fields='roe')\n"
            + "roe = float(df_fina.iloc[0]['roe']) if not df_fina.empty else 0.0\n"
            + "\n"
            + "data = {\n"
            + "    \"pe_ttm\": float(row['pe_ttm']),\n"
            + "    \"pb\": float(row['pb']),\n"
            + "    \"roe\": roe,\n"
            + "    \"market_cap\": float(row['total_mv']) / 10000,  # 万元转亿元\n"
            + "    \"updated_at\": datetime.now().isoformat(),\n"
            + "    \"data_source\": \"tushare\"\n"
            + "}\n"
            + "print(json.dumps(data))\n";

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new DataSourceException("tushare 调用失败: " + result.getStderr());
        }

        try {
            var node = objectMapper.readTree(result.getStdout());
            CachedStockEntry.FundamentalData fundamentalData = new CachedStockEntry.FundamentalData();
            fundamentalData.setPeTtm(node.get("pe_ttm").asDouble());
            fundamentalData.setPb(node.get("pb").asDouble());
            fundamentalData.setRoe(node.get("roe").asDouble());
            fundamentalData.setMarketCap(node.get("market_cap").asDouble());
            fundamentalData.setUpdatedAt(Instant.parse(node.get("updated_at").asText()));
            fundamentalData.setDataSource("tushare");
            return fundamentalData;
        } catch (Exception e) {
            throw new DataSourceException("解析基本面数据失败", e);
        }
    }

    @Override
    public CachedStockEntry.IndustryRankData fetchIndustryRank(String code) throws DataSourceException {
        // tushare 行业排名接口较复杂，暂时返回占位数据（rank=0, total=0）
        CachedStockEntry.IndustryRankData rankData = new CachedStockEntry.IndustryRankData();
        rankData.setRankInL2(0);
        rankData.setTotalInL2(0);
        rankData.setUpdatedAt(Instant.now());
        return rankData;
    }

    @Override
    public boolean isAvailable() {
        if (tushareToken == null || tushareToken.isBlank()) {
            return false;
        }
        String checkCode = "import tushare; print('ok')";
        CodeExecutionManager.ExecutionResult result = executionManager.executePython(checkCode);
        return result.isSuccess();
    }

    /**
     * 转换股票代码为 tushare 格式。
     * 例如：600519 → 600519.SH
     */
    private String convertToTuShareCode(String code) {
        if (code.startsWith("6")) {
            return code + ".SH";
        } else {
            return code + ".SZ";
        }
    }
}
```

> **实现说明**（依据 `TuShareDataSource.java:1-170`）：
> - `ts_code`、`start_date` 等参数全部在 Java 侧通过字符串拼接注入，**不存在** Python `%` 格式化或 `% (a, b, c)` 元组替换；
> - `TUSHARE_TOKEN` 在构造函数中读取一次，`isAvailable()` 据此快速判定；
> - `fetchIndustryRank` 同样返回占位结果。

### 3.5 StockDataService 核心实现

```java
/**
 * 股票数据服务。
 * 负责数据采集、缓存、更新，支持双数据源自动降级。
 */
public class StockDataService {

    private static final Logger log = LoggerFactory.getLogger(StockDataService.class);

    /** 缓存目录：workspace/cache/stocks/ */
    private final Path cacheDir;

    /** 数据源列表（按优先级排序） */
    private final List<StockDataSource> dataSources;

    /** TTL 配置（构造时初始化为 quote=1h、fundamental=24h、industry_rank=7d） */
    private final Map<String, Duration> ttlConfig;

    /** Jackson ObjectMapper（注册 JavaTimeModule、关闭 WRITE_DATES_AS_TIMESTAMPS、开启 INDENT_OUTPUT） */
    private final ObjectMapper objectMapper;

    /** 行业服务 */
    private final IndustryService industryService;

    public StockDataService(Path cacheDir, List<StockDataSource> dataSources, IndustryService industryService) {
        this.cacheDir = cacheDir;
        this.dataSources = dataSources;
        this.industryService = industryService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        this.ttlConfig = new HashMap<>();
        this.ttlConfig.put("quote", Duration.ofHours(1));
        this.ttlConfig.put("fundamental", Duration.ofHours(24));
        this.ttlConfig.put("industry_rank", Duration.ofDays(7));
    }

    /**
     * 获取单只股票的完整数据。
     * 优先读缓存，过期则刷新；每段刷新独立 try-catch，单段失败不阻塞其他段。
     */
    public StockEntity getStockData(String code, boolean forceRefresh) {
        CachedStockEntry entry = loadFromCache(code);

        boolean quoteExpired = entry == null || entry.isExpired("quote", ttlConfig.get("quote"));
        boolean fundamentalExpired = entry == null || entry.isExpired("fundamental", ttlConfig.get("fundamental"));
        boolean rankExpired = entry == null || entry.isExpired("industry_rank", ttlConfig.get("industry_rank"));

        if (!forceRefresh && !quoteExpired && !fundamentalExpired && !rankExpired) {
            return convertToStockEntity(entry);
        }

        if (entry == null) entry = new CachedStockEntry(code);

        // 每段独立 try-catch：单段失败仅记录日志，不中断其他段
        if (quoteExpired || entry.getQuote() == null) {
            try {
                entry.setQuote(fetchWithFallback(code, "quote"));
            } catch (Exception e) {
                log.warn("获取行情数据失败: code={}, error={}", code, e.getMessage());
            }
        }
        if (fundamentalExpired || entry.getFundamental() == null) {
            try {
                entry.setFundamental(fetchWithFallback(code, "fundamental"));
            } catch (Exception e) {
                log.warn("获取基本面数据失败: code={}, error={}", code, e.getMessage());
            }
        }
        if (rankExpired || entry.getIndustryRank() == null) {
            try {
                entry.setIndustryRank(fetchWithFallback(code, "industry_rank"));
            } catch (Exception e) {
                log.warn("获取行业排名数据失败: code={}, error={}", code, e.getMessage());
            }
        }

        saveToCache(entry);
        return convertToStockEntity(entry);
    }

    /**
     * 带降级的数据拉取。
     * 优先 akshare，失败则 tushare，再失败则返回过期缓存。
     */
    @SuppressWarnings("unchecked")
    private <T> T fetchWithFallback(String code, String dataType) {
        for (StockDataSource source : dataSources) {
            if (!source.isAvailable()) {
                log.debug("数据源 {} 不可用，跳过", source.getName());
                continue;
            }
            try {
                return switch (dataType) {
                    case "quote" -> (T) source.fetchQuote(code);
                    case "fundamental" -> (T) source.fetchFundamental(code);
                    case "industry_rank" -> (T) source.fetchIndustryRank(code);
                    default -> throw new IllegalArgumentException("未知数据类型: " + dataType);
                };
            } catch (DataSourceException e) {
                log.warn("数据源 {} 拉取 {} 失败: {}，尝试下一个",
                    source.getName(), dataType, e.getMessage());
            }
        }

        // 所有数据源都失败，尝试返回过期缓存
        CachedStockEntry cached = loadFromCache(code);
        if (cached != null) {
            log.warn("所有数据源失败，返回过期缓存: code={}", code);
            return switch (dataType) {
                case "quote" -> (T) cached.getQuote();
                case "fundamental" -> (T) cached.getFundamental();
                case "industry_rank" -> (T) cached.getIndustryRank();
                default -> null;
            };
        }
        throw new RuntimeException("数据拉取失败且无缓存: code=" + code + ", type=" + dataType);
    }

    /**
     * 批量获取行业内全部股票数据。
     * 串行调用 + 200ms sleep 做简单限流。
     */
    public List<StockEntity> getIndustryStockData(String industryName, int level) {
        List<String> codes = industryService.getStockCodesByIndustry(industryName, level);
        List<StockEntity> results = new ArrayList<>();
        for (String code : codes) {
            try {
                results.add(getStockData(code, false));
                Thread.sleep(200); // akshare 限流
            } catch (Exception e) {
                log.warn("获取股票 {} 数据失败: {}", code, e.getMessage());
            }
        }
        return results;
    }

    /**
     * 刷新数据（支持 scope 和 data_type）。
     */
    public RefreshResult updateStockData(String scope, String target, String dataType, boolean force) {
        List<String> codes = resolveCodes(scope, target);
        int refreshed = 0, skipped = 0, failed = 0;
        List<FailureInfo> failures = new ArrayList<>();
        for (String code : codes) {
            try {
                if (!(force || isExpired(code, dataType))) {
                    skipped++;
                    continue;
                }
                refreshData(code, dataType);
                refreshed++;
                Thread.sleep(200);
            } catch (Exception e) {
                failed++;
                failures.add(new FailureInfo(code, e.getMessage()));
            }
        }
        return new RefreshResult(scope, target, codes.size(), refreshed, skipped, failed, failures);
    }

    private List<String> resolveCodes(String scope, String target) {
        if ("single".equals(scope)) return List.of(target);
        if ("industry".equals(scope)) return industryService.getStockCodesByIndustry(target, 2);
        return Collections.emptyList(); // market scope 简化为空
    }

    private boolean isExpired(String code, String dataType) {
        CachedStockEntry entry = loadFromCache(code);
        if (entry == null) return true;
        if ("all".equals(dataType)) {
            return entry.isExpired("quote", ttlConfig.get("quote")) ||
                   entry.isExpired("fundamental", ttlConfig.get("fundamental")) ||
                   entry.isExpired("industry_rank", ttlConfig.get("industry_rank"));
        }
        return entry.isExpired(dataType, ttlConfig.get(dataType));
    }

    private void refreshData(String code, String dataType) {
        CachedStockEntry entry = loadFromCache(code);
        if (entry == null) entry = new CachedStockEntry(code);
        if ("all".equals(dataType) || "quote".equals(dataType)) {
            entry.setQuote(fetchWithFallback(code, "quote"));
        }
        if ("all".equals(dataType) || "fundamental".equals(dataType)) {
            entry.setFundamental(fetchWithFallback(code, "fundamental"));
        }
        if ("all".equals(dataType) || "industry_rank".equals(dataType)) {
            entry.setIndustryRank(fetchWithFallback(code, "industry_rank"));
        }
        saveToCache(entry);
    }

    /**
     * 缓存写入（原子写入：先写 .tmp，再 move 覆盖）。
     */
    private void saveToCache(CachedStockEntry entry) {
        Path file = cacheDir.resolve(entry.getCode() + ".json");
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(cacheDir);
            // INDENT_OUTPUT 已在 ObjectMapper 上启用，这里直接 writeValueAsString
            String json = objectMapper.writeValueAsString(entry);
            Files.writeString(tempFile, json);
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("缓存写入失败: {}", entry.getCode(), e);
        }
    }

    private CachedStockEntry loadFromCache(String code) {
        Path file = cacheDir.resolve(code + ".json");
        if (!Files.exists(file)) return null;
        try {
            return objectMapper.readValue(Files.readString(file), CachedStockEntry.class);
        } catch (IOException e) {
            log.warn("缓存读取失败: {}", code, e);
            return null;
        }
    }

    /**
     * CachedStockEntry → StockEntity 转换。
     * 注意：当前实现只映射 quote + fundamental，**未映射 industry_rank**。
     */
    private StockEntity convertToStockEntity(CachedStockEntry entry) {
        StockEntity entity = new StockEntity();
        entity.setCode(entry.getCode());

        if (entry.getQuote() != null) {
            entity.setDataSource(entry.getQuote().getDataSource());
            entity.setUpdatedAt(entry.getQuote().getUpdatedAt());
        }
        if (entry.getFundamental() != null) {
            entity.setPeTtm(entry.getFundamental().getPeTtm());
            entity.setPb(entry.getFundamental().getPb());
            entity.setRoe(entry.getFundamental().getRoe());
            entity.setMarketCap(entry.getFundamental().getMarketCap());
        }
        // 注：industryRank 字段当前未映射到 StockEntity
        return entity;
    }

    // 嵌套静态类：RefreshResult、FailureInfo（含全字段构造器和 getter）
}
```

### 3.6 降级策略

```
akshare 调用成功 → 更新缓存并返回
    ↓ 失败
tushare 调用成功 → 更新缓存并返回
    ↓ 失败
检查本地缓存（即使过期）→ 返回并标注 stale=true
    ↓ 无缓存
抛出异常，工具返回错误信息
```

---

## 4. 模块4：评分服务层（LeaderScoringService）

### 4.1 职责

实现龙头评分算法：多因子加权评分（市值+营收+ROE+品牌度），行业内 z-score 归一化，支持权重可配置。

### 4.2 核心实现

```java
/**
 * 龙头评分服务。
 * 实现多因子加权评分算法，行业内 z-score 归一化。
 */
public class LeaderScoringService {

    private static final Logger log = LoggerFactory.getLogger(LeaderScoringService.class);

    /**
     * 计算行业内全部股票的龙头评分。
     * 权重直接引用 LeaderScoreResult 上的 public static final 常量。
     */
    public List<LeaderScoreResult> computeLeaderScores(List<StockEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 提取各维度原始数据（注意：revenues 取的是 revenueGrowth，不是 revenue）
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
        return values.stream().map(v -> (v - mean) / std).toList();
    }

    /**
     * 对数变换（抑制长尾效应）。
     */
    private List<Double> logTransform(List<Double> values) {
        return values.stream().map(v -> Math.log(Math.max(v, 1))).toList();
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
     * 品牌度启发式评分（简化版）。
     * 实际实现仅包含两个维度：名称匹配 + 市值分档。
     */
    private double heuristicBrandScore(StockEntity entity) {
        double score = 0;

        // 名称含"中国"/"中华"
        if (entity.getName() != null &&
            (entity.getName().contains("中国") || entity.getName().contains("中华"))) {
            score += 5;
        }

        // 市值越大，品牌度越高（简化处理）
        if (entity.getMarketCap() > 1000) {       // > 1000 亿
            score += 30;
        } else if (entity.getMarketCap() > 500) { // > 500 亿
            score += 20;
        } else if (entity.getMarketCap() > 100) { // > 100 亿
            score += 10;
        }

        return score;
    }
}
```

> **实现说明**（依据 `LeaderScoringService.java:1-138`）：
> - 不存在 `ScoringWeights` 类，也不读取 `scoring_weights.json`；权重以 `public static final` 形式定义在 `LeaderScoreResult`（详见 §1.5）。
> - `revenues` 实际取值 `entity.getRevenueGrowth()`（营收**增长率**），并非营收绝对值。
> - `scoreComponents` 通过 `HashMap` + `put` 构造，未使用 `Map.of()`。
> - `heuristicBrandScore` 当前**只**包含名称匹配（"中国"/"中华"，+5）和市值分档（>1000 → +30；>500 → +20；>100 → +10）两个维度；**不存在** `isHS300Component`/`isSSE50Component`/`isIndustryTop1ByMarketCap`/`getFundHoldCount` 这些设想中的辅助方法。

### 4.3 权重配置

权重以常量形式硬编码在 `LeaderScoreResult`（详见 §1.5），**不存在** `workspace/config/scoring_weights.json` 文件，也**不存在** `ScoringWeights` 类。如需运行时调整权重，需修改源码常量后重新编译。

| 常量名 | 值 | 说明 |
|---|---|---|
| `WEIGHT_MARKET_CAP` | 0.40 | 市值权重 |
| `WEIGHT_REVENUE` | 0.25 | 营收（增长率）权重 |
| `WEIGHT_ROE` | 0.20 | ROE 权重 |
| `WEIGHT_BRAND` | 0.15 | 品牌度权重 |

---

## 5. 模块5：筛选服务层（StockFilterService）

### 5.1 职责

实现多维筛选逻辑：PE/PB/ROE/营收增速/市值区间任意组合，支持 ROE 连续 N 年达标检查。

### 5.2 核心实现

```java
/**
 * 股票筛选服务。
 * 支持多维筛选条件任意组合。
 */
public class StockFilterService {
    
    /**
     * 应用多维筛选条件。
     */
    public List<StockEntity> applyFilters(List<StockEntity> entities, StockFilter filter) {
        return entities.stream()
            .filter(e -> matchesFilter(e, filter))
            .collect(toList());
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
            if (entity.getName().contains("ST")) {
                return false;
            }
        }
        
        return true;
    }
}
```

---

## 6. 模块6：工具集成层（StockToolService）

### 6.1 职责

作为工具入口，注册 4 个股票工具到 `MCPClient`，协调各服务层完成业务逻辑。

### 6.2 核心实现

```java
/**
 * 股票工具服务。
 * 注册 4 个股票工具到 MCPClient。
 */
public class StockToolService {
    
    private final IndustryService industryService;
    private final StockDataService stockDataService;
    private final LeaderScoringService scoringService;
    private final StockFilterService filterService;
    
    /**
     * 注册 4 个股票工具到 MCPClient。
     */
    public void registerTools(MCPClient mcpClient) {
        registerListIndustriesTool(mcpClient);
        registerSelectIndustryLeadersTool(mcpClient);
        registerGetStockDetailTool(mcpClient);
        registerUpdateStockDataTool(mcpClient);
    }
    
    /**
     * 工具1：list_industries
     */
    private void registerListIndustriesTool(MCPClient mcpClient) {
        String params = """
            {
              "type": "object",
              "properties": {
                "level": {
                  "type": "string",
                  "enum": ["l1", "l2", "l3", "all"],
                  "default": "l1"
                },
                "parent": {
                  "type": "string",
                  "description": "父级行业名称"
                }
              }
            }
            """;
        
        mcpClient.registerCustomTool(
            "list_industries",
            "列出申万行业分类树，支持一级/二级/三级行业查询",
            params,
            (args) -> {
                String level = (String) args.getOrDefault("level", "l1");
                String parent = (String) args.get("parent");
                
                List<IndustryNode> nodes = industryService.listIndustries(level, parent);
                return objectMapper.writeValueAsString(nodes);
            }
        );
    }
    
    /**
     * 工具2：select_industry_leaders（核心）
     */
    private void registerSelectIndustryLeadersTool(MCPClient mcpClient) {
        String params = """
            {
              "type": "object",
              "required": ["industry"],
              "properties": {
                "industry": {"type": "string"},
                "level": {"type": "string", "enum": ["l1", "l2", "l3"], "default": "l2"},
                "top_n": {"type": "integer", "default": 10},
                "filters": {"type": "object"},
                "use_cache": {"type": "boolean", "default": true}
              }
            }
            """;
        
        mcpClient.registerCustomTool(
            "select_industry_leaders",
            "按行业筛选龙头股，支持多维过滤条件",
            params,
            (args) -> {
                String industry = (String) args.get("industry");
                String level = (String) args.getOrDefault("level", "l2");
                // 注意：JSON 反序列化时整型可能装箱为 Integer/Long，统一用 Number.intValue()
                int topN = ((Number) args.getOrDefault("top_n", 10)).intValue();
                boolean useCache = (boolean) args.getOrDefault("use_cache", true);

                StockFilter filter = parseFilter((Map<String, Object>) args.get("filters"));

                int levelInt = switch (level) {
                    case "l1" -> 1;
                    case "l2" -> 2;
                    case "l3" -> 3;
                    default -> 2;
                };
                List<StockEntity> entities = stockDataService.getIndustryStockData(industry, levelInt);
                List<StockEntity> filtered = filterService.applyFilters(entities, filter);
                List<LeaderScoreResult> scores = scoringService.computeLeaderScores(filtered);
                List<LeaderScoreResult> topResults = scores.subList(0, Math.min(topN, scores.size()));

                // 使用 HashMap 构造返回对象（不用 Map.of，便于扩展）
                Map<String, Object> result = new HashMap<>();
                result.put("industry", industry);
                result.put("level", level);
                result.put("total_universe", entities.size());
                result.put("filtered_count", filtered.size());
                result.put("leaders", topResults);
                result.put("data_updated_at", Instant.now().toString());
                result.put("cache_hit", useCache);

                return objectMapper.writeValueAsString(result);
            }
        );
    }
    
    /**
     * 工具3：get_stock_detail
     */
    private void registerGetStockDetailTool(MCPClient mcpClient) {
        String params = """
            {
              "type": "object",
              "required": ["code"],
              "properties": {
                "code": {"type": "string", "pattern": "^\\d{6}$"},
                "include_history": {"type": "boolean", "default": false},
                "force_refresh": {"type": "boolean", "default": false}
              }
            }
            """;
        
        mcpClient.registerCustomTool(
            "get_stock_detail",
            "查询单只股票的完整指标",
            params,
            (args) -> {
                String code = (String) args.get("code");
                boolean forceRefresh = (boolean) args.getOrDefault("force_refresh", false);
                
                StockEntity entity = stockDataService.getStockData(code, forceRefresh);
                return objectMapper.writeValueAsString(entity);
            }
        );
    }
    
    /**
     * 工具4：update_stock_data
     */
    private void registerUpdateStockDataTool(MCPClient mcpClient) {
        String params = """
            {
              "type": "object",
              "properties": {
                "scope": {"type": "string", "enum": ["single", "industry", "market"]},
                "code": {"type": "string"},
                "industry": {"type": "string"},
                "data_type": {"type": "string", "enum": ["quote", "fundamental", "industry_rank", "all"]},
                "force": {"type": "boolean", "default": false}
              }
            }
            """;
        
        mcpClient.registerCustomTool(
            "update_stock_data",
            "刷新股票数据",
            params,
            (args) -> {
                String scope = (String) args.getOrDefault("scope", "industry");
                String target = (String) args.get(scope.equals("single") ? "code" : "industry");
                String dataType = (String) args.getOrDefault("data_type", "all");
                boolean force = (boolean) args.getOrDefault("force", false);

                // 内部静态类需以 StockDataService.RefreshResult 形式引用
                StockDataService.RefreshResult result = stockDataService.updateStockData(scope, target, dataType, force);
                return objectMapper.writeValueAsString(result);
            }
        );
    }
}
```

> **实现说明**（依据 `StockToolService.java:1-255`）：
> - 返回结果一律用 `HashMap` + `put` 构造，**未使用** `Map.of()`；
> - `top_n` 用 `((Number) ...).intValue()` 兼容 Integer/Long 装箱；
> - `level` 通过 `switch` 映射为 `int`（`l1→1, l2→2, l3→3`，default 2），再传给 `StockDataService.getIndustryStockData`；
> - `parseFilter` 是 `@SuppressWarnings("unchecked")` 的私有方法，按 key 逐项 `containsKey` + setter 构造 `StockFilter`。

---

## 7. 模块7：系统集成

### 7.1 AgentScopeDemoApplication 修改

```java
// 在 main() 中（registerCodeExecutionTools 之后），见 AgentScopeDemoApplication.java:134-154：

// 7.1 创建股票服务
IndustryService industryService = new IndustryService(executionManager,
    workspaceDir.resolve("cache"));
industryService.initialize();

// 创建数据源（akshare 主，tushare 备）
AkShareDataSource akShareSource = new AkShareDataSource(executionManager);
TuShareDataSource tuShareSource = new TuShareDataSource(executionManager);

StockDataService stockDataService = new StockDataService(
    workspaceDir.resolve("cache/stocks"),
    List.of(akShareSource, tuShareSource),
    industryService
);

LeaderScoringService scoringService = new LeaderScoringService();
StockFilterService filterService = new StockFilterService();

StockToolService stockToolService = new StockToolService(
    industryService,
    stockDataService,
    scoringService,
    filterService
);

// 7.2 注册股票工具
stockToolService.registerTools(mcpClient);
```

> **实现说明**：
> - 路径基数为 `workspaceDir`（不是 `secureFileWorkspace.getBaseDir()`）；
> - `List.of(akShareSource, tuShareSource)` 直接内联到构造函数，不抽取中间变量；
> - `industryService.initialize()` 同步执行（缓存 → akshare → tushare → 内置兜底）。

### 7.2 权限规则

```java
// 在 createPermissionEngine() 中（见 AgentScopeDemoApplication.java:363-367）：

// 允许股票工具
engine.addRule(new PermissionRule("list_industries", PermissionDecision.ALLOW, "行业分类查询"));
engine.addRule(new PermissionRule("select_industry_leaders", PermissionDecision.ALLOW, "龙头筛选"));
engine.addRule(new PermissionRule("get_stock_detail", PermissionDecision.ALLOW, "单股详情"));
engine.addRule(new PermissionRule("update_stock_data", PermissionDecision.ALLOW, "数据更新"));
```

### 7.3 系统提示词

```java
// 在 SYSTEM_PROMPT 中追加（见 AgentScopeDemoApplication.java:81-94）：

"""
股票研究工具：
- list_industries: 列出申万行业分类树（参数: level, parent）
- select_industry_leaders: 按行业筛选龙头股（参数: industry, level, top_n, filters）
- get_stock_detail: 查询单只股票的完整指标（参数: code, force_refresh）
- update_stock_data: 刷新股票数据（参数: scope, code/industry, data_type, force）

股票数据来源于 akshare（主）+ tushare（备），带缓存机制（行情1h、基本面24h、行业排名7d）。
龙头评分基于市值、营收、ROE、品牌度多因子加权计算，行业内归一化到 0-100 分。
"""
```

### 7.4 环境变量

```bash
# 可选：tushare token（如果不设置，则只使用 akshare）
export TUSHARE_TOKEN=your_token_here
```

### 7.5 改动汇总

| 文件 | 改动量 | 说明 |
|---|---|---|
| `AgentScopeDemoApplication.java` | +25 行 | 创建股票服务、注册工具、追加权限规则、更新提示词 |
| `AgentTeam.java` | +5 行 | 领导者提示词追加股票工具描述 |
| 新增 `stock/` 包 | ~2000 行 | 14 个核心类（5 模型 + 1 接口 + 1 异常 + 4 服务 + 2 数据源 + 1 工具服务），见 §8 |
| 新增 `stock/` 测试 | ~260 行 | `StockServiceTest`（9 个测试用例，覆盖行业/筛选/评分/模型） |

**不改动**：`MCPClient`、`LocalWorkspace`、`PermissionEngine`、`Agent`、`Middleware` 链。

> **注**：行业树初始化**不依赖独立的命令行工具**，而是由 `IndustryService.initialize()` 在应用启动时同步完成（缓存 → akshare → tushare → 内置兜底，详见 §2.2）。

---

## 8. 新增文件清单

```
agentscope-demo/src/main/java/com/demo/agentscope/stock/
├── model/
│   ├── StockEntity.java
│   ├── CachedStockEntry.java
│   ├── IndustryNode.java
│   ├── StockFilter.java
│   └── LeaderScoreResult.java
├── industry/
│   └── IndustryService.java
├── data/
│   ├── StockDataService.java
│   ├── StockDataSource.java          # 接口
│   ├── DataSourceException.java      # 自定义异常
│   ├── AkShareDataSource.java
│   └── TuShareDataSource.java
├── scoring/
│   └── LeaderScoringService.java
├── filter/
│   └── StockFilterService.java
└── StockToolService.java
```

> **注**：原设计稿中提及的 `industry/IndustryTreeInitializer.java` 在代码中**不存在**——行业树初始化由 `IndustryService.initialize()` 在应用启动时同步完成，三级兜底策略详见 §2.2。

---

## 9. 待确认事项

1. **tushare token**：当前实现从 `TUSHARE_TOKEN` 环境变量读取；未设置时 `TuShareDataSource.isAvailable()` 返回 false，自动降级为仅 akshare。
2. ~~**行业树初始化**：首次启动时自动从 akshare 拉取，还是提供静态 JSON 文件？~~ **已确认**：三级兜底策略（缓存文件 `industry_tree.json` → akshare 拉取 → tushare 拉取 → 内置 31 个一级行业兜底），详见 §2.2 与 `IndustryService.initialize()`。
3. ~~**评分权重**：默认权重（市值0.4、营收0.25、ROE0.2、品牌0.15）是否合理？~~ **已确认**：权重为 `LeaderScoreResult` 中的 `public static final` 编译期常量（`WEIGHT_MARKET_CAP=0.40` 等，详见 §4.3 表），调整需修改源码并重新编译；不存在运行时可配置的 JSON 文件。
4. **缓存目录**：当前实现为 `workspace/cache/stocks/`（单股票）与 `workspace/cache/industry_tree.json`（行业树），TTL 见 §3.5（行情 1h、基本面 24h、行业排名未实际拉取）。
5. **Team 模式**：跨行业对比场景下，工作者是否需要独立的股票工具？当前 `AgentTeam` 仅 leader 持有股票工具描述。

---

## 10. 实施计划

### P0（核心能力）
- 数据模型层（`stock/model/*`：StockEntity、CachedStockEntry、IndustryNode、StockFilter、LeaderScoreResult）
- 行业服务层（`IndustryService.initialize()` 同步初始化：缓存 → akshare → tushare → 内置兜底）
- 数据服务层（`StockDataService` + `AkShareDataSource`，含本地文件缓存）
- 评分服务层（`LeaderScoringService`，权重为 `LeaderScoreResult` 中的 `public static final` 常量）
- 筛选服务层（`StockFilterService`）
- 工具集成层（`StockToolService` 注册 4 个工具到 `MCPClient`）

### P1（增强能力）
- tushare 备用数据源（`TuShareDataSource`，需 `TUSHARE_TOKEN`）
- 多维筛选完整支持（PE/PB/ROE/市值/营收增长率/利润增长率/连续 ROE 年数/ST 排除）
- 权限规则 + 系统提示词（4 个工具均加 ALLOW 规则）

### P2（体验优化）
- Team 模式跨行业对比
- 评分权重可配置（**当前未实现**：权重为编译期常量，调整需修改 `LeaderScoreResult` 源码；若需运行时配置，需新增 `scoring_weights.json` 加载逻辑）
- JSON/CSV 导出

---

## 附录 A：v1.1 → v1.2 修正点（基于代码全量分析）

> 全量比对 `agentscope-demo/src/main/java/com/demo/agentscope/stock/**` 与 `src/test/...` 后，按章节归纳的修正点。每条均有源码行号佐证。

### A.1 数据模型层（§1）
- **LeaderScoreResult**：权重不是 `ScoringWeights` 类配置，而是 `public static final` 常量（`WEIGHT_MARKET_CAP=0.40`、`WEIGHT_REVENUE=0.25`、`WEIGHT_ROE=0.20`、`WEIGHT_BRAND=0.15`）。
- **CachedStockEntry**：`QuoteData`/`FundamentalData`/`IndustryRankData` 均为内部静态类；`IndustryRankData` 字段为 `rankInL2`/`totalInL2`（不是 `rankInL1`/`totalInL1`）。
- **StockFilter**：使用 Builder 模式；除 PE/PB/ROE/市值外，还有 `revenueGrowthMin`/`profitGrowthMin`/`roeYears`/`excludeSt` 字段。

### A.2 行业服务层（§2）
- **行业树初始化**：无独立命令行工具；初始化逻辑在 `IndustryService.initialize()` 内，三级兜底：缓存文件 → akshare → tushare → 内置 31 个一级行业。
- **内置兜底数据**：包含全部 31 个申万一级行业（`801010` 农林牧渔 … `801970` 环保）。
- **公开方法**：`fetchAndCacheIndustryTree()`、`fetchFromAkShare()`、`fetchFromTuShare()` 均为 `public`，便于测试与外部调用。

### A.3 数据服务层（§3）
- **接口返回类型**：`StockDataSource` 三个方法返回 `CachedStockEntry.QuoteData`/`FundamentalData`/`IndustryRankData`（内部类），抛 `DataSourceException`（自定义受检异常）。
- **AkShare 参数注入**：`fetchQuote()` 用 Java 文本块 + `pythonCode.replace("%s", code)`（**不是** Python `%` 格式化）；`fetchFundamental()` 用字符串 `+` 拼接。
- **TuShare 参数注入**：统一用 Java 字符串 `+` 拼接，先计算 `tsCode`（如 `600519.SH`）与 `dateStr`。
- **StockDataService**：3 参构造函数（`cacheDir`、`List<StockDataSource>`、`IndustryService`）；ObjectMapper 配置为 `JavaTimeModule` + 关闭 `WRITE_DATES_AS_TIMESTAMPS` + 开启 `INDENT_OUTPUT`；`getStockData()` 内部对每个数据段独立 try-catch；序列化用 `objectMapper.writeValueAsString()`。
- **convertToStockEntity()**：仅映射 quote + fundamental，**不映射** industry_rank。
- **IndustryRankData 占位**：akshare 与 tushare 当前均返回 `(0, 0)` 占位数据，未实际拉取排名。

### A.4 评分服务层（§4）
- **权重引用**：直接引用 `LeaderScoreResult.WEIGHT_MARKET_CAP` 等常量（不存在 `ScoringWeights` 类，不存在 `scoring_weights.json`）。
- **营收字段**：用 `entity.getRevenueGrowth()`（不是 `getRevenue()`）。
- **品牌评分简化**：`heuristicBrandScore()` 仅 2 个维度——
  1. 名称包含「中国」「中华」→ +5；
  2. 市值分档：>1000→+30，>500→+20，>100→+10。
  （**移除**了原稿中提及的 HS300/SSE50 成分、行业市值第一、基金持股数等维度。）
- **scoreComponents**：用 `HashMap` + `put()`（不是 `Map.of()`）。

### A.5 工具集成层（§6）
- **select_industry_leaders**：
  - 结果 Map 用 `HashMap` + `put()`（不是 `Map.of()`）。
  - `top_n` 解包用 `((Number) args.getOrDefault("top_n", 10)).intValue()`（避免 Integer/Long 强转异常）。
  - `level` 字符串经 `switch` 表达式映射为 int（`l1→1, l2→2, l3→3, default→2`）。
- **update_stock_data**：返回类型为 `StockDataService.RefreshResult`（嵌套静态类，含 `RefreshResult` 与 `FailureInfo` 两个内部记录）。

### A.6 文件清单（§8）
- **移除**：`industry/IndustryTreeInitializer.java`（代码中不存在）。
- **新增**：`data/DataSourceException.java`（自定义受检异常）。
- **类总数**：14 个主类（5 模型 + 1 接口 + 1 异常 + IndustryService + StockDataService + 2 DataSource + LeaderScoringService + StockFilterService + StockToolService）。

### A.7 系统集成与计划（§7、§9、§10）
- **行业树初始化**：在应用启动时由 `IndustryService.initialize()` 同步完成，**不依赖独立 CLI 工具**。
- **§9 待确认事项**：第 2、3 项已确认（行业树三级兜底；权重为编译期常量）。
- **§10 P2「评分权重可配置」**：当前未实现，需新增 JSON 加载逻辑才能支持运行时配置。

---

**文档结束**
