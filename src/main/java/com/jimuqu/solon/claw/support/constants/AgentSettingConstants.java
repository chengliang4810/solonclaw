package com.jimuqu.solon.claw.support.constants;

/** Agent 全局设置常量。 */
public interface AgentSettingConstants {
    /** 当前激活人格名。 */
    String ACTIVE_PERSONALITY = "active_personality";

    /** 永久批准的危险命令模式列表。 */
    String DANGEROUS_COMMAND_ALWAYS_PATTERNS = "dangerous_command_always_patterns";

    /** 已永久确认的 slash 命令集合。 */
    String SLASH_CONFIRM_ALWAYS_COMMANDS = "slash_confirm_always_commands";

    /** 每个来源键的 reasoning 展示设置前缀。 */
    String DISPLAY_REASONING_VISIBLE_PREFIX = "display_reasoning_visible:";
}
