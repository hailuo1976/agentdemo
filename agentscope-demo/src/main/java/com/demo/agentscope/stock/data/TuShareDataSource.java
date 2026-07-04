package com.demo.agentscope.stock.data;

import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.stock.model.CachedStockEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;

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
        String pythonCode = 
            "import tushare as ts\n" +
            "import json\n" +
            "from datetime import datetime\n" +
            "import os\n" +
            "\n" +
            "token = os.getenv('TUSHARE_TOKEN')\n" +
            "pro = ts.pro_api(token)\n" +
            "\n" +
            "# 获取最新行情\n" +
            "df = pro.daily(ts_code='" + tsCode + "', start_date='" + dateStr + "', end_date='" + dateStr + "')\n" +
            "if df.empty:\n" +
            "    print(json.dumps({\"error\": \"无数据\"}))\n" +
            "else:\n" +
            "    row = df.iloc[0]\n" +
            "    data = {\n" +
            "        \"price\": float(row['close']),\n" +
            "        \"change_pct\": float(row['pct_chg']),\n" +
            "        \"volume\": int(row['vol']),\n" +
            "        \"updated_at\": datetime.now().isoformat(),\n" +
            "        \"data_source\": \"tushare\"\n" +
            "    }\n" +
            "    print(json.dumps(data))\n";

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
            "import tushare as ts\n" +
            "import json\n" +
            "from datetime import datetime\n" +
            "import os\n" +
            "\n" +
            "token = os.getenv('TUSHARE_TOKEN')\n" +
            "pro = ts.pro_api(token)\n" +
            "\n" +
            "# 基本面指标\n" +
            "df = pro.daily_basic(ts_code='" + tsCode + "', fields='pe_ttm,pb,total_mv')\n" +
            "row = df.iloc[0]\n" +
            "\n" +
            "# 财务指标（ROE）\n" +
            "df_fina = pro.fina_indicator(ts_code='" + tsCode + "', fields='roe')\n" +
            "roe = float(df_fina.iloc[0]['roe']) if not df_fina.empty else 0.0\n" +
            "\n" +
            "data = {\n" +
            "    \"pe_ttm\": float(row['pe_ttm']),\n" +
            "    \"pb\": float(row['pb']),\n" +
            "    \"roe\": roe,\n" +
            "    \"market_cap\": float(row['total_mv']) / 10000,  # 万元转亿元\n" +
            "    \"updated_at\": datetime.now().isoformat(),\n" +
            "    \"data_source\": \"tushare\"\n" +
            "}\n" +
            "print(json.dumps(data))\n";

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
        // tushare 行业排名接口较复杂，暂时返回空数据
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

        // 检查 tushare 是否安装
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
