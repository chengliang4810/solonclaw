package com.jimuqu.solon.claw.core.model;

/** 表示Agent运行Stop结果，携带调用方后续判断所需信息。 */
public class AgentRunStopResult {
    /** 是否启用active运行。 */
    private boolean activeRun;

    /** 记录Agent运行Stop中的运行标识。 */
    private String runId;

    /** 记录Agent运行Stop中的会话标识。 */
    private String sessionId;

    /** 是否启用interruptSent。 */
    private boolean interruptSent;

    /** 记录Agent运行Stop中的started时间。 */
    private long startedAt;

    /**
     * 执行none相关逻辑。
     *
     * @return 返回none结果。
     */
    public static AgentRunStopResult none() {
        return new AgentRunStopResult();
    }

    /**
     * 执行stopped相关逻辑。
     *
     * @param runId 运行标识。
     * @param sessionId 当前会话标识。
     * @param interruptSent interruptSent 参数。
     * @param startedAt startedAt 参数。
     * @return 返回stopped结果。
     */
    public static AgentRunStopResult stopped(
            String runId, String sessionId, boolean interruptSent, long startedAt) {
        AgentRunStopResult result = new AgentRunStopResult();
        result.setActiveRun(true);
        result.setRunId(runId);
        result.setSessionId(sessionId);
        result.setInterruptSent(interruptSent);
        result.setStartedAt(startedAt);
        return result;
    }

    /**
     * 判断是否Active运行。
     *
     * @return 如果Active运行满足条件则返回 true，否则返回 false。
     */
    public boolean isActiveRun() {
        return activeRun;
    }

    /**
     * 写入Active运行。
     *
     * @param activeRun active运行参数。
     */
    public void setActiveRun(boolean activeRun) {
        this.activeRun = activeRun;
    }

    /**
     * 读取运行标识。
     *
     * @return 返回读取到的运行标识。
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 写入运行标识。
     *
     * @param runId 运行标识。
     */
    public void setRunId(String runId) {
        this.runId = runId;
    }

    /**
     * 读取会话标识。
     *
     * @return 返回读取到的会话标识。
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 写入会话标识。
     *
     * @param sessionId 当前会话标识。
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 判断是否Interrupt Sent。
     *
     * @return 如果Interrupt Sent满足条件则返回 true，否则返回 false。
     */
    public boolean isInterruptSent() {
        return interruptSent;
    }

    /**
     * 写入Interrupt Sent。
     *
     * @param interruptSent interruptSent 参数。
     */
    public void setInterruptSent(boolean interruptSent) {
        this.interruptSent = interruptSent;
    }

    /**
     * 读取Started时间。
     *
     * @return 返回读取到的Started时间。
     */
    public long getStartedAt() {
        return startedAt;
    }

    /**
     * 写入Started时间。
     *
     * @param startedAt startedAt 参数。
     */
    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }
}
