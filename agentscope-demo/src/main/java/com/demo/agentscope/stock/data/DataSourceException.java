package com.demo.agentscope.stock.data;

/**
 * 数据源异常。携带错误类型,用于决定是否重试。
 * 对齐 Python stocktools/errors.py 的层级语义。
 */
public class DataSourceException extends Exception {

    public enum Type {
        NETWORK,          // 连接中断/超时 → 可重试
        RATE_LIMIT,       // 429 / 限频 → 可重试
        PERMISSION,       // token 权限不足 → 不重试
        DATA_UNAVAILABLE, // 接口正常返回但无数据 → 不重试
        UNKNOWN           // 其他 → 不重试
    }

    private final Type type;

    public DataSourceException(String message) {
        this(Type.UNKNOWN, message, null);
    }

    public DataSourceException(Type type, String message) {
        this(type, message, null);
    }

    public DataSourceException(String message, Throwable cause) {
        this(Type.UNKNOWN, message, cause);
    }

    public DataSourceException(Type type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    /**
     * NETWORK 和 RATE_LIMIT 可重试,其余重试无意义。
     */
    public boolean isRetryable() {
        return type == Type.NETWORK || type == Type.RATE_LIMIT;
    }
}
