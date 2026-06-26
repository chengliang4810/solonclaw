package com.jimuqu.solon.claw.support;

/** 时间计算辅助工具，集中处理运行态展示用的简单时间换算。 */
public final class TimeSupport {
    /** 工具类不保存状态，禁止创建实例。 */
    private TimeSupport() {}

    /**
     * 计算毫秒时间戳距离当前时间的剩余秒数，向上取整且不返回负数。
     *
     * @param expiresAt 过期时间戳，单位毫秒。
     * @return 剩余秒数，已过期或未设置时返回0。
     */
    public static long expiresInSeconds(long expiresAt) {
        if (expiresAt <= 0L) {
            return 0L;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    /**
     * 将对象解析为毫秒时间戳；解析失败时返回当前时间，避免运行报告落入0时间。
     *
     * @param value 待解析的时间值。
     * @return 解析出的毫秒时间戳，失败时返回当前时间。
     */
    public static long millisOrNow(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
