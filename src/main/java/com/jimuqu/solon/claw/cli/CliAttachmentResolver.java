package com.jimuqu.solon.claw.cli;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 承载CLI附件Resolver相关状态和辅助逻辑。 */
public class CliAttachmentResolver {
    /** 记录附件解析中的可恢复降级事件，日志不得包含路径、token或文件内容。 */
    private static final Logger log = LoggerFactory.getLogger(CliAttachmentResolver.class);

    /** 最大附件字节的统一常量值。 */
    private static final long MAX_ATTACHMENT_BYTES = 32L * 1024L * 1024L;

    /** 最大附件路径列表的统一常量值。 */
    private static final int MAX_ATTACHMENT_PATHS = 8;

    /** QUOTEDtoken的统一常量值。 */
    private static final Pattern QUOTED_TOKEN = Pattern.compile("(['\"])([^'\"\\r\\n]{2,})\\1");

    /** 文件URItoken的统一常量值。 */
    private static final Pattern FILE_URI_TOKEN = Pattern.compile("(?i)(?<!\\S)file:/[^\\s'\"<>]+");

    /** Windows路径token的统一常量值。 */
    private static final Pattern WINDOWS_PATH_TOKEN =
            Pattern.compile("(?<![\\p{L}\\p{N}_./\\\\-])(?:[A-Za-z]:[/\\\\][^\\s'\"<>|]+)");

    /** POSIX路径token的统一常量值。 */
    private static final Pattern POSIX_PATH_TOKEN =
            Pattern.compile(
                    "(?<![A-Za-z]:)(?<![\\p{L}\\p{N}_./\\\\-])(?:~?/[A-Za-z0-9._+@%=-][^\\s'\"<>|]*)");

    /** ABSOLUTE路径IN消息的统一常量值。 */
    private static final Pattern ABSOLUTE_PATH_IN_MESSAGE =
            Pattern.compile("(?:(?<=\\s)|(?<=^)|(?<==)|(?<=:))(/[A-Za-z0-9._+@%-][^\\s'\"<>|;,]*)");

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /**
     * 创建Cli附件Resolver实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public CliAttachmentResolver(
            AppConfig appConfig, AttachmentCacheService attachmentCacheService) {
        this(
                attachmentCacheService == null
                        ? new AttachmentCacheService(appConfig)
                        : attachmentCacheService,
                new SecurityPolicyService(appConfig));
    }

    /**
     * 创建Cli附件Resolver实例，并注入运行所需依赖。
     *
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public CliAttachmentResolver(
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        this.attachmentCacheService = attachmentCacheService;
        this.securityPolicyService = securityPolicyService;
    }

    /**
     * 构建当前策略配置摘要。
     *
     * @return 返回策略Summary结果。
     */
    public static Map<String, Object> policySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("pastedLocalPathDetection", Boolean.TRUE);
        summary.put("fileUriDetection", Boolean.TRUE);
        summary.put("fileUriPercentDecoded", Boolean.TRUE);
        summary.put("windowsPathDetection", Boolean.TRUE);
        summary.put("windowsPathPreviewCrossPlatform", Boolean.TRUE);
        summary.put("windowsDrivePathNotDuplicatedAsPosix", Boolean.TRUE);
        summary.put("posixPathDetection", Boolean.TRUE);
        summary.put("tildeHomeExpansion", Boolean.TRUE);
        summary.put("canonicalPathResolvedBeforePolicy", Boolean.TRUE);
        summary.put("duplicatePathDeduplicated", Boolean.TRUE);
        summary.put("pathPolicyCheckedBeforeCache", Boolean.TRUE);
        summary.put("cacheWriteAfterPolicyOnly", Boolean.TRUE);
        summary.put("credentialPathBlocked", Boolean.TRUE);
        summary.put("blockedPreviewRedacted", Boolean.TRUE);
        summary.put("missingPreviewRedacted", Boolean.TRUE);
        summary.put("resolvedDisplayNameRedacted", Boolean.TRUE);
        summary.put("rawPathHiddenInPrompt", Boolean.TRUE);
        summary.put("maxAttachmentPaths", Integer.valueOf(MAX_ATTACHMENT_PATHS));
        summary.put("maxAttachmentBytes", Long.valueOf(MAX_ATTACHMENT_BYTES));
        summary.put(
                "description",
                "CLI/TUI pasted local paths are converted to cached attachments only after path safety checks; resolved attachment labels, blocked previews, and missing previews are secret-redacted.");
        return summary;
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param input 输入参数。
     * @return 返回resolve结果。
     */
    public ResolvedInput resolve(String input) {
        String text = StrUtil.nullToEmpty(input);
        List<Candidate> candidates = findCandidates(text);
        if (candidates.isEmpty()) {
            return new ResolvedInput(text, java.util.Collections.<MessageAttachment>emptyList());
        }

        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        String resolvedText = text;
        for (Candidate candidate : candidates) {
            if (attachments.size() >= MAX_ATTACHMENT_PATHS) {
                break;
            }
            File file = resolveCandidate(candidate.path);
            if (file == null || !file.isFile()) {
                continue;
            }
            SecurityPolicyService.FileVerdict verdict =
                    securityPolicyService.checkPath(file.getAbsolutePath(), false);
            if (!verdict.isAllowed()) {
                throw new IllegalArgumentException(
                        "附件路径被安全策略阻断：" + safeMessage(verdict.getMessage()));
            }
            if (file.length() > MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("附件文件过大：" + file.getName());
            }
            MessageAttachment attachment =
                    attachmentCacheService.cacheBytes(
                            PlatformType.MEMORY,
                            null,
                            file.getName(),
                            AttachmentCacheService.normalizeMimeType(file, null, file.getName()),
                            false,
                            null,
                            FileUtil.readBytes(file));
            attachments.add(attachment);
            resolvedText =
                    replaceToken(
                            resolvedText,
                            candidate.originalToken,
                            "[附件: " + safeName(attachment.getOriginalName()) + "]");
        }

        if (attachments.isEmpty()) {
            return new ResolvedInput(text, java.util.Collections.<MessageAttachment>emptyList());
        }
        return new ResolvedInput(resolvedText, attachments);
    }

    /**
     * 渲染预览。
     *
     * @param input 输入参数。
     * @return 返回render Preview结果。
     */
    public String renderPreview(String input) {
        List<AttachmentPreview> previews = preview(input);
        if (previews.isEmpty()) {
            return "未识别到可预检的本地附件路径。";
        }
        StringBuilder buffer = new StringBuilder("附件预检：");
        for (int i = 0; i < previews.size(); i++) {
            AttachmentPreview preview = previews.get(i);
            buffer.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(preview.getStatus())
                    .append(" name=")
                    .append(preview.getName())
                    .append(" kind=")
                    .append(preview.getKind())
                    .append(" mime=")
                    .append(preview.getMimeType())
                    .append(" size=")
                    .append(preview.getSizeText());
            if (StrUtil.isNotBlank(preview.getMessage())) {
                buffer.append(" - ").append(preview.getMessage());
            }
        }
        return buffer.toString();
    }

    /**
     * 执行预览相关逻辑。
     *
     * @param input 输入参数。
     * @return 返回preview结果。
     */
    public List<AttachmentPreview> preview(String input) {
        String text = StrUtil.nullToEmpty(input);
        List<Candidate> candidates = findCandidates(text);
        if (candidates.isEmpty()) {
            return java.util.Collections.<AttachmentPreview>emptyList();
        }
        List<AttachmentPreview> previews = new ArrayList<AttachmentPreview>();
        for (Candidate candidate : candidates) {
            if (previews.size() >= MAX_ATTACHMENT_PATHS) {
                break;
            }
            File file = resolveCandidate(candidate.path);
            if (file == null || !file.isFile()) {
                previews.add(AttachmentPreview.missing(displayName(candidate.path)));
                continue;
            }
            String name = file.getName();
            SecurityPolicyService.FileVerdict verdict =
                    securityPolicyService.checkPath(file.getAbsolutePath(), false);
            if (!verdict.isAllowed()) {
                previews.add(AttachmentPreview.blocked(name, verdict.getMessage()));
                continue;
            }
            if (file.length() > MAX_ATTACHMENT_BYTES) {
                previews.add(AttachmentPreview.blocked(name, "附件文件过大"));
                continue;
            }
            String mimeType = AttachmentCacheService.normalizeMimeType(file, null, name);
            previews.add(
                    AttachmentPreview.allowed(
                            name,
                            AttachmentCacheService.normalizeKind(null, name, mimeType),
                            mimeType,
                            file.length()));
        }
        return previews;
    }

    /**
     * 查找Candidates。
     *
     * @param input 输入参数。
     * @return 返回Candidates结果。
     */
    private List<Candidate> findCandidates(String input) {
        Set<String> seen = new LinkedHashSet<String>();
        List<Candidate> candidates = new ArrayList<Candidate>();
        addMatches(candidates, QUOTED_TOKEN.matcher(input), 2);
        addMatches(candidates, FILE_URI_TOKEN.matcher(input), 0);
        addMatches(candidates, WINDOWS_PATH_TOKEN.matcher(input), 0);
        addMatches(candidates, POSIX_PATH_TOKEN.matcher(input), 0);
        List<Candidate> unique = new ArrayList<Candidate>();
        for (Candidate candidate : candidates) {
            String key = candidate.path.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    /**
     * 追加Matches。
     *
     * @param candidates candidates标识或键值。
     * @param matcher matcher 参数。
     * @param group group 参数。
     */
    private void addMatches(List<Candidate> candidates, Matcher matcher, int group) {
        while (matcher.find() && candidates.size() < MAX_ATTACHMENT_PATHS) {
            String value = group <= 0 ? matcher.group() : matcher.group(group);
            value = stripTrailingPunctuation(StrUtil.nullToEmpty(value).trim());
            if (looksLikeLocalFileToken(value)) {
                candidates.add(new Candidate(matcher.group(), value));
            }
        }
    }

    /**
     * 解析Candidate。
     *
     * @param candidate candidate标识或键值。
     * @return 返回解析后的Candidate。
     */
    private File resolveCandidate(String candidate) {
        String value = stripWrappingQuotes(StrUtil.nullToEmpty(candidate).trim());
        if (value.length() == 0) {
            return null;
        }
        try {
            if (value.toLowerCase(Locale.ROOT).startsWith("file:")) {
                return new File(new URI(value)).getCanonicalFile();
            }
        } catch (Exception e) {
            logRecoverableFailure("file_uri_canonicalize", e);
            try {
                String withoutScheme = value.substring("file:".length());
                return FileUtil.file(URLDecoder.decode(withoutScheme, "UTF-8")).getCanonicalFile();
            } catch (Exception fallbackError) {
                logRecoverableFailure("file_uri_decode_fallback", fallbackError);
                return null;
            }
        }
        try {
            String expanded = expandUserHome(value);
            return FileUtil.file(expanded).getCanonicalFile();
        } catch (Exception e) {
            logRecoverableFailure("local_path_canonicalize", e);
            return null;
        }
    }

    /**
     * 判断是否具有本地文件token特征。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回looks Like本地文件token结果。
     */
    private boolean looksLikeLocalFileToken(String value) {
        String text = stripWrappingQuotes(value);
        if (text.length() == 0) {
            return false;
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("file:")) {
            return true;
        }
        if (Pattern.compile("^[A-Za-z]:[/\\\\].+").matcher(text).matches()) {
            return true;
        }
        return text.startsWith("/") || text.startsWith("~/");
    }

    /**
     * 执行expand用户主渠道相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回expand用户主渠道结果。
     */
    private String expandUserHome(String value) {
        String home = System.getProperty("user.home");
        if (StrUtil.isBlank(home)) {
            return value;
        }
        if ("~".equals(value)) {
            return home;
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return home + value.substring(1);
        }
        return value;
    }

    /**
     * 剥离WrappingQuotes。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Wrapping Quotes结果。
     */
    private String stripWrappingQuotes(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    /**
     * 执行replacetoken相关逻辑。
     *
     * @param text 待处理文本。
     * @param token token 参数。
     * @param replacement replacement 参数。
     * @return 返回replace token结果。
     */
    private String replaceToken(String text, String token, String replacement) {
        if (StrUtil.isBlank(token)) {
            return text;
        }
        String updated = text.replace(token, replacement);
        if (!updated.equals(text)) {
            return updated;
        }
        return text.replace(stripWrappingQuotes(token), replacement);
    }

    /**
     * 剥离TrailingPunctuation。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Trailing Punctuation结果。
     */
    private String stripTrailingPunctuation(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        while (text.length() > 0) {
            char ch = text.charAt(text.length() - 1);
            if (ch == '.' || ch == ',' || ch == ';' || ch == ')' || ch == ']' || ch == '}') {
                text = text.substring(0, text.length() - 1).trim();
                continue;
            }
            break;
        }
        return text;
    }

    /**
     * 执行展示名称相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回展示名称结果。
     */
    private String displayName(String path) {
        String value = stripWrappingQuotes(StrUtil.nullToEmpty(path).trim());
        if (StrUtil.isBlank(value)) {
            return "-";
        }
        try {
            if (value.toLowerCase(Locale.ROOT).startsWith("file:")) {
                return new File(new URI(value)).getName();
            }
        } catch (Exception e) {
            logRecoverableFailure("file_uri_display_name", e);
        }
        if (Pattern.compile("^[A-Za-z]:[/\\\\].+").matcher(value).matches()) {
            String normalized = value.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            return slash >= 0 ? normalized.substring(slash + 1) : normalized;
        }
        return FileUtil.file(value).getName();
    }

    /**
     * 生成安全展示用的消息。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe消息结果。
     */
    private static String safeMessage(String value) {
        String redacted =
                SecretRedactor.redactSensitivePaths(
                        SecretRedactor.redact(StrUtil.nullToEmpty(value), 1000));
        redacted = ABSOLUTE_PATH_IN_MESSAGE.matcher(redacted).replaceAll("[REDACTED_PATH]");
        redacted = WINDOWS_PATH_TOKEN.matcher(redacted).replaceAll("[REDACTED_PATH]");
        return POSIX_PATH_TOKEN.matcher(redacted).replaceAll("[REDACTED_PATH]");
    }

    /**
     * 生成安全展示用的名称。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe名称结果。
     */
    private static String safeName(String value) {
        return SecretRedactor.redact(StrUtil.blankToDefault(value, "-"), 400);
    }

    /**
     * 记录附件解析中的可恢复异常，仅输出动作名和异常类型，避免泄露路径、token或附件内容。
     *
     * @param action 降级动作名称。
     * @param error 触发降级的异常。
     */
    private static void logRecoverableFailure(String action, Exception error) {
        log.debug("CLI附件解析降级：action={} error={}", action, exceptionType(error));
    }

    /**
     * 提取异常类型摘要，避免记录异常消息和堆栈中的敏感上下文。
     *
     * @param error 待摘要的异常。
     * @return 返回异常类型名称。
     */
    private static String exceptionType(Exception error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    /** 承载Candidate相关状态和辅助逻辑。 */
    private static class Candidate {
        /** 记录Candidate中的originaltoken。 */
        private final String originalToken;

        /** 记录Candidate中的路径。 */
        private final String path;

        /**
         * 创建Candidate实例，并注入运行所需依赖。
         *
         * @param originalToken originaltoken参数。
         * @param path 文件或目录路径。
         */
        private Candidate(String originalToken, String path) {
            this.originalToken = StrUtil.nullToEmpty(originalToken).trim();
            this.path = StrUtil.nullToEmpty(path).trim();
        }
    }

    /** 承载Resolved输入相关状态和辅助逻辑。 */
    public static class ResolvedInput {
        /** 记录Resolved输入中的文本。 */
        private final String text;

        /** 保存附件集合，维持调用顺序或去重语义。 */
        private final List<MessageAttachment> attachments;

        /**
         * 创建Resolved输入实例，并注入运行所需依赖。
         *
         * @param text 待处理文本。
         * @param attachments attachments 参数。
         */
        public ResolvedInput(String text, List<MessageAttachment> attachments) {
            this.text = StrUtil.nullToEmpty(text);
            this.attachments =
                    attachments == null
                            ? java.util.Collections.<MessageAttachment>emptyList()
                            : new ArrayList<MessageAttachment>(attachments);
        }

        /**
         * 读取Text。
         *
         * @return 返回读取到的Text。
         */
        public String getText() {
            return text;
        }

        /**
         * 读取附件。
         *
         * @return 返回读取到的附件。
         */
        public List<MessageAttachment> getAttachments() {
            return new ArrayList<MessageAttachment>(attachments);
        }
    }

    /** 承载附件预览相关状态和辅助逻辑。 */
    public static class AttachmentPreview {
        /** 记录附件预览中的状态。 */
        private final String status;

        /** 记录附件预览中的名称。 */
        private final String name;

        /** 记录附件预览中的kind。 */
        private final String kind;

        /** 记录附件预览中的MIME 类型。 */
        private final String mimeType;

        /** 记录附件预览中的大小字节。 */
        private final long sizeBytes;

        /** 记录附件预览中的消息。 */
        private final String message;

        /**
         * 创建附件Preview实例，并注入运行所需依赖。
         *
         * @param status 状态参数。
         * @param name 名称参数。
         * @param kind kind 参数。
         * @param mimeType MIME 类型参数。
         * @param sizeBytes size字节参数。
         * @param message 平台消息或错误消息。
         */
        private AttachmentPreview(
                String status,
                String name,
                String kind,
                String mimeType,
                long sizeBytes,
                String message) {
            this.status = status;
            this.name = safeName(name);
            this.kind = StrUtil.blankToDefault(kind, "-");
            this.mimeType = StrUtil.blankToDefault(mimeType, "-");
            this.sizeBytes = sizeBytes;
            this.message = safeMessage(message);
        }

        /**
         * 执行allowed相关逻辑。
         *
         * @param name 名称参数。
         * @param kind kind 参数。
         * @param mimeType MIME 类型参数。
         * @param sizeBytes size字节参数。
         * @return 返回allowed结果。
         */
        public static AttachmentPreview allowed(
                String name, String kind, String mimeType, long sizeBytes) {
            return new AttachmentPreview("allowed", name, kind, mimeType, sizeBytes, "");
        }

        /**
         * 执行阻断相关逻辑。
         *
         * @param name 名称参数。
         * @param message 平台消息或错误消息。
         * @return 返回blocked结果。
         */
        public static AttachmentPreview blocked(String name, String message) {
            return new AttachmentPreview("blocked", name, "-", "-", -1L, message);
        }

        /**
         * 执行missing相关逻辑。
         *
         * @param name 名称参数。
         * @return 返回missing结果。
         */
        public static AttachmentPreview missing(String name) {
            return new AttachmentPreview("missing", name, "-", "-", -1L, "文件不存在或不可读取");
        }

        /**
         * 读取状态。
         *
         * @return 返回读取到的状态。
         */
        public String getStatus() {
            return status;
        }

        /**
         * 读取名称。
         *
         * @return 返回读取到的名称。
         */
        public String getName() {
            return name;
        }

        /**
         * 读取Kind。
         *
         * @return 返回读取到的Kind。
         */
        public String getKind() {
            return kind;
        }

        /**
         * 读取Mime类型。
         *
         * @return 返回读取到的Mime类型。
         */
        public String getMimeType() {
            return mimeType;
        }

        /**
         * 读取大小Bytes。
         *
         * @return 返回读取到的大小Bytes。
         */
        public long getSizeBytes() {
            return sizeBytes;
        }

        /**
         * 读取大小Text。
         *
         * @return 返回读取到的大小Text。
         */
        public String getSizeText() {
            return sizeBytes < 0L ? "-" : String.valueOf(sizeBytes);
        }

        /**
         * 读取消息。
         *
         * @return 返回读取到的消息。
         */
        public String getMessage() {
            return message;
        }
    }
}
