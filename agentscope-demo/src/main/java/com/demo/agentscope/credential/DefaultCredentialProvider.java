package com.demo.agentscope.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 默认凭证提供者实现。
 * <p>
 * 从环境变量和可变配置映射中读取各提供商的凭证信息。
 * 支持 primary + fallback 机制：当主提供商的 API Key 不可用时，
 * 自动切换到备用提供商。
 * </p>
 *
 * <pre>
 * 内置提供商配置：
 * - openai:    OPENAI_API_KEY,   base=https://api.openai.com/v1,       model=gpt-4o-mini
 * - dashscope: DASHSCOPE_API_KEY, base=https://dashscope.aliyuncs.com/compatible-mode/v1, model=qwen-plus
 * - anthropic: ANTHROPIC_API_KEY, base=https://api.anthropic.com/v1,    model=claude-sonnet-4-20250514
 * - deepseek:  DEEPSEEK_API_KEY,  base=https://api.deepseek.com/v1,     model=deepseek-chat
 *
 * 环境变量覆盖（优先级高于内置默认值）：
 * - MODEL_BASE_URL: 全局自定义 API 基础 URL（适配任意 OpenAI 兼容服务）
 * - MODEL_NAME:      全局自定义模型名称
 * - {PROVIDER}_BASE_URL: 提供商专属基础 URL（如 OPENAI_BASE_URL）
 * - {PROVIDER}_MODEL:    提供商专属模型名（如 OPENAI_MODEL）
 * </pre>
 */
public class DefaultCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultCredentialProvider.class);

    /** 提供商配置注册表 */
    private final Map<String, ProviderConfig> configs;

    /** 主提供商名称 */
    private volatile String primaryProvider;

    /** 备用提供商名称 */
    private volatile String fallbackProvider;

    /**
     * 提供商配置内部记录。
     */
    private record ProviderConfig(
            String apiKeyEnvVar,
            String defaultBaseUrl,
            String defaultModel
    ) {}

    public DefaultCredentialProvider() {
        this.configs = new LinkedHashMap<>();
        registerBuiltinProviders();
        log.info("默认凭证提供者已初始化，已注册 {} 个内置提供商", configs.size());
    }

    /**
     * 注册内置提供商配置。
     */
    private void registerBuiltinProviders() {
        configs.put("openai", new ProviderConfig(
                "OPENAI_API_KEY",
                "https://api.openai.com/v1",
                "gpt-4o-mini"
        ));
        configs.put("dashscope", new ProviderConfig(
                "DASHSCOPE_API_KEY",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus"
        ));
        configs.put("anthropic", new ProviderConfig(
                "ANTHROPIC_API_KEY",
                "https://api.anthropic.com/v1",
                "claude-sonnet-4-20250514"
        ));
        configs.put("deepseek", new ProviderConfig(
                "DEEPSEEK_API_KEY",
                "https://api.deepseek.com/v1",
                "deepseek-chat"
        ));
    }

    @Override
    public String getApiKey(String providerName) {
        // 尝试主提供商
        if (providerName == null && primaryProvider != null) {
            String key = doGetApiKey(primaryProvider);
            if (key != null) {
                return key;
            }
            // 主提供商不可用，尝试备用
            if (fallbackProvider != null) {
                log.debug("主提供商 [{}] API Key 不可用，回退到 [{}]", primaryProvider, fallbackProvider);
                return doGetApiKey(fallbackProvider);
            }
            return null;
        }

        // 指定了提供商名称
        if (providerName != null) {
            String key = doGetApiKey(providerName);
            if (key != null) {
                return key;
            }
            // 指定提供商不可用，尝试备用
            if (fallbackProvider != null && !fallbackProvider.equals(providerName)) {
                log.debug("提供商 [{}] API Key 不可用，回退到 [{}]", providerName, fallbackProvider);
                return doGetApiKey(fallbackProvider);
            }
            return null;
        }

        return null;
    }

    /**
     * 从环境变量中获取指定提供商的 API Key。
     */
    private String doGetApiKey(String providerName) {
        ProviderConfig config = configs.get(providerName);
        if (config == null) {
            return null;
        }
        String apiKey = System.getenv(config.apiKeyEnvVar());
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        return null;
    }

    @Override
    public String getBaseUrl(String providerName) {
        // 优先读取通用的 MODEL_BASE_URL 环境变量（适配自定义 OpenAI 兼容 API）
        String globalBaseUrl = System.getenv("MODEL_BASE_URL");
        if (globalBaseUrl != null && !globalBaseUrl.isBlank()) {
            return globalBaseUrl;
        }
        String effectiveProvider = resolveProvider(providerName);
        ProviderConfig config = configs.get(effectiveProvider);
        if (config == null) {
            return null;
        }
        // 其次从提供商专属环境变量读取自定义 Base URL
        String envBaseUrl = System.getenv(effectiveProvider.toUpperCase() + "_BASE_URL");
        if (envBaseUrl != null && !envBaseUrl.isBlank()) {
            return envBaseUrl;
        }
        return config.defaultBaseUrl();
    }

    @Override
    public String getModelName(String providerName) {
        // 优先读取通用的 MODEL_NAME 环境变量（适配自定义模型名）
        String globalModel = System.getenv("MODEL_NAME");
        if (globalModel != null && !globalModel.isBlank()) {
            return globalModel;
        }
        String effectiveProvider = resolveProvider(providerName);
        ProviderConfig config = configs.get(effectiveProvider);
        if (config == null) {
            return "unknown";
        }
        // 其次从提供商专属环境变量读取自定义模型名
        String envModel = System.getenv(effectiveProvider.toUpperCase() + "_MODEL");
        if (envModel != null && !envModel.isBlank()) {
            return envModel;
        }
        return config.defaultModel();
    }

    @Override
    public List<String> getAvailableProviders() {
        List<String> available = new ArrayList<>();
        for (Map.Entry<String, ProviderConfig> entry : configs.entrySet()) {
            String apiKey = System.getenv(entry.getValue().apiKeyEnvVar());
            if (apiKey != null && !apiKey.isBlank()) {
                available.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(available);
    }

    /**
     * 解析有效提供商：如果传入 null，则使用主提供商。
     */
    private String resolveProvider(String providerName) {
        if (providerName != null) {
            return providerName;
        }
        if (primaryProvider != null) {
            return primaryProvider;
        }
        // 默认回退到 openai
        return "openai";
    }

    // ==================== Primary / Fallback 设置 ====================

    /**
     * 设置主提供商。
     *
     * @param providerName 提供商名称
     */
    public void setPrimaryProvider(String providerName) {
        if (providerName != null && !configs.containsKey(providerName)) {
            log.warn("未知的提供商: {}，设置主提供商失败", providerName);
            return;
        }
        this.primaryProvider = providerName;
        log.info("主提供商已设置: {}", providerName);
    }

    /**
     * 设置备用提供商。
     *
     * @param providerName 提供商名称
     */
    public void setFallbackProvider(String providerName) {
        if (providerName != null && !configs.containsKey(providerName)) {
            log.warn("未知的提供商: {}，设置备用提供商失败", providerName);
            return;
        }
        this.fallbackProvider = providerName;
        log.info("备用提供商已设置: {}", providerName);
    }

    /**
     * 获取主提供商名称。
     *
     * @return 主提供商名称
     */
    public String getPrimaryProvider() {
        return primaryProvider;
    }

    /**
     * 获取备用提供商名称。
     *
     * @return 备用提供商名称
     */
    public String getFallbackProvider() {
        return fallbackProvider;
    }
}
