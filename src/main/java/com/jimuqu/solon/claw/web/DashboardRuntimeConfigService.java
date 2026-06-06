package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard 运行时配置管理服务。 */
public class DashboardRuntimeConfigService {
    /** 记录控制台运行时配置中的配置Resolver。 */
    private final RuntimeConfigResolver configResolver;

    /** 保存definitions集合，维持调用顺序或去重语义。 */
    private final List<ConfigItemDefinition> definitions;

    /** 注入消息网关运行时刷新服务，用于调用对应业务能力。 */
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;

    /**
     * 创建控制台运行时配置服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
    public DashboardRuntimeConfigService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this.configResolver = RuntimeConfigResolver.initialize(appConfig.getRuntime().getHome());
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.definitions =
                Arrays.asList(
                        item(
                                "model.providerKey",
                                "默认模型 provider key",
                                "provider",
                                false,
                                false,
                                "llm"),
                        item("model.default", "全局默认模型覆盖", "provider", false, false, "llm"),
                        item(
                                "providers.default.name",
                                "默认 provider 名称",
                                "provider",
                                false,
                                false,
                                "llm"),
                        item(
                                "providers.default.baseUrl",
                                "默认 provider 基础地址",
                                "provider",
                                false,
                                false,
                                "llm"),
                        item(
                                "providers.default.defaultModel",
                                "默认 provider 模型",
                                "provider",
                                false,
                                false,
                                "llm"),
                        item(
                                "providers.default.dialect",
                                "默认 provider 协议方言",
                                "provider",
                                false,
                                false,
                                "llm"),
                        item(
                                "providers.default.apiKey",
                                "默认 provider API 密钥",
                                "provider",
                                true,
                                false,
                                "llm"),
                        item(
                                "solonclaw.react.maxSteps",
                                "主代理最大推理步数",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.react.retryMax",
                                "主代理决策重试次数",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.react.retryDelayMs",
                                "主代理决策重试延迟（毫秒）",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.react.delegateMaxSteps",
                                "子代理最大推理步数",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.react.delegateRetryMax",
                                "子代理决策重试次数",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.react.delegateRetryDelayMs",
                                "子代理决策重试延迟（毫秒）",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.react.summarizationEnabled",
                                "启用 ReAct 工作记忆摘要守卫",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.react.summarizationMaxMessages",
                                "ReAct 摘要触发消息阈值",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.react.summarizationMaxTokens",
                                "ReAct 摘要触发 token 阈值",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.scheduler.wrapResponse",
                                "默认包装 Cron 投递回复",
                                "runtime",
                                false,
                                false,
                                "cron"),
                        item(
                                "solonclaw.task.busyPolicy",
                                "运行中输入策略：queue / steer / interrupt / reject",
                                "runtime",
                                false,
                                false,
                                "agent"),
                        item(
                                "solonclaw.task.toolOutputInlineLimit",
                                "工具输出内联字节上限",
                                "runtime",
                                false,
                                true,
                                "agent"),
                        item(
                                "solonclaw.task.toolOutputTurnBudget",
                                "单轮工具输出累计预算字节",
                                "runtime",
                                false,
                                true,
                                "agent"),
                        item(
                                "solonclaw.task.toolOutputMaxLines",
                                "工具文件读取最大行数",
                                "runtime",
                                false,
                                true,
                                "agent"),
                        item(
                                "solonclaw.task.toolOutputMaxLineLength",
                                "工具输出单行最大长度",
                                "runtime",
                                false,
                                true,
                                "agent"),
                        item(
                                "solonclaw.compression.summaryModel",
                                "压缩/工作记忆摘要模型",
                                "provider",
                                false,
                                true,
                                "llm"),
                        item(
                                "solonclaw.channels.feishu.enabled",
                                "启用飞书渠道",
                                "messaging",
                                false,
                                false,
                                "feishu"),
                        item(
                                "solonclaw.channels.feishu.appId",
                                "飞书应用 ID",
                                "messaging",
                                false,
                                false,
                                "feishu"),
                        item(
                                "solonclaw.channels.feishu.appSecret",
                                "飞书应用密钥",
                                "messaging",
                                true,
                                false,
                                "feishu"),
                        item(
                                "solonclaw.channels.feishu.domain",
                                "飞书/Lark 租户域",
                                "messaging",
                                false,
                                false,
                                "feishu"),
                        item(
                                "solonclaw.channels.feishu.groupAllowedUsers",
                                "飞书群聊 allowlist",
                                "messaging",
                                false,
                                true,
                                "feishu"),
                        item(
                                "solonclaw.channels.feishu.botOpenId",
                                "飞书 bot Open ID",
                                "messaging",
                                false,
                                true,
                                "feishu"),
                        item(
                                "solonclaw.channels.feishu.botUserId",
                                "飞书 bot User ID",
                                "messaging",
                                false,
                                true,
                                "feishu"),
                        item(
                                "solonclaw.channels.dingtalk.enabled",
                                "启用钉钉渠道",
                                "messaging",
                                false,
                                false,
                                "dingtalk"),
                        item(
                                "solonclaw.channels.dingtalk.clientId",
                                "钉钉客户端 ID",
                                "messaging",
                                false,
                                false,
                                "dingtalk"),
                        item(
                                "solonclaw.channels.dingtalk.clientSecret",
                                "钉钉客户端密钥",
                                "messaging",
                                true,
                                false,
                                "dingtalk"),
                        item(
                                "solonclaw.channels.dingtalk.robotCode",
                                "钉钉机器人编码",
                                "messaging",
                                true,
                                false,
                                "dingtalk"),
                        item(
                                "solonclaw.channels.dingtalk.groupAllowedUsers",
                                "钉钉群聊 allowlist",
                                "messaging",
                                false,
                                true,
                                "dingtalk"),
                        item(
                                "solonclaw.channels.wecom.enabled",
                                "启用企微渠道",
                                "messaging",
                                false,
                                false,
                                "wecom"),
                        item(
                                "solonclaw.channels.wecom.botId",
                                "企微机器人 ID",
                                "messaging",
                                false,
                                false,
                                "wecom"),
                        item(
                                "solonclaw.channels.wecom.secret",
                                "企微机器人密钥",
                                "messaging",
                                true,
                                false,
                                "wecom"),
                        item(
                                "solonclaw.channels.wecom.groupAllowedUsers",
                                "企微群聊 allowlist",
                                "messaging",
                                false,
                                true,
                                "wecom"),
                        item(
                                "solonclaw.channels.weixin.enabled",
                                "启用微信渠道",
                                "messaging",
                                false,
                                false,
                                "weixin"),
                        item(
                                "solonclaw.channels.weixin.token",
                                "微信令牌",
                                "messaging",
                                true,
                                false,
                                "weixin"),
                        item(
                                "solonclaw.channels.weixin.accountId",
                                "微信 iLink accountId",
                                "messaging",
                                false,
                                false,
                                "weixin"),
                        item(
                                "solonclaw.channels.weixin.groupAllowedUsers",
                                "微信群聊 allowlist",
                                "messaging",
                                false,
                                true,
                                "weixin"),
                        item(
                                "solonclaw.channels.qqbot.enabled",
                                "启用 QQBot 渠道",
                                "messaging",
                                false,
                                false,
                                "qqbot"),
                        item(
                                "solonclaw.channels.qqbot.appId",
                                "QQBot 应用 ID",
                                "messaging",
                                false,
                                false,
                                "qqbot"),
                        item(
                                "solonclaw.channels.qqbot.clientSecret",
                                "QQBot 客户端密钥",
                                "messaging",
                                true,
                                false,
                                "qqbot"),
                        item(
                                "solonclaw.channels.yuanbao.enabled",
                                "启用腾讯元宝渠道",
                                "messaging",
                                false,
                                false,
                                "yuanbao"),
                        item(
                                "solonclaw.channels.yuanbao.appId",
                                "腾讯元宝应用 ID",
                                "messaging",
                                false,
                                false,
                                "yuanbao"),
                        item(
                                "solonclaw.channels.yuanbao.appSecret",
                                "腾讯元宝应用密钥",
                                "messaging",
                                true,
                                false,
                                "yuanbao"),
                        item(
                                "solonclaw.gateway.injectionSecret",
                                "HTTP gateway injection HMAC secret",
                                "security",
                                true,
                                true,
                                "gateway"),
                        item(
                                "solonclaw.gateway.injectionMaxBodyBytes",
                                "HTTP gateway injection max body bytes",
                                "security",
                                false,
                                true,
                                "gateway"),
                        item(
                                "solonclaw.gateway.injectionReplayWindowSeconds",
                                "HTTP gateway injection replay window seconds",
                                "security",
                                false,
                                true,
                                "gateway"),
                        item(
                                "solonclaw.dashboard.accessToken",
                                "Dashboard access token",
                                "dashboard",
                                true,
                                false,
                                "dashboard"),
                        item(
                                "solonclaw.update.repo",
                                "版本检查使用的 GitHub 仓库，格式 owner/repo",
                                "runtime",
                                false,
                                true,
                                "version"),
                        item(
                                "solonclaw.update.releaseApiUrl",
                                "自定义最新版本检查 API 地址，默认 GitHub releases/latest",
                                "runtime",
                                false,
                                true,
                                "version"),
                        item(
                                "solonclaw.update.tagsApiUrl",
                                "自定义 tags 检查 API 地址",
                                "runtime",
                                false,
                                true,
                                "version"),
                        item(
                                "solonclaw.update.httpProxy",
                                "版本检查 HTTP 代理地址，例如 http://proxy.example:7890",
                                "runtime",
                                false,
                                true,
                                "version"),
                        item(
                                "solonclaw.integrations.github.token",
                                "Skills Hub 使用的 GitHub 访问令牌",
                                "tool",
                                true,
                                true,
                                "skills_hub"),
                        item(
                                "solonclaw.integrations.github.cliToken",
                                "GitHub CLI 回退令牌",
                                "tool",
                                true,
                                true,
                                "skills_hub"),
                        item(
                                "solonclaw.integrations.github.appId",
                                "GitHub App ID",
                                "tool",
                                false,
                                true,
                                "skills_hub"),
                        item(
                                "solonclaw.integrations.github.privateKeyPath",
                                "GitHub App 私钥路径",
                                "tool",
                                false,
                                true,
                                "skills_hub"),
                        item(
                                "solonclaw.integrations.github.installationId",
                                "GitHub App 安装 ID",
                                "tool",
                                false,
                                true,
                                "skills_hub"));
    }

    /**
     * 读取配置Items。
     *
     * @return 返回读取到的配置Items。
     */
    public Map<String, Object> getConfigItems() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (ConfigItemDefinition definition : definitions) {
            String value = configResolver.get(definition.key);

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("is_set", StrUtil.isNotBlank(value));
            item.put("redacted_value", StrUtil.isBlank(value) ? null : redact(value));
            item.put("description", definition.description);
            item.put("url", definition.url);
            item.put("category", definition.category);
            item.put("is_password", definition.password);
            item.put("tools", definition.tools);
            item.put("advanced", definition.advanced);
            result.put(definition.key, item);
        }
        return result;
    }

    /**
     * 执行reveal相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回reveal结果。
     */
    public Map<String, Object> reveal(String key) {
        ConfigItemDefinition definition = requireSupported(key);
        if (!definition.password) {
            throw new IllegalStateException("Runtime config item is not revealable: " + key);
        }
        String value = configResolver.get(key);
        if (StrUtil.isBlank(value)) {
            throw new IllegalStateException("Runtime config item not set: " + key);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("key", key);
        result.put("value", value);
        return result;
    }

    /**
     * 执行set相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回set结果。
     */
    public Map<String, Object> set(String key, String value) {
        return set(key, value, true);
    }

    /**
     * 执行set相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param reconnectChannels reconnectChannels 参数。
     * @return 返回set结果。
     */
    public Map<String, Object> set(String key, String value, boolean reconnectChannels) {
        ConfigItemDefinition definition = requireSupported(key);
        if (definition.password) {
            return updateSecret(key, value, reconnectChannels);
        }
        return writeNonSecret(key, value, reconnectChannels);
    }

    /**
     * 写入Non密钥。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param reconnectChannels reconnectChannels 参数。
     * @return 返回Non密钥结果。
     */
    public Map<String, Object> writeNonSecret(String key, String value, boolean reconnectChannels) {
        ConfigItemDefinition definition = requireSupported(key);
        if (definition.password) {
            throw new IllegalArgumentException(key + " 是密钥配置，请使用 secret update/reveal 流程。");
        }
        persist(key, value, reconnectChannels);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 更新密钥。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param reconnectChannels reconnectChannels 参数。
     * @return 返回密钥结果。
     */
    public Map<String, Object> updateSecret(String key, String value, boolean reconnectChannels) {
        ConfigItemDefinition definition = requireSupported(key);
        if (!definition.password) {
            throw new IllegalArgumentException(key + " 不是密钥配置，请使用普通配置写入流程。");
        }
        if (SecretValueGuard.isPlaceholderSecret(value)) {
            throw new IllegalArgumentException(key + " 不能使用示例或占位符密钥。");
        }
        persist(key, value, reconnectChannels);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 执行persist相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param reconnectChannels reconnectChannels 参数。
     */
    private void persist(String key, String value, boolean reconnectChannels) {
        configResolver.setFileValue(key, value);
        if (reconnectChannels) {
            gatewayRuntimeRefreshService.refreshNow();
        } else {
            gatewayRuntimeRefreshService.refreshConfigOnly();
        }
    }

    /**
     * 执行remove相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回remove结果。
     */
    public Map<String, Object> remove(String key) {
        return remove(key, true);
    }

    /**
     * 执行remove相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param reconnectChannels reconnectChannels 参数。
     * @return 返回remove结果。
     */
    public Map<String, Object> remove(String key, boolean reconnectChannels) {
        ensureSupported(key);
        configResolver.removeFileValue(key);
        if (reconnectChannels) {
            gatewayRuntimeRefreshService.refreshNow();
        } else {
            gatewayRuntimeRefreshService.refreshConfigOnly();
        }
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 确保Supported。
     *
     * @param key 配置键或映射键。
     */
    private void ensureSupported(String key) {
        requireSupported(key);
    }

    /**
     * 要求Supported。
     *
     * @param key 配置键或映射键。
     * @return 返回Supported结果。
     */
    private ConfigItemDefinition requireSupported(String key) {
        for (ConfigItemDefinition definition : definitions) {
            if (definition.key.equals(key)) {
                return definition;
            }
        }
        throw new IllegalStateException("Unsupported runtime config item: " + key);
    }

    /**
     * 脱敏文本中的密钥、令牌和敏感路径。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回redact结果。
     */
    private String redact(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    /**
     * 执行item相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param description 描述参数。
     * @param category 分类参数。
     * @param password password 参数。
     * @param advanced advanced 参数。
     * @param tool 工具参数。
     * @return 返回item结果。
     */
    private static ConfigItemDefinition item(
            String key,
            String description,
            String category,
            boolean password,
            boolean advanced,
            String tool) {
        return new ConfigItemDefinition(
                key, description, category, password, advanced, null, Arrays.asList(tool));
    }

    /** 承载配置ItemDefinition相关状态和辅助逻辑。 */
    private static class ConfigItemDefinition {
        /** 记录配置ItemDefinition中的键。 */
        private final String key;

        /** 记录配置ItemDefinition中的描述。 */
        private final String description;

        /** 记录配置ItemDefinition中的category。 */
        private final String category;

        /** 是否启用密码。 */
        private final boolean password;

        /** 是否启用advanced。 */
        private final boolean advanced;

        /** 记录配置ItemDefinition中的URL。 */
        private final String url;

        /** 保存工具集合，维持调用顺序或去重语义。 */
        private final List<String> tools;

        /**
         * 创建配置Item Definition实例，并注入运行所需依赖。
         *
         * @param key 配置键或映射键。
         * @param description 描述参数。
         * @param category 分类参数。
         * @param password password 参数。
         * @param advanced advanced 参数。
         * @param url 待校验或访问的 URL。
         * @param tools tools 参数。
         */
        private ConfigItemDefinition(
                String key,
                String description,
                String category,
                boolean password,
                boolean advanced,
                String url,
                List<String> tools) {
            this.key = key;
            this.description = description;
            this.category = category;
            this.password = password;
            this.advanced = advanced;
            this.url = url;
            this.tools = tools;
        }
    }
}
