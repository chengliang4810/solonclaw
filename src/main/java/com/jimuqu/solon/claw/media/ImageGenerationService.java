package com.jimuqu.solon.claw.media;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.provider.ImageGenProvider;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BasicValueSupport;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 图片生成运行时服务，负责选择内置提供方、下载或解码图片结果，并写入附件缓存。 */
public class ImageGenerationService {
    /** 单张生成图片允许缓存的最大字节数，避免提供方返回超大文件拖垮运行时。 */
    private static final long MAX_IMAGE_BYTES = 32L * 1024L * 1024L;

    /** 附件缓存服务，用于把生成结果落到本地媒体缓存并形成 mediaReference。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 图片生成提供方列表，按配置顺序选择第一个可用提供方。 */
    private final List<ImageGenProvider> providers;

    /** URL 安全策略服务，用于约束提供方返回的远程图片下载地址。 */
    private final SecurityPolicyService securityPolicyService;

    /** 图片输入统一边界，用于把编辑主图和参考图归一到受控附件缓存。 */
    private final ImageInputService imageInputService;

    /**
     * 创建图片Generation服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param providers 图片生成能力提供方列表。
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
     * @param providers 图片生成能力提供方列表。
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
        this.providers = BasicValueSupport.emptyListIfNull(providers);
        this.securityPolicyService = securityPolicyService;
        this.imageInputService =
                new ImageInputService(
                        appConfig, this.attachmentCacheService, securityPolicyService);
    }

    /**
     * 执行图片生成请求并返回缓存后的媒体引用。
     *
     * @param prompt 图片生成提示词，不能为空。
     * @param aspectRatio 期望宽高比；为空时使用 landscape。
     * @param imageUrl 可选编辑主图。
     * @param referenceImageUrls 可选参考图列表。
     * @return 返回生成结果、缓存附件和媒体用量。
     */
    public ImageGenerationOutcome generate(
            String prompt, String aspectRatio, String imageUrl, List<String> referenceImageUrls) {
        if (StrUtil.isBlank(prompt)) {
            return ImageGenerationOutcome.fail("Image prompt is required");
        }
        ImageAspectRatio resolvedAspectRatio = ImageAspectRatio.resolve(aspectRatio);
        if (resolvedAspectRatio == null) {
            return ImageGenerationOutcome.fail(
                    "aspect_ratio must be one of landscape, square, portrait");
        }
        ImageGenProvider provider = chooseProvider();
        if (provider == null) {
            return ImageGenerationOutcome.fail("No available image generation provider");
        }
        try {
            String primary = BasicValueSupport.trimToEmpty(imageUrl);
            List<String> references = normalizeReferences(referenceImageUrls);
            int sourceCount = (StrUtil.isBlank(primary) ? 0 : 1) + references.size();
            if (sourceCount > 0 && !provider.supportsImageInput()) {
                return ImageGenerationOutcome.fail(
                        "Image provider " + provider.name() + " does not support image input");
            }
            int sourceLimit = provider.maxSourceImages();
            if (sourceCount > 0 && (sourceLimit <= 0 || sourceCount > sourceLimit)) {
                return ImageGenerationOutcome.fail(
                        "Image provider "
                                + provider.name()
                                + " accepts at most "
                                + Math.max(0, sourceLimit)
                                + " source image(s)");
            }
            String preparedPrimary =
                    StrUtil.isBlank(primary)
                            ? null
                            : imageInputService.resolve(primary).getProviderReference();
            List<String> preparedReferences = new ArrayList<String>();
            for (String reference : references) {
                preparedReferences.add(imageInputService.resolve(reference).getProviderReference());
            }
            ImageGenProvider.ImageGenResult result =
                    provider.generate(
                            prompt,
                            resolvedAspectRatio.name(),
                            preparedPrimary,
                            Collections.unmodifiableList(preparedReferences));
            if (result == null || !result.isSuccess()) {
                return ImageGenerationOutcome.fail(
                        safeError(result == null ? "Image generation failed" : result.getError()));
            }
            ImageBytes image = bytesFromProviderUrl(result.getUrl());
            byte[] bytes = image.bytes;
            if (BasicValueSupport.isEmpty(bytes)) {
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
            usage.put("sourceImages", Integer.valueOf(sourceCount));
            return ImageGenerationOutcome.ok(
                    attachment,
                    attachmentCacheService.mediaReference(attachment),
                    provider.name(),
                    usage);
        } catch (Exception e) {
            return ImageGenerationOutcome.fail(safeError(e.getMessage()));
        }
    }

    /** 清理参考图列表中的空值并保留原顺序。 */
    private List<String> normalizeReferences(List<String> references) {
        if (references == null || references.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String reference : references) {
            String value = BasicValueSupport.trimToEmpty(reference);
            if (StrUtil.isNotBlank(value)) {
                result.add(value);
            }
        }
        return result;
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
     * 从提供方返回地址读取图片字节，支持 data URL 和经过安全策略校验的 HTTP(S) URL。
     *
     * @param url 提供方返回的图片地址。
     * @return 图片字节与推断 MIME。
     */
    private ImageBytes bytesFromProviderUrl(String url) {
        String value = BasicValueSupport.trimToEmpty(url);
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
     * 从 data URL 推断图片 MIME，推断失败时回退到 png。
     *
     * @param url 提供方返回的图片地址。
     * @return 可用于附件缓存的图片 MIME。
     */
    private String mimeFromProviderUrl(String url) {
        String value = BasicValueSupport.trimToEmpty(url);
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
     * 规范化 HTTP Content-Type，只接受 image/*。
     *
     * @param contentType HTTP 响应头里的 Content-Type。
     * @return 合法图片 MIME；不是图片时返回空字符串。
     */
    private String imageMime(String contentType) {
        String value = BasicValueSupport.trimToEmpty(contentType).toLowerCase(Locale.ROOT);
        int semi = value.indexOf(';');
        if (semi > 0) {
            value = value.substring(0, semi).trim();
        }
        return value.startsWith("image/") ? value : "";
    }

    /**
     * 根据图片 MIME 选择缓存文件扩展名。
     *
     * @param mimeType MIME 类型参数。
     * @return 返回无点号扩展名。
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

    /** 承载图片结果的原始字节和 MIME。 */
    private static class ImageBytes {
        /** 图片原始字节。 */
        private final byte[] bytes;

        /** 图片 MIME 类型。 */
        private final String mimeType;

        /**
         * 创建图片字节结果。
         *
         * @param bytes 字节参数。
         * @param mimeType MIME 类型参数。
         */
        private ImageBytes(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }

    /** 表示图片生成结果，携带调用方后续判断所需信息。 */
    public static class ImageGenerationOutcome {
        /** 本次图片生成是否成功。 */
        private final boolean success;

        /** 成功时缓存得到的附件。 */
        private final MessageAttachment attachment;

        /** 成功时可回填到会话或工具结果的媒体引用。 */
        private final String mediaReference;

        /** 实际执行生成的内置提供方名称。 */
        private final String provider;

        /** 失败时经过脱敏处理的错误。 */
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
            this.mediaUsage = BasicValueSupport.mutableLinkedMap(mediaUsage);
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
         * 判断图片生成是否成功。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取缓存附件。
         *
         * @return 返回读取到的附件。
         */
        public MessageAttachment getAttachment() {
            return attachment;
        }

        /**
         * 读取可被模型或前端引用的媒体地址。
         *
         * @return 返回读取到的媒体Reference。
         */
        public String getMediaReference() {
            return mediaReference;
        }

        /**
         * 读取实际使用的内置提供方名称。
         *
         * @return 返回读取到的提供方。
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 读取失败错误文本。
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
