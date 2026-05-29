package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;

/** Renders a compact shutdown summary for local terminal sessions. */
public final class TerminalLifecycleSummary {
    private TerminalLifecycleSummary() {}

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
