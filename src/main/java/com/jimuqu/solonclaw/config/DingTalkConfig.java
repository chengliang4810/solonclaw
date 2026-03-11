package com.jimuqu.solonclaw.config;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

/**
 * 钉钉配置 - 支持企业内部机器人和Webhook两种方式
 *
 * @author SolonClaw
 */
@Configuration
public class DingTalkConfig {

    @Inject("${solonclaw.dingtalk.enabled:false}")
    private boolean enabled;

    // 企业内部机器人配置
    @Inject("${solonclaw.dingtalk.appKey:}")
    private String appKey;

    @Inject("${solonclaw.dingtalk.appSecret:}")
    private String appSecret;

    // Stream 模式机器人 Code（在群里添加机器人后获取）
    @Inject("${solonclaw.dingtalk.robotCode:}")
    private String robotCode;

    // Webhook机器人配置（已废弃，仅保留兼容性）
    @Inject("${solonclaw.dingtalk.webhook:}")
    private String webhook;

    @Inject("${solonclaw.dingtalk.secret:}")
    private String secret;

    public boolean isEnabled() {
        return enabled;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public String getRobotCode() {
        return robotCode;
    }

    public String getWebhook() {
        return webhook;
    }

    public String getSecret() {
        return secret;
    }

    /**
     * 是否使用企业内部机器人方式
     */
    public boolean isEnterpriseBot() {
        return appKey != null && !appKey.isBlank() && appSecret != null && !appSecret.isBlank();
    }
}
