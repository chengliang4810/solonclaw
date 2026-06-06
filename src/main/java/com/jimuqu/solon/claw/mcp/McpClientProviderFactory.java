package com.jimuqu.solon.claw.mcp;

import org.noear.solon.ai.mcp.client.McpClientProvider;

/** 定义MCP Client提供方的抽象契约，供不同运行时实现保持一致行为。 */
public interface McpClientProviderFactory {
    /**
     * 执行create，服务于MCPClient提供方主流程相关逻辑。
     *
     * @param config 当前模块使用的配置对象。
     * @return 返回create结果。
     */
    McpClientProvider create(McpRuntimeService.McpServerConfig config);
}
