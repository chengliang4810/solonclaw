package com.jimuqu.solon.claw.gateway.feedback;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 面向消息渠道的中间态反馈 sink。 */
public class GatewayConversationFeedbackSink implements ConversationFeedbackSink {
    /** 日志的统一常量值。 */
    private static final Logger log =
            LoggerFactory.getLogger(GatewayConversationFeedbackSink.class);

    /** 记录消息网关对话反馈接收端中的消息。 */
    private final GatewayMessage message;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 保存展示设置服务集合，维持调用顺序或去重语义。 */
    private final DisplaySettingsService displaySettingsService;

    /** 记录消息网关对话反馈接收端中的最近一次工具名称。 */
    private String lastToolName;

    /** 记录消息网关对话反馈接收端中的最近一次推理。 */
    private String lastReasoning;

    /** 记录消息网关对话反馈接收端中的最近一次推理时间。 */
    private long lastReasoningAt;

    /** 记录消息网关对话反馈接收端中的工具Started次数。 */
    private int toolStartedCount;

    /** 记录消息网关对话反馈接收端中的工具Finished次数。 */
    private int toolFinishedCount;

    /** 记录消息网关对话反馈接收端中的钉钉卡片Biz标识。 */
    private String dingtalkCardBizId;

    /** 是否启用钉钉卡片Sent。 */
    private boolean dingtalkCardSent;

    /**
     * 创建消息网关对话Feedback接收端实例，并注入运行所需依赖。
     *
     * @param message 平台消息或错误消息。
     * @param deliveryService 投递服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     */
    public GatewayConversationFeedbackSink(
            GatewayMessage message,
            DeliveryService deliveryService,
            DisplaySettingsService displaySettingsService) {
        this.message = message;
        this.deliveryService = deliveryService;
        this.displaySettingsService = displaySettingsService;
    }

    /**
     * 响应工具Started事件。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     */
    @Override
    public void onToolStarted(String toolName, Map<String, Object> args) {
        String progressMode = displaySettingsService.resolveToolProgress(message.getPlatform());
        if ("off".equals(progressMode)) {
            return;
        }

        toolStartedCount++;
        boolean verbose = "verbose".equals(progressMode);
        boolean emit =
                "all".equals(progressMode)
                        || verbose
                        || ("new".equals(progressMode) && !StrUtil.equals(toolName, lastToolName));
        lastToolName = toolName;
        if (!emit) {
            return;
        }

        String preview =
                ToolPreviewSupport.buildPreview(
                        toolName, args, displaySettingsService.toolPreviewLength(), verbose);

        if (message.getPlatform() == PlatformType.DINGTALK
                && StrUtil.isNotBlank(displaySettingsService.dingtalkProgressCardTemplateId())) {
            if (!sendDingtalkProgressCard("进行中", toolName, preview, false)) {
                sendText(buildToolProgressText(toolName, preview));
            }
            return;
        }

        sendText(buildToolProgressText(toolName, preview));
    }

    /**
     * 响应工具Finished事件。
     *
     * @param toolName 工具名称。
     * @param result 结果响应或执行结果。
     * @param durationMs durationMs 参数。
     */
    @Override
    public void onToolFinished(String toolName, String result, long durationMs) {
        toolFinishedCount++;
    }

    /**
     * 响应推理事件。
     *
     * @param thought thought 参数。
     */
    @Override
    public void onReasoning(String thought) {
        if (!displaySettingsService.isReasoningVisible(
                message.sourceKey(), message.getPlatform())) {
            return;
        }

        String normalized = normalize(thought);
        if (normalized.length() == 0 || StrUtil.equals(normalized, lastReasoning)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (displaySettingsService.progressThrottleMs() > 0
                && lastReasoningAt > 0
                && now - lastReasoningAt < displaySettingsService.progressThrottleMs()) {
            return;
        }

        lastReasoning = normalized;
        lastReasoningAt = now;
        sendText(
                "【思考】"
                        + truncate(
                                normalized,
                                Math.max(200, displaySettingsService.toolPreviewLength() * 4)));
    }

    /**
     * 响应最终回复事件。
     *
     * @param finalReply 最终回复参数。
     */
    @Override
    public void onFinalReply(String finalReply) {
        if (!dingtalkCardSent || message.getPlatform() != PlatformType.DINGTALK) {
            return;
        }

        String summary = "本轮共调用 " + toolStartedCount + " 个工具，完成 " + toolFinishedCount + " 个结果回填";
        sendDingtalkProgressCard(
                "已完成",
                "final_reply",
                truncate(
                        normalize(finalReply),
                        Math.max(120, displaySettingsService.toolPreviewLength() * 3)),
                true,
                summary);
    }

    /**
     * 构建工具Progress Text。
     *
     * @param toolName 工具名称。
     * @param preview 预览参数。
     * @return 返回创建好的工具Progress Text。
     */
    private String buildToolProgressText(String toolName, String preview) {
        if (StrUtil.isBlank(preview)) {
            return "【工具】" + toolName;
        }
        return "【工具】" + toolName + " · " + preview;
    }

    /**
     * 发送钉钉Progress Card。
     *
     * @param status 状态参数。
     * @param toolName 工具名称。
     * @param preview 预览参数。
     * @param updateOnly updateOnly 参数。
     * @return 返回钉钉Progress Card结果。
     */
    private boolean sendDingtalkProgressCard(
            String status, String toolName, String preview, boolean updateOnly) {
        String summary = "当前步骤：" + toolName + "，已启动 " + toolStartedCount + " 个工具";
        return sendDingtalkProgressCard(status, toolName, preview, updateOnly, summary);
    }

    /**
     * 发送钉钉Progress Card。
     *
     * @param status 状态参数。
     * @param toolName 工具名称。
     * @param preview 预览参数。
     * @param updateOnly updateOnly 参数。
     * @param summary 摘要参数。
     * @return 返回钉钉Progress Card结果。
     */
    private boolean sendDingtalkProgressCard(
            String status, String toolName, String preview, boolean updateOnly, String summary) {
        try {
            DeliveryRequest request = baseRequest();
            String templateId = displaySettingsService.dingtalkProgressCardTemplateId();
            request.getChannelExtras().put("mode", "ai_card");
            request.getChannelExtras().put("cardTemplateId", templateId);
            request.getChannelExtras().put("cardBizId", ensureDingtalkCardBizId());
            request.getChannelExtras()
                    .put(
                            "cardData",
                            DingTalkProgressCardSupport.buildCardData(
                                    "Jimuqu 长任务进度",
                                    status,
                                    summary,
                                    StrUtil.blankToDefault(preview, toolName),
                                    DateUtil.now()));
            if (updateOnly || dingtalkCardSent) {
                request.getChannelExtras().put("updateExisting", true);
            }
            deliveryService.deliver(request);
            dingtalkCardSent = true;
            return true;
        } catch (Exception e) {
            log.warn(
                    "DingTalk progress card delivery failed: chatId={}, toolName={}, error={}",
                    message.getChatId(),
                    toolName,
                    safeError(e));
            return false;
        }
    }

    /**
     * 发送Text。
     *
     * @param text 待处理文本。
     */
    private void sendText(String text) {
        if (StrUtil.isBlank(text)) {
            return;
        }

        try {
            DeliveryRequest request = baseRequest();
            request.setText(text);
            deliveryService.deliver(request);
        } catch (Exception e) {
            log.warn(
                    "Conversation feedback delivery failed: platform={}, chatId={}, error={}",
                    message.getPlatform(),
                    message.getChatId(),
                    safeError(e));
        }
    }

    /**
     * 执行基础请求相关逻辑。
     *
     * @return 返回base请求结果。
     */
    private DeliveryRequest baseRequest() {
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(message.getPlatform());
        request.setChatId(message.getChatId());
        request.setUserId(message.getUserId());
        request.setChatType(message.getChatType());
        request.setThreadId(message.getThreadId());
        return request;
    }

    /**
     * 确保钉钉Card Biz标识。
     *
     * @return 返回钉钉Card Biz标识。
     */
    private String ensureDingtalkCardBizId() {
        if (StrUtil.isBlank(dingtalkCardBizId)) {
            dingtalkCardBizId =
                    "jimuqu-progress-"
                            + Integer.toHexString(
                                    StrUtil.nullToEmpty(message.sourceKey()).hashCode())
                            + "-"
                            + Integer.toHexString(
                                    StrUtil.nullToEmpty(message.getThreadId()).hashCode())
                            + "-"
                            + Long.toHexString(System.currentTimeMillis());
        }
        return dingtalkCardBizId;
    }

    /**
     * 执行规范化相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回规范化结果。
     */
    private String normalize(String text) {
        return StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * 执行truncate相关逻辑。
     *
     * @param text 待处理文本。
     * @param limit 最大返回数量。
     * @return 返回truncate结果。
     */
    private String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return StrUtil.nullToEmpty(text);
        }
        return text.substring(0, Math.max(0, limit - 3)) + "...";
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 错误参数。
     * @return 返回safe Error结果。
     */
    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message;
        return SecretRedactor.redact(value, 1000);
    }
}
