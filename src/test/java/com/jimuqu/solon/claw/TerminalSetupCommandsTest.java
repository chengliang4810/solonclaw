package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalModelPicker;
import com.jimuqu.solon.claw.cli.TerminalSetupCommands;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class TerminalSetupCommandsTest {
    @Test
    void shouldRenderSetupOverviewWithRunnableSections() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-setup")));

        String text = commands.render("/setup");

        assertThat(commands.isSetupCommand("/setup")).isTrue();
        assertThat(text)
                .contains("solonclaw setup")
                .contains("solonclaw setup model")
                .contains("solonclaw setup gateway")
                .contains("solonclaw config path");
    }

    @Test
    void shouldRenderNonInteractiveSetupGuidance() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-setup-noninteractive")));

        String text = commands.render("/setup --non-interactive");

        assertThat(text)
                .contains("非交互初始化")
                .contains("solonclaw model set")
                .contains("solonclaw setup gateway")
                .contains("solonclaw doctor")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderSupportedSetupSectionsAsLocalGuidance() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-setup-sections")));

        String terminal = commands.render("/setup terminal");
        String tools = commands.render("/setup tools");
        String agent = commands.render("/setup agent");
        String tts = commands.render("/setup tts");

        assertThat(commands.isSetupCommand("/setup terminal")).isTrue();
        assertThat(terminal)
                .contains("终端初始化")
                .contains("solonclaw")
                .contains("/help")
                .doesNotContain("未知 setup/config 命令");
        assertThat(tools)
                .contains("工具初始化")
                .contains("/security policy")
                .contains("/reload-mcp")
                .doesNotContain("未知 setup/config 命令");
        assertThat(agent)
                .contains("Agent 初始化")
                .contains("/agent")
                .contains("/goal")
                .doesNotContain("未知 setup/config 命令");
        assertThat(tts)
                .contains("语音初始化")
                .contains("solonclaw config set")
                .contains("当前版本不提供语音模式入口")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderQuickSetupMissingItems() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-setup-quick"));
        config.getProviders().get("default").setApiKey("");
        TerminalSetupCommands commands = commands(config);

        String text = commands.render("/setup --quick");

        assertThat(text)
                .contains("快速初始化")
                .contains("model=missing_api_key")
                .contains("run=solonclaw setup model")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldResetRuntimeConfigFileThroughSetupReset() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-setup-reset");
        AppConfig config = config(runtimeHome);
        RuntimeConfigResolver.initialize(runtimeHome.toString())
                .setFileValue("providers.default.defaultModel", "before-reset");
        TerminalSetupCommands commands = commands(config);

        String text = commands.render("/setup --reset");

        assertThat(text)
                .contains("runtime/config.yml 已重置")
                .contains(runtimeHome.resolve("config.yml").toString())
                .contains("next=solonclaw setup model");
        assertThat(Files.exists(runtimeHome.resolve("config.yml"))).isFalse();
    }

    @Test
    void shouldRenderModelSetupFromSharedProviderConfiguration() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-model")));

        String text = commands.render("/setup model");

        assertThat(text)
                .contains("模型配置")
                .contains("active.provider=default")
                .contains("active.model=gpt-main")
                .contains("api_url=https://api.openai.com/v1")
                .contains("api_key=missing")
                .contains("solonclaw config set providers.default.apiKey")
                .contains("/model pick");
    }

    @Test
    void shouldRenderSupportedModelProviderTemplatesInSetupGuidance() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-model-templates")));

        String text = commands.render("/setup model");

        assertThat(text)
                .contains("available.providers=openai,openai-responses,ollama,gemini,anthropic")
                .contains("gemini: baseUrl=https://generativelanguage.googleapis.com, model=gemini-2.5-pro")
                .contains("solonclaw model set --provider gemini");
    }

    @Test
    void shouldRenderModelRefreshAsRuntimeRefreshGuidance() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-model-refresh")));

        String text = commands.render("/model --refresh");

        assertThat(commands.isSetupCommand("/model --refresh")).isTrue();
        assertThat(text)
                .contains("模型列表刷新")
                .contains("runtime/config.yml")
                .contains("active.provider=default")
                .contains("/model pick")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderGatewaySetupOnlyForDomesticChannels() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-gateway")));

        String text = commands.render("/setup gateway");

        assertThat(text)
                .contains("feishu")
                .contains("dingtalk")
                .contains("wecom")
                .contains("weixin")
                .contains("qqbot")
                .contains("yuanbao")
                .doesNotContain("sms")
                .doesNotContain("webhook");
    }

    @Test
    void shouldConfigureDomesticGatewayChannelThroughSetupGatewayCommand() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-gateway-set");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output =
                commands.render(
                        "/setup gateway feishu --app-id cli_test_app --app-secret "
                                + "Sk-Test-FeishuSecret123 --enabled true");

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(output)
                .contains("渠道配置已写入")
                .contains("channel=feishu")
                .contains("enabled=true")
                .contains("appId=cli_test_app")
                .contains("appSecret=***")
                .doesNotContain("Sk-Test-FeishuSecret123");
        assertThat(file)
                .contains("solonclaw:")
                .contains("channels:")
                .contains("feishu:")
                .contains("enabled: 'true'")
                .contains("appId: cli_test_app")
                .contains("appSecret: Sk-Test-FeishuSecret123");
    }

    @Test
    void shouldRejectUnsupportedGatewayChannel() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-gateway-unsupported");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output =
                commands.render(
                        "/setup gateway unknown --token Sk-Test-UnknownSecret123 --enabled true");

        assertThat(output).contains("不支持的渠道").contains("feishu,dingtalk,wecom,weixin,qqbot,yuanbao");
        assertThat(Files.exists(runtimeHome.resolve("config.yml"))).isFalse();
    }

    @Test
    void shouldExplainUnsupportedGatewayField() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-gateway-unsupported-field");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output =
                commands.render(
                        "/setup gateway feishu --enabled true --app-id cli_test_app "
                                + "--app-secret Sk-Test-FeishuSecret123 "
                                + "--verification-token Sk-Test-VerificationToken123");

        assertThat(output)
                .contains("渠道 feishu 不支持配置项：verification-token")
                .contains("可用配置项：")
                .doesNotContain("用法：solonclaw setup gateway");
        assertThat(Files.exists(runtimeHome.resolve("config.yml"))).isFalse();
    }

    @Test
    void shouldRenderConfigPathShowAndCheck() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-config");
        AppConfig config = config(runtimeHome);
        RuntimeConfigResolver.initialize(runtimeHome.toString())
                .setFileValue("providers.default.defaultModel", "gpt-main");
        TerminalSetupCommands commands = commands(config);

        assertThat(commands.render("/config path"))
                .contains(runtimeHome.resolve("config.yml").toString());
        assertThat(commands.render("/config show"))
                .contains("runtime/config.yml")
                .contains("providers.default.defaultModel");
        assertThat(commands.render("/config check")).contains("配置检查").contains("has_issues=");
    }

    @Test
    void shouldShowDynamicProviderAfterModelSet() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-config-dynamic-provider");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        commands.render(
                "/model set --provider local-openai --base-url http://127.0.0.1:9999/v1 "
                        + "--api-key Sk-Test-DynamicProvider123 --model test-model "
                        + "--dialect openai");
        String text = commands.render("/config show");

        assertThat(text)
                .contains("providers.local-openai.baseUrl=http://127.0.0.1:9999/v1")
                .contains("providers.local-openai.defaultModel=test-model")
                .contains("providers.local-openai.apiKey=***")
                .doesNotContain("Sk-Test-DynamicProvider123");
    }

    @Test
    void shouldRenderConfigEnvPathAsRuntimeConfigGuidance() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-config-env-path");
        TerminalSetupCommands commands = commands(config(runtimeHome));

        String text = commands.render("/config env-path");

        assertThat(text)
                .contains("runtime.config=")
                .contains(runtimeHome.resolve("config.yml").toString())
                .contains("本项目使用 runtime/config.yml 作为本地配置入口")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderConfigEditCommandWithoutOpeningEditor() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-config-edit");
        TerminalSetupCommands commands = commands(config(runtimeHome));

        String text = commands.render("/config edit");

        assertThat(text)
                .contains("配置文件")
                .contains(runtimeHome.resolve("config.yml").toString())
                .contains("solonclaw config set")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderConfigMigrateAsNoOpDiagnostics() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-config-migrate");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String text = commands.render("/config migrate");

        assertThat(text)
                .contains("配置迁移")
                .contains("has_issues=false")
                .contains("runtime/config.yml")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderDoctorSummaryWithoutLeakingSecrets() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-doctor");
        AppConfig config = config(runtimeHome);
        config.getProviders().get("default").setApiKey("Sk-Test-DoctorSecret123");
        config.getChannels().getFeishu().setEnabled(true);
        config.getChannels().getFeishu().setAppId("doctor_app");
        config.getChannels().getFeishu().setAppSecret("Sk-Test-DoctorFeishu456");
        TerminalSetupCommands commands = commands(config);

        String output = commands.render("/doctor");

        assertThat(commands.isSetupCommand("/doctor")).isTrue();
        assertThat(output)
                .contains("Solon Claw Doctor")
                .contains("runtime.config=")
                .contains("config.has_issues=false")
                .contains("model.provider=default")
                .contains("model.api_key=configured")
                .contains("channels.configured=feishu")
                .contains("channels.missing=")
                .contains("next=")
                .doesNotContain("Sk-Test-DoctorSecret123")
                .doesNotContain("Sk-Test-DoctorFeishu456");
    }

    @Test
    void shouldRenderDoctorFromRuntimeConfigAfterTuiSetupWrites() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-doctor-runtime");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);
        com.jimuqu.solon.claw.support.RuntimeSetupService setupService =
                new com.jimuqu.solon.claw.support.RuntimeSetupService(config);
        com.jimuqu.solon.claw.support.RuntimeSetupService.ModelSetupRequest request =
                new com.jimuqu.solon.claw.support.RuntimeSetupService.ModelSetupRequest();
        request.setProviderKey("default");
        request.setProviderName("DefaultProvider");
        request.setBaseUrl("https://api.example.com/v1");
        request.setApiKey("Sk-Test-RuntimeDoctorSecret123");
        request.setModel("mimo-v2.5-pro");
        request.setDialect("openai");

        setupService.configureModel(request);
        String output = commands.render("/doctor");

        assertThat(output)
                .contains("config.has_issues=false")
                .contains("model.provider=default")
                .contains("model.default=mimo-v2.5-pro")
                .contains("model.api_key=configured")
                .doesNotContain("Sk-Test-RuntimeDoctorSecret123")
                .doesNotContain("gpt-5.4");
    }

    @Test
    void shouldRenderDoctorFromRuntimeChannelConfigAfterTuiSetupWrites() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-doctor-runtime-channel");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);
        com.jimuqu.solon.claw.support.RuntimeSetupService setupService =
                new com.jimuqu.solon.claw.support.RuntimeSetupService(config);
        java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>();
        values.put("enabled", "true");
        values.put("appId", "doctor_app");
        values.put("appSecret", "Sk-Test-RuntimeChannelSecret123");

        setupService.configureGatewayChannel("feishu", values);
        String output = commands.render("/doctor");

        assertThat(output)
                .contains("config.has_issues=false")
                .contains("channels.configured=feishu")
                .contains("channels.missing=(none)")
                .doesNotContain("Sk-Test-RuntimeChannelSecret123");
    }

    @Test
    void shouldRenderDoctorFixGuidance() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-doctor-fix"));
        config.getProviders().get("default").setApiKey("");
        TerminalSetupCommands commands = commands(config);

        String output = commands.render("/doctor --fix");

        assertThat(output)
                .contains("Doctor 自动修复")
                .contains("model.api_key=missing")
                .contains("next=run solonclaw setup model")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderDoctorAckAsUnsupportedLocalAdvisoryState() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-doctor-ack")));

        String output = commands.render("/doctor --ack SC-2026-001");

        assertThat(output)
                .contains("Doctor advisory ack")
                .contains("advisory=SC-2026-001")
                .contains("当前版本没有待确认的本地安全公告")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderLocalStatusVersionAndLogoutWithoutModelCall() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-system-commands");
        AppConfig config = config(runtimeHome);
        config.getProviders().get("default").setApiKey("Sk-Test-SystemSecret123");
        TerminalSetupCommands commands = commands(config);

        String status = commands.render("/status");
        String version = commands.render("/version");
        String logout = commands.render("/logout");

        assertThat(commands.isSetupCommand("/status")).isTrue();
        assertThat(commands.isSetupCommand("/version")).isTrue();
        assertThat(commands.isSetupCommand("/logout")).isTrue();
        assertThat(status)
                .contains("Solon Claw Status")
                .contains("runtime.config=")
                .contains(runtimeHome.resolve("config.yml").toString())
                .contains("model.provider=default")
                .contains("channels.configured=")
                .doesNotContain("Sk-Test-SystemSecret123")
                .doesNotContain("未知 setup/config 命令");
        assertThat(version)
                .contains("Solon Claw Version")
                .contains("version=")
                .contains("deployment=")
                .doesNotContain("未知 setup/config 命令");
        assertThat(logout)
                .contains("本地登出")
                .contains("当前版本没有独立的终端登录态")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderReferenceCommandGuidanceWithoutStartingServer() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-reference-commands")));

        String postinstall = commands.render("/postinstall");
        String login = commands.render("/login --provider openai");
        String auth = commands.render("/auth list");
        String fallback = commands.render("/fallback list");
        String secrets = commands.render("/secrets bitwarden");
        String proxy = commands.render("/proxy status");
        String migrate = commands.render("/migrate xai");
        String send = commands.render("/send feishu hello");
        String mcp = commands.render("/mcp list");

        assertThat(commands.isSetupCommand("/postinstall")).isTrue();
        assertThat(postinstall)
                .contains("Postinstall")
                .contains("java -jar")
                .contains("solonclaw setup")
                .doesNotContain("未知 setup/config 命令");
        assertThat(login)
                .contains("登录与认证")
                .contains("solonclaw model set")
                .contains("API Key")
                .doesNotContain("OAuth")
                .doesNotContain("未知 setup/config 命令");
        assertThat(auth)
                .contains("认证状态")
                .contains("default api_key=missing")
                .contains("solonclaw auth add")
                .doesNotContain("未知 setup/config 命令");
        assertThat(fallback)
                .contains("Fallback Providers")
                .contains("solonclaw fallback add")
                .doesNotContain("solonclaw config set fallbackProviders.0")
                .doesNotContain("未知 setup/config 命令");
        assertThat(secrets)
                .contains("外部密钥源")
                .contains("暂未启用")
                .doesNotContain("未知 setup/config 命令");
        assertThat(proxy)
                .contains("本地代理")
                .contains("不提供 OpenAI 兼容 API Server")
                .doesNotContain("未知 setup/config 命令");
        assertThat(migrate)
                .contains("模型迁移")
                .contains("当前保留协议")
                .contains("openai")
                .doesNotContain("未知 setup/config 命令");
        assertThat(send)
                .contains("发送消息")
                .contains("send_message")
                .contains("home channel")
                .doesNotContain("未知 setup/config 命令");
        assertThat(mcp)
                .contains("MCP 配置")
                .contains("Dashboard")
                .contains("/reload-mcp")
                .contains("solonclaw config set")
                .doesNotContain("已连接")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderRetainedTopLevelCommandGuidanceWithoutModelRun() throws Exception {
        TerminalSetupCommands commands =
                commands(config(Files.createTempDirectory("solonclaw-retained-commands")));

        String hooks = commands.render("/hooks list");
        String dump = commands.render("/dump --json");
        String backup = commands.render("/backup create");
        String checkpoints = commands.render("/checkpoints list");
        String importCommand = commands.render("/import sessions file.json");
        String bundles = commands.render("/bundles list");
        String memory = commands.render("/memory status");
        String dashboard = commands.render("/dashboard start");
        String logs = commands.render("/logs --tail 20");
        String promptSize = commands.render("/prompt-size hello world");

        assertThat(commands.isSetupCommand("/hooks list")).isTrue();
        assertThat(hooks)
                .contains("Hooks")
                .contains("/plugins")
                .contains("runtime/config.yml")
                .doesNotContain("未知 setup/config 命令");
        assertThat(dump)
                .contains("诊断导出")
                .contains("/debug")
                .contains("/doctor")
                .doesNotContain("未知 setup/config 命令");
        assertThat(backup)
                .contains("运行目录备份")
                .contains("runtime.home")
                .contains("config.yml")
                .doesNotContain("未知 setup/config 命令");
        assertThat(checkpoints)
                .contains("Checkpoints")
                .contains("/rollback")
                .contains("会话")
                .doesNotContain("未知 setup/config 命令");
        assertThat(importCommand)
                .contains("导入")
                .contains("Skills")
                .contains("/skills")
                .doesNotContain("未知 setup/config 命令");
        assertThat(bundles)
                .contains("Bundles")
                .contains("/skills")
                .contains("Skills Hub")
                .doesNotContain("未知 setup/config 命令");
        assertThat(memory)
                .contains("记忆")
                .contains("runtime/config.yml")
                .contains("/config")
                .doesNotContain("未知 setup/config 命令");
        assertThat(dashboard)
                .contains("Dashboard")
                .contains("java -jar")
                .contains("http://127.0.0.1")
                .doesNotContain("未知 setup/config 命令");
        assertThat(logs)
                .contains("日志")
                .contains("runtime")
                .contains("/debug")
                .doesNotContain("未知 setup/config 命令");
        assertThat(promptSize)
                .contains("Prompt Size")
                .contains("token")
                .contains("/usage")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldRenderPairingGuidanceAsLocalTerminalCommand() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-pairing-guidance");
        TerminalSetupCommands commands = commands(config(runtimeHome));

        String list = commands.render("/pairing list");
        String approve = commands.render("/pairing approve feishu 123456");

        assertThat(commands.isSetupCommand("/pairing list")).isTrue();
        assertThat(list)
                .contains("Pairing 管理")
                .contains("feishu,dingtalk,wecom,weixin,qqbot,yuanbao")
                .contains("/pairing claim-admin")
                .contains("/pairing pending <platform>")
                .doesNotContain("未知 setup/config 命令");
        assertThat(approve)
                .contains("Pairing 管理")
                .contains("请在对应平台管理员私聊中执行")
                .contains("/pairing approve feishu 123456")
                .doesNotContain("已批准");
        assertThat(Files.exists(runtimeHome.resolve("config.yml"))).isFalse();
    }

    @Test
    void shouldManageApiKeyAuthStateLocally() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-auth-local");
        AppConfig config = config(runtimeHome);
        config.getProviders().get("default").setApiKey("");
        TerminalSetupCommands commands = commands(config);

        String empty = commands.render("/auth list");
        String add =
                commands.render(
                        "/auth add local-openai --api-key Sk-Test-AuthSecret123 "
                                + "--base-url https://api.example.com/v1 --model auth-model "
                                + "--dialect openai --label Local");
        String status = commands.render("/auth status local-openai");
        String logout = commands.render("/auth logout local-openai");
        String file = Files.readString(runtimeHome.resolve("config.yml"));

        assertThat(empty)
                .contains("认证状态")
                .contains("default api_key=missing")
                .doesNotContain("未知 setup/config 命令");
        assertThat(add)
                .contains("认证凭据已写入")
                .contains("provider=local-openai")
                .contains("apiKey=***")
                .doesNotContain("Sk-Test-AuthSecret123")
                .doesNotContain("OAuth")
                .doesNotContain("未知 setup/config 命令");
        assertThat(status)
                .contains("认证状态")
                .contains("provider=local-openai")
                .contains("api_key=configured")
                .doesNotContain("Sk-Test-AuthSecret123");
        assertThat(logout)
                .contains("认证凭据已清理")
                .contains("provider=local-openai")
                .doesNotContain("Sk-Test-AuthSecret123");
        assertThat(file)
                .contains("local-openai:")
                .contains("baseUrl: https://api.example.com/v1")
                .contains("defaultModel: auth-model")
                .contains("dialect: openai")
                .doesNotContain("Sk-Test-AuthSecret123");
    }

    @Test
    void shouldRouteLoginWithApiKeyToLocalAuthAdd() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-login-api-key");
        TerminalSetupCommands commands = commands(config(runtimeHome));

        String output =
                commands.render(
                        "/login --provider default --api-key Sk-Test-LoginSecret123 "
                                + "--base-url https://api.example.com/v1 --model login-model "
                                + "--dialect openai");

        assertThat(output)
                .contains("认证凭据已写入")
                .contains("provider=default")
                .contains("apiKey=***")
                .doesNotContain("Sk-Test-LoginSecret123")
                .doesNotContain("OAuth")
                .doesNotContain("未知 setup/config 命令");
        assertThat(Files.readString(runtimeHome.resolve("config.yml")))
                .contains("apiKey: Sk-Test-LoginSecret123")
                .contains("defaultModel: login-model");
    }

    @Test
    void shouldUseProviderTemplateDefaultsWhenAddingApiKeyAuth() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-auth-template");
        TerminalSetupCommands commands = commands(config(runtimeHome));

        String output =
                commands.render("/auth add gemini --api-key Sk-Test-GeminiAuthSecret123 --activate");

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(output)
                .contains("认证凭据已写入")
                .contains("provider=gemini")
                .contains("model=gemini-2.5-pro")
                .contains("baseUrl=https://generativelanguage.googleapis.com")
                .contains("dialect=gemini")
                .contains("apiKey=***")
                .doesNotContain("Sk-Test-GeminiAuthSecret123");
        assertThat(file)
                .contains("providerKey: gemini")
                .contains("default: gemini-2.5-pro")
                .contains("gemini:")
                .contains("name: Gemini")
                .contains("baseUrl: https://generativelanguage.googleapis.com")
                .contains("apiKey: Sk-Test-GeminiAuthSecret123")
                .contains("defaultModel: gemini-2.5-pro")
                .contains("dialect: gemini");
    }

    @Test
    void shouldManageFallbackProviderChainLocally() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-fallback-chain");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);
        commands.render(
                "/auth add backup --api-key Sk-Test-FallbackSecret123 "
                        + "--base-url https://backup.example.com/v1 --model backup-model "
                        + "--dialect openai");

        String empty = commands.render("/fallback list");
        String add = commands.render("/fallback add --provider backup --model backup-model");
        String addedFile = Files.readString(runtimeHome.resolve("config.yml"));
        String duplicate = commands.render("/fallback add --provider backup --model backup-model");
        String list = commands.render("/fallback list");
        String remove = commands.render("/fallback remove 1");
        String addAgain = commands.render("/fallback add --provider backup --model backup-model");
        String clear = commands.render("/fallback clear");
        String finalList = commands.render("/fallback list");
        String file = Files.readString(runtimeHome.resolve("config.yml"));

        assertThat(empty)
                .contains("Fallback Providers")
                .contains("No fallback providers configured")
                .contains("solonclaw fallback add")
                .doesNotContain("solonclaw config set")
                .doesNotContain("未知 setup/config 命令");
        assertThat(add)
                .contains("Added fallback")
                .contains("provider=backup")
                .contains("model=backup-model")
                .doesNotContain("Sk-Test-FallbackSecret123");
        assertThat(addedFile)
                .contains("fallbackProviders:")
                .contains("provider: backup")
                .contains("model: backup-model")
                .doesNotContain("0:");
        assertThat(duplicate)
                .contains("already in the fallback chain")
                .doesNotContain("Sk-Test-FallbackSecret123");
        assertThat(list)
                .contains("Fallback Providers")
                .contains("Primary: gpt-main (via default)")
                .contains("1. backup / backup-model")
                .doesNotContain("Sk-Test-FallbackSecret123");
        assertThat(remove)
                .contains("Removed fallback")
                .contains("backup / backup-model");
        assertThat(addAgain).contains("Added fallback");
        assertThat(clear).contains("Fallback providers cleared");
        assertThat(finalList).contains("No fallback providers configured");
        assertThat(file)
                .contains("fallbackProviders:")
                .doesNotContain("0:");
    }

    @Test
    void shouldRenderGatewayOperationsForSingleProcessRuntime() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-gateway-ops");
        AppConfig config = config(runtimeHome);
        config.getChannels().getFeishu().setEnabled(true);
        config.getChannels().getFeishu().setAppId("gateway_app");
        config.getChannels().getFeishu().setAppSecret("Sk-Test-GatewaySecret123");
        TerminalSetupCommands commands = commands(config);

        String status = commands.render("/gateway status --deep");
        String list = commands.render("/gateway list");
        String run = commands.render("/gateway run");
        String start = commands.render("/gateway start");
        String stop = commands.render("/gateway stop");
        String restart = commands.render("/gateway restart");
        String install = commands.render("/gateway install --force");
        String uninstall = commands.render("/gateway uninstall");
        String migrate = commands.render("/gateway migrate-legacy");

        assertThat(commands.isSetupCommand("/gateway status")).isTrue();
        assertThat(status)
                .contains("Gateway Status")
                .contains("runtime=single-process")
                .contains("feishu: enabled=true, status=configured")
                .contains("config.path=" + runtimeHome.resolve("config.yml"))
                .doesNotContain("Sk-Test-GatewaySecret123")
                .doesNotContain("未知 setup/config 命令");
        assertThat(list)
                .contains("Gateway Channels")
                .contains("feishu configured")
                .doesNotContain("sms")
                .doesNotContain("webhook");
        assertThat(run)
                .contains("Gateway Run")
                .contains("java -jar")
                .contains("前台启动当前单实例服务");
        assertThat(start)
                .contains("Gateway Start")
                .contains("当前版本不安装独立后台服务")
                .contains("java -jar");
        assertThat(stop)
                .contains("Gateway Stop")
                .contains("/stop")
                .contains("停止当前会话运行中的任务");
        assertThat(restart)
                .contains("Gateway Restart")
                .contains("/restart")
                .contains("请求当前运行实例重载网关");
        assertThat(install)
                .contains("Gateway Install")
                .contains("当前版本不安装独立后台服务")
                .contains("Docker");
        assertThat(uninstall)
                .contains("Gateway Uninstall")
                .contains("没有独立后台服务单元需要卸载");
        assertThat(migrate)
                .contains("Gateway Migrate Legacy")
                .contains("无需迁移旧式服务单元")
                .doesNotContain("未知 setup/config 命令");
    }

    @Test
    void shouldPreserveConfigSetKeyAndValueCase() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-config-set");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output =
                commands.render(
                        "/config set providers.default.apiKey Sk-Test-Secret-WithCase123");

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(output)
                .contains("providers.default.apiKey=***")
                .doesNotContain("providers.default.apikey")
                .doesNotContain("Sk-Test-Secret-WithCase123");
        assertThat(file)
                .contains("apiKey: Sk-Test-Secret-WithCase123")
                .doesNotContain("apikey:");
    }

    @Test
    void shouldNotRedactNonSecretProviderKey() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-provider-key");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output = commands.render("/config set model.providerKey default");

        assertThat(output).contains("model.providerKey=default");
        assertThat(output).doesNotContain("model.providerKey=***");
    }

    @Test
    void shouldRenderUnsupportedConfigSetKeyWithoutThrowingStackTrace() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-config-set-unsupported");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output = commands.render("/config set fallbackProviders.0.provider backup");

        assertThat(output)
                .contains("不支持的配置键")
                .contains("fallbackProviders.0.provider")
                .contains("solonclaw fallback add")
                .doesNotContain("java.lang")
                .doesNotContain("RuntimeConfigResolver");
        assertThat(Files.exists(runtimeHome.resolve("config.yml"))).isFalse();
    }

    @Test
    void shouldConfigureModelProviderThroughSemanticModelSetCommand() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-model-set");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output =
                commands.render(
                        "/model set --provider default --base-url https://api.example.com/v1 "
                                + "--api-key Sk-Test-Secret-WithCase123 --model mimo-v2.5-pro "
                                + "--dialect openai");

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(output)
                .contains("模型配置已写入")
                .contains("provider=default")
                .contains("model=mimo-v2.5-pro")
                .contains("baseUrl=https://api.example.com/v1")
                .contains("apiKey=***")
                .doesNotContain("Sk-Test-Secret-WithCase123");
        assertThat(file)
                .contains("providerKey: default")
                .contains("default: mimo-v2.5-pro")
                .contains("baseUrl: https://api.example.com/v1")
                .contains("apiKey: Sk-Test-Secret-WithCase123")
                .contains("defaultModel: mimo-v2.5-pro")
                .contains("dialect: openai");
    }

    @Test
    void shouldConfigureModelProviderThroughSetupModelAlias() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-setup-model-set");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output =
                commands.render(
                        "/setup model --provider default --base-url https://api.example.com/v1 "
                                + "--api-key Sk-Test-SetupModel123 --model kimi-test "
                                + "--dialect openai");

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(output)
                .contains("模型配置已写入")
                .contains("model=kimi-test")
                .contains("apiKey=***")
                .doesNotContain("Sk-Test-SetupModel123");
        assertThat(file)
                .contains("apiKey: Sk-Test-SetupModel123")
                .contains("defaultModel: kimi-test");
    }

    @Test
    void shouldUseProviderTemplateDefaultsWhenSettingModelWithApiKeyOnly() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-model-template-set");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output =
                commands.render("/model set --provider gemini --api-key Sk-Test-GeminiModelSecret123");

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(output)
                .contains("模型配置已写入")
                .contains("provider=gemini")
                .contains("model=gemini-2.5-pro")
                .contains("baseUrl=https://generativelanguage.googleapis.com")
                .contains("apiKey=***")
                .contains("dialect=gemini")
                .doesNotContain("Sk-Test-GeminiModelSecret123");
        assertThat(file)
                .contains("providerKey: gemini")
                .contains("default: gemini-2.5-pro")
                .contains("baseUrl: https://generativelanguage.googleapis.com")
                .contains("apiKey: Sk-Test-GeminiModelSecret123")
                .contains("defaultModel: gemini-2.5-pro")
                .contains("dialect: gemini");
    }

    @Test
    void shouldRejectModelSetWithoutModelName() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-model-set-missing");
        AppConfig config = config(runtimeHome);
        TerminalSetupCommands commands = commands(config);

        String output =
                commands.render(
                        "/model set --provider default --base-url https://api.example.com/v1 "
                                + "--api-key Sk-Test-Secret-WithCase123 --dialect openai");

        assertThat(output)
                .contains("用法：solonclaw model set")
                .contains("--model <model>");
        assertThat(Files.exists(runtimeHome.resolve("config.yml"))).isFalse();
    }

    private TerminalSetupCommands commands(AppConfig config) {
        TerminalModelPicker picker =
                new TerminalModelPicker(config, new LlmProviderService(config));
        return new TerminalSetupCommands(config, picker);
    }

    private AppConfig config(Path runtimeHome) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.toString());
        config.getRuntime().setConfigFile(runtimeHome.resolve("config.yml").toString());
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default Provider");
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setDefaultModel("gpt-main");
        provider.setDialect("openai");
        provider.setApiKey("");
        config.getProviders().put("default", provider);
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("gpt-main");
        config.getLlm().setProvider("default");
        config.getLlm().setApiUrl("https://api.openai.com/v1");
        config.getLlm().setModel("gpt-main");
        return config;
    }
}
