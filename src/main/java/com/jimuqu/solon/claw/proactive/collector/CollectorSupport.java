package com.jimuqu.solon.claw.proactive.collector;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.List;
import java.util.Locale;

/**
 * 主动协作采集器共享辅助，集中各 collector 原本复制粘贴的文本规范化、关键词匹配、脱敏、回看窗口计算和异常类型提取逻辑。
 *
 * <p>所有方法均为无状态静态方法，行为以多数 collector 的原有实现为准，供 {@code proactive/collector} 下各采集器复用。
 */
public final class CollectorSupport {
    /** 一天对应的毫秒数，用于把配置中的回看天数转换为时间窗口。 */
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    /** 创建采集器共享辅助实例。 */
    private CollectorSupport() {}

    /**
     * 规范化文本用于状态与关键词匹配，返回小写且非 null 的文本。
     *
     * @param value 原始文本。
     * @return 返回小写且非 null 的文本。
     */
    public static String normalize(String value) {
        return StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /**
     * 判断文本是否包含任一关键词，英文统一按小写匹配，中文保持原文匹配。
     *
     * @param text 候选文本。
     * @param keywords 关键词列表。
     * @return 命中任一关键词返回 true。
     */
    public static boolean containsKeyword(String text, List<String> keywords) {
        String value = normalize(text);
        if (StrUtil.isBlank(value) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StrUtil.isNotBlank(keyword)
                    && value.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对载荷和摘要文本做统一脱敏与长度限制。
     *
     * @param value 原始文本。
     * @param maxLength 最大保留长度。
     * @return 返回安全文本。
     */
    public static String safe(String value, int maxLength) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), maxLength);
    }

    /**
     * 追加一段非空文本到匹配缓冲区，非首段前以换行分隔。
     *
     * @param builder 文本缓冲区。
     * @param value 候选文本。
     */
    public static void appendText(StringBuilder builder, String value) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(value);
    }

    /**
     * 根据回看天数计算回看窗口起点，异常大天数会被夹紧，避免乘法溢出导致窗口反转。
     *
     * @param nowMillis 当前 tick 时间。
     * @param lookbackDays 配置的回看天数。
     * @return 返回可用于比较或仓储查询的起始毫秒时间戳。
     */
    public static long lookbackCutoffMillis(long nowMillis, int lookbackDays) {
        long safeDays = Math.max(1L, Math.min((long) lookbackDays, 3650L));
        long windowMillis = safeDays * DAY_MILLIS;
        return nowMillis < windowMillis ? 0L : nowMillis - windowMillis;
    }

    /**
     * 提取异常类型；如果异常链包含中断异常，恢复线程中断标记。
     *
     * @param error 原始异常。
     * @return 异常类名；异常为 null 时返回 "UnknownException"。
     */
    public static String exceptionType(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            current = current.getCause();
        }
        return error == null ? "UnknownException" : error.getClass().getSimpleName();
    }
}
