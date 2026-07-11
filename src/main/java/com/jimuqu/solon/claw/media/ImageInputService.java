package com.jimuqu.solon.claw.media;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BasicValueSupport;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.noear.solon.ai.chat.content.ImageBlock;

/** 图片输入统一边界，把 URL、data URL 与缓存引用转换为受控本地附件。 */
public class ImageInputService {
    /** 远程图片下载超时，避免图像工具长期占用执行线程。 */
    private static final int DOWNLOAD_TIMEOUT_MILLIS = 30000;

    /** 附件缓存服务，负责把所有外部图片归一到运行时缓存。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 多模态图片边界，复用 MIME、体积和缓存目录约束。 */
    private final MediaInputBoundaryService mediaInputBoundaryService;

    /** URL 安全策略，负责 SSRF、重定向和私网地址阻断。 */
    private final SecurityPolicyService securityPolicyService;

    /**
     * 创建图片输入服务。
     *
     * @param appConfig 应用配置。
     * @param attachmentCacheService 附件缓存服务。
     * @param securityPolicyService URL 安全策略。
     */
    public ImageInputService(
            AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        this.attachmentCacheService =
                attachmentCacheService == null
                        ? new AttachmentCacheService(appConfig)
                        : attachmentCacheService;
        this.mediaInputBoundaryService = new MediaInputBoundaryService(appConfig);
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
    }

    /**
     * 解析图片引用并返回模型与图片 Provider 均可安全读取的缓存附件。
     *
     * @param reference HTTP(S)、data URL、media:// 或运行时缓存内本地路径。
     * @return 已通过图片边界校验的缓存附件。
     */
    public ResolvedImage resolve(String reference) {
        String value = BasicValueSupport.trimToEmpty(reference);
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("Image reference is required");
        }
        try {
            MessageAttachment attachment;
            if (value.regionMatches(true, 0, "data:", 0, 5)) {
                attachment = cacheDataUrl(value);
            } else if (value.regionMatches(true, 0, "media://", 0, 8)) {
                File file = attachmentCacheService.resolveMediaReference(value);
                attachment =
                        attachmentCacheService.fromMediaCacheFile(
                                PlatformType.MEMORY, file, "image", false, null);
            } else if (isHttpUrl(value)) {
                attachment = cacheRemoteUrl(value);
            } else {
                attachment =
                        attachmentCacheService.fromLocalFile(
                                PlatformType.MEMORY,
                                FileUtil.file(value).getAbsoluteFile(),
                                "image",
                                false,
                                null);
            }
            if (attachment.getSizeBytes() > MediaInputBoundaryService.MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Image exceeds the 20 MB input limit");
            }
            ImageBlock block = mediaInputBoundaryService.toImageBlock(attachment);
            if (block == null) {
                throw new IllegalArgumentException("Unsupported or invalid image input");
            }
            return new ResolvedImage(attachment, attachment.getLocalPath());
        } catch (Exception e) {
            String message = SecretRedactor.redact(e.getMessage(), 400);
            throw new IllegalArgumentException(
                    StrUtil.blankToDefault(message, "Failed to resolve image input"));
        }
    }

    /** 将受控 HTTP(S) 图片下载到附件缓存。 */
    private MessageAttachment cacheRemoteUrl(String url) {
        BoundedAttachmentIO.HutoolDownloadResult result =
                BoundedAttachmentIO.downloadHutoolResult(
                        url,
                        DOWNLOAD_TIMEOUT_MILLIS,
                        MediaInputBoundaryService.MAX_IMAGE_BYTES,
                        securityPolicyService);
        String mimeType = cleanContentType(result.getContentType());
        return attachmentCacheService.cacheBytes(
                PlatformType.MEMORY,
                "image",
                remoteFileName(url),
                mimeType,
                false,
                null,
                result.getData());
    }

    /** 严格解码 base64 data URL 并写入附件缓存。 */
    private MessageAttachment cacheDataUrl(String value) {
        int comma = value.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("Invalid image data URL");
        }
        String header = value.substring(0, comma).trim().toLowerCase(Locale.ROOT);
        if (!header.startsWith("data:image/") || !header.contains(";base64")) {
            throw new IllegalArgumentException("Image data URL must contain base64 image data");
        }
        String mimeType = header.substring("data:".length(), header.indexOf(';')).trim();
        byte[] data =
                java.util.Base64.getDecoder()
                        .decode(
                                value.substring(comma + 1)
                                        .trim()
                                        .getBytes(StandardCharsets.US_ASCII));
        return attachmentCacheService.cacheBytes(
                PlatformType.MEMORY,
                "image",
                "image-input." + extension(mimeType),
                mimeType,
                false,
                null,
                data);
    }

    /** 判断输入是否为完整 HTTP(S) URL。 */
    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
            return "http".equals(scheme) || "https".equals(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    /** 从响应头中移除 charset 等非 MIME 参数。 */
    private String cleanContentType(String contentType) {
        String value = StrUtil.nullToEmpty(contentType).trim();
        int separator = value.indexOf(';');
        return separator < 0 ? value : value.substring(0, separator).trim();
    }

    /** 从 URL 路径提取安全文件名，缺失时使用固定占位名。 */
    private String remoteFileName(String url) {
        try {
            String path = StrUtil.nullToEmpty(URI.create(url).getPath());
            String name = FileUtil.getName(path);
            return StrUtil.blankToDefault(name, "image-input");
        } catch (Exception e) {
            return "image-input";
        }
    }

    /** 按图片 MIME 生成缓存扩展名。 */
    private String extension(String mimeType) {
        String value = StrUtil.nullToEmpty(mimeType).toLowerCase(Locale.ROOT);
        if ("image/jpeg".equals(value)) {
            return "jpg";
        }
        if (value.startsWith("image/") && value.length() > "image/".length()) {
            String extension = value.substring("image/".length());
            return "svg+xml".equals(extension) ? "svg" : extension;
        }
        return "bin";
    }

    /** 已解析图片，Provider 只接收这里生成的受控本地路径。 */
    public static final class ResolvedImage {
        /** 供 Solon AI 多模态消息使用的附件。 */
        private final MessageAttachment attachment;

        /** 供图片生成 Provider 读取的运行时缓存路径。 */
        private final String providerReference;

        private ResolvedImage(MessageAttachment attachment, String providerReference) {
            this.attachment = attachment;
            this.providerReference = providerReference;
        }

        /**
         * @return 已通过边界校验的图片附件。
         */
        public MessageAttachment getAttachment() {
            return attachment;
        }

        /**
         * @return Provider 可读取的受控本地路径。
         */
        public String getProviderReference() {
            return providerReference;
        }
    }
}
