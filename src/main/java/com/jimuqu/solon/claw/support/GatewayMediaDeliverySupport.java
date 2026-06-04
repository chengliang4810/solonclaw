package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 解析文本中的 MEDIA: 附件指令并转换为安全附件投递模型。 */
public class GatewayMediaDeliverySupport {
    private static final Logger log = LoggerFactory.getLogger(GatewayMediaDeliverySupport.class);

    private final AttachmentCacheService attachmentCacheService;

    public GatewayMediaDeliverySupport(AttachmentCacheService attachmentCacheService) {
        this.attachmentCacheService = attachmentCacheService;
    }

    public DeliveryMedia resolve(PlatformType platform, String content) {
        String value = StrUtil.nullToEmpty(content);
        List<MediaDirectiveSupport.MediaDirective> refs = MediaDirectiveSupport.parse(value);
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        List<MediaDirectiveSupport.MediaDirective> resolved =
                new ArrayList<MediaDirectiveSupport.MediaDirective>();
        if (attachmentCacheService != null) {
            for (MediaDirectiveSupport.MediaDirective ref : refs) {
                File file = FileUtil.file(ref.getPath());
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

    private String safePath(File file) {
        if (file == null) {
            return "[unknown]";
        }
        String name = StrUtil.blankToDefault(file.getName(), file.getPath());
        return SecretRedactor.redact(name, 400);
    }

    private String removeResolvedMediaTags(
            String text, List<MediaDirectiveSupport.MediaDirective> resolved) {
        String cleaned = StrUtil.nullToEmpty(text);
        if (resolved != null) {
            for (MediaDirectiveSupport.MediaDirective media : resolved) {
                if (StrUtil.isNotBlank(media.getToken())) {
                    cleaned = cleaned.replace(media.getToken(), "");
                }
            }
        }
        return cleaned.replaceAll("\\n{3,}", "\n\n").trim();
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
