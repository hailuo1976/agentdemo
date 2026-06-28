package com.demo.agentscope.credential;

import java.util.List;

/**
 * 凭证提供者接口。
 * <p>
 * AgentScope 2.0 凭证系统的核心抽象，负责根据提供商名称
 * 获取对应的 API Key、Base URL 和默认模型名称。
 * 不同的实现可从环境变量、配置文件或密钥管理服务中读取凭证。
 * </p>
 */
public interface CredentialProvider {

    /**
     * 获取指定提供商的 API Key。
     *
     * @param providerName 提供商名称（如 "openai"、"dashscope" 等）
     * @return API Key 字符串，未找到时返回 null
     */
    String getApiKey(String providerName);

    /**
     * 获取指定提供商的 Base URL。
     *
     * @param providerName 提供商名称
     * @return Base URL 字符串，未找到时返回 null
     */
    String getBaseUrl(String providerName);

    /**
     * 获取指定提供商的默认模型名称。
     *
     * @param providerName 提供商名称
     * @return 模型名称字符串
     */
    String getModelName(String providerName);

    /**
     * 获取所有可用的提供商名称列表。
     *
     * @return 提供商名称列表
     */
    List<String> getAvailableProviders();
}
