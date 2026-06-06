package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.util.StrUtil;
import java.time.Duration;
import java.util.Map;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import reactor.core.publisher.Mono;

/** 创建Solon Ai MCP Client提供方相关实例，统一封装构造参数与默认策略。 */
public class SolonAiMcpClientProviderFactory implements McpClientProviderFactory {
    /** 注入运行时服务，用于调用对应业务能力。 */
    private final McpRuntimeService runtimeService;

    /**
     * 创建Solon Ai MCP Client提供方工厂实例，并注入运行所需依赖。
     *
     * @param runtimeService 运行时服务依赖。
     */
    public SolonAiMcpClientProviderFactory(McpRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * 执行create，服务于SolonAiMCPClient提供方主流程相关逻辑。
     *
     * @param config 当前模块使用的配置对象。
     * @return 返回create结果。
     */
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

    /**
     * 判断是否存在Header。
     *
     * @param headers headers 参数。
     * @param name 名称参数。
     * @return 如果Header满足条件则返回 true，否则返回 false。
     */
    private boolean hasHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 转换为渠道。
     *
     * @param transport transport 参数。
     * @return 返回转换后的渠道。
     */
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
