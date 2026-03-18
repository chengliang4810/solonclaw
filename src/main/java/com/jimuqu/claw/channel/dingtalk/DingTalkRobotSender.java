package com.jimuqu.claw.channel.dingtalk;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.dingtalkrobot_1_0.Client;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTOHeaders;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTORequest;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendHeaders;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendRequest;
import com.aliyun.teautil.models.RuntimeOptions;
import com.jimuqu.claw.agent.model.ConversationType;
import com.jimuqu.claw.agent.model.ReplyTarget;
import com.jimuqu.claw.config.SolonClawProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于钉钉机器人 OpenAPI 发送群聊和私聊文本消息。
 */
public class DingTalkRobotSender {
    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(DingTalkRobotSender.class);
    /** access_token 服务。 */
    private final DingTalkAccessTokenService accessTokenService;
    /** 钉钉配置。 */
    private final SolonClawProperties.DingTalk properties;
    /** 机器人 OpenAPI 客户端。 */
    private final Client robotClient;

    /**
     * 创建钉钉机器人发送服务。
     *
     * @param accessTokenService access_token 服务
     * @param properties 钉钉配置
     * @throws Exception 创建底层客户端时的异常
     */
    public DingTalkRobotSender(
            DingTalkAccessTokenService accessTokenService,
            SolonClawProperties.DingTalk properties
    ) throws Exception {
        this(accessTokenService, properties, createRobotClient());
    }

    /**
     * 使用显式客户端创建发送服务。
     *
     * @param accessTokenService access_token 服务
     * @param properties 钉钉配置
     * @param robotClient 机器人客户端
     */
    DingTalkRobotSender(
            DingTalkAccessTokenService accessTokenService,
            SolonClawProperties.DingTalk properties,
            Client robotClient
    ) {
        this.accessTokenService = accessTokenService;
        this.properties = properties;
        this.robotClient = robotClient;
    }

    /**
     * 向指定回复目标发送文本。
     *
     * @param replyTarget 回复目标
     * @param content 文本内容
     */
    public void sendText(ReplyTarget replyTarget, String content) {
        if (replyTarget == null || content == null || content.isBlank()) {
            return;
        }

        if (!accessTokenService.isReady()) {
            log.warn("Skip DingTalk send because access token is not ready.");
            return;
        }

        if (properties.getRobotCode() == null || properties.getRobotCode().isBlank()) {
            log.warn("Skip DingTalk send because robotCode is missing.");
            return;
        }

        try {
            if (replyTarget.getConversationType() == ConversationType.GROUP) {
                sendGroup(replyTarget, content);
            } else {
                sendPrivate(replyTarget, content);
            }
        } catch (Exception exception) {
            log.warn("Failed to send DingTalk message: {}", exception.getMessage(), exception);
        }
    }

    /**
     * 向群聊发送消息。
     *
     * @param replyTarget 回复目标
     * @param content 文本内容
     * @throws Exception 发送异常
     */
    private void sendGroup(ReplyTarget replyTarget, String content) throws Exception {
        if (replyTarget.getConversationId() == null || replyTarget.getConversationId().isBlank()) {
            log.warn("Skip DingTalk group send because conversationId is missing.");
            return;
        }

        OrgGroupSendHeaders headers = new OrgGroupSendHeaders();
        headers.setXAcsDingtalkAccessToken(accessTokenService.getAccessToken());

        OrgGroupSendRequest request = new OrgGroupSendRequest();
        request.setMsgKey("sampleText");
        request.setRobotCode(properties.getRobotCode());
        request.setOpenConversationId(replyTarget.getConversationId());
        request.setMsgParam(messageParam(content));

        robotClient.orgGroupSendWithOptions(request, headers, new RuntimeOptions());
    }

    /**
     * 向私聊发送消息。
     *
     * @param replyTarget 回复目标
     * @param content 文本内容
     * @throws Exception 发送异常
     */
    private void sendPrivate(ReplyTarget replyTarget, String content) throws Exception {
        if (replyTarget.getUserId() == null || replyTarget.getUserId().isBlank()) {
            log.warn("Skip DingTalk private send because userId is missing.");
            return;
        }

        BatchSendOTOHeaders headers = new BatchSendOTOHeaders();
        headers.setXAcsDingtalkAccessToken(accessTokenService.getAccessToken());

        BatchSendOTORequest request = new BatchSendOTORequest();
        request.setMsgKey("sampleText");
        request.setRobotCode(properties.getRobotCode());
        request.setUserIds(List.of(replyTarget.getUserId()));
        request.setMsgParam(messageParam(content));

        robotClient.batchSendOTOWithOptions(request, headers, new RuntimeOptions());
    }

    /**
     * 将文本内容包装成钉钉消息参数 JSON。
     *
     * @param content 文本内容
     * @return JSON 字符串
     */
    private String messageParam(String content) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("content", content);
        return jsonObject.toJSONString();
    }

    /**
     * 创建钉钉机器人客户端。
     *
     * @return 机器人客户端
     * @throws Exception 创建异常
     */
    private static Client createRobotClient() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.protocol = "https";
        config.regionId = "central";
        return new Client(config);
    }
}
