package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import java.time.Duration;
import java.util.Map;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import reactor.core.publisher.Mono;

/** 创建Solon Ai MCP Client提供方相关实例，统一封装构造参数与默认策略。 */
public class SolonAiMcpClientProviderFactory implements McpClientProviderFactory {
    /** MCP 运行时服务，用于在 Solon AI 通知工具变化时持久化最新工具清单。 */
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
     * 根据本项目 MCP 服务端配置创建 Solon AI 的 McpClientProvider。
     *
     * @param config 已完成默认值补齐和安全策略校验的 MCP 服务端配置。
     * @return 可交给 Solon AI 工具链注册的 MCP 客户端提供方。
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
            if (CollUtil.isNotEmpty(headers)) {
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
     * 判断配置中是否已经显式设置指定 HTTP 头，避免重复覆盖鉴权头。
     *
     * @param headers MCP HTTP/SSE 连接的请求头。
     * @param name 待检查的请求头名称。
     * @return 忽略大小写命中时返回 true。
     */
    private boolean hasHeader(Map<String, String> headers, String name) {
        if (CollUtil.isEmpty(headers)) {
            return false;
        }
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将配置中的传输类型映射为 Solon AI MCP 通道常量。
     *
     * @param transport 用户配置的传输类型，允许 http、streamable、sse、stdio 等别名。
     * @return Solon AI MCP 客户端识别的通道常量。
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
