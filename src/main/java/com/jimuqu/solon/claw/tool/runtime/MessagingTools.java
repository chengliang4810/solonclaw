package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.AttachmentPathResolver;
import com.jimuqu.solon.claw.support.FilePathSupport;
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
    public String sendMessage(
            String platform,
            String chatId,
            String threadId,
            String text,
            List<String> mediaPaths,
            String channelExtrasJson)
            throws Exception {
        return dispatchMessage(
                null, platform, chatId, threadId, text, mediaPaths, channelExtrasJson);
    }

    /**
     * 发送消息或列出可用目标。
     *
     * @param action 操作：send/list。
     * @param platform 平台参数。
     * @param chatId 聊天标识。
     * @param threadId thread标识。
     * @param text 待处理文本。
     * @param mediaPaths 文件或目录路径参数。
     * @param channelExtrasJson 渠道ExtrasJSON参数。
     * @return 返回消息结果。
     */
    private String dispatchMessage(
            String action,
            String platform,
            String chatId,
            String threadId,
            String text,
            List<String> mediaPaths,
            String channelExtrasJson)
            throws Exception {
        String normalizedAction = StrUtil.blankToDefault(action, "send").trim().toLowerCase();
        if ("list".equals(normalizedAction) || "targets".equals(normalizedAction)) {
            return listTargets();
        }
        String[] parts = SourceKeySupport.split(sourceKey);
        if (StrUtil.isBlank(parts[0]) || StrUtil.isBlank(parts[1])) {
            return error("invalid sourceKey");
        }
        PlatformType sourcePlatform = PlatformType.fromName(parts[0]);
        if (sourcePlatform == null) {
            return error("invalid source platform: " + parts[0]);
        }
        String sourceChatId = parts[1];
        String sourceThreadId = parts[3];
        PlatformType targetPlatform =
                PlatformType.fromName(StrUtil.isBlank(platform) ? parts[0] : platform);
        if (targetPlatform == null) {
            return error("invalid target platform: " + platform);
        }
        String targetChatId = StrUtil.isBlank(chatId) ? parts[1] : chatId;
        String targetThreadId = StrUtil.blankToDefault(threadId, sourceThreadId);
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
        MessageDeliveryTracker.recordDirectDelivery(
                sourceKey,
                sourcePlatform,
                sourceChatId,
                sourceThreadId,
                targetPlatform,
                targetChatId,
                targetThreadId);
        return ToolResultEnvelope.ok("Message delivered")
                .data("platform", targetPlatform.name())
                .data("chatId", safeResult(targetChatId, 400))
                .data("attachmentCount", Integer.valueOf(attachments.size()))
                .preview(safeResult(text, 1000))
                .metadata("sourceKey", safeResult(sourceKey, 400))
                .toJson();
    }

    /**
     * 列出可用消息目标。
     *
     * @return 返回目标列表。
     */
    @ToolMapping(
            name = "send_message",
            description =
                    "Send a message or list available targets. Use action='list' before sending to a non-current target.")
    public String sendMessage(
            @Param(name = "action", required = false, description = "send/list") String action,
            @Param(name = "platform", description = "目标平台名", required = false) String platform,
            @Param(name = "chatId", description = "目标聊天 ID", required = false) String chatId,
            @Param(name = "threadId", description = "目标线程或话题 ID", required = false) String threadId,
            @Param(name = "text", description = "要发送的文本", required = false) String text,
            @Param(
                            name = "mediaPaths",
                            description = "可选附件路径数组，支持当前 Profile 工作区文件或附件缓存路径。",
                            required = false)
                    List<String> mediaPaths,
            @Param(name = "channelExtrasJson", description = "可选渠道扩展 JSON", required = false)
                    String channelExtrasJson)
            throws Exception {
        return dispatchMessage(
                action, platform, chatId, threadId, text, mediaPaths, channelExtrasJson);
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
        return dispatchMessage(null, platform, chatId, null, text, mediaPaths, channelExtrasJson);
    }

    /**
     * 列出当前来源和默认回发目标。
     *
     * @return 返回结构化目标信息。
     */
    private String listTargets() {
        String[] parts = SourceKeySupport.split(sourceKey);
        Map<String, Object> current = new LinkedHashMap<String, Object>();
        current.put("sourceKey", safeResult(sourceKey, 400));
        current.put("platform", safeResult(parts[0], 100));
        current.put("chatId", safeResult(parts[1], 400));
        current.put("userId", safeResult(parts[2], 400));
        current.put("threadId", safeResult(parts[3], 400));
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("label", "current_source");
        target.put("platform", safeResult(parts[0], 100));
        target.put("chatId", safeResult(parts[1], 400));
        target.put("threadId", safeResult(parts[3], 400));
        List<Map<String, Object>> targets = new ArrayList<Map<String, Object>>();
        targets.add(target);
        return ToolResultEnvelope.ok("Available message targets")
                .data("current", current)
                .data("targets", targets)
                .data("explicitTargetsAllowed", Boolean.FALSE)
                .metadata("sourceKey", safeResult(sourceKey, 400))
                .toJson();
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
            try {
                attachments.add(
                        attachmentCacheService.fromLocalOrGeneratedFile(
                                platform, file.getAbsoluteFile(), null, false, null));
            } catch (IllegalArgumentException e) {
                if (!StrUtil.contains(e.getMessage(), "outside runtime cache")
                        || !isWorkspaceGeneratedFile(file)) {
                    throw e;
                }
                AttachmentPathResolver.ResolvedInput resolved =
                        new AttachmentPathResolver(appConfig, attachmentCacheService)
                                .resolve(file.getAbsolutePath());
                if (resolved.getAttachments().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Attachment file could not be imported from the current profile workspace");
                }
                attachments.addAll(resolved.getAttachments());
            }
        }
        return attachments;
    }

    /** 判断附件是否为当前 Profile 工作区根目录生成物，运行内部文件和子目录仍禁止外发。 */
    private boolean isWorkspaceGeneratedFile(File file) {
        File workspaceHome = preferredWorkspaceHome();
        if (file == null || !file.isFile() || workspaceHome == null) {
            return false;
        }
        try {
            File canonicalFile = file.getCanonicalFile();
            File canonicalHome = workspaceHome.getCanonicalFile();
            if (!FilePathSupport.isUnderPath(canonicalFile, canonicalHome)) {
                return false;
            }
            String relative = canonicalHome.toPath().relativize(canonicalFile.toPath()).toString();
            return StrUtil.isNotBlank(relative)
                    && relative.indexOf(File.separatorChar) < 0
                    && !"config.yml".equalsIgnoreCase(relative);
        } catch (Exception e) {
            throw new IllegalArgumentException("Attachment path cannot be resolved", e);
        }
    }

    /**
     * 解析附件文件。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回解析后的附件文件。
     */
    private File resolveAttachmentFile(String rawPath) {
        File direct = new File(rawPath);
        if (direct.isAbsolute() && direct.isFile()) {
            return direct;
        }

        File workspaceHome = preferredWorkspaceHome();
        File workspaceFile = null;
        if (!direct.isAbsolute() && workspaceHome != null) {
            workspaceFile = resolveInsideWorkspace(workspaceHome, rawPath);
            if (workspaceFile.isFile()) {
                return workspaceFile;
            }
        }

        if (ProfileRuntimeScope.current() == null && direct.isFile()) {
            return direct;
        }

        String name = direct.getName();
        for (File candidate : fallbackCandidates(name)) {
            if (candidate.isFile()) {
                return candidate;
            }
        }

        if (direct.isAbsolute()) {
            return direct;
        }
        if (workspaceFile != null) {
            return workspaceFile;
        }
        return new File(System.getProperty("user.dir"), rawPath);
    }

    /**
     * 执行兜底Candidates相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回兜底Candidates结果。
     */
    private List<File> fallbackCandidates(String fileName) {
        List<File> candidates = new ArrayList<File>();
        File workspaceHome = preferredWorkspaceHome();
        if (workspaceHome != null) {
            File cacheDir = configuredCacheDir(workspaceHome);
            candidates.add(new File(cacheDir, "pdf/" + fileName));
            candidates.add(new File(cacheDir, fileName));
            candidates.add(new File(workspaceHome, fileName));
        }
        if (ProfileRuntimeScope.current() == null) {
            candidates.add(new File(System.getProperty("user.dir"), fileName));
        }
        return candidates;
    }

    /** 返回当前 Profile 优先的附件工作区；未进入作用域时使用注入配置。 */
    private File preferredWorkspaceHome() {
        ProfileRuntimeScope.Context scoped = ProfileRuntimeScope.current();
        if (scoped != null && scoped.getHome() != null) {
            return scoped.getHome().toFile().getAbsoluteFile();
        }
        if (appConfig == null
                || appConfig.getRuntime() == null
                || StrUtil.isBlank(appConfig.getRuntime().getHome())) {
            return null;
        }
        return new File(appConfig.getRuntime().getHome()).getAbsoluteFile();
    }

    /** 解析当前工作区内的相对附件，并拒绝路径遍历或链接越界。 */
    private File resolveInsideWorkspace(File workspaceHome, String rawPath) {
        try {
            File canonicalHome = workspaceHome.getCanonicalFile();
            File candidate = new File(canonicalHome, rawPath).getCanonicalFile();
            if (!FilePathSupport.isUnderPath(candidate, canonicalHome)) {
                throw new IllegalArgumentException(
                        "Attachment path escapes the current profile workspace");
            }
            return candidate;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Attachment path cannot be resolved", e);
        }
    }

    /** 返回与当前工作区匹配的缓存目录，避免 scoped 调用误用其他 Profile 的配置。 */
    private File configuredCacheDir(File workspaceHome) {
        if (appConfig != null
                && appConfig.getRuntime() != null
                && StrUtil.isNotBlank(appConfig.getRuntime().getHome())
                && StrUtil.isNotBlank(appConfig.getRuntime().getCacheDir())) {
            try {
                File configuredHome = new File(appConfig.getRuntime().getHome()).getCanonicalFile();
                if (configuredHome.equals(workspaceHome.getCanonicalFile())) {
                    return new File(appConfig.getRuntime().getCacheDir()).getAbsoluteFile();
                }
            } catch (Exception ignored) {
                // 路径无法规范化时回退当前 Profile 的约定缓存目录。
            }
        }
        return new File(workspaceHome, "cache");
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
