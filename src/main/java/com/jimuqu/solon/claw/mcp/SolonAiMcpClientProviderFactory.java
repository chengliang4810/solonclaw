package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.util.StrUtil;
import java.time.Duration;
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
        String channel = toChannel(config.getTransport());
        McpClientProvider.Builder builder =
                McpClientProvider.builder()
                        .name("jimuqu-" + config.getServerId())
                        .version("1.0")
                        .channel(channel)
                        .cacheSeconds(0)
                        .requestTimeout(Duration.ofMillis(config.getToolTimeoutMillis()))
                        .initializationTimeout(Duration.ofMillis(config.getConnectTimeoutMillis()))
                        .toolsChangeConsumer(
                                tools -> {
                                    runtimeService.persistToolsChanged(config.getServerId(), tools);
                                    return Mono.empty();
                                });
        if (McpChannel.STDIO.equals(channel)) {
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
            if (StrUtil.isNotBlank(accessToken) && !hasHeader(headers, "Authorization")) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
        }
        return builder.build();
    }

    private boolean hasHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private String toChannel(String transport) {
        String value = StrUtil.nullToEmpty(transport).trim().toLowerCase().replace('-', '_');
        if ("stdio".equals(value)) {
            return McpChannel.STDIO;
        }
        if ("http".equals(value) || "streamable".equals(value)) {
            return McpChannel.STREAMABLE;
        }
        if ("sse".equals(value)) {
            return McpChannel.SSE;
        }
        if ("streamable_stateless".equals(value)) {
            return McpChannel.STREAMABLE_STATELESS;
        }
        throw new IllegalArgumentException(
                "不支持的 MCP transport："
                        + StrUtil.blankToDefault(transport, "")
                        + "。可选值：stdio、http、streamable、streamable_stateless、sse。");
    }
}
