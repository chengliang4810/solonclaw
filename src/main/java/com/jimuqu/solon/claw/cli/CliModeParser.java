package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import java.util.ArrayList;
import java.util.List;

/** 解析进程启动参数，决定当前进程是启动服务端、进入本地 CLI/TUI，还是输出补全脚本。 */
public final class CliModeParser {
    /** 创建Cli模式Parser实例。 */
    private CliModeParser() {}

    /**
     * 解析命令行参数为启动模式。
     *
     * @param args 原始启动参数。
     * @return CLI/TUI/server/completion 启动模式。
     */
    public static CliMode parse(String[] args) {
        if (ArrayUtil.isEmpty(args)) {
            return new CliMode(CliMode.Kind.SERVER, null, null);
        }
        CliMode topLevelCommand = parseTopLevelTerminalCommand(args);
        if (topLevelCommand != null) {
            return topLevelCommand;
        }

        CliMode.Kind kind = CliMode.Kind.SERVER;
        String sessionId = null;
        List<String> inputParts = new ArrayList<String>();
        boolean captureRest = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (captureRest) {
                inputParts.add(arg);
                continue;
            }
            if ("--cli".equals(arg) || "cli".equalsIgnoreCase(arg)) {
                kind = CliMode.Kind.CLI;
                continue;
            }
            if ("--tui".equals(arg) || "tui".equalsIgnoreCase(arg)) {
                kind = CliMode.Kind.TUI;
                continue;
            }
            if ("completion".equalsIgnoreCase(arg) || "--completion".equalsIgnoreCase(arg)) {
                kind = CliMode.Kind.COMPLETION;
                if (i + 1 < args.length) {
                    inputParts.add(args[++i]);
                }
                continue;
            }
            if ("--session".equals(arg) && i + 1 < args.length) {
                sessionId = args[++i];
                continue;
            }
            if (arg != null && arg.startsWith("--session=")) {
                sessionId = arg.substring("--session=".length());
                continue;
            }
            if ("--ask".equals(arg) || "-p".equals(arg)) {
                captureRest = true;
                continue;
            }
            if (kind != CliMode.Kind.SERVER) {
                inputParts.add(arg);
            }
        }

        String input = StrUtil.join(" ", inputParts).trim();
        return new CliMode(kind, StrUtil.isBlank(input) ? null : input, sessionId);
    }

    /**
     * 将用户直接输入的顶层配置命令转成本地终端 slash 命令。
     *
     * @param args 启动参数。
     * @return 可本地执行的 CLI 模式；非顶层配置命令返回 null。
     */
    private static CliMode parseTopLevelTerminalCommand(String[] args) {
        args = expandSingleCompositeArgument(args);
        String first = args[0] == null ? "" : args[0].trim().toLowerCase();
        if ("--cli".equals(first)
                || "cli".equals(first)
                || "--tui".equals(first)
                || "tui".equals(first)
                || "completion".equals(first)
                || "--completion".equals(first)) {
            return null;
        }
        if ("model".equals(first)) {
            if (args.length > 1
                    && ("set".equalsIgnoreCase(args[1])
                            || "configure".equalsIgnoreCase(args[1]))) {
                return localSlashCommand("/model", args, 1);
            }
            return args.length == 1 ? new CliMode(CliMode.Kind.CLI, "/setup model", null) : null;
        }
        if ("models".equals(first)) {
            return new CliMode(CliMode.Kind.CLI, "/models", null);
        }
        if ("session".equals(first)) {
            return localSlashCommand("/session", args, 1);
        }
        if ("sessions".equals(first) && args.length > 1) {
            String sub = StrUtil.nullToEmpty(args[1]).trim().toLowerCase();
            if ("browse".equals(sub) || "list".equals(sub) || "ls".equals(sub)) {
                return localSlashCommand("/sessions", args, 2);
            }
        }
        if ("setup".equals(first)) {
            return localSlashCommand("/setup", args, 1);
        }
        if ("gateway".equals(first) && args.length > 1 && "setup".equalsIgnoreCase(args[1])) {
            return localSlashCommand("/setup", "gateway", args, 2);
        }
        if ("gateway".equals(first)) {
            if (args.length == 1) {
                return localSlashCommand("/gateway", "status", args, 1);
            }
            return localSlashCommand("/gateway", args, 1);
        }
        if ("config".equals(first)) {
            return localSlashCommand("/config", args, 1);
        }
        if ("doctor".equals(first)) {
            return localSlashCommand("/doctor", args, 1);
        }
        if ("status".equals(first) || "logout".equals(first)) {
            return localSlashCommand("/" + first, args, 1);
        }
        if ("version".equals(first) || "--version".equals(first) || "-v".equals(first)) {
            if ("version".equals(first)) {
                return localSlashCommand("/version", args, 1);
            }
            return new CliMode(CliMode.Kind.CLI, "/version", null);
        }
        if ("pairing".equals(first)) {
            return localSlashCommand("/pairing", args, 1);
        }
        if (LocalGuidanceCommands.COMMANDS.contains(first)) {
            return localSlashCommand("/" + first, args, 1);
        }
        CommandDescriptor descriptor = CommandRegistry.resolve(first);
        if (descriptor != null) {
            return localSlashCommand("/" + descriptor.getName(), args, 1);
        }
        return null;
    }

    /**
     * 将顶层管理命令转换成本地 CLI 可执行的 slash command。
     *
     * @param slashCommand 目标 slash command，例如 /setup 或 /doctor。
     * @param args 原始启动参数。
     * @param fromIndex 需要保留到 slash command 后面的原始参数起点。
     * @return 本地 CLI 模式，网络监听不会启动。
     */
    private static CliMode localSlashCommand(String slashCommand, String[] args, int fromIndex) {
        return localSlashCommand(slashCommand, null, args, fromIndex);
    }

    /**
     * 将顶层管理命令转换成本地 CLI 可执行的 slash command，并可插入固定子命令。
     *
     * @param slashCommand 目标 slash command，例如 /setup 或 /gateway。
     * @param fixedArgument 需要固定插入的子命令；为空时不插入。
     * @param args 原始启动参数。
     * @param fromIndex 需要保留到 slash command 后面的原始参数起点。
     * @return 本地 CLI 模式，网络监听不会启动。
     */
    private static CliMode localSlashCommand(
            String slashCommand, String fixedArgument, String[] args, int fromIndex) {
        List<String> command = new ArrayList<String>();
        command.add(slashCommand);
        if (StrUtil.isNotBlank(fixedArgument)) {
            command.add(fixedArgument);
        }
        if (ArrayUtil.isNotEmpty(args)) {
            for (int i = Math.max(0, fromIndex); i < args.length; i++) {
                command.add(args[i]);
            }
        }
        return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
    }

    /**
     * 兼容桌面入口或脚本把顶层管理命令作为单个参数传入的情况。
     *
     * @param args 原始启动参数。
     * @return 若只有一个复合参数则按空白切分，否则返回原数组。
     */
    private static String[] expandSingleCompositeArgument(String[] args) {
        if (ArrayUtil.length(args) != 1 || args[0] == null) {
            return args;
        }
        String value = args[0].trim();
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0) {
            return args;
        }
        List<String> tokens = new ArrayList<String>();
        for (String token : value.split("\\s+")) {
            if (StrUtil.isNotBlank(token)) {
                tokens.add(token);
            }
        }
        return tokens.isEmpty() ? args : tokens.toArray(new String[tokens.size()]);
    }
}
