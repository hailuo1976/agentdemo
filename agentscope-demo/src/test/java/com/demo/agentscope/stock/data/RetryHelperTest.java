package com.demo.agentscope.stock.data;

import com.demo.agentscope.stock.data.RetryHelper.Retryable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryHelper 单元测试。
 * 对齐 Python stocktools/errors.py:retry_on_network 的语义。
 */
public class RetryHelperTest {

    @Test
    void networkError_retriesAndSucceedsOnThirdAttempt() throws DataSourceException {
        AtomicInteger calls = new AtomicInteger(0);
        Retryable<String> action = () -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new DataSourceException(DataSourceException.Type.NETWORK,
                        "模拟网络抖动 #" + n);
            }
            return "ok";
        };

        String result = RetryHelper.retry(action, "test.network");
        assertEquals("ok", result);
        assertEquals(3, calls.get(), "前两次 NETWORK 失败应被重试");
    }

    @Test
    void permissionError_doesNotRetry() {
        AtomicInteger calls = new AtomicInteger(0);
        Retryable<String> action = () -> {
            calls.incrementAndGet();
            throw new DataSourceException(DataSourceException.Type.PERMISSION,
                    "token 权限不足,重试无意义");
        };

        DataSourceException ex = assertThrows(DataSourceException.class,
                () -> RetryHelper.retry(action, "test.permission"));
        assertEquals(DataSourceException.Type.PERMISSION, ex.getType());
        assertEquals(1, calls.get(), "PERMISSION 不可重试,应立即抛出");
    }

    @Test
    void allNetworkFails_throwsAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger(0);
        Retryable<String> action = () -> {
            calls.incrementAndGet();
            throw new DataSourceException(DataSourceException.Type.NETWORK,
                    "持续断连");
        };

        DataSourceException ex = assertThrows(DataSourceException.class,
                () -> RetryHelper.retry(action, "test.exhausted"));
        assertEquals(DataSourceException.Type.NETWORK, ex.getType());
        assertEquals(3, calls.get(), "应尝试满 3 次后放弃");
    }
}
