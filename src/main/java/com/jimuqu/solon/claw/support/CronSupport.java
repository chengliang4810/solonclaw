package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 轻量级 cron 计算辅助类，覆盖 5 段 cron 与可选年份字段。 */
public final class CronSupport {
    private static final Pattern RECURRING_INTERVAL_PATTERN =
            Pattern.compile(
                    "^every\\s+(\\d+)\\s*(m|min|minute|minutes|h|hr|hrs|hour|hours|d|day|days)$");
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^(\\d+)\\s*(m|min|minute|minutes|h|hr|hrs|hour|hours|d|day|days)$");

    private CronSupport() {}

    /**
     * 计算下一次执行时间。
     *
     * @param cronExpr 5 段 cron 表达式，或第 6 段为年份
     * @param fromEpochMillis 起算时间
     * @return 下一次执行时间戳
     */
    public static long nextRunAt(String cronExpr, long fromEpochMillis) {
        if (StrUtil.isBlank(cronExpr)) {
            return fromEpochMillis + 60000L;
        }
        Long direct = nextDirectSchedule(cronExpr, fromEpochMillis);
        if (direct != null) {
            return direct.longValue();
        }

        String[] parts = cronParts(cronExpr);
        if (parts == null) {
            return fromEpochMillis + 60000L;
        }
        validate(parts);

        Calendar candidate = Calendar.getInstance();
        candidate.setTimeInMillis(fromEpochMillis + 60000L);
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);

        long max = fromEpochMillis + 366L * 24L * 60L * 60L * 1000L;
        while (candidate.getTimeInMillis() <= max) {
            if (matches(parts[0], candidate.get(Calendar.MINUTE))
                    && matches(parts[1], candidate.get(Calendar.HOUR_OF_DAY))
                    && matches(parts[2], candidate.get(Calendar.DAY_OF_MONTH))
                    && matches(parts[3], candidate.get(Calendar.MONTH) + 1)
                    && matchesDayOfWeek(parts[4], candidate.get(Calendar.DAY_OF_WEEK))
                    && matchesYear(parts, candidate.get(Calendar.YEAR))) {
                return candidate.getTimeInMillis();
            }

            candidate.add(Calendar.MINUTE, 1);
        }

        return fromEpochMillis + 60000L;
    }

    public static long periodMillis(String cronExpr, long fromEpochMillis) {
        if (StrUtil.isBlank(cronExpr) || isOneShot(cronExpr)) {
            return 0L;
        }
        Long direct = directIntervalMillis(cronExpr);
        if (direct != null) {
            return direct.longValue();
        }
        try {
            long first = nextRunAt(cronExpr, fromEpochMillis);
            long second = nextRunAt(cronExpr, first);
            return Math.max(0L, second - first);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** 将 Java 星期映射为 cron 星期值后做匹配。 */
    private static boolean matchesDayOfWeek(String expr, int dayOfWeek) {
        int normalized = dayOfWeek - 1;
        if (normalized < 0) {
            normalized = 0;
        }
        return matches(expr, normalized) || (normalized == 0 && matches(expr, 7));
    }

    public static void validate(String cronExpr) {
        if (StrUtil.isBlank(cronExpr)) {
            throw new IllegalArgumentException("Cron expression is required");
        }
        if (nextDirectSchedule(cronExpr, System.currentTimeMillis()) != null) {
            return;
        }
        String[] parts = cronParts(cronExpr);
        if (parts == null) {
            throw new IllegalArgumentException(
                    "Cron expression must have 5 fields or 6 fields with year");
        }
        validate(parts);
    }

    /** 判断是否是一次性 ISO 时间。 */
    public static boolean isOneShot(String schedule) {
        if (StrUtil.isBlank(schedule)) {
            return false;
        }
        String normalized = schedule.trim();
        if (DURATION_PATTERN.matcher(normalized.toLowerCase(Locale.ROOT)).matches()) {
            return true;
        }
        try {
            parseIsoMillis(normalized);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isInterval(String schedule) {
        return StrUtil.isNotBlank(schedule)
                && RECURRING_INTERVAL_PATTERN
                        .matcher(schedule.trim().toLowerCase(Locale.ROOT))
                        .matches();
    }

    public static boolean isCronExpression(String schedule) {
        if (StrUtil.isBlank(schedule)) {
            return false;
        }
        String[] parts = cronParts(schedule);
        if (parts == null) {
            return false;
        }
        try {
            validate(parts);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String kind(String schedule) {
        if (isInterval(schedule)) {
            return "interval";
        }
        if (isOneShot(schedule)) {
            return "once";
        }
        return "cron";
    }

    public static Integer intervalMinutes(String schedule) {
        if (StrUtil.isBlank(schedule)) {
            return null;
        }
        Matcher matcher =
                RECURRING_INTERVAL_PATTERN.matcher(schedule.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            matcher = DURATION_PATTERN.matcher(schedule.trim().toLowerCase(Locale.ROOT));
        }
        if (!matcher.matches()) {
            return null;
        }
        long millis = intervalMillis(matcher);
        return Integer.valueOf((int) Math.max(1L, millis / 60000L));
    }

    public static Long absoluteRunAt(String schedule) {
        if (StrUtil.isBlank(schedule)) {
            return null;
        }
        String normalized = schedule.trim();
        if (DURATION_PATTERN.matcher(normalized.toLowerCase(Locale.ROOT)).matches()) {
            return null;
        }
        try {
            return Long.valueOf(parseIsoMillis(normalized));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long nextDirectSchedule(String schedule, long fromEpochMillis) {
        String normalized = schedule.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = RECURRING_INTERVAL_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            matcher = DURATION_PATTERN.matcher(normalized);
        }
        if (matcher.matches()) {
            long millis = intervalMillis(matcher);
            return Long.valueOf(fromEpochMillis + Math.max(60000L, millis));
        }
        try {
            long isoMillis = parseIsoMillis(schedule.trim());
            return Long.valueOf(Math.max(isoMillis, fromEpochMillis + 1000L));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long directIntervalMillis(String schedule) {
        Matcher matcher =
                RECURRING_INTERVAL_PATTERN.matcher(schedule.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        return Long.valueOf(Math.max(60000L, intervalMillis(matcher)));
    }

    private static long intervalMillis(Matcher matcher) {
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        if (unit.startsWith("h")) {
            return amount * 60L * 60L * 1000L;
        }
        if (unit.startsWith("d")) {
            return amount * 24L * 60L * 1000L;
        }
        return amount * 60L * 1000L;
    }

    private static long parseIsoMillis(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            LocalDateTime dateTime = LocalDateTime.parse(value);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
    }

    private static void validate(String[] parts) {
        validateField(parts[0], 0, 59);
        validateField(parts[1], 0, 23);
        validateField(parts[2], 1, 31);
        validateField(parts[3], 1, 12);
        validateField(parts[4], 0, 7);
        if (parts.length == 6) {
            validateField(parts[5], 1970, 2099);
        }
    }

    private static String[] cronParts(String schedule) {
        if (StrUtil.isBlank(schedule)) {
            return null;
        }
        String[] parts = schedule.trim().split("\\s+");
        return parts.length == 5 || parts.length == 6 ? parts : null;
    }

    private static boolean matchesYear(String[] parts, int year) {
        return parts.length < 6 || matches(parts[5], year);
    }

    private static void validateField(String expr, int min, int max) {
        if ("*".equals(expr)) {
            return;
        }
        if (expr.startsWith("*/")) {
            int step = Integer.parseInt(expr.substring(2));
            if (step <= 0) {
                throw new IllegalArgumentException("Cron step must be positive");
            }
            return;
        }
        String[] items = expr.split(",");
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.length() == 0) {
                throw new IllegalArgumentException("Cron field contains empty item");
            }
            if (trimmed.indexOf('-') > 0) {
                String[] range = trimmed.split("-", 2);
                int start = Integer.parseInt(range[0]);
                int end = Integer.parseInt(range[1]);
                if (start > end || start < min || end > max) {
                    throw new IllegalArgumentException("Cron range is out of bounds");
                }
            } else {
                int value = Integer.parseInt(trimmed);
                if (value < min || value > max) {
                    throw new IllegalArgumentException("Cron value is out of bounds");
                }
            }
        }
    }

    /** 匹配单个 cron 字段。 */
    private static boolean matches(String expr, int value) {
        if ("*".equals(expr)) {
            return true;
        }

        if (expr.startsWith("*/")) {
            int step = Integer.parseInt(expr.substring(2));
            return step > 0 && value % step == 0;
        }

        String[] items = expr.split(",");
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.length() == 0) {
                continue;
            }

            if (trimmed.indexOf('-') > 0) {
                String[] range = trimmed.split("-", 2);
                int start = Integer.parseInt(range[0]);
                int end = Integer.parseInt(range[1]);
                if (value >= start && value <= end) {
                    return true;
                }
            } else if (Integer.parseInt(trimmed) == value) {
                return true;
            }
        }

        return false;
    }
}
