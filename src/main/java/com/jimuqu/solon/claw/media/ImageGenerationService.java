package com.jimuqu.solon.claw.media;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 图片生成运行时服务。 */
public class ImageGenerationService {
    /** 最大图片字节的统一常量值。 */
    private static final long MAX_IMAGE_BYTES = 32L * 1024L * 1024L;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 保存providers集合，维持调用顺序或去重语义。 */
    private final List<ImageGenProvider> providers;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /**
     * 创建图片Generation服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param providers 能力提供方列表。
     */
    public ImageGenerationService(
            com.jimuqu.solon.claw.config.AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<ImageGenProvider> providers) {
        this(appConfig, attachmentCacheService, providers, null);
    }

    /**
     * 创建图片Generation服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param providers 能力提供方列表。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public ImageGenerationService(
            com.jimuqu.solon.claw.config.AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<ImageGenProvider> providers,
            SecurityPolicyService securityPolicyService) {
        this.attachmentCacheService =
                attachmentCacheService == null
                        ? new AttachmentCacheService(appConfig)
                        : attachmentCacheService;
        this.providers = providers == null ? Collections.<ImageGenProvider>emptyList() : providers;
        this.securityPolicyService = securityPolicyService;
    }

    /**
     * 执行图片生成请求并返回缓存后的媒体引用。
     *
     * @param prompt 提示词参数。
     * @param aspectRatio aspectRatio 参数。
     * @param options options 参数。
     * @return 返回generate结果。
     */
    public ImageGenerationOutcome generate(
            String prompt, String aspectRatio, Map<String, Object> options) {
        if (StrUtil.isBlank(prompt)) {
            return ImageGenerationOutcome.fail("Image prompt is required");
        }
        ImageGenProvider provider = chooseProvider();
        if (provider == null) {
            return ImageGenerationOutcome.fail("No available image generation provider");
        }
        try {
            ImageGenProvider.ImageGenResult result =
                    provider.generate(
                            prompt,
                            StrUtil.blankToDefault(aspectRatio, "1:1"),
                            options == null ? Collections.<String, Object>emptyMap() : options);
            if (result == null || !result.isSuccess()) {
                return ImageGenerationOutcome.fail(
                        safeError(result == null ? "Image generation failed" : result.getError()));
            }
            ImageBytes image = bytesFromProviderUrl(result.getUrl());
            byte[] bytes = image.bytes;
            if (bytes == null || bytes.length == 0) {
                return ImageGenerationOutcome.fail("Image provider returned empty image");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                return ImageGenerationOutcome.fail("Generated image is too large");
            }
            String mimeType =
                    StrUtil.blankToDefault(image.mimeType, mimeFromProviderUrl(result.getUrl()));
            MessageAttachment attachment =
                    attachmentCacheService.cacheBytes(
                            PlatformType.MEMORY,
                            "image",
                            "generated-image." + extension(mimeType),
                            mimeType,
                            false,
                            null,
                            bytes);
            Map<String, Object> usage = new LinkedHashMap<String, Object>();
            usage.put("generatedImages", Integer.valueOf(1));
            usage.put("imageOutputBytes", Long.valueOf(bytes.length));
            return ImageGenerationOutcome.ok(
                    attachment,
                    attachmentCacheService.mediaReference(attachment),
                    provider.name(),
                    usage);
        } catch (Exception e) {
            return ImageGenerationOutcome.fail(safeError(e.getMessage()));
        }
    }

    /**
     * 选择提供方。
     *
     * @return 返回choose提供方结果。
     */
    private ImageGenProvider chooseProvider() {
        for (ImageGenProvider provider : providers) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    /**
     * 执行字节From提供方URL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回bytes From提供方URL结果。
     */
    private ImageBytes bytesFromProviderUrl(String url) {
        String value = StrUtil.nullToEmpty(url).trim();
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("Invalid generated image data URL");
            }
            String header = value.substring(0, comma).toLowerCase(Locale.ROOT);
            if (!header.contains(";base64")) {
                throw new IllegalArgumentException("Generated image data URL must be base64");
            }
            return new ImageBytes(
                    Base64.decode(value.substring(comma + 1)), mimeFromProviderUrl(value));
        }
        URI uri = URI.create(value);
        String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (securityPolicyService == null) {
                throw new IllegalArgumentException(
                        "Generated image URL download requires URL security policy");
            }
            BoundedAttachmentIO.HutoolDownloadResult result =
                    BoundedAttachmentIO.downloadHutoolResult(
                            value, 30000, MAX_IMAGE_BYTES, securityPolicyService);
            String mimeType = imageMime(result.getContentType());
            return new ImageBytes(
                    result.getData(), StrUtil.blankToDefault(mimeType, mimeFromProviderUrl(value)));
        }
        throw new IllegalArgumentException("Generated image result URL is not supported");
    }

    /**
     * 执行MIMEFrom提供方URL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回mime From提供方URL结果。
     */
    private String mimeFromProviderUrl(String url) {
        String value = StrUtil.nullToEmpty(url).trim();
        if (value.startsWith("data:")) {
            int semi = value.indexOf(';');
            if (semi > 5) {
                String mime = value.substring(5, semi).toLowerCase(Locale.ROOT);
                if (mime.startsWith("image/")) {
                    return mime;
                }
            }
        }
        return "image/png";
    }

    /**
     * 执行图片MIME相关逻辑。
     *
     * @param contentType content类型参数。
     * @return 返回图片Mime结果。
     */
    private String imageMime(String contentType) {
        String value = StrUtil.nullToEmpty(contentType).trim().toLowerCase(Locale.ROOT);
        int semi = value.indexOf(';');
        if (semi > 0) {
            value = value.substring(0, semi).trim();
        }
        return value.startsWith("image/") ? value : "";
    }

    /**
     * 执行扩展名相关逻辑。
     *
     * @param mimeType MIME 类型参数。
     * @return 返回extension结果。
     */
    private String extension(String mimeType) {
        if ("image/jpeg".equals(mimeType)) {
            return "jpg";
        }
        if ("image/webp".equals(mimeType)) {
            return "webp";
        }
        if ("image/gif".equals(mimeType)) {
            return "gif";
        }
        return "png";
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Error结果。
     */
    private String safeError(String value) {
        return SecretRedactor.redact(
                StrUtil.blankToDefault(value, "Image generation failed"), 1000);
    }

    /** 承载图片字节相关状态和辅助逻辑。 */
    private static class ImageBytes {
        /** 记录图片字节中的字节。 */
        private final byte[] bytes;

        /** 记录图片字节中的MIME 类型。 */
        private final String mimeType;

        /**
         * 创建图片Bytes实例，并注入运行所需依赖。
         *
         * @param bytes 字节参数。
         * @param mimeType MIME 类型参数。
         */
        private ImageBytes(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }

    /** 表示图片Generation结果，携带调用方后续判断所需信息。 */
    public static class ImageGenerationOutcome {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录图片Generation中的附件。 */
        private final MessageAttachment attachment;

        /** 记录图片Generation中的媒体引用。 */
        private final String mediaReference;

        /** 记录图片Generation中的提供方。 */
        private final String provider;

        /** 记录图片Generation中的错误。 */
        private final String error;

        /** 保存媒体用量映射，便于按键快速查询。 */
        private final Map<String, Object> mediaUsage;

        /**
         * 创建图片Generation Outcome实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param attachment 附件参数。
         * @param mediaReference 媒体引用参数。
         * @param provider 模型或能力提供方。
         * @param error 错误参数。
         * @param mediaUsage 媒体用量参数。
         */
        private ImageGenerationOutcome(
                boolean success,
                MessageAttachment attachment,
                String mediaReference,
                String provider,
                String error,
                Map<String, Object> mediaUsage) {
            this.success = success;
            this.attachment = attachment;
            this.mediaReference = mediaReference;
            this.provider = provider;
            this.error = error;
            this.mediaUsage =
                    mediaUsage == null
                            ? Collections.<String, Object>emptyMap()
                            : new LinkedHashMap<String, Object>(mediaUsage);
        }

        /**
         * 构造成功结果。
         *
         * @param attachment 附件参数。
         * @param mediaReference 媒体引用参数。
         * @param provider 模型或能力提供方。
         * @param mediaUsage 媒体用量参数。
         * @return 返回ok结果。
         */
        public static ImageGenerationOutcome ok(
                MessageAttachment attachment,
                String mediaReference,
                String provider,
                Map<String, Object> mediaUsage) {
            return new ImageGenerationOutcome(
                    true, attachment, mediaReference, provider, null, mediaUsage);
        }

        /**
         * 构造失败结果并携带安全错误信息。
         *
         * @param error 错误参数。
         * @return 返回fail结果。
         */
        public static ImageGenerationOutcome fail(String error) {
            return new ImageGenerationOutcome(false, null, null, null, error, null);
        }

        /**
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取附件。
         *
         * @return 返回读取到的附件。
         */
        public MessageAttachment getAttachment() {
            return attachment;
        }

        /**
         * 读取媒体Reference。
         *
         * @return 返回读取到的媒体Reference。
         */
        public String getMediaReference() {
            return mediaReference;
        }

        /**
         * 读取提供方。
         *
         * @return 返回读取到的提供方。
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 读取Error。
         *
         * @return 返回读取到的Error。
         */
        public String getError() {
            return error;
        }

        /**
         * 读取媒体用量。
         *
         * @return 返回读取到的媒体用量。
         */
        public Map<String, Object> getMediaUsage() {
            return mediaUsage;
        }
    }
}
