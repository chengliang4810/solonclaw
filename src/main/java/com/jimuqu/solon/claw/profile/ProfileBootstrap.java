package com.jimuqu.solon.claw.profile;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 在 Solon 和 AppConfig 初始化前解析全局 Profile，并处理一次性 profile 管理命令。 */
public final class ProfileBootstrap {
    /** 工具类不保存实例状态。 */
    private ProfileBootstrap() {}

    /**
     * 使用当前进程目录和用户目录处理 Profile 启动参数。
     *
     * @param args 原始进程参数。
     * @param input 确认提示输入流。
     * @param out 标准输出。
     * @param err 标准错误。
     * @return 启动前处理结果。
     */
    public static Result prepare(
            String[] args, InputStream input, PrintStream out, PrintStream err) {
        ParsedArguments parsed;
        try {
            parsed = parse(args);
        } catch (BootstrapUsageException e) {
            err.println("Profile error: " + e.getMessage());
            return Result.handled(2);
        } catch (IllegalArgumentException e) {
            err.println("Profile error: " + e.getMessage());
            return Result.handled(1);
        }
        Path root = ProfileManager.resolveRoot(parsed.workspace);
        Path userHome =
                Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        ProfileManager manager =
                new ProfileManager(
                        root,
                        userHome.resolve(".local").resolve("bin"),
                        "solonclaw",
                        new ProfileDescriptionService(),
                        ProfileBundledSkillSeeder.discover());
        return prepare(parsed, manager, input, out, err);
    }

    /**
     * 使用测试或嵌入环境提供的管理器处理启动参数。
     *
     * @param args 原始进程参数。
     * @param manager Profile 管理器。
     * @param input 确认提示输入流。
     * @param out 标准输出。
     * @param err 标准错误。
     * @return 启动前处理结果。
     */
    static Result prepare(
            String[] args,
            ProfileManager manager,
            InputStream input,
            PrintStream out,
            PrintStream err) {
        try {
            return prepare(parse(args), manager, input, out, err);
        } catch (BootstrapUsageException e) {
            err.println("Profile error: " + e.getMessage());
            return Result.handled(2);
        } catch (IllegalArgumentException e) {
            err.println("Profile error: " + e.getMessage());
            return Result.handled(1);
        }
    }

    /** 应用已解析 Profile 参数或执行 profile 管理命令。 */
    private static Result prepare(
            ParsedArguments parsed,
            ProfileManager manager,
            InputStream input,
            PrintStream out,
            PrintStream err) {
        System.setProperty("solonclaw.profile.root", manager.root().toString());
        String selected =
                parsed.profile == null || parsed.profile.trim().length() == 0
                        ? manager.activeProfile()
                        : parsed.profile;
        if (!parsed.remaining.isEmpty() && "profile".equals(parsed.remaining.get(0))) {
            List<String> profileArguments = parsed.remaining.subList(1, parsed.remaining.size());
            String helpAction = profileHelpAction(profileArguments);
            if (helpAction != null) {
                out.print(profileHelp(helpAction));
                return Result.handled(0);
            }
            int exitCode = manager.execute(profileArguments, selected, input, out, err);
            return Result.handled(exitCode);
        }
        try {
            Path home = manager.requireProfileHome(selected);
            String normalized =
                    home.equals(manager.root()) ? "default" : home.getFileName().toString();
            applyProfileProperties(home, normalized);
            if (isGatewayCommand(parsed.remaining)) {
                return prepareGateway(parsed.remaining, manager, normalized, out);
            }
            return Result.continueWith(
                    parsed.remaining.toArray(new String[parsed.remaining.size()]), normalized);
        } catch (Exception e) {
            err.println("Profile error: " + e.getMessage());
            return Result.handled(1);
        }
    }

    /** 判断剩余参数是否为启动前可安全处理的网关生命周期命令。 */
    private static boolean isGatewayCommand(List<String> arguments) {
        return arguments != null
                && !arguments.isEmpty()
                && "gateway".equalsIgnoreCase(arguments.get(0));
    }

    /** 执行 Profile 级网关 run/start/stop/restart/status，避免落入说明文本命令。 */
    private static Result prepareGateway(
            List<String> arguments, ProfileManager manager, String profile, PrintStream out)
            throws Exception {
        String action =
                arguments.size() < 2
                        ? "status"
                        : arguments.get(1).trim().toLowerCase(java.util.Locale.ROOT);
        List<String> trailing =
                arguments.size() <= 2
                        ? Collections.<String>emptyList()
                        : new ArrayList<String>(arguments.subList(2, arguments.size()));
        boolean force = trailing.remove("--force");
        boolean all = trailing.remove("--all");
        if ("list".equals(action)) {
            if (all || force || !trailing.isEmpty()) {
                throw new IllegalArgumentException("Usage: solonclaw gateway list");
            }
            printAllGatewayStatuses(manager, out);
            return Result.handled(0);
        }
        if ("run".equals(action) && all) {
            throw new IllegalArgumentException(
                    "gateway run --all is not supported; use gateway start --all.");
        }
        if (all) {
            if (!("start".equals(action)
                    || "stop".equals(action)
                    || "restart".equals(action)
                    || "status".equals(action))) {
                throw new IllegalArgumentException(
                        "--all is only supported by gateway start, stop, restart, and status.");
            }
            for (String name : manager.listProfileNames()) {
                List<String> profileArguments = new ArrayList<String>();
                profileArguments.add("gateway");
                profileArguments.add(action);
                profileArguments.addAll(trailing);
                if (force) {
                    profileArguments.add("--force");
                }
                prepareGateway(profileArguments, manager, name, out);
            }
            return Result.handled(0);
        }
        if ("run".equals(action)) {
            guardNamedProfileUnderMultiplexer(manager, profile, force);
            ProfileGatewayStatus status = manager.gatewayStatus(profile);
            if (status.isRunning()) {
                throw new IllegalStateException(
                        "Gateway for profile '" + profile + "' is already running.");
            }
            List<String> serverArguments = manager.gatewayServerArguments(profile, trailing);
            out.println(
                    "Running gateway for profile '"
                            + profile
                            + "' on port "
                            + serverPort(serverArguments)
                            + ".");
            return Result.continueWith(
                    serverArguments.toArray(new String[serverArguments.size()]), profile);
        }
        if ("start".equals(action)) {
            guardNamedProfileUnderMultiplexer(manager, profile, force);
            boolean wasRunning = manager.gatewayStatus(profile).isRunning();
            manager.startGateway(profile, trailing);
            ProfileGatewayStatus status = manager.gatewayStatus(profile);
            out.println(
                    (wasRunning ? "Gateway already running" : "Started gateway")
                            + " for profile '"
                            + profile
                            + "'"
                            + gatewayLocation(status)
                            + ".");
            return Result.handled(0);
        }
        if ("stop".equals(action)) {
            manager.stopGateway(profile);
            out.println("Stopped gateway for profile '" + profile + "'.");
            return Result.handled(0);
        }
        if ("restart".equals(action)) {
            guardNamedProfileUnderMultiplexer(manager, profile, force);
            manager.stopGateway(profile);
            manager.startGateway(profile, trailing);
            ProfileGatewayStatus status = manager.gatewayStatus(profile);
            out.println(
                    "Restarted gateway for profile '"
                            + profile
                            + "'"
                            + gatewayLocation(status)
                            + ".");
            return Result.handled(0);
        }
        if ("status".equals(action)) {
            if (!trailing.isEmpty()
                    && !(trailing.size() == 1 && "--deep".equals(trailing.get(0)))) {
                throw new IllegalArgumentException("Usage: solonclaw gateway status [--deep]");
            }
            printGatewayStatus(manager.gatewayStatus(profile), out);
            return Result.handled(0);
        }
        return Result.continueWith(arguments.toArray(new String[arguments.size()]), profile);
    }

    /** 按稳定顺序输出全部 Profile 的网关状态，供生命周期批量运维前检查。 */
    private static void printAllGatewayStatuses(ProfileManager manager, PrintStream out)
            throws Exception {
        boolean first = true;
        for (String name : manager.listProfileNames()) {
            if (!first) {
                out.println();
            }
            printGatewayStatus(manager.gatewayStatus(name), out);
            first = false;
        }
    }

    /**
     * 默认 Profile 的网关启用复用模式并正在运行时，拒绝重复启动命名 Profile 网关。
     *
     * <p>{@code --force} 仅作为显式运维逃生口；默认行为防止一个渠道凭据被两个进程同时消费。
     */
    private static void guardNamedProfileUnderMultiplexer(
            ProfileManager manager, String profile, boolean force) throws Exception {
        if (force || "default".equals(profile)) {
            return;
        }
        ProfileGatewayMultiplexGuard.requireIndependentGatewayAllowed(manager, profile, force);
    }

    /** 设置后续配置加载和运行状态记录共同使用的 Profile 属性。 */
    private static void applyProfileProperties(Path home, String profile) {
        System.setProperty("solonclaw.workspace", home.toString());
        System.setProperty("solonclaw.profile.name", profile);
    }

    /** 输出不包含凭据的 Profile 网关状态。 */
    private static void printGatewayStatus(ProfileGatewayStatus status, PrintStream out) {
        out.println("Gateway Status");
        out.println("Profile: " + status.getProfile());
        out.println("Path:    " + status.getHome());
        out.println("State:   " + (status.isRunning() ? "running" : "stopped"));
        out.println("PID:     " + (status.getPid() == null ? "none" : status.getPid()));
        out.println("Port:    " + (status.getPort() == null ? "not allocated" : status.getPort()));
        out.println("PID file: " + status.getPidFile());
        out.println("State file: " + status.getStateFile());
        out.println("Log:     " + status.getLogFile());
    }

    /** 生成启动或重启成功输出中的 PID/端口摘要。 */
    private static String gatewayLocation(ProfileGatewayStatus status) {
        StringBuilder result = new StringBuilder();
        if (status.getPid() != null) {
            result.append(" (PID ").append(status.getPid());
            if (status.getPort() != null) {
                result.append(", port ").append(status.getPort());
            }
            result.append(')');
        }
        return result.toString();
    }

    /** 从规范化服务端参数读取最终端口，仅用于用户可见启动提示。 */
    private static String serverPort(List<String> arguments) {
        for (String argument : arguments) {
            if (argument != null && argument.startsWith("--server.port=")) {
                return argument.substring("--server.port=".length());
            }
        }
        return "unknown";
    }

    /** 从原始参数剥离全局 Profile 与启动级工作区参数。 */
    private static ParsedArguments parse(String[] rawArgs) {
        List<String> args =
                rawArgs == null ? Collections.<String>emptyList() : Arrays.asList(rawArgs);
        List<String> remaining = new ArrayList<String>();
        String profile = null;
        String workspace = null;
        boolean promptStarted = false;
        boolean passthrough = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (promptStarted || passthrough) {
                remaining.add(arg);
                continue;
            }
            if ("--".equals(arg)) {
                passthrough = true;
                remaining.add(arg);
                continue;
            }
            if ("--args".equals(arg) && isMcpAddCommand(remaining)) {
                passthrough = true;
                remaining.add(arg);
                continue;
            }
            if ("--ask".equals(arg)) {
                promptStarted = true;
                remaining.add(arg);
                continue;
            }
            if ("-p".equals(arg) || "--profile".equals(arg)) {
                profile = requireNext(args, ++i, arg);
                continue;
            }
            if (arg != null && arg.startsWith("--profile=")) {
                profile = requireInlineValue(arg, "--profile=");
                continue;
            }
            if ("--solonclaw.workspace".equals(arg)) {
                workspace = requireNext(args, ++i, arg);
                continue;
            }
            if (arg != null && arg.startsWith("--solonclaw.workspace=")) {
                workspace = requireInlineValue(arg, "--solonclaw.workspace=");
                continue;
            }
            remaining.add(arg);
        }
        return new ParsedArguments(profile, workspace, remaining);
    }

    /** 判断当前参数前缀是否已经进入 `mcp add` 的子进程参数区。 */
    private static boolean isMcpAddCommand(List<String> remaining) {
        return remaining != null
                && remaining.size() >= 2
                && "mcp".equalsIgnoreCase(remaining.get(0))
                && "add".equalsIgnoreCase(remaining.get(1));
    }

    /** 返回请求子命令帮助的动作；普通执行返回 null。 */
    private static String profileHelpAction(List<String> arguments) {
        if (arguments == null || arguments.size() < 2) {
            return null;
        }
        String action = arguments.get(0).trim();
        if (!isProfileAction(action)) {
            return null;
        }
        for (int i = 1; i < arguments.size(); i++) {
            String value = arguments.get(i);
            if ("--".equals(value)) {
                return null;
            }
            String option = profileOptionName(value);
            if (profileValueOption(action, option)) {
                if (value.startsWith(option + "=")) {
                    continue;
                }
                if (i + 1 >= arguments.size()) {
                    return null;
                }
                i++;
                continue;
            }
            if ("-h".equals(value) || "--help".equals(value)) {
                return action;
            }
            if (isOptionToken(value) && !profileFlagOption(action, option)) {
                return null;
            }
        }
        return null;
    }

    /** 返回长选项等号形式中的选项名，普通 token 原样返回。 */
    private static String profileOptionName(String value) {
        if (value == null || !value.startsWith("--")) {
            return value;
        }
        int equals = value.indexOf('=');
        return equals > 2 ? value.substring(0, equals) : value;
    }

    /** 判断 Profile 子命令选项是否消费下一个 token。 */
    private static boolean profileValueOption(String action, String option) {
        return ("create".equals(action)
                        && ("--clone-from".equals(option) || "--description".equals(option)))
                || ("describe".equals(action) && "--text".equals(option))
                || ("alias".equals(action) && "--name".equals(option))
                || ("export".equals(action) && ("-o".equals(option) || "--output".equals(option)))
                || ("import".equals(action) && "--name".equals(option))
                || ("install".equals(action) && "--name".equals(option));
    }

    /** 判断 Profile 子命令布尔选项是否合法。 */
    private static boolean profileFlagOption(String action, String option) {
        return ("create".equals(action)
                        && ("--clone".equals(option)
                                || "--clone-all".equals(option)
                                || "--no-alias".equals(option)
                                || "--no-skills".equals(option)))
                || ("describe".equals(action)
                        && ("--auto".equals(option)
                                || "--all".equals(option)
                                || "--overwrite".equals(option)))
                || ("delete".equals(action) && ("-y".equals(option) || "--yes".equals(option)))
                || ("alias".equals(action) && "--remove".equals(option))
                || ("install".equals(action)
                        && ("--alias".equals(option)
                                || "--force".equals(option)
                                || "-y".equals(option)
                                || "--yes".equals(option)))
                || ("update".equals(action)
                        && ("--force-config".equals(option)
                                || "-y".equals(option)
                                || "--yes".equals(option)));
    }

    /** 判断 token 是否属于选项语法。 */
    private static boolean isOptionToken(String value) {
        return value != null && value.length() > 1 && value.charAt(0) == '-';
    }

    /** 判断动作是否属于当前 Profile CLI 的公开子命令。 */
    private static boolean isProfileAction(String action) {
        return "list".equals(action)
                || "use".equals(action)
                || "create".equals(action)
                || "describe".equals(action)
                || "delete".equals(action)
                || "show".equals(action)
                || "alias".equals(action)
                || "rename".equals(action)
                || "export".equals(action)
                || "import".equals(action)
                || "install".equals(action)
                || "update".equals(action)
                || "info".equals(action);
    }

    /** 渲染 Profile 子命令帮助；参数默认值和互斥关系与执行器保持一致。 */
    private static String profileHelp(String action) {
        if ("list".equals(action)) {
            return "Usage: solonclaw profile list\n";
        }
        if ("use".equals(action)) {
            return "Usage: solonclaw profile use <name>\n";
        }
        if ("create".equals(action)) {
            return "Usage: solonclaw profile create <name> [options]\n"
                    + "  --clone                 Clone config and skills from the selected"
                    + " Profile\n"
                    + "  --clone-all             Clone all non-history state from the selected"
                    + " Profile\n"
                    + "  --clone-from <source>   Select clone source; implies --clone\n"
                    + "  --no-alias              Skip wrapper creation\n"
                    + "  --no-skills             Seed no bundled skills; conflicts with every"
                    + " clone option\n"
                    + "  --description <text>    Set the initial description\n";
        }
        if ("describe".equals(action)) {
            return "Usage: solonclaw profile describe [<name>] [options]\n"
                    + "  --text <text>   Set exact text; conflicts with --auto\n"
                    + "  --auto          Generate with the configured description model\n"
                    + "  --overwrite     With --auto, replace user-authored text\n"
                    + "  --all           With --auto, process all eligible Profiles; conflicts"
                    + " with name/--text\n";
        }
        if ("delete".equals(action)) {
            return "Usage: solonclaw profile delete <name> [-y|--yes]\n";
        }
        if ("show".equals(action)) {
            return "Usage: solonclaw profile show <name>\n";
        }
        if ("alias".equals(action)) {
            return "Usage: solonclaw profile alias <name> [--remove] [--name <alias>]\n";
        }
        if ("rename".equals(action)) {
            return "Usage: solonclaw profile rename <old-name> <new-name>\n";
        }
        if ("export".equals(action)) {
            return "Usage: solonclaw profile export <name> [-o|--output <path>]\n"
                    + "Default output: <name>.tar.gz\n";
        }
        if ("import".equals(action)) {
            return "Usage: solonclaw profile import <archive> [--name <name>]\n"
                    + "Default name: archive root name\n";
        }
        if ("install".equals(action)) {
            return "Usage: solonclaw profile install <source> [options]\n"
                    + "  --name <name>   Override the manifest name\n"
                    + "  --alias         Create a wrapper alias\n"
                    + "  --force         Overwrite distribution-owned files in an existing"
                    + " Profile\n"
                    + "  -y, --yes       Skip confirmation\n";
        }
        if ("update".equals(action)) {
            return "Usage: solonclaw profile update <name> [--force-config] [-y|--yes]\n"
                    + "Default: preserve local config and ask for confirmation\n";
        }
        if ("info".equals(action)) {
            return "Usage: solonclaw profile info <name>\n";
        }
        return "Unknown profile subcommand: " + action + System.lineSeparator();
    }

    /** 读取独立选项的下一个非空值。 */
    private static String requireNext(List<String> args, int index, String option) {
        if (index >= args.size()
                || args.get(index) == null
                || args.get(index).trim().length() == 0) {
            throw new BootstrapUsageException(option + " requires a value.");
        }
        return args.get(index).trim();
    }

    /** 读取 `--option=value` 形式的非空值。 */
    private static String requireInlineValue(String argument, String prefix) {
        String value = argument.substring(prefix.length()).trim();
        if (value.length() == 0) {
            throw new BootstrapUsageException(
                    prefix.substring(0, prefix.length() - 1) + " requires a value.");
        }
        return value;
    }

    /** 启动级 Profile 选项结构错误，统一映射为命令行退出码 2。 */
    private static final class BootstrapUsageException extends IllegalArgumentException {
        /** 创建启动参数用法错误。 */
        private BootstrapUsageException(String message) {
            super(message);
        }
    }

    /** 保存剥离全局选项后的参数。 */
    private static final class ParsedArguments {
        /** 显式选择的 Profile。 */
        private final String profile;

        /** 显式启动级工作区根目录。 */
        private final String workspace;

        /** 交给后续 CLI 或 Solon 的剩余参数。 */
        private final List<String> remaining;

        /** 创建已解析参数。 */
        private ParsedArguments(String profile, String workspace, List<String> remaining) {
            this.profile = profile;
            this.workspace = workspace;
            this.remaining = remaining;
        }
    }

    /** 描述启动前 Profile 处理结果。 */
    public static final class Result {
        /** 是否已经完成一次性命令、无需启动 Solon。 */
        private final boolean handled;

        /** 一次性命令退出码。 */
        private final int exitCode;

        /** 交给后续 CLI 或 Solon 的参数。 */
        private final String[] arguments;

        /** 本次实际选择的 Profile 名。 */
        private final String profileName;

        /** 创建启动前处理结果。 */
        private Result(boolean handled, int exitCode, String[] arguments, String profileName) {
            this.handled = handled;
            this.exitCode = exitCode;
            this.arguments = arguments == null ? new String[0] : arguments.clone();
            this.profileName = profileName;
        }

        /** 创建已处理结果。 */
        private static Result handled(int exitCode) {
            return new Result(true, exitCode, new String[0], null);
        }

        /** 创建继续启动结果。 */
        private static Result continueWith(String[] arguments, String profileName) {
            return new Result(false, 0, arguments, profileName);
        }

        /**
         * 判断是否已处理完成。
         *
         * @return 已处理时返回 true。
         */
        public boolean isHandled() {
            return handled;
        }

        /**
         * 返回一次性命令退出码。
         *
         * @return 退出码。
         */
        public int getExitCode() {
            return exitCode;
        }

        /**
         * 返回后续启动参数。
         *
         * @return 防御性复制的参数数组。
         */
        public String[] getArguments() {
            return arguments.clone();
        }

        /**
         * 返回本次实际选择的 Profile 名。
         *
         * @return default 或命名 Profile。
         */
        public String getProfileName() {
            return profileName;
        }
    }
}
