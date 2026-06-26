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
