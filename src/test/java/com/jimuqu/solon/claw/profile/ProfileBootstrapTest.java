package com.jimuqu.solon.claw.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 Profile 选择在 Solon 与配置加载前完成，并从后续启动参数中移除。 */
class ProfileBootstrapTest {
    /** 每个测试独占的临时目录。 */
    @TempDir Path tempDir;

    /** 测试专用 Profile 管理器。 */
    private ProfileManager manager;

    /** 原始工作区系统属性。 */
    private String previousWorkspace;

    /** 原始 Profile 名系统属性。 */
    private String previousProfileName;

    /** 原始 Profile 根目录系统属性。 */
    private String previousProfileRoot;

    /** 创建 Profile 根目录并保存会被启动选择覆盖的系统属性。 */
    @BeforeEach
    void setUp() throws Exception {
        previousWorkspace = System.getProperty("solonclaw.workspace");
        previousProfileName = System.getProperty("solonclaw.profile.name");
        previousProfileRoot = System.getProperty("solonclaw.profile.root");
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        manager = new ProfileManager(root, tempDir.resolve("bin"), "solonclaw");
        execute("default", "create", "work", "--no-alias");
    }

    /** 恢复系统属性，避免影响同一 JVM 内的其他配置测试。 */
    @AfterEach
    void tearDown() {
        restore("solonclaw.workspace", previousWorkspace);
        restore("solonclaw.profile.name", previousProfileName);
        restore("solonclaw.profile.root", previousProfileRoot);
    }

    /** `-p` 选择仅影响本次命令，并把实际工作区重定向到命名 Profile。 */
    @Test
    void appliesExplicitShortProfileBeforeCliParsing() {
        ProfileBootstrap.Result result = prepare("-p", "work", "--cli", "--ask", "hello");

        assertThat(result.isHandled()).isFalse();
        assertThat(result.getProfileName()).isEqualTo("work");
        assertThat(result.getArguments()).containsExactly("--cli", "--ask", "hello");
        assertThat(System.getProperty("solonclaw.workspace"))
                .isEqualTo(manager.profileHome("work").toString());
        assertThat(System.getProperty("solonclaw.profile.name")).isEqualTo("work");
    }

    /** `--profile=name` 与 sticky use 使用同一套选择规则。 */
    @Test
    void appliesLongAndStickyProfileSelection() {
        ProfileBootstrap.Result explicit = prepare("--profile=work", "status");
        assertThat(explicit.getProfileName()).isEqualTo("work");
        assertThat(explicit.getArguments()).containsExactly("status");

        execute("default", "use", "work");
        ProfileBootstrap.Result sticky = prepare("status");
        assertThat(sticky.getProfileName()).isEqualTo("work");
        assertThat(System.getProperty("solonclaw.workspace"))
                .isEqualTo(manager.profileHome("work").toString());
    }

    /** sticky 文件指向缺失 Profile 时启动明确失败，不能静默切回 default。 */
    @Test
    void reportsMissingStickyProfileInsteadOfFallingBack() throws Exception {
        Files.writeString(manager.root().resolve("active_profile"), "missing\n");

        ProfileBootstrap.Result result = prepare("status");

        assertThat(result.isHandled()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(1);
    }

    /** `--ask` 后的 `-p` 属于用户提示词，不得被误解析成全局 Profile。 */
    @Test
    void leavesPromptTokensAfterAskUntouched() {
        ProfileBootstrap.Result result = prepare("--cli", "--ask", "keep", "-p", "literal");

        assertThat(result.getProfileName()).isEqualTo("default");
        assertThat(result.getArguments())
                .containsExactly("--cli", "--ask", "keep", "-p", "literal");
    }

    /** profile 管理命令在启动 Solon 前直接完成并返回退出码。 */
    @Test
    void handlesProfileCommandsWithoutStartingApplication() {
        ProfileBootstrap.Result result = prepare("profile", "list");

        assertThat(result.isHandled()).isTrue();
        assertThat(result.getExitCode()).isZero();
    }

    /** 子命令帮助必须在参数必填校验前成功返回。 */
    @Test
    void handlesProfileSubcommandHelpBeforeValidation() {
        String[][] actions = {
            {"list", "profile list"},
            {"use", "profile use <name>"},
            {"create", "--clone-from"},
            {"describe", "--overwrite"},
            {"delete", "--yes"},
            {"show", "profile show <name>"},
            {"alias", "--remove"},
            {"rename", "<new-name>"},
            {"export", "<name>.tar.gz"},
            {"import", "archive root name"},
            {"install", "--force"},
            {"update", "--force-config"},
            {"info", "profile info <name>"}
        };
        for (String[] action : actions) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ProfileBootstrap.Result result =
                    ProfileBootstrap.prepare(
                            new String[] {"profile", action[0], "--help"},
                            manager,
                            new ByteArrayInputStream(new byte[0]),
                            new PrintStream(out, true, StandardCharsets.UTF_8),
                            new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(result.isHandled()).isTrue();
            assertThat(result.getExitCode()).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains(action[1]);
            assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
        }

        ProfileBootstrap.Result unknown = prepare("profile", "missing", "--help");
        assertThat(unknown.getExitCode()).isEqualTo(2);
    }

    /** help 只在真实选项位置生效，不能吞掉带值选项内容或 `--` 后的位置文本。 */
    @Test
    void parsesHelpWithoutConfusingOptionValuesOrPassthroughText() throws Exception {
        CapturedPreparation value =
                prepareCaptured(
                        "profile",
                        "create",
                        "--description",
                        "--help",
                        "--no-alias",
                        "literal-help");
        assertThat(value.result.getExitCode()).isZero();
        assertThat(value.stdout).doesNotContain("Usage:");
        assertThat(value.stderr).isEmpty();
        assertThat(manager.profileView("literal-help").getDescription()).isEqualTo("--help");

        CapturedPreparation passthrough =
                prepareCaptured("profile", "create", "--no-alias", "after-dash", "--", "--help");
        assertThat(passthrough.result.getExitCode()).isEqualTo(2);
        assertThat(passthrough.stdout).isEmpty();
        assertThat(passthrough.stderr).contains("Usage:");
        assertThat(manager.profileHome("after-dash")).doesNotExist();

        CapturedPreparation actualHelp =
                prepareCaptured("profile", "create", "--no-alias", "unused", "--help");
        assertThat(actualHelp.result.getExitCode()).isZero();
        assertThat(actualHelp.stdout).contains("Usage: solonclaw profile create");
        assertThat(actualHelp.stderr).isEmpty();
        assertThat(manager.profileHome("unused")).doesNotExist();
    }

    /** Profile action 区分大小写，且不额外接受 `profile help` 动作。 */
    @Test
    void requiresLowercaseActionAndRejectsProfileHelpAlias() {
        CapturedPreparation uppercase = prepareCaptured("profile", "List");
        assertThat(uppercase.result.getExitCode()).isEqualTo(2);
        assertThat(uppercase.stdout).isEmpty();
        assertThat(uppercase.stderr).contains("Unknown profile subcommand: List");

        CapturedPreparation helpAlias = prepareCaptured("profile", "help");
        assertThat(helpAlias.result.getExitCode()).isEqualTo(2);
        assertThat(helpAlias.stdout).isEmpty();
        assertThat(helpAlias.stderr).contains("Unknown profile subcommand: help");
    }

    /** `--` 与 MCP 子进程参数区后的 Profile 风格参数必须原样传递。 */
    @Test
    void preservesProfileFlagsInsidePassthroughArguments() {
        ProfileBootstrap.Result separator = prepare("status", "--", "--profile", "literal");
        assertThat(separator.getProfileName()).isEqualTo("default");
        assertThat(separator.getArguments())
                .containsExactly("status", "--", "--profile", "literal");

        ProfileBootstrap.Result mcp =
                prepare("mcp", "add", "demo", "--args", "docker", "--profile", "desktop");
        assertThat(mcp.getProfileName()).isEqualTo("default");
        assertThat(mcp.getArguments())
                .containsExactly("mcp", "add", "demo", "--args", "docker", "--profile", "desktop");
    }

    /** 全局 Profile 选择在顶层子命令后仍然生效。 */
    @Test
    void appliesProfileSelectionAfterTopLevelCommand() {
        ProfileBootstrap.Result result = prepare("status", "-p", "work");

        assertThat(result.getProfileName()).isEqualTo("work");
        assertThat(result.getArguments()).containsExactly("status");
    }

    /** gateway run 会切换为真实服务端启动，并为命名 Profile 注入持久化独立端口。 */
    @Test
    void preparesForegroundProfileGatewayAsServerMode() {
        ProfileBootstrap.Result result =
                prepare("-p", "work", "gateway", "run", "--server.host=127.0.0.1");

        assertThat(result.isHandled()).isFalse();
        assertThat(result.getProfileName()).isEqualTo("work");
        assertThat(result.getArguments())
                .contains("--server.host=127.0.0.1")
                .anyMatch(argument -> argument.startsWith("--server.port="));
        assertThat(System.getProperty("solonclaw.workspace"))
                .isEqualTo(manager.profileHome("work").toString());
    }

    /** gateway status 在 Solon 初始化前读取目标 Profile 状态并直接退出。 */
    @Test
    void handlesProfileGatewayStatusBeforeStartingApplication() {
        ProfileBootstrap.Result result = prepare("--profile=work", "gateway", "status");

        assertThat(result.isHandled()).isTrue();
        assertThat(result.getExitCode()).isZero();
    }

    /** gateway list 按稳定顺序输出 default 与全部命名 Profile 的隔离状态。 */
    @Test
    void listsAllProfileGatewaysBeforeStartingApplication() {
        CapturedPreparation result = prepareCaptured("gateway", "list");

        assertThat(result.result.isHandled()).isTrue();
        assertThat(result.result.getExitCode()).isZero();
        assertThat(result.stdout).contains("Gateway Status", "Profile: default", "Profile: work");
        assertThat(result.stdout.indexOf("Profile: default"))
                .isLessThan(result.stdout.indexOf("Profile: work"));
        assertThat(result.stderr).isEmpty();
    }

    /** gateway status --all 复用单 Profile 状态契约并覆盖全部 Profile。 */
    @Test
    void handlesAllProfileGatewayStatuses() {
        CapturedPreparation result = prepareCaptured("gateway", "status", "--all", "--deep");

        assertThat(result.result.isHandled()).isTrue();
        assertThat(result.result.getExitCode()).isZero();
        assertThat(result.stdout).contains("Profile: default", "Profile: work");
        assertThat(result.stderr).isEmpty();
    }

    /** gateway stop --all 即使网关均未运行也应幂等遍历并清理全部状态。 */
    @Test
    void stopsAllProfileGatewaysIdempotently() {
        CapturedPreparation result = prepareCaptured("gateway", "stop", "--all");

        assertThat(result.result.isHandled()).isTrue();
        assertThat(result.result.getExitCode()).isZero();
        assertThat(result.stdout)
                .contains(
                        "Stopped gateway for profile 'default'.",
                        "Stopped gateway for profile 'work'.");
        assertThat(result.stderr).isEmpty();
    }

    /** 前台 run 只能绑定单个工作区，明确拒绝无法兑现的 --all 语义。 */
    @Test
    void rejectsForegroundGatewayRunForAllProfiles() {
        CapturedPreparation result = prepareCaptured("gateway", "run", "--all");

        assertThat(result.result.isHandled()).isTrue();
        assertThat(result.result.getExitCode()).isEqualTo(1);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr)
                .contains("gateway run --all is not supported", "gateway start --all");
    }

    /** 批量生命周期仅接受已声明动作，避免 --all 被静默透传给后续 CLI。 */
    @Test
    void rejectsAllFlagForUnknownGatewayAction() {
        CapturedPreparation result = prepareCaptured("gateway", "unknown", "--all");

        assertThat(result.result.isHandled()).isTrue();
        assertThat(result.result.getExitCode()).isEqualTo(1);
        assertThat(result.stderr).contains("--all is only supported");
    }

    /** default 复用网关运行时，命名 Profile 的启动与运行默认硬失败。 */
    @Test
    void rejectsNamedGatewayWhenDefaultMultiplexerIsRunning() throws Exception {
        Files.writeString(
                manager.root().resolve("config.yml"),
                "solonclaw:\n  gateway:\n    multiplexProfiles: true\n");
        writeFakeRunningGateway(manager.root(), "default", 8080);

        ProfileBootstrap.Result start = prepare("-p", "work", "gateway", "start");

        assertThat(start.isHandled()).isTrue();
        assertThat(start.getExitCode()).isEqualTo(1);
    }

    /** --force 显式绕过复用网关启动保护，继续准备前台命名 Profile 网关。 */
    @Test
    void forceBypassesNamedGatewayMultiplexGuard() throws Exception {
        Files.writeString(
                manager.root().resolve("config.yml"),
                "solonclaw:\n  gateway:\n    multiplexProfiles: true\n");
        writeFakeRunningGateway(manager.root(), "default", 8080);

        ProfileBootstrap.Result result = prepare("-p", "work", "gateway", "run", "--force");

        assertThat(result.isHandled()).isFalse();
        assertThat(result.getArguments()).anyMatch(value -> value.startsWith("--server.port="));
        assertThat(result.getArguments()).doesNotContain("--force");
    }

    /** 写入由当前测试 JVM 拥有的真实结构状态，使网关存活校验不依赖伪 PID。 */
    static void writeFakeRunningGateway(Path home, String profile, int port) throws Exception {
        com.jimuqu.solon.claw.config.AppConfig config =
                new com.jimuqu.solon.claw.config.AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getDashboard().setBindPort(port);
        com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService status =
                new com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService(
                        config, profile);
        status.writePidFile();
        status.writeState("running", "test");
    }

    /** 执行 Profile 管理命令并要求成功。 */
    private void execute(String selectedProfile, String... args) {
        int exitCode =
                manager.execute(
                        Arrays.asList(args),
                        selectedProfile,
                        new ByteArrayInputStream(new byte[0]),
                        new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));
        assertThat(exitCode).isZero();
    }

    /** 运行启动前 Profile 参数处理。 */
    private ProfileBootstrap.Result prepare(String... args) {
        return ProfileBootstrap.prepare(
                args,
                manager,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    /** 运行启动前解析并保留标准输出和错误输出。 */
    private CapturedPreparation prepareCaptured(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ProfileBootstrap.Result result =
                ProfileBootstrap.prepare(
                        args,
                        manager,
                        new ByteArrayInputStream(new byte[0]),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new CapturedPreparation(
                result, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** 恢复单个系统属性。 */
    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** 保存一次启动前解析的结果与双通道输出。 */
    private static final class CapturedPreparation {
        /** 启动前处理结果。 */
        private final ProfileBootstrap.Result result;

        /** 标准输出。 */
        private final String stdout;

        /** 标准错误。 */
        private final String stderr;

        /** 创建捕获结果。 */
        private CapturedPreparation(ProfileBootstrap.Result result, String stdout, String stderr) {
            this.result = result;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
