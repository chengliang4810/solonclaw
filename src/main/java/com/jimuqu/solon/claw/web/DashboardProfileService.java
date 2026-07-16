package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.profile.ProfileCreateOptions;
import com.jimuqu.solon.claw.profile.ProfileDescriptionService;
import com.jimuqu.solon.claw.profile.ProfileGatewayMultiplexGuard;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.profile.ProfileView;
import com.jimuqu.solon.claw.web.profile.DashboardProfileConfigFile;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 为机器级 Dashboard 提供 Profile 列表、生命周期和网关状态管理。 */
public class DashboardProfileService {
    /** 记录 Builder 后处理降级，日志只包含 Profile 名、阶段和异常类型。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardProfileService.class);

    /** SOUL.md 单次写入上限，避免机器级管理接口被超大正文拖垮。 */
    private static final int MAX_SOUL_BYTES = 2 * 1024 * 1024;

    /** macOS 仅拼接已经作为独立 argv 传入的参数，并逐项使用 AppleScript 安全引用。 */
    private static final String MAC_TERMINAL_SCRIPT =
            "on run argv\n"
                    + "set commandText to \"\"\n"
                    + "repeat with argumentValue in argv\n"
                    + "if commandText is not \"\" then set commandText to commandText & \" \"\n"
                    + "set commandText to commandText & quoted form of (contents of argumentValue)\n"
                    + "end repeat\n"
                    + "tell application \"Terminal\"\n"
                    + "activate\n"
                    + "do script commandText\n"
                    + "end tell\n"
                    + "end run";

    /** Profile 核心管理器。 */
    private final ProfileManager profileManager;

    /** 复用 Dashboard 已有的跨 Profile MCP 写入能力。 */
    private final DashboardMcpService mcpService;

    /** 复用 Dashboard 已有的跨 Profile 技能启停能力。 */
    private final DashboardSkillsService skillsService;

    /** default 进程内的命名 Profile 子运行时；删除前用于释放数据库和渠道资源。 */
    private final ProfileMultiplexRuntimeManager profileRuntimeManager;

    /**
     * 创建 Dashboard Profile 服务。
     *
     * @param profileManager Profile 核心管理器。
     */
    public DashboardProfileService(ProfileManager profileManager) {
        this(profileManager, null, null, null);
    }

    /**
     * 创建支持 Builder 后处理的 Dashboard Profile 服务。
     *
     * @param profileManager Profile 核心管理器。
     * @param mcpService 跨 Profile MCP 服务。
     * @param skillsService 跨 Profile 技能服务。
     */
    public DashboardProfileService(
            ProfileManager profileManager,
            DashboardMcpService mcpService,
            DashboardSkillsService skillsService) {
        this(profileManager, mcpService, skillsService, null);
    }

    /** 创建同时支持 Profile 子运行时释放的 Dashboard 管理服务。 */
    public DashboardProfileService(
            ProfileManager profileManager,
            DashboardMcpService mcpService,
            DashboardSkillsService skillsService,
            ProfileMultiplexRuntimeManager profileRuntimeManager) {
        this.profileManager = profileManager;
        this.mcpService = mcpService;
        this.skillsService = skillsService;
        this.profileRuntimeManager = profileRuntimeManager;
    }

    /**
     * 返回机器上的全部 Profile，以及 sticky 活动项和当前 Dashboard 运行项。
     *
     * @return 不包含凭据内容的 Profile 列表响应。
     * @throws Exception Profile 状态读取失败。
     */
    public Map<String, Object> listProfiles() throws Exception {
        List<ProfileView> views = profileManager.listProfileViews();
        List<Map<String, Object>> profiles = new ArrayList<Map<String, Object>>();
        String current = "default";
        for (ProfileView view : views) {
            profiles.add(profileMap(view));
            if (view.isCurrent()) {
                current = view.getName();
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("profiles", profiles);
        result.put("active", profileManager.activeProfile());
        result.put("current", current);
        return result;
    }

    /**
     * 返回 sticky 活动 Profile 与当前 Dashboard 实际运行 Profile。
     *
     * @return 活动与当前 Profile 名。
     * @throws Exception Profile 状态读取失败。
     */
    public Map<String, Object> activeProfile() throws Exception {
        Map<String, Object> list = listProfiles();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("active", list.get("active"));
        result.put("current", list.get("current"));
        return result;
    }

    /**
     * 返回单个 Profile 的隔离路径和运行状态。
     *
     * @param name Profile 名。
     * @return Profile 结构化视图。
     * @throws Exception Profile 不存在或状态读取失败。
     */
    public Map<String, Object> showProfile(String name) throws Exception {
        return profileMap(profileManager.profileView(name));
    }

    /**
     * 按 Dashboard 请求创建空白或克隆 Profile。
     *
     * @param body 创建参数，除基础克隆参数外还支持 provider、model、mcp_servers、keep_skills、hub_skills。
     * @return 新 Profile 视图与路径。
     * @throws Exception 参数或文件操作失败。
     */
    public Map<String, Object> createProfile(Map<String, Object> body) throws Exception {
        Map<String, Object> values = body == null ? new LinkedHashMap<String, Object>() : body;
        String name = text(values.get("name"));
        rejectNullCollection(values, "mcp_servers");
        rejectNullCollection(values, "keep_skills");
        rejectNullCollection(values, "hub_skills");
        List<Map<String, Object>> mcpServers =
                objectMapList(values.get("mcp_servers"), "mcp_servers");
        List<String> keepSkills = strictStringList(values.get("keep_skills"), "keep_skills");
        List<String> hubSkills = strictStringList(values.get("hub_skills"), "hub_skills");
        validateMcpServers(mcpServers);
        ProfileCreateOptions options =
                new ProfileCreateOptions()
                        .setClone(booleanValue(values.get("clone_from_default")))
                        .setCloneAll(booleanValue(values.get("clone_all")))
                        .setCloneFrom(nullableText(values.get("clone_from")))
                        .setNoAlias(booleanValue(values.get("no_alias")))
                        .setNoSkills(booleanValue(values.get("no_skills")))
                        .setDescription(nullableText(values.get("description")));
        Path home = profileManager.createProfile(name, options);
        boolean modelSet = false;
        String provider = text(values.get("provider"));
        String model = text(values.get("model"));
        if (provider.length() > 0 && model.length() > 0) {
            try {
                updateModel(name, provider, model);
                modelSet = true;
            } catch (Exception e) {
                logBuilderFailure(name, "model", e);
            }
        }
        int mcpWritten = writeMcpServers(name, mcpServers);
        int skillsDisabled = disableUnselectedSkills(name, keepSkills);
        List<Map<String, Object>> hubInstalls = spawnHubInstalls(name, hubSkills, home);
        Map<String, Object> result = profileMap(profileManager.profileView(name));
        result.put("ok", Boolean.TRUE);
        result.put("path", home.toString());
        result.put("model_set", Boolean.valueOf(modelSet));
        result.put("mcp_written", Integer.valueOf(mcpWritten));
        result.put("skills_disabled", Integer.valueOf(skillsDisabled));
        result.put("hub_installs", hubInstalls);
        return result;
    }

    /** 尽力写入 Builder 指定的 MCP 服务，无效或失败的单项不影响其他服务。 */
    private int writeMcpServers(String profile, List<Map<String, Object>> servers) {
        if (mcpService == null || servers.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (Map<String, Object> source : servers) {
            String name = text(source.get("name"));
            String url = text(source.get("url"));
            String command = text(source.get("command"));
            if (name.length() == 0 || (url.length() == 0 && command.length() == 0)) {
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("serverId", name);
            body.put("name", name);
            if (url.length() > 0) {
                body.put("transport", "streamable");
                body.put("endpoint", url);
            } else {
                body.put("transport", "stdio");
                body.put("command", command);
                if (source.get("args") != null) {
                    body.put("args", source.get("args"));
                }
            }
            Map<String, Object> auth = new LinkedHashMap<String, Object>();
            if (source.get("env") != null) {
                auth.put("env", source.get("env"));
            }
            String authMode = text(source.get("auth"));
            if (authMode.length() > 0) {
                auth.put("type", authMode);
            }
            if (!auth.isEmpty()) {
                body.put("auth", auth);
            }
            try {
                mcpService.save(profile, body);
                written++;
            } catch (Exception e) {
                logBuilderFailure(profile, "mcp", e);
            }
        }
        return written;
    }

    /** 非空保留列表使用替换语义，禁用目标 Profile 中未被保留且当前启用的技能。 */
    private int disableUnselectedSkills(String profile, List<String> keepSkills) {
        if (skillsService == null || keepSkills.isEmpty()) {
            return 0;
        }
        java.util.Set<String> keep = new java.util.LinkedHashSet<String>(keepSkills);
        int disabled = 0;
        try {
            for (Map<String, Object> skill : skillsService.getSkills(profile)) {
                String name = text(skill.get("name"));
                if (name.length() > 0
                        && !keep.contains(name)
                        && Boolean.TRUE.equals(skill.get("enabled"))) {
                    skillsService.toggleSkill(profile, name, false);
                    disabled++;
                }
            }
        } catch (Exception e) {
            logBuilderFailure(profile, "skills", e);
            return 0;
        }
        return disabled;
    }

    /** 为每个非空 Hub 标识启动独立 Profile 命令，并保留单项启动失败。 */
    private List<Map<String, Object>> spawnHubInstalls(
            String profile, List<String> identifiers, Path home) {
        List<Map<String, Object>> installs = new ArrayList<Map<String, Object>>();
        for (String identifier : identifiers) {
            String normalized = text(identifier);
            if (normalized.length() == 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("identifier", normalized);
            try {
                item.put("pid", spawnHubInstall(profile, normalized, home));
            } catch (Exception e) {
                logBuilderFailure(profile, "hub-install", e);
                item.put("pid", null);
            }
            installs.add(item);
        }
        return installs;
    }

    /** 使用当前 jar 或类路径启动一次性 Skills Hub 安装；protected 仅供进程边界测试替换。 */
    protected Long spawnHubInstall(String profile, String identifier, Path home) throws Exception {
        List<String> command = applicationLaunchCommand();
        command.add("--profile");
        command.add(profile);
        command.add("skills");
        command.add("install");
        command.add(identifier);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(System.getProperty("user.dir", ".")));
        builder.redirectErrorStream(true);
        Path logFile = home.resolve("logs/profile-builder-hub.log");
        Files.createDirectories(logFile.getParent());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = builder.start();
        return processId(process);
    }

    /** 构建与当前 Dashboard 进程一致的 Java 应用启动前缀。 */
    private List<String> applicationLaunchCommand() throws Exception {
        List<String> command = new ArrayList<String>();
        Path java =
                Paths.get(
                                System.getProperty("java.home", ""),
                                "bin",
                                System.getProperty("os.name", "")
                                                .toLowerCase(Locale.ROOT)
                                                .contains("win")
                                        ? "java.exe"
                                        : "java")
                        .toAbsolutePath()
                        .normalize();
        if (!Files.isRegularFile(java)) {
            throw new IOException("Java runtime executable was not found.");
        }
        command.add(java.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsolonclaw.profile.root=" + profileManager.root());
        URI location =
                DashboardProfileService.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI();
        Path codeSource = Paths.get(location).toAbsolutePath().normalize();
        if (Files.isRegularFile(codeSource)
                && codeSource.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            command.add("-jar");
            command.add(codeSource.toString());
        } else {
            String classPath = nullableText(System.getProperty("java.class.path"));
            if (classPath == null) {
                throw new IOException("Current Java classpath is unavailable.");
            }
            command.add("-cp");
            command.add(classPath);
            command.add("com.jimuqu.solon.claw.SolonClawApp");
        }
        return command;
    }

    /** Java 8 编译目标下通过反射读取 Java 9+ Process.pid。 */
    private Long processId(Process process) {
        try {
            Method method = Process.class.getMethod("pid");
            Object value = method.invoke(process);
            return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 记录不包含请求正文和凭据的 Builder 降级信息。 */
    private void logBuilderFailure(String profile, String stage, Exception error) {
        log.warn(
                "Profile Builder post-processing failed. profile={} stage={} error={}",
                profile,
                stage,
                error == null ? "unknown" : error.getClass().getSimpleName());
    }

    /**
     * 写入或清空人工维护的 Profile 职责说明。
     *
     * @param name Profile 名。
     * @param description 人工说明；空字符串表示清空。
     * @return 与外部对标 Dashboard 一致的说明状态。
     * @throws Exception Profile 不存在或元数据写入失败。
     */
    public Map<String, Object> updateDescription(String name, String description) throws Exception {
        String normalized = description == null ? "" : description.trim();
        ProfileView view = profileManager.setDescription(name, normalized);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("description", view.getDescription());
        result.put("description_auto", Boolean.FALSE);
        return result;
    }

    /**
     * 使用目标 Profile 自身模型和技能自动生成职责说明。
     *
     * @param name Profile 名。
     * @param overwrite 是否覆盖人工说明。
     * @return 成功状态、失败原因和生成后的说明。
     */
    public Map<String, Object> describeAutomatically(String name, boolean overwrite) {
        ProfileDescriptionService.DescribeOutcome outcome =
                profileManager.describeProfile(name, overwrite);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.valueOf(outcome.isSuccess()));
        result.put("reason", outcome.getReason());
        result.put("description", outcome.getDescription());
        result.put("description_auto", Boolean.valueOf(outcome.isSuccess()));
        return result;
    }

    /**
     * 读取 Profile 的 SOUL.md，缺失文件返回空内容而不是创建文件。
     *
     * @param name Profile 名。
     * @return 内容和存在标记。
     * @throws Exception Profile 不存在或文件读取失败。
     */
    public Map<String, Object> readSoul(String name) throws Exception {
        Path soul = profileManager.requireProfileHome(name).resolve("SOUL.md");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(
                "content",
                Files.isRegularFile(soul)
                        ? new String(Files.readAllBytes(soul), StandardCharsets.UTF_8)
                        : "");
        result.put("exists", Boolean.valueOf(Files.isRegularFile(soul)));
        return result;
    }

    /**
     * 原子写入 Profile 的 SOUL.md。
     *
     * @param name Profile 名。
     * @param content 完整文件正文。
     * @return 写入结果。
     * @throws Exception Profile 不存在、正文超限或文件写入失败。
     */
    public Map<String, Object> updateSoul(String name, String content) throws Exception {
        Path home = profileManager.requireProfileHome(name);
        String value = content == null ? "" : content;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SOUL_BYTES) {
            throw new IllegalArgumentException("Profile SOUL.md exceeds the two MiB limit.");
        }
        writeAtomically(home.resolve("SOUL.md"), bytes);
        return Collections.<String, Object>singletonMap("ok", Boolean.TRUE);
    }

    /**
     * 原子写入目标 Profile 的默认模型并清零模型相关上下文窗口强制值，不修改其他 Profile。
     *
     * @param name Profile 名。
     * @param provider Provider 键。
     * @param model 模型标识。
     * @return 已写入的模型配置。
     * @throws Exception Profile 不存在或参数无效。
     */
    public Map<String, Object> updateModel(String name, String provider, String model)
            throws Exception {
        String providerKey = requiredText(provider, "Profile provider is required.");
        String modelName = requiredText(model, "Profile model is required.");
        Path home = profileManager.requireProfileHome(name);
        DashboardProfileConfigFile config =
                new DashboardProfileConfigFile(home.resolve("config.yml"));
        Map<String, Object> root = config.readRoot();
        Map<String, Object> modelConfig = stringObjectMap(root.get("model"));
        modelConfig.put("providerKey", providerKey);
        modelConfig.put("default", modelName);
        root.put("model", modelConfig);
        Map<String, Object> application = stringObjectMap(root.get("solonclaw"));
        Map<String, Object> llm = stringObjectMap(application.get("llm"));
        llm.put("contextWindowTokens", Integer.valueOf(0));
        application.put("llm", llm);
        root.put("solonclaw", application);
        config.writeRoot(root);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("provider", providerKey);
        result.put("model", modelName);
        return result;
    }

    /** 校验终端工作目录仍是管理器解析出的直接 Profile 目录，拒绝命名 Profile 符号链接。 */
    private Path validatedTerminalHome(ProfileView view) {
        Path home = view.getHome().toAbsolutePath().normalize();
        Path root = profileManager.root().toAbsolutePath().normalize();
        if ("default".equals(view.getName())) {
            if (!home.equals(root)) {
                throw new IllegalArgumentException("Invalid default Profile path.");
            }
            return home;
        }
        Path profiles = root.resolve("profiles").normalize();
        if (!home.getParent().equals(profiles)
                || Files.isSymbolicLink(home)
                || !Files.isDirectory(home, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Invalid named Profile path.");
        }
        return home;
    }

    /** 根据当前平台选择终端模拟器；需要 shell 时只传入逐参数安全引用的内部命令。 */
    protected void launchProfileTerminal(Path home, List<String> applicationCommand)
            throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<List<String>> candidates = terminalCandidates(home, applicationCommand, os);
        for (List<String> command : candidates) {
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(home.toFile());
                builder.redirectErrorStream(true);
                Path logFile = home.resolve("logs/profile-terminal.log");
                Files.createDirectories(logFile.getParent());
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                builder.start();
                return;
            } catch (IOException unavailable) {
                // 继续探测下一个终端模拟器，最终统一返回稳定的 400 错误。
            }
        }
        throw new IllegalArgumentException("No supported terminal emulator found.");
    }

    /**
     * 按上游顺序构建当前平台的终端候选；protected 仅供候选矩阵测试读取，不启动任何进程。
     *
     * @param home Profile 工作目录，用于生成 Windows 安全启动脚本。
     * @param applicationCommand Java 应用及其独立参数。
     * @param os 当前操作系统小写名称。
     * @return 按探测优先级排列的终端启动命令。
     * @throws IOException Windows 启动脚本创建失败。
     */
    protected List<List<String>> terminalCandidates(
            Path home, List<String> applicationCommand, String os) throws IOException {
        List<List<String>> candidates = new ArrayList<List<String>>();
        if (os.contains("mac")) {
            List<String> command =
                    new ArrayList<String>(Arrays.asList("osascript", "-e", MAC_TERMINAL_SCRIPT));
            command.addAll(applicationCommand);
            candidates.add(command);
        } else if (os.contains("win")) {
            candidates.add(windowsCmdStartCommand(home, applicationCommand));
        } else {
            String shellCommand = posixApplicationCommand(applicationCommand);
            candidates.add(
                    terminalCommand(
                            Arrays.asList("x-terminal-emulator", "-e", "sh", "-lc"),
                            Collections.singletonList(shellCommand)));
            candidates.add(
                    terminalCommand(
                            Arrays.asList("gnome-terminal", "--", "sh", "-lc"),
                            Collections.singletonList(shellCommand)));
            candidates.add(
                    terminalCommand(
                            Arrays.asList("konsole", "-e", "sh", "-lc"),
                            Collections.singletonList(shellCommand)));
            candidates.add(singleStringTerminalCommand("xfce4-terminal", shellCommand));
            candidates.add(singleStringTerminalCommand("mate-terminal", shellCommand));
            candidates.add(singleStringTerminalCommand("lxterminal", shellCommand));
            candidates.add(
                    terminalCommand(
                            Arrays.asList("tilix", "-e", "sh", "-lc"),
                            Collections.singletonList(shellCommand)));
            candidates.add(
                    terminalCommand(
                            Arrays.asList("alacritty", "-e", "sh", "-lc"),
                            Collections.singletonList(shellCommand)));
            candidates.add(
                    terminalCommand(
                            Arrays.asList("kitty", "sh", "-lc"),
                            Collections.singletonList(shellCommand)));
            candidates.add(
                    terminalCommand(
                            Arrays.asList("xterm", "-e", "sh", "-lc"),
                            Collections.singletonList(shellCommand)));
        }
        return candidates;
    }

    /** 把终端模拟器前缀与应用 argv 合并，始终保持每个参数独立。 */
    private List<String> terminalCommand(List<String> prefix, List<String> applicationCommand) {
        List<String> command = new ArrayList<String>(prefix);
        command.addAll(applicationCommand);
        return command;
    }

    /** 为只接受单一命令字符串的 Linux 终端生成逐参数引用的 sh 启动命令。 */
    private List<String> singleStringTerminalCommand(String executable, String shellCommand) {
        return Arrays.asList(executable, "-e", "sh -lc " + posixQuote(shellCommand));
    }

    /** 把应用 argv 转为只由单引号引用参数组成的 POSIX 命令文本。 */
    private String posixApplicationCommand(List<String> applicationCommand) {
        StringBuilder command = new StringBuilder("exec");
        for (String argument : applicationCommand) {
            command.append(' ').append(posixQuote(argument));
        }
        return command.toString();
    }

    /** 使用 POSIX 单引号规则引用单个参数，避免终端的命令字符串重新解释用户输入。 */
    private String posixQuote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\"'\"'") + "'";
    }

    /** 生成不含原始应用参数的 Windows cmd start 兜底，参数仅写入逐项引用的临时脚本。 */
    private List<String> windowsCmdStartCommand(Path home, List<String> applicationCommand)
            throws IOException {
        Path logs = home.resolve("logs");
        Files.createDirectories(logs);
        Path script = Files.createTempFile(logs, "profile-terminal-", ".cmd");
        StringBuilder body = new StringBuilder("@echo off\r\nsetlocal DisableDelayedExpansion\r\n");
        for (int i = 0; i < applicationCommand.size(); i++) {
            if (i > 0) {
                body.append(' ');
            }
            body.append(windowsBatchQuote(applicationCommand.get(i)));
        }
        body.append("\r\ndel /f /q \"%~f0\" >nul 2>&1\r\n");
        Files.write(script, body.toString().getBytes(StandardCharsets.UTF_8));
        script.toFile().deleteOnExit();
        return Arrays.asList("cmd.exe", "/c", "start", "", script.toString());
    }

    /** 按 Windows 命令行和批处理变量规则引用一个参数。 */
    private String windowsBatchQuote(String value) {
        String input = String.valueOf(value);
        if (input.indexOf('\u0000') >= 0 || input.indexOf('\r') >= 0 || input.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Terminal command contains an invalid character.");
        }
        input = input.replace("%", "%%");
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '\\') {
                backslashes++;
                continue;
            }
            if (current == '"') {
                appendBackslashes(quoted, backslashes * 2 + 1);
                quoted.append('"');
            } else {
                appendBackslashes(quoted, backslashes);
                quoted.append(current);
            }
            backslashes = 0;
        }
        appendBackslashes(quoted, backslashes * 2);
        return quoted.append('"').toString();
    }

    /** 向 Windows 参数引用结果追加指定数量的反斜杠。 */
    private void appendBackslashes(StringBuilder target, int count) {
        for (int i = 0; i < count; i++) {
            target.append('\\');
        }
    }

    /** 创建或刷新 Profile 快捷命令别名。 */
    public Map<String, Object> createAlias(String name, String alias) throws Exception {
        return profileMap(profileManager.createProfileAlias(name, nullableText(alias)));
    }

    /** 删除 Profile 快捷命令别名。 */
    public Map<String, Object> removeAlias(String name, String alias) throws Exception {
        return profileMap(profileManager.removeProfileAlias(name, nullableText(alias)));
    }

    /** 返回 Profile 分发清单的非密信息。 */
    public Map<String, Object> distributionInfo(String name) throws Exception {
        return profileManager.distributionInfo(name);
    }

    /** 从本地目录或 Git 地址安装 Profile 分发。 */
    public Map<String, Object> installDistribution(Map<String, Object> body) throws Exception {
        Map<String, Object> values = body == null ? Collections.<String, Object>emptyMap() : body;
        String source =
                requiredText(values.get("source"), "Profile distribution source is required.");
        ProfileView view =
                profileManager.installDistribution(
                        source,
                        nullableText(values.get("name")),
                        booleanValue(values.get("alias")),
                        booleanValue(values.get("force")));
        return profileMap(view);
    }

    /** 更新已由分发安装的 Profile，并默认保留本机模型配置。 */
    public Map<String, Object> updateDistribution(String name, boolean forceConfig)
            throws Exception {
        return profileMap(profileManager.updateDistribution(name, forceConfig));
    }

    /**
     * 设置未来 CLI 与网关启动默认使用的 sticky Profile，不重定向当前 Dashboard 进程。
     *
     * @param name Profile 名。
     * @return 更新后的活动 Profile 信息。
     * @throws Exception Profile 不存在或活动标记写入失败。
     */
    public Map<String, Object> useProfile(String name) throws Exception {
        profileManager.useProfile(name);
        return activeProfile();
    }

    /**
     * 重命名非默认 Profile。
     *
     * @param name 原 Profile 名。
     * @param newName 新 Profile 名。
     * @return 重命名后的 Profile 视图。
     * @throws Exception 校验、网关停止或文件移动失败。
     */
    public Map<String, Object> renameProfile(String name, String newName) throws Exception {
        profileManager.renameProfile(name, newName);
        return profileMap(profileManager.profileView(newName));
    }

    /**
     * 删除受允许的命名 Profile。
     *
     * @param name Profile 名。
     * @return 被删除 Profile 的原路径。
     * @throws Exception Profile 受保护或删除失败。
     */
    public Map<String, Object> deleteProfile(String name) throws Exception {
        if (profileRuntimeManager != null && !"default".equalsIgnoreCase(text(name))) {
            profileRuntimeManager.releaseRuntime(name);
        }
        Path deleted = profileManager.deleteProfile(name);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("path", deleted.toString());
        return result;
    }

    /**
     * 导出不包含明文凭据的 Profile 归档。
     *
     * @param name Profile 名。
     * @return 等待 HTTP 附件响应消费的临时 tar.gz。
     * @throws Exception 导出失败。
     */
    public Path exportProfile(String name) throws Exception {
        Path output = Files.createTempFile("solonclaw-profile-export-", ".tar.gz");
        output.toFile().deleteOnExit();
        return profileManager.exportProfile(name, output);
    }

    /**
     * 从已上传的安全 tar.gz 创建命名 Profile。
     *
     * @param archive 服务端临时归档路径。
     * @param name 可选目标名称。
     * @return 导入后的 Profile 视图。
     * @throws Exception 归档、名称或文件校验失败。
     */
    public Map<String, Object> importProfile(Path archive, String name) throws Exception {
        Path home = profileManager.importProfile(archive, nullableText(name));
        return profileMap(profileManager.profileView(home.getFileName().toString()));
    }

    /**
     * 返回单个 Profile 独立网关的真实进程状态。
     *
     * @param name Profile 名。
     * @return 网关 PID、状态文件和日志路径。
     * @throws Exception Profile 不存在或状态读取失败。
     */
    public Map<String, Object> gatewayStatus(String name) throws Exception {
        return profileManager.gatewayStatus(name).toMap();
    }

    /** 启动指定 Profile 的独立网关，并返回完成后的真实状态。 */
    public Map<String, Object> startGateway(String name, Map<String, Object> body)
            throws Exception {
        ProfileGatewayMultiplexGuard.requireIndependentGatewayAllowed(
                profileManager, name, booleanValue(body == null ? null : body.get("force")));
        profileManager.startGateway(name, stringList(body == null ? null : body.get("args")));
        return profileManager.gatewayStatus(name).toMap();
    }

    /** 停止指定 Profile 的独立网关，并返回完成后的真实状态。 */
    public Map<String, Object> stopGateway(String name) throws Exception {
        profileManager.stopGateway(name);
        return profileManager.gatewayStatus(name).toMap();
    }

    /** 重启指定 Profile 的独立网关，并返回完成后的真实状态。 */
    public Map<String, Object> restartGateway(String name, Map<String, Object> body)
            throws Exception {
        ProfileGatewayMultiplexGuard.requireIndependentGatewayAllowed(
                profileManager, name, booleanValue(body == null ? null : body.get("force")));
        profileManager.stopGateway(name);
        profileManager.startGateway(name, stringList(body == null ? null : body.get("args")));
        return profileManager.gatewayStatus(name).toMap();
    }

    /** 将请求正文中的字符串数组转换为网关附加参数。 */
    private static List<String> stringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof Iterable)) {
            throw new IllegalArgumentException("Profile gateway args must be a string list.");
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (Iterable<?>) value) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (text.length() > 0) {
                result.add(text);
            }
        }
        return result;
    }

    /** 严格解析 Builder 的字符串数组，避免对象被静默转成无效标识。 */
    private static List<String> strictStringList(Object value, String field) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof Iterable)) {
            throw new IllegalArgumentException("Profile " + field + " must be a string list.");
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (Iterable<?>) value) {
            if (item == null) {
                throw new IllegalArgumentException("Profile " + field + " must be a string list.");
            }
            if (!(item instanceof String)) {
                throw new IllegalArgumentException("Profile " + field + " must be a string list.");
            }
            result.add(text(item));
        }
        return result;
    }

    /** 严格解析 Builder 的对象数组，返回与请求映射隔离的浅拷贝。 */
    private static List<Map<String, Object>> objectMapList(Object value, String field) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof Iterable)) {
            throw new IllegalArgumentException("Profile " + field + " must be an object list.");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object item : (Iterable<?>) value) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("Profile " + field + " must be an object list.");
            }
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            result.add(copy);
        }
        return result;
    }

    /** 把配置中的对象映射复制为字符串键映射；非对象值按空映射处理。 */
    private static Map<String, Object> stringObjectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /** 校验 MCP Builder 嵌套字段类型，错误请求不得先创建 Profile 再失败。 */
    private static void validateMcpServers(List<Map<String, Object>> servers) {
        for (Map<String, Object> server : servers) {
            requireStringField(server, "name", false);
            requireStringField(server, "url", true);
            requireStringField(server, "command", true);
            requireStringField(server, "auth", true);
            if (server.containsKey("args") && server.get("args") == null) {
                throw new IllegalArgumentException(
                        "Profile mcp_servers.args must be a string list.");
            }
            strictStringList(server.get("args"), "mcp_servers.args");
            Object env = server.get("env");
            if (server.containsKey("env") && env == null) {
                throw new IllegalArgumentException("Profile mcp_servers.env must be an object.");
            }
            if (env != null && !(env instanceof Map)) {
                throw new IllegalArgumentException("Profile mcp_servers.env must be an object.");
            }
            if (env instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) env).entrySet()) {
                    if (!(entry.getKey() instanceof String)
                            || !(entry.getValue() instanceof String)) {
                        throw new IllegalArgumentException(
                                "Profile mcp_servers.env must contain string values.");
                    }
                }
            }
        }
    }

    /** 显式 null 不等同于字段缺失，保持 Builder 数组字段的请求模型约束。 */
    private static void rejectNullCollection(Map<String, Object> values, String field) {
        if (values.containsKey(field) && values.get(field) == null) {
            throw new IllegalArgumentException("Profile " + field + " must be a list.");
        }
    }

    /** 校验 MCP 文本字段；可选字段缺失或为 null 时保持默认值。 */
    private static void requireStringField(
            Map<String, Object> server, String field, boolean optional) {
        if (!server.containsKey(field)) {
            if (optional) {
                return;
            }
            throw new IllegalArgumentException("Profile mcp_servers." + field + " is required.");
        }
        Object value = server.get(field);
        if (value == null && optional) {
            return;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    "Profile mcp_servers." + field + " must be a string.");
        }
    }

    /** 将通用值转换为布尔选项。 */
    private static boolean booleanValue(Object value) {
        return value instanceof Boolean
                ? ((Boolean) value).booleanValue()
                : "true".equalsIgnoreCase(text(value));
    }

    /** 将通用值转换为去空白文本。 */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 将空白值转换为 null。 */
    private static String nullableText(Object value) {
        String text = text(value);
        return text.length() == 0 ? null : text;
    }

    /** 将必填值转换为非空文本。 */
    private static String requiredText(Object value, String message) {
        String result = text(value);
        if (result.length() == 0) {
            throw new IllegalArgumentException(message);
        }
        return result;
    }

    /** 统一补充 Profile 元数据中用于前端提示的自动说明标记。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> profileMap(ProfileView view) throws IOException {
        Map<String, Object> result = view.toMap();
        Map<String, Object> root =
                new DashboardProfileConfigFile(view.getHome().resolve("config.yml")).readRoot();
        Map<String, Object> modelConfig = stringObjectMap(root.get("model"));
        String provider = text(modelConfig.get("providerKey"));
        String model = text(modelConfig.get("default"));
        Map<String, Object> providers = stringObjectMap(root.get("providers"));
        if (model.length() == 0 && provider.length() > 0) {
            model = text(stringObjectMap(providers.get(provider)).get("defaultModel"));
        }
        if (provider.length() == 0 || model.length() == 0) {
            String combined = view.getModel();
            int separator = combined.indexOf('/');
            if (separator > 0) {
                if (provider.length() == 0) {
                    provider = combined.substring(0, separator);
                }
                if (model.length() == 0 && separator + 1 < combined.length()) {
                    model = combined.substring(separator + 1);
                }
            } else if (provider.length() == 0) {
                provider = combined;
            }
        }
        result.put("provider", provider);
        result.put("model", model);
        Path metadata = view.getHome().resolve(".profile.json");
        boolean automatic = false;
        if (Files.isRegularFile(metadata)) {
            try {
                Map<String, Object> values =
                        org.noear.snack4.ONode.deserialize(
                                new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8),
                                LinkedHashMap.class);
                automatic = Boolean.TRUE.equals(values.get("description_auto"));
            } catch (RuntimeException ignored) {
                automatic = false;
            }
        }
        result.put("description_auto", Boolean.valueOf(automatic));
        return result;
    }

    /** 通过同目录临时文件原子替换文本资源。 */
    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
