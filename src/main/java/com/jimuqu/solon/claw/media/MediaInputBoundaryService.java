package com.jimuqu.solon.claw.media;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 多模态图片输入安全边界。 */
public class MediaInputBoundaryService {
    private static final Logger log = LoggerFactory.getLogger(MediaInputBoundaryService.class);
    public static final int MAX_IMAGE_ATTACHMENTS = 3;
    public static final long MAX_IMAGE_BYTES = 5L * 1024L * 1024L;
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
                if (data.length > MAX_IMAGE_BYTES) {
                    return null;
                }
                return ImageBlock.ofBase64(cleanBase64(attachment.getData()), mimeType);
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
                    if (dataPart == null || Base64.decode(dataPart).length > MAX_IMAGE_BYTES) {
                        return null;
                    }
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
            if (file.length() > MAX_IMAGE_BYTES) {
                return null;
            }
            byte[] data = Files.readAllBytes(file.toPath());
            return ImageBlock.ofBase64(data, mimeType);
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
