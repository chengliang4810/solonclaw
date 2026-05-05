package com.jimuqu.solon.claw.mcp;

import org.noear.solon.ai.mcp.client.McpClientProvider;

/** Factory boundary for Solon AI MCP clients. */
public interface McpClientProviderFactory {
    McpClientProvider create(McpRuntimeService.McpServerConfig config);
}
