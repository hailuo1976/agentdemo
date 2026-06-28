package com.demo.agentscope.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置密封接口。
 * <p>
 * 使用 Java 17+ 密封接口（sealed interface）定义 MCP 服务器的两种连接方式：
 * <ul>
 *   <li>{@link StdioMCPConfig} - 通过标准输入/输出与本地进程通信</li>
 *   <li>{@link HttpMCPConfig} - 通过 HTTP 协议与远程服务通信</li>
 * </ul>
 * </p>
 */
public sealed interface MCPConfig permits MCPConfig.StdioMCPConfig, MCPConfig.HttpMCPConfig {

    /**
     * 获取服务器配置名称。
     *
     * @return 配置名称
     */
    String getName();

    /**
     * 标准输入/输出型 MCP 配置。
     * <p>
     * 通过启动本地子进程，使用 stdin/stdout 进行 JSON-RPC 2.0 通信。
     * </p>
     *
     * @param name    配置名称
     * @param command 启动命令（如 "npx"、"python"）
     * @param args    命令参数列表
     */
    record StdioMCPConfig(String name, String command, List<String> args) implements MCPConfig {
        @Override
        public String getName() {
            return name;
        }
    }

    /**
     * HTTP 型 MCP 配置。
     * <p>
     * 通过 HTTP/SSE 协议与远程 MCP 服务器通信。
     * </p>
     *
     * @param name    配置名称
     * @param url     服务器 URL
     * @param headers 请求头映射
     */
    record HttpMCPConfig(String name, String url, Map<String, String> headers) implements MCPConfig {
        @Override
        public String getName() {
            return name;
        }
    }
}
