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
    private static final long MAX_IMAGE_BYTES = 32L * 1024L * 1024L;

    private final AttachmentCacheService attachmentCacheService;
    private final List<ImageGenProvider> providers;
    private final SecurityPolicyService securityPolicyService;

    public ImageGenerationService(
            com.jimuqu.solon.claw.config.AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<ImageGenProvider> providers) {
        this(appConfig, attachmentCacheService, providers, null);
    }

    public ImageGenerationService(
            com.jimuqu.solon.claw.config.AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<ImageGenProvider> providers,
            SecurityPolicyService securityPolicyService) {
        this.attachmentCacheService =
                attachmentCacheService == null
                        ? new AttachmentCacheService(appConfig)
                        : attachmentCacheService;
        this.providers =
                providers == null
                        ? Collections.<ImageGenProvider>emptyList()
                        : providers;
        this.securityPolicyService = securityPolicyService;
    }

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
            String mimeType = StrUtil.blankToDefault(image.mimeType, mimeFromProviderUrl(result.getUrl()));
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
                    attachment, attachmentCacheService.mediaReference(attachment), provider.name(), usage);
        } catch (Exception e) {
            return ImageGenerationOutcome.fail(safeError(e.getMessage()));
        }
    }

    private ImageGenProvider chooseProvider() {
        for (ImageGenProvider provider : providers) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

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
            return new ImageBytes(Base64.decode(value.substring(comma + 1)), mimeFromProviderUrl(value));
        }
        URI uri = URI.create(value);
        String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (securityPolicyService == null) {
                throw new IllegalArgumentException("Generated image URL download requires URL security policy");
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

    private String imageMime(String contentType) {
        String value = StrUtil.nullToEmpty(contentType).trim().toLowerCase(Locale.ROOT);
        int semi = value.indexOf(';');
        if (semi > 0) {
            value = value.substring(0, semi).trim();
        }
        return value.startsWith("image/") ? value : "";
    }

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

    private String safeError(String value) {
        return SecretRedactor.redact(StrUtil.blankToDefault(value, "Image generation failed"), 1000);
    }

    private static class ImageBytes {
        private final byte[] bytes;
        private final String mimeType;

        private ImageBytes(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }

    public static class ImageGenerationOutcome {
        private final boolean success;
        private final MessageAttachment attachment;
        private final String mediaReference;
        private final String provider;
        private final String error;
        private final Map<String, Object> mediaUsage;

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

        public static ImageGenerationOutcome ok(
                MessageAttachment attachment,
                String mediaReference,
                String provider,
                Map<String, Object> mediaUsage) {
            return new ImageGenerationOutcome(
                    true, attachment, mediaReference, provider, null, mediaUsage);
        }

        public static ImageGenerationOutcome fail(String error) {
            return new ImageGenerationOutcome(false, null, null, null, error, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public MessageAttachment getAttachment() {
            return attachment;
        }

        public String getMediaReference() {
            return mediaReference;
        }

        public String getProvider() {
            return provider;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> getMediaUsage() {
            return mediaUsage;
        }
    }
}
