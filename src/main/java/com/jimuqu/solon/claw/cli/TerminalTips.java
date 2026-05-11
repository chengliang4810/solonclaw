package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Small local tips surfaced by the terminal UI. */
public final class TerminalTips {
    private static final List<String> TIPS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "/queue <提示> 会排到当前任务之后执行。",
                            "/steer <提示> 会注入正在运行的任务。",
                            "/events 可查看最近一次运行的事件明细。",
                            "/goal <目标> --max N 会启动跨轮长目标循环。",
                            "/recap 可显示恢复会话用的紧凑历史摘要。",
                            "/trajectory save <问题> 可保存会话轨迹样本。",
                            "/reload-mcp now 可立即重载 MCP 工具 schema。",
                            "/acp status 可查看本地适配器能力快照。",
                            "/security audit 可查看危险命令、URL、路径和工具参数策略摘要。",
                            "/cron guide 可查看自动化字段、技能绑定和投递策略。",
                            "/kanban guide 可查看任务抽屉、流水、重试和派发流程。",
                            "/compact <重点> 可压缩当前会话上下文。",
                            "/attachments <路径> 可在发送前预检附件识别结果。",
                            "/approve <确认编号> session 可批准当前会话的危险命令。",
                            "/deny <确认编号> 可拒绝危险命令审批。",
                            "/skin mono 可切换到无 ANSI 的纯文本皮肤。",
                            "粘贴本地文件路径会自动作为附件发送。"));

    private TerminalTips() {}

    public static boolean isTipsCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/tips".equalsIgnoreCase(value);
    }

    public static String current(String sessionId) {
        if (TIPS.isEmpty()) {
            return "";
        }
        String key = StrUtil.blankToDefault(sessionId, "tui");
        int index = Math.abs(key.hashCode()) % TIPS.size();
        return TIPS.get(index);
    }

    public static String render() {
        StringBuilder buffer = new StringBuilder("终端提示：");
        for (int i = 0; i < TIPS.size(); i++) {
            buffer.append('\n').append(i + 1).append(". ").append(TIPS.get(i));
        }
        return buffer.toString();
    }
}
