package com.jimuqu.solonclaw.dingtalk;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.dingtalkrobot_1_0.Client;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendHeaders;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendRequest;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.jimuqu.solonclaw.config.DingTalkConfig;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Init;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 钉钉消息发送服务
 *
 * @author SolonClaw
 */
@Component
public class DingTalkMessageSender {

    private static final Logger log = LoggerFactory.getLogger(DingTalkMessageSender.class);
    private static final String ACCESS_TOKEN_URL = "https://api.dingtalk.com/v1.0/oauth2/accessToken";

    @Inject
    private DingTalkConfig dingTalkConfig;

    private Client robotClient;
    private String cachedAccessToken;
    private Instant tokenExpireTime;

    @Init
    public void init() throws Exception {
        Config config = new Config();
        config.protocol = "https";
        config.regionId = "central";
        robotClient = new Client(config);
    }

    /**
     * 获取 access_token（带缓存）
     */
    private String getAccessToken() throws Exception {
        // 检查缓存的 token 是否有效
        if (cachedAccessToken != null && tokenExpireTime != null
                && Instant.now().isBefore(tokenExpireTime)) {
            return cachedAccessToken;
        }

        String appKey = dingTalkConfig.getAppKey();
        String appSecret = dingTalkConfig.getAppSecret();

        // 构建请求体
        JSONObject requestBody = new JSONObject();
        requestBody.put("appKey", appKey);
        requestBody.put("appSecret", appSecret);

        String responseStr = cn.hutool.http.HttpUtil.post(ACCESS_TOKEN_URL, requestBody.toString());
        JSONObject response = JSONObject.parseObject(responseStr);

        if (response.containsKey("accessToken")) {
            cachedAccessToken = response.getString("accessToken");
            tokenExpireTime = Instant.now().plus(Duration.ofMinutes(85));
            log.info("获取 access_token 成功");
            return cachedAccessToken;
        } else {
            log.error("获取 access_token 失败: {}", responseStr);
            throw new Exception("Failed to get access_token: " + responseStr);
        }
    }

    /**
     * 发送消息到群
     *
     * @param openConversationId 会话ID
     * @param text               消息内容（支持 Markdown 格式）
     * @param isMarkdown         是否使用 Markdown 格式
     */
    public void sendToGroup(String openConversationId, String text, boolean isMarkdown) {
        String robotCode = dingTalkConfig.getRobotCode();
        if (robotCode == null || robotCode.isBlank()) {
            log.warn("机器人 Code 未配置，无法发送消息");
            return;
        }

        try {
            String accessToken = getAccessToken();

            OrgGroupSendHeaders headers = new OrgGroupSendHeaders();
            headers.setXAcsDingtalkAccessToken(accessToken);

            OrgGroupSendRequest request = new OrgGroupSendRequest();

            // 根据 isMarkdown 参数选择消息类型
            if (isMarkdown) {
                request.setMsgKey("sampleMarkdown");
                JSONObject msgParam = new JSONObject();
                msgParam.put("title", "AI 助手");
                msgParam.put("text", text);
                request.setMsgParam(msgParam.toJSONString());
            } else {
                request.setMsgKey("sampleText");
                JSONObject msgParam = new JSONObject();
                msgParam.put("content", text);
                request.setMsgParam(msgParam.toJSONString());
            }

            request.setRobotCode(robotCode);
            request.setOpenConversationId(openConversationId);

            OrgGroupSendResponse response = robotClient.orgGroupSendWithOptions(request, headers, new RuntimeOptions());

            if (Objects.isNull(response) || Objects.isNull(response.getBody())) {
                log.error("发送群消息失败，响应为空");
                return;
            }

            log.info("发送钉钉消息成功，messageId={}", response.getBody().getProcessQueryKey());

        } catch (Exception e) {
            log.error("发送钉钉消息异常", e);
        }
    }

    /**
     * 发送消息到群（默认使用 Markdown 格式）
     *
     * @param openConversationId 会话ID
     * @param text               消息内容（支持 Markdown 格式）
     */
    public void sendToGroup(String openConversationId, String text) {
        sendToGroup(openConversationId, text, true); // 默认启用 Markdown
    }

    /**
     * 发送消息到用户（私聊）
     * <p>
     * 使用机器人发送单聊消息
     *
     * @param userId 用户ID
     * @param text   消息内容
     */
    public void sendToUser(String userId, String text) {
        String robotCode = dingTalkConfig.getRobotCode();
        if (robotCode == null || robotCode.isBlank()) {
            log.warn("机器人 Code 未配置，无法发送私聊消息");
            return;
        }

        try {
            String accessToken = getAccessToken();

            // 构建单聊消息请求
            // 钉钉单聊消息API
            String url = "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend";

            JSONObject msgParam = new JSONObject();
            msgParam.put("content", text);

            JSONObject requestBody = new JSONObject();
            requestBody.put("robotCode", robotCode);
            requestBody.put("userIds", new String[]{userId});
            requestBody.put("msgKey", "sampleText");
            requestBody.put("msgParam", msgParam.toJSONString());

            log.info("发送私聊消息到 userId={}: {}", userId, text);

            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                    requestBody.toString(),
                    okhttp3.MediaType.parse("application/json; charset=utf-8")
            );

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .header("x-acs-dingtalk-access-token", accessToken)
                    .post(body)
                    .build();

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.Response response = client.newCall(request).execute();
            String resultStr = response.body().string();

            JSONObject result = JSONObject.parseObject(resultStr);
            if (result.containsKey("processQueryKey")) {
                log.info("发送私聊消息成功，processQueryKey={}", result.getString("processQueryKey"));
            } else {
                log.error("发送私聊消息失败: {}", resultStr);
            }

        } catch (Exception e) {
            log.error("发送私聊消息异常", e);
        }
    }
}
