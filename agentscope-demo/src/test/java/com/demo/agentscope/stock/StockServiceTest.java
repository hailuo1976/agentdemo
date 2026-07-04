package com.demo.agentscope.stock;

import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.stock.data.AkShareDataSource;
import com.demo.agentscope.stock.data.StockDataService;
import com.demo.agentscope.stock.data.TuShareDataSource;
import com.demo.agentscope.stock.filter.StockFilterService;
import com.demo.agentscope.stock.industry.IndustryService;
import com.demo.agentscope.stock.model.CachedStockEntry;
import com.demo.agentscope.stock.model.IndustryNode;
import com.demo.agentscope.stock.model.LeaderScoreResult;
import com.demo.agentscope.stock.model.StockEntity;
import com.demo.agentscope.stock.model.StockFilter;
import com.demo.agentscope.stock.scoring.LeaderScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票选择功能单元测试。
 */
public class StockServiceTest {

    @TempDir
    Path tempDir;

    private IndustryService industryService;
    private StockDataService stockDataService;
    private LeaderScoringService scoringService;
    private StockFilterService filterService;

    @BeforeEach
    void setUp() {
        CodeExecutionManager executionManager = new CodeExecutionManager(tempDir);
        industryService = new IndustryService(executionManager, tempDir.resolve("cache"));

        // 初始化行业树（网络不可用时使用内置数据）
        industryService.initialize();

        AkShareDataSource akShareSource = new AkShareDataSource(executionManager);
        TuShareDataSource tuShareSource = new TuShareDataSource(executionManager);
        stockDataService = new StockDataService(
            tempDir.resolve("cache/stocks"),
            List.of(akShareSource, tuShareSource),
            industryService);

        scoringService = new LeaderScoringService();
        filterService = new StockFilterService();
    }

    @Test
    void testIndustryServiceInitialization() {
        List<IndustryNode> tree = industryService.getIndustryTree();
        assertNotNull(tree);
        assertFalse(tree.isEmpty(), "行业树不应为空");

        // 验证包含常见行业
        boolean hasElectronics = tree.stream()
            .anyMatch(node -> node.getName().contains("电子"));
        assertTrue(hasElectronics, "行业树应包含电子行业");
    }

    @Test
    void testIndustryServiceFindByName() {
        // 精确匹配
        IndustryNode node = industryService.findNodeByName("电子");
        assertNotNull(node, "应能找到电子行业");
        assertEquals("电子", node.getName());

        // 模糊匹配
        IndustryNode fuzzyNode = industryService.findNodeByName("计算机");
        assertNotNull(fuzzyNode, "应能通过模糊匹配找到计算机行业");
    }

    @Test
    void testIndustryServiceCodeNameMapping() {
        // 验证代码→名称映射
        var codeToName = industryService.getCodeToNameMap();
        assertNotNull(codeToName);
        assertFalse(codeToName.isEmpty());

        // 验证名称→代码映射
        var nameToCode = industryService.getNameToCodeMap();
        assertNotNull(nameToCode);
        assertTrue(nameToCode.containsKey("电子"));
    }

    @Test
    void testStockFilterService() {
        List<StockEntity> stocks = createTestStocks();

        // 测试 PE 过滤
        StockFilter peFilter = new StockFilter.Builder()
            .peMin(0.0)
            .peMax(20.0)
            .build();
        List<StockEntity> filtered = filterService.applyFilters(stocks, peFilter);
        assertTrue(filtered.size() < stocks.size(), "PE 过滤应减少股票数量");
        filtered.forEach(s -> assertTrue(s.getPeTtm() >= 0 && s.getPeTtm() <= 20));

        // 测试 ROE 过滤
        StockFilter roeFilter = new StockFilter.Builder()
            .roeMin(15.0)
            .build();
        filtered = filterService.applyFilters(stocks, roeFilter);
        filtered.forEach(s -> assertTrue(s.getRoe() >= 15.0));

        // 测试市值过滤
        StockFilter capFilter = new StockFilter.Builder()
            .marketCapMin(100.0)
            .marketCapMax(1000.0)
            .build();
        filtered = filterService.applyFilters(stocks, capFilter);
        filtered.forEach(s -> assertTrue(s.getMarketCap() >= 100 && s.getMarketCap() <= 1000));
    }

    @Test
    void testLeaderScoringService() {
        List<StockEntity> stocks = createTestStocks();

        List<LeaderScoreResult> results = scoringService.computeLeaderScores(stocks);
        assertNotNull(results);
        assertFalse(results.isEmpty());

        // 验证评分在 0-100 范围内
        results.forEach(r -> {
            assertTrue(r.getTotalScore() >= 0 && r.getTotalScore() <= 100,
                "评分应在 0-100 范围内，实际: " + r.getTotalScore());
        });

        // 验证按分数降序排列
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).getTotalScore() >= results.get(i + 1).getTotalScore(),
                "结果应按分数降序排列");
        }
    }

    @Test
    void testStockFilterBuilder() {
        StockFilter filter = new StockFilter.Builder()
            .peMin(5.0)
            .peMax(30.0)
            .pbMin(1.0)
            .pbMax(5.0)
            .roeMin(10.0)
            .revenueGrowthMin(5.0)
            .marketCapMin(50.0)
            .marketCapMax(5000.0)
            .build();

        assertEquals(5.0, filter.getPeMin());
        assertEquals(30.0, filter.getPeMax());
        assertEquals(1.0, filter.getPbMin());
        assertEquals(5.0, filter.getPbMax());
        assertEquals(10.0, filter.getRoeMin());
        assertEquals(5.0, filter.getRevenueGrowthMin());
        assertEquals(50.0, filter.getMarketCapMin());
        assertEquals(5000.0, filter.getMarketCapMax());
    }

    @Test
    void testLeaderScoreResult() {
        LeaderScoreResult result = new LeaderScoreResult(
            "600519", 95.5, 90.0, 85.0, 95.0, 98.0);

        assertEquals("600519", result.getCode());
        assertEquals(95.5, result.getTotalScore());
        assertEquals(90.0, result.getMarketCapScore());
        assertEquals(85.0, result.getRevenueScore());
        assertEquals(95.0, result.getRoeScore());
        assertEquals(98.0, result.getBrandScore());
    }

    @Test
    void testCachedStockEntry() {
        CachedStockEntry entry = new CachedStockEntry("600519");

        CachedStockEntry.QuoteData quoteData = new CachedStockEntry.QuoteData();
        quoteData.setPrice(100.0);
        quoteData.setChangePct(2.5);
        quoteData.setVolume(1000000L);
        quoteData.setUpdatedAt(Instant.now());
        quoteData.setDataSource("mock");
        entry.setQuote(quoteData);

        CachedStockEntry.FundamentalData fundamentalData = new CachedStockEntry.FundamentalData();
        fundamentalData.setPeTtm(20.0);
        fundamentalData.setPb(3.0);
        fundamentalData.setRoe(15.0);
        fundamentalData.setMarketCap(500.0);
        fundamentalData.setUpdatedAt(Instant.now());
        fundamentalData.setDataSource("mock");
        entry.setFundamental(fundamentalData);

        assertEquals("600519", entry.getCode());
        assertNotNull(entry.getQuote());
        assertNotNull(entry.getFundamental());
        assertEquals(100.0, entry.getQuote().getPrice());
        assertEquals(20.0, entry.getFundamental().getPeTtm());
    }

    @Test
    void testStockEntity() {
        StockEntity entity = new StockEntity("600519", "贵州茅台");
        entity.setPeTtm(45.0);
        entity.setPb(15.0);
        entity.setRoe(30.0);
        entity.setMarketCap(2000.0);
        entity.setRevenueGrowth(15.0);
        entity.setIndustryL1("食品饮料");

        assertEquals("600519", entity.getCode());
        assertEquals("贵州茅台", entity.getName());
        assertEquals(45.0, entity.getPeTtm());
        assertEquals(15.0, entity.getPb());
        assertEquals(30.0, entity.getRoe());
        assertEquals(2000.0, entity.getMarketCap());
        assertEquals(15.0, entity.getRevenueGrowth());
        assertEquals("食品饮料", entity.getIndustryL1());
    }

    private List<StockEntity> createTestStocks() {
        List<StockEntity> stocks = new ArrayList<>();

        StockEntity stock1 = new StockEntity("600519", "贵州茅台");
        stock1.setPeTtm(45.0);
        stock1.setPb(15.0);
        stock1.setRoe(30.0);
        stock1.setMarketCap(2000.0);
        stock1.setRevenueGrowth(15.0);
        stocks.add(stock1);

        StockEntity stock2 = new StockEntity("000858", "五粮液");
        stock2.setPeTtm(25.0);
        stock2.setPb(8.0);
        stock2.setRoe(20.0);
        stock2.setMarketCap(800.0);
        stock2.setRevenueGrowth(10.0);
        stocks.add(stock2);

        StockEntity stock3 = new StockEntity("000568", "泸州老窖");
        stock3.setPeTtm(15.0);
        stock3.setPb(5.0);
        stock3.setRoe(12.0);
        stock3.setMarketCap(300.0);
        stock3.setRevenueGrowth(5.0);
        stocks.add(stock3);

        return stocks;
    }
}
