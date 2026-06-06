package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 附件缓存服务。 */
public class AttachmentCacheService {
    /** 最大缓存字节的统一常量值。 */
    private static final long MAX_CACHE_BYTES = 32L * 1024L * 1024L;

    /** REDACTEDtoken文件PART的统一常量值。 */
    private static final Pattern REDACTED_TOKEN_FILE_PART =
            Pattern.compile(
                    "(?i)(?:ghp_|github_pat_|sk-|sk_|sk_live_|sk_test_|xox[baprs]-|hf_|npm_|pypi-|gsk_|tvly-|exa_|brv_)?\\*\\*\\*");

    /** 记录附件缓存中的运行时主渠道。 */
    private final File runtimeHome;

    /** 记录附件缓存中的缓存根用户。 */
    private final File cacheRoot;

    /** 媒体引用PREFIX的统一常量值。 */
    private static final String MEDIA_REFERENCE_PREFIX = "media://";

    /**
     * 创建附件缓存服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public AttachmentCacheService(AppConfig appConfig) {
        String runtimeHomeValue = null;
        String cacheDirValue = null;
        if (appConfig != null && appConfig.getRuntime() != null) {
            runtimeHomeValue = appConfig.getRuntime().getHome();
            cacheDirValue = appConfig.getRuntime().getCacheDir();
        }
        this.runtimeHome =
                FileUtil.file(
                                StrUtil.blankToDefault(
                                        runtimeHomeValue, RuntimePathConstants.RUNTIME_HOME))
                        .getAbsoluteFile();
        File cacheDir =
                FileUtil.file(
                                StrUtil.blankToDefault(
                                        cacheDirValue,
                                        new File(runtimeHome, RuntimePathConstants.CACHE_DIR_NAME)
                                                .getPath()))
                        .getAbsoluteFile();
        this.cacheRoot = new File(cacheDir, "media");
    }

    /**
     * 构建当前策略配置摘要。
     *
     * @return 返回策略Summary结果。
     */
    public Map<String, Object> policySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("mediaReferencePrefix", MEDIA_REFERENCE_PREFIX);
        summary.put("maxCacheBytes", Long.valueOf(MAX_CACHE_BYTES));
        summary.put("cacheBytesSizeChecked", Boolean.TRUE);
        summary.put("safeOriginalNameSanitized", Boolean.TRUE);
        summary.put("safeOriginalNameSecretRedacted", Boolean.TRUE);
        summary.put("mimeSniffingEnabled", Boolean.TRUE);
        summary.put("kindNormalized", Boolean.TRUE);
        summary.put("fromLocalFileRequiresRuntimeCache", Boolean.TRUE);
        summary.put("fromMediaCacheRequiresMediaRoot", Boolean.TRUE);
        summary.put("mediaReferenceRequiresMediaRoot", Boolean.TRUE);
        summary.put("mediaReferenceTraversalBlocked", Boolean.TRUE);
        summary.put("generatedAttachmentSingleRuntimeLevelOnly", Boolean.TRUE);
        summary.put("generatedAttachmentExtensionAllowlist", Boolean.TRUE);
        summary.put("hostPathsNotReturnedInMediaReference", Boolean.TRUE);
        summary.put("mediaRoot", "runtime://cache/media");
        return summary;
    }

    /** 将原始字节落盘并返回附件模型。 */
    public MessageAttachment cacheBytes(
            PlatformType platform,
            String kind,
            String originalName,
            String mimeType,
            boolean fromQuote,
            String transcribedText,
            byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Attachment bytes are required");
        }
        if (data.length > MAX_CACHE_BYTES) {
            throw new IllegalStateException("Attachment too large: " + data.length);
        }

        File target = new File(platformDir(platform), prefixedName(originalName));
        FileUtil.mkParentDirs(target);
        FileUtil.writeBytes(data, target);

        String normalizedMimeType = normalizeMimeType(mimeType, originalName, data);
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind(normalizeKind(kind, originalName, normalizedMimeType));
        attachment.setLocalPath(target.getAbsolutePath());
        attachment.setOriginalName(safeName(originalName));
        attachment.setMimeType(normalizedMimeType);
        attachment.setSizeBytes(data.length);
        attachment.setFromQuote(fromQuote);
        attachment.setTranscribedText(StrUtil.nullToEmpty(transcribedText).trim());
        return attachment;
    }

    /** 由现有本地文件构造附件模型。 */
    public MessageAttachment fromLocalFile(
            PlatformType platform,
            File file,
            String explicitKind,
            boolean fromQuote,
            String transcribedText) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("Attachment file does not exist: " + safePath(file));
        }
        File canonical = FileUtil.file(file).getAbsoluteFile();
        FileUtil.file(platformDir(platform)).getAbsoluteFile();
        if (!isUnderCacheRoot(canonical)) {
            throw new IllegalArgumentException(
                    "Attachment file is outside runtime cache: " + safePath(file));
        }

        MessageAttachment attachment = new MessageAttachment();
        String normalizedMimeType = normalizeMimeType(canonical, null, canonical.getName());
        attachment.setKind(normalizeKind(explicitKind, canonical.getName(), normalizedMimeType));
        attachment.setLocalPath(canonical.getAbsolutePath());
        attachment.setOriginalName(safeName(canonical.getName()));
        attachment.setMimeType(normalizedMimeType);
        attachment.setSizeBytes(canonical.length());
        attachment.setFromQuote(fromQuote);
        attachment.setTranscribedText(StrUtil.nullToEmpty(transcribedText).trim());
        return attachment;
    }

    /**
     * 从输入转换媒体缓存文件。
     *
     * @param platform 平台参数。
     * @param file 文件或目录路径参数。
     * @param explicitKind explicitKind 参数。
     * @param fromQuote fromQuote 参数。
     * @param transcribedText transcribed文本参数。
     * @return 返回媒体缓存文件结果。
     */
    public MessageAttachment fromMediaCacheFile(
            PlatformType platform,
            File file,
            String explicitKind,
            boolean fromQuote,
            String transcribedText) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("Attachment file does not exist: " + safePath(file));
        }
        File canonical = FileUtil.file(file).getAbsoluteFile();
        if (!isUnderMediaRoot(canonical)) {
            throw new IllegalArgumentException(
                    "Attachment file is outside media cache: " + safePath(file));
        }
        return fromLocalFile(platform, canonical, explicitKind, fromQuote, transcribedText);
    }

    /**
     * 发送工具引用 runtime 根目录下生成的附件时，先导入媒体缓存再发送。
     *
     * <p>只允许 runtime 根目录的一层生成物，避免把 config/data/logs 等运行时内部文件直接作为附件外发。
     */
    public MessageAttachment fromLocalOrGeneratedFile(
            PlatformType platform,
            File file,
            String explicitKind,
            boolean fromQuote,
            String transcribedText) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("Attachment file does not exist: " + safePath(file));
        }
        File canonical = FileUtil.file(file).getAbsoluteFile();
        if (isUnderCacheRoot(canonical)) {
            return fromLocalFile(platform, canonical, explicitKind, fromQuote, transcribedText);
        }
        if (!isSafeRuntimeGeneratedFile(canonical)) {
            throw new IllegalArgumentException(
                    "Attachment file is outside runtime cache: " + safePath(file));
        }
        if (canonical.length() > MAX_CACHE_BYTES) {
            throw new IllegalStateException("Attachment too large: " + canonical.length());
        }

        File target = new File(platformDir(platform), prefixedName(canonical.getName()));
        FileUtil.mkParentDirs(target);
        try {
            Files.copy(canonical.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to cache generated attachment: " + safePath(canonical), e);
        }
        return fromLocalFile(platform, target, explicitKind, fromQuote, transcribedText);
    }

    /**
     * 执行平台目录相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回平台Dir结果。
     */
    public File platformDir(PlatformType platform) {
        return new File(
                cacheRoot,
                String.valueOf(platform == null ? PlatformType.MEMORY : platform)
                        .toLowerCase(Locale.ROOT));
    }

    /**
     * 执行媒体引用相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回媒体Reference结果。
     */
    public String mediaReference(MessageAttachment attachment) {
        if (attachment == null) {
            return "";
        }
        return mediaReference(new File(attachment.getLocalPath()));
    }

    /**
     * 执行媒体引用相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回媒体Reference结果。
     */
    public String mediaReference(File file) {
        File canonical = FileUtil.file(file).getAbsoluteFile();
        if (!isUnderMediaRoot(canonical)) {
            throw new IllegalArgumentException(
                    "Attachment file is outside media cache: " + safePath(file));
        }
        try {
            String relative =
                    cacheRoot
                            .getCanonicalFile()
                            .toPath()
                            .relativize(canonical.getCanonicalFile().toPath())
                            .toString()
                            .replace('\\', '/');
            return MEDIA_REFERENCE_PREFIX + relative;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid media cache path: " + safePath(file), e);
        }
    }

    /**
     * 解析媒体Reference。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回解析后的媒体Reference。
     */
    public File resolveMediaReference(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (!text.startsWith(MEDIA_REFERENCE_PREFIX)) {
            return FileUtil.file(text);
        }
        String relative = text.substring(MEDIA_REFERENCE_PREFIX.length()).replace('\\', '/');
        if (StrUtil.isBlank(relative) || relative.startsWith("/") || relative.contains("..")) {
            throw new IllegalArgumentException("Invalid media reference");
        }
        File file = new File(cacheRoot, relative);
        File canonical = FileUtil.file(file).getAbsoluteFile();
        if (!isUnderMediaRoot(canonical)) {
            throw new IllegalArgumentException(
                    "Attachment file is outside media cache: " + safeMediaReference(value));
        }
        return canonical;
    }

    /**
     * 规范化Kind。
     *
     * @param kind kind 参数。
     * @param name 名称参数。
     * @param mimeType MIME 类型参数。
     * @return 返回Kind结果。
     */
    public static String normalizeKind(String kind, String name, String mimeType) {
        String normalized = StrUtil.nullToEmpty(kind).trim().toLowerCase(Locale.ROOT);
        if ("image".equals(normalized)
                || "file".equals(normalized)
                || "video".equals(normalized)
                || "voice".equals(normalized)) {
            return normalized;
        }

        String mime = StrUtil.nullToEmpty(mimeType).toLowerCase(Locale.ROOT);
        String ext = extension(name);
        if (mime.startsWith("image/")
                || matches(
                        ext, ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".heic", ".heif")) {
            return "image";
        }
        if (mime.startsWith("video/")
                || matches(ext, ".mp4", ".mov", ".avi", ".mkv", ".webm", ".3gp", ".m4v")) {
            return "video";
        }
        if (mime.startsWith("audio/")
                || ".silk".equals(ext)
                || matches(ext, ".ogg", ".opus", ".mp3", ".wav", ".m4a", ".aac", ".flac", ".amr")) {
            return "voice";
        }
        return "file";
    }

    /**
     * 规范化Mime类型。
     *
     * @param mimeType MIME 类型参数。
     * @param name 名称参数。
     * @return 返回Mime类型结果。
     */
    public static String normalizeMimeType(String mimeType, String name) {
        return normalizeMimeType(mimeType, name, null);
    }

    /**
     * 规范化Mime类型。
     *
     * @param mimeType MIME 类型参数。
     * @param name 名称参数。
     * @param data 数据参数。
     * @return 返回Mime类型结果。
     */
    public static String normalizeMimeType(String mimeType, String name, byte[] data) {
        String sniffed = sniffImageMimeType(data);
        if (sniffed != null) {
            return sniffed;
        }

        String normalized = StrUtil.nullToEmpty(mimeType).trim();
        if (normalized.length() > 0) {
            return normalized;
        }

        String ext = extension(name);
        if (matches(ext, ".png")) {
            return "image/png";
        }
        if (matches(ext, ".jpg", ".jpeg")) {
            return "image/jpeg";
        }
        if (matches(ext, ".gif")) {
            return "image/gif";
        }
        if (matches(ext, ".webp")) {
            return "image/webp";
        }
        if (matches(ext, ".bmp")) {
            return "image/bmp";
        }
        if (matches(ext, ".heic")) {
            return "image/heic";
        }
        if (matches(ext, ".heif")) {
            return "image/heif";
        }
        if (matches(ext, ".mp4", ".m4v")) {
            return "video/mp4";
        }
        if (matches(ext, ".mov")) {
            return "video/quicktime";
        }
        if (matches(ext, ".avi")) {
            return "video/x-msvideo";
        }
        if (matches(ext, ".mkv")) {
            return "video/x-matroska";
        }
        if (matches(ext, ".webm")) {
            return "video/webm";
        }
        if (matches(ext, ".ogg", ".opus")) {
            return "audio/ogg";
        }
        if (matches(ext, ".mp3")) {
            return "audio/mpeg";
        }
        if (matches(ext, ".wav")) {
            return "audio/wav";
        }
        if (matches(ext, ".m4a")) {
            return "audio/mp4";
        }
        if (matches(ext, ".aac")) {
            return "audio/aac";
        }
        if (matches(ext, ".flac")) {
            return "audio/flac";
        }
        if (matches(ext, ".amr")) {
            return "audio/amr";
        }
        if (matches(ext, ".silk")) {
            return "audio/silk";
        }
        if (matches(ext, ".pdf")) {
            return "application/pdf";
        }
        if (matches(ext, ".txt")) {
            return "text/plain";
        }
        if (matches(ext, ".md")) {
            return "text/markdown";
        }
        if (matches(ext, ".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (matches(ext, ".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (matches(ext, ".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        return "application/octet-stream";
    }

    /**
     * 规范化Mime类型。
     *
     * @param file 文件或目录路径参数。
     * @param mimeType MIME 类型参数。
     * @param name 名称参数。
     * @return 返回Mime类型结果。
     */
    public static String normalizeMimeType(File file, String mimeType, String name) {
        return normalizeMimeType(mimeType, name, readImageHeader(file));
    }

    /**
     * 执行sniff图片MIME 类型相关逻辑。
     *
     * @param data 数据参数。
     * @return 返回sniff图片Mime类型结果。
     */
    public static String sniffImageMimeType(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (startsWith(data, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(data, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(data, ascii("GIF87a")) || startsWith(data, ascii("GIF89a"))) {
            return "image/gif";
        }
        if (data.length >= 12
                && bytesEqual(data, 0, ascii("RIFF"))
                && bytesEqual(data, 8, ascii("WEBP"))) {
            return "image/webp";
        }
        if (startsWith(data, ascii("BM"))) {
            return "image/bmp";
        }
        if (data.length >= 12 && bytesEqual(data, 4, ascii("ftyp"))) {
            String brand = new String(data, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("heic".equals(brand)
                    || "heix".equals(brand)
                    || "hevc".equals(brand)
                    || "hevx".equals(brand)
                    || "mif1".equals(brand)
                    || "msf1".equals(brand)
                    || "heim".equals(brand)
                    || "heis".equals(brand)) {
                return "image/heic";
            }
        }
        return null;
    }

    /**
     * 读取图片Header。
     *
     * @param file 文件或目录路径参数。
     * @return 返回读取到的图片Header。
     */
    private static byte[] readImageHeader(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        byte[] header = new byte[32];
        try {
            java.io.InputStream input = Files.newInputStream(file.toPath());
            try {
                int read = input.read(header);
                if (read <= 0) {
                    return null;
                }
                if (read == header.length) {
                    return header;
                }
                byte[] trimmed = new byte[read];
                System.arraycopy(header, 0, trimmed, 0, read);
                return trimmed;
            } finally {
                input.close();
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * 执行ascii相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回ascii结果。
     */
    private static byte[] ascii(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 执行startsWith相关逻辑。
     *
     * @param data 数据参数。
     * @param prefix prefix 参数。
     * @return 返回starts With结果。
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        return bytesEqual(data, 0, prefix);
    }

    /**
     * 执行字节Equal相关逻辑。
     *
     * @param data 数据参数。
     * @param offset 分页偏移量。
     * @param expected expected 参数。
     * @return 返回bytes Equal结果。
     */
    private static boolean bytesEqual(byte[] data, int offset, byte[] expected) {
        if (data == null
                || expected == null
                || offset < 0
                || data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 执行prefixed名称相关逻辑。
     *
     * @param originalName original名称参数。
     * @return 返回prefixed名称结果。
     */
    private String prefixedName(String originalName) {
        return UUID.randomUUID().toString().replace("-", "") + "_" + safeName(originalName);
    }

    /**
     * 生成安全展示用的名称。
     *
     * @param originalName original名称参数。
     * @return 返回safe名称结果。
     */
    private static String safeName(String originalName) {
        String value =
                StrUtil.blankToDefault(originalName, "attachment.bin")
                        .replace("\\", "_")
                        .replace("/", "_")
                        .replace(":", "_")
                        .replace("*", "_")
                        .replace("?", "_")
                        .replace("\"", "_")
                        .replace("<", "_")
                        .replace(">", "_")
                        .replace("|", "_");
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            cleaned.append(Character.isISOControl(ch) ? '_' : ch);
        }
        value = cleaned.toString().trim();
        if (value.length() > 120) {
            value = value.substring(0, 120);
        }
        if (value.length() == 0) {
            value = "attachment.bin";
        }
        value =
                REDACTED_TOKEN_FILE_PART
                        .matcher(SecretRedactor.redact(value, 120))
                        .replaceAll("redacted");
        value =
                value.replace("\\", "_")
                        .replace("/", "_")
                        .replace(":", "_")
                        .replace("*", "_")
                        .replace("?", "_")
                        .replace("\"", "_")
                        .replace("<", "_")
                        .replace(">", "_")
                        .replace("|", "_")
                        .trim();
        value = removeTraversalDots(value);
        if (value.length() > 120) {
            value = value.substring(0, 120);
        }
        return value.length() == 0 ? "attachment.bin" : value;
    }

    /**
     * 移除Traversal Dots。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Traversal Dots结果。
     */
    private static String removeTraversalDots(String value) {
        String result = StrUtil.nullToEmpty(value);
        while (result.contains("..")) {
            result = result.replace("..", "_");
        }
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        result = result.trim();
        return result.length() == 0 ? "attachment.bin" : result;
    }

    /**
     * 生成安全展示用的路径。
     *
     * @param file 文件或目录路径参数。
     * @return 返回safe路径。
     */
    private static String safePath(File file) {
        if (file == null) {
            return "[unknown]";
        }
        String name = file.getName();
        if (StrUtil.isBlank(name)) {
            name = file.getPath();
        }
        return safeName(SecretRedactor.redact(name, 400));
    }

    /**
     * 生成安全展示用的媒体引用。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe媒体Reference结果。
     */
    private static String safeMediaReference(String value) {
        String text = StrUtil.nullToEmpty(value).replace('\\', '/').trim();
        if (StrUtil.isBlank(text)) {
            return "";
        }
        if (text.startsWith(MEDIA_REFERENCE_PREFIX)) {
            String relative = text.substring(MEDIA_REFERENCE_PREFIX.length());
            int slash = relative.lastIndexOf('/');
            String name = slash >= 0 ? relative.substring(slash + 1) : relative;
            return MEDIA_REFERENCE_PREFIX + safeName(SecretRedactor.redact(name, 400));
        }
        return safePath(new File(text));
    }

    /**
     * 判断是否Under缓存根用户。
     *
     * @param file 文件或目录路径参数。
     * @return 如果Under缓存根用户满足条件则返回 true，否则返回 false。
     */
    private boolean isUnderCacheRoot(File file) {
        File runtimeCacheRoot =
                cacheRoot.getParentFile() == null ? cacheRoot : cacheRoot.getParentFile();
        return isUnder(file, runtimeCacheRoot);
    }

    /**
     * 判断是否Under媒体根用户。
     *
     * @param file 文件或目录路径参数。
     * @return 如果Under媒体根用户满足条件则返回 true，否则返回 false。
     */
    private boolean isUnderMediaRoot(File file) {
        return isUnder(file, cacheRoot);
    }

    /**
     * 判断是否Safe运行时Generated文件。
     *
     * @param file 文件或目录路径参数。
     * @return 如果Safe运行时Generated文件满足条件则返回 true，否则返回 false。
     */
    private boolean isSafeRuntimeGeneratedFile(File file) {
        if (!isUnder(file, runtimeHome)) {
            return false;
        }
        try {
            File parent = file.getCanonicalFile().getParentFile();
            if (parent == null || !parent.equals(runtimeHome.getCanonicalFile())) {
                return false;
            }
        } catch (Exception e) {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent == null || !parent.equals(runtimeHome.getAbsoluteFile())) {
                return false;
            }
        }
        String ext = extension(file.getName());
        return matches(
                ext, ".pdf", ".docx", ".xlsx", ".pptx", ".png", ".jpg", ".jpeg", ".gif", ".webp",
                ".bmp", ".heic", ".heif", ".mp4", ".mov", ".avi", ".mkv", ".webm", ".3gp", ".m4v",
                ".silk", ".ogg", ".opus", ".mp3", ".wav", ".m4a", ".aac", ".flac", ".amr");
    }

    /**
     * 判断是否Under。
     *
     * @param file 文件或目录路径参数。
     * @param root root 参数。
     * @return 如果Under满足条件则返回 true，否则返回 false。
     */
    private boolean isUnder(File file, File root) {
        try {
            return isUnderPath(file.getCanonicalFile(), root.getCanonicalFile());
        } catch (Exception ignored) {
        }
        return isUnderPath(file.getAbsoluteFile(), root.getAbsoluteFile());
    }

    /**
     * 判断是否Under路径。
     *
     * @param file 文件或目录路径参数。
     * @param root root 参数。
     * @return 如果Under路径满足条件则返回 true，否则返回 false。
     */
    private boolean isUnderPath(File file, File root) {
        String rootPath = root.toPath().toAbsolutePath().normalize().toString();
        String filePath = file.toPath().toAbsolutePath().normalize().toString();
        if (File.separatorChar == '\\') {
            rootPath = rootPath.toLowerCase(Locale.ROOT);
            filePath = filePath.toLowerCase(Locale.ROOT);
        }
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    /**
     * 执行扩展名相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回extension结果。
     */
    private static String extension(String name) {
        String value = StrUtil.nullToEmpty(name).trim().toLowerCase(Locale.ROOT);
        int index = value.lastIndexOf('.');
        if (index < 0) {
            return "";
        }
        return value.substring(index);
    }

    /**
     * 执行matches相关逻辑。
     *
     * @param ext ext 参数。
     * @param values 待规范化或校验的原始值集合。
     * @return 返回matches结果。
     */
    private static boolean matches(String ext, String... values) {
        for (String value : values) {
            if (value.equalsIgnoreCase(ext)) {
                return true;
            }
        }
        return false;
    }
}
