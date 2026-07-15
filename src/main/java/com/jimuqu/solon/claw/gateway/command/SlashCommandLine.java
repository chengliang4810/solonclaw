package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;

/** 封装消息网关 slash command 的解析结果，避免服务层重复拆分命令文本。 */
final class SlashCommandLine {
    /** 注册表中解析出的命令描述符；未登记命令保持为 null，便于走兜底帮助。 */
    private final CommandDescriptor descriptor;

    /** 规范化后的命令名，不包含斜杠前缀。 */
    private final String command;

    /** 命令后续参数文本，保留用户输入内容但去掉首尾空白。 */
    private final String args;

    /**
     * 创建不可变解析结果。
     *
     * @param descriptor 命令描述符，未登记命令可为空。
     * @param command 规范化命令名。
     * @param args 命令参数文本。
     */
    private SlashCommandLine(CommandDescriptor descriptor, String command, String args) {
        this.descriptor = descriptor;
        this.command = command;
        this.args = args;
    }

    /**
     * 按既有消息网关规则解析 slash command 文本。
     *
     * @param commandLine 原始命令行，必须包含斜杠前缀。
     * @return 命令解析结果。
     */
    static SlashCommandLine parse(String commandLine) {
        String withoutSlash = commandLine.substring(1).trim();
        String[] parts = withoutSlash.split("\\s+", 2);
        CommandDescriptor descriptor = CommandRegistry.resolve(parts[0]);
        String command = descriptor == null ? parts[0].toLowerCase() : descriptor.getName();
        String args = parts.length > 1 ? parts[1].trim() : "";
        return new SlashCommandLine(descriptor, command, args);
    }

    /**
     * 解析命令参数中的首个动作和后续尾部参数，供带子动作的 slash command 复用。
     *
     * @param raw 原始参数文本。
     * @param defaultAction 参数为空时使用的默认动作。
     * @return 动作和尾部参数的不可变解析结果。
     */
    static ActionTail parseActionTail(String raw, String defaultAction) {
        String value = StrUtil.nullToEmpty(raw).trim();
        String action = firstToken(value);
        if (StrUtil.isBlank(action)) {
            action = StrUtil.nullToEmpty(defaultAction);
        } else {
            action = action.toLowerCase(java.util.Locale.ROOT);
        }
        return new ActionTail(action, remainingTokens(value));
    }

    /**
     * 读取参数文本中的首个 token。
     *
     * @param raw 原始参数文本。
     * @return 首个 token；空参数返回空字符串。
     */
    static String firstToken(String raw) {
        String[] parts = StrUtil.nullToEmpty(raw).trim().split("\\s+", 2);
        return parts.length == 0 ? "" : parts[0];
    }

    /**
     * 读取参数文本中首个 token 之后的剩余内容。
     *
     * @param raw 原始参数文本。
     * @return 去掉首个 token 后的剩余参数。
     */
    static String remainingTokens(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        String first = firstToken(value);
        if (StrUtil.isBlank(first) || value.length() <= first.length()) {
            return "";
        }
        return value.substring(first.length()).trim();
    }

    /**
     * 读取注册表中解析出的命令描述符。
     *
     * @return 命令描述符；未登记命令返回 null。
     */
    CommandDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * 读取规范化后的命令名。
     *
     * @return 不含斜杠前缀的命令名。
     */
    String getCommand() {
        return command;
    }

    /**
     * 读取命令参数文本。
     *
     * @return 去掉首尾空白后的参数文本。
     */
    String getArgs() {
        return args;
    }

    /** 封装 slash command 子动作解析结果，避免调用方重复手写 split 规则。 */
    static final class ActionTail {
        /** 标准化后的子动作名称。 */
        private final String action;

        /** 子动作之后的尾部参数文本。 */
        private final String tail;

        /**
         * 创建不可变子动作解析结果。
         *
         * @param action 标准化后的子动作名称。
         * @param tail 子动作之后的尾部参数文本。
         */
        private ActionTail(String action, String tail) {
            this.action = action;
            this.tail = tail;
        }

        /**
         * 读取标准化后的子动作名称。
         *
         * @return 子动作名称。
         */
        String getAction() {
            return action;
        }

        /**
         * 读取子动作之后的尾部参数文本。
         *
         * @return 尾部参数文本。
         */
        String getTail() {
            return tail;
        }
    }
}
