package com.demo.agentscope.stock;

import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.stock.data.StockDataService;
import com.demo.agentscope.stock.filter.StockFilterService;
import com.demo.agentscope.stock.industry.IndustryService;
import com.demo.agentscope.stock.model.*;
import com.demo.agentscope.stock.scoring.LeaderScoringService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票工具服务。
 * 注册 4 个股票工具到 MCPClient。
 */
public class StockToolService {

    private static final Logger log = LoggerFactory.getLogger(StockToolService.class);

    private final IndustryService industryService;
    private final StockDataService stockDataService;
    private final LeaderScoringService scoringService;
    private final StockFilterService filterService;
    private final ObjectMapper objectMapper;

    public StockToolService(IndustryService industryService, StockDataService stockDataService,
                           LeaderScoringService scoringService, StockFilterService filterService) {
        this.industryService = industryService;
        this.stockDataService = stockDataService;
        this.scoringService = scoringService;
        this.filterService = filterService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 注册 4 个股票工具到 MCPClient。
     */
    public void registerTools(MCPClient mcpClient) {
        registerListIndustriesTool(mcpClient);
        registerSelectIndustryLeadersTool(mcpClient);
        registerGetStockDetailTool(mcpClient);
        registerUpdateStockDataTool(mcpClient);
        log.info("股票工具已注册到 MCPClient");
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
                "code": {"type": "string", "pattern": "^\\\\d{6}$"},
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

                StockDataService.RefreshResult result = stockDataService.updateStockData(scope, target, dataType, force);
                return objectMapper.writeValueAsString(result);
            }
        );
    }

    /**
     * 解析筛选条件
     */
    @SuppressWarnings("unchecked")
    private StockFilter parseFilter(Map<String, Object> filterMap) {
        if (filterMap == null) {
            return new StockFilter();
        }

        StockFilter filter = new StockFilter();
        if (filterMap.containsKey("market_cap_min")) {
            filter.setMarketCapMin(((Number) filterMap.get("market_cap_min")).doubleValue());
        }
        if (filterMap.containsKey("market_cap_max")) {
            filter.setMarketCapMax(((Number) filterMap.get("market_cap_max")).doubleValue());
        }
        if (filterMap.containsKey("pe_min")) {
            filter.setPeMin(((Number) filterMap.get("pe_min")).doubleValue());
        }
        if (filterMap.containsKey("pe_max")) {
            filter.setPeMax(((Number) filterMap.get("pe_max")).doubleValue());
        }
        if (filterMap.containsKey("pb_min")) {
            filter.setPbMin(((Number) filterMap.get("pb_min")).doubleValue());
        }
        if (filterMap.containsKey("pb_max")) {
            filter.setPbMax(((Number) filterMap.get("pb_max")).doubleValue());
        }
        if (filterMap.containsKey("roe_min")) {
            filter.setRoeMin(((Number) filterMap.get("roe_min")).doubleValue());
        }
        if (filterMap.containsKey("roe_years")) {
            filter.setRoeYears((Integer) filterMap.get("roe_years"));
        }
        if (filterMap.containsKey("revenue_growth_min")) {
            filter.setRevenueGrowthMin(((Number) filterMap.get("revenue_growth_min")).doubleValue());
        }
        if (filterMap.containsKey("profit_growth_min")) {
            filter.setProfitGrowthMin(((Number) filterMap.get("profit_growth_min")).doubleValue());
        }
        if (filterMap.containsKey("exclude_st")) {
            filter.setExcludeSt((Boolean) filterMap.get("exclude_st"));
        }

        return filter;
    }
}
