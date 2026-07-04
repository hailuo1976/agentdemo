package com.demo.agentscope.stock.data;

import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.stock.model.CachedStockEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * TuShare 数据源实现。
 * Token 制,免费版有严格接口权限与频次限制。
 *
 * <p>对齐 Python stocktools/fetchers.py:
 * <ul>
 *   <li>{@code fetchQuote} 改用 {@code ts.pro_bar(adj='qfq')} 拿前复权价,
 *       而非 {@code pro.daily()}(未复权,与 akshare 不可比)。</li>
 *   <li>{@code convertToTuShareCode} 修正边界:6/9 → .SH,0/3 → .SZ。</li>
 *   <li>tushare 错误经 {@link #classifyError(String)} 分类。</li>
 * </ul>
 */
public class TuShareDataSource implements StockDataSource {

    private static final Logger log = LoggerFactory.getLogger(TuShareDataSource.class);

    private final CodeExecutionManager executionManager;
    private final ObjectMapper objectMapper;
    private final String tushareToken;

    // isAvailable() 结果缓存:首次探测后复用,避免每次 fetch 都 spawn python 子进程
    private volatile Boolean availabilityCache;

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
        StockDataUtils.validateCode(code);
        String tsCode = convertToTuShareCode(code);
        String today = LocalDate.now().toString().replace("-", "");
        String startOffset = LocalDate.now().minus(10, ChronoUnit.DAYS).toString().replace("-", "");
        String pythonCode = QUOTE_SCRIPT.formatted(tsCode, startOffset, today);

        return RetryHelper.retry(() -> {
            CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
            if (!result.isSuccess()) {
                throw classifyError("tushare 执行失败: " + result.getStderr());
            }
            JsonNode node = StockDataUtils.parseStdoutJson(objectMapper, log,
                    result.getStdout(), "tushare");
            if (node == null) {
                throw new DataSourceException(DataSourceException.Type.UNKNOWN,
                        "tushare 无 JSON 输出");
            }
            if (node.has("error")) {
                throw classifyError(node.path("error").asText());
            }
            return StockDataUtils.buildQuote(node, getName());
        }, "tushare.fetchQuote");
    }

    @Override
    public CachedStockEntry.FundamentalData fetchFundamental(String code) throws DataSourceException {
        StockDataUtils.validateCode(code);
        String tsCode = convertToTuShareCode(code);
        String pythonCode = FUNDAMENTAL_SCRIPT.formatted(tsCode);

        return RetryHelper.retry(() -> {
            CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
            if (!result.isSuccess()) {
                throw classifyError("tushare 执行失败: " + result.getStderr());
            }
            JsonNode node = StockDataUtils.parseStdoutJson(objectMapper, log,
                    result.getStdout(), "tushare");
            if (node == null) {
                throw new DataSourceException(DataSourceException.Type.UNKNOWN,
                        "tushare 无 JSON 输出");
            }
            if (node.has("error")) {
                throw classifyError(node.path("error").asText());
            }
            return StockDataUtils.buildFundamental(node, getName());
        }, "tushare.fetchFundamental");
    }

    @Override
    public CachedStockEntry.IndustryRankData fetchIndustryRank(String code) throws DataSourceException {
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
        if (tushareToken == null || tushareToken.isBlank()) {
            availabilityCache = false;
            return false;
        }
        CodeExecutionManager.ExecutionResult result =
                executionManager.executePython("import tushare; print('ok')");
        availabilityCache = result.isSuccess();
        return availabilityCache;
    }

    // ==================== 内部工具 ====================

    /**
     * 股票代码 → tushare ts_code。
     * <ul>
     *   <li>6/9 开头 → .SH(上交所,9 开头是 B 股)</li>
     *   <li>0/3 开头 → .SZ(深交所,3 开头是创业板)</li>
     * </ul>
     */
    private static String convertToTuShareCode(String code) {
        char first = code.charAt(0);
        return (first == '6' || first == '9') ? code + ".SH" : code + ".SZ";
    }

    /**
     * 把 tushare 的混合错误信息归类到我们的错误类型。
     */
    private static DataSourceException classifyError(String message) {
        if (message == null) {
            return new DataSourceException(DataSourceException.Type.UNKNOWN, "tushare 未知错误");
        }
        String lower = message.toLowerCase();
        if (lower.contains("5000") || lower.contains("5001") || lower.contains("5002")
                || lower.contains("频次") || lower.contains("rate") || lower.contains("limit")) {
            return new DataSourceException(DataSourceException.Type.RATE_LIMIT, message);
        }
        if (lower.contains("40001") || lower.contains("40002") || lower.contains("权限")
                || lower.contains("permission")) {
            return new DataSourceException(DataSourceException.Type.PERMISSION, message);
        }
        if (lower.contains("connection") || lower.contains("timeout") || lower.contains("网络")
                || lower.contains("remote")) {
            return new DataSourceException(DataSourceException.Type.NETWORK, message);
        }
        if (lower.contains("无数据") || lower.contains("empty") || lower.contains("no rows")) {
            return new DataSourceException(DataSourceException.Type.DATA_UNAVAILABLE, message);
        }
        return new DataSourceException(DataSourceException.Type.UNKNOWN, message);
    }

    // ==================== Python 脚本模板 ====================

    /**
     * pro_bar(adj='qfq') 拿前复权日线。
     */
    private static final String QUOTE_SCRIPT = """
            import os, sys, json
            from datetime import datetime
            try:
                import tushare as ts
            except ImportError:
                print(json.dumps({"error": "tushare 未安装"}))
                sys.exit(0)

            token = os.getenv("TUSHARE_TOKEN")
            if not token:
                print(json.dumps({"error": "权限: TUSHARE_TOKEN 未配置"}))
                sys.exit(0)

            ts_code = "%s"
            start = "%s"
            end = "%s"
            try:
                ts.set_token(token)
                df = ts.pro_bar(ts_code=ts_code, start_date=start, end_date=end, adj="qfq", freq="D")
            except Exception as e:
                print(json.dumps({"error": str(e)}))
                sys.exit(0)

            if df is None or df.empty:
                print(json.dumps({"error": "无数据: " + ts_code}))
                sys.exit(0)

            row = df.iloc[-1]
            prev = df.iloc[-2] if len(df) > 1 else row
            close = float(row["close"])
            prev_close = float(prev["close"])
            change_pct = (close - prev_close) / prev_close * 100 if prev_close else float(row.get("pct_chg", 0.0) or 0.0)
            print(json.dumps({
                "price": close,
                "change_pct": change_pct,
                "volume": int(row.get("vol", 0) or 0),
                "updated_at": datetime.now().isoformat(),
                "data_source": "tushare"
            }))
            """;

    /**
     * daily_basic 取 PE/PB/总市值,fina_indicator 取 ROE。
     */
    private static final String FUNDAMENTAL_SCRIPT = """
            import os, sys, json
            from datetime import datetime
            try:
                import tushare as ts
            except ImportError:
                print(json.dumps({"error": "tushare 未安装"}))
                sys.exit(0)

            token = os.getenv("TUSHARE_TOKEN")
            if not token:
                print(json.dumps({"error": "权限: TUSHARE_TOKEN 未配置"}))
                sys.exit(0)

            ts_code = "%s"
            ts.set_token(token)
            pro = ts.pro_api()

            try:
                df_basic = pro.daily_basic(ts_code=ts_code, fields="pe_ttm,pb,total_mv")
            except Exception as e:
                print(json.dumps({"error": str(e)}))
                sys.exit(0)
            if df_basic is None or df_basic.empty:
                print(json.dumps({"error": "无数据: " + ts_code}))
                sys.exit(0)
            row = df_basic.iloc[0]

            roe = 0.0
            try:
                df_fina = pro.fina_indicator(ts_code=ts_code, fields="roe")
                if df_fina is not None and not df_fina.empty:
                    roe = float(df_fina.iloc[0]["roe"] or 0.0)
            except Exception:
                pass

            print(json.dumps({
                "pe_ttm": float(row.get("pe_ttm") or 0.0),
                "pb": float(row.get("pb") or 0.0),
                "roe": roe,
                "market_cap": float(row.get("total_mv") or 0.0) / 10000,
                "updated_at": datetime.now().isoformat(),
                "data_source": "tushare"
            }))
            """;
}
