package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 定义本地初始化配置的模型和国内渠道字段范围，供 CLI/TUI/slash 共用。 */
public final class RuntimeSetupSpec {
    /** 国内渠道顺序，和本项目已确认保留的渠道范围保持一致。 */
    private static final List<String> DOMESTIC_CHANNELS =
            Collections.unmodifiableList(
                    Arrays.asList("feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao"));

    /** Dashboard 设置页展示用的国内渠道元数据，后端统一提供展示名、图标键和排序。 */
    private static final List<Map<String, Object>> DOMESTIC_CHANNEL_CATALOG =
            domesticChannelCatalogEntries();

    /** 需要在 setup gateway 中展示的关键渠道配置项。 */
    private static final Map<String, List<String>> CHANNEL_REQUIRED_KEYS = channelRequiredKeys();

    /** 允许 setup gateway 直接写入的渠道字段，避免误写未确认渠道或无关配置。 */
    private static final Map<String, List<String>> CHANNEL_ALLOWED_KEYS = channelAllowedKeys();

    /** 命令行参数名到配置字段名的映射，支持 kebab-case 与 camelCase 混用。 */
    private static final Map<String, String> CHANNEL_FLAG_KEYS = channelFlagKeys();

    /** 禁止创建工具类实例。 */
    private RuntimeSetupSpec() {}

    /** 返回已确认保留的国内渠道列表。 */
    public static List<String> domesticChannels() {
        return DOMESTIC_CHANNELS;
    }

    /** 返回国内渠道展示元数据，用于 Dashboard 设置页数据化渲染平台清单。 */
    public static List<Map<String, Object>> domesticChannelCatalog() {
        return DOMESTIC_CHANNEL_CATALOG;
    }

    /**
     * 返回渠道必填字段。
     *
     * @param channel 渠道标识。
     * @return 不可变必填字段列表；未知渠道返回空列表。
     */
    public static List<String> requiredChannelKeys(String channel) {
        List<String> values = CHANNEL_REQUIRED_KEYS.get(normalizeChannel(channel));
        return values == null ? Collections.<String>emptyList() : values;
    }

    /**
     * 返回渠道允许字段。
     *
     * @param channel 渠道标识。
     * @return 不可变允许字段列表；未知渠道返回空列表。
     */
    public static List<String> allowedChannelKeys(String channel) {
        List<String> values = CHANNEL_ALLOWED_KEYS.get(normalizeChannel(channel));
        return values == null ? Collections.<String>emptyList() : values;
    }

    /**
     * 判断渠道字段是否允许通过初始化命令写入。
     *
     * @param channel 渠道标识。
     * @param key 配置字段名。
     * @return 允许写入返回 true。
     */
    public static boolean isAllowedChannelKey(String channel, String key) {
        return allowedChannelKeys(channel).contains(StrUtil.nullToEmpty(key).trim());
    }

    /**
     * 将命令参数名归一化为渠道配置字段名。
     *
     * @param flag 用户输入的参数名，不含 -- 前缀。
     * @return 本项目配置字段名；未知字段返回空字符串。
     */
    public static String normalizeChannelFlag(String flag) {
        String value = StrUtil.nullToEmpty(flag).trim();
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String direct = CHANNEL_FLAG_KEYS.get(value);
        if (StrUtil.isNotBlank(direct)) {
            return direct;
        }
        return StrUtil.blankToDefault(
                CHANNEL_FLAG_KEYS.get(value.toLowerCase(java.util.Locale.ROOT)), "");
    }

    /** 归一化渠道标识。 */
    private static String normalizeChannel(String channel) {
        return StrUtil.nullToEmpty(channel).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 构建国内渠道关键配置项映射。 */
    private static Map<String, List<String>> channelRequiredKeys() {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        result.put("feishu", Collections.unmodifiableList(Arrays.asList("appId", "appSecret")));
        result.put(
                "dingtalk",
                Collections.unmodifiableList(
                        Arrays.asList("clientId", "clientSecret", "robotCode")));
        result.put("wecom", Collections.unmodifiableList(Arrays.asList("botId", "secret")));
        result.put("weixin", Collections.unmodifiableList(Arrays.asList("token", "accountId")));
        result.put("qqbot", Collections.unmodifiableList(Arrays.asList("appId", "clientSecret")));
        result.put(
                "yuanbao",
                Collections.unmodifiableList(Arrays.asList("appId", "appSecret", "botId")));
        return Collections.unmodifiableMap(result);
    }

    /** 构建国内渠道展示元数据列表。 */
    private static List<Map<String, Object>> domesticChannelCatalogEntries() {
        java.util.ArrayList<Map<String, Object>> result =
                new java.util.ArrayList<Map<String, Object>>();
        result.add(channelCatalogEntry("feishu", "飞书", "feishu", 10));
        result.add(channelCatalogEntry("dingtalk", "钉钉", "dingtalk", 20));
        result.add(channelCatalogEntry("wecom", "企业微信", "wecom", 30));
        result.add(channelCatalogEntry("weixin", "微信", "weixin", 40));
        result.add(channelCatalogEntry("qqbot", "QQBot", "qqbot", 50));
        result.add(channelCatalogEntry("yuanbao", "腾讯元宝", "yuanbao", 60));
        return Collections.unmodifiableList(result);
    }

    /** 构建单个国内渠道展示元数据项。 */
    private static Map<String, Object> channelCatalogEntry(
            String code, String displayName, String iconKey, int order) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("code", code);
        entry.put("displayName", displayName);
        entry.put("iconKey", iconKey);
        entry.put("order", order);
        entry.put("enabled", true);
        return Collections.unmodifiableMap(entry);
    }

    /** 构建各国内渠道允许通过 setup gateway 写入的配置项。 */
    private static Map<String, List<String>> channelAllowedKeys() {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        List<String> common =
                Arrays.asList(
                        "enabled",
                        "allowedUsers",
                        "allowAllUsers",
                        "dmPolicy",
                        "groupPolicy",
                        "groupAllowedUsers",
                        "allowedChats",
                        "unauthorizedDmBehavior",
                        "toolProgress");
        result.put(
                "feishu",
                mergeKeys(
                        common,
                        Arrays.asList(
                                "appId",
                                "appSecret",
                                "domain",
                                "websocketUrl",
                                "requireMention",
                                "freeResponseChats",
                                "botOpenId",
                                "botUserId",
                                "botName",
                                "comment.enabled",
                                "comment.pairingFile")));
        result.put(
                "dingtalk",
                mergeKeys(
                        common,
                        Arrays.asList(
                                "clientId",
                                "clientSecret",
                                "robotCode",
                                "coolAppCode",
                                "streamUrl",
                                "requireMention",
                                "freeResponseChats",
                                "progressCardTemplateId",
                                "aiCardStreaming.enabled")));
        result.put("wecom", mergeKeys(common, Arrays.asList("botId", "secret", "websocketUrl")));
        result.put(
                "weixin",
                mergeKeys(
                        common,
                        Arrays.asList(
                                "token",
                                "accountId",
                                "baseUrl",
                                "cdnBaseUrl",
                                "longPollUrl",
                                "splitMultilineMessages",
                                "textBatchDelaySeconds",
                                "textBatchSplitDelaySeconds",
                                "sendChunkDelaySeconds",
                                "sendChunkRetries",
                                "sendChunkRetryDelaySeconds")));
        result.put(
                "qqbot",
                mergeKeys(
                        common,
                        Arrays.asList(
                                "appId",
                                "clientSecret",
                                "apiDomain",
                                "websocketUrl",
                                "markdownSupport")));
        result.put(
                "yuanbao",
                mergeKeys(
                        common,
                        Arrays.asList("appId", "appSecret", "botId", "apiDomain", "websocketUrl")));
        return Collections.unmodifiableMap(result);
    }

    /** 合并公共字段与渠道专属字段。 */
    private static List<String> mergeKeys(List<String> common, List<String> specific) {
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (String key : common) {
            if (!values.contains(key)) {
                values.add(key);
            }
        }
        for (String key : specific) {
            if (!values.contains(key)) {
                values.add(key);
            }
        }
        return Collections.unmodifiableList(values);
    }

    /** 构建 setup gateway 命令参数到配置字段的归一化映射。 */
    private static Map<String, String> channelFlagKeys() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        registerFlag(result, "enabled");
        registerFlag(result, "allowedUsers", "allowed-users");
        registerFlag(result, "allowAllUsers", "allow-all-users");
        registerFlag(result, "dmPolicy", "dm-policy");
        registerFlag(result, "groupPolicy", "group-policy");
        registerFlag(result, "groupAllowedUsers", "group-allowed-users");
        registerFlag(result, "allowedChats", "allowed-chats");
        registerFlag(result, "requireMention", "require-mention");
        registerFlag(result, "freeResponseChats", "free-response-chats");
        registerFlag(result, "unauthorizedDmBehavior", "unauthorized-dm-behavior");
        registerFlag(result, "toolProgress", "tool-progress");
        registerFlag(result, "appId", "app-id");
        registerFlag(result, "appSecret", "app-secret");
        registerFlag(result, "domain");
        registerFlag(result, "websocketUrl", "websocket-url");
        registerFlag(result, "botOpenId", "bot-open-id");
        registerFlag(result, "botUserId", "bot-user-id");
        registerFlag(result, "botName", "bot-name");
        registerFlag(result, "comment.enabled", "comment-enabled");
        registerFlag(result, "comment.pairingFile", "comment-pairing-file");
        registerFlag(result, "clientId", "client-id");
        registerFlag(result, "clientSecret", "client-secret");
        registerFlag(result, "robotCode", "robot-code");
        registerFlag(result, "coolAppCode", "cool-app-code");
        registerFlag(result, "streamUrl", "stream-url");
        registerFlag(result, "progressCardTemplateId", "progress-card-template-id");
        registerFlag(result, "aiCardStreaming.enabled", "ai-card-streaming-enabled");
        registerFlag(result, "botId", "bot-id");
        registerFlag(result, "secret");
        registerFlag(result, "token");
        registerFlag(result, "accountId", "account-id");
        registerFlag(result, "baseUrl", "base-url");
        registerFlag(result, "cdnBaseUrl", "cdn-base-url");
        registerFlag(result, "longPollUrl", "long-poll-url");
        registerFlag(result, "splitMultilineMessages", "split-multiline-messages");
        registerFlag(result, "textBatchDelaySeconds", "text-batch-delay-seconds");
        registerFlag(result, "textBatchSplitDelaySeconds", "text-batch-split-delay-seconds");
        registerFlag(result, "sendChunkDelaySeconds", "send-chunk-delay-seconds");
        registerFlag(result, "sendChunkRetries", "send-chunk-retries");
        registerFlag(result, "sendChunkRetryDelaySeconds", "send-chunk-retry-delay-seconds");
        registerFlag(result, "apiDomain", "api-domain");
        registerFlag(result, "markdownSupport", "markdown-support");
        return Collections.unmodifiableMap(result);
    }

    /** 注册命令参数别名。 */
    private static void registerFlag(
            Map<String, String> output, String configKey, String... aliases) {
        output.put(configKey, configKey);
        output.put(configKey.toLowerCase(java.util.Locale.ROOT), configKey);
        for (String alias : aliases) {
            output.put(alias, configKey);
            output.put(alias.toLowerCase(java.util.Locale.ROOT), configKey);
        }
    }
}
