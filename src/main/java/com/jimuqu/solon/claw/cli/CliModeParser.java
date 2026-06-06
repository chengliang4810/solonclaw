package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import java.util.ArrayList;
import java.util.List;

/** 承载CLI模式Parser相关状态和辅助逻辑。 */
public final class CliModeParser {
    /** 需要在本地终端渲染说明而不是启动 HTTP/WebSocket 服务的顶层管理命令。 */
    private static final java.util.List<String> LOCAL_GUIDANCE_COMMANDS =
            java.util.Arrays.asList(
                    "postinstall",
                    "login",
                    "auth",
                    "fallback",
                    "secrets",
                    "proxy",
                    "mcp",
                    "migrate",
                    "send",
                    "hooks",
                    "dump",
                    "backup",
                    "checkpoints",
                    "import",
                    "bundles",
                    "memory",
                    "dashboard",
                    "logs",
                    "prompt-size");

    /** 创建Cli模式Parser实例。 */
    private CliModeParser() {}

    /**
     * 执行解析相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回parse结果。
     */
    public static CliMode parse(String[] args) {
        if (args == null || args.length == 0) {
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
                List<String> command = new ArrayList<String>();
                command.add("/model");
                for (int i = 1; i < args.length; i++) {
                    command.add(args[i]);
                }
                return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
            }
            if (args.length > 1) {
                List<String> command = new ArrayList<String>();
                command.add("/model");
                for (int i = 1; i < args.length; i++) {
                    command.add(args[i]);
                }
                return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
            }
            return new CliMode(CliMode.Kind.CLI, "/setup model", null);
        }
        if ("models".equals(first)) {
            return new CliMode(CliMode.Kind.CLI, "/models", null);
        }
        if ("session".equals(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/session");
            for (int i = 1; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if ("sessions".equals(first) && args.length > 1) {
            String sub = args[1] == null ? "" : args[1].trim().toLowerCase();
            if ("browse".equals(sub) || "list".equals(sub) || "ls".equals(sub)) {
                List<String> command = new ArrayList<String>();
                command.add("/sessions");
                for (int i = 2; i < args.length; i++) {
                    command.add(args[i]);
                }
                return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
            }
        }
        if ("setup".equals(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/setup");
            for (int i = 1; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if ("gateway".equals(first) && args.length > 1 && "setup".equalsIgnoreCase(args[1])) {
            List<String> command = new ArrayList<String>();
            command.add("/setup");
            command.add("gateway");
            for (int i = 2; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if ("gateway".equals(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/gateway");
            if (args.length == 1) {
                command.add("status");
            } else {
                for (int i = 1; i < args.length; i++) {
                    command.add(args[i]);
                }
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if ("config".equals(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/config");
            for (int i = 1; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if ("doctor".equals(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/doctor");
            for (int i = 1; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if ("status".equals(first) || "logout".equals(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/" + first);
            for (int i = 1; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if ("version".equals(first) || "--version".equals(first) || "-v".equals(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/version");
            if ("version".equals(first)) {
                for (int i = 1; i < args.length; i++) {
                    command.add(args[i]);
                }
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if ("pairing".equals(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/pairing");
            for (int i = 1; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        if (LOCAL_GUIDANCE_COMMANDS.contains(first)) {
            List<String> command = new ArrayList<String>();
            command.add("/" + first);
            for (int i = 1; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        CommandDescriptor descriptor = CommandRegistry.resolve(first);
        if (descriptor != null) {
            List<String> command = new ArrayList<String>();
            command.add("/" + descriptor.getName());
            for (int i = 1; i < args.length; i++) {
                command.add(args[i]);
            }
            return new CliMode(CliMode.Kind.CLI, StrUtil.join(" ", command).trim(), null);
        }
        return null;
    }

    /**
     * 兼容桌面入口或脚本把顶层管理命令作为单个参数传入的情况。
     *
     * @param args 原始启动参数。
     * @return 若只有一个复合参数则按空白切分，否则返回原数组。
     */
    private static String[] expandSingleCompositeArgument(String[] args) {
        if (args == null || args.length != 1 || args[0] == null) {
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
