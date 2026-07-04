package com.demo.agentscope.stock.data;

import com.demo.agentscope.stock.model.CachedStockEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * {@link StockDataSource} 实现共享的纯函数工具。
 * 抽出来主要是消除 AkShare / TuShare 两个实现里字节级重复的 validateCode / parseJson / build*。
 */
final class StockDataUtils {

    /** A 股 6 位数字代码。 */
    static final Pattern CODE_PATTERN = Pattern.compile("\\d{6}");

    private StockDataUtils() {
    }

    /**
     * 入口校验:6 位数字,防字符串注入到 Python 脚本模板。
     */
    static void validateCode(String code) throws DataSourceException {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new DataSourceException("非法股票代码: " + code);
        }
    }

    /**
     * 解析 Python 脚本 stdout 为 JsonNode;失败返回 null(由调用侧决定是否兜底/重试)。
     */
    static JsonNode parseStdoutJson(ObjectMapper mapper, Logger log, String stdout, String label) {
        try {
            return mapper.readTree(stdout);
        } catch (Exception e) {
            log.warn("{} JSON 解析失败: {}", label, stdout);
            return null;
        }
    }

    /**
     * 行情 JSON → {@link CachedStockEntry.QuoteData}。
     *
     * @param dataSourceName 写入 {@code setDataSource},通常传 {@code StockDataSource.getName()}
     */
    static CachedStockEntry.QuoteData buildQuote(JsonNode node, String dataSourceName)
            throws DataSourceException {
        try {
            CachedStockEntry.QuoteData q = new CachedStockEntry.QuoteData();
            q.setPrice(node.get("price").asDouble());
            q.setChangePct(node.get("change_pct").asDouble());
            q.setVolume(node.get("volume").asLong());
            q.setUpdatedAt(Instant.parse(node.get("updated_at").asText()));
            q.setDataSource(dataSourceName);
            return q;
        } catch (Exception e) {
            throw new DataSourceException(DataSourceException.Type.UNKNOWN,
                    "解析行情数据失败", e);
        }
    }

    /**
     * 基本面 JSON → {@link CachedStockEntry.FundamentalData}。
     *
     * @param dataSourceName 写入 {@code setDataSource},通常传 {@code StockDataSource.getName()}
     */
    static CachedStockEntry.FundamentalData buildFundamental(JsonNode node, String dataSourceName)
            throws DataSourceException {
        try {
            CachedStockEntry.FundamentalData f = new CachedStockEntry.FundamentalData();
            f.setPeTtm(node.get("pe_ttm").asDouble());
            f.setPb(node.get("pb").asDouble());
            f.setRoe(node.get("roe").asDouble());
            f.setMarketCap(node.get("market_cap").asDouble());
            f.setUpdatedAt(Instant.parse(node.get("updated_at").asText()));
            f.setDataSource(dataSourceName);
            return f;
        } catch (Exception e) {
            throw new DataSourceException(DataSourceException.Type.UNKNOWN,
                    "解析基本面数据失败", e);
        }
    }
}
