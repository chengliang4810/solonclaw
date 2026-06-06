package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.media.MediaInputBoundaryService;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 入站附件提示组装辅助类。 */
public final class MessageAttachmentSupport {
    /** 图片ESTIMATEDtoken的统一常量值。 */
    private static final int IMAGE_ESTIMATED_TOKENS = 1500;

    /** 语音记录文本最小token的统一常量值。 */
    private static final int VOICE_TRANSCRIPT_MIN_TOKENS = 80;

    /** VIDEO元数据最小token的统一常量值。 */
    private static final int VIDEO_METADATA_MIN_TOKENS = 300;

    /** 文件元数据最小token的统一常量值。 */
    private static final int FILE_METADATA_MIN_TOKENS = 32;

    /** 字节PERESTIMATEDtoken的统一常量值。 */
    private static final int BYTES_PER_ESTIMATED_TOKEN = 4096;

    /** UNKNOWN大小字节的统一常量值。 */
    private static final long UNKNOWN_SIZE_BYTES = -1L;

    /** 图片MIMEALLOW列表的统一常量值。 */
    private static final Set<String> IMAGE_MIME_ALLOWLIST =
            new HashSet<String>(
                    Arrays.asList(
                            "image/png",
                            "image/jpeg",
                            "image/gif",
                            "image/webp",
                            "image/bmp",
                            "image/heic",
                            "image/heif"));

    /** 创建消息附件辅助实例。 */
    private MessageAttachmentSupport() {}

    /** 将附件元信息注入为会话可见文本。 */
    public static String composeEffectiveUserText(GatewayMessage message) {
        String text = StrUtil.nullToEmpty(message == null ? null : message.getText()).trim();
        if (message == null
                || message.getAttachments() == null
                || message.getAttachments().isEmpty()) {
            return text;
        }

        StringBuilder buffer = new StringBuilder();
        if (StrUtil.isNotBlank(text)) {
            buffer.append(text).append("\n\n");
        }
        buffer.append("[attachments]");
        int imageCandidates = 0;
        for (MessageAttachment attachment : message.getAttachments()) {
            String kind = normalizedKind(attachment);
            AttachmentInsight insight = inspect(attachment, imageCandidates);
            if ("image".equals(kind) && insight.visionCandidate) {
                imageCandidates++;
            }
            buffer.append("\n- kind=").append(kind);
            buffer.append(", originalName=")
                    .append(safeAttachmentName(attachment.getOriginalName()));
            buffer.append(", mimeType=").append(safeInline(attachment.getMimeType()));
            buffer.append(", localPath=")
                    .append(safeAttachmentPath(attachment.getLocalPath(), true));
            buffer.append(", fromQuote=").append(attachment.isFromQuote());
            buffer.append(", sizeBytes=")
                    .append(insight.sizeBytes < 0 ? "unknown" : String.valueOf(insight.sizeBytes));
            buffer.append(", estimatedTokens=").append(insight.estimatedTokens);
            buffer.append(", availability=").append(insight.availability);
            buffer.append(", payloadMode=").append(insight.payloadMode);
            buffer.append(", signal=").append(insight.signal);
            if (StrUtil.isNotBlank(insight.hint)) {
                buffer.append(", hint=").append(insight.hint);
            }
            if (StrUtil.isNotBlank(attachment.getTranscribedText())) {
                buffer.append(", transcribedText=")
                        .append(safeInline(attachment.getTranscribedText()));
            }
        }
        return buffer.toString().trim();
    }

    /** 返回附件清单的副本，避免空指针。 */
    public static List<MessageAttachment> safeAttachments(GatewayMessage message) {
        return message == null
                ? java.util.Collections.<MessageAttachment>emptyList()
                : message.getAttachments();
    }

    /**
     * 执行estimatedtoken成本相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回estimated token成本结果。
     */
    public static int estimatedTokenCost(MessageAttachment attachment) {
        return inspect(attachment, 0).estimatedTokens;
    }

    /**
     * 执行multimodalAvailability相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回multimodal Availability结果。
     */
    public static String multimodalAvailability(MessageAttachment attachment) {
        return inspect(attachment, 0).availability;
    }

    /**
     * 执行multimodal载荷模式相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回multimodal Payload模式结果。
     */
    public static String multimodalPayloadMode(MessageAttachment attachment) {
        return inspect(attachment, 0).payloadMode;
    }

    /**
     * 执行multimodalSignal相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回multimodal Signal结果。
     */
    public static String multimodalSignal(MessageAttachment attachment) {
        return inspect(attachment, 0).signal;
    }

    /**
     * 判断是否可以Send As Vision Payload。
     *
     * @param attachment 附件参数。
     * @param providerSupportsVision 提供方SupportsVision标识或键值。
     * @return 如果Send As Vision Payload满足条件则返回 true，否则返回 false。
     */
    public static boolean canSendAsVisionPayload(
            MessageAttachment attachment, boolean providerSupportsVision) {
        AttachmentInsight insight = inspect(attachment, 0);
        return providerSupportsVision && insight.visionCandidate;
    }

    /**
     * 估算附件token From Summary。
     *
     * @param text 待处理文本。
     * @return 返回附件token From Summary结果。
     */
    public static int estimateAttachmentTokensFromSummary(String text) {
        String value = StrUtil.nullToEmpty(text);
        int total = 0;
        int from = 0;
        String marker = "estimatedTokens=";
        while (from < value.length()) {
            int index = value.indexOf(marker, from);
            if (index < 0) {
                break;
            }
            int start = index + marker.length();
            int end = start;
            while (end < value.length() && Character.isDigit(value.charAt(end))) {
                end++;
            }
            if (end > start) {
                try {
                    total = safeAdd(total, Integer.parseInt(value.substring(start, end)));
                } catch (NumberFormatException ignored) {
                }
            }
            from = Math.max(end, start + 1);
        }
        return total;
    }

    /**
     * 执行文件NotFound消息相关逻辑。
     *
     * @param platform 平台参数。
     * @param attachment 附件参数。
     * @return 返回文件Not Found消息结果。
     */
    public static String fileNotFoundMessage(String platform, MessageAttachment attachment) {
        String name =
                attachment == null
                        ? ""
                        : StrUtil.blankToDefault(
                                attachment.getOriginalName(), attachment.getLocalPath());
        String path = attachment == null ? "" : attachment.getLocalPath();
        StringBuilder message =
                new StringBuilder(StrUtil.blankToDefault(platform, "Channel"))
                        .append(" attachment file not found");
        String safeName = safeAttachmentName(name);
        if (StrUtil.isNotBlank(safeName)) {
            message.append(": ").append(safeName);
        }
        String safePath = safeAttachmentPath(path, false);
        if (StrUtil.isNotBlank(safePath) && !StrUtil.equals(safePath, safeName)) {
            message.append(" (path=").append(safePath).append(")");
        }
        return SecretRedactor.redact(message.toString(), 1000);
    }

    /**
     * 生成安全展示用的Add。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 返回safe Add结果。
     */
    private static int safeAdd(int left, int right) {
        long value = (long) left + right;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * 执行inspect相关逻辑。
     *
     * @param attachment 附件参数。
     * @param acceptedImageCount accepted图片Count参数。
     * @return 返回inspect结果。
     */
    private static AttachmentInsight inspect(MessageAttachment attachment, int acceptedImageCount) {
        String kind = normalizedKind(attachment);
        String mime =
                StrUtil.nullToEmpty(attachment == null ? null : attachment.getMimeType())
                        .trim()
                        .toLowerCase(Locale.ROOT);
        long sizeBytes = attachmentSizeBytes(attachment);
        int estimatedTokens = estimateTokens(kind, sizeBytes, attachment);
        String availability = "metadata_only";
        String payloadMode = "metadata_only";
        String signal = "degrade_to_metadata";
        String hint = "llm_can_use_attachment_metadata";
        boolean visionCandidate = false;
        if ("image".equals(kind)) {
            if (!mime.startsWith("image/") || !IMAGE_MIME_ALLOWLIST.contains(mime)) {
                availability = "blocked";
                payloadMode = "reject_payload";
                signal = "reject_unsupported_image";
                hint = "image_mime_not_supported_for_vision";
            } else if (sizeBytes > MediaInputBoundaryService.MAX_IMAGE_BYTES) {
                availability = "blocked";
                payloadMode = "reject_payload";
                signal = "reject_image_too_large";
                hint = "image_too_large_for_vision";
            } else if (acceptedImageCount >= MediaInputBoundaryService.MAX_IMAGE_ATTACHMENTS) {
                availability = "metadata_only";
                payloadMode = "metadata_only";
                signal = "degrade_image_limit";
                hint = "image_limit_exceeded_for_vision";
            } else {
                availability = "vision_payload_candidate";
                payloadMode = "vision_payload";
                signal = "accept_vision_payload";
                hint = "image_may_be_sent_as_multimodal_payload";
                visionCandidate = true;
            }
        } else if ("voice".equals(kind)) {
            if (StrUtil.isNotBlank(attachment == null ? null : attachment.getTranscribedText())) {
                availability = "transcript_available";
                payloadMode = "transcript_only";
                signal = "degrade_to_transcript";
                hint = "voice_transcript_injected";
            } else {
                availability = "needs_transcription";
                payloadMode = "await_transcription";
                signal = "reject_until_transcribed";
                hint = "voice_requires_speech_transcribe_before_llm_can_read_audio";
            }
        } else if ("video".equals(kind)) {
            availability = "metadata_only";
            payloadMode = "metadata_only";
            signal = "degrade_video_metadata";
            hint = "video_payload_not_sent_to_llm";
        }
        return new AttachmentInsight(
                sizeBytes,
                estimatedTokens,
                availability,
                payloadMode,
                signal,
                hint,
                visionCandidate);
    }

    /**
     * 估算token。
     *
     * @param kind kind 参数。
     * @param sizeBytes size字节参数。
     * @param attachment 附件参数。
     * @return 返回token结果。
     */
    private static int estimateTokens(String kind, long sizeBytes, MessageAttachment attachment) {
        long sizeTokens =
                sizeBytes <= 0
                        ? 0
                        : (sizeBytes + BYTES_PER_ESTIMATED_TOKEN - 1L) / BYTES_PER_ESTIMATED_TOKEN;
        int tokens;
        if ("image".equals(kind)) {
            tokens = IMAGE_ESTIMATED_TOKENS;
        } else if ("voice".equals(kind)) {
            tokens =
                    Math.max(
                            VOICE_TRANSCRIPT_MIN_TOKENS,
                            safeInline(attachment == null ? null : attachment.getTranscribedText())
                                            .length()
                                    / 2);
        } else if ("video".equals(kind)) {
            tokens = VIDEO_METADATA_MIN_TOKENS;
        } else {
            tokens = FILE_METADATA_MIN_TOKENS;
        }
        long estimated = Math.max((long) tokens, sizeTokens);
        return estimated > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimated;
    }

    /**
     * 执行附件大小字节相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回附件大小Bytes结果。
     */
    private static long attachmentSizeBytes(MessageAttachment attachment) {
        if (attachment == null) {
            return UNKNOWN_SIZE_BYTES;
        }
        if (attachment.getSizeBytes() > 0) {
            return attachment.getSizeBytes();
        }
        if (StrUtil.isNotBlank(attachment.getData())) {
            return Math.max(1L, cleanBase64(attachment.getData()).length() * 3L / 4L);
        }
        String path = StrUtil.nullToEmpty(attachment.getLocalPath()).trim();
        if (StrUtil.isBlank(path) || path.startsWith("media://")) {
            return UNKNOWN_SIZE_BYTES;
        }
        try {
            java.io.File file = new java.io.File(path);
            if (file.isFile()) {
                return file.length();
            }
        } catch (Exception ignored) {
        }
        return UNKNOWN_SIZE_BYTES;
    }

    /**
     * 清理Base64。
     *
     * @param data 数据参数。
     * @return 返回clean Base64结果。
     */
    private static String cleanBase64(String data) {
        String value = StrUtil.nullToEmpty(data).trim();
        int comma = value.indexOf(',');
        return comma >= 0 ? value.substring(comma + 1).trim() : value;
    }

    /**
     * 执行normalizedKind相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回normalized Kind结果。
     */
    private static String normalizedKind(MessageAttachment attachment) {
        String kind =
                StrUtil.nullToEmpty(attachment == null ? null : attachment.getKind())
                        .trim()
                        .toLowerCase(Locale.ROOT);
        return StrUtil.blankToDefault(kind, "file");
    }

    /** 承载附件Insight相关状态和辅助逻辑。 */
    private static final class AttachmentInsight {
        /** 记录附件Insight中的大小字节。 */
        private final long sizeBytes;

        /** 记录附件Insight中的estimatedtoken。 */
        private final int estimatedTokens;

        /** 记录附件Insight中的availability。 */
        private final String availability;

        /** 记录附件Insight中的载荷模式。 */
        private final String payloadMode;

        /** 记录附件Insight中的signal。 */
        private final String signal;

        /** 记录附件Insight中的hint。 */
        private final String hint;

        /** 是否启用visionCandidate。 */
        private final boolean visionCandidate;

        /**
         * 创建附件Insight实例，并注入运行所需依赖。
         *
         * @param sizeBytes size字节参数。
         * @param estimatedTokens estimatedtoken参数。
         * @param availability availability 参数。
         * @param payloadMode 载荷模式请求载荷。
         * @param signal signal 参数。
         * @param hint hint 参数。
         * @param visionCandidate visionCandidate标识或键值。
         */
        private AttachmentInsight(
                long sizeBytes,
                int estimatedTokens,
                String availability,
                String payloadMode,
                String signal,
                String hint,
                boolean visionCandidate) {
            this.sizeBytes = sizeBytes;
            this.estimatedTokens = estimatedTokens;
            this.availability = availability;
            this.payloadMode = payloadMode;
            this.signal = signal;
            this.hint = hint;
            this.visionCandidate = visionCandidate;
        }
    }

    /**
     * 生成安全展示用的内联。
     *
     * @param text 待处理文本。
     * @return 返回safe Inline结果。
     */
    private static String safeInline(String text) {
        String value = StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
        value = value.length() > 300 ? value.substring(0, 300) : value;
        return SecretRedactor.redact(value, 300);
    }

    /**
     * 生成安全展示用的附件名称。
     *
     * @param name 名称参数。
     * @return 返回safe附件名称结果。
     */
    private static String safeAttachmentName(String name) {
        String raw = StrUtil.nullToEmpty(name).replace('\r', ' ').replace('\n', ' ').trim();
        // 先在原始文件名上判断敏感名称，避免脱敏后丢失命中信号。
        if (isSensitiveFileName(raw)) {
            return "[redacted-sensitive-name]";
        }
        String value = raw.length() > 300 ? raw.substring(0, 300) : raw;
        value = SecretRedactor.redact(value, 300);
        // 脱敏后再次检查，避免截断或替换后的名称仍暴露敏感文件类型。
        if (isSensitiveFileName(value)) {
            return "[redacted-sensitive-name]";
        }
        return value;
    }

    /**
     * 生成安全展示用的附件路径。
     *
     * @param localPath 文件或目录路径参数。
     * @param asReference as引用参数。
     * @return 返回safe附件路径。
     */
    private static String safeAttachmentPath(String localPath, boolean asReference) {
        String raw = StrUtil.nullToEmpty(localPath).replace('\r', ' ').replace('\n', ' ').trim();
        if (raw.length() == 0) {
            return "";
        }
        // 在原始路径上检查敏感目录模式，优先阻断凭据路径泄漏。
        String normalized = raw.replace('\\', '/');
        if (containsSensitivePath(normalized)) {
            return "[redacted-sensitive-path]";
        }
        // 对末级文件名做敏感名称判断，避免只暴露文件名也泄漏凭据语义。
        String trimmed = normalized;
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        int drive = name.indexOf(':');
        if (drive >= 0) {
            name = name.substring(drive + 1);
        }
        if (StrUtil.isBlank(name)) {
            return "[redacted-path]";
        }
        if (isSensitiveFileName(name)) {
            return "[redacted-sensitive-path]";
        }
        // 路径片段中若包含 token=xxx 等键值形式，按敏感路径整体遮蔽。
        if (containsTokenSegment(normalized)) {
            return "[redacted-sensitive-path]";
        }
        // 通过敏感路径检查后，再执行长度限制和通用密钥脱敏。
        String value = raw.length() > 300 ? raw.substring(0, 300) : raw;
        value = SecretRedactor.redact(value, 300);
        // 引用路径只暴露脱敏后的末级文件名。
        String redactedNorm = value.replace('\\', '/');
        while (redactedNorm.endsWith("/") && redactedNorm.length() > 1) {
            redactedNorm = redactedNorm.substring(0, redactedNorm.length() - 1);
        }
        int slash2 = redactedNorm.lastIndexOf('/');
        String safeName = slash2 >= 0 ? redactedNorm.substring(slash2 + 1) : redactedNorm;
        int drive2 = safeName.indexOf(':');
        if (drive2 >= 0) {
            safeName = safeName.substring(drive2 + 1);
        }
        if (StrUtil.isBlank(safeName)) {
            return "[redacted-path]";
        }
        return asReference ? "path://" + safeName : safeName;
    }

    /**
     * 判断是否包含tokenSegment。
     *
     * @param normalizedPath 文件或目录路径参数。
     * @return 返回contains token Segment结果。
     */
    private static boolean containsTokenSegment(String normalizedPath) {
        String lower = StrUtil.nullToEmpty(normalizedPath).toLowerCase(Locale.ROOT);
        // 匹配形如 token=value、key=value 或 secret=value 的路径片段。
        for (String segment : lower.split("/")) {
            if (segment.contains("token=")
                    || segment.contains("key=")
                    || segment.contains("secret=")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否包含Sensitive路径。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Sensitive路径。
     */
    private static boolean containsSensitivePath(String value) {
        String lower = StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
        return lower.contains("/.ssh/")
                || lower.contains("/.aws/")
                || lower.contains("/.gnupg/")
                || lower.contains("/.kube/")
                || lower.contains("/.docker/")
                || lower.contains("/.azure/")
                || lower.contains("/.claude/")
                || lower.contains("/.Jimuqu/")
                || lower.contains("/.codex/")
                || lower.contains("/.qwen/")
                || lower.contains("/.config/gh/")
                || lower.contains("/.config/gcloud/")
                || lower.endsWith("/.ssh")
                || lower.endsWith("/.aws")
                || lower.endsWith("/.gnupg")
                || lower.endsWith("/.kube")
                || lower.endsWith("/.docker")
                || lower.endsWith("/.azure")
                || lower.endsWith("/.claude")
                || lower.endsWith("/.Jimuqu")
                || lower.endsWith("/.codex")
                || lower.endsWith("/.qwen")
                || lower.endsWith("/.config/gh")
                || lower.endsWith("/.config/gcloud");
    }

    /**
     * 判断是否Sensitive文件名称。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Sensitive文件名称满足条件则返回 true，否则返回 false。
     */
    private static boolean isSensitiveFileName(String value) {
        String name = StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT).trim();
        return ".env".equals(name)
                || name.startsWith(".env.")
                || ".netrc".equals(name)
                || ".pgpass".equals(name)
                || ".npmrc".equals(name)
                || ".pypirc".equals(name)
                || "authorized_keys".equals(name)
                || name.startsWith("id_rsa")
                || name.startsWith("id_ed25519")
                || name.contains("credential")
                || name.contains("secret")
                || name.contains("password")
                || name.contains("passwd")
                || name.contains("private_key")
                || "credentials".equals(name)
                || "credentials.json".equals(name)
                || ".credentials.json".equals(name)
                || ".anthropic_oauth.json".equals(name)
                || "oauth_creds.json".equals(name)
                || "application_default_credentials.json".equals(name);
    }
}
