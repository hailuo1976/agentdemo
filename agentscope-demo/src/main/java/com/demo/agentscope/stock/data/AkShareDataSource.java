package com.demo.agentscope.stock.data;

import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.stock.model.CachedStockEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

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
        String pythonCode = "import akshare as ak\n"
            + "import json\n"
            + "from datetime import datetime\n"
            + "\n"
            + "# 市盈率、市净率\n"
            + "df_indicator = ak.stock_a_indicator_lg(symbol='" + code + "')\n"
            + "pe_ttm = float(df_indicator.iloc[-1]['pe_ttm'])\n"
            + "pb = float(df_indicator.iloc[-1]['pb'])\n"
            + "\n"
            + "# 财务指标（ROE）\n"
            + "df_finance = ak.stock_financial_analysis_indicator(symbol='" + code + "')\n"
            + "roe = float(df_finance.iloc[0]['净资产收益率(%)'])\n"
            + "\n"
            + "# 总市值\n"
            + "df_spot = ak.stock_zh_a_spot_em()\n"
            + "row = df_spot[df_spot['代码'] == '" + code + "']\n"
            + "market_cap = float(row.iloc[0]['总市值']) / 100000000  # 转为亿元\n"
            + "\n"
            + "data = {\n"
            + "    \"pe_ttm\": pe_ttm,\n"
            + "    \"pb\": pb,\n"
            + "    \"roe\": roe,\n"
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
        // akshare 没有直接的行业排名接口，返回空数据
        CachedStockEntry.IndustryRankData rankData = new CachedStockEntry.IndustryRankData();
        rankData.setRankInL2(0);
        rankData.setTotalInL2(0);
        rankData.setUpdatedAt(Instant.now());
        return rankData;
    }

    @Override
    public boolean isAvailable() {
        // 检查 akshare 是否安装
        String checkCode = "import akshare; print('ok')";
        CodeExecutionManager.ExecutionResult result = executionManager.executePython(checkCode);
        return result.isSuccess();
    }
}
