package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 仓库引用提取器，用于从记忆、会话和定时任务文本中识别显式仓库或本地工作区路径。 */
public class RepositoryReferenceExtractor {
    /** 支持的代码托管 URL，允许用户只写域名路径但会规范为 HTTPS URL。 */
    private static final Pattern HOSTED_REPOSITORY_URL =
            Pattern.compile(
                    "(?i)(?:https?://)?(?:github\\.com|gitee\\.com|gitlab\\.com)/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?(?:/[-/_A-Za-z0-9.]+)?");

    /** 支持的本地项目路径范围，限制在用户常用代码目录，避免扫描任意文件系统路径。 */
    private static final Pattern LOCAL_REPOSITORY_PATH =
            Pattern.compile(
                    "(?i)(?:/[A-Za-z0-9_. -]+)*/(?:code-projects|code-repositories)/[^\\s\"'<>),;]+|[A-Za-z]:\\\\(?:[^\\\\\\s\"'<>),;]+\\\\)*(?:code-projects|code-repositories)\\\\[^\\s\"'<>),;]+");

    /** 单条证据文本最大长度，避免把长消息完整带入观测载荷。 */
    private static final int EVIDENCE_MAX_LENGTH = 260;

    /**
     * 从文本中提取仓库引用。
     *
     * @param sourceType 文本来源类型，例如 memory、session 或 cron。
     * @param sourceRef 文本来源引用，例如记忆文件、会话 ID 或任务 ID。
     * @param text 原始文本。
     * @return 返回按出现顺序去重后的仓库引用列表。
     */
    public List<RepositoryReference> extract(String sourceType, String sourceRef, String text) {
        Map<String, RepositoryReference> result =
                new LinkedHashMap<String, RepositoryReference>();
        if (StrUtil.isBlank(text)) {
            return new ArrayList<RepositoryReference>();
        }
        extractUrls(sourceType, sourceRef, text, result);
        extractLocalPaths(sourceType, sourceRef, text, result);
        return new ArrayList<RepositoryReference>(result.values());
    }

    /**
     * 提取显式托管仓库 URL。
     *
     * @param sourceType 文本来源类型。
     * @param sourceRef 文本来源引用。
     * @param text 原始文本。
     * @param result 聚合后的引用结果。
     */
    private void extractUrls(
            String sourceType,
            String sourceRef,
            String text,
            Map<String, RepositoryReference> result) {
        Matcher matcher = HOSTED_REPOSITORY_URL.matcher(text);
        while (matcher.find()) {
            String ref = normalizeHostedUrl(matcher.group());
            if (StrUtil.isBlank(ref)) {
                continue;
            }
            putReference(sourceType, sourceRef, text, matcher.start(), matcher.end(), ref, result);
        }
    }

    /**
     * 提取允许范围内的本地仓库路径。
     *
     * @param sourceType 文本来源类型。
     * @param sourceRef 文本来源引用。
     * @param text 原始文本。
     * @param result 聚合后的引用结果。
     */
    private void extractLocalPaths(
            String sourceType,
            String sourceRef,
            String text,
            Map<String, RepositoryReference> result) {
        Matcher matcher = LOCAL_REPOSITORY_PATH.matcher(text);
        while (matcher.find()) {
            String ref = stripTrailingPunctuation(matcher.group());
            if (StrUtil.isBlank(ref)) {
                continue;
            }
            putReference(sourceType, sourceRef, text, matcher.start(), matcher.end(), ref, result);
        }
    }

    /**
     * 追加去重后的引用记录。
     *
     * @param sourceType 文本来源类型。
     * @param sourceRef 文本来源引用。
     * @param text 原始文本。
     * @param start 命中起点。
     * @param end 命中终点。
     * @param ref 规范化仓库引用。
     * @param result 聚合后的引用结果。
     */
    private void putReference(
            String sourceType,
            String sourceRef,
            String text,
            int start,
            int end,
            String ref,
            Map<String, RepositoryReference> result) {
        if (result.containsKey(ref)) {
            return;
        }
        RepositoryReference reference = new RepositoryReference();
        reference.setRef(ref);
        reference.setSourceType(StrUtil.blankToDefault(sourceType, "unknown"));
        reference.setSourceRef(StrUtil.blankToDefault(sourceRef, "unknown"));
        reference.setEvidence(extractEvidence(text, start, end));
        result.put(ref, reference);
    }

    /**
     * 规范化托管仓库 URL，仅保留仓库根路径，避免 release、issues 等页面造成重复引用。
     *
     * @param raw 原始 URL。
     * @return 返回规范化后的仓库 URL。
     */
    private String normalizeHostedUrl(String raw) {
        String value = stripTrailingPunctuation(StrUtil.nullToEmpty(raw));
        if (StrUtil.isBlank(value)) {
            return "";
        }
        if (!value.toLowerCase(Locale.ROOT).startsWith("http://")
                && !value.toLowerCase(Locale.ROOT).startsWith("https://")) {
            value = "https://" + value;
        }
        String prefix = value.substring(0, value.indexOf("://") + 3);
        String rest = value.substring(value.indexOf("://") + 3);
        String[] segments = rest.split("/");
        if (segments.length < 3) {
            return value;
        }
        return prefix + segments[0] + "/" + segments[1] + "/" + segments[2];
    }

    /**
     * 移除仓库引用末尾常见标点。
     *
     * @param value 原始引用。
     * @return 返回去除尾部标点后的引用。
     */
    private String stripTrailingPunctuation(String value) {
        String result = StrUtil.nullToEmpty(value).trim();
        while (result.endsWith(".")
                || result.endsWith("，")
                || result.endsWith("。")
                || result.endsWith("、")
                || result.endsWith(":")
                || result.endsWith("：")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 从原文中截取引用附近的短证据。
     *
     * @param text 原始文本。
     * @param start 命中起点。
     * @param end 命中终点。
     * @return 返回短证据文本。
     */
    private String extractEvidence(String text, int start, int end) {
        int left = Math.max(0, start - 80);
        int right = Math.min(text.length(), end + 80);
        String evidence = text.substring(left, right).replace('\n', ' ').replace('\r', ' ').trim();
        return StrUtil.maxLength(evidence, EVIDENCE_MAX_LENGTH);
    }

    /** 单个仓库引用及其来源证据。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class RepositoryReference {
        /** 规范化后的仓库引用，可能是 HTTPS URL 或允许范围内的本地绝对路径。 */
        private String ref;

        /** 引用来源类型，例如 memory、session 或 cron。 */
        private String sourceType;

        /** 引用来源标识，例如会话 ID、记忆分区或定时任务 ID。 */
        private String sourceRef;

        /** 命中引用附近的短证据文本。 */
        private String evidence;
    }
}
