package com.jimuqu.solon.claw.support;

import java.lang.management.ManagementFactory;

/** 运行时进程信息工具，集中处理当前 JVM 进程标识解析。 */
public final class RuntimeProcessSupport {
    /** 工具类不保存状态，禁止创建实例。 */
    private RuntimeProcessSupport() {}

    /**
     * 读取当前 JVM 进程 ID；解析失败时返回 null，供 HTTP 状态接口保持原有空值语义。
     *
     * @return 当前 JVM 进程 ID，无法解析时返回 null。
     */
    public static Integer currentPid() {
        try {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int index = runtimeName.indexOf('@');
            String pid = index > 0 ? runtimeName.substring(0, index) : runtimeName;
            return Integer.valueOf(pid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取当前 JVM 进程 ID；解析失败时返回 -1，供网关进程匹配逻辑保留原有哨兵值语义。
     *
     * @return 当前 JVM 进程 ID，无法解析时返回 -1。
     */
    public static long currentPidOrUnknown() {
        Integer pid = currentPid();
        return pid == null ? -1L : pid.longValue();
    }
}
