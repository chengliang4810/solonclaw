package com.jimuqu.solon.claw.mcp;

import org.noear.solon.ai.mcp.client.McpClientProvider;

/** 定义MCP Client提供方的抽象契约，供不同运行时实现保持一致行为。 */
public interface McpClientProviderFactory {
    /**
     * 根据服务端配置创建 MCP 客户端提供方。
     *
     * @param config 本项目解析后的 MCP 服务端配置。
     * @return 可注册到工具运行时的 MCP 客户端提供方。
     */
    McpClientProvider create(McpRuntimeService.McpServerConfig config);
}
