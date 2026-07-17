package com.jimuqu.solon.claw.gateway.feedback;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 把 Agent 推理和工具调用中间态转成消息渠道可读的进度反馈。 */
public class GatewayConversationFeedbackSink implements ConversationFeedbackSink {
    /** 记录进度消息投递失败，避免中间态反馈影响主回复。 */
    private static final Logger log =
            LoggerFactory.getLogger(GatewayConversationFeedbackSink.class);

    /** 当前会话的原始入站消息，用于复用平台、chatId 和 threadId。 */
    private final GatewayMessage message;

    /** 渠道投递服务，负责发送文本或钉钉进度卡。 */
    private final DeliveryService deliveryService;

    /** 展示设置服务，控制进度可见性、节流和预览长度。 */
    private final DisplaySettingsService displaySettingsService;

    /** 上一次已处理的工具名，用于 new 模式下抑制重复提示。 */
    private String lastToolName;

    /** 上一次已发送的推理文本，用于去重。 */
    private String lastReasoning;

    /** 上一次推理文本发送时间，用于进度节流。 */
    private long lastReasoningAt;

    /** 本轮已启动的工具数量，用于最终进度摘要。 */
    private int toolStartedCount;

    /** 本轮已完成回填的工具数量，用于最终进度摘要。 */
    private int toolFinishedCount;

    /** 钉钉进度卡业务标识，同一轮会话内用于更新同一张卡片。 */
    private String dingtalkCardBizId;

    /** 当前会话是否已经成功发送过钉钉进度卡。 */
    private boolean dingtalkCardSent;

    /**
     * 创建面向单条网关消息的反馈接收器。
     *
     * @param message 平台入站消息。
     * @param deliveryService 投递服务依赖。
     * @param displaySettingsService 展示设置服务依赖。
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
     * 在工具开始执行时按展示策略发送进度文本或钉钉卡片。
     *
     * @param toolName 工具名称。
     * @param args 工具调用参数。
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
     * 记录工具完成数量，最终回复时用于汇总。
     *
     * @param toolName 工具名称。
     * @param result 结果响应或执行结果。
     * @param durationMs 工具耗时毫秒数。
     */
    @Override
    public void onToolFinished(String toolName, String result, long durationMs) {
        toolFinishedCount++;
    }

    /**
     * 按展示设置发送推理中间态，带去重和节流。
     *
     * @param thought 模型推理或思考文本。
     */
    @Override
    public void onReasoning(String thought) {
        if (!displaySettingsService.isReasoningVisible(
                message.sourceKey(), message.getPlatform())) {
            return;
        }

        String normalized = normalize(thought);
        if (StrUtil.isEmpty(normalized) || StrUtil.equals(normalized, lastReasoning)) {
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
     * 向消息渠道发送独立语义阶段说明，不受逐工具进度开关影响。
     *
     * @param text 已完成安全过滤的阶段说明。
     */
    @Override
    public void onProgressUpdate(String text) {
        String normalized = normalize(text);
        if (StrUtil.isBlank(normalized)) {
            return;
        }
        sendText("【进度】" + truncate(normalized, 240));
    }

    /**
     * 在最终回复时更新钉钉进度卡为完成态。
     *
     * @param finalReply 本轮最终回复文本。
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
     * 构建渠道文本版工具进度提示。
     *
     * @param toolName 工具名称。
     * @param preview 已脱敏的参数预览。
     * @return 可直接发送到渠道的进度文本。
     */
    private String buildToolProgressText(String toolName, String preview) {
        if (StrUtil.isBlank(preview)) {
            return "【工具】" + toolName;
        }
        return "【工具】" + toolName + " · " + preview;
    }

    /**
     * 发送或更新钉钉进度卡，默认摘要显示已启动工具数。
     *
     * @param status 卡片展示的当前状态。
     * @param toolName 工具名称。
     * @param preview 已脱敏的参数预览。
     * @param updateOnly 是否只更新既有卡片。
     * @return 发送成功时返回 true。
     */
    private boolean sendDingtalkProgressCard(
            String status, String toolName, String preview, boolean updateOnly) {
        String summary = "当前步骤：" + toolName + "，已启动 " + toolStartedCount + " 个工具";
        return sendDingtalkProgressCard(status, toolName, preview, updateOnly, summary);
    }

    /**
     * 发送或更新钉钉进度卡。
     *
     * @param status 卡片展示的当前状态。
     * @param toolName 工具名称。
     * @param preview 已脱敏的参数预览。
     * @param updateOnly 是否只更新既有卡片。
     * @param summary 卡片摘要。
     * @return 发送成功时返回 true。
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
                    ErrorTextSupport.safeError(e));
            return false;
        }
    }

    /**
     * 发送普通文本进度消息，失败只记录日志不打断主流程。
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
                    ErrorTextSupport.safeError(e));
        }
    }

    /**
     * 复制入站消息中的路由字段，构造中间态投递请求。
     *
     * @return 未设置正文的投递请求。
     */
    private DeliveryRequest baseRequest() {
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(message.getPlatform());
        request.setChatId(message.getChatId());
        request.setUserId(message.getUserId());
        request.setChatType(message.getChatType());
        request.setThreadId(message.getThreadId());
        request.setReplyToMessageId(message.getReplyToMessageId());
        return request;
    }

    /**
     * 生成并缓存钉钉进度卡业务标识，确保同一轮会话更新同一张卡。
     *
     * @return 当前会话的卡片业务标识。
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
     * 将多行进度文本压成单行，避免中间态消息刷屏。
     *
     * @param text 待处理文本。
     * @return 单行文本。
     */
    private String normalize(String text) {
        return StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * 截断中间态文本，保留渠道消息的可读性。
     *
     * @param text 待处理文本。
     * @param limit 最大字符数。
     * @return 不超过限制的文本，超出时追加省略号。
     */
    private String truncate(String text, int limit) {
        if (StrUtil.isEmpty(text) || text.length() <= limit) {
            return StrUtil.nullToEmpty(text);
        }
        return text.substring(0, Math.max(0, limit - 3)) + "...";
    }
}
