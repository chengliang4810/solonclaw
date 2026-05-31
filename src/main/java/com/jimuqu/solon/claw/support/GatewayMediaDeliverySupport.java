package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 解析文本中的 MEDIA: 附件指令并转换为安全附件投递模型。 */
public class GatewayMediaDeliverySupport {
    private static final Logger log = LoggerFactory.getLogger(GatewayMediaDeliverySupport.class);
    private static final Pattern MEDIA_PATTERN =
            Pattern.compile(
                    "[`\"']?MEDIA:\\s*(?<path>`[^`\\n]+`|\"[^\"\\n]+\"|'[^'\\n]+'|\\S+)[`\"']?",
                    Pattern.CASE_INSENSITIVE);

    private final AttachmentCacheService attachmentCacheService;

    public GatewayMediaDeliverySupport(AttachmentCacheService attachmentCacheService) {
        this.attachmentCacheService = attachmentCacheService;
    }

    public DeliveryMedia resolve(PlatformType platform, String content) {
        String value = StrUtil.nullToEmpty(content);
        List<MediaRef> refs = parse(value);
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        List<MediaRef> resolved = new ArrayList<MediaRef>();
        if (attachmentCacheService != null) {
            for (MediaRef ref : refs) {
                File file = FileUtil.file(ref.path);
                if (!file.isFile()) {
                    continue;
                }
                try {
                    attachments.add(
                            attachmentCacheService.fromLocalOrGeneratedFile(
                                    platform, file, null, false, null));
                    resolved.add(ref);
                } catch (RuntimeException e) {
                    log.warn(
                            "MEDIA attachment skipped: path={}, error={}",
                            safePath(file),
                            SecretRedactor.redact(e.getMessage(), 400));
                }
            }
        }
        if (resolved.isEmpty()) {
            return new DeliveryMedia(value, attachments);
        }
        return new DeliveryMedia(removeResolvedMediaTags(value, resolved), attachments);
    }

    private List<MediaRef> parse(String content) {
        List<MediaRef> refs = new ArrayList<MediaRef>();
        Matcher matcher = MEDIA_PATTERN.matcher(StrUtil.nullToEmpty(content));
        while (matcher.find()) {
            String path = cleanupMediaPath(matcher.group("path"));
            if (StrUtil.isNotBlank(path)) {
                refs.add(new MediaRef(matcher.group(), path));
            }
        }
        return refs;
    }

    private String cleanupMediaPath(String raw) {
        String path = StrUtil.nullToEmpty(raw).trim();
        if (path.length() >= 2) {
            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);
            if ((first == '`' || first == '"' || first == '\'') && first == last) {
                path = path.substring(1, path.length() - 1).trim();
            }
        }
        while (path.startsWith("`") || path.startsWith("\"") || path.startsWith("'")) {
            path = path.substring(1).trim();
        }
        while (path.endsWith("`")
                || path.endsWith("\"")
                || path.endsWith("'")
                || path.endsWith(",")
                || path.endsWith(".")
                || path.endsWith(";")
                || path.endsWith(":")
                || path.endsWith(")")
                || path.endsWith("}")
                || path.endsWith("]")) {
            path = path.substring(0, path.length() - 1).trim();
        }
        if (path.startsWith("~/")) {
            return new File(System.getProperty("user.home"), path.substring(2)).getAbsolutePath();
        }
        return path;
    }

    private String safePath(File file) {
        if (file == null) {
            return "[unknown]";
        }
        String name = StrUtil.blankToDefault(file.getName(), file.getPath());
        return SecretRedactor.redact(name, 400);
    }

    private String removeResolvedMediaTags(String text, List<MediaRef> resolved) {
        String cleaned = StrUtil.nullToEmpty(text);
        if (resolved != null) {
            for (MediaRef media : resolved) {
                if (StrUtil.isNotBlank(media.token)) {
                    cleaned = cleaned.replace(media.token, "");
                }
            }
        }
        return cleaned.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static class MediaRef {
        private final String token;
        private final String path;

        private MediaRef(String token, String path) {
            this.token = StrUtil.nullToEmpty(token);
            this.path = StrUtil.nullToEmpty(path);
        }
    }

    @Getter
    public static class DeliveryMedia {
        private final String text;
        private final List<MessageAttachment> attachments;

        private DeliveryMedia(String text, List<MessageAttachment> attachments) {
            this.text = StrUtil.nullToEmpty(text);
            this.attachments =
                    attachments == null
                            ? new ArrayList<MessageAttachment>()
                            : new ArrayList<MessageAttachment>(attachments);
        }
    }
}
