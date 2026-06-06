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
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(GatewayMediaDeliverySupport.class);

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /**
     * 创建消息网关媒体投递辅助实例，并注入运行所需依赖。
     *
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public GatewayMediaDeliverySupport(AttachmentCacheService attachmentCacheService) {
        this.attachmentCacheService = attachmentCacheService;
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param platform 平台参数。
     * @param content 待处理内容。
     * @return 返回resolve结果。
     */
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

    /**
     * 生成安全展示用的路径。
     *
     * @param file 文件或目录路径参数。
     * @return 返回safe路径。
     */
    private String safePath(File file) {
        if (file == null) {
            return "[unknown]";
        }
        String name = StrUtil.blankToDefault(file.getName(), file.getPath());
        return SecretRedactor.redact(name, 400);
    }

    /**
     * 移除Resolved媒体Tags。
     *
     * @param text 待处理文本。
     * @param resolved resolved 参数。
     * @return 返回Resolved媒体Tags结果。
     */
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

    /** 承载投递媒体相关状态和辅助逻辑。 */
    @Getter
    public static class DeliveryMedia {
        /** 记录投递媒体中的文本。 */
        private final String text;

        /** 保存附件集合，维持调用顺序或去重语义。 */
        private final List<MessageAttachment> attachments;

        /**
         * 创建投递媒体实例，并注入运行所需依赖。
         *
         * @param text 待处理文本。
         * @param attachments attachments 参数。
         */
        private DeliveryMedia(String text, List<MessageAttachment> attachments) {
            this.text = StrUtil.nullToEmpty(text);
            this.attachments =
                    attachments == null
                            ? new ArrayList<MessageAttachment>()
                            : new ArrayList<MessageAttachment>(attachments);
        }
    }
}
