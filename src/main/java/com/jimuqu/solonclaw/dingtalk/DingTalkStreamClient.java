package com.jimuqu.solonclaw.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.jimuqu.solonclaw.config.DingTalkConfig;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉 Stream 模式客户端配置
 * <p>
 * 使用 Stream 模式连接钉钉，无需公网回调地址
 *
 * @author SolonClaw
 */
@Component
public class DingTalkStreamClient {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamClient.class);

    @Inject
    private DingTalkMessageHandler messageHandler;

    @Inject
    private DingTalkConfig dingTalkConfig;

    /**
     * 创建并启动钉钉 Stream 客户端
     */
    @Bean(initMethod = "start")
    public OpenDingTalkClient dingTalkClient() {
        if (!dingTalkConfig.isEnabled()) {
            log.info("钉钉渠道未启用");
            return null;
        }

        String appKey = dingTalkConfig.getAppKey();
        String appSecret = dingTalkConfig.getAppSecret();

        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.warn("钉钉 AppKey 或 AppSecret 未配置");
            return null;
        }

        log.info("正在启动钉钉 Stream 客户端...");

        try {
            OpenDingTalkClient client = OpenDingTalkStreamClientBuilder.custom()
                    .credential(new AuthClientCredential(appKey, appSecret))
                    // 注册机器人消息回调
                    .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, messageHandler)
                    .build();

            log.info("钉钉 Stream 客户端创建成功！");
            return client;
        } catch (Exception e) {
            log.error("钉钉 Stream 客户端创建失败", e);
            return null;
        }
    }
}
