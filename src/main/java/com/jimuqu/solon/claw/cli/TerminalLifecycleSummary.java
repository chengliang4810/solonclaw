package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;

/** 承载终端生命周期摘要相关状态和辅助逻辑。 */
public final class TerminalLifecycleSummary {
    /** 创建终端生命周期Summary实例。 */
    private TerminalLifecycleSummary() {}

    /**
     * 执行render相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param taskRunner 任务Runner参数。
     * @param transcript transcript 参数。
     * @param eventSnapshot 事件Snapshot参数。
     * @param copyReady copyReady 参数。
     * @return 返回render结果。
     */
    public static String render(
            String sessionId,
            LocalTerminalTaskRunner taskRunner,
            LocalTerminalTranscript transcript,
            ConsoleEventSink.EventSnapshot eventSnapshot,
            boolean copyReady) {
        int running = taskRunner == null ? 0 : taskRunner.runningCount();
        int recentTasks = taskRunner == null ? 0 : taskRunner.snapshots().size();
        int transcriptCount = transcript == null ? 0 : transcript.count();
        int events = eventSnapshot == null ? 0 : eventSnapshot.getEventCount();
        int tools = eventSnapshot == null ? 0 : eventSnapshot.getToolCount();
        int failures = eventSnapshot == null ? 0 : eventSnapshot.getFailureCount();
        return "终端会话结束：session="
                + StrUtil.blankToDefault(sessionId, "-")
                + "  tasks="
                + running
                + "/"
                + recentTasks
                + "  transcript="
                + transcriptCount
                + "  events="
                + events
                + " tools="
                + tools
                + " failures="
                + failures
                + "  copy="
                + (copyReady ? "ready" : "empty");
    }
}
