package com.demo.agentscope.stock.data;

import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.stock.model.CachedStockEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * AkShare 数据源实现。
 * 免费、无需 token、覆盖全 A 股,但接口偶发不稳定(RemoteDisconnected)。
 *
 * <p>对齐 Python stocktools/fetchers.py:
 * <ul>
 *   <li>{@code fetchQuote} 用单股历史接口 {@code stock_zh_a_hist}(Eastmoney qfq) +
 *       {@code stock_zh_a_daily}(Sina qfq) 双源兜底,不再扫描全市场。</li>
 *   <li>{@code fetchFundamental} 用 {@code stock_individual_info_em} 拿单股市值,
 *       不再调用 {@code stock_zh_a_spot_em}。</li>
 *   <li>所有调用经 {@link RetryHelper} 重试(等价 {@code @retry_on_network})。</li>
 *   <li>入口校验 code 仅含 6 位数字,防字符串注入。</li>
 * </ul>
 */
public class AkShareDataSource implements StockDataSource {

    private static final Logger log = LoggerFactory.getLogger(AkShareDataSource.class);

    private final CodeExecutionManager executionManager;
    private final ObjectMapper objectMapper;

    // isAvailable() 结果缓存:首次探测后复用,避免每次 fetch 都 spawn python 子进程
    private volatile Boolean availabilityCache;

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
        validateCode(code);

        String pythonCode = QUOTE_SCRIPT.formatted(code);

        return RetryHelper.retry(() -> {
            CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
            if (!result.isSuccess()) {
                // Python 执行失败、连接中断等 → 可重试
                throw new DataSourceException(DataSourceException.Type.NETWORK,
                        "akshare 执行失败: " + result.getStderr());
            }
            JsonNode node = parseJson(result.getStdout());
            if (node == null || node.has("error")) {
                String msg = node != null ? node.path("error").asText() : "无 JSON 输出";
                // akshare 两个端点都失败 → 网络/源站问题,可重试
                throw new DataSourceException(DataSourceException.Type.NETWORK,
                        "akshare 行情获取失败: " + msg);
            }
            return buildQuote(node);
        }, "akshare.fetchQuote");
    }

    @Override
    public CachedStockEntry.FundamentalData fetchFundamental(String code) throws DataSourceException {
        validateCode(code);

        String pythonCode = FUNDAMENTAL_SCRIPT.formatted(code);

        return RetryHelper.retry(() -> {
            CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
            if (!result.isSuccess()) {
                throw new DataSourceException(DataSourceException.Type.NETWORK,
                        "akshare 执行失败: " + result.getStderr());
            }
            JsonNode node = parseJson(result.getStdout());
            if (node == null || node.has("error")) {
                String msg = node != null ? node.path("error").asText() : "无 JSON 输出";
                // 接口正常但取不到数据 → 不重试
                throw new DataSourceException(DataSourceException.Type.DATA_UNAVAILABLE,
                        "akshare 基本面获取失败: " + msg);
            }
            return buildFundamental(node);
        }, "akshare.fetchFundamental");
    }

    @Override
    public CachedStockEntry.IndustryRankData fetchIndustryRank(String code) throws DataSourceException {
        // akshare 没有直接的行业排名接口,返回占位数据
        CachedStockEntry.IndustryRankData rankData = new CachedStockEntry.IndustryRankData();
        rankData.setRankInL2(0);
        rankData.setTotalInL2(0);
        rankData.setUpdatedAt(Instant.now());
        return rankData;
    }

    @Override
    public boolean isAvailable() {
        if (availabilityCache != null) {
            return availabilityCache;
        }
        String checkCode = "import akshare; print('ok')";
        CodeExecutionManager.ExecutionResult result = executionManager.executePython(checkCode);
        boolean ok = result.isSuccess();
        availabilityCache = ok;
        return ok;
    }

    // ==================== 内部工具 ====================

    private static void validateCode(String code) throws DataSourceException {
        if (code == null || !code.matches("\\d{6}")) {
            throw new DataSourceException("非法股票代码: " + code);
        }
    }

    private JsonNode parseJson(String stdout) {
        try {
            return objectMapper.readTree(stdout);
        } catch (Exception e) {
            log.warn("akshare JSON 解析失败: {}", stdout);
            return null;
        }
    }

    private CachedStockEntry.QuoteData buildQuote(JsonNode node) throws DataSourceException {
        try {
            CachedStockEntry.QuoteData q = new CachedStockEntry.QuoteData();
            q.setPrice(node.get("price").asDouble());
            q.setChangePct(node.get("change_pct").asDouble());
            q.setVolume(node.get("volume").asLong());
            q.setUpdatedAt(Instant.parse(node.get("updated_at").asText()));
            q.setDataSource("akshare");
            return q;
        } catch (Exception e) {
            throw new DataSourceException(DataSourceException.Type.UNKNOWN,
                    "解析行情数据失败", e);
        }
    }

    private CachedStockEntry.FundamentalData buildFundamental(JsonNode node) throws DataSourceException {
        try {
            CachedStockEntry.FundamentalData f = new CachedStockEntry.FundamentalData();
            f.setPeTtm(node.get("pe_ttm").asDouble());
            f.setPb(node.get("pb").asDouble());
            f.setRoe(node.get("roe").asDouble());
            f.setMarketCap(node.get("market_cap").asDouble());
            f.setUpdatedAt(Instant.parse(node.get("updated_at").asText()));
            f.setDataSource("akshare");
            return f;
        } catch (Exception e) {
            throw new DataSourceException(DataSourceException.Type.UNKNOWN,
                    "解析基本面数据失败", e);
        }
    }

    // ==================== Python 脚本模板 ====================

    /**
     * 行情:Eastmoney 单股历史 qfq 优先 → Sina 单股 qfq 兜底。
     * 取近 10 个交易日(够算最新价/涨跌幅/成交量),不再扫全市场。
     */
    private static final String QUOTE_SCRIPT = """
            import akshare as ak
            import json
            import sys
            from datetime import datetime, timedelta

            code = "%s"
            end = datetime.now().strftime("%%Y%%m%%d")
            start = (datetime.now() - timedelta(days=10)).strftime("%%Y%%m%%d")

            last_exc = None
            df = None

            # Source 1: Eastmoney (forward-adjusted)
            try:
                df = ak.stock_zh_a_hist(symbol=code, period="daily",
                                        start_date=start, end_date=end, adjust="qfq")
                if df is None or df.empty:
                    df = None
            except Exception as e:
                last_exc = e

            # Source 2: Sina fallback (forward-adjusted)
            if df is None:
                try:
                    prefix = "sh" if code.startswith(("6", "9")) else "sz"
                    df = ak.stock_zh_a_daily(symbol=prefix + code,
                                             start_date=start, end_date=end, adjust="qfq")
                except Exception as e:
                    last_exc = e

            if df is None or df.empty:
                print(json.dumps({"error": "akshare 两个端点均失败: " + str(last_exc)}))
                sys.exit(0)

            col_map = {"日期": "date", "开盘": "open", "最高": "high", "最低": "low",
                       "收盘": "close", "成交量": "volume", "成交额": "amount"}
            df = df.rename(columns={k: v for k, v in col_map.items() if k in df.columns})

            row = df.iloc[-1]
            prev = df.iloc[-2] if len(df) > 1 else row
            price = float(row["close"])
            prev_close = float(prev["close"])
            change_pct = (price - prev_close) / prev_close * 100 if prev_close else 0.0
            print(json.dumps({
                "price": price,
                "change_pct": change_pct,
                "volume": int(row.get("volume", 0) or 0),
                "updated_at": datetime.now().isoformat(),
                "data_source": "akshare"
            }))
            """;

    /**
     * 基本面:PE/PB 用 stock_a_indicator_lg,ROE 用 stock_financial_analysis_indicator,
     * 总市值用单股定点 stock_individual_info_em(不再扫全市场)。
     */
    private static final String FUNDAMENTAL_SCRIPT = """
            import akshare as ak
            import json
            import sys
            from datetime import datetime

            code = "%s"

            # PE/PB
            try:
                df_ind = ak.stock_a_indicator_lg(symbol=code)
                last = df_ind.iloc[-1]
                pe_ttm = float(last["pe_ttm"]) if "pe_ttm" in df_ind.columns else None
                pb = float(last["pb"]) if "pb" in df_ind.columns else None
            except Exception as e:
                print(json.dumps({"error": "stock_a_indicator_lg 失败: " + str(e)}))
                sys.exit(0)

            # ROE
            roe = None
            try:
                df_fin = ak.stock_financial_analysis_indicator(symbol=code)
                if df_fin is not None and not df_fin.empty:
                    roe = float(df_fin.iloc[0]["净资产收益率(%)"])
            except Exception:
                pass

            # 总市值(单股定点接口)
            market_cap = None
            try:
                df_info = ak.stock_individual_info_em(symbol=code)
                row = df_info[df_info["item"] == "总市值"]
                if not row.empty:
                    market_cap = float(row.iloc[0]["value"]) / 1e8  # 转亿元
            except Exception as e:
                pass

            if pe_ttm is None and pb is None and roe is None and market_cap is None:
                print(json.dumps({"error": "akshare 基本面字段全部取不到"}))
                sys.exit(0)

            print(json.dumps({
                "pe_ttm": pe_ttm if pe_ttm is not None else 0.0,
                "pb": pb if pb is not None else 0.0,
                "roe": roe if roe is not None else 0.0,
                "market_cap": market_cap if market_cap is not None else 0.0,
                "updated_at": datetime.now().isoformat(),
                "data_source": "akshare"
            }))
            """;
}
