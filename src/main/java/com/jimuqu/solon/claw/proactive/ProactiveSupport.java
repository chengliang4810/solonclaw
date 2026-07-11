package com.jimuqu.solon.claw.proactive;

/** 主动协作共享辅助，集中各 proactive 类原本复制粘贴的纯函数（如免打扰时段判定），避免口径漂移。 */
public final class ProactiveSupport {
    /** 创建主动协作共享辅助实例。 */
    private ProactiveSupport() {}

    /**
     * 判断指定小时是否落在配置的免打扰时段内；起止小时自动夹紧到 0-23。
     *
     * <p>跨午夜区间（start &gt; end）按 {@code hour >= start || hour < end} 判定；start == end 视为未配置，返回 false。
     *
     * @param startHour 免打扰起始小时（配置原始值）。
     * @param endHour 免打扰结束小时（配置原始值）。
     * @param hour 当前小时（0-23）。
     * @return 落在免打扰时段返回 true。
     */
    public static boolean isQuietHour(int startHour, int endHour, int hour) {
        int start = clampHour(startHour);
        int end = clampHour(endHour);
        if (start == end) {
            return false;
        }
        if (start < end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }

    /** 把小时夹紧到 0-23，避免配置越界影响判定。 */
    private static int clampHour(int hour) {
        return Math.max(0, Math.min(23, hour));
    }
}
