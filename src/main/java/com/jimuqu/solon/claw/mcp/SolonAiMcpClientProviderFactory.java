package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.util.StrUtil;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import reactor.core.publisher.Mono;

/** Builds Solon AI MCP providers from dashboard registry rows. */
public class SolonAiMcpClientProviderFactory implements McpClientProviderFactory {
    private final McpRuntimeService runtimeService;

    public SolonAiMcpClientProviderFactory(McpRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public McpClientProvider create(final McpRuntimeService.McpServerConfig config) {
        McpClientProvider.Builder builder =
                McpClientProvider.builder()
                        .name("jimuqu-" + config.getServerId())
                        .version("1.0")
                        .channel(toChannel(config.getTransport()))
                        .cacheSeconds(0)
                        .requestTimeout(Duration.ofMillis(config.getToolTimeoutMillis()))
                        .initializationTimeout(Duration.ofMillis(config.getConnectTimeoutMillis()))
                        .toolsChangeConsumer(
                                tools -> {
                                    runtimeService.persistToolsChanged(config.getServerId(), tools);
                                    return Mono.empty();
                                });
        if (McpChannel.STDIO.equals(toChannel(config.getTransport()))) {
            builder.command(config.getCommand());
            builder.args(config.getArgs());
            builder.env(config.getEnv());
        } else {
            builder.url(config.getEndpoint());
            Map<String, String> headers = config.getHeaders();
            if (!headers.isEmpty()) {
                builder.headers(headers);
            }
            String accessToken = config.getAccessToken();
            if (StrUtil.isNotBlank(accessToken) && !headers.containsKey("Authorization")) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
        }
        return builder.build();
    }

    private String toChannel(String transport) {
        String value = StrUtil.nullToEmpty(transport).trim().toLowerCase();
        if ("stdio".equals(value)) {
            return McpChannel.STDIO;
        }
        if ("sse".equals(value)) {
            return McpChannel.SSE;
        }
        if ("streamable_stateless".equals(value) || "streamable-stateless".equals(value)) {
            return McpChannel.STREAMABLE_STATELESS;
        }
        return McpChannel.STREAMABLE;
    }
}
