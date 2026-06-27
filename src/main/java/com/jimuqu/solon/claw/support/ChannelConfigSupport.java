package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.config.AppConfig;

/** 国内渠道配置读取辅助逻辑，统一按渠道标识定位 ChannelConfig。 */
public final class ChannelConfigSupport {
    /** 工具类不允许创建实例。 */
    private ChannelConfigSupport() {}

    /**
     * 读取指定国内渠道配置。
     *
     * @param appConfig 应用配置。
     * @param channel 渠道标识。
     * @return 对应渠道配置；未知渠道或未配置时返回 null。
     */
    public static AppConfig.ChannelConfig get(AppConfig appConfig, String channel) {
        if (appConfig == null || appConfig.getChannels() == null) {
            return null;
        }
        return get(appConfig.getChannels(), channel);
    }

    /**
     * 读取指定国内渠道配置，必要时初始化 channels 配置块。
     *
     * @param appConfig 应用配置。
     * @param channel 渠道标识。
     * @return 对应渠道配置；未知渠道时返回 null。
     */
    public static AppConfig.ChannelConfig getOrCreate(AppConfig appConfig, String channel) {
        if (appConfig == null) {
            return null;
        }
        if (appConfig.getChannels() == null) {
            appConfig.setChannels(new AppConfig.ChannelsConfig());
        }
        return get(appConfig.getChannels(), channel);
    }

    /**
     * 按字段名读取渠道凭据或连接字段，供 setup 与 TUI 状态检查共享同一套字段映射。
     *
     * @param config 渠道配置。
     * @param key 字段名。
     * @return 字段文本；未知字段或空配置返回空字符串。
     */
    public static String fieldValue(AppConfig.ChannelConfig config, String key) {
        if (config == null) {
            return "";
        }
        if ("appId".equals(key)) {
            return config.getAppId();
        }
        if ("appSecret".equals(key)) {
            return config.getAppSecret();
        }
        if ("clientId".equals(key)) {
            return config.getClientId();
        }
        if ("clientSecret".equals(key)) {
            return config.getClientSecret();
        }
        if ("botId".equals(key)) {
            return config.getBotId();
        }
        if ("secret".equals(key)) {
            return config.getSecret();
        }
        if ("token".equals(key)) {
            return config.getToken();
        }
        if ("accountId".equals(key)) {
            return config.getAccountId();
        }
        if ("robotCode".equals(key)) {
            return config.getRobotCode();
        }
        return "";
    }

    /**
     * 按渠道标识读取渠道配置。
     *
     * @param channels 渠道配置集合。
     * @param channel 渠道标识。
     * @return 对应渠道配置；未知渠道时返回 null。
     */
    private static AppConfig.ChannelConfig get(AppConfig.ChannelsConfig channels, String channel) {
        if ("feishu".equals(channel)) {
            return channels.getFeishu();
        }
        if ("dingtalk".equals(channel)) {
            return channels.getDingtalk();
        }
        if ("wecom".equals(channel)) {
            return channels.getWecom();
        }
        if ("weixin".equals(channel)) {
            return channels.getWeixin();
        }
        if ("qqbot".equals(channel)) {
            return channels.getQqbot();
        }
        if ("yuanbao".equals(channel)) {
            return channels.getYuanbao();
        }
        return null;
    }
}
