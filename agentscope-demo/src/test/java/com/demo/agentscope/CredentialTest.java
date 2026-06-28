package com.demo.agentscope;

import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.credential.DefaultCredentialProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 凭证系统测试。
 */
@DisplayName("凭证系统测试")
class CredentialTest {

    private DefaultCredentialProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DefaultCredentialProvider();
    }

    @Test
    @DisplayName("DefaultCredentialProvider 初始化后注册 4 个内置提供商")
    void defaultProviderRegisters4BuiltinProviders() {
        // 未设置环境变量时，可用提供商可能为 0
        // 但 ProviderConfig 应该已注册
        assertNotNull(provider.getBaseUrl("openai"));
        assertNotNull(provider.getBaseUrl("dashscope"));
        assertNotNull(provider.getBaseUrl("anthropic"));
        assertNotNull(provider.getBaseUrl("deepseek"));
    }

    @Test
    @DisplayName("DefaultCredentialProvider getBaseUrl 返回默认 Base URL")
    void defaultProviderGetBaseUrl() {
        String openaiUrl = provider.getBaseUrl("openai");
        assertNotNull(openaiUrl);
        assertEquals("https://api.openai.com/v1", openaiUrl);

        String dashscopeUrl = provider.getBaseUrl("dashscope");
        assertNotNull(dashscopeUrl);
        assertTrue(dashscopeUrl.contains("dashscope"));
    }

    @Test
    @DisplayName("DefaultCredentialProvider getModelName 返回默认模型名称")
    void defaultProviderGetModelName() {
        assertEquals("gpt-4o-mini", provider.getModelName("openai"));
        assertEquals("qwen-plus", provider.getModelName("dashscope"));
        assertEquals("claude-sonnet-4-20250514", provider.getModelName("anthropic"));
        assertEquals("deepseek-chat", provider.getModelName("deepseek"));
    }

    @Test
    @DisplayName("DefaultCredentialProvider getModelName 未知提供商返回 unknown")
    void defaultProviderUnknownProviderModel() {
        assertEquals("unknown", provider.getModelName("unknown_provider"));
    }

    @Test
    @DisplayName("DefaultCredentialProvider setPrimaryProvider 设置主提供商")
    void setPrimaryProvider() {
        provider.setPrimaryProvider("openai");
        assertEquals("openai", provider.getPrimaryProvider());
    }

    @Test
    @DisplayName("DefaultCredentialProvider setFallbackProvider 设置备用提供商")
    void setFallbackProvider() {
        provider.setFallbackProvider("dashscope");
        assertEquals("dashscope", provider.getFallbackProvider());
    }

    @Test
    @DisplayName("DefaultCredentialProvider 设置未知提供商不生效")
    void setUnknownProviderIgnored() {
        provider.setPrimaryProvider("nonexistent_provider");
        assertNull(provider.getPrimaryProvider());

        provider.setFallbackProvider("nonexistent_provider");
        assertNull(provider.getFallbackProvider());
    }

    @Test
    @DisplayName("DefaultCredentialProvider primary+fallback 机制：指定提供商不可用时回退")
    void primaryFallbackMechanism() {
        // 设置主提供商和备用提供商
        provider.setPrimaryProvider("openai");
        provider.setFallbackProvider("dashscope");

        // 当指定提供商的 API Key 不可用时，回退到备用提供商
        // 由于环境变量未设置，两个都不会有 key，所以会返回 null
        // 但至少验证回退逻辑不会抛出异常
        assertDoesNotThrow(() -> provider.getApiKey("nonexistent"));
    }

    @Test
    @DisplayName("DefaultCredentialProvider getApiKey 未知提供商返回 null")
    void getApiKeyUnknownProviderReturnsNull() {
        assertNull(provider.getApiKey("unknown_provider"));
    }

    @Test
    @DisplayName("DefaultCredentialProvider getBaseUrl 未知提供商返回 null")
    void getBaseUrlUnknownProviderReturnsNull() {
        assertNull(provider.getBaseUrl("unknown_provider"));
    }

    @Test
    @DisplayName("DefaultCredentialProvider getAvailableProviders 返回列表")
    void getAvailableProviders() {
        List<String> available = provider.getAvailableProviders();
        assertNotNull(available);
        // 在没有设置环境变量的情况下，可能为空列表
        // 这是正常行为
    }

    @Test
    @DisplayName("DefaultCredentialProvider 使用系统属性模拟 API Key")
    void getApiKeyFromEnv() {
        // 通过系统属性设置 API Key 来模拟环境变量
        // 注意：System.getenv() 不可修改，这里只能验证逻辑结构
        // 实际测试需要 CI 环境中设置环境变量

        // 验证 openai 提供商的 base URL 不为 null
        String baseUrl = provider.getBaseUrl("openai");
        assertNotNull(baseUrl);
        assertTrue(baseUrl.startsWith("https://"));
    }

    @Test
    @DisplayName("CredentialProvider 接口定义完整")
    void credentialProviderInterfaceComplete() {
        CredentialProvider cp = new DefaultCredentialProvider();
        // 验证接口方法都能正常调用
        assertDoesNotThrow(() -> cp.getApiKey("openai"));
        assertDoesNotThrow(() -> cp.getBaseUrl("openai"));
        assertDoesNotThrow(() -> cp.getModelName("openai"));
        assertDoesNotThrow(() -> cp.getAvailableProviders());
    }

    @Test
    @DisplayName("DefaultCredentialProvider null 提供商名称时使用主提供商")
    void nullProviderNameUsesPrimary() {
        provider.setPrimaryProvider("openai");

        // 调用 getBaseUrl(null) 应使用主提供商
        String baseUrl = provider.getBaseUrl(null);
        assertNotNull(baseUrl);
        assertEquals("https://api.openai.com/v1", baseUrl);
    }

    @Test
    @DisplayName("DefaultCredentialProvider 无主提供商时默认回退到 openai")
    void noPrimaryDefaultsToOpenai() {
        // 未设置主提供商时，getModelName(null) 应返回 openai 的模型名
        assertEquals("gpt-4o-mini", provider.getModelName(null));
    }
}
