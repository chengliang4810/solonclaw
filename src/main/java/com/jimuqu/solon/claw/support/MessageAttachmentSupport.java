package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import java.util.List;
import java.util.Locale;

/** 入站附件提示组装辅助类。 */
public final class MessageAttachmentSupport {
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
        for (MessageAttachment attachment : message.getAttachments()) {
            buffer.append("\n- kind=").append(StrUtil.blankToDefault(attachment.getKind(), "file"));
            buffer.append(", originalName=")
                    .append(safeAttachmentName(attachment.getOriginalName()));
            buffer.append(", mimeType=")
                    .append(safeInline(attachment.getMimeType()));
            buffer.append(", localPath=")
                    .append(safeAttachmentPath(attachment.getLocalPath(), true));
            buffer.append(", fromQuote=").append(attachment.isFromQuote());
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

    private static String safeInline(String text) {
        String value = StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
        value = value.length() > 300 ? value.substring(0, 300) : value;
        return SecretRedactor.redact(value, 300);
    }

    private static String safeAttachmentName(String name) {
        String raw = StrUtil.nullToEmpty(name).replace('\r', ' ').replace('\n', ' ').trim();
        // Check sensitive file name on raw value before any redaction
        if (isSensitiveFileName(raw)) {
            return "[redacted-sensitive-name]";
        }
        String value = raw.length() > 300 ? raw.substring(0, 300) : raw;
        value = SecretRedactor.redact(value, 300);
        // Re-check after redaction in case the redacted form is still sensitive
        if (isSensitiveFileName(value)) {
            return "[redacted-sensitive-name]";
        }
        return value;
    }

    private static String safeAttachmentPath(String localPath, boolean asReference) {
        String raw = StrUtil.nullToEmpty(localPath).replace('\r', ' ').replace('\n', ' ').trim();
        if (raw.length() == 0) {
            return "";
        }
        // Check sensitive path patterns on the raw value before any redaction
        String normalized = raw.replace('\\', '/');
        if (containsSensitivePath(normalized)) {
            return "[redacted-sensitive-path]";
        }
        // Check if the path segment (filename) is sensitive before redaction
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
        // Check if the path contains token-like segments (e.g. token=xxx/...)
        if (containsTokenSegment(normalized)) {
            return "[redacted-sensitive-path]";
        }
        // Safe to apply length limit and secret redaction now
        String value = raw.length() > 300 ? raw.substring(0, 300) : raw;
        value = SecretRedactor.redact(value, 300);
        // Re-extract name after redaction for the reference path
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

    private static boolean containsTokenSegment(String normalizedPath) {
        String lower = StrUtil.nullToEmpty(normalizedPath).toLowerCase(Locale.ROOT);
        // Match path segments that look like token=value or key=value
        for (String segment : lower.split("/")) {
            if (segment.contains("token=") || segment.contains("key=") || segment.contains("secret=")) {
                return true;
            }
        }
        return false;
    }

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
