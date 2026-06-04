package com.jimuqu.solon.claw.media;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 多模态图片输入安全边界。 */
public class MediaInputBoundaryService {
    private static final Logger log = LoggerFactory.getLogger(MediaInputBoundaryService.class);
    public static final int MAX_IMAGE_ATTACHMENTS = 3;
    public static final long EMBED_TARGET_BYTES = 4L * 1024L * 1024L;
    public static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
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

    private final File cacheRoot;

    public MediaInputBoundaryService(AppConfig appConfig) {
        String cacheDir = null;
        if (appConfig != null && appConfig.getRuntime() != null) {
            cacheDir = appConfig.getRuntime().getCacheDir();
        }
        this.cacheRoot =
                FileUtil.file(StrUtil.blankToDefault(cacheDir, "runtime/cache")).getAbsoluteFile();
    }

    public ImageBlock toImageBlock(MessageAttachment attachment) {
        if (!isImageAttachment(attachment)) {
            return null;
        }
        String mimeType = normalizeMime(attachment.getMimeType());
        if (!IMAGE_MIME_ALLOWLIST.contains(mimeType)) {
            return null;
        }
        try {
            if (StrUtil.isNotBlank(attachment.getData())) {
                byte[] data = Base64.decode(cleanBase64(attachment.getData()));
                ImagePayload payload = imagePayload(data, mimeType);
                if (payload == null) {
                    return null;
                }
                return ImageBlock.ofBase64(payload.base64, payload.mimeType);
            }
            if (StrUtil.isNotBlank(attachment.getUrl())) {
                String url = attachment.getUrl().trim();
                URI uri = URI.create(url);
                String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
                if (!"http".equals(scheme) && !"https".equals(scheme) && !"data".equals(scheme)) {
                    return null;
                }
                if ("data".equals(scheme)) {
                    String dataPart = dataUriPayload(url);
                    ImagePayload payload =
                            dataPart == null
                                    ? null
                                    : imagePayload(Base64.decode(dataPart), mimeType);
                    if (payload == null) {
                        return null;
                    }
                    return ImageBlock.ofBase64(payload.base64, payload.mimeType);
                }
                return ImageBlock.ofUrl(url, mimeType);
            }
            if (StrUtil.isBlank(attachment.getLocalPath())) {
                return null;
            }
            File file = FileUtil.file(attachment.getLocalPath()).getAbsoluteFile();
            if (!isUnderCacheRoot(file)) {
                return null;
            }
            if (!file.isFile()) {
                log.warn(
                        "Failed to attach image block: path={}, error={}",
                        SecretRedactor.redact(attachment.getLocalPath(), 400),
                        "file_missing");
                return null;
            }
            byte[] data = Files.readAllBytes(file.toPath());
            ImagePayload payload = imagePayload(data, mimeType);
            if (payload == null) {
                return null;
            }
            return ImageBlock.ofBase64(payload.base64, payload.mimeType);
        } catch (Exception e) {
            log.warn(
                    "Failed to attach image block: path={}, error={}",
                    SecretRedactor.redact(attachment.getLocalPath(), 400),
                    safeError(e));
            return null;
        }
    }

    public int maxImageAttachments() {
        return MAX_IMAGE_ATTACHMENTS;
    }

    private boolean isImageAttachment(MessageAttachment attachment) {
        if (attachment == null) {
            return false;
        }
        String kind = StrUtil.nullToEmpty(attachment.getKind()).trim().toLowerCase(Locale.ROOT);
        String mime = StrUtil.nullToEmpty(attachment.getMimeType()).trim().toLowerCase(Locale.ROOT);
        return "image".equals(kind) && mime.startsWith("image/");
    }

    private ImagePayload imagePayload(byte[] data, String mimeType) {
        if (data == null || data.length == 0 || data.length > MAX_IMAGE_BYTES) {
            return null;
        }
        String base64 = java.util.Base64.getEncoder().encodeToString(data);
        if (base64.length() <= EMBED_TARGET_BYTES) {
            return new ImagePayload(base64, mimeType);
        }
        return shrinkImagePayload(data, mimeType);
    }

    private ImagePayload shrinkImagePayload(byte[] data, String mimeType) {
        try {
            BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (source == null) {
                return null;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            for (int attempt = 0; attempt < 8; attempt++) {
                if (attempt > 0) {
                    double scale = Math.pow(0.72D, attempt);
                    width = Math.max(64, (int) Math.round(source.getWidth() * scale));
                    height = Math.max(64, (int) Math.round(source.getHeight() * scale));
                }
                BufferedImage scaled = scaleImage(source, width, height);
                float[] qualities = new float[] {0.85F, 0.7F, 0.55F, 0.4F};
                for (float quality : qualities) {
                    byte[] encoded = encodeJpeg(scaled, quality);
                    String base64 = java.util.Base64.getEncoder().encodeToString(encoded);
                    if (base64.length() <= EMBED_TARGET_BYTES) {
                        return new ImagePayload(base64, "image/jpeg");
                    }
                }
                if (width == 64 && height == 64) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to shrink image block: error={}", safeError(e));
        }
        return null;
    }

    private BufferedImage scaleImage(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output);
        try {
            writer.setOutput(imageOutput);
            JPEGImageWriteParam params = new JPEGImageWriteParam(Locale.ROOT);
            params.setCompressionMode(JPEGImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
            imageOutput.close();
        }
    }

    private static final class ImagePayload {
        private final String base64;
        private final String mimeType;

        private ImagePayload(String base64, String mimeType) {
            this.base64 = base64;
            this.mimeType = mimeType;
        }
    }

    private String normalizeMime(String mimeType) {
        String value = StrUtil.nullToEmpty(mimeType).trim().toLowerCase(Locale.ROOT);
        return StrUtil.blankToDefault(value, "image/png");
    }

    private String cleanBase64(String data) {
        String value = StrUtil.nullToEmpty(data).trim();
        int comma = value.indexOf(',');
        return comma >= 0 ? value.substring(comma + 1).trim() : value;
    }

    private String dataUriPayload(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        int comma = text.indexOf(',');
        if (comma < 0 || !text.substring(0, comma).toLowerCase(Locale.ROOT).contains(";base64")) {
            return null;
        }
        return text.substring(comma + 1).trim();
    }

    private boolean isUnderCacheRoot(File file) {
        try {
            return isUnderPath(file.getCanonicalFile(), cacheRoot.getCanonicalFile());
        } catch (Exception ignored) {
        }
        return isUnderPath(file.getAbsoluteFile(), cacheRoot.getAbsoluteFile());
    }

    private boolean isUnderPath(File file, File root) {
        String rootPath = root.toPath().toAbsolutePath().normalize().toString();
        String filePath = file.toPath().toAbsolutePath().normalize().toString();
        if (File.separatorChar == '\\') {
            rootPath = rootPath.toLowerCase(Locale.ROOT);
            filePath = filePath.toLowerCase(Locale.ROOT);
        }
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private String safeError(Exception e) {
        return SecretRedactor.redact(e == null ? "" : e.getMessage(), 400);
    }
}
