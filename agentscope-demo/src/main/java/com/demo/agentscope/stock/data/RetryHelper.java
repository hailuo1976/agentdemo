package com.demo.agentscope.stock.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试工具,等价 Python stocktools/errors.py 的 {@code @retry_on_network}。
 * 仅对 NETWORK 和 RATE_LIMIT 类型的异常重试;PERMISSION / DATA_UNAVAILABLE 立即抛出。
 *
 * <p>参数默认值:max_attempts=3, base_delay=0.8s, backoff=2x, jitter=0.3s
 */
public final class RetryHelper {

    private static final Logger log = LoggerFactory.getLogger(RetryHelper.class);

    @FunctionalInterface
    public interface Retryable<T> {
        T call() throws DataSourceException;
    }

    public static <T> T retry(Retryable<T> action, String label) throws DataSourceException {
        int maxAttempts = 3;
        double delay = 0.8;
        double backoff = 2.0;
        double jitter = 0.3;

        DataSourceException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (DataSourceException e) {
                last = e;
                if (!e.isRetryable() || attempt == maxAttempts) {
                    throw e;
                }
                double sleepFor = delay + ThreadLocalRandom.current().nextDouble(0, jitter);
                log.info("{}: attempt {}/{} failed ({}); sleeping {}s",
                        label, attempt, maxAttempts, e.getMessage(), String.format("%.2f", sleepFor));
                try {
                    Thread.sleep((long) (sleepFor * 1000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                delay *= backoff;
            }
        }
        throw last;
    }

    private RetryHelper() {
    }
}
