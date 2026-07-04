package com.demo.agentscope.stock.data;

import com.demo.agentscope.stock.model.CachedStockEntry;

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
