package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 承载终端Tips相关状态和辅助逻辑。 */
public final class TerminalTips {
    /** TIP 列表的统一常量值。 */
    private static final List<String> TIPS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "/queue <提示> 会排到当前任务之后执行。",
                            "/steer <提示> 会注入正在运行的任务。",
                            "/busy interrupt 会把新输入切换为中断当前任务后执行。",
                            "/busy reject 会把新输入切换为忙时直接拒绝。",
                            "/events 可查看最近一次运行的事件明细。",
                            "/goal <目标> --max N 会启动跨轮长目标循环。",
                            "/recap 可显示恢复会话用的紧凑历史摘要。",
                            "/trajectory save <问题> 可保存会话轨迹样本。",
                            "/reload-mcp now 可立即重载 MCP 工具 schema。",
                            "/security audit 可查看危险命令、URL、路径和工具参数策略摘要。",
                            "/security status 可只读查看安全策略状态，不执行命令、不访问 URL。",
                            "/cron guide 可查看自动化字段、技能绑定和投递策略。",
                            "/compact <重点> 可压缩当前会话上下文。",
                            "/attachments <路径> 可在发送前预检附件识别结果。",
                            "/approve <确认编号> session 可批准当前会话的危险命令。",
                            "/deny <确认编号> 可拒绝危险命令审批。",
                            "/skin mono 可切换到无 ANSI 的纯文本皮肤。",
                            "粘贴本地文件路径会自动作为附件发送。"));

    /** 创建终端Tips实例。 */
    private TerminalTips() {}

    /**
     * 判断是否Tips命令。
     *
     * @param input 输入参数。
     * @return 如果Tips命令满足条件则返回 true，否则返回 false。
     */
    public static boolean isTipsCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/tips".equalsIgnoreCase(value);
    }

    /**
     * 执行当前相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回当前结果。
     */
    public static String current(String sessionId) {
        if (TIPS.isEmpty()) {
            return "";
        }
        String key = StrUtil.blankToDefault(sessionId, "tui");
        int index = Math.abs(key.hashCode()) % TIPS.size();
        return TIPS.get(index);
    }

    /**
     * 执行render相关逻辑。
     *
     * @return 返回render结果。
     */
    public static String render() {
        StringBuilder buffer = new StringBuilder("终端提示：");
        for (int i = 0; i < TIPS.size(); i++) {
            buffer.append('\n').append(i + 1).append(". ").append(TIPS.get(i));
        }
        return buffer.toString();
    }
}
