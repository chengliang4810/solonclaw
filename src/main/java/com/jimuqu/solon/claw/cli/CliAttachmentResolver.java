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

/** Resolves pasted local file paths in CLI/TUI input into normal message attachments. */
public class CliAttachmentResolver {
    private static final long MAX_ATTACHMENT_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_ATTACHMENT_PATHS = 8;
    private static final Pattern QUOTED_TOKEN =
            Pattern.compile("(['\"])([^'\"\\r\\n]{2,})\\1");
    private static final Pattern FILE_URI_TOKEN =
            Pattern.compile("(?i)(?<!\\S)file://[^\\s'\"<>]+");
    private static final Pattern WINDOWS_PATH_TOKEN =
            Pattern.compile(
                    "(?<![\\p{L}\\p{N}_./\\\\-])(?:[A-Za-z]:[/\\\\][^\\s'\"<>|]+)");
    private static final Pattern POSIX_PATH_TOKEN =
            Pattern.compile(
                    "(?<![\\p{L}\\p{N}_./\\\\-])(?:~?/[A-Za-z0-9._+@%=-][^\\s'\"<>|]*)");
    private static final Pattern ABSOLUTE_PATH_IN_MESSAGE =
            Pattern.compile("(?:(?<=\\s)|(?<=^)|(?<==)|(?<=:))(/[A-Za-z0-9._+@%-][^\\s'\"<>|;,]*)");

    private final AttachmentCacheService attachmentCacheService;
    private final SecurityPolicyService securityPolicyService;

    public CliAttachmentResolver(AppConfig appConfig, AttachmentCacheService attachmentCacheService) {
        this(
                attachmentCacheService == null
                        ? new AttachmentCacheService(appConfig)
                        : attachmentCacheService,
                new SecurityPolicyService(appConfig));
    }

    public CliAttachmentResolver(
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        this.attachmentCacheService = attachmentCacheService;
        this.securityPolicyService = securityPolicyService;
    }

    public static Map<String, Object> policySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("pastedLocalPathDetection", Boolean.TRUE);
        summary.put("fileUriDetection", Boolean.TRUE);
        summary.put("fileUriPercentDecoded", Boolean.TRUE);
        summary.put("windowsPathDetection", Boolean.TRUE);
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
        summary.put("description", "CLI/TUI pasted local paths are converted to cached attachments only after path safety checks; resolved attachment labels, blocked previews, and missing previews are secret-redacted.");
        return summary;
    }

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

    private void addMatches(List<Candidate> candidates, Matcher matcher, int group) {
        while (matcher.find() && candidates.size() < MAX_ATTACHMENT_PATHS) {
            String value = group <= 0 ? matcher.group() : matcher.group(group);
            value = stripTrailingPunctuation(StrUtil.nullToEmpty(value).trim());
            if (looksLikeLocalFileToken(value)) {
                candidates.add(new Candidate(matcher.group(), value));
            }
        }
    }

    private File resolveCandidate(String candidate) {
        String value = stripWrappingQuotes(StrUtil.nullToEmpty(candidate).trim());
        if (value.length() == 0) {
            return null;
        }
        try {
            if (value.toLowerCase(Locale.ROOT).startsWith("file://")) {
                return new File(new URI(value)).getCanonicalFile();
            }
        } catch (Exception ignored) {
            try {
                String withoutScheme = value.substring("file://".length());
                return FileUtil.file(URLDecoder.decode(withoutScheme, "UTF-8")).getCanonicalFile();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
        try {
            String expanded = expandUserHome(value);
            return FileUtil.file(expanded).getCanonicalFile();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean looksLikeLocalFileToken(String value) {
        String text = stripWrappingQuotes(value);
        if (text.length() == 0) {
            return false;
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("file://")) {
            return true;
        }
        if (Pattern.compile("^[A-Za-z]:[/\\\\].+").matcher(text).matches()) {
            return true;
        }
        return text.startsWith("/") || text.startsWith("~/");
    }

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

    private String displayName(String path) {
        String value = stripWrappingQuotes(StrUtil.nullToEmpty(path).trim());
        if (StrUtil.isBlank(value)) {
            return "-";
        }
        try {
            if (value.toLowerCase(Locale.ROOT).startsWith("file://")) {
                return new File(new URI(value)).getName();
            }
        } catch (Exception ignored) {
        }
        return FileUtil.file(value).getName();
    }

    private static String safeMessage(String value) {
        String result = SecretRedactor.redact(StrUtil.nullToEmpty(value), 1000);
        // Redact any remaining file paths in the message (e.g. path=/some/absolute/path)
        result = ABSOLUTE_PATH_IN_MESSAGE.matcher(result).replaceAll("[REDACTED_PATH]");
        return result;
    }

    private static String safeName(String value) {
        return SecretRedactor.redact(StrUtil.blankToDefault(value, "-"), 400);
    }

    private static class Candidate {
        private final String originalToken;
        private final String path;

        private Candidate(String originalToken, String path) {
            this.originalToken = StrUtil.nullToEmpty(originalToken).trim();
            this.path = StrUtil.nullToEmpty(path).trim();
        }
    }

    public static class ResolvedInput {
        private final String text;
        private final List<MessageAttachment> attachments;

        public ResolvedInput(String text, List<MessageAttachment> attachments) {
            this.text = StrUtil.nullToEmpty(text);
            this.attachments =
                    attachments == null
                            ? java.util.Collections.<MessageAttachment>emptyList()
                            : new ArrayList<MessageAttachment>(attachments);
        }

        public String getText() {
            return text;
        }

        public List<MessageAttachment> getAttachments() {
            return new ArrayList<MessageAttachment>(attachments);
        }
    }

    public static class AttachmentPreview {
        private final String status;
        private final String name;
        private final String kind;
        private final String mimeType;
        private final long sizeBytes;
        private final String message;

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

        public static AttachmentPreview allowed(
                String name, String kind, String mimeType, long sizeBytes) {
            return new AttachmentPreview("allowed", name, kind, mimeType, sizeBytes, "");
        }

        public static AttachmentPreview blocked(String name, String message) {
            return new AttachmentPreview("blocked", name, "-", "-", -1L, message);
        }

        public static AttachmentPreview missing(String name) {
            return new AttachmentPreview("missing", name, "-", "-", -1L, "文件不存在或不可读取");
        }

        public String getStatus() {
            return status;
        }

        public String getName() {
            return name;
        }

        public String getKind() {
            return kind;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public String getSizeText() {
            return sizeBytes < 0L ? "-" : String.valueOf(sizeBytes);
        }

        public String getMessage() {
            return message;
        }
    }
}
