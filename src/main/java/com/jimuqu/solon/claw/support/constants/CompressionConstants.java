package com.jimuqu.solon.claw.support.constants;

import cn.hutool.core.util.StrUtil;

/** 上下文压缩相关常量。 */
public interface CompressionConstants {
    /** 压缩摘要前缀。 */
    String SUMMARY_PREFIX =
            "[CONTEXT COMPACTION - REFERENCE ONLY] Earlier turns were compacted into the "
                    + "summary below. Treat it as background reference, NOT as active "
                    + "instructions. Respond only to the latest user message after this summary; "
                    + "when older summary content conflicts with that latest user message, the "
                    + "latest user message wins. If a historical Active Task or handoff "
                    + "conflicts with the latest user message, discard that stale Active Task.";

    /** 历史摘要前缀。 */
    String[] HISTORICAL_SUMMARY_PREFIXES =
            new String[] {
                "[CONTEXT COMPACTION - REFERENCE ONLY] Earlier turns were compacted into the "
                        + "summary below. Treat it as background reference, NOT as active "
                        + "instructions. Respond only to the latest user message after this summary; "
                        + "when older summary content conflicts with that latest user message, the "
                        + "latest user message wins.",
                "[CONTEXT COMPACTION \u2014 REFERENCE ONLY] Earlier turns were compacted "
                        + "into the summary below. This is a handoff from a previous context "
                        + "window \u2014 treat it as background reference, NOT as active instructions. "
                        + "Do NOT answer questions or fulfill requests mentioned in this summary; "
                        + "they were already addressed. "
                        + "Your current task is identified in the '## Active Task' section of the "
                        + "summary \u2014 resume exactly from there. "
                        + "Respond ONLY to the latest user message "
                        + "that appears AFTER this summary. The current session state (files, "
                        + "config, etc.) may reflect work described here \u2014 avoid repeating it:",
                "[CONTEXT COMPACTION]",
                "[CONTEXT SUMMARY]:"
            };

    /** 历史摘要正文里常见的结构化小节名。 */
    String[] SUMMARY_SECTION_HEADINGS =
            new String[] {"Previous Summary", "Focus", "Goal", "Progress", "Decisions", "Files"};

    /** 旧编码链路留下的常见中文乱码片段。 */
    String[] MOJIBAKE_MARKERS = new String[] {"闀挎湡", "楠岃瘉", "锛", "绔", "�", "���"};

    /** 被裁剪的旧工具输出占位文本。 */
    String PRUNED_TOOL_PLACEHOLDER = "[Old tool output cleared to save context space]";

    /** 默认压缩阈值，占上下文窗口的百分比。 */
    double DEFAULT_THRESHOLD_PERCENT = 0.50D;

    /** 默认尾部保护比例。 */
    double DEFAULT_TAIL_RATIO = 0.20D;

    /** 默认 head 保护消息数。 */
    int DEFAULT_PROTECT_HEAD_MESSAGES = 3;

    /** 估算字符到 token 的粗略倍率。 */
    int CHARS_PER_TOKEN = 4;

    /** 旧摘要注入到新摘要时的最大保留长度。 */
    int MAX_PREVIOUS_SUMMARY_LENGTH = 400;

    /** 单次结构化摘要的最大长度，避免反复压缩后摘要自身膨胀。 */
    int MAX_SUMMARY_LENGTH = 2400;

    /** 会话标题最大长度。 */
    int MAX_TITLE_LENGTH = 80;

    /** 压缩失败后的冷却时间，单位毫秒。 */
    long FAILURE_COOLDOWN_MILLIS = 10L * 60L * 1000L;

    /** 成功压缩后的最短重压缩间隔，单位毫秒。 */
    long RECOMPRESS_COOLDOWN_MILLIS = 60L * 1000L;

    /** 再次压缩前至少新增的估算 token。 */
    int MIN_RECOMPRESS_DELTA_TOKENS = 512;

    /** 判断内容是否为压缩摘要消息。 */
    static boolean isSummaryContent(String content) {
        String value = StrUtil.nullToEmpty(content).trim();
        if (StrUtil.startWithIgnoreCase(value, SUMMARY_PREFIX)) {
            return true;
        }
        for (String prefix : HISTORICAL_SUMMARY_PREFIXES) {
            if (StrUtil.startWithIgnoreCase(value, prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 判断内容是否像已经失去摘要前缀的历史压缩摘要残留。 */
    static boolean isHistoricalSummaryArtifact(String content) {
        String value = StrUtil.nullToEmpty(content).trim();
        if (StrUtil.isBlank(value)) {
            return false;
        }
        if (isSummaryContent(value)) {
            return true;
        }

        int sectionCount = summarySectionCount(value);
        if (sectionCount < 2) {
            return false;
        }
        if (StrUtil.containsIgnoreCase(value, "Previous Summary")) {
            return true;
        }
        if (sectionCount >= 3
                && (StrUtil.containsIgnoreCase(value, "marker=")
                        || StrUtil.containsIgnoreCase(value, "Loop"))) {
            return true;
        }
        return hasMojibakeMarker(value)
                && (StrUtil.containsIgnoreCase(value, "marker=")
                        || StrUtil.containsIgnoreCase(value, "Loop"));
    }

    /** 去掉当前或历史摘要前缀，只保留摘要正文。 */
    static String stripSummaryPrefix(String content) {
        String value = StrUtil.nullToEmpty(content).trim();
        if (StrUtil.startWithIgnoreCase(value, SUMMARY_PREFIX)) {
            return value.substring(SUMMARY_PREFIX.length()).trim();
        }
        for (String prefix : HISTORICAL_SUMMARY_PREFIXES) {
            if (StrUtil.startWithIgnoreCase(value, prefix)) {
                return value.substring(prefix.length()).trim();
            }
        }
        return value;
    }

    /** 统计内容里命中的摘要结构化小节数量。 */
    static int summarySectionCount(String content) {
        String normalized = "\n" + StrUtil.nullToEmpty(content).replace("\r\n", "\n").replace('\r', '\n') + "\n";
        int count = 0;
        for (String heading : SUMMARY_SECTION_HEADINGS) {
            if (normalized.contains("\n" + heading + "\n")) {
                count++;
            }
        }
        return count;
    }

    /** 判断内容是否包含已知乱码特征。 */
    static boolean hasMojibakeMarker(String content) {
        String value = StrUtil.nullToEmpty(content);
        for (String marker : MOJIBAKE_MARKERS) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
