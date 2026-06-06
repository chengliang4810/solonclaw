package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** MessagingTools 实现。 */
@RequiredArgsConstructor
public class MessagingTools {
    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 记录Messaging中的来源键。 */
    private final String sourceKey;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 注入应用配置，用于Messaging。 */
    private final AppConfig appConfig;

    /**
     * 发送消息。
     *
     * @param platform 平台参数。
     * @param chatId 聊天标识。
     * @param threadId thread标识。
     * @param text 待处理文本。
     * @param mediaPaths 文件或目录路径参数。
     * @param channelExtrasJson 渠道ExtrasJSON参数。
     * @return 返回消息结果。
     */
    @ToolMapping(
            name = "send_message",
            description =
                    "Send a text message with optional local media attachments to a target platform and chat. If platform or chatId is empty, send back to the current source.")
    public String sendMessage(
            @Param(name = "platform", description = "目标平台名", required = false) String platform,
            @Param(name = "chatId", description = "目标聊天 ID", required = false) String chatId,
            @Param(name = "threadId", description = "目标线程或话题 ID", required = false) String threadId,
            @Param(name = "text", description = "要发送的文本") String text,
            @Param(
                            name = "mediaPaths",
                            description =
                                    "可选本地附件路径数组；优先传 PDF/文件工具返回的路径或文件名。文件必须位于 runtime/cache 下，或是 runtime 根目录直接生成的安全附件文件。",
                            required = false)
                    List<String> mediaPaths,
            @Param(
                            name = "channelExtrasJson",
                            description = "可选渠道扩展 JSON；例如钉钉 AI card 所需参数",
                            required = false)
                    String channelExtrasJson)
            throws Exception {
        String[] parts = SourceKeySupport.split(sourceKey);
        if (StrUtil.isBlank(parts[0]) || StrUtil.isBlank(parts[1])) {
            return error("invalid sourceKey");
        }
        PlatformType sourcePlatform = PlatformType.fromName(parts[0]);
        if (sourcePlatform == null) {
            return error("invalid source platform: " + parts[0]);
        }
        String sourceChatId = parts[1];
        PlatformType targetPlatform =
                PlatformType.fromName(StrUtil.isBlank(platform) ? parts[0] : platform);
        if (targetPlatform == null) {
            return error("invalid target platform: " + platform);
        }
        String targetChatId = StrUtil.isBlank(chatId) ? parts[1] : chatId;
        String targetThreadId = StrUtil.blankToDefault(threadId, parts[3]);
        CronAutoDeliveryContext.Target autoTarget =
                CronAutoDeliveryContext.matchingTarget(
                        targetPlatform, targetChatId, targetThreadId);
        if (autoTarget != null) {
            return ToolResultEnvelope.ok("Skipped duplicate cron auto-delivery target")
                    .data("skipped", Boolean.TRUE)
                    .data("reason", "cron_auto_delivery_duplicate_target")
                    .data("target", safeResult(autoTarget.label(), 400))
                    .data(
                            "note",
                            "This cron job will already auto-deliver its final response to that same target.")
                    .preview("Skipped duplicate cron send_message")
                    .metadata("sourceKey", safeResult(sourceKey, 400))
                    .toJson();
        }
        String targetUserId = StrUtil.blankToDefault(parts[2], null);
        List<MessageAttachment> attachments = resolveAttachments(targetPlatform, mediaPaths);
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(targetPlatform);
        request.setChatId(targetChatId);
        request.setUserId(targetUserId);
        request.setThreadId(StrUtil.blankToDefault(targetThreadId, null));
        request.setText(text);
        request.setAttachments(attachments);
        request.setChannelExtras(parseChannelExtras(channelExtrasJson));
        deliveryService.deliver(request);
        MessageDeliveryTracker.recordEcho(
                sourceKey,
                sourcePlatform,
                sourceChatId,
                targetPlatform,
                targetChatId,
                text,
                attachments != null && !attachments.isEmpty());
        return ToolResultEnvelope.ok("Message delivered")
                .data("platform", targetPlatform.name())
                .data("chatId", safeResult(targetChatId, 400))
                .data("attachmentCount", Integer.valueOf(attachments.size()))
                .preview(safeResult(text, 1000))
                .metadata("sourceKey", safeResult(sourceKey, 400))
                .toJson();
    }

    /**
     * 发送消息。
     *
     * @param platform 平台参数。
     * @param chatId 聊天标识。
     * @param text 待处理文本。
     * @param mediaPaths 文件或目录路径参数。
     * @param channelExtrasJson 渠道ExtrasJSON参数。
     * @return 返回消息结果。
     */
    public String sendMessage(
            String platform,
            String chatId,
            String text,
            List<String> mediaPaths,
            String channelExtrasJson)
            throws Exception {
        return sendMessage(platform, chatId, null, text, mediaPaths, channelExtrasJson);
    }

    /**
     * 执行错误相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回error结果。
     */
    private String error(String message) {
        return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
    }

    /**
     * 生成安全展示用的结果。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe结果。
     */
    private String safeResult(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /**
     * 解析附件。
     *
     * @param platform 平台参数。
     * @param mediaPaths 文件或目录路径参数。
     * @return 返回解析后的附件。
     */
    private List<MessageAttachment> resolveAttachments(
            PlatformType platform, List<String> mediaPaths) {
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        if (mediaPaths == null) {
            return attachments;
        }

        for (String rawPath : mediaPaths) {
            if (StrUtil.isBlank(rawPath)) {
                continue;
            }
            File file = resolveAttachmentFile(rawPath.trim());
            attachments.add(
                    attachmentCacheService.fromLocalOrGeneratedFile(
                            platform, file.getAbsoluteFile(), null, false, null));
        }
        return attachments;
    }

    /**
     * 解析附件文件。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回解析后的附件文件。
     */
    private File resolveAttachmentFile(String rawPath) {
        File direct = new File(rawPath);
        if (direct.isFile()) {
            return direct;
        }

        File workspaceFile = new File(System.getProperty("user.dir"), rawPath);
        if (workspaceFile.isFile()) {
            return workspaceFile;
        }

        String name = direct.getName();
        for (File candidate : fallbackCandidates(name)) {
            if (candidate.isFile()) {
                return candidate;
            }
        }

        return direct.isAbsolute() ? direct : workspaceFile;
    }

    /**
     * 执行兜底Candidates相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回兜底Candidates结果。
     */
    private List<File> fallbackCandidates(String fileName) {
        List<File> candidates = new ArrayList<File>();
        if (appConfig != null && appConfig.getRuntime() != null) {
            File runtimeHome = new File(appConfig.getRuntime().getHome());
            File cacheDir = new File(appConfig.getRuntime().getCacheDir());
            candidates.add(new File(cacheDir, "pdf/" + fileName));
            candidates.add(new File(cacheDir, fileName));
            candidates.add(new File(runtimeHome, fileName));
        }
        candidates.add(new File(System.getProperty("user.dir"), fileName));
        return candidates;
    }

    /**
     * 解析渠道Extras。
     *
     * @param channelExtrasJson 渠道ExtrasJSON参数。
     * @return 返回解析后的渠道Extras。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseChannelExtras(String channelExtrasJson) {
        if (StrUtil.isBlank(channelExtrasJson)) {
            return new LinkedHashMap<String, Object>();
        }
        Object parsed = org.noear.snack4.ONode.deserialize(channelExtrasJson.trim(), Object.class);
        if (parsed instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
        }
        throw new IllegalArgumentException("channelExtrasJson must be a JSON object");
    }
}
