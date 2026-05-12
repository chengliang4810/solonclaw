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
        String value = safeInline(name);
        if (isSensitiveFileName(value)) {
            return "[redacted-sensitive-name]";
        }
        return value;
    }

    private static String safeAttachmentPath(String localPath, boolean asReference) {
        String value = safeInline(localPath);
        if (value.length() == 0) {
            return "";
        }
        String normalized = value.replace('\\', '/');
        if (containsSensitivePath(normalized)) {
            return "[redacted-sensitive-path]";
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
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
        return asReference ? "path://" + SecretRedactor.redact(name, 200) : name;
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
