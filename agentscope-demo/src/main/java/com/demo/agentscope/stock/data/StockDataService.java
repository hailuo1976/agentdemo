package com.demo.agentscope.stock.data;

import com.demo.agentscope.stock.industry.IndustryService;
import com.demo.agentscope.stock.model.CachedStockEntry;
import com.demo.agentscope.stock.model.StockEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

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

    /** TTL 配置 */
    private final Map<String, Duration> ttlConfig;

    /** Jackson ObjectMapper */
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

        // 初始化 TTL 配置
        this.ttlConfig = new HashMap<>();
        this.ttlConfig.put("quote", Duration.ofHours(1));
        this.ttlConfig.put("fundamental", Duration.ofHours(24));
        this.ttlConfig.put("industry_rank", Duration.ofDays(7));
    }

    /**
     * 获取单只股票的完整数据。
     * 优先读缓存，过期则刷新。
     */
    public StockEntity getStockData(String code, boolean forceRefresh) {
        // 1. 读缓存
        CachedStockEntry entry = loadFromCache(code);

        // 2. 检查各段是否过期
        boolean quoteExpired = entry == null || entry.isExpired("quote", ttlConfig.get("quote"));
        boolean fundamentalExpired = entry == null || entry.isExpired("fundamental", ttlConfig.get("fundamental"));
        boolean rankExpired = entry == null || entry.isExpired("industry_rank", ttlConfig.get("industry_rank"));

        if (!forceRefresh && !quoteExpired && !fundamentalExpired && !rankExpired) {
            return convertToStockEntity(entry);
        }

        // 3. 刷新过期段
        if (entry == null) entry = new CachedStockEntry(code);

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

        // 4. 写回缓存
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
     * 支持并发控制（akshare 限流）。
     */
    public List<StockEntity> getIndustryStockData(String industryName, int level) {
        List<String> codes = industryService.getStockCodesByIndustry(industryName, level);
        List<StockEntity> results = new ArrayList<>();

        for (String code : codes) {
            try {
                StockEntity entity = getStockData(code, false);
                results.add(entity);
                Thread.sleep(200); // akshare 限流：间隔 ≥ 200ms
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
                boolean needRefresh = force || isExpired(code, dataType);
                if (!needRefresh) {
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
        if ("single".equals(scope)) {
            return List.of(target);
        } else if ("industry".equals(scope)) {
            return industryService.getStockCodesByIndustry(target, 2);
        } else {
            // market scope - 返回全部股票（这里简化为返回空列表）
            return Collections.emptyList();
        }
    }

    private boolean isExpired(String code, String dataType) {
        CachedStockEntry entry = loadFromCache(code);
        if (entry == null) return true;

        if ("all".equals(dataType)) {
            return entry.isExpired("quote", ttlConfig.get("quote")) ||
                   entry.isExpired("fundamental", ttlConfig.get("fundamental")) ||
                   entry.isExpired("industry_rank", ttlConfig.get("industry_rank"));
        } else {
            return entry.isExpired(dataType, ttlConfig.get(dataType));
        }
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
     * 缓存读写（原子写入）。
     */
    private void saveToCache(CachedStockEntry entry) {
        Path file = cacheDir.resolve(entry.getCode() + ".json");
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");

        try {
            Files.createDirectories(cacheDir);
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
            String json = Files.readString(file);
            return objectMapper.readValue(json, CachedStockEntry.class);
        } catch (IOException e) {
            log.warn("缓存读取失败: {}", code, e);
            return null;
        }
    }

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

        return entity;
    }

    /**
     * 刷新结果
     */
    public static class RefreshResult {
        private final String scope;
        private final String target;
        private final int total;
        private final int refreshed;
        private final int skipped;
        private final int failed;
        private final List<FailureInfo> failures;

        public RefreshResult(String scope, String target, int total, int refreshed, int skipped, int failed, List<FailureInfo> failures) {
            this.scope = scope;
            this.target = target;
            this.total = total;
            this.refreshed = refreshed;
            this.skipped = skipped;
            this.failed = failed;
            this.failures = failures;
        }

        public String getScope() { return scope; }
        public String getTarget() { return target; }
        public int getTotal() { return total; }
        public int getRefreshed() { return refreshed; }
        public int getSkipped() { return skipped; }
        public int getFailed() { return failed; }
        public List<FailureInfo> getFailures() { return failures; }
    }

    /**
     * 失败信息
     */
    public static class FailureInfo {
        private final String code;
        private final String error;

        public FailureInfo(String code, String error) {
            this.code = code;
            this.error = error;
        }

        public String getCode() { return code; }
        public String getError() { return error; }
    }
}
