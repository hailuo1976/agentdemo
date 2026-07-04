package com.demo.agentscope.stock.data;

/**
 * 数据源异常。
 */
public class DataSourceException extends Exception {

    public DataSourceException(String message) {
        super(message);
    }

    public DataSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
