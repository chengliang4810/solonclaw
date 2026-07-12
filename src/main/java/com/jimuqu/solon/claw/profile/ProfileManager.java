package com.jimuqu.solon.claw.profile;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService;
import com.jimuqu.solon.claw.support.RuntimePathSupport;
import com.jimuqu.solon.claw.support.RuntimeProcessSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 管理多个完全独立的 Profile 工作区，并承载 profile 顶层命令。 */
public class ProfileManager {
    /** 命名 Profile 的安全标识格式。 */
    private static final Pattern PROFILE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    /** 不能作为 Profile 名或命令别名的保留名称。 */
    private static final Set<String> RESERVED_NAMES =
            unmodifiableSet("solonclaw", "default", "test", "tmp", "root", "sudo");

    /** 不能被 Profile 快捷别名覆盖的 solonclaw 顶层命令。 */
    private static final Set<String> TOP_LEVEL_COMMANDS =
            unmodifiableSet(
                    "cli",
                    "tui",
                    "completion",
                    "profile",
                    "model",
                    "models",
                    "setup",
                    "gateway",
                    "config",
                    "doctor",
                    "status",
                    "logout",
                    "version",
                    "pairing",
                    "session",
                    "sessions");

    /** 新 Profile 必须预建的独立运行目录；同时保留当前 Java 运行面需要的目录。 */
    private static final List<String> PROFILE_DIRS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "memories",
                            "sessions",
                            "skills",
                            "skins",
                            "logs",
                            "plans",
                            "workspace",
                            "cron",
                            "home",
                            "context",
                            "cache",
                            "data",
                            "memory",
                            "artifacts"));

    /** `--clone` 复制的 Profile 根文件。 */
    private static final List<String> CLONE_FILES =
            Collections.unmodifiableList(
                    Arrays.asList("config.yml", ".env", "SOUL.md", "USER.md", "MEMORY.md"));

    /** `--clone-all` 对任意来源都排除的根历史路径。 */
    private static final Set<String> CLONE_ALL_HISTORY_EXCLUDED =
            unmodifiableSet(
                    "state.db",
                    "state.db-wal",
                    "state.db-shm",
                    "data/state.db",
                    "data/state.db-wal",
                    "data/state.db-shm",
                    "sessions",
                    "backups",
                    "state-snapshots",
                    "checkpoints",
                    "logs",
                    "forensics");

    /** `--clone-all` 仅从 default 来源复制时排除的宿主基础设施。 */
    private static final Set<String> CLONE_ALL_DEFAULT_EXCLUDED =
            unmodifiableSet("solonclaw", ".worktrees", "profiles", "bin", "node_modules");

    /** default Profile 导出允许进入便携归档的根路径。 */
    private static final Set<String> DEFAULT_EXPORT_INCLUDED =
            unmodifiableSet(
                    "config.yml",
                    "SOUL.md",
                    "MEMORY.md",
                    "USER.md",
                    "todo.json",
                    "system_prompt.md",
                    "AGENTS.md",
                    "IDENTITY.md",
                    "TOOLS.md",
                    "HEARTBEAT.md",
                    "skills",
                    "cron",
                    "scripts",
                    "sessions",
                    "plugins",
                    "memories",
                    "memory",
                    "knowledge",
                    "preferences",
                    "workspace",
                    ".no-bundled-skills");

    /** 分发安装永远不能覆盖的用户数据和凭据路径。 */
    private static final Set<String> DISTRIBUTION_USER_DATA =
            unmodifiableSet(
                    ".git",
                    ".env",
                    ".profile.json",
                    "active_profile",
                    "profiles",
                    "data",
                    "sessions",
                    "memory",
                    "logs",
                    "cache",
                    "artifacts",
                    "backups",
                    "state-snapshots",
                    "checkpoints",
                    "local",
                    "MEMORY.md",
                    "USER.md",
                    "processes.json",
                    "gateway.pid",
                    "gateway_state.json");

    /** 分发清单未声明 distribution_owned 时采用的当前项目默认所有权路径。 */
    private static final List<String> DEFAULT_DISTRIBUTION_OWNED =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "SOUL.md",
                            "IDENTITY.md",
                            "AGENTS.md",
                            "TOOLS.md",
                            "HEARTBEAT.md",
                            "config.yml",
                            "mcp.json",
                            "skills",
                            "cron",
                            "distribution.yaml"));

    /** 活动 Profile 标记文件名。 */
    private static final String ACTIVE_FILE = "active_profile";

    /** Profile 本机元数据文件名。 */
    private static final String METADATA_FILE = ".profile.json";

    /** Profile 分发清单文件名。 */
    private static final String MANIFEST_FILE = "distribution.yaml";

    /** 分发清单中声明当前应用最低或比较版本的字段。 */
    private static final String DISTRIBUTION_REQUIRES_FIELD = "solonclaw_requires";

    /** 分发作者提供的环境变量模板文件。 */
    private static final String ENV_TEMPLATE_FILE = ".env.template";

    /** 安装后供用户复制的环境变量示例文件。 */
    private static final String ENV_EXAMPLE_FILE = ".env.example";

    /** 明确禁止内置技能初始化的 Profile 标记。 */
    private static final String NO_BUNDLED_SKILLS_FILE = ".no-bundled-skills";

    /** 每个 Profile 后台网关的合并日志相对路径。 */
    private static final String GATEWAY_LOG_FILE = "logs/gateway.log";

    /** 所有 Profile 共用的网关启动锁，避免跨进程端口分配与启动竞争。 */
    private static final String GATEWAY_START_LOCK_FILE = "profiles/.gateway-start.lock";

    /** 后台网关启动等待上限。 */
    private static final long GATEWAY_START_TIMEOUT_MILLIS = 30000L;

    /** 默认 Profile 根目录。 */
    private final Path root;

    /** 命名 Profile 父目录。 */
    private final Path profilesRoot;

    /** Profile 快捷命令目录。 */
    private final Path wrapperDir;

    /** 快捷命令调用的 solonclaw 启动命令。 */
    private final String launcher;

    /** 自动描述服务；测试可注入本地模型替身。 */
    private final ProfileDescriptionService profileDescriptionService;

    /** 当前发行版内置技能同步器。 */
    private final ProfileBundledSkillSeeder bundledSkillSeeder;

    /**
     * 创建 Profile 管理器。
     *
     * @param root 默认 Profile 工作区根目录。
     * @param wrapperDir 快捷命令写入目录。
     * @param launcher 快捷命令调用的 solonclaw 命令或绝对路径。
     */
    public ProfileManager(Path root, Path wrapperDir, String launcher) {
        this(
                root,
                wrapperDir,
                launcher,
                new ProfileDescriptionService(),
                ProfileBundledSkillSeeder.discover());
    }

    /**
     * 创建可注入自动描述服务的 Profile 管理器。
     *
     * @param root 默认 Profile 工作区根目录。
     * @param wrapperDir 快捷命令写入目录。
     * @param launcher 快捷命令调用的 solonclaw 命令或绝对路径。
     * @param profileDescriptionService 自动描述服务。
     */
    ProfileManager(
            Path root,
            Path wrapperDir,
            String launcher,
            ProfileDescriptionService profileDescriptionService) {
        this(
                root,
                wrapperDir,
                launcher,
                profileDescriptionService,
                ProfileBundledSkillSeeder.discover());
    }

    /** 创建可注入自动描述与内置技能同步器的 Profile 管理器。 */
    ProfileManager(
            Path root,
            Path wrapperDir,
            String launcher,
            ProfileDescriptionService profileDescriptionService,
            ProfileBundledSkillSeeder bundledSkillSeeder) {
        if (root == null || wrapperDir == null) {
            throw new IllegalArgumentException("Profile root and wrapper directory are required.");
        }
        if (profileDescriptionService == null) {
            throw new IllegalArgumentException("Profile description service is required.");
        }
        if (bundledSkillSeeder == null) {
            throw new IllegalArgumentException("Profile bundled skill seeder is required.");
        }
        this.root = root.toAbsolutePath().normalize();
        this.profilesRoot = this.root.resolve("profiles");
        this.wrapperDir = wrapperDir.toAbsolutePath().normalize();
        this.launcher =
                launcher == null || launcher.trim().length() == 0 ? "solonclaw" : launcher.trim();
        this.profileDescriptionService = profileDescriptionService;
        this.bundledSkillSeeder = bundledSkillSeeder;
    }

    /**
     * 根据当前进程启动属性创建 Profile 管理器。
     *
     * @return 可解析当前和兄弟 Profile 的管理器。
     */
    public static ProfileManager current() {
        Path currentRoot = resolveRoot(null);
        Path home = Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        return new ProfileManager(
                currentRoot,
                home.resolve(".local").resolve("bin"),
                "solonclaw",
                new ProfileDescriptionService(),
                ProfileBundledSkillSeeder.discover());
    }

    /**
     * 执行 profile 子命令。
     *
     * @param args `profile` 后的参数。
     * @param selectedProfile 本次命令已选择的 Profile，用作 clone 默认来源。
     * @param input 确认提示输入流。
     * @param out 标准输出。
     * @param err 标准错误。
     * @return 进程语义退出码。
     */
    public int execute(
            List<String> args,
            String selectedProfile,
            InputStream input,
            PrintStream out,
            PrintStream err) {
        try {
            List<String> values = args == null ? Collections.<String>emptyList() : args;
            if (values.isEmpty()) {
                showActiveProfileStatus(selectedProfile, out);
                return 0;
            }
            if (isHelp(values.get(0))) {
                out.print(help());
                return 0;
            }
            String action = values.get(0).trim();
            if ("list".equals(action)) {
                requirePositionals(
                        parseCommandArguments(values, 1, action, noOptions(), noOptions()),
                        0,
                        0,
                        "profile list");
                listProfiles(out);
            } else if ("use".equals(action)) {
                ParsedCommandArguments parsed =
                        parseCommandArguments(values, 1, action, noOptions(), noOptions());
                requirePositionals(parsed, 1, 1, "profile use <name>");
                String name = parsed.positionals.get(0);
                setActiveProfile(name);
                out.println("Active profile: " + normalizeName(name));
            } else if ("create".equals(action)) {
                createProfileCommand(values, selectedProfile, out);
            } else if ("describe".equals(action)) {
                return describeProfileCommand(values, out, err);
            } else if ("show".equals(action)) {
                ParsedCommandArguments parsed =
                        parseCommandArguments(values, 1, action, noOptions(), noOptions());
                requirePositionals(parsed, 1, 1, "profile show <name>");
                showProfileCommand(parsed.positionals.get(0), out);
            } else if ("rename".equals(action)) {
                ParsedCommandArguments parsed =
                        parseCommandArguments(values, 1, action, noOptions(), noOptions());
                requirePositionals(parsed, 2, 2, "profile rename <old> <new>");
                renameProfileCommand(parsed.positionals.get(0), parsed.positionals.get(1), out);
            } else if ("delete".equals(action)) {
                deleteProfileCommand(values, input, out);
            } else if ("alias".equals(action)) {
                manageAlias(values, out);
            } else if ("export".equals(action)) {
                exportProfileCommand(values, out);
            } else if ("import".equals(action)) {
                importProfileCommand(values, out);
            } else if ("install".equals(action)) {
                installProfile(values, input, out);
            } else if ("update".equals(action)) {
                updateProfile(values, input, out);
            } else if ("info".equals(action)) {
                ParsedCommandArguments parsed =
                        parseCommandArguments(values, 1, action, noOptions(), noOptions());
                requirePositionals(parsed, 1, 1, "profile info <name>");
                showDistributionInfo(parsed.positionals.get(0), out);
            } else {
                throw new ProfileUsageException("Unknown profile subcommand: " + action);
            }
            return 0;
        } catch (ProfileUsageException e) {
            err.println("Profile error: " + SecretRedactor.redact(e.getMessage(), 2000));
            return 2;
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.trim().length() == 0) {
                message = e.getClass().getSimpleName();
            }
            err.println("Profile error: " + SecretRedactor.redact(message, 2000));
            return 1;
        }
    }

    /**
     * 返回指定 Profile 的工作区路径，不要求目录已经存在。
     *
     * @param name Profile 名。
     * @return default 根目录或 profiles/name 目录。
     */
    public Path profileHome(String name) {
        String normalized = normalizeName(name);
        validateProfileName(normalized);
        return "default".equals(normalized) ? root : profilesRoot.resolve(normalized).normalize();
    }

    /**
     * 返回已存在 Profile 的工作区路径。
     *
     * @param name Profile 名。
     * @return 已存在的独立 Profile 工作区。
     * @throws IOException 命名 Profile 不存在。
     */
    public Path requireProfileHome(String name) throws IOException {
        Path home = profileHome(name);
        if (!"default".equals(normalizeName(name)) && !Files.isDirectory(home)) {
            throw new IOException(
                    "Profile '"
                            + normalizeName(name)
                            + "' does not exist. Create it with: solonclaw profile create "
                            + normalizeName(name));
        }
        return home;
    }

    /**
     * 返回指定 Profile 的会话数据库路径，但不创建文件或初始化表结构。
     *
     * @param name Profile 名。
     * @return 已存在 Profile 下的 data/state.db 路径。
     * @throws IOException Profile 不存在。
     */
    public Path requireProfileStateDb(String name) throws IOException {
        return requireProfileHome(name).resolve("data").resolve("state.db").normalize();
    }

    /**
     * 读取 sticky 活动 Profile；缺失、空内容或不可读时回退 default。
     *
     * <p>这里只读取选择，不替调用方吞掉已删除或非法 Profile。启动路径会继续通过 {@link #requireProfileHome(String)} 给出明确错误。
     *
     * @return 活动 Profile 名。
     */
    public String activeProfile() {
        Path marker = root.resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(marker)) {
            return "default";
        }
        try {
            String name = readText(marker).trim();
            return name.length() == 0 ? "default" : name;
        } catch (Exception e) {
            return "default";
        }
    }

    /** 返回当前 JVM 实际加载的 Profile 名；无法确认时回退 default。 */
    private String currentProfileName() {
        String configured = trimToNull(System.getProperty("solonclaw.profile.name"));
        if (configured != null) {
            try {
                String normalized = normalizeName(configured);
                validateProfileName(normalized);
                return normalized;
            } catch (Exception ignored) {
                return "default";
            }
        }
        String workspace = trimToNull(System.getProperty("solonclaw.workspace"));
        if (workspace != null) {
            try {
                Path current = Paths.get(workspace).toAbsolutePath().normalize();
                if (current.equals(root)) {
                    return "default";
                }
                if (current.getParent() != null && current.getParent().equals(profilesRoot)) {
                    String name = normalizeName(current.getFileName().toString());
                    validateProfileName(name);
                    return name;
                }
            } catch (Exception ignored) {
                return "default";
            }
        }
        return "default";
    }

    /**
     * 返回默认 Profile 根目录。
     *
     * @return 绝对规范化根目录。
     */
    public Path root() {
        return root;
    }

    /**
     * 返回全部 Profile 的结构化隔离与运行状态视图。
     *
     * @return default 在首位、命名 Profile 按名称排序的视图列表。
     * @throws Exception 状态文件或目录无法读取。
     */
    public List<ProfileView> listProfileViews() throws Exception {
        List<ProfileView> result = new ArrayList<ProfileView>();
        for (String name : profileNames()) {
            result.add(profileView(name));
        }
        return result;
    }

    /**
     * 返回 default 与全部合法命名 Profile 的稳定排序名称。
     *
     * <p>该轻量入口供会话只读定位等机器级能力使用，不读取配置、技能或网关状态。
     *
     * @return default 在首位的 Profile 名称列表。
     * @throws IOException Profile 根目录无法读取。
     */
    public List<String> listProfileNames() throws IOException {
        return new ArrayList<String>(profileNames());
    }

    /**
     * 返回指定 Profile 的结构化隔离与运行状态视图。
     *
     * @param rawName Profile 名。
     * @return 不包含凭据内容的 Profile 视图。
     * @throws Exception Profile 不存在或状态读取失败。
     */
    public ProfileView profileView(String rawName) throws Exception {
        String name = normalizeName(rawName);
        Path home = requireProfileHome(name);
        Path config = home.resolve("config.yml");
        Map<String, Object> metadata = readMetadata(home);
        return new ProfileView(
                name,
                activeProfile().equals(name),
                currentProfileName().equals(name),
                home,
                text(metadata.get("description")),
                readConfiguredModel(config),
                gatewayStatus(name),
                countSkills(home.resolve("skills")),
                config,
                home.resolve(".env"),
                home.resolve("SOUL.md"),
                home.resolve("data/state.db"),
                home.resolve("MEMORY.md"),
                home.resolve("memory"),
                home.resolve("skills"),
                config,
                config,
                home.resolve("logs"),
                aliases(home),
                readManifest(home, false),
                Files.isRegularFile(home.resolve(NO_BUNDLED_SKILLS_FILE)));
    }

    /**
     * 返回 sticky 活动 Profile 的结构化视图。
     *
     * @return 活动 Profile 视图。
     * @throws Exception 状态读取失败。
     */
    public ProfileView activeProfileView() throws Exception {
        return profileView(activeProfile());
    }

    /**
     * 创建独立 Profile；clone-all 排除来源历史并原样保留其余用户文件和链接。
     *
     * @param rawName 新 Profile 名。
     * @param rawOptions 创建选项；为空时使用默认选项。
     * @return 新 Profile 工作区。
     * @throws Exception 名称、来源、别名或文件操作失败。
     */
    public Path createProfile(String rawName, ProfileCreateOptions rawOptions) throws Exception {
        ProfileCreateOptions options = rawOptions == null ? new ProfileCreateOptions() : rawOptions;
        String name = normalizeName(rawName);
        validateProfileName(name);
        if ("default".equals(name)) {
            throw new IllegalArgumentException("Cannot create the built-in default profile.");
        }
        boolean clone = options.isClone() || options.isCloneAll() || options.getCloneFrom() != null;
        if (options.isNoSkills() && clone) {
            throw new IllegalArgumentException(
                    "--no-skills cannot be combined with --clone, --clone-from, or --clone-all.");
        }
        Path target = profileHome(name);
        if (Files.exists(target)) {
            throw new IOException("Profile '" + name + "' already exists at " + target);
        }
        Path source = null;
        if (clone) {
            String sourceName = options.getCloneFrom();
            source = requireProfileHome(sourceName == null ? activeProfile() : sourceName);
        }
        try {
            Map<String, Object> clonedMetadata = new LinkedHashMap<String, Object>();
            if (options.isCloneAll()) {
                Set<String> excluded = new LinkedHashSet<String>(CLONE_ALL_HISTORY_EXCLUDED);
                if (samePath(source, root)) {
                    excluded.addAll(CLONE_ALL_DEFAULT_EXCLUDED);
                }
                copyTree(source, target, excluded, false, true, false, true);
                Files.deleteIfExists(target.resolve("gateway.pid"));
                Files.deleteIfExists(target.resolve("gateway_state.json"));
                Files.deleteIfExists(target.resolve("processes.json"));
                clonedMetadata = readMetadata(target);
                seedProfileFiles(target);
            } else {
                bootstrapProfile(target);
                if (source != null) {
                    for (String filename : CLONE_FILES) {
                        copyCloneFileIfExists(source.resolve(filename), target.resolve(filename));
                    }
                    if (Files.isDirectory(source.resolve("skills"))) {
                        copyTree(
                                source.resolve("skills"),
                                target.resolve("skills"),
                                Collections.<String>emptySet(),
                                false,
                                false,
                                false,
                                true);
                    }
                }
            }
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("name", name);
            metadata.put("aliases", new ArrayList<String>());
            String description = trimToNull(options.getDescription());
            if (description != null) {
                metadata.put("description", description);
                metadata.put("description_auto", Boolean.FALSE);
            } else if (options.isCloneAll()) {
                String clonedDescription = trimToNull(text(clonedMetadata.get("description")));
                if (clonedDescription != null) {
                    metadata.put("description", clonedDescription);
                    metadata.put(
                            "description_auto",
                            Boolean.valueOf(
                                    Boolean.TRUE.equals(clonedMetadata.get("description_auto"))));
                }
            }
            writeMetadata(target, metadata);
            if (options.isNoSkills()) {
                writeAtomically(
                        target.resolve(NO_BUNDLED_SKILLS_FILE),
                        "# 禁止为此 Profile 自动初始化内置技能。" + System.lineSeparator());
            } else if (!clone) {
                bundledSkillSeeder.seed(target);
            }
            if (!options.isNoAlias()) {
                try {
                    createAlias(name, name);
                } catch (Exception ignored) {
                    // Profile 创建是主操作；别名冲突由 alias 命令单独修复。
                }
            }
            return target;
        } catch (Exception e) {
            deleteTree(target);
            throw e;
        }
    }

    /**
     * 设置 sticky 活动 Profile，不改变当前已运行 JVM 的工作区。
     *
     * @param name Profile 名。
     * @throws IOException Profile 不存在或标记写入失败。
     */
    public void useProfile(String name) throws IOException {
        setActiveProfile(name);
    }

    /**
     * 重命名 Profile，并同步活动标记、网关、分发清单和别名。
     *
     * @param oldName 原名称。
     * @param newName 新名称。
     * @return 重命名后的工作区。
     * @throws Exception 校验、停止网关或文件移动失败。
     */
    public Path renameProfile(String oldName, String newName) throws Exception {
        return renameProfileInternal(oldName, newName, null);
    }

    /**
     * 删除非 default、非活动且非当前运行中的 Profile。
     *
     * @param name Profile 名。
     * @return 删除前的工作区路径。
     * @throws Exception Profile 受保护、网关无法停止或目录删除失败。
     */
    public Path deleteProfile(String name) throws Exception {
        return deleteProfileInternal(name);
    }

    /**
     * 导出不含明文凭据的 Profile 归档；default 使用便携工件白名单。
     *
     * @param name Profile 名。
     * @param output 输出 tar.gz 路径。
     * @return 绝对规范化输出路径。
     * @throws Exception 导出失败。
     */
    public Path exportProfile(String name, Path output) throws Exception {
        return exportProfileInternal(name, output);
    }

    /**
     * 从安全 tar.gz 归档导入新的命名 Profile。
     *
     * @param archive 归档路径。
     * @param name 可选目标名称；为空时使用归档根目录名。
     * @return 导入后的工作区。
     * @throws Exception 归档、名称或文件校验失败。
     */
    public Path importProfile(Path archive, String name) throws Exception {
        return importProfileInternal(archive, name);
    }

    /**
     * 写入人工维护的 Profile 职责说明。
     *
     * @param rawName Profile 名。
     * @param rawDescription 人工说明；空字符串用于清空现有说明。
     * @return 更新后的 Profile 视图。
     * @throws Exception Profile 不存在或元数据写入失败。
     */
    public ProfileView setDescription(String rawName, String rawDescription) throws Exception {
        String name = normalizeName(rawName);
        Path home = requireProfileHome(name);
        String description = rawDescription == null ? "" : rawDescription.trim();
        Map<String, Object> metadata = readMetadata(home);
        metadata.put("name", name);
        metadata.put("description", description);
        metadata.put("description_auto", Boolean.FALSE);
        if (!metadata.containsKey("aliases")) {
            metadata.put("aliases", new ArrayList<String>());
        }
        writeMetadata(home, metadata);
        return profileView(name);
    }

    /**
     * 使用 Profile 局部模型配置生成职责说明。
     *
     * @param rawName Profile 名。
     * @param overwrite 是否允许覆盖人工说明。
     * @return 自动描述结果。
     */
    public ProfileDescriptionService.DescribeOutcome describeProfile(
            String rawName, boolean overwrite) {
        String name = normalizeName(rawName);
        return profileDescriptionService.describe(name, profileHome(name), overwrite);
    }

    /**
     * 创建 Profile 快捷命令别名。
     *
     * @param profile Profile 名。
     * @param alias 可选别名；为空时使用 Profile 名。
     * @return 更新后的 Profile 视图。
     * @throws Exception Profile、别名或文件操作失败。
     */
    public ProfileView createProfileAlias(String profile, String alias) throws Exception {
        String name = normalizeName(profile);
        createAlias(name, trimToNull(alias) == null ? name : alias);
        return profileView(name);
    }

    /**
     * 删除 Profile 快捷命令别名。
     *
     * @param profile Profile 名。
     * @param alias 可选别名；为空时使用 Profile 名。
     * @return 更新后的 Profile 视图。
     * @throws Exception Profile、别名或文件操作失败。
     */
    public ProfileView removeProfileAlias(String profile, String alias) throws Exception {
        String name = normalizeName(profile);
        removeAlias(name, trimToNull(alias) == null ? name : alias);
        return profileView(name);
    }

    /**
     * 返回已安装分发清单的非密快照。
     *
     * @param name Profile 名。
     * @return 空映射表示该 Profile 不是分发安装。
     * @throws Exception Profile 不存在或清单无法读取。
     */
    public Map<String, Object> distributionInfo(String name) throws Exception {
        return new LinkedHashMap<String, Object>(readManifest(requireProfileHome(name), false));
    }

    /**
     * 无交互安装 Profile 分发，供 Dashboard 等结构化入口复用 CLI 的同一套安全规则。
     *
     * @param source 本地目录或 Git 地址。
     * @param name 可选目标 Profile 名。
     * @param alias 是否创建同名快捷命令。
     * @param force 是否允许覆盖既有 Profile 的分发所有文件。
     * @return 安装后的 Profile 视图。
     * @throws Exception 分发、清单、Profile 或文件操作失败。
     */
    public ProfileView installDistribution(String source, String name, boolean alias, boolean force)
            throws Exception {
        List<String> arguments = new ArrayList<String>();
        arguments.add("install");
        arguments.add(source);
        if (trimToNull(name) != null) {
            arguments.add("--name");
            arguments.add(name);
        }
        if (alias) {
            arguments.add("--alias");
        }
        if (force) {
            arguments.add("--force");
        }
        arguments.add("-y");
        String installedName;
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream())) {
            installedName = installProfile(arguments, new ByteArrayInputStream(new byte[0]), sink);
        }
        return profileView(installedName);
    }

    /**
     * 无交互更新 Profile 分发，默认保留本机配置。
     *
     * @param name Profile 名。
     * @param forceConfig 是否使用分发版本覆盖本机 config.yml。
     * @return 更新后的 Profile 视图。
     * @throws Exception Profile、分发来源或文件操作失败。
     */
    public ProfileView updateDistribution(String name, boolean forceConfig) throws Exception {
        List<String> arguments = new ArrayList<String>();
        arguments.add("update");
        arguments.add(name);
        if (forceConfig) {
            arguments.add("--force-config");
        }
        arguments.add("-y");
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream())) {
            updateProfile(arguments, new ByteArrayInputStream(new byte[0]), sink);
        }
        return profileView(name);
    }

    /** 根据启动级工作区配置解析默认 Profile 根目录。 */
    static Path resolveRoot(String configuredWorkspace) {
        String configured = trimToNull(configuredWorkspace);
        if (configured == null) {
            configured = trimToNull(System.getProperty("solonclaw.profile.root"));
        }
        if (configured == null) {
            configured = trimToNull(System.getProperty("solonclaw.workspace"));
        }
        if (configured == null) {
            configured = "./workspace";
        }
        Path path = Paths.get(configured);
        if (!path.isAbsolute()) {
            File fallback = new File(System.getProperty("user.dir", "."));
            path =
                    RuntimePathSupport.jarBaseDir(ProfileManager.class, fallback)
                            .toPath()
                            .resolve(path);
        }
        path = path.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null
                && parent.getFileName() != null
                && "profiles".equals(parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent().toAbsolutePath().normalize();
        }
        return path;
    }

    /** 输出全部 Profile，并用星号标记 sticky 活动项。 */
    private void listProfiles(PrintStream out) throws IOException {
        try {
            for (ProfileView view : listProfileViews()) {
                Map<String, Object> distribution = view.getDistribution();
                String distributionText =
                        distribution.isEmpty()
                                ? "none"
                                : valueOrDefault(distribution.get("version"), "unknown")
                                        + "@"
                                        + valueOrDefault(distribution.get("source"), "local");
                out.println(
                        (view.isActive() ? "* " : "  ")
                                + view.getName()
                                + "  model="
                                + valueOrDefault(view.getModel(), "not-configured")
                                + "  gateway="
                                + (view.getGateway().isRunning() ? "running" : "stopped")
                                + "  skills="
                                + view.getSkillsCount()
                                + "  config="
                                + (view.isConfigExists() ? "exists" : "missing")
                                + "  alias="
                                + (view.getAliases().isEmpty()
                                        ? "none"
                                        : join(view.getAliases(), ","))
                                + "  distribution="
                                + distributionText);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not inspect profiles: " + safeMessage(e), e);
        }
    }

    /** 输出裸 profile 命令的当前选择摘要，不泄露配置或凭据内容。 */
    private void showActiveProfileStatus(String selectedProfile, PrintStream out) throws Exception {
        String name = normalizeName(selectedProfile == null ? activeProfile() : selectedProfile);
        ProfileView view = profileView(name);
        out.println();
        out.println("Active profile: " + view.getName());
        out.println("Path:           " + view.getHome());
        if (view.getModel().length() > 0) {
            out.println("Model:          " + view.getModel());
        }
        out.println("Gateway:        " + (view.getGateway().isRunning() ? "running" : "stopped"));
        out.println("Skills:         " + view.getSkillsCount() + " installed");
        if (!view.getAliases().isEmpty()) {
            out.println(
                    "Alias:          "
                            + join(view.getAliases(), ", ")
                            + " -> solonclaw -p "
                            + view.getName());
        }
        out.println();
    }

    /** 返回 default 与全部合法命名 Profile 的稳定排序名称。 */
    private List<String> profileNames() throws IOException {
        Files.createDirectories(root);
        List<String> names = new ArrayList<String>();
        names.add("default");
        if (Files.isDirectory(profilesRoot)) {
            try (java.util.stream.Stream<Path> stream = Files.list(profilesRoot)) {
                stream.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> PROFILE_NAME.matcher(name).matches())
                        .sorted()
                        .forEach(names::add);
            }
        }
        return names;
    }

    /** 创建空白、配置克隆或全量克隆 Profile。 */
    private void createProfileCommand(List<String> args, String selectedProfile, PrintStream out)
            throws Exception {
        ParsedCommandArguments parsed =
                parseCommandArguments(
                        args,
                        1,
                        "create",
                        optionAliases(
                                "--clone",
                                "--clone",
                                "--clone-all",
                                "--clone-all",
                                "--no-alias",
                                "--no-alias",
                                "--no-skills",
                                "--no-skills"),
                        optionAliases(
                                "--clone-from", "--clone-from", "--description", "--description"));
        requirePositionals(
                parsed,
                1,
                1,
                "profile create <name> [--clone|--clone-all] [--clone-from SOURCE] [--no-alias]"
                        + " [--description TEXT] [--no-skills]");
        ProfileCreateOptions options =
                new ProfileCreateOptions()
                        .setClone(parsed.hasFlag("--clone"))
                        .setCloneAll(parsed.hasFlag("--clone-all"))
                        .setNoAlias(parsed.hasFlag("--no-alias"))
                        .setNoSkills(parsed.hasFlag("--no-skills"))
                        .setDescription(parsed.option("--description"));
        String cloneFrom = parsed.option("--clone-from");
        if (cloneFrom != null) {
            options.setCloneFrom(cloneFrom).setClone(true);
        }
        if ((options.isClone() || options.isCloneAll()) && options.getCloneFrom() == null) {
            options.setCloneFrom(selectedProfile == null ? activeProfile() : selectedProfile);
        }
        String name = normalizeName(parsed.positionals.get(0));
        Path target = createProfile(name, options);
        if (!options.isNoAlias() && aliases(target).isEmpty()) {
            out.println("Profile created; alias skipped because the command name is unavailable.");
        }
        out.println("Created profile '" + name + "': " + target);
    }

    /** 读取、人工设置或自动生成 Profile 职责说明，并保留命令行退出码语义。 */
    private int describeProfileCommand(List<String> args, PrintStream out, PrintStream err)
            throws Exception {
        ParsedCommandArguments parsed;
        try {
            parsed =
                    parseCommandArguments(
                            args,
                            1,
                            "describe",
                            optionAliases(
                                    "--auto",
                                    "--auto",
                                    "--all",
                                    "--all",
                                    "--overwrite",
                                    "--overwrite"),
                            optionAliases("--text", "--text"));
            requirePositionals(
                    parsed,
                    0,
                    1,
                    "profile describe [name] [--text TEXT | --auto [--overwrite]] [--all --auto]");
        } catch (ProfileUsageException e) {
            return describeUsageError(err, e.getMessage());
        }
        String name = parsed.positionals.isEmpty() ? null : parsed.positionals.get(0);
        String description = parsed.option("--text");
        boolean automatic = parsed.hasFlag("--auto");
        boolean all = parsed.hasFlag("--all");
        boolean overwrite = parsed.hasFlag("--overwrite");
        if (all && !automatic) {
            return describeUsageError(err, "--all requires --auto");
        }
        if (all && (description != null || name != null)) {
            return describeUsageError(
                    err, "--all is mutually exclusive with a profile name / --text");
        }
        if (!all && name == null) {
            return describeUsageError(err, "profile name is required (or --all --auto)");
        }
        if (description != null && automatic) {
            return describeUsageError(err, "--text is mutually exclusive with --auto");
        }
        if (all) {
            List<String> targets = describableProfileNames();
            if (targets.isEmpty()) {
                out.println("All profiles already have descriptions.");
                return 0;
            }
            int successes = 0;
            for (String target : targets) {
                ProfileDescriptionService.DescribeOutcome outcome =
                        profileDescriptionService.describe(target, profileHome(target), overwrite);
                if (outcome.isSuccess()) {
                    successes++;
                    out.println(
                            "Described '"
                                    + outcome.getProfileName()
                                    + "': "
                                    + outcome.getDescription());
                } else {
                    err.println(
                            "profile describe "
                                    + outcome.getProfileName()
                                    + ": "
                                    + outcome.getReason());
                }
            }
            return successes > 0 ? 0 : 1;
        }
        if (automatic) {
            String normalized = normalizeName(name);
            ProfileDescriptionService.DescribeOutcome outcome =
                    profileDescriptionService.describe(
                            normalized, profileHome(normalized), overwrite);
            if (!outcome.isSuccess()) {
                err.println(
                        "profile describe "
                                + outcome.getProfileName()
                                + ": "
                                + outcome.getReason());
                return 1;
            }
            out.println(
                    "Described '" + outcome.getProfileName() + "': " + outcome.getDescription());
            return 0;
        }
        Path home = requireProfileHome(name);
        Map<String, Object> metadata = readMetadata(home);
        String normalized = normalizeName(name);
        if (description == null) {
            String current = text(metadata.get("description"));
            out.println(
                    current.length() == 0
                            ? "(no description set for '" + normalized + "')"
                            : (Boolean.TRUE.equals(metadata.get("description_auto"))
                                    ? "[auto] " + current
                                    : current));
            return 0;
        }
        metadata.put("name", normalized);
        metadata.put("description", description.trim());
        metadata.put("description_auto", Boolean.FALSE);
        if (!metadata.containsKey("aliases")) {
            metadata.put("aliases", new ArrayList<String>());
        }
        writeMetadata(home, metadata);
        out.println("Description updated for '" + normalized + "'.");
        return 0;
    }

    /** 返回缺少说明或说明由模型生成、因此允许重新生成的 Profile。 */
    private List<String> describableProfileNames() throws IOException {
        List<String> targets = new ArrayList<String>();
        for (String profileName : profileNames()) {
            Map<String, Object> metadata = readMetadata(profileHome(profileName));
            String description = text(metadata.get("description"));
            if (description.length() == 0
                    || Boolean.TRUE.equals(metadata.get("description_auto"))) {
                targets.add(profileName);
            }
        }
        return targets;
    }

    /** 输出 profile describe 参数错误，并返回命令行约定的退出码 2。 */
    private int describeUsageError(PrintStream err, String reason) {
        err.println("profile describe: " + reason);
        return 2;
    }

    /** 写入 sticky 活动 Profile，default 通过删除标记表示。 */
    private void setActiveProfile(String rawName) throws IOException {
        String name = normalizeName(rawName);
        requireProfileHome(name);
        Files.createDirectories(root);
        Path marker = root.resolve(ACTIVE_FILE);
        if ("default".equals(name)) {
            Files.deleteIfExists(marker);
            return;
        }
        writeAtomically(marker, name + System.lineSeparator());
    }

    /** 输出 Profile 的独立路径、模型、网关、技能和状态文件。 */
    private void showProfileCommand(String rawName, PrintStream out) throws Exception {
        ProfileView view = profileView(rawName);
        out.println("Profile: " + view.getName());
        out.println("Path:    " + view.getHome());
        out.println(
                "Description: "
                        + (view.getDescription().length() == 0
                                ? "not configured"
                                : view.getDescription()));
        out.println(
                "Model:   " + (view.getModel().length() == 0 ? "not configured" : view.getModel()));
        out.println("Gateway: " + (view.getGateway().isRunning() ? "running" : "stopped"));
        out.println("Skills:  " + view.getSkillsCount());
        out.println("Config:  " + view.getConfig() + status(view.isConfigExists()));
        out.println("Credentials: " + view.getCredentials() + status(view.isCredentialsExists()));
        out.println("SOUL.md: " + view.getSoul() + status(view.isSoulExists()));
        out.println("Sessions: " + view.getSessions());
        out.println("Memory:   " + view.getMemoryFile() + ", " + view.getMemoryDir());
        out.println("Skills directory: " + view.getSkillsDir());
        out.println("MCP:      " + view.getMcpConfig());
        out.println("Channels: " + view.getChannelsConfig() + ", " + view.getSessions());
        out.println("Logs:     " + view.getLogs());
        out.println(
                "Alias:    "
                        + (view.getAliases().isEmpty() ? "none" : join(view.getAliases(), ", ")));
        Map<String, Object> manifest = view.getDistribution();
        out.println(
                "Distribution: "
                        + (manifest.isEmpty()
                                ? "none"
                                : valueOrDefault(manifest.get("version"), "unknown")
                                        + " from "
                                        + valueOrDefault(manifest.get("source"), "local")));
    }

    /** 重命名 Profile 目录、sticky 标记、分发清单和全部快捷别名。 */
    private void renameProfileCommand(String rawOld, String rawNew, PrintStream out)
            throws Exception {
        renameProfileInternal(rawOld, rawNew, out);
        out.println(
                "Renamed profile '"
                        + normalizeName(rawOld)
                        + "' to '"
                        + normalizeName(rawNew)
                        + "'.");
    }

    /** 执行 Profile 重命名核心操作。 */
    private Path renameProfileInternal(String rawOld, String rawNew, PrintStream out)
            throws Exception {
        String oldName = normalizeName(rawOld);
        String newName = normalizeName(rawNew);
        validateProfileName(oldName);
        validateProfileName(newName);
        if ("default".equals(oldName) || "default".equals(newName)) {
            throw new IllegalArgumentException("The default profile cannot be renamed.");
        }
        Path oldHome = requireProfileHome(oldName);
        Path newHome = profileHome(newName);
        if (Files.exists(newHome)) {
            throw new IOException("Profile '" + newName + "' already exists.");
        }
        boolean wasActive = activeProfile().equals(oldName);
        stopGateway(oldHome);
        moveWithoutReplace(oldHome, newHome);
        Map<String, Object> metadata = readMetadata(newHome);
        List<String> oldAliases = aliasesFromMetadata(metadata);
        if (oldAliases.isEmpty() && isOwnWrapper(wrapperPath(oldName), oldName)) {
            oldAliases.add(oldName);
        }
        List<String> newAliases = new ArrayList<String>();
        for (String alias : oldAliases) {
            String nextAlias = alias.equals(oldName) ? newName : alias;
            Files.deleteIfExists(wrapperPath(alias));
            try {
                writeWrapper(nextAlias, newName);
                newAliases.add(nextAlias);
            } catch (Exception e) {
                if (out != null) {
                    out.println(
                            "Alias '" + nextAlias + "' skipped after rename: " + safeMessage(e));
                }
            }
        }
        metadata.put("name", newName);
        metadata.put("aliases", newAliases);
        writeMetadata(newHome, metadata);
        Map<String, Object> manifest = readManifest(newHome, false);
        if (!manifest.isEmpty()) {
            manifest.put("name", newName);
            writeManifest(newHome, manifest);
        }
        if (wasActive) {
            setActiveProfile(newName);
        }
        return newHome;
    }

    /** 删除 Profile、其网关进程、全部别名，并在需要时回退 sticky default。 */
    private void deleteProfileCommand(List<String> args, InputStream input, PrintStream out)
            throws Exception {
        ParsedCommandArguments parsed =
                parseCommandArguments(
                        args,
                        1,
                        "delete",
                        optionAliases("-y", "--yes", "--yes", "--yes"),
                        noOptions());
        requirePositionals(parsed, 1, 1, "profile delete <name> [-y]");
        String name = normalizeName(parsed.positionals.get(0));
        if ("default".equals(name)) {
            throw new IllegalArgumentException("The default profile cannot be deleted.");
        }
        boolean confirmed = parsed.hasFlag("--yes");
        if (!confirmed) {
            out.print("Type '" + name + "' to confirm deletion: ");
            out.flush();
            String answer = readLine(input);
            if (!name.equals(answer == null ? "" : answer.trim())) {
                out.println("Cancelled.");
                return;
            }
        }
        deleteProfileInternal(name);
        out.println("Deleted profile '" + name + "'.");
    }

    /** 删除受保护检查通过的命名 Profile。 */
    private Path deleteProfileInternal(String rawName) throws Exception {
        String name = normalizeName(rawName);
        if ("default".equals(name)) {
            throw new IllegalArgumentException("The default profile cannot be deleted.");
        }
        boolean wasActive = activeProfile().equals(name);
        Path home = requireProfileHome(name);
        stopGateway(home);
        for (String alias : aliases(home)) {
            removeAliasFile(alias, name);
        }
        if (isOwnWrapper(wrapperPath(name), name)) {
            Files.deleteIfExists(wrapperPath(name));
        }
        Exception deletionFailure = null;
        try {
            deleteTree(home);
        } catch (Exception e) {
            deletionFailure = e;
        }
        if (wasActive) {
            try {
                setActiveProfile("default");
            } catch (Exception e) {
                if (deletionFailure == null) {
                    throw e;
                }
                deletionFailure.addSuppressed(e);
            }
        }
        if (deletionFailure != null) {
            throw deletionFailure;
        }
        return home;
    }

    /** 创建、更新或删除 Profile 快捷命令。 */
    private void manageAlias(List<String> args, PrintStream out) throws Exception {
        ParsedCommandArguments parsed =
                parseCommandArguments(
                        args,
                        1,
                        "alias",
                        optionAliases("--remove", "--remove"),
                        optionAliases("--name", "--name"));
        requirePositionals(parsed, 1, 1, "profile alias <name> [--remove] [--name NAME]");
        String profile = normalizeName(parsed.positionals.get(0));
        requireProfileHome(profile);
        String alias = parsed.option("--name") == null ? profile : parsed.option("--name");
        if (parsed.hasFlag("--remove")) {
            if (removeAlias(profile, alias)) {
                out.println("Removed alias '" + alias + "'.");
            } else {
                out.println("No alias '" + alias + "' found to remove.");
            }
        } else {
            createAlias(profile, alias);
            out.println("Alias '" + alias + "' -> solonclaw --profile " + profile);
        }
    }

    /** 将 Profile 导出为不含明文凭据的 tar.gz。 */
    private void exportProfileCommand(List<String> args, PrintStream out) throws Exception {
        ParsedCommandArguments parsed =
                parseCommandArguments(
                        args,
                        1,
                        "export",
                        noOptions(),
                        optionAliases("-o", "--output", "--output", "--output"));
        requirePositionals(parsed, 1, 1, "profile export <name> [-o FILE]");
        String name = normalizeName(parsed.positionals.get(0));
        Path output =
                Paths.get(
                        parsed.option("--output") == null
                                ? name + ".tar.gz"
                                : parsed.option("--output"));
        Path exported = exportProfileInternal(name, output);
        out.println("Exported profile '" + name + "': " + exported);
    }

    /** 执行安全 Profile 归档导出，并保留可信本地来源中的原样链接条目。 */
    private Path exportProfileInternal(String rawName, Path rawOutput) throws Exception {
        String name = normalizeName(rawName);
        Path source = requireProfileHome(name);
        if (rawOutput == null) {
            throw new IllegalArgumentException("Profile export output path is required.");
        }
        Path output = normalizeArchiveOutput(rawOutput);
        Path staging = Files.createTempDirectory("solonclaw-profile-export-");
        try {
            Path stagedProfile = staging.resolve(name);
            Set<String> excluded = new LinkedHashSet<String>();
            if ("default".equals(name)) {
                try (java.util.stream.Stream<Path> stream = Files.list(source)) {
                    for (Path entry : (Iterable<Path>) stream::iterator) {
                        String entryName = entry.getFileName().toString();
                        if (!DEFAULT_EXPORT_INCLUDED.contains(entryName)) {
                            excluded.add(entryName);
                        }
                    }
                }
            }
            copyTree(source, stagedProfile, excluded, false, false, true, true);
            removeCredentialFiles(stagedProfile);
            redactConfig(stagedProfile.resolve("config.yml"));
            if (!"default".equals(name)) {
                Map<String, Object> metadata = portableMetadata(readMetadata(source), name);
                Files.deleteIfExists(stagedProfile.resolve(METADATA_FILE));
                if (!metadata.isEmpty()) {
                    writeMetadata(stagedProfile, metadata);
                }
            }
            ProfileArchive.create(stagedProfile, name, output);
        } finally {
            deleteTree(staging);
        }
        return output;
    }

    /** 从安全 tar.gz 恢复为新的命名 Profile。 */
    private void importProfileCommand(List<String> args, PrintStream out) throws Exception {
        ParsedCommandArguments parsed =
                parseCommandArguments(
                        args, 1, "import", noOptions(), optionAliases("--name", "--name"));
        requirePositionals(parsed, 1, 1, "profile import <archive> [--name NAME]");
        Path archive = Paths.get(parsed.positionals.get(0)).toAbsolutePath().normalize();
        String requestedName = parsed.option("--name");
        Path imported = importProfileInternal(archive, requestedName);
        String importedName = imported.getFileName().toString();
        out.println("Imported profile '" + importedName + "': " + imported);
        try {
            createAlias(importedName, importedName);
            out.println("Wrapper created: " + wrapperPath(importedName));
        } catch (Exception ignored) {
            out.println("Profile imported; alias skipped because the command name is unavailable.");
        }
    }

    /** 执行安全 Profile 归档导入。 */
    private Path importProfileInternal(Path rawArchive, String requestedName) throws Exception {
        if (rawArchive == null) {
            throw new IllegalArgumentException("Profile import archive is required.");
        }
        Path archive = rawArchive.toAbsolutePath().normalize();
        Path staging = Files.createTempDirectory("solonclaw-profile-import-");
        try {
            String archiveRoot = ProfileArchive.extract(archive, staging);
            String requested = trimToNull(requestedName);
            String name = normalizeName(requested == null ? archiveRoot : requested);
            validateProfileName(name);
            if ("default".equals(name)) {
                throw new IllegalArgumentException(
                        "Import requires a named profile; use --name NAME.");
            }
            Path target = profileHome(name);
            if (Files.exists(target)) {
                throw new IOException("Profile '" + name + "' already exists at " + target);
            }
            copyTree(
                    staging.resolve(archiveRoot),
                    target,
                    Collections.<String>emptySet(),
                    false,
                    false,
                    false,
                    false);
            bootstrapProfile(target);
            Map<String, Object> metadata = readMetadata(target);
            metadata.put("name", name);
            metadata.put("aliases", new ArrayList<String>());
            writeMetadata(target, metadata);
            Map<String, Object> manifest = readManifest(target, false);
            if (!manifest.isEmpty()) {
                manifest.put("name", name);
                writeManifest(target, manifest);
            }
            return target;
        } finally {
            deleteTree(staging);
        }
    }

    /** 从本地目录或 git URL 安装 Profile 分发。 */
    private String installProfile(List<String> args, InputStream input, PrintStream out)
            throws Exception {
        ParsedCommandArguments parsed =
                parseCommandArguments(
                        args,
                        1,
                        "install",
                        optionAliases(
                                "--alias", "--alias", "--force", "--force", "-y", "--yes", "--yes",
                                "--yes"),
                        optionAliases("--name", "--name"));
        requirePositionals(
                parsed, 1, 1, "profile install <source> [--name N] [--alias] [--force] [-y]");
        String sourceValue = parsed.positionals.get(0);
        String requestedName = parsed.option("--name");
        boolean alias = parsed.hasFlag("--alias");
        boolean force = parsed.hasFlag("--force");
        boolean yes = parsed.hasFlag("--yes");
        Path staging = Files.createTempDirectory("solonclaw-profile-install-");
        try {
            StagedDistribution staged = stageDistribution(sourceValue, staging);
            assertNoSymlinks(staged.directory);
            Map<String, Object> manifest = readManifest(staged.directory, true);
            validateDistributionManifest(manifest);
            String manifestName = text(manifest.get("name"));
            String name = normalizeName(requestedName == null ? manifestName : requestedName);
            validateProfileName(name);
            if ("default".equals(name)) {
                throw new IllegalArgumentException(
                        "A distribution cannot replace the default profile.");
            }
            Path target = profileHome(name);
            boolean exists = Files.isDirectory(target);
            if (exists && !force) {
                throw new IOException(
                        "Profile '"
                                + name
                                + "' already exists; pass --force to overwrite distribution"
                                + " files.");
            }
            out.println("Profile: " + name);
            out.println("Version: " + valueOrDefault(manifest.get("version"), "0.1.0"));
            out.println("Source:  " + staged.provenance);
            if (!yes) {
                out.print("Install this profile? [y/N] ");
                out.flush();
                String answer = readLine(input);
                if (!"y".equalsIgnoreCase(answer) && !"yes".equalsIgnoreCase(answer)) {
                    out.println("Cancelled.");
                    return null;
                }
            }
            if (exists) {
                stopGateway(target);
            } else {
                bootstrapProfile(target);
            }
            applyDistribution(staged.directory, target, manifest, false);
            bootstrapProfile(target);
            redactConfig(target.resolve("config.yml"));
            manifest.put("name", name);
            manifest.put("version", valueOrDefault(manifest.get("version"), "0.1.0"));
            manifest.put("source", staged.provenance);
            manifest.put("installed_at", Instant.now().toString());
            writeManifest(target, manifest);
            writeEnvExample(staged.directory, target, manifest);
            Map<String, Object> metadata = readMetadata(target);
            metadata.put("name", name);
            if (!metadata.containsKey("aliases")) {
                metadata.put("aliases", new ArrayList<String>());
            }
            writeMetadata(target, metadata);
            if (alias) {
                createAlias(name, name);
            }
            out.println("Installed profile '" + name + "': " + target);
            return name;
        } finally {
            deleteTree(staging);
        }
    }

    /** 从已记录来源更新 Profile 分发文件，同时默认保留本机 config.yml。 */
    private String updateProfile(List<String> args, InputStream input, PrintStream out)
            throws Exception {
        ParsedCommandArguments parsed =
                parseCommandArguments(
                        args,
                        1,
                        "update",
                        optionAliases(
                                "--force-config",
                                "--force-config",
                                "-y",
                                "--yes",
                                "--yes",
                                "--yes"),
                        noOptions());
        requirePositionals(parsed, 1, 1, "profile update <name> [--force-config] [-y]");
        String name = normalizeName(parsed.positionals.get(0));
        boolean forceConfig = parsed.hasFlag("--force-config");
        boolean yes = parsed.hasFlag("--yes");
        Path target = requireProfileHome(name);
        Map<String, Object> installed = readManifest(target, true);
        String source = trimToNull(text(installed.get("source")));
        if (source == null) {
            throw new IOException("Profile '" + name + "' has no recorded distribution source.");
        }
        Path staging = Files.createTempDirectory("solonclaw-profile-update-");
        try {
            StagedDistribution staged = stageDistribution(source, staging);
            if (staged.directory.toAbsolutePath().normalize().equals(target)) {
                throw new IOException(
                        "A profile cannot update itself from its own live directory.");
            }
            assertNoSymlinks(staged.directory);
            Map<String, Object> incoming = readManifest(staged.directory, true);
            validateDistributionManifest(incoming);
            out.println("Profile: " + name);
            out.println(
                    "Version: "
                            + valueOrDefault(installed.get("version"), "unknown")
                            + " -> "
                            + valueOrDefault(incoming.get("version"), "unknown"));
            out.println("Source:  " + source);
            if (!yes) {
                out.print("Update this profile? [y/N] ");
                out.flush();
                String answer = readLine(input);
                if (!"y".equalsIgnoreCase(answer) && !"yes".equalsIgnoreCase(answer)) {
                    out.println("Cancelled.");
                    return null;
                }
            }
            stopGateway(target);
            applyDistribution(staged.directory, target, incoming, !forceConfig);
            bootstrapProfile(target);
            if (forceConfig) {
                redactConfig(target.resolve("config.yml"));
            }
            incoming.put("name", name);
            incoming.put("version", valueOrDefault(incoming.get("version"), "0.1.0"));
            incoming.put("source", source);
            incoming.put(
                    "installed_at",
                    valueOrDefault(installed.get("installed_at"), Instant.now().toString()));
            incoming.put("updated_at", Instant.now().toString());
            writeManifest(target, incoming);
            writeEnvExample(staged.directory, target, incoming);
            out.println("Updated profile '" + name + "': " + target);
            return name;
        } finally {
            deleteTree(staging);
        }
    }

    /** 输出已安装 Profile 的分发清单。 */
    private void showDistributionInfo(String rawName, PrintStream out) throws Exception {
        String name = normalizeName(rawName);
        Path home = requireProfileHome(name);
        Map<String, Object> manifest = readManifest(home, false);
        if (manifest.isEmpty()) {
            out.println("Profile: " + name);
            out.println("Distribution: none");
            return;
        }
        out.println("Profile: " + name);
        out.println("Version: " + valueOrDefault(manifest.get("version"), "0.1.0"));
        printIfPresent(out, "Description", manifest.get("description"));
        printIfPresent(out, "Requires", manifest.get(DISTRIBUTION_REQUIRES_FIELD));
        printIfPresent(out, "Author", manifest.get("author"));
        printIfPresent(out, "License", manifest.get("license"));
        printIfPresent(out, "Source", manifest.get("source"));
        printIfPresent(out, "Installed", manifest.get("installed_at"));
        printIfPresent(out, "Updated", manifest.get("updated_at"));
    }

    /** 创建 Profile 快捷命令并登记到本机元数据。 */
    private void createAlias(String profile, String alias) throws Exception {
        String normalizedProfile = normalizeName(profile);
        validateAliasName(alias);
        String normalizedAlias = alias;
        requireProfileHome(normalizedProfile);
        checkAliasCollision(normalizedAlias, normalizedProfile);
        writeWrapper(normalizedAlias, normalizedProfile);
        Path home = profileHome(normalizedProfile);
        Map<String, Object> metadata = readMetadata(home);
        metadata.put("name", normalizedProfile);
        List<String> aliases = aliasesFromMetadata(metadata);
        if (!aliases.contains(normalizedAlias)) {
            aliases.add(normalizedAlias);
        }
        metadata.put("aliases", aliases);
        writeMetadata(home, metadata);
    }

    /** 删除指定快捷命令并更新 Profile 元数据，别名不存在时返回 false。 */
    private boolean removeAlias(String profile, String alias) throws Exception {
        String normalizedProfile = normalizeName(profile);
        validateAliasName(alias);
        String normalizedAlias = alias;
        Path home = requireProfileHome(normalizedProfile);
        Path wrapper = wrapperPath(normalizedAlias);
        if (!Files.exists(wrapper)) {
            Map<String, Object> metadata = readMetadata(home);
            List<String> aliases = aliasesFromMetadata(metadata);
            if (aliases.remove(normalizedAlias)) {
                metadata.put("name", normalizedProfile);
                metadata.put("aliases", aliases);
                writeMetadata(home, metadata);
            }
            return false;
        }
        removeAliasFile(normalizedAlias, normalizedProfile);
        Map<String, Object> metadata = readMetadata(home);
        List<String> aliases = aliasesFromMetadata(metadata);
        aliases.remove(normalizedAlias);
        metadata.put("name", normalizedProfile);
        metadata.put("aliases", aliases);
        writeMetadata(home, metadata);
        return true;
    }

    /** 只删除确实属于目标 Profile 的快捷命令文件。 */
    private void removeAliasFile(String alias, String profile) throws IOException {
        Path wrapper = wrapperPath(alias);
        if (Files.exists(wrapper) && !isOwnWrapper(wrapper, profile)) {
            throw new IOException(
                    "Alias '" + alias + "' is not owned by profile '" + profile + "'.");
        }
        Files.deleteIfExists(wrapper);
    }

    /** 写入跨平台快捷命令脚本。 */
    private void writeWrapper(String alias, String profile) throws IOException {
        validateAliasName(alias);
        Files.createDirectories(wrapperDir);
        Path wrapper = wrapperPath(alias);
        String content;
        if (isWindows()) {
            content =
                    "@echo off\r\n"
                            + "rem solonclaw-profile="
                            + profile
                            + "\r\n"
                            + launcher
                            + " --profile "
                            + profile
                            + " %*\r\n";
        } else {
            content =
                    "#!/bin/sh\n"
                            + "# solonclaw-profile="
                            + profile
                            + "\nexec "
                            + shellCommand(launcher)
                            + " --profile "
                            + profile
                            + " \"$@\"\n";
        }
        writeAtomically(wrapper, content);
        if (!isWindows()) {
            try {
                Files.setPosixFilePermissions(
                        wrapper,
                        EnumSet.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.GROUP_EXECUTE,
                                PosixFilePermission.OTHERS_READ,
                                PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException e) {
                wrapper.toFile().setExecutable(true, false);
            }
        }
    }

    /** 检查快捷命令不会覆盖保留命令、PATH 程序或其他 Profile 别名。 */
    private void checkAliasCollision(String alias, String profile) throws IOException {
        if (RESERVED_NAMES.contains(alias) || TOP_LEVEL_COMMANDS.contains(alias)) {
            throw new IOException("Alias '" + alias + "' conflicts with a reserved command.");
        }
        Path wrapper = wrapperPath(alias);
        if (Files.exists(wrapper) && !isOwnWrapper(wrapper, profile)) {
            throw new IOException("Alias '" + alias + "' already exists at " + wrapper);
        }
        String pathValue = System.getenv("PATH");
        if (pathValue == null) {
            return;
        }
        for (String entry : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (entry.trim().length() == 0) {
                continue;
            }
            Path candidate =
                    Paths.get(entry).toAbsolutePath().normalize().resolve(wrapper.getFileName());
            if (Files.exists(candidate) && !candidate.equals(wrapper)) {
                throw new IOException(
                        "Alias '"
                                + alias
                                + "' conflicts with an existing PATH command: "
                                + candidate);
            }
        }
    }

    /** 返回逻辑别名对应的脚本路径。 */
    private Path wrapperPath(String alias) {
        return wrapperDir.resolve(isWindows() ? alias + ".cmd" : alias).normalize();
    }

    /** 判断脚本是否由 solonclaw 为指定 Profile 创建。 */
    private boolean isOwnWrapper(Path wrapper, String profile) {
        if (!Files.isRegularFile(wrapper)) {
            return false;
        }
        try {
            return readText(wrapper).contains("solonclaw-profile=" + profile);
        } catch (IOException e) {
            return false;
        }
    }

    /** 初始化新 Profile 的目录与独立空凭据文件。 */
    private void bootstrapProfile(Path home) throws IOException {
        Files.createDirectories(home);
        for (String directory : PROFILE_DIRS) {
            Files.createDirectories(home.resolve(directory));
        }
        seedProfileFiles(home);
    }

    /** 为 clone-all 或缺少模板的 Profile 补齐独立凭据占位与默认人格文件。 */
    private void seedProfileFiles(Path home) throws IOException {
        Path env = home.resolve(".env");
        if (!Files.exists(env, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.write(
                    env,
                    ("# solonclaw profile-local secrets.\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
            tightenOwnerOnly(env);
        }
        Path soul = home.resolve("SOUL.md");
        if (!Files.exists(soul, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream template =
                    ProfileManager.class
                            .getClassLoader()
                            .getResourceAsStream("persona-templates/SOUL.md")) {
                if (template != null) {
                    Files.copy(template, soul);
                }
            }
        }
    }

    /** 基础 clone 按普通文件语义复制配置；文件链接会复制其目标内容。 */
    private void copyCloneFileIfExists(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        if (Files.isDirectory(source)) {
            copyTree(source, target, Collections.<String>emptySet(), false, false, false, true);
            return;
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("Profile clone only supports regular files: " + source);
        }
        Files.createDirectories(target.getParent());
        copyRegularFile(source, target);
        if (".env".equals(target.getFileName().toString())) {
            tightenOwnerOnly(target);
        }
    }

    /** 复制分发目录中的单个文件或目录，并拒绝任何符号链接。 */
    private void copyIfExists(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        if (Files.isSymbolicLink(source)) {
            throw new IOException("Profile clone does not support symbolic links: " + source);
        }
        if (Files.isDirectory(source)) {
            copyTree(source, target, Collections.<String>emptySet(), false);
        } else if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            copyRegularFile(source, target);
            if (".env".equals(target.getFileName().toString())) {
                tightenOwnerOnly(target);
            }
        }
    }

    /** 复制目录树，可排除或替换源目录下的顶层条目。 */
    private void copyTree(
            Path source, Path target, Set<String> excludedRoots, boolean replaceTopLevel)
            throws IOException {
        copyTree(source, target, excludedRoots, replaceTopLevel, false, false, false);
    }

    /** 复制目录树，并按调用边界选择过滤规则与符号链接行为。 */
    private void copyTree(
            Path source,
            Path target,
            Set<String> excludedRoots,
            boolean replaceTopLevel,
            boolean excludeCloneTransientFiles,
            boolean excludeExportTransientFiles,
            boolean preserveSymlinks)
            throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            throw new IOException("Profile source is not a directory: " + source);
        }
        if (!preserveSymlinks) {
            assertNoSymlinks(source);
        }
        final Path walkRoot = source.toRealPath();
        Files.createDirectories(target);
        if (replaceTopLevel) {
            try (java.util.stream.Stream<Path> stream = Files.list(source)) {
                for (Path entry : (Iterable<Path>) stream::iterator) {
                    String name = entry.getFileName().toString();
                    if (!excludedRoots.contains(name)) {
                        deleteTree(target.resolve(name));
                    }
                }
            }
        }
        Files.walkFileTree(
                walkRoot,
                new SimpleFileVisitor<Path>() {
                    /** 创建目标目录并跳过被排除的路径。 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        Path relative = walkRoot.relativize(dir);
                        if (isExcluded(relative, excludedRoots)
                                || (excludeCloneTransientFiles && isTransientCloneEntry(relative))
                                || (excludeExportTransientFiles
                                        && isTransientExportEntry(relative))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        Files.createDirectories(target.resolve(relative));
                        return FileVisitResult.CONTINUE;
                    }

                    /** 复制普通文件或原样链接，并跳过被排除的路径。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Path relative = walkRoot.relativize(file);
                        if (isExcluded(relative, excludedRoots)
                                || (excludeCloneTransientFiles && isTransientCloneEntry(relative))
                                || (excludeExportTransientFiles
                                        && isTransientExportEntry(relative))) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path destination = target.resolve(relative);
                        Files.createDirectories(destination.getParent());
                        if (Files.isSymbolicLink(file)) {
                            if (!preserveSymlinks) {
                                throw new IOException(
                                        "Profile source contains a symbolic link: " + file);
                            }
                            deleteTree(destination);
                            Files.createSymbolicLink(destination, Files.readSymbolicLink(file));
                            return FileVisitResult.CONTINUE;
                        }
                        if (!attrs.isRegularFile()) {
                            throw new IOException(
                                    "Profile copy only supports regular files: " + file);
                        }
                        copyRegularFile(file, destination);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** 复制普通文件并尽量保留时间戳与 POSIX 权限。 */
    private static void copyRegularFile(Path source, Path target) throws IOException {
        try {
            Files.copy(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException | UnsupportedOperationException first) {
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    /** 判断相对路径是否命中根路径或其任意子项排除规则。 */
    private static boolean isExcluded(Path relative, Set<String> excludedRoots) {
        if (relative == null || relative.getNameCount() == 0 || excludedRoots == null) {
            return false;
        }
        String value = relative.toString().replace('\\', '/');
        for (String excluded : excludedRoots) {
            String normalized = excluded.replace('\\', '/');
            if (value.equals(normalized) || value.startsWith(normalized + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 判断条目是否属于 clone-all 不应继承的可再生或未完成运行时文件。 */
    private static boolean isTransientCloneEntry(Path relative) {
        if (relative == null || relative.getNameCount() == 0) {
            return false;
        }
        for (Path segment : relative) {
            if ("__pycache__".equals(segment.toString())) {
                return true;
            }
        }
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pyc")
                || name.endsWith(".pyo")
                || name.endsWith(".sock")
                || name.endsWith(".tmp");
    }

    /** 判断条目是否属于导出归档不应携带的缓存、套接字或临时文件。 */
    private static boolean isTransientExportEntry(Path relative) {
        if (relative == null || relative.getNameCount() == 0) {
            return false;
        }
        for (Path segment : relative) {
            if ("__pycache__".equals(segment.toString())) {
                return true;
            }
        }
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sock")
                || name.endsWith(".tmp")
                || "package.json".equals(name)
                || "package-lock.json".equals(name);
    }

    /** 拒绝来源目录中的符号链接，避免复制或安装越过 Profile 边界。 */
    private void assertNoSymlinks(Path source) throws IOException {
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<Path>() {
                    /** 检查每个目录是否为符号链接。 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        if (Files.isSymbolicLink(dir)) {
                            throw new IOException(
                                    "Profile source contains a symbolic link: " + dir);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    /** 检查每个文件是否为符号链接或特殊文件。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                            throw new IOException(
                                    "Profile source contains an unsupported file: " + file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** 删除导出暂存区中的常见凭据文件。 */
    private void removeCredentialFiles(Path rootPath) throws IOException {
        Files.walkFileTree(
                rootPath,
                new SimpleFileVisitor<Path>() {
                    /** 跳过不应进入归档的凭据目录。 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        if (!dir.equals(rootPath)
                                && isCredentialDirectory(dir.getFileName().toString())) {
                            deleteTree(dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    /** 删除不应进入归档的凭据文件。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        if (isCredentialFile(file.getFileName().toString())) {
                            Files.deleteIfExists(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** 判断目录名是否属于系统凭据目录。 */
    private static boolean isCredentialDirectory(String name) {
        return ".ssh".equals(name) || ".gnupg".equals(name);
    }

    /** 判断文件名是否属于系统凭据文件。 */
    private static boolean isCredentialFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (".env.example".equals(lower)) {
            return false;
        }
        return lower.equals(".env")
                || lower.startsWith(".env.")
                || lower.equals("auth.json")
                || lower.equals("credentials.json")
                || lower.equals(".credentials.json")
                || lower.equals("application_default_credentials.json")
                || lower.equals("id_rsa")
                || lower.equals("id_dsa")
                || lower.equals("id_ecdsa")
                || lower.equals("id_ed25519");
    }

    /** 对导出或安装后的 config.yml 做完整文本脱敏。 */
    private void redactConfig(Path config) throws IOException {
        if (Files.isSymbolicLink(config) || !Files.isRegularFile(config)) {
            return;
        }
        String sanitized = SecretRedactor.redact(readText(config), Integer.MAX_VALUE);
        Files.write(
                config,
                sanitized.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /** 解析配置中的 provider/model，只读取非密展示字段。 */
    @SuppressWarnings("unchecked")
    private String readConfiguredModel(Path config) {
        if (!Files.isRegularFile(config)) {
            return "";
        }
        try {
            Object parsed = new Yaml().load(readText(config));
            if (!(parsed instanceof Map)) {
                return "";
            }
            Map<String, Object> rootMap = stringMap((Map<?, ?>) parsed);
            Map<String, Object> model = mapValue(rootMap.get("model"));
            String provider = valueOrDefault(model.get("providerKey"), "default");
            String modelName = text(model.get("default"));
            Map<String, Object> providers = mapValue(rootMap.get("providers"));
            Map<String, Object> providerConfig = mapValue(providers.get(provider));
            if (modelName.length() == 0) {
                modelName = text(providerConfig.get("defaultModel"));
            }
            if (modelName.length() == 0) {
                return provider;
            }
            return provider + "/" + modelName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 统计 skills 下实际存在的 SKILL.md 文件。 */
    private long countSkills(Path skills) throws IOException {
        if (!Files.isDirectory(skills)) {
            return 0L;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(skills)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .count();
        }
    }

    /** 使用现有网关状态服务读取 Profile 本地 PID/状态。 */
    private boolean gatewayRunning(Path home) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        String name = home.equals(root) ? "default" : home.getFileName().toString();
        return new GatewayRuntimeStatusService(config, name).isRunning();
    }

    /**
     * 返回指定 Profile 的网关运行状态和隔离文件路径。
     *
     * @param rawName Profile 名。
     * @return 网关状态视图。
     * @throws Exception Profile 不存在或状态文件无法读取。
     */
    public ProfileGatewayStatus gatewayStatus(String rawName) throws Exception {
        String name = normalizeName(rawName);
        Path home = requireProfileHome(name);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config, name);
        boolean running = service.isRunning();
        Long pid = null;
        Integer port = knownGatewayPort(name, home);
        if (running) {
            Map<String, Object> record = readJson(home.resolve("gateway.pid"), true);
            long value = longValue(record.get("pid"));
            if (value > 0L) {
                pid = Long.valueOf(value);
            }
            int recordedPort = intValue(record.get("port"), -1);
            if (recordedPort > 0) {
                port = Integer.valueOf(recordedPort);
            }
        }
        return new ProfileGatewayStatus(
                name,
                home,
                running,
                pid,
                port,
                service.readState(),
                home.resolve("gateway.pid"),
                home.resolve("gateway_state.json"),
                home.resolve(GATEWAY_LOG_FILE));
    }

    /**
     * 为前台或后台网关生成唯一端口参数，并将命名 Profile 的选择持久化到本机元数据。
     *
     * @param rawName Profile 名。
     * @param rawArgs 原始服务端参数。
     * @return 去重后带 `--server.port=<port>` 的参数副本。
     * @throws Exception Profile 不存在、端口无效或无可用端口。
     */
    public List<String> gatewayServerArguments(String rawName, List<String> rawArgs)
            throws Exception {
        synchronized (ProfileManager.class) {
            Path lockFile = root.resolve(GATEWAY_START_LOCK_FILE);
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel =
                            FileChannel.open(
                                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return gatewayServerArgumentsLocked(rawName, rawArgs);
            }
        }
    }

    /** 在网关启动锁内完成端口选择和元数据持久化。 */
    private List<String> gatewayServerArgumentsLocked(String rawName, List<String> rawArgs)
            throws Exception {
        String name = normalizeName(rawName);
        Path home = requireProfileHome(name);
        List<String> source = rawArgs == null ? Collections.<String>emptyList() : rawArgs;
        List<String> result = new ArrayList<String>();
        Integer explicitPort = null;
        for (int i = 0; i < source.size(); i++) {
            String argument = source.get(i);
            if ("--server.port".equals(argument)) {
                if (i + 1 >= source.size()) {
                    throw new IllegalArgumentException("--server.port requires a value.");
                }
                explicitPort = Integer.valueOf(parsePort(source.get(++i)));
                continue;
            }
            if (argument != null && argument.startsWith("--server.port=")) {
                explicitPort =
                        Integer.valueOf(parsePort(argument.substring("--server.port=".length())));
                continue;
            }
            result.add(argument);
        }
        PortSelection selection = selectGatewayPort(name, home, explicitPort);
        result.add("--server.port=" + selection.port);
        if (!"default".equals(name)) {
            Map<String, Object> metadata = readMetadata(home);
            metadata.put("name", name);
            if (!metadata.containsKey("aliases")) {
                metadata.put("aliases", new ArrayList<String>());
            }
            metadata.put("gateway_port", Integer.valueOf(selection.port));
            metadata.put("gateway_port_auto", Boolean.valueOf(selection.automatic));
            writeMetadata(home, metadata);
        }
        return result;
    }

    /**
     * 为指定 Profile 启动独立后台 JVM，并等待其写入经过校验的 PID 状态。
     *
     * @param rawName Profile 名。
     * @param serverArgs 传给 Solon 服务端的附加参数。
     * @throws Exception 启动命令不可解析、子进程退出或超时。
     */
    public void startGateway(String rawName, List<String> serverArgs) throws Exception {
        synchronized (ProfileManager.class) {
            Path lockFile = root.resolve(GATEWAY_START_LOCK_FILE);
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel =
                            FileChannel.open(
                                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                startGatewayLocked(rawName, serverArgs);
            }
        }
    }

    /** 在工作区级启动锁内复核状态并等待独立网关完成启动。 */
    private void startGatewayLocked(String rawName, List<String> serverArgs) throws Exception {
        String name = normalizeName(rawName);
        Path home = requireProfileHome(name);
        if (gatewayRunning(home)) {
            return;
        }
        Files.deleteIfExists(home.resolve("gateway.pid"));
        Files.deleteIfExists(home.resolve("gateway_state.json"));
        Path logFile = home.resolve(GATEWAY_LOG_FILE);
        Files.createDirectories(logFile.getParent());
        List<String> effectiveArgs = gatewayServerArgumentsLocked(name, serverArgs);
        List<String> command = gatewayLaunchCommand(name, home, effectiveArgs);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(System.getProperty("user.dir", ".")));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        File nullDevice = new File(isWindows() ? "NUL" : "/dev/null");
        if (nullDevice.exists()) {
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice));
        }
        Process process = builder.start();
        long deadline = System.currentTimeMillis() + GATEWAY_START_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (gatewayRunning(home)) {
                return;
            }
            if (!process.isAlive()) {
                break;
            }
            Thread.sleep(100L);
        }
        if (process.isAlive()) {
            process.destroy();
            process.waitFor(3L, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        Files.deleteIfExists(home.resolve("gateway.pid"));
        Files.deleteIfExists(home.resolve("gateway_state.json"));
        String detail = readLogTail(logFile, 4000);
        throw new IOException(
                "Profile gateway failed to start"
                        + (detail.length() == 0
                                ? "."
                                : ": " + SecretRedactor.redact(detail, 4000)));
    }

    /**
     * 停止指定 Profile 的独立网关进程并清理运行状态。
     *
     * @param rawName Profile 名。
     * @throws Exception Profile 不存在、PID 不安全或进程无法停止。
     */
    public void stopGateway(String rawName) throws Exception {
        stopGateway(requireProfileHome(rawName));
    }

    /** 停止确认为该 Profile 网关的进程，并清理过期运行状态文件。 */
    private void stopGateway(Path home) throws Exception {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        String name = home.equals(root) ? "default" : home.getFileName().toString();
        GatewayRuntimeStatusService statusService = new GatewayRuntimeStatusService(config, name);
        if (!statusService.isRunning()) {
            Files.deleteIfExists(home.resolve("gateway.pid"));
            Files.deleteIfExists(home.resolve("gateway_state.json"));
            return;
        }
        Map<String, Object> record = readJson(home.resolve("gateway.pid"), true);
        long pid = longValue(record.get("pid"));
        if (pid <= 0L || pid == RuntimeProcessSupport.currentPidOrUnknown()) {
            throw new IOException("Refusing to stop an invalid profile gateway PID.");
        }
        terminateProcess(pid, false);
        for (int i = 0; i < 30 && statusService.isRunning(); i++) {
            Thread.sleep(100L);
        }
        if (statusService.isRunning()) {
            terminateProcess(pid, true);
            for (int i = 0; i < 20 && statusService.isRunning(); i++) {
                Thread.sleep(100L);
            }
        }
        if (statusService.isRunning()) {
            throw new IOException("Profile gateway did not stop: PID " + pid);
        }
        Files.deleteIfExists(home.resolve("gateway.pid"));
        Files.deleteIfExists(home.resolve("gateway_state.json"));
    }

    /** 构建与当前 jar 或类路径一致的后台服务端启动命令。 */
    private List<String> gatewayLaunchCommand(String profile, Path home, List<String> serverArgs)
            throws Exception {
        List<String> command = new ArrayList<String>();
        Path java =
                Paths.get(
                                System.getProperty("java.home", ""),
                                "bin",
                                isWindows() ? "java.exe" : "java")
                        .toAbsolutePath()
                        .normalize();
        if (!Files.isRegularFile(java)) {
            throw new IOException("Java runtime executable was not found: " + java);
        }
        command.add(java.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsolonclaw.profile.root=" + root);
        command.add("-Dsolonclaw.workspace=" + home);
        command.add("-Dsolonclaw.profile.name=" + profile);
        URI location =
                ProfileManager.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path codeSource = Paths.get(location).toAbsolutePath().normalize();
        if (Files.isRegularFile(codeSource)
                && codeSource.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            command.add("-jar");
            command.add(codeSource.toString());
        } else {
            String classPath = trimToNull(System.getProperty("java.class.path"));
            if (classPath == null) {
                throw new IOException("Current Java classpath is unavailable for gateway start.");
            }
            command.add("-cp");
            command.add(classPath);
            command.add("com.jimuqu.solon.claw.SolonClawApp");
        }
        command.add("--profile");
        command.add(profile);
        if (serverArgs != null) {
            for (String argument : serverArgs) {
                if (argument != null && argument.indexOf('\0') < 0) {
                    command.add(argument);
                }
            }
        }
        return command;
    }

    /** 选择显式、已持久化、配置或自动分配的 Profile 网关端口。 */
    private PortSelection selectGatewayPort(String name, Path home, Integer explicitPort)
            throws Exception {
        if (explicitPort != null) {
            requireAvailableGatewayPort(name, explicitPort.intValue());
            return new PortSelection(explicitPort.intValue(), false);
        }
        Map<String, Object> metadata = readMetadata(home);
        int metadataPort = intValue(metadata.get("gateway_port"), -1);
        boolean automatic = Boolean.TRUE.equals(metadata.get("gateway_port_auto"));
        if (metadataPort > 0) {
            if (isGatewayPortAvailable(metadataPort)) {
                return new PortSelection(metadataPort, automatic);
            }
            if (!automatic) {
                throw new IOException(
                        "Gateway port "
                                + metadataPort
                                + " for profile '"
                                + name
                                + "' is already in use.");
            }
        }
        Integer configured = readConfiguredGatewayPort(home.resolve("config.yml"));
        if ("default".equals(name)) {
            int port = configured == null ? 8080 : configured.intValue();
            requireAvailableGatewayPort(name, port);
            return new PortSelection(port, false);
        }
        if (configured != null && configured.intValue() != 8080) {
            requireAvailableGatewayPort(name, configured.intValue());
            return new PortSelection(configured.intValue(), false);
        }
        return new PortSelection(findAvailableGatewayPort(name), true);
    }

    /** 返回已知监听端口但不分配或写入新的端口。 */
    private Integer knownGatewayPort(String name, Path home) throws IOException {
        Map<String, Object> metadata = readMetadata(home);
        int metadataPort = intValue(metadata.get("gateway_port"), -1);
        if (metadataPort > 0) {
            return Integer.valueOf(metadataPort);
        }
        Integer configured = readConfiguredGatewayPort(home.resolve("config.yml"));
        if (configured != null && ("default".equals(name) || configured.intValue() != 8080)) {
            return configured;
        }
        return "default".equals(name) ? Integer.valueOf(8080) : null;
    }

    /** 从 Profile 配置文件读取显式 server.port。 */
    private Integer readConfiguredGatewayPort(Path config) throws IOException {
        if (!Files.isRegularFile(config)) {
            return null;
        }
        try {
            Object parsed =
                    new Yaml(new SafeConstructor(new LoaderOptions())).load(readText(config));
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<String, Object> rootMap = stringMap((Map<?, ?>) parsed);
            Object raw = rootMap.get("server.port");
            if (raw == null) {
                raw = mapValue(rootMap.get("server")).get("port");
            }
            if (raw == null) {
                return null;
            }
            return Integer.valueOf(parsePort(String.valueOf(raw)));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid server.port in " + config, e);
        }
    }

    /** 为命名 Profile 查找未被现有 Profile 预留且当前可绑定的端口。 */
    private int findAvailableGatewayPort(String profile) throws Exception {
        Set<Integer> reserved = reservedGatewayPorts(profile);
        int first = 8081 + Math.floorMod(profile.hashCode(), 1000);
        for (int offset = 0; offset < 10000; offset++) {
            int candidate = 8081 + Math.floorMod(first - 8081 + offset, 10000);
            if (!reserved.contains(Integer.valueOf(candidate))
                    && isGatewayPortAvailable(candidate)) {
                return candidate;
            }
        }
        throw new IOException(
                "No available gateway port could be allocated for profile '" + profile + "'.");
    }

    /** 收集其他 Profile 已持久化或显式配置的端口。 */
    private Set<Integer> reservedGatewayPorts(String exceptProfile) throws Exception {
        Set<Integer> result = new HashSet<Integer>();
        result.add(Integer.valueOf(8080));
        for (String name : profileNames()) {
            if (name.equals(exceptProfile)) {
                continue;
            }
            Path home = requireProfileHome(name);
            Integer port = knownGatewayPort(name, home);
            if (port != null) {
                result.add(port);
            }
        }
        return result;
    }

    /** 验证端口范围和当前绑定可用性。 */
    private void requireAvailableGatewayPort(String profile, int port) throws IOException {
        validatePort(port);
        if (!isGatewayPortAvailable(port)) {
            throw new IOException(
                    "Gateway port " + port + " for profile '" + profile + "' is already in use.");
        }
    }

    /** 尝试在回环地址绑定端口，用于启动前快速冲突检查。 */
    private boolean isGatewayPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析并校验 TCP 监听端口。 */
    private int parsePort(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            validatePort(port);
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid server.port: " + value);
        }
    }

    /** 校验 TCP 监听端口范围。 */
    private void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid server.port: " + port);
        }
    }

    /** 读取后台网关日志尾部，避免错误输出加载整个大文件。 */
    private String readLogTail(Path logFile, int limit) {
        if (!Files.isRegularFile(logFile) || limit <= 0) {
            return "";
        }
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = file.length();
            int count = (int) Math.min((long) limit, length);
            byte[] bytes = new byte[count];
            file.seek(length - count);
            file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 向已验证的网关 PID 发送终止信号。 */
    private void terminateProcess(long pid, boolean force)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<String>();
        if (isWindows()) {
            command.add("taskkill");
            command.add("/PID");
            command.add(String.valueOf(pid));
            command.add("/T");
            if (force) {
                command.add("/F");
            }
        } else {
            command.add("kill");
            command.add(force ? "-KILL" : "-TERM");
            command.add(String.valueOf(pid));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.waitFor(5L, TimeUnit.SECONDS);
    }

    /** 将本地目录或 git 地址暂存为待安装分发目录。 */
    private StagedDistribution stageDistribution(String source, Path staging) throws Exception {
        String normalizedSource = normalizeDistributionSource(source);
        Path local = localDistributionPath(normalizedSource);
        if (Files.isDirectory(local)) {
            return new StagedDistribution(local, local.toString());
        }
        Path checkout = staging.resolve("checkout");
        Path log = staging.resolve("git-clone.log");
        ProcessBuilder builder =
                new ProcessBuilder(
                        "git",
                        "clone",
                        "--depth",
                        "1",
                        "--",
                        normalizedSource,
                        checkout.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(120L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Profile distribution git clone timed out.");
        }
        if (process.exitValue() != 0) {
            String detail =
                    Files.isRegularFile(log) ? SecretRedactor.redact(readText(log), 2000) : "";
            throw new IOException("Profile distribution git clone failed: " + detail);
        }
        return new StagedDistribution(checkout, normalizedSource);
    }

    /** 规范化常见 git 地址简写，不改变本地目录和完整协议地址。 */
    private String normalizeDistributionSource(String rawSource) {
        String source = trimToNull(rawSource);
        if (source == null) {
            throw new IllegalArgumentException("Profile distribution source is required.");
        }
        if (source.matches("^github\\.com/[^/\\s]+/[^/\\s]+/?$")) {
            String normalized =
                    source.endsWith("/") ? source.substring(0, source.length() - 1) : source;
            return "https://" + normalized + (normalized.endsWith(".git") ? "" : ".git");
        }
        return source;
    }

    /** 把可能的用户目录本地来源解析为绝对路径；协议地址返回必不存在的哨兵路径。 */
    private Path localDistributionPath(String source) {
        if (source.contains("://") || source.matches("^[^/\\s]+@[^:]+:.*$")) {
            return root.resolve(".distribution-source-url").normalize();
        }
        String value = source;
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = System.getProperty("user.home", ".") + value.substring(1);
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    /** 按 distribution_owned 白名单应用分发文件，并保护本机用户数据。 */
    private void applyDistribution(
            Path source, Path target, Map<String, Object> manifest, boolean preserveConfig)
            throws Exception {
        for (String owned : distributionOwnedPaths(manifest)) {
            Path relative = ProfileArchive.safeRelativePath(owned);
            String top = relative.getName(0).toString();
            if (DISTRIBUTION_USER_DATA.contains(top)) {
                throw new IOException(
                        "distribution_owned cannot include protected user data: " + owned);
            }
            if (preserveConfig && "config.yml".equals(relative.toString().replace('\\', '/'))) {
                continue;
            }
            String normalized = relative.toString().replace('\\', '/');
            if (MANIFEST_FILE.equals(normalized) || ENV_TEMPLATE_FILE.equals(normalized)) {
                continue;
            }
            Path sourcePath = source.resolve(relative).normalize();
            Path targetPath = target.resolve(relative).normalize();
            if (!sourcePath.startsWith(source) || !targetPath.startsWith(target)) {
                throw new IOException("Distribution path escapes its profile boundary: " + owned);
            }
            deleteTree(targetPath);
            copyIfExists(sourcePath, targetPath);
        }
    }

    /** 读取并校验分发清单声明的所有权路径。 */
    private List<String> distributionOwnedPaths(Map<String, Object> manifest) throws IOException {
        Object raw = manifest == null ? null : manifest.get("distribution_owned");
        List<String> result = new ArrayList<String>();
        if (raw != null && !(raw instanceof Iterable)) {
            throw new IOException("distribution_owned must be a list.");
        }
        if (raw instanceof Iterable) {
            for (Object item : (Iterable<?>) raw) {
                String path = text(item);
                if (path.length() == 0) {
                    continue;
                }
                ProfileArchive.safeRelativePath(path);
                if (!result.contains(path)) {
                    result.add(path);
                }
            }
        }
        if (result.isEmpty()) {
            result.addAll(DEFAULT_DISTRIBUTION_OWNED);
        }
        return result;
    }

    /** 在修改目标 Profile 前校验分发清单中会影响版本、复制和凭据模板的字段。 */
    private void validateDistributionManifest(Map<String, Object> manifest) throws IOException {
        distributionOwnedPaths(manifest);
        validateDistributionVersionRequirement(manifest);
        Object rawRequirements = manifest.get("env_requires");
        if (rawRequirements == null) {
            return;
        }
        if (!(rawRequirements instanceof Iterable)) {
            throw new IOException("env_requires must be a list.");
        }
        for (Object item : (Iterable<?>) rawRequirements) {
            if (!(item instanceof Map)) {
                throw new IOException("env_requires entries must be mappings.");
            }
            Map<String, Object> requirement = stringMap((Map<?, ?>) item);
            String name = text(requirement.get("name"));
            if (!name.matches("^[A-Z][A-Z0-9_]{0,127}$")) {
                throw new IOException("Invalid env_requires name: " + name);
            }
            Object required = requirement.get("required");
            if (required != null && !(required instanceof Boolean)) {
                throw new IOException("env_requires required must be a boolean: " + name);
            }
        }
    }

    /** 校验分发声明的当前应用版本比较式，裸版本按最低版本处理。 */
    private void validateDistributionVersionRequirement(Map<String, Object> manifest)
            throws IOException {
        String spec = text(manifest.get(DISTRIBUTION_REQUIRES_FIELD));
        if (spec.length() == 0) {
            return;
        }
        java.util.regex.Matcher matcher =
                Pattern.compile("^(>=|<=|==|!=|>|<)\\s*(.+)$").matcher(spec);
        String operator = ">=";
        String target = spec;
        if (matcher.matches()) {
            operator = matcher.group(1);
            target = matcher.group(2).trim();
        }
        int comparison;
        String current = new AppVersionService(new AppConfig()).currentVersion();
        try {
            comparison = compareDistributionVersions(current, target);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid " + DISTRIBUTION_REQUIRES_FIELD + ": " + spec, e);
        }
        boolean satisfied;
        if (">=".equals(operator)) {
            satisfied = comparison >= 0;
        } else if ("<=".equals(operator)) {
            satisfied = comparison <= 0;
        } else if ("==".equals(operator)) {
            satisfied = comparison == 0;
        } else if ("!=".equals(operator)) {
            satisfied = comparison != 0;
        } else if (">".equals(operator)) {
            satisfied = comparison > 0;
        } else {
            satisfied = comparison < 0;
        }
        if (!satisfied) {
            throw new IOException(
                    "This distribution requires solonclaw "
                            + operator
                            + target
                            + ", but the current version is "
                            + current
                            + ".");
        }
    }

    /** 按主、次、补丁三段比较版本，预发布与构建后缀不影响分发约束。 */
    private static int compareDistributionVersions(String left, String right) {
        int[] leftParts = distributionVersionParts(left);
        int[] rightParts = distributionVersionParts(right);
        for (int i = 0; i < leftParts.length; i++) {
            if (leftParts[i] != rightParts[i]) {
                return leftParts[i] < rightParts[i] ? -1 : 1;
            }
        }
        return 0;
    }

    /** 将版本规范化为三段整数。 */
    private static int[] distributionVersionParts(String value) {
        String normalized = text(value);
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        int suffix = normalized.indexOf('-');
        int build = normalized.indexOf('+');
        int end = normalized.length();
        if (suffix >= 0) {
            end = Math.min(end, suffix);
        }
        if (build >= 0) {
            end = Math.min(end, build);
        }
        String[] values = normalized.substring(0, end).split("\\.", -1);
        if (values.length == 0) {
            throw new IllegalArgumentException("Unparseable version: " + value);
        }
        int[] result = new int[] {0, 0, 0};
        for (int i = 0; i < Math.min(values.length, result.length); i++) {
            if (!values[i].matches("[0-9]+")) {
                throw new IllegalArgumentException("Unparseable version: " + value);
            }
            try {
                result[i] = Integer.parseInt(values[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Unparseable version: " + value, e);
            }
        }
        return result;
    }

    /** 读取 Profile 分发清单。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readManifest(Path home, boolean required) throws IOException {
        Path manifest = home.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest)) {
            if (required) {
                throw new IOException("Distribution source is missing " + MANIFEST_FILE + ".");
            }
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> data;
        try {
            Object parsed =
                    new Yaml(new SafeConstructor(new LoaderOptions())).load(readText(manifest));
            if (!(parsed instanceof Map)) {
                throw new IOException(MANIFEST_FILE + " must contain a mapping.");
            }
            data = stringMap((Map<?, ?>) parsed);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid distribution manifest: " + manifest, e);
        }
        if (text(data.get("name")).length() == 0) {
            throw new IOException(MANIFEST_FILE + " is missing 'name'.");
        }
        return data;
    }

    /** 写回已解析的分发清单。 */
    private void writeManifest(Path home, Map<String, Object> manifest) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        writeAtomically(home.resolve(MANIFEST_FILE), new Yaml(options).dump(manifest));
    }

    /** 读取 Profile 本机元数据；不存在时返回空映射。 */
    private Map<String, Object> readMetadata(Path home) throws IOException {
        Path metadata = home.resolve(METADATA_FILE);
        return Files.isRegularFile(metadata)
                ? readJson(metadata, false)
                : new LinkedHashMap<String, Object>();
    }

    /** 生成可随备份迁移且不携带本机别名的 Profile 元数据。 */
    private Map<String, Object> portableMetadata(Map<String, Object> metadata, String profileName) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String description = text(metadata == null ? null : metadata.get("description"));
        if (description.length() > 0) {
            result.put("name", profileName);
            result.put("description", description);
            result.put(
                    "description_auto",
                    Boolean.valueOf(
                            metadata != null
                                    && Boolean.TRUE.equals(metadata.get("description_auto"))));
            result.put("aliases", new ArrayList<String>());
        }
        return result;
    }

    /** 写回 Profile 本机元数据。 */
    private void writeMetadata(Path home, Map<String, Object> metadata) throws IOException {
        writeJson(home.resolve(METADATA_FILE), metadata);
    }

    /** 从 Profile 元数据读取字符串别名列表。 */
    private List<String> aliasesFromMetadata(Map<String, Object> metadata) {
        List<String> result = new ArrayList<String>();
        Object raw = metadata.get("aliases");
        if (raw instanceof Iterable) {
            for (Object item : (Iterable<?>) raw) {
                String alias = text(item);
                if (alias.length() > 0 && !result.contains(alias)) {
                    result.add(alias);
                }
            }
        }
        return result;
    }

    /** 返回仍存在且确实指向该 Profile 的别名。 */
    private List<String> aliases(Path home) throws IOException {
        Map<String, Object> metadata = readMetadata(home);
        String profile = home.equals(root) ? "default" : home.getFileName().toString();
        List<String> result = new ArrayList<String>();
        for (String alias : aliasesFromMetadata(metadata)) {
            if (isOwnWrapper(wrapperPath(alias), profile)) {
                result.add(alias);
            }
        }
        return result;
    }

    /** 使用 Snack4 读取 JSON 对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Path path, boolean strict) throws IOException {
        try {
            Object parsed = ONode.deserialize(readText(path), Object.class);
            if (parsed instanceof Map) {
                return stringMap((Map<?, ?>) parsed);
            }
        } catch (Exception e) {
            if (strict) {
                throw new IOException("Invalid JSON file: " + path, e);
            }
        }
        if (strict) {
            throw new IOException("JSON file must contain an object: " + path);
        }
        return new LinkedHashMap<String, Object>();
    }

    /** 使用 Snack4 原子写入 JSON 对象。 */
    private void writeJson(Path path, Map<String, Object> data) throws IOException {
        writeAtomically(path, ONode.serialize(data) + System.lineSeparator());
    }

    /** 优先使用分发模板，否则根据清单生成不含真实凭据的环境变量示例。 */
    private void writeEnvExample(Path source, Path home, Map<String, Object> manifest)
            throws IOException {
        Path template = source.resolve(ENV_TEMPLATE_FILE).normalize();
        if (Files.isRegularFile(template) && !Files.isSymbolicLink(template)) {
            copyRegularFile(template, home.resolve(ENV_EXAMPLE_FILE));
            return;
        }
        Object raw = manifest.get("env_requires");
        if (!(raw instanceof Iterable)) {
            return;
        }
        StringBuilder content =
                new StringBuilder(
                        "# Environment variables required by this solonclaw distribution.\n"
                                + "# Copy to `.env` and fill in your own values before running.\n\n");
        boolean hasRequirements = false;
        for (Object item : (Iterable<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> requirement = stringMap((Map<?, ?>) item);
            String name = text(requirement.get("name"));
            if (!name.matches("^[A-Z][A-Z0-9_]{0,127}$")) {
                throw new IOException("Invalid env_requires name: " + name);
            }
            hasRequirements = true;
            String description = text(requirement.get("description"));
            if (description.length() > 0) {
                content.append("# ").append(description.replace('\n', ' ')).append('\n');
            }
            boolean required = !Boolean.FALSE.equals(requirement.get("required"));
            content.append("# (").append(required ? "required" : "optional").append(")\n");
            if (!required) {
                content.append("# ");
            }
            content.append(name)
                    .append('=')
                    .append(text(requirement.get("default")))
                    .append("\n\n");
        }
        if (hasRequirements) {
            writeAtomically(home.resolve(ENV_EXAMPLE_FILE), content.toString());
        }
    }

    /** 删除文件或目录树，符号链接只删除链接本身。 */
    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<Path>() {
                    /** 删除前确保目录对当前用户可写，兼容只读分发文件。 */
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path dir, BasicFileAttributes attributes) {
                        makeOwnerWritable(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    /** 删除普通文件和符号链接。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        deleteWritable(file);
                        return FileVisitResult.CONTINUE;
                    }

                    /** 子项删除后删除目录。 */
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException error)
                            throws IOException {
                        if (error != null) {
                            throw error;
                        }
                        deleteWritable(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** 删除路径，权限不足时补齐自身与父目录的 owner 写权限后重试。 */
    private static void deleteWritable(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (IOException first) {
            makeOwnerWritable(path);
            makeOwnerWritable(path == null ? null : path.getParent());
            try {
                Files.deleteIfExists(path);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    /** 为删除流程补齐当前用户写权限，不修改其他用户权限位。 */
    private static void makeOwnerWritable(Path path) {
        if (path == null) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.add(PosixFilePermission.OWNER_WRITE)) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (Exception e) {
            path.toFile().setWritable(true, true);
        }
    }

    /** 判断两个已存在路径是否指向同一个文件系统对象。 */
    private static boolean samePath(Path left, Path right) {
        try {
            return left != null && right != null && Files.isSameFile(left, right);
        } catch (IOException e) {
            return left != null
                    && right != null
                    && left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
        }
    }

    /** 保留调用方指定的精确归档路径。 */
    private static Path normalizeArchiveOutput(Path rawOutput) {
        return rawOutput.toAbsolutePath().normalize();
    }

    /** 原子写入 UTF-8 文本，文件系统不支持原子移动时回退普通替换。 */
    private static void writeAtomically(Path path, String content) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(
                temporary,
                content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 不覆盖目标地移动目录，原子移动不可用时回退普通移动。 */
    private static void moveWithoutReplace(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target);
        }
    }

    /** 收紧凭据文件到仅当前用户可读写。 */
    private static void tightenOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(
                    path,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception e) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }

    /** 读取 UTF-8 文本。 */
    private static String readText(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 从输入流读取一行确认文本。 */
    private static String readLine(InputStream input) throws IOException {
        if (input == null) {
            return null;
        }
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).readLine();
    }

    /** 规范化 Profile 或别名为小写标识。 */
    private static String normalizeName(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0) {
            throw new IllegalArgumentException("Profile name cannot be empty.");
        }
        return value;
    }

    /** 校验 Profile 名不会越过 profiles 目录且不使用保留名。 */
    private static void validateProfileName(String name) {
        if ("default".equals(name)) {
            return;
        }
        if (!PROFILE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + name + "'. Must match [a-z0-9][a-z0-9_-]{0,63}.");
        }
        if (RESERVED_NAMES.contains(name)) {
            throw new IllegalArgumentException("Profile name '" + name + "' is reserved.");
        }
    }

    /** 校验别名是安全单段命令名。 */
    private static void validateAliasName(String name) {
        if (!PROFILE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid alias name '" + name + "'. Must match [a-z0-9][a-z0-9_-]{0,63}.");
        }
    }

    /** 解析子命令选项，支持位置参数交错、长选项等号值和 `--` 终止标记。 */
    private static ParsedCommandArguments parseCommandArguments(
            List<String> args,
            int start,
            String action,
            Map<String, String> flagOptions,
            Map<String, String> valueOptions) {
        List<String> positionals = new ArrayList<String>();
        Set<String> flags = new LinkedHashSet<String>();
        Map<String, String> options = new LinkedHashMap<String, String>();
        boolean optionsEnded = false;
        for (int i = start; i < args.size(); i++) {
            String token = args.get(i);
            if (!optionsEnded && "--".equals(token)) {
                optionsEnded = true;
                continue;
            }
            if (optionsEnded || !isOptionToken(token)) {
                positionals.add(token);
                continue;
            }
            String option = token;
            String inlineValue = null;
            int equals = token != null && token.startsWith("--") ? token.indexOf('=') : -1;
            if (equals > 2) {
                option = token.substring(0, equals);
                inlineValue = token.substring(equals + 1);
            }
            String valueCanonical = valueOptions.get(option);
            if (valueCanonical != null) {
                String value = inlineValue;
                if (value == null) {
                    if (i + 1 >= args.size()
                            || args.get(i + 1) == null
                            || args.get(i + 1).trim().length() == 0) {
                        throw new ProfileUsageException(option + " requires a value.");
                    }
                    value = args.get(++i);
                } else if (value.trim().length() == 0) {
                    throw new ProfileUsageException(option + " requires a value.");
                }
                options.put(valueCanonical, value);
                continue;
            }
            String flagCanonical = flagOptions.get(option);
            if (flagCanonical != null) {
                if (inlineValue != null) {
                    throw new ProfileUsageException(option + " does not accept a value.");
                }
                flags.add(flagCanonical);
                continue;
            }
            throw new ProfileUsageException("Unknown profile " + action + " option: " + option);
        }
        return new ParsedCommandArguments(positionals, flags, options);
    }

    /** 校验位置参数数量，缺失或多余都属于命令行用法错误。 */
    private static void requirePositionals(
            ParsedCommandArguments parsed, int minimum, int maximum, String usage) {
        int size = parsed.positionals.size();
        if (size < minimum || size > maximum) {
            throw new ProfileUsageException("Usage: solonclaw " + usage);
        }
    }

    /** 构造选项别名到规范长选项的映射。 */
    private static Map<String, String> optionAliases(String... aliases) {
        if (aliases.length % 2 != 0) {
            throw new IllegalArgumentException("Option aliases must be supplied in pairs.");
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < aliases.length; i += 2) {
            result.put(aliases[i], aliases[i + 1]);
        }
        return result;
    }

    /** 返回不接受任何选项的解析配置。 */
    private static Map<String, String> noOptions() {
        return Collections.emptyMap();
    }

    /** 判断 token 是否会被命令行解析器视为选项。 */
    private static boolean isOptionToken(String value) {
        return value != null && value.length() > 1 && value.charAt(0) == '-';
    }

    /** 判断参数是否请求帮助。 */
    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    /** 返回 profile 命令帮助文本。 */
    private static String help() {
        return "Usage: solonclaw profile <subcommand>\n"
                + "  list\n"
                + "  use <name>\n"
                + "  create <name> [--clone] [--clone-all] [--clone-from SOURCE] [--no-alias]"
                + " [--description TEXT] [--no-skills]\n"
                + "  describe [name] [--text TEXT | --auto [--overwrite]] [--all --auto]\n"
                + "  show <name>\n"
                + "  rename <old> <new>\n"
                + "  delete <name> [-y]\n"
                + "  alias <name> [--remove] [--name NAME]\n"
                + "  export <name> [-o FILE]\n"
                + "  import <archive> [--name NAME]\n"
                + "  install <source> [--name NAME] [--alias] [--force] [-y]\n"
                + "  update <name> [--force-config] [-y]\n"
                + "  info <name>\n";
    }

    /** 创建不可修改字符串集合。 */
    private static Set<String> unmodifiableSet(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }

    /** 将通用映射键转换为字符串。 */
    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source != null) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return result;
    }

    /** 将值安全转换为字符串映射。 */
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map
                ? stringMap((Map<?, ?>) value)
                : new LinkedHashMap<String, Object>();
    }

    /** 将任意值转为去空白文本。 */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 读取非空文本或默认值。 */
    private static String valueOrDefault(Object value, String fallback) {
        String result = text(value);
        return result.length() == 0 ? fallback : result;
    }

    /** 将值转换为长整型，失败时返回 -1。 */
    private static long longValue(Object value) {
        try {
            return Long.parseLong(text(value));
        } catch (Exception e) {
            return -1L;
        }
    }

    /** 将值转换为整数，失败时返回默认值。 */
    private static int intValue(Object value, int fallback) {
        try {
            return Integer.parseInt(text(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 将空白字符串转换为 null。 */
    private static String trimToNull(String value) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        return value.trim();
    }

    /** 返回存在或缺失状态后缀。 */
    private static String status(boolean exists) {
        return exists ? " (exists)" : " (missing)";
    }

    /** 使用分隔符连接字符串列表。 */
    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(value);
        }
        return result.toString();
    }

    /** 输出存在的分发字段。 */
    private static void printIfPresent(PrintStream out, String label, Object value) {
        String text = text(value);
        if (text.length() > 0) {
            out.println(label + ": " + text);
        }
    }

    /** 返回不泄露敏感内容的简短异常文本。 */
    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return SecretRedactor.redact(
                message == null || message.trim().length() == 0
                        ? error.getClass().getSimpleName()
                        : message,
                1000);
    }

    /** 判断当前操作系统是否为 Windows。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 将启动命令转换为安全 shell 片段。 */
    private static String shellCommand(String command) {
        return command.matches("^[A-Za-z0-9_./-]+$")
                ? command
                : "'" + command.replace("'", "'\"'\"'") + "'";
    }

    /** 已完成 option/positional intermix 解析的子命令参数。 */
    private static final class ParsedCommandArguments {
        /** 按用户输入顺序保留的位置参数。 */
        private final List<String> positionals;

        /** 已出现的规范布尔选项。 */
        private final Set<String> flags;

        /** 规范长选项到最后一次输入值的映射。 */
        private final Map<String, String> options;

        /** 创建不可变使用边界内的解析结果。 */
        private ParsedCommandArguments(
                List<String> positionals, Set<String> flags, Map<String, String> options) {
            this.positionals = positionals;
            this.flags = flags;
            this.options = options;
        }

        /** 判断规范布尔选项是否出现。 */
        private boolean hasFlag(String option) {
            return flags.contains(option);
        }

        /** 返回规范带值选项的最后一次输入值。 */
        private String option(String option) {
            return options.get(option);
        }
    }

    /** 仅表示命令行 token 结构错误，调用方统一映射为退出码 2。 */
    private static final class ProfileUsageException extends IllegalArgumentException {
        /** 创建用法错误。 */
        private ProfileUsageException(String message) {
            super(message);
        }
    }

    /** 保存已暂存分发目录及其来源。 */
    private static final class StagedDistribution {
        /** 已暂存的分发根目录。 */
        private final Path directory;

        /** 用户可见来源 URL 或本地绝对路径。 */
        private final String provenance;

        /** 创建暂存分发描述。 */
        private StagedDistribution(Path directory, String provenance) {
            this.directory = directory;
            this.provenance = provenance;
        }
    }

    /** 保存网关端口及其是否由系统自动分配。 */
    private static final class PortSelection {
        /** 最终监听端口。 */
        private final int port;

        /** 是否由 Profile 管理器自动分配。 */
        private final boolean automatic;

        /** 创建端口选择结果。 */
        private PortSelection(int port, boolean automatic) {
            this.port = port;
            this.automatic = automatic;
        }
    }
}
