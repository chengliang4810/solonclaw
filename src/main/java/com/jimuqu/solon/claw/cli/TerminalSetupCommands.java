package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.support.RuntimeProviderSetupSpec;
import com.jimuqu.solon.claw.support.RuntimeSetupService;
import com.jimuqu.solon.claw.support.RuntimeSetupSpec;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 终端 setup/config 命令渲染服务，保证 CLI 与 TUI 复用同一套配置展示逻辑。 */
public class TerminalSetupCommands {
    /** 国内渠道顺序，和本项目已确认保留的渠道范围保持一致。 */
    private static final List<String> DOMESTIC_CHANNELS =
            RuntimeSetupSpec.domesticChannels();

    /** 只提供本地配置说明、不启动服务或外部向导的当前管理命令。 */
    private static final List<String> LOCAL_GUIDANCE_COMMANDS =
            Arrays.asList(
                    "postinstall",
                    "login",
                    "auth",
                    "fallback",
                    "secrets",
                    "proxy",
                    "mcp",
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

    /** 保存应用配置依赖，用于读取当前生效模型、渠道与运行时路径。 */
    private final AppConfig appConfig;

    /** 保存模型选择器依赖，用于复用终端模型列表渲染。 */
    private final TerminalModelPicker modelPicker;

    /**
     * 创建终端 setup/config 命令服务。
     *
     * @param appConfig 应用运行配置。
     * @param modelPicker 终端模型选择器。
     */
    public TerminalSetupCommands(AppConfig appConfig, TerminalModelPicker modelPicker) {
        this.appConfig = appConfig;
        this.modelPicker = modelPicker;
    }

    /**
     * 判断输入是否应由本地 setup/config 服务处理。
     *
     * @param input 终端输入文本。
     * @return setup/config 命令返回 true，否则返回 false。
     */
    public boolean isSetupCommand(String input) {
        String value = commandKey(input);
        return "setup".equals(value)
                || value.startsWith("setup ")
                || "gateway setup".equals(value)
                || value.startsWith("gateway setup ")
                || "doctor".equals(value)
                || value.startsWith("doctor ")
                || "status".equals(value)
                || value.startsWith("status ")
                || "version".equals(value)
                || value.startsWith("version ")
                || "logout".equals(value)
                || value.startsWith("logout ")
                || "pairing".equals(value)
                || value.startsWith("pairing ")
                || "gateway".equals(value)
                || value.startsWith("gateway ")
                || "config".equals(value)
                || value.startsWith("config ")
                || "model".equals(value)
                || "model set".equals(value)
                || value.startsWith("model set ")
                || "model configure".equals(value)
                || value.startsWith("model configure ")
                || isLocalGuidanceCommand(value);
    }

    /**
     * 渲染 setup/config 命令输出。
     *
     * @param input 终端输入文本。
     * @return 面向用户的终端输出。
     */
    public String render(String input) {
        String rawValue = commandText(input);
        String value = rawValue.toLowerCase(java.util.Locale.ROOT);
        if ("setup".equals(value)) {
            return renderSetupOverview();
        }
        if (value.startsWith("setup --")) {
            return renderSetupOptions(rawValue);
        }
        if ("setup terminal".equals(value)) {
            return renderSetupTerminal();
        }
        if ("setup tools".equals(value)) {
            return renderSetupTools();
        }
        if ("setup agent".equals(value)) {
            return renderSetupAgent();
        }
        if ("setup tts".equals(value)) {
            return renderSetupTts();
        }
        if ("setup model".equals(value) || "model".equals(value)) {
            return renderModelSetup();
        }
        if (value.startsWith("setup model ")
                || value.startsWith("model set")
                || value.startsWith("model configure")) {
            return renderModelSet(rawValue);
        }
        if ("setup gateway".equals(value) || "gateway setup".equals(value)) {
            return renderGatewaySetup();
        }
        if (value.startsWith("setup gateway ") || value.startsWith("gateway setup ")) {
            return renderGatewaySet(rawValue);
        }
        if ("doctor".equals(value)) {
            return renderDoctor();
        }
        if (value.startsWith("doctor ")) {
            return renderDoctorOptions(rawValue);
        }
        if ("status".equals(value) || value.startsWith("status ")) {
            return renderStatus();
        }
        if ("version".equals(value) || value.startsWith("version ")) {
            return renderVersion(rawValue);
        }
        if ("logout".equals(value) || value.startsWith("logout ")) {
            return renderLogout();
        }
        if ("pairing".equals(value) || value.startsWith("pairing ")) {
            return renderPairingGuidance(rawValue);
        }
        if ("gateway".equals(value) || "gateway status".equals(value) || value.startsWith("gateway status ")) {
            return renderGatewayStatus(rawValue);
        }
        if ("gateway list".equals(value)) {
            return renderGatewayList();
        }
        if ("gateway run".equals(value) || value.startsWith("gateway run ")) {
            return renderGatewayRun();
        }
        if ("gateway start".equals(value) || value.startsWith("gateway start ")) {
            return renderGatewayStart();
        }
        if ("gateway stop".equals(value) || value.startsWith("gateway stop ")) {
            return renderGatewayStop();
        }
        if ("gateway restart".equals(value) || value.startsWith("gateway restart ")) {
            return renderGatewayRestart();
        }
        if ("gateway install".equals(value) || value.startsWith("gateway install ")) {
            return renderGatewayInstall();
        }
        if ("gateway uninstall".equals(value) || value.startsWith("gateway uninstall ")) {
            return renderGatewayUninstall();
        }
        if ("config".equals(value) || "config show".equals(value)) {
            return renderConfigShow();
        }
        if ("config path".equals(value)) {
            return renderConfigPath();
        }
        if ("config edit".equals(value)) {
            return renderConfigEdit();
        }
        if ("config check".equals(value)) {
            return renderConfigCheck();
        }
        if (value.startsWith("config set ")) {
            return renderConfigSet(rawValue.substring("config set ".length()).trim());
        }
        if ("postinstall".equals(value) || value.startsWith("postinstall ")) {
            return renderPostinstallGuidance();
        }
        if ("login".equals(value) || value.startsWith("login ")) {
            return renderLoginGuidance(rawValue);
        }
        if ("auth".equals(value) || value.startsWith("auth ")) {
            return renderAuthGuidance(rawValue);
        }
        if ("fallback".equals(value) || value.startsWith("fallback ")) {
            return renderFallbackCommand(rawValue);
        }
        if ("secrets".equals(value) || value.startsWith("secrets ")) {
            return renderSecretsGuidance();
        }
        if ("proxy".equals(value) || value.startsWith("proxy ")) {
            return renderProxyGuidance();
        }
        if ("mcp".equals(value) || value.startsWith("mcp ")) {
            return renderMcpGuidance(rawValue);
        }
        if ("send".equals(value) || value.startsWith("send ")) {
            return renderSendGuidance();
        }
        if ("hooks".equals(value) || value.startsWith("hooks ")) {
            return renderHooksGuidance(rawValue);
        }
        if ("dump".equals(value) || value.startsWith("dump ")) {
            return renderDumpGuidance(rawValue);
        }
        if ("backup".equals(value) || value.startsWith("backup ")) {
            return renderBackupGuidance(rawValue);
        }
        if ("checkpoints".equals(value) || value.startsWith("checkpoints ")) {
            return renderCheckpointsGuidance(rawValue);
        }
        if ("import".equals(value) || value.startsWith("import ")) {
            return renderImportGuidance(rawValue);
        }
        if ("bundles".equals(value) || value.startsWith("bundles ")) {
            return renderBundlesGuidance(rawValue);
        }
        if ("memory".equals(value) || value.startsWith("memory ")) {
            return renderMemoryGuidance(rawValue);
        }
        if ("dashboard".equals(value) || value.startsWith("dashboard ")) {
            return renderDashboardGuidance(rawValue);
        }
        if ("logs".equals(value) || value.startsWith("logs ")) {
            return renderLogsGuidance(rawValue);
        }
        if ("prompt-size".equals(value) || value.startsWith("prompt-size ")) {
            return renderPromptSizeGuidance(rawValue);
        }
        return "未知 setup/config 命令。\n"
                + "可用命令：solonclaw setup、solonclaw setup model、solonclaw setup gateway、"
                + "solonclaw model set、solonclaw config path、solonclaw config show、"
                + "solonclaw config check、solonclaw doctor、solonclaw gateway status。";
    }

    /**
     * 判断是否为只在本地终端输出说明的当前管理命令。
     *
     * @param value 去掉斜杠后的规范化命令文本。
     * @return 属于本地说明命令返回 true。
     */
    private boolean isLocalGuidanceCommand(String value) {
        for (String command : LOCAL_GUIDANCE_COMMANDS) {
            if (command.equals(value) || value.startsWith(command + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 渲染 MCP 顶层命令说明；当前本地终端只给出配置路径和刷新入口，不伪造连接测试结果。
     *
     * @param rawValue 用户输入的 MCP 命令。
     * @return MCP 配置引导文本。
     */
    private String renderMcpGuidance(String rawValue) {
        String rest = StrUtil.nullToEmpty(rawValue).trim();
        if (rest.toLowerCase(java.util.Locale.ROOT).startsWith("mcp")) {
            rest = rest.length() <= "mcp".length() ? "" : rest.substring("mcp".length()).trim();
        }
        String action = StrUtil.isBlank(rest) ? "list" : shellTokens(rest).get(0);
        return "MCP 配置\n"
                + "action="
                + action
                + "\n当前本地终端不直接启动或探测 MCP server；请通过 Dashboard 的 MCP 页面或 "
                + "runtime/config.yml 写入 mcp.servers 配置。\n"
                + "config="
                + configResolver().configFile().getPath()
                + "\n可用入口：\n"
                + "1. solonclaw config set mcp.servers.<name>.command <command>\n"
                + "2. solonclaw config set mcp.servers.<name>.url <url>\n"
                + "3. /reload-mcp now - 配置后刷新 MCP 工具 schema\n"
                + "4. /security mcp、/security mcp-oauth、/security mcp-package - 查看 MCP 安全策略。";
    }

    /** 渲染 setup 总览。 */
    private String renderSetupOverview() {
        return "solonclaw setup\n"
                + "1. solonclaw setup model - 配置模型提供方、API 地址、API Key 与默认模型\n"
                + "2. solonclaw setup gateway - 查看并配置国内消息渠道\n"
                + "3. solonclaw config path - 查看 runtime/config.yml 路径\n"
                + "4. solonclaw config check - 检查 runtime/config.yml 与当前生效配置差异\n"
                + "5. solonclaw doctor - 汇总模型、配置与国内渠道自检\n"
                + "终端内也可使用：/setup model、/setup gateway、/config path、/doctor。";
    }

    /**
     * 渲染 setup 顶层选项，覆盖非交互、快速初始化和重置配置这三类用户入口。
     *
     * @param rawValue 用户输入的 setup 命令。
     * @return setup 选项输出。
     */
    private String renderSetupOptions(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.contains("--reset")) {
            return renderSetupReset();
        }
        if (tokens.contains("--quick")) {
            return renderSetupQuick();
        }
        if (tokens.contains("--non-interactive")) {
            return renderSetupNonInteractive();
        }
        return renderSetupOverview();
    }

    /** 渲染非交互初始化引导，给无 TTY 或脚本环境直接可执行的配置命令。 */
    private String renderSetupNonInteractive() {
        String providerKey = StrUtil.blankToDefault(activeProviderKey(), "default");
        String model = StrUtil.blankToDefault(activeModel(activeProvider()), "your-model");
        return "非交互初始化\n"
                + "runtime.config="
                + configResolver().configFile().getPath()
                + "\n"
                + "1. solonclaw model set --provider "
                + providerKey
                + " --base-url <https://host/v1> --api-key <api-key> --model "
                + model
                + " --dialect <openai|openai-responses|ollama|gemini|anthropic>\n"
                + "2. solonclaw setup gateway <feishu|dingtalk|wecom|weixin|qqbot|yuanbao> "
                + "--enabled true ...\n"
                + "3. solonclaw doctor";
    }

    /** 渲染快速初始化摘要，只提示当前缺失的关键模型和渠道配置。 */
    private String renderSetupQuick() {
        AppConfig.ProviderConfig provider = activeProvider();
        boolean apiKeyConfigured =
                provider != null && SecretValueGuard.hasUsableSecret(provider.getApiKey());
        String modelState;
        if (StrUtil.isBlank(activeProviderKey()) || StrUtil.isBlank(activeModel(provider))) {
            modelState = "missing";
        } else if (!apiKeyConfigured) {
            modelState = "missing_api_key";
        } else {
            modelState = "configured";
        }
        String missingChannels = missingEnabledChannels();
        String next;
        if (!"configured".equals(modelState)) {
            next = "solonclaw setup model";
        } else if (StrUtil.isNotBlank(missingChannels) && !"(none)".equals(missingChannels)) {
            next = "solonclaw setup gateway " + missingChannels.split(",")[0];
        } else {
            next = "solonclaw doctor";
        }
        return "快速初始化\n"
                + "model="
                + modelState
                + "\nchannels.missing="
                + missingChannels
                + "\nrun="
                + next;
    }

    /** 重置 runtime/config.yml 覆盖配置，让下一次启动回到应用默认配置。 */
    private String renderSetupReset() {
        RuntimeConfigResolver resolver = configResolver();
        File file = resolver.configFile();
        boolean deleted = !file.exists() || file.delete();
        resolver.reload();
        if (!deleted) {
            return "runtime/config.yml 重置失败："
                    + file.getPath()
                    + "\n请检查文件权限后重试。";
        }
        return "runtime/config.yml 已重置\n"
                + "path="
                + file.getPath()
                + "\nnext=solonclaw setup model";
    }

    /** 渲染终端初始化分节，说明当前 Java CLI/TUI 的可用入口。 */
    private String renderSetupTerminal() {
        return "终端初始化\n"
                + "1. solonclaw - 启动本地 TUI/CLI 入口，按当前启动模式进入交互界面\n"
                + "2. solonclaw --cli -p /help - 查看 CLI 本地命令\n"
                + "3. solonclaw --tui -p /help - 查看 TUI 本地命令\n"
                + "4. solonclaw completion <bash|zsh|fish> - 输出补全脚本\n"
                + "可在终端内继续使用 /help、/tips、/skin、/sessions、/history。";
    }

    /** 渲染工具初始化分节，聚焦安全策略、MCP 与内置工具可见性。 */
    private String renderSetupTools() {
        return "工具初始化\n"
                + "1. /security policy - 查看工具审批、路径、URL 与终端执行策略\n"
                + "2. /reload-mcp now - 立即重载 MCP 工具 schema\n"
                + "3. /tools、/toolsets、/browser、/plugins - 查看工具、浏览器自动化与插件状态\n"
                + "4. solonclaw config set <key> <value> - 写入工具相关 runtime/config.yml 覆盖项";
    }

    /** 渲染 Agent 初始化分节，说明会话、目标和 Agent 切换入口。 */
    private String renderSetupAgent() {
        return "Agent 初始化\n"
                + "1. /agent - 查看或切换当前会话 Agent\n"
                + "2. /goal [status|pause|resume|clear|目标 --max N] - 管理跨轮长目标\n"
                + "3. /new、/resume、/branch、/rollback - 管理会话生命周期\n"
                + "4. solonclaw config set solonclaw.agent.defaultProfile <profile> - 写入默认 Agent 配置";
    }

    /** 渲染语音初始化分节；当前保留 TTS/STT 服务配置但不提供语音模式入口。 */
    private String renderSetupTts() {
        return "语音初始化\n"
                + "当前版本不提供语音模式入口；仅保留 TTS / 独立语音转写服务配置能力。\n"
                + "可使用 solonclaw config set <key> <value> 写入语音服务地址、密钥或默认 voice。\n"
                + "配置后通过媒体/工具能力调用，终端不会启动独立语音会话。";
    }

    /** 渲染模型配置状态与可执行配置示例。 */
    private String renderModelSetup() {
        AppConfig.ProviderConfig provider = activeProvider();
        String providerKey = activeProviderKey();
        String activeModel = activeModel(provider);
        String dialect = provider == null ? "" : StrUtil.nullToEmpty(provider.getDialect()).trim();
        String baseUrl = provider == null ? "" : StrUtil.nullToEmpty(provider.getBaseUrl()).trim();
        String apiUrl = LlmProviderSupport.buildApiUrl(baseUrl, dialect);
        String apiKeyState =
                provider != null && SecretValueGuard.hasUsableSecret(provider.getApiKey())
                        ? "configured"
                        : "missing";
        StringBuilder buffer = new StringBuilder();
        buffer.append("模型配置\n")
                .append("active.provider=")
                .append(StrUtil.blankToDefault(providerKey, "(not set)"))
                .append('\n')
                .append("active.model=")
                .append(StrUtil.blankToDefault(activeModel, "(not set)"))
                .append('\n')
                .append("dialect=")
                .append(StrUtil.blankToDefault(dialect, "(not set)"))
                .append('\n')
                .append("api_url=")
                .append(StrUtil.blankToDefault(SecretRedactor.maskUrl(apiUrl), "(not set)"))
                .append('\n')
                .append("api_key=")
                .append(apiKeyState)
                .append('\n')
                .append('\n')
                .append("常用配置命令：\n")
                .append("solonclaw model set --provider ")
                .append(StrUtil.blankToDefault(providerKey, "default"))
                .append(" --base-url <https://host/v1> --api-key <api-key> --model ")
                .append(StrUtil.blankToDefault(activeModel, "your-model"))
                .append(" --dialect ")
                .append(StrUtil.blankToDefault(dialect, "openai"))
                .append('\n')
                .append("solonclaw config set model.providerKey ")
                .append(StrUtil.blankToDefault(providerKey, "default"))
                .append('\n')
                .append("solonclaw config set model.default ")
                .append(StrUtil.blankToDefault(activeModel, "your-model"))
                .append('\n')
                .append("solonclaw config set providers.")
                .append(StrUtil.blankToDefault(providerKey, "default"))
                .append(".apiKey <api-key>\n")
                .append("solonclaw config set providers.")
                .append(StrUtil.blankToDefault(providerKey, "default"))
                .append(".baseUrl <https://host/v1>\n")
                .append('\n')
                .append(renderProviderTemplates())
                .append('\n');
        if (modelPicker != null) {
            buffer.append(modelPicker.render()).append('\n');
        }
        return buffer.toString().trim();
    }

    /** 渲染受支持 provider 模板，帮助用户从未配置状态直接复制可执行命令。 */
    private String renderProviderTemplates() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("available.providers=");
        for (int i = 0; i < RuntimeProviderSetupSpec.providers().size(); i++) {
            if (i > 0) {
                buffer.append(',');
            }
            buffer.append(RuntimeProviderSetupSpec.providers().get(i).getSlug());
        }
        buffer.append('\n').append("provider.templates:\n");
        for (RuntimeProviderSetupSpec.ProviderTemplate template : RuntimeProviderSetupSpec.providers()) {
            buffer.append("- ")
                    .append(template.getSlug())
                    .append(": baseUrl=")
                    .append(SecretRedactor.maskUrl(template.getBaseUrl()))
                    .append(", model=")
                    .append(template.getDefaultModel())
                    .append(", dialect=")
                    .append(template.getDialect())
                    .append(", keyEnv=")
                    .append(template.getKeyEnv())
                    .append('\n');
        }
        buffer.append("示例：solonclaw model set --provider gemini --api-key <api-key>");
        return buffer.toString().trim();
    }

    /**
     * 写入模型提供方配置，复用原始 runtime/config.yml 配置入口。
     *
     * @param rawValue 用户输入的完整 model set/configure 命令。
     * @return 配置写入结果说明。
     */
    private String renderModelSet(String rawValue) {
        ModelSetRequest request = parseModelSet(rawValue);
        if (request == null
                || StrUtil.isBlank(request.providerKey)
                || StrUtil.isBlank(request.baseUrl)
                || StrUtil.isBlank(request.apiKey)
                || !request.modelProvided
                || StrUtil.isBlank(request.model)
                || StrUtil.isBlank(request.dialect)) {
            return modelSetUsage();
        }
        try {
            LlmProviderSupport.validateBaseUrl(request.baseUrl);
        } catch (IllegalArgumentException e) {
            return "provider.baseUrl 配置无效：" + e.getMessage();
        }
        String dialect = LlmProviderSupport.normalizeDialect(request.dialect);
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return "不支持的 provider dialect：" + request.dialect;
        }
        RuntimeSetupService.ModelSetupRequest setupRequest =
                new RuntimeSetupService.ModelSetupRequest();
        setupRequest.setProviderKey(request.providerKey);
        setupRequest.setProviderName(request.providerName);
        setupRequest.setBaseUrl(request.baseUrl);
        setupRequest.setApiKey(request.apiKey);
        setupRequest.setModel(request.model);
        setupRequest.setDialect(dialect);
        RuntimeSetupService.SetupResult result = runtimeSetupService().configureModel(setupRequest);
        if (!result.isSuccess()) {
            return "模型配置写入失败：" + result.getMessage();
        }
        return "模型配置已写入 runtime/config.yml：\n"
                + "provider="
                + request.providerKey
                + "\nmodel="
                + request.model
                + "\nbaseUrl="
                + SecretRedactor.maskUrl(request.baseUrl)
                + "\napiKey=***\ndialect="
                + dialect;
    }

    /** 渲染国内渠道配置状态。 */
    private String renderGatewaySetup() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("消息渠道配置\n");
        for (String channel : DOMESTIC_CHANNELS) {
            AppConfig.ChannelConfig config = channelConfig(channel);
            List<String> requiredKeys = RuntimeSetupSpec.requiredChannelKeys(channel);
            buffer.append("- ")
                    .append(channel)
                    .append(": enabled=")
                    .append(config != null && config.isEnabled())
                    .append(", status=")
                    .append(channelStatus(config, requiredKeys))
                    .append(", keys=")
                    .append(String.join(",", requiredKeys))
                    .append('\n');
        }
        buffer.append("使用：solonclaw setup gateway <channel> --enabled true ...\n")
                .append("示例：solonclaw setup gateway feishu --app-id <app-id> --app-secret <secret>");
        return buffer.toString();
    }

    /**
     * 写入国内消息渠道配置。
     *
     * @param rawValue 用户输入的完整 setup gateway 命令。
     * @return 配置写入结果说明。
     */
    private String renderGatewaySet(String rawValue) {
        GatewaySetRequest request = parseGatewaySet(rawValue);
        if (request == null || StrUtil.isBlank(request.channel)) {
            return gatewaySetUsage();
        }
        if (StrUtil.isNotBlank(request.unsupportedField)) {
            String availableKeys = "";
            List<String> allowedKeys = RuntimeSetupSpec.allowedChannelKeys(request.channel);
            if (allowedKeys != null) {
                availableKeys = String.join(",", allowedKeys);
            }
            return "渠道 "
                    + request.channel
                    + " 不支持配置项："
                    + request.unsupportedField
                    + "\n可用配置项："
                    + availableKeys;
        }
        if (!DOMESTIC_CHANNELS.contains(request.channel)) {
            return "不支持的渠道："
                    + request.channel
                    + "\n可用渠道："
                    + String.join(",", DOMESTIC_CHANNELS);
        }
        if (request.values.isEmpty()) {
            return gatewaySetUsage();
        }
        List<String> allowedKeys = RuntimeSetupSpec.allowedChannelKeys(request.channel);
        for (String key : request.values.keySet()) {
            if (allowedKeys == null || !allowedKeys.contains(key)) {
                String availableKeys =
                        allowedKeys == null ? "" : String.join(",", allowedKeys);
                return "渠道 "
                        + request.channel
                        + " 不支持配置项："
                        + key
                        + "\n可用配置项："
                        + availableKeys;
            }
        }
        RuntimeSetupService.SetupResult result =
                runtimeSetupService().configureGatewayChannel(request.channel, request.values);
        if (!result.isSuccess()) {
            return "渠道配置写入失败：" + result.getMessage();
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("渠道配置已写入 runtime/config.yml：\n")
                .append("channel=")
                .append(request.channel);
        for (Map.Entry<String, String> entry : request.values.entrySet()) {
            buffer.append('\n')
                    .append(entry.getKey())
                    .append('=')
                    .append(safeConfigValue(entry.getKey(), entry.getValue()));
        }
        return buffer.toString();
    }

    /** 渲染 runtime/config.yml 文件路径。 */
    private String renderConfigPath() {
        RuntimeConfigResolver resolver = configResolver();
        return resolver.configFile().getPath();
    }

    /** 渲染配置编辑引导，不在 CLI/TUI 自动拉起外部编辑器，避免阻塞本地终端流程。 */
    private String renderConfigEdit() {
        RuntimeConfigResolver resolver = configResolver();
        return "配置文件\n"
                + "path="
                + resolver.configFile().getPath()
                + "\n可直接编辑该文件，或使用：solonclaw config set <key> <value>"
                + "\n修改后重新启动服务或通过 Dashboard 刷新运行配置。";
    }

    /** 渲染 runtime/config.yml 的轻量快照。 */
    private String renderConfigShow() {
        RuntimeConfigResolver resolver = configResolver();
        Map<String, String> snapshot = resolver.fileValues();
        StringBuilder buffer = new StringBuilder();
        buffer.append("runtime/config.yml: ").append(resolver.configFile().getPath()).append('\n');
        if (snapshot.isEmpty()) {
            buffer.append("当前 runtime/config.yml 还没有覆盖项。");
            return buffer.toString();
        }
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            buffer.append(entry.getKey())
                    .append("=")
                    .append(safeConfigValue(entry.getKey(), entry.getValue()))
                    .append('\n');
        }
        return buffer.toString();
    }

    /** 渲染 runtime/config.yml 与当前生效配置的诊断结果。 */
    private String renderConfigCheck() {
        Map<String, Object> diagnostics = configResolver().diagnostics(appConfig);
        return "配置检查\n"
                + "has_issues="
                + diagnostics.get("has_issues")
                + "\nunknown_count="
                + diagnostics.get("unknown_count")
                + "\neffective_diff_count="
                + diagnostics.get("effective_diff_count");
    }

    /** 渲染本地 doctor 自检摘要，覆盖初始化后最常见的配置问题。 */
    private String renderDoctor() {
        RuntimeConfigResolver resolver = configResolver();
        Map<String, Object> diagnostics = resolver.diagnostics(appConfig);
        AppConfig.ProviderConfig provider = activeProvider();
        String providerKey = activeProviderKey();
        String model = activeModel(provider);
        boolean apiKeyConfigured = providerApiKeyConfigured(providerKey);
        String configuredChannels = configuredChannels();
        String missingChannels = missingEnabledChannels();
        String next = doctorNextStep(diagnostics, providerKey, model, apiKeyConfigured, missingChannels);
        return "Solon Claw Doctor\n"
                + "runtime.config="
                + resolver.configFile().getPath()
                + "\nconfig.has_issues="
                + diagnostics.get("has_issues")
                + "\nconfig.unknown_count="
                + diagnostics.get("unknown_count")
                + "\nconfig.effective_diff_count="
                + diagnostics.get("effective_diff_count")
                + "\nmodel.provider="
                + StrUtil.blankToDefault(providerKey, "(not set)")
                + "\nmodel.default="
                + StrUtil.blankToDefault(model, "(not set)")
                + "\nmodel.dialect="
                + StrUtil.blankToDefault(provider == null ? "" : provider.getDialect(), "(not set)")
                + "\nmodel.api_key="
                + (apiKeyConfigured ? "configured" : "missing")
                + "\nchannels.configured="
                + configuredChannels
                + "\nchannels.missing="
                + missingChannels
                + "\nnext="
                + next;
    }

    /**
     * 渲染 doctor 参数命令，覆盖自动修复和安全公告确认这两类常见运维入口。
     *
     * @param rawValue 用户输入的 doctor 命令。
     * @return doctor 参数命令输出。
     */
    private String renderDoctorOptions(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.contains("--fix")) {
            return renderDoctorFix();
        }
        int ackIndex = tokens.indexOf("--ack");
        if (ackIndex >= 0) {
            String advisory = ackIndex + 1 < tokens.size() ? tokens.get(ackIndex + 1) : "";
            return renderDoctorAck(advisory);
        }
        return renderDoctor();
    }

    /** 渲染 doctor 自动修复摘要；当前仅执行无副作用刷新并给出可执行下一步。 */
    private String renderDoctorFix() {
        configResolver().reload();
        String doctor = renderDoctor();
        return "Doctor 自动修复\n"
                + "已刷新 runtime/config.yml 视图；涉及模型密钥和渠道凭据的项目需要用户确认后写入。\n"
                + doctor;
    }

    /**
     * 渲染安全公告确认结果；本项目当前没有独立公告确认存储，因此只给出明确状态。
     *
     * @param advisory 用户请求确认的公告 ID。
     * @return 安全公告确认状态。
     */
    private String renderDoctorAck(String advisory) {
        return "Doctor advisory ack\n"
                + "advisory="
                + StrUtil.blankToDefault(advisory, "(missing)")
                + "\n当前版本没有待确认的本地安全公告。"
                + "\n如需复查安全状态，请运行：solonclaw doctor";
    }

    /** 渲染本地运行状态，供顶层 status 与 TUI 内 /status 共用。 */
    private String renderStatus() {
        RuntimeConfigResolver resolver = configResolver();
        AppConfig.ProviderConfig provider = activeProvider();
        boolean apiKeyConfigured =
                provider != null && SecretValueGuard.hasUsableSecret(provider.getApiKey());
        return "Solon Claw Status\n"
                + "runtime=local-terminal\n"
                + "runtime.home="
                + runtimeHomeText()
                + "\nruntime.config="
                + resolver.configFile().getPath()
                + "\nmodel.provider="
                + StrUtil.blankToDefault(activeProviderKey(), "(not set)")
                + "\nmodel.default="
                + StrUtil.blankToDefault(activeModel(provider), "(not set)")
                + "\nmodel.api_key="
                + (apiKeyConfigured ? "configured" : "missing")
                + "\nchannels.configured="
                + configuredChannels()
                + "\nchannels.missing="
                + missingEnabledChannels()
                + "\nnext=solonclaw doctor";
    }

    /**
     * 渲染版本信息；本地终端只读取本地版本，不触发联网检查或升级。
     *
     * @param rawValue 用户输入的 version 命令。
     * @return 版本摘要。
     */
    private String renderVersion(String rawValue) {
        AppVersionService versionService =
                new AppVersionService(appConfig == null ? new AppConfig() : appConfig);
        List<String> tokens = shellTokens(rawValue);
        String note = "";
        if (tokens.size() > 1
                && ("check".equalsIgnoreCase(tokens.get(1))
                        || "status".equalsIgnoreCase(tokens.get(1)))) {
            note = "\ncheck=本地终端未执行联网版本检查；需要在线检查时可在运行服务后使用 Dashboard 或 /version check。";
        } else if (tokens.size() > 1
                && ("update".equalsIgnoreCase(tokens.get(1))
                        || "upgrade".equalsIgnoreCase(tokens.get(1))
                        || "run".equalsIgnoreCase(tokens.get(1)))) {
            note = "\nupdate=本地终端未直接执行自更新；请在确认运行环境后使用 /version update 或发布包替换。";
        }
        return "Solon Claw Version\n"
                + "version="
                + versionService.currentVersion()
                + "\ntag="
                + versionService.currentTag()
                + "\ndeployment="
                + versionService.deploymentMode()
                + "\nrepo="
                + versionService.releaseRepo()
                + note;
    }

    /** 渲染本地登出结果；当前无独立终端 OAuth 登录态。 */
    private String renderLogout() {
        return "本地登出\n"
                + "当前版本没有独立的终端登录态；模型 API Key 和渠道凭据保存在 runtime/config.yml。\n"
                + "如需移除凭据，请使用 solonclaw config set 或直接编辑配置文件。";
    }

    /** 渲染安装后初始化检查，替代脚本式依赖安装流程。 */
    private String renderPostinstallGuidance() {
        return "Postinstall\n"
                + "当前 Java 发行形态不执行脚本式依赖安装；运行时只需要 java -jar 或 Docker。\n"
                + "初始化顺序：\n"
                + "1. solonclaw setup\n"
                + "2. solonclaw model set --provider <key> --base-url <https://host/v1> --api-key <api-key> --model <model> --dialect <openai|openai-responses|ollama|gemini|anthropic>\n"
                + "3. solonclaw setup gateway <feishu|dingtalk|wecom|weixin|qqbot|yuanbao> --enabled true ...\n"
                + "4. java -Dsolonclaw.runtime.home=<runtime> -jar target/solon-claw-0.0.1.jar";
    }

    /** 渲染模型登录说明；本项目以 API Key provider 配置为主，不提供独立终端 OAuth。 */
    private String renderLoginGuidance(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.contains("--api-key") || tokens.contains("--apiKey")) {
            AuthSetRequest request = parseLoginAuthSet(rawValue);
            if (request == null) {
                return authAddUsage();
            }
            return writeAuthProvider(request);
        }
        return "登录与认证\n"
                + "当前终端不维护独立登录态；请通过 API Key 配置模型提供方。\n"
                + "用法：solonclaw model set --provider <key> --base-url <https://host/v1> --api-key <api-key> --model <model> --dialect <openai|openai-responses|ollama|gemini|anthropic>\n"
                + "查看状态：solonclaw status\n"
                + "清理凭据：solonclaw config set providers.<key>.apiKey <empty-or-new-key>";
    }

    /** 渲染认证配置说明，保持凭据入口集中到 runtime/config.yml。 */
    private String renderAuthGuidance(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.size() >= 2) {
            String action = tokens.get(1).toLowerCase(java.util.Locale.ROOT);
            if ("list".equals(action) || "ls".equals(action)) {
                return renderAuthList();
            }
            if ("status".equals(action)) {
                String provider = tokens.size() >= 3 ? tokens.get(2) : activeProviderKey();
                return renderAuthStatus(provider);
            }
            if ("add".equals(action)) {
                AuthSetRequest request = parseAuthAdd(rawValue);
                if (request == null) {
                    return authAddUsage();
                }
                return writeAuthProvider(request);
            }
            if ("logout".equals(action)
                    || "remove".equals(action)
                    || "rm".equals(action)
                    || "delete".equals(action)) {
                String provider = tokens.size() >= 3 ? tokens.get(2) : activeProviderKey();
                return removeAuthProvider(provider);
            }
        }
        return "认证配置\n"
                + "模型与渠道凭据统一写入 runtime/config.yml，不维护独立凭据池。\n"
                + "路径："
                + configResolver().configFile().getPath()
                + "\n模型：solonclaw model set --provider <key> --base-url <url> --api-key <key> --model <model> --dialect <dialect>"
                + "\n状态：solonclaw auth list 或 solonclaw auth status <provider>"
                + "\n写入：solonclaw auth add <provider> --api-key <api-key> --base-url <url> --model <model> --dialect <dialect>"
                + "\n清理：solonclaw auth logout <provider>"
                + "\n渠道：solonclaw setup gateway <channel> --enabled true ...";
    }

    /** 渲染 provider API Key 状态列表，合并启动配置和 runtime/config.yml 动态 provider。 */
    private String renderAuthList() {
        java.util.LinkedHashSet<String> providers = providerKeysForAuth();
        StringBuilder buffer = new StringBuilder();
        buffer.append("认证状态\n");
        if (providers.isEmpty()) {
            buffer.append("当前没有可显示的 provider。");
            return buffer.toString();
        }
        for (String provider : providers) {
            buffer.append("- ")
                    .append(provider)
                    .append(" api_key=")
                    .append(providerApiKeyConfigured(provider) ? "configured" : "missing")
                    .append(", model=")
                    .append(StrUtil.blankToDefault(providerModel(provider), "(not set)"))
                    .append(", dialect=")
                    .append(StrUtil.blankToDefault(providerDialect(provider), "(not set)"))
                    .append('\n');
        }
        buffer.append("写入：solonclaw auth add <provider> --api-key <api-key> --base-url <url> --model <model> --dialect <dialect>");
        return buffer.toString();
    }

    /**
     * 渲染单个 provider 的认证状态。
     *
     * @param provider provider 键。
     * @return provider 认证状态摘要。
     */
    private String renderAuthStatus(String provider) {
        String key = StrUtil.blankToDefault(StrUtil.nullToEmpty(provider).trim(), activeProviderKey());
        if (StrUtil.isBlank(key)) {
            return "认证状态\nprovider=(missing)\napi_key=missing";
        }
        return "认证状态\n"
                + "provider="
                + key
                + "\napi_key="
                + (providerApiKeyConfigured(key) ? "configured" : "missing")
                + "\nmodel="
                + StrUtil.blankToDefault(providerModel(key), "(not set)")
                + "\ndialect="
                + StrUtil.blankToDefault(providerDialect(key), "(not set)")
                + "\nbaseUrl="
                + StrUtil.blankToDefault(SecretRedactor.maskUrl(providerBaseUrl(key)), "(not set)");
    }

    /**
     * 写入本地 provider API Key 与模型配置。
     *
     * @param request 已解析的认证写入请求。
     * @return 写入结果。
     */
    private String writeAuthProvider(AuthSetRequest request) {
        if (request == null
                || StrUtil.isBlank(request.providerKey)
                || StrUtil.isBlank(request.apiKey)
                || StrUtil.isBlank(request.baseUrl)
                || StrUtil.isBlank(request.model)
                || StrUtil.isBlank(request.dialect)) {
            return authAddUsage();
        }
        try {
            LlmProviderSupport.validateBaseUrl(request.baseUrl);
        } catch (IllegalArgumentException e) {
            return "provider.baseUrl 配置无效：" + e.getMessage();
        }
        String dialect = LlmProviderSupport.normalizeDialect(request.dialect);
        if (!LlmProviderSupport.isSupportedDialect(dialect)) {
            return "不支持的 provider dialect：" + request.dialect;
        }
        if (SecretValueGuard.isPlaceholderSecret(request.apiKey)) {
            return "apiKey 不能使用示例或占位符密钥。";
        }
        RuntimeConfigResolver resolver = configResolver();
        String prefix = "providers." + request.providerKey + ".";
        resolver.setFileValue(prefix + "name", request.providerName);
        resolver.setFileValue(prefix + "baseUrl", request.baseUrl);
        resolver.setFileValue(prefix + "apiKey", request.apiKey);
        resolver.setFileValue(prefix + "defaultModel", request.model);
        resolver.setFileValue(prefix + "dialect", dialect);
        if (request.activate) {
            resolver.setFileValue("model.providerKey", request.providerKey);
            resolver.setFileValue("model.default", request.model);
        }
        return "认证凭据已写入 runtime/config.yml：\n"
                + "provider="
                + request.providerKey
                + "\nmodel="
                + request.model
                + "\nbaseUrl="
                + SecretRedactor.maskUrl(request.baseUrl)
                + "\napiKey=***\ndialect="
                + dialect;
    }

    /**
     * 清理本地 provider API Key。
     *
     * @param provider provider 键。
     * @return 清理结果。
     */
    private String removeAuthProvider(String provider) {
        String key = StrUtil.blankToDefault(StrUtil.nullToEmpty(provider).trim(), activeProviderKey());
        if (StrUtil.isBlank(key)) {
            return "用法：solonclaw auth logout <provider>";
        }
        RuntimeConfigResolver resolver = configResolver();
        resolver.removeFileValue("providers." + key + ".apiKey");
        return "认证凭据已清理：\nprovider="
                + key
                + "\napi_key=missing\nnext=solonclaw auth status "
                + key;
    }

    /**
     * 渲染 fallback provider 管理命令，复用本地 provider 配置作为可选目标。
     *
     * @param rawValue 用户输入的 fallback 命令。
     * @return fallback 链路管理结果。
     */
    private String renderFallbackCommand(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        String action = tokens.size() >= 2 ? tokens.get(1).toLowerCase(java.util.Locale.ROOT) : "list";
        if ("list".equals(action) || "ls".equals(action)) {
            return renderFallbackList();
        }
        if ("add".equals(action)) {
            return renderFallbackAdd(tokens);
        }
        if ("remove".equals(action) || "rm".equals(action)) {
            return renderFallbackRemove(tokens);
        }
        if ("clear".equals(action)) {
            return renderFallbackClear();
        }
        return fallbackUsage();
    }

    /** 渲染当前 fallback 链路。 */
    private String renderFallbackList() {
        List<FallbackEntry> chain = fallbackChain();
        StringBuilder buffer = new StringBuilder();
        buffer.append("Fallback Providers\n");
        String providerKey = activeProviderKey();
        String model = activeModel(activeProvider());
        if (StrUtil.isNotBlank(providerKey) || StrUtil.isNotBlank(model)) {
            buffer.append("Primary: ")
                    .append(StrUtil.blankToDefault(model, "(not set)"))
                    .append(" (via ")
                    .append(StrUtil.blankToDefault(providerKey, "(not set)"))
                    .append(")\n");
        }
        if (chain.isEmpty()) {
            buffer.append("No fallback providers configured.\n")
                    .append("Add one with: solonclaw fallback add --provider <provider> --model <model>");
            return buffer.toString();
        }
        buffer.append("Fallback chain (")
                .append(chain.size())
                .append(chain.size() == 1 ? " entry" : " entries")
                .append("):\n");
        for (int i = 0; i < chain.size(); i++) {
            buffer.append(i + 1).append(". ").append(chain.get(i).display()).append('\n');
        }
        buffer.append("Tried in order when the primary model fails.");
        return buffer.toString().trim();
    }

    /**
     * 添加一个 fallback provider。
     *
     * @param tokens fallback add 命令 token。
     * @return 添加结果。
     */
    private String renderFallbackAdd(List<String> tokens) {
        FallbackEntry request = parseFallbackAdd(tokens);
        if (request == null || StrUtil.isBlank(request.provider)) {
            return fallbackUsage();
        }
        if (!providerKeysForAuth().contains(request.provider)) {
            return "未知 provider："
                    + request.provider
                    + "\n请先运行：solonclaw auth add "
                    + request.provider
                    + " --api-key <api-key> --base-url <url> --model <model> --dialect <dialect>";
        }
        if (StrUtil.isBlank(request.model)) {
            request.model = providerModel(request.provider);
        }
        if (StrUtil.isBlank(request.model)) {
            return "fallback provider 缺少模型："
                    + request.provider
                    + "\n用法：solonclaw fallback add --provider "
                    + request.provider
                    + " --model <model>";
        }
        String activeProvider = activeProviderKey();
        String activeModel = activeModel(activeProvider());
        if (request.provider.equals(activeProvider) && request.model.equals(activeModel)) {
            return "Selected model matches the current primary ("
                    + request.display()
                    + ").\nA provider cannot be a fallback for itself.";
        }
        List<FallbackEntry> chain = fallbackChain();
        for (FallbackEntry entry : chain) {
            if (entry.sameTarget(request)) {
                return request.display() + " is already in the fallback chain - skipped.";
            }
        }
        chain.add(request);
        writeFallbackChain(chain);
        return "Added fallback:\n"
                + "provider="
                + request.provider
                + "\nmodel="
                + request.model
                + "\nchain_size="
                + chain.size()
                + "\nnext=solonclaw fallback list";
    }

    /**
     * 移除一个 fallback provider。
     *
     * @param tokens fallback remove 命令 token。
     * @return 移除结果。
     */
    private String renderFallbackRemove(List<String> tokens) {
        List<FallbackEntry> chain = fallbackChain();
        if (chain.isEmpty()) {
            return "No fallback providers configured - nothing to remove.";
        }
        FallbackEntry removed = null;
        if (tokens.size() >= 3 && isPositiveInteger(tokens.get(2))) {
            int index = Integer.parseInt(tokens.get(2)) - 1;
            if (index < 0 || index >= chain.size()) {
                return "fallback index out of range: " + tokens.get(2);
            }
            removed = chain.remove(index);
        } else {
            FallbackEntry request = parseFallbackRemove(tokens);
            if (request == null || StrUtil.isBlank(request.provider)) {
                return "用法：solonclaw fallback remove <index>\n"
                        + "或：solonclaw fallback remove --provider <provider> [--model <model>]";
            }
            int matchedIndex = -1;
            for (int i = 0; i < chain.size(); i++) {
                FallbackEntry entry = chain.get(i);
                if (entry.provider.equals(request.provider)
                        && (StrUtil.isBlank(request.model) || entry.model.equals(request.model))) {
                    if (matchedIndex >= 0 && StrUtil.isBlank(request.model)) {
                        return "provider 有多个 fallback，请补充 --model 精确移除。";
                    }
                    matchedIndex = i;
                }
            }
            if (matchedIndex < 0) {
                return "未找到 fallback provider：" + request.display();
            }
            removed = chain.remove(matchedIndex);
        }
        writeFallbackChain(chain);
        return "Removed fallback:\n"
                + removed.display()
                + "\nchain_size="
                + chain.size();
    }

    /** 清空 fallback 链路。 */
    private String renderFallbackClear() {
        writeFallbackChain(new java.util.ArrayList<FallbackEntry>());
        return "Fallback providers cleared\nchain_size=0\nnext=solonclaw fallback list";
    }

    /** 渲染外部密钥源说明。 */
    private String renderSecretsGuidance() {
        return "外部密钥源\n"
                + "当前版本暂未启用外部密钥源同步；请使用运行环境变量或 runtime/config.yml 管理凭据。\n"
                + "可用入口：solonclaw config path、solonclaw config set、solonclaw doctor。\n"
                + "终端输出会对 apiKey、secret、token 等敏感字段脱敏。";
    }

    /** 渲染本地代理说明，明确不提供 OpenAI 兼容 API Server。 */
    private String renderProxyGuidance() {
        return "本地代理\n"
                + "当前版本不提供 OpenAI 兼容 API Server，也不启动独立本地代理。\n"
                + "本地 UI 可通过 SOLONCLAW_SERVER_URL=http://127.0.0.1:8080 连接已有服务。\n"
                + "远程服务模式请直接配置后端地址，不通过本命令转发模型请求。";
    }

    /** 渲染发送消息说明，引导用户使用内置 send_message 工具与 home channel。 */
    private String renderSendGuidance() {
        return "发送消息\n"
                + "跨渠道主动投递由内置 send_message 工具和 home channel 策略处理。\n"
                + "先配置渠道：solonclaw setup gateway <channel> --enabled true ...\n"
                + "在渠道内可用 /sethome 设置 home channel；定时任务和工具投递会优先使用该目标。\n"
                + "当前终端不把 solonclaw send 作为独立外发客户端。";
    }

    /**
     * 渲染 Hook 管理入口说明；当前插件 hook 由插件系统和配置文件驱动，不提供独立外部向导。
     *
     * @param rawValue 用户输入的 hooks 命令。
     * @return Hook 本地说明。
     */
    private String renderHooksGuidance(String rawValue) {
        String action = commandAction(rawValue, "hooks", "list");
        return "Hooks\n"
                + "action="
                + action
                + "\n插件 hook 通过插件系统注册，当前本地终端不直接安装或触发外部 hook。\n"
                + "runtime/config.yml="
                + configResolver().configFile().getPath()
                + "\n可用入口：/plugins 查看插件加载状态，/security policy 查看工具审批边界，"
                + "solonclaw config set plugins.<name>.enabled true 写入插件开关。";
    }

    /**
     * 渲染诊断导出说明；本地终端只给出可复现的诊断入口，不伪造压缩包导出结果。
     *
     * @param rawValue 用户输入的 dump 命令。
     * @return 诊断导出说明。
     */
    private String renderDumpGuidance(String rawValue) {
        String action = commandAction(rawValue, "dump", "summary");
        return "诊断导出\n"
                + "action="
                + action
                + "\n当前终端不自动打包运行目录，避免把凭据或会话内容写入未确认位置。\n"
                + "可用入口：/debug 查看脱敏诊断摘要，/doctor 查看配置自检，"
                + "solonclaw config show 查看脱敏 runtime/config.yml 快照。";
    }

    /**
     * 渲染运行目录备份说明；备份动作需要用户确认目标路径后手动执行。
     *
     * @param rawValue 用户输入的 backup 命令。
     * @return 运行目录备份说明。
     */
    private String renderBackupGuidance(String rawValue) {
        String action = commandAction(rawValue, "backup", "guide");
        return "运行目录备份\n"
                + "action="
                + action
                + "\nruntime.home="
                + runtimeHomeText()
                + "\nconfig.yml="
                + configResolver().configFile().getPath()
                + "\n当前终端不自动复制运行目录；请在确认目标路径、停用中的写入任务后备份 runtime 目录。"
                + "\n恢复后运行：solonclaw config check && solonclaw doctor";
    }

    /**
     * 渲染 checkpoint 管理说明；当前终端把用户引导到会话 rollback 能力。
     *
     * @param rawValue 用户输入的 checkpoints 命令。
     * @return checkpoint 说明。
     */
    private String renderCheckpointsGuidance(String rawValue) {
        String action = commandAction(rawValue, "checkpoints", "list");
        return "Checkpoints\n"
                + "action="
                + action
                + "\ncheckpoint 与会话生命周期绑定；本地终端不单独导出 checkpoint 存储。\n"
                + "可用入口：/rollback <checkpoint> 回滚当前会话，/sessions 浏览会话，"
                + "/history 查看当前终端上下文。";
    }

    /**
     * 渲染导入入口说明；保留 Skills 和会话导入方向，但不执行未确认格式迁移。
     *
     * @param rawValue 用户输入的 import 命令。
     * @return 导入说明。
     */
    private String renderImportGuidance(String rawValue) {
        String action = commandAction(rawValue, "import", "guide");
        return "导入\n"
                + "action="
                + action
                + "\n当前终端不执行未确认格式的批量导入。Skills 可通过 /skills 或 Skills Hub 管理；"
                + "会话和配置导入请先备份 runtime 目录，再按目标格式迁移。\n"
                + "可用入口：/skills list、/skills install、solonclaw config path、solonclaw doctor。";
    }

    /**
     * 渲染 skill bundle 说明；保留技能包方向，实际安装由 Skills Hub 命令处理。
     *
     * @param rawValue 用户输入的 bundles 命令。
     * @return bundles 说明。
     */
    private String renderBundlesGuidance(String rawValue) {
        String action = commandAction(rawValue, "bundles", "list");
        return "Bundles\n"
                + "action="
                + action
                + "\n技能包属于 Skills Hub 管理范围；当前本地终端不提供单独 bundles 向导。\n"
                + "可用入口：/skills browse、/skills install、/skills list，"
                + "配置入口："
                + configResolver().configFile().getPath();
    }

    /**
     * 渲染记忆配置说明；当前本地终端展示配置入口并避免伪造外部记忆提供方状态。
     *
     * @param rawValue 用户输入的 memory 命令。
     * @return 记忆说明。
     */
    private String renderMemoryGuidance(String rawValue) {
        String action = commandAction(rawValue, "memory", "status");
        return "记忆\n"
                + "action="
                + action
                + "\n本项目保留本地记忆、上下文文件和跨会话检索能力；外部记忆提供方需要通过配置显式接入。\n"
                + "runtime/config.yml="
                + configResolver().configFile().getPath()
                + "\n可用入口：/config show、/config set memory.enabled true、/history、/sessions、/recap。";
    }

    /**
     * 渲染 Dashboard 启动说明；Dashboard 随 Java 单实例服务启动，不在终端另起第二个服务。
     *
     * @param rawValue 用户输入的 dashboard 命令。
     * @return Dashboard 启动说明。
     */
    private String renderDashboardGuidance(String rawValue) {
        String action = commandAction(rawValue, "dashboard", "status");
        String port =
                appConfig == null || appConfig.getDashboard() == null
                        ? "8080"
                        : String.valueOf(appConfig.getDashboard().getBindPort());
        return "Dashboard\n"
                + "action="
                + action
                + "\nDashboard 随后端单实例服务启动；本地终端不会再启动第二个 Java 服务。\n"
                + "启动：java -jar target/solon-claw-0.0.1.jar\n"
                + "指定运行目录：java -Dsolonclaw.runtime.home=<runtime> -jar target/solon-claw-0.0.1.jar\n"
                + "地址：http://127.0.0.1:"
                + port
                + "\nTUI 可通过 SOLONCLAW_SERVER_URL=http://127.0.0.1:"
                + port
                + " 连接已有后端。";
    }

    /**
     * 渲染日志查看说明；日志由运行实例和 runtime 目录管理，本地终端不 tail 外部进程。
     *
     * @param rawValue 用户输入的 logs 命令。
     * @return 日志说明。
     */
    private String renderLogsGuidance(String rawValue) {
        String action = commandAction(rawValue, "logs", "tail");
        return "日志\n"
                + "action="
                + action
                + "\nruntime.home="
                + runtimeHomeText()
                + "\n当前终端不直接 tail 外部服务日志；可用 /debug 查看脱敏诊断摘要，"
                + "/events 查看最近一次终端运行事件，Dashboard 日志页查看运行实例日志。";
    }

    /**
     * 渲染 prompt size 估算说明；当前未把顶层命令作为独立 tokenizer 工具执行。
     *
     * @param rawValue 用户输入的 prompt-size 命令。
     * @return prompt size 说明。
     */
    private String renderPromptSizeGuidance(String rawValue) {
        String prompt = rawValue == null ? "" : rawValue.replaceFirst("(?i)^prompt-size\\s*", "");
        int charCount = StrUtil.nullToEmpty(prompt).length();
        int roughTokens = Math.max(0, (int) Math.ceil(charCount / 4.0d));
        return "Prompt Size\n"
                + "chars="
                + charCount
                + "\nrough_tokens="
                + roughTokens
                + "\n该值是本地粗略估算；真实 token、cache token 与费用以模型响应后的 /usage 和价格分析为准。"
                + "\n可用入口：/usage、/status、/doctor。";
    }

    /**
     * 渲染 pairing 管理说明；本地终端没有真实平台上下文，不能代替渠道管理员私聊执行授权。
     *
     * @param rawValue 用户输入的 pairing 命令。
     * @return pairing 管理说明。
     */
    private String renderPairingGuidance(String rawValue) {
        String command = commandText(rawValue);
        if (StrUtil.isBlank(command) || "pairing".equalsIgnoreCase(command)) {
            command = "pairing list";
        }
        return "Pairing 管理\n"
                + "支持渠道："
                + String.join(",", DOMESTIC_CHANNELS)
                + "\n本地终端只能展示管理路径，不能伪造平台管理员身份审批用户。\n"
                + "请在对应平台管理员私聊中执行：/"
                + command
                + "\n常用命令：\n"
                + "- /pairing claim-admin - 在渠道私聊中完成首个管理员认领\n"
                + "- /pairing pending <platform> - 查看待处理 pairing code\n"
                + "- /pairing approve <platform> <code> - 批准用户\n"
                + "- /pairing approved <platform> - 查看授权用户列表\n"
                + "- /pairing revoke <platform> <userId> - 撤销用户\n"
                + "- /pairing clear-pending <platform> - 清理待处理请求\n"
                + "平台值：feishu、dingtalk、wecom、weixin、qqbot、yuanbao。";
    }

    /**
     * 渲染网关运行状态。
     *
     * @param rawValue 用户输入的 gateway status 命令。
     * @return 网关状态摘要。
     */
    private String renderGatewayStatus(String rawValue) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Gateway Status\n")
                .append("runtime=single-process\n")
                .append("config.path=")
                .append(configResolver().configFile().getPath())
                .append('\n')
                .append("dashboard.port=")
                .append(appConfig == null || appConfig.getDashboard() == null
                        ? "(unknown)"
                        : String.valueOf(appConfig.getDashboard().getBindPort()))
                .append('\n');
        for (String channel : DOMESTIC_CHANNELS) {
            AppConfig.ChannelConfig config = channelConfig(channel);
            buffer.append(channelStatusLine(channel, config)).append('\n');
        }
        if (shellTokens(rawValue).contains("--deep")) {
            buffer.append("deep=已展示国内渠道关键配置完整性；不会扫描未确认渠道或系统服务。")
                    .append('\n');
        }
        buffer.append("next=solonclaw setup gateway");
        return buffer.toString();
    }

    /** 渲染国内渠道列表，用于 gateway list。 */
    private String renderGatewayList() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Gateway Channels\n");
        for (String channel : DOMESTIC_CHANNELS) {
            AppConfig.ChannelConfig config = channelConfig(channel);
            buffer.append("- ")
                    .append(channel)
                    .append(' ')
                    .append(channelStatus(config, RuntimeSetupSpec.requiredChannelKeys(channel)))
                    .append(" enabled=")
                    .append(config != null && config.isEnabled())
                    .append('\n');
        }
        buffer.append("仅列出当前确认保留的国内渠道。");
        return buffer.toString();
    }

    /** 渲染前台启动网关说明。 */
    private String renderGatewayRun() {
        return "Gateway Run\n"
                + "前台启动当前单实例服务：java -jar target/solon-claw-0.0.1.jar\n"
                + "指定运行目录：java -Dsolonclaw.runtime.home=<runtime> -jar target/solon-claw-0.0.1.jar";
    }

    /** 渲染 start 命令说明，避免误导为独立后台服务。 */
    private String renderGatewayStart() {
        return "Gateway Start\n"
                + "当前版本不安装独立后台服务；请使用 java -jar 启动单实例服务。\n"
                + "示例：java -Dsolonclaw.runtime.home=<runtime> -jar target/solon-claw-0.0.1.jar";
    }

    /** 渲染 stop 命令说明。 */
    private String renderGatewayStop() {
        return "Gateway Stop\n"
                + "本地终端不会直接杀死外部 Java 进程。\n"
                + "在 CLI/TUI 会话中使用 /stop 停止当前会话运行中的任务；停止服务请结束启动该 jar 的进程。";
    }

    /** 渲染 restart 命令说明。 */
    private String renderGatewayRestart() {
        return "Gateway Restart\n"
                + "在 CLI/TUI 会话中使用 /restart 请求当前运行实例重载网关。\n"
                + "如果服务未启动，请重新执行 java -jar 启动命令。";
    }

    /** 渲染 install 命令说明，明确本项目不做本机服务管理安装。 */
    private String renderGatewayInstall() {
        return "Gateway Install\n"
                + "当前版本不安装独立后台服务；运行形态只保留 java -jar 与 Docker。\n"
                + "本地前台：java -Dsolonclaw.runtime.home=<runtime> -jar target/solon-claw-0.0.1.jar\n"
                + "容器部署：使用项目 Docker 镜像或部署脚本，由外部进程管理器负责守护。";
    }

    /** 渲染 uninstall 命令说明，避免误删用户系统服务。 */
    private String renderGatewayUninstall() {
        return "Gateway Uninstall\n"
                + "当前版本没有独立后台服务单元需要卸载，也不会自动删除系统服务文件。\n"
                + "如需停止服务，请结束启动该 jar 的进程或停止对应 Docker 容器。";
    }

    /**
     * 写入一个 runtime/config.yml 配置项。
     *
     * @param args set 子命令后的参数文本。
     * @return 写入结果说明。
     */
    private String renderConfigSet(String args) {
        int split = args.indexOf(' ');
        if (split <= 0 || split >= args.length() - 1) {
            return "用法：solonclaw config set <key> <value>";
        }
        String key = args.substring(0, split).trim();
        String value = args.substring(split + 1).trim();
        try {
            configResolver().setFileValue(key, value);
        } catch (IllegalStateException e) {
            return "不支持的配置键：" + key + "\n"
                    + "请使用已登记的 runtime/config.yml 键名；fallback provider 链路请使用 "
                    + "solonclaw fallback add/remove/clear 管理，避免写坏 YAML 列表结构。";
        }
        return "已写入 runtime/config.yml：" + key + "=" + safeConfigValue(key, value);
    }

    /** 读取当前运行时配置解析器。 */
    private RuntimeConfigResolver configResolver() {
        String home =
                appConfig == null || appConfig.getRuntime() == null
                        ? ""
                        : appConfig.getRuntime().getHome();
        return RuntimeConfigResolver.initialize(home);
    }

    /** 创建共享初始化配置服务，保证终端命令与后续 TUI/slash 配置入口共用写入规则。 */
    private RuntimeSetupService runtimeSetupService() {
        return new RuntimeSetupService(appConfig);
    }

    /** 读取当前主 provider key。 */
    private String activeProviderKey() {
        String runtimeProvider = configResolver().get("model.providerKey");
        if (StrUtil.isNotBlank(runtimeProvider)) {
            return runtimeProvider.trim();
        }
        if (appConfig == null) {
            return "";
        }
        String providerKey =
                appConfig.getModel() == null ? "" : appConfig.getModel().getProviderKey();
        if (StrUtil.isBlank(providerKey) && appConfig.getLlm() != null) {
            providerKey = appConfig.getLlm().getProvider();
        }
        return StrUtil.nullToEmpty(providerKey).trim();
    }

    /** 读取当前主 provider 配置。 */
    private AppConfig.ProviderConfig activeProvider() {
        if (appConfig == null || appConfig.getProviders() == null) {
            return null;
        }
        return appConfig.getProviders().get(activeProviderKey());
    }

    /** 读取当前模型名。 */
    private String activeModel(AppConfig.ProviderConfig provider) {
        String runtimeModel = configResolver().get("model.default");
        if (StrUtil.isNotBlank(runtimeModel)) {
            return runtimeModel.trim();
        }
        String model = "";
        if (appConfig != null && appConfig.getModel() != null) {
            model = appConfig.getModel().getDefault();
        }
        if (StrUtil.isBlank(model) && appConfig != null && appConfig.getLlm() != null) {
            model = appConfig.getLlm().getModel();
        }
        if (StrUtil.isBlank(model) && provider != null) {
            model = provider.getDefaultModel();
        }
        return StrUtil.nullToEmpty(model).trim();
    }

    /** 汇总已启用且关键字段完整的国内渠道。 */
    private String configuredChannels() {
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (String channel : DOMESTIC_CHANNELS) {
            if ("configured".equals(channelStatus(channelConfig(channel), RuntimeSetupSpec.requiredChannelKeys(channel)))) {
                values.add(channel);
            }
        }
        return values.isEmpty() ? "(none)" : String.join(",", values);
    }

    /** 汇总已启用但关键字段缺失的国内渠道。 */
    private String missingEnabledChannels() {
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (String channel : DOMESTIC_CHANNELS) {
            AppConfig.ChannelConfig config = channelConfig(channel);
            String status = channelStatus(config, RuntimeSetupSpec.requiredChannelKeys(channel));
            if (config != null && config.isEnabled() && "missing_config".equals(status)) {
                values.add(channel);
            }
        }
        return values.isEmpty() ? "(none)" : String.join(",", values);
    }

    /** 读取运行时目录文本，兼容测试和极简构造场景。 */
    private String runtimeHomeText() {
        if (appConfig == null || appConfig.getRuntime() == null) {
            return "(unknown)";
        }
        return StrUtil.blankToDefault(appConfig.getRuntime().getHome(), "(unknown)");
    }

    /**
     * 渲染单个渠道的状态行。
     *
     * @param channel 渠道名称。
     * @param config 渠道配置。
     * @return 不含敏感字段的渠道状态。
     */
    private String channelStatusLine(String channel, AppConfig.ChannelConfig config) {
        return channel
                + ": enabled="
                + (config != null && config.isEnabled())
                + ", status="
                + channelStatus(config, RuntimeSetupSpec.requiredChannelKeys(channel));
    }

    /**
     * 根据自检状态生成下一步建议。
     *
     * @param diagnostics runtime/config.yml 诊断结果。
     * @param providerKey 当前 provider key。
     * @param model 当前默认模型。
     * @param apiKeyConfigured API Key 是否已配置。
     * @param missingChannels 启用但缺失关键字段的渠道列表。
     * @return 面向终端用户的下一步建议。
     */
    private String doctorNextStep(
            Map<String, Object> diagnostics,
            String providerKey,
            String model,
            boolean apiKeyConfigured,
            String missingChannels) {
        if (Boolean.TRUE.equals(diagnostics.get("has_issues"))) {
            return "run solonclaw config check";
        }
        if (StrUtil.isBlank(providerKey) || StrUtil.isBlank(model) || !apiKeyConfigured) {
            return "run solonclaw setup model";
        }
        if (StrUtil.isNotBlank(missingChannels) && !"(none)".equals(missingChannels)) {
            return "run solonclaw setup gateway " + missingChannels.split(",")[0];
        }
        return "run solonclaw setup gateway or start chatting";
    }

    /**
     * 读取指定国内渠道配置。
     *
     * @param channel 渠道标识。
     * @return 渠道配置。
     */
    private AppConfig.ChannelConfig channelConfig(String channel) {
        if (appConfig == null || appConfig.getChannels() == null) {
            return null;
        }
        if ("feishu".equals(channel)) {
            return appConfig.getChannels().getFeishu();
        }
        if ("dingtalk".equals(channel)) {
            return appConfig.getChannels().getDingtalk();
        }
        if ("wecom".equals(channel)) {
            return appConfig.getChannels().getWecom();
        }
        if ("weixin".equals(channel)) {
            return appConfig.getChannels().getWeixin();
        }
        if ("qqbot".equals(channel)) {
            return appConfig.getChannels().getQqbot();
        }
        if ("yuanbao".equals(channel)) {
            return appConfig.getChannels().getYuanbao();
        }
        return null;
    }

    /**
     * 计算渠道配置状态。
     *
     * @param config 渠道配置。
     * @param requiredKeys 必填配置项。
     * @return configured / disabled / missing_config。
     */
    private String channelStatus(AppConfig.ChannelConfig config, List<String> requiredKeys) {
        if (config == null) {
            return "missing_config";
        }
        if (!config.isEnabled()) {
            return "disabled";
        }
        for (String key : requiredKeys) {
            if (StrUtil.isBlank(channelField(config, key))) {
                return "missing_config";
            }
        }
        return "configured";
    }

    /**
     * 读取渠道字段。
     *
     * @param config 渠道配置。
     * @param key 字段名。
     * @return 字段文本。
     */
    private String channelField(AppConfig.ChannelConfig config, String key) {
        if ("appId".equals(key)) {
            return config.getAppId();
        }
        if ("appSecret".equals(key)) {
            return config.getAppSecret();
        }
        if ("clientId".equals(key)) {
            return config.getClientId();
        }
        if ("clientSecret".equals(key)) {
            return config.getClientSecret();
        }
        if ("botId".equals(key)) {
            return config.getBotId();
        }
        if ("secret".equals(key)) {
            return config.getSecret();
        }
        if ("token".equals(key)) {
            return config.getToken();
        }
        if ("accountId".equals(key)) {
            return config.getAccountId();
        }
        if ("robotCode".equals(key)) {
            return config.getRobotCode();
        }
        return "";
    }

    /**
     * 解析模型配置命令参数。
     *
     * @param rawValue 用户输入的 model set/configure 命令文本。
     * @return 参数对象；无法识别时返回空对象用于输出用法。
     */
    private ModelSetRequest parseModelSet(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.size() < 2) {
            return null;
        }
        int optionsStart = 2;
        if ("setup".equalsIgnoreCase(tokens.get(0)) && "model".equalsIgnoreCase(tokens.get(1))) {
            optionsStart = 2;
        } else if ("model".equalsIgnoreCase(tokens.get(0))
                && ("set".equalsIgnoreCase(tokens.get(1))
                        || "configure".equalsIgnoreCase(tokens.get(1)))) {
            optionsStart = 2;
        } else {
            return null;
        }
        ModelSetRequest request = new ModelSetRequest();
        request.providerKey = StrUtil.blankToDefault(activeProviderKey(), "default");
        AppConfig.ProviderConfig provider = activeProvider();
        request.providerName =
                provider == null
                        ? request.providerKey
                        : StrUtil.blankToDefault(provider.getName(), request.providerKey);
        request.baseUrl = provider == null ? "" : StrUtil.nullToEmpty(provider.getBaseUrl()).trim();
        request.apiKey = provider == null ? "" : StrUtil.nullToEmpty(provider.getApiKey()).trim();
        request.model = activeModel(provider);
        request.dialect =
                provider == null
                        ? "openai"
                        : StrUtil.blankToDefault(provider.getDialect(), "openai").trim();

        for (int i = optionsStart; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            if ("--provider".equals(token) || "--provider-key".equals(token)) {
                request.providerKey = value;
                i++;
            } else if ("--name".equals(token) || "--provider-name".equals(token)) {
                request.providerName = value;
                request.providerNameProvided = true;
                i++;
            } else if ("--base-url".equals(token) || "--baseUrl".equals(token)) {
                request.baseUrl = value;
                request.baseUrlProvided = true;
                i++;
            } else if ("--api-key".equals(token) || "--apiKey".equals(token)) {
                request.apiKey = value;
                i++;
            } else if ("--model".equals(token) || "--default-model".equals(token)) {
                request.model = value;
                request.modelProvided = true;
                i++;
            } else if ("--dialect".equals(token)) {
                request.dialect = value;
                request.dialectProvided = true;
                i++;
            } else if (!token.startsWith("--")) {
                request.model = token;
                request.modelProvided = true;
            }
        }
        request.providerKey = StrUtil.nullToEmpty(request.providerKey).trim();
        applyProviderTemplateDefaults(request);
        request.providerName =
                StrUtil.blankToDefault(
                                StrUtil.nullToEmpty(request.providerName).trim(), request.providerKey)
                        .trim();
        request.baseUrl = StrUtil.nullToEmpty(request.baseUrl).trim();
        request.apiKey = StrUtil.nullToEmpty(request.apiKey).trim();
        request.model = StrUtil.nullToEmpty(request.model).trim();
        request.dialect = StrUtil.nullToEmpty(request.dialect).trim();
        return request;
    }

    /**
     * 返回模型配置命令用法。
     *
     * @return 面向终端用户的用法文本。
     */
    private String modelSetUsage() {
        return "用法：solonclaw model set --provider <provider> --base-url <https://host/v1> "
                + "--api-key <api-key> --model <model> --dialect <openai|openai-responses|ollama|gemini|anthropic>";
    }

    /**
     * 使用 provider 模板补齐模型写入请求中的默认字段，支持未配置 provider 直接激活。
     *
     * @param request 模型配置请求。
     */
    private void applyProviderTemplateDefaults(ModelSetRequest request) {
        if (request == null || StrUtil.isBlank(request.providerKey)) {
            return;
        }
        RuntimeProviderSetupSpec.ProviderTemplate template =
                RuntimeProviderSetupSpec.provider(request.providerKey);
        if (!request.providerNameProvided) {
            request.providerName = providerName(request.providerKey);
        }
        if (!request.baseUrlProvided) {
            request.baseUrl = providerBaseUrl(request.providerKey);
        }
        if (!request.dialectProvided) {
            request.dialect = providerDialect(request.providerKey);
        }
        if (template == null) {
            return;
        }
        if (!request.providerNameProvided) {
            request.providerName = template.getName();
        }
        if (!request.baseUrlProvided) {
            request.baseUrl = template.getBaseUrl();
        }
        if (!request.modelProvided) {
            request.model = template.getDefaultModel();
            request.modelProvided = StrUtil.isNotBlank(request.model);
        }
        if (!request.dialectProvided) {
            request.dialect = template.getDialect();
        }
    }

    /**
     * 使用 provider 模板补齐认证写入请求中的默认字段，保证 auth/login 与 model setup 行为一致。
     *
     * @param request 认证写入请求。
     */
    private void applyProviderTemplateDefaults(AuthSetRequest request) {
        if (request == null || StrUtil.isBlank(request.providerKey)) {
            return;
        }
        RuntimeProviderSetupSpec.ProviderTemplate template =
                RuntimeProviderSetupSpec.provider(request.providerKey);
        if (!request.providerNameProvided) {
            request.providerName = providerName(request.providerKey);
        }
        if (!request.baseUrlProvided) {
            request.baseUrl = providerBaseUrl(request.providerKey);
        }
        if (!request.modelProvided) {
            request.model = providerModel(request.providerKey);
        }
        if (!request.dialectProvided) {
            request.dialect = providerDialect(request.providerKey);
        }
        if (template == null) {
            return;
        }
        if (!request.providerNameProvided) {
            request.providerName = template.getName();
        }
        if (!request.baseUrlProvided) {
            request.baseUrl = template.getBaseUrl();
        }
        if (!request.modelProvided) {
            request.model = template.getDefaultModel();
        }
        if (!request.dialectProvided) {
            request.dialect = template.getDialect();
        }
    }

    /**
     * 解析国内渠道配置命令参数。
     *
     * @param rawValue 用户输入的 setup gateway 命令文本。
     * @return 渠道配置请求，无法识别时返回 null。
     */
    private GatewaySetRequest parseGatewaySet(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.size() < 3) {
            return null;
        }
        int channelIndex;
        if ("setup".equalsIgnoreCase(tokens.get(0)) && "gateway".equalsIgnoreCase(tokens.get(1))) {
            channelIndex = 2;
        } else if ("gateway".equalsIgnoreCase(tokens.get(0))
                && "setup".equalsIgnoreCase(tokens.get(1))) {
            channelIndex = 2;
        } else {
            return null;
        }
        GatewaySetRequest request = new GatewaySetRequest();
        request.channel = StrUtil.nullToEmpty(tokens.get(channelIndex))
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        for (int i = channelIndex + 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("--")) {
                return null;
            }
            String flag = token.substring(2);
            String value = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            String configKey = RuntimeSetupSpec.normalizeChannelFlag(flag);
            if (StrUtil.isBlank(configKey)) {
                request.unsupportedField = flag;
                return request;
            }
            if (StrUtil.isBlank(value) || value.startsWith("--")) {
                return null;
            }
            request.values.put(configKey, value);
            i++;
        }
        return request;
    }

    /**
     * 解析 auth add 子命令，写入 provider API Key 凭据。
     *
     * @param rawValue 用户输入的 auth add 命令。
     * @return 认证写入请求；参数缺失时返回 null。
     */
    private AuthSetRequest parseAuthAdd(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.size() < 3
                || !"auth".equalsIgnoreCase(tokens.get(0))
                || !"add".equalsIgnoreCase(tokens.get(1))) {
            return null;
        }
        AuthSetRequest request = defaultAuthRequest();
        request.providerKey = tokens.get(2);
        request.activate = false;
        applyAuthOptions(request, tokens, 3);
        normalizeAuthRequest(request);
        return request;
    }

    /**
     * 解析 login --api-key 子命令，将登录习惯入口映射到本地 provider 凭据写入。
     *
     * @param rawValue 用户输入的 login 命令。
     * @return 认证写入请求；参数缺失时返回 null。
     */
    private AuthSetRequest parseLoginAuthSet(String rawValue) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.size() < 2 || !"login".equalsIgnoreCase(tokens.get(0))) {
            return null;
        }
        AuthSetRequest request = defaultAuthRequest();
        request.activate = true;
        applyAuthOptions(request, tokens, 1);
        normalizeAuthRequest(request);
        return request;
    }

    /** 创建带当前 provider 默认值的认证写入请求。 */
    private AuthSetRequest defaultAuthRequest() {
        AuthSetRequest request = new AuthSetRequest();
        request.providerKey = StrUtil.blankToDefault(activeProviderKey(), "default");
        request.providerName = providerName(request.providerKey);
        request.baseUrl = providerBaseUrl(request.providerKey);
        request.model = providerModel(request.providerKey);
        request.dialect = StrUtil.blankToDefault(providerDialect(request.providerKey), "openai");
        return request;
    }

    /**
     * 从命令 tokens 中提取通用认证参数。
     *
     * @param request 认证写入请求。
     * @param tokens shell token 列表。
     * @param startIndex 起始下标。
     */
    private void applyAuthOptions(AuthSetRequest request, List<String> tokens, int startIndex) {
        for (int i = startIndex; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            if ("--provider".equals(token) || "--provider-key".equals(token)) {
                request.providerKey = value;
                i++;
            } else if ("--label".equals(token)
                    || "--name".equals(token)
                    || "--provider-name".equals(token)) {
                request.providerName = value;
                request.providerNameProvided = true;
                i++;
            } else if ("--api-key".equals(token) || "--apiKey".equals(token)) {
                request.apiKey = value;
                i++;
            } else if ("--base-url".equals(token) || "--baseUrl".equals(token)) {
                request.baseUrl = value;
                request.baseUrlProvided = true;
                i++;
            } else if ("--model".equals(token) || "--default-model".equals(token)) {
                request.model = value;
                request.modelProvided = true;
                i++;
            } else if ("--dialect".equals(token)) {
                request.dialect = value;
                request.dialectProvided = true;
                i++;
            } else if ("--activate".equals(token)) {
                request.activate = true;
            } else if ("--no-activate".equals(token)) {
                request.activate = false;
            }
        }
    }

    /**
     * 清理认证写入请求中的空白并补充展示名。
     *
     * @param request 认证写入请求。
     */
    private void normalizeAuthRequest(AuthSetRequest request) {
        request.providerKey = StrUtil.nullToEmpty(request.providerKey).trim();
        applyProviderTemplateDefaults(request);
        request.providerName =
                StrUtil.blankToDefault(
                                StrUtil.nullToEmpty(request.providerName).trim(),
                                request.providerKey)
                        .trim();
        request.apiKey = StrUtil.nullToEmpty(request.apiKey).trim();
        request.baseUrl = StrUtil.nullToEmpty(request.baseUrl).trim();
        request.model = StrUtil.nullToEmpty(request.model).trim();
        request.dialect = StrUtil.nullToEmpty(request.dialect).trim();
    }

    /**
     * 返回认证写入命令用法。
     *
     * @return 面向终端用户的用法文本。
     */
    private String authAddUsage() {
        return "用法：solonclaw auth add <provider> --api-key <api-key> --base-url <https://host/v1> "
                + "--model <model> --dialect <openai|openai-responses|ollama|gemini|anthropic>";
    }

    /**
     * 返回 fallback 管理命令用法。
     *
     * @return 面向终端用户的用法文本。
     */
    private String fallbackUsage() {
        return "用法：solonclaw fallback list\n"
                + "添加：solonclaw fallback add --provider <provider> --model <model>\n"
                + "移除：solonclaw fallback remove <index>\n"
                + "清空：solonclaw fallback clear";
    }

    /** 读取当前 fallback 链路，优先使用 runtime/config.yml 中的结构化列表。 */
    @SuppressWarnings("unchecked")
    private List<FallbackEntry> fallbackChain() {
        Object raw = configResolver().getRaw("fallbackProviders");
        List<FallbackEntry> values = new java.util.ArrayList<FallbackEntry>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<Object, Object> map = (Map<Object, Object>) item;
                FallbackEntry entry =
                        new FallbackEntry(
                                readMapText(map, "provider"), readMapText(map, "model"));
                if (StrUtil.isNotBlank(entry.provider)) {
                    values.add(entry);
                }
            }
            return values;
        }
        if (appConfig != null && appConfig.getFallbackProviders() != null) {
            for (AppConfig.FallbackProviderConfig config : appConfig.getFallbackProviders()) {
                if (config == null || StrUtil.isBlank(config.getProvider())) {
                    continue;
                }
                values.add(new FallbackEntry(config.getProvider(), config.getModel()));
            }
        }
        return values;
    }

    /**
     * 写入 fallback 链路，保持 AppConfig 可解析的 YAML 列表结构。
     *
     * @param chain fallback 链路。
     */
    private void writeFallbackChain(List<FallbackEntry> chain) {
        List<Map<String, String>> values = new java.util.ArrayList<Map<String, String>>();
        if (chain != null) {
            for (FallbackEntry entry : chain) {
                if (entry == null || StrUtil.isBlank(entry.provider)) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<String, String>();
                item.put("provider", entry.provider);
                if (StrUtil.isNotBlank(entry.model)) {
                    item.put("model", entry.model);
                }
                values.add(item);
            }
        }
        configResolver().setFileList("fallbackProviders", values);
    }

    /**
     * 解析 fallback add 参数。
     *
     * @param tokens 命令 token。
     * @return fallback 目标。
     */
    private FallbackEntry parseFallbackAdd(List<String> tokens) {
        if (tokens == null || tokens.size() < 2) {
            return null;
        }
        FallbackEntry entry = new FallbackEntry("", "");
        for (int i = 2; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            if ("--provider".equals(token) || "--provider-key".equals(token)) {
                entry.provider = value;
                i++;
            } else if ("--model".equals(token) || "--default-model".equals(token)) {
                entry.model = value;
                i++;
            } else if (!token.startsWith("--") && StrUtil.isBlank(entry.provider)) {
                entry.provider = token;
            } else if (!token.startsWith("--") && StrUtil.isBlank(entry.model)) {
                entry.model = token;
            }
        }
        entry.normalize();
        return entry;
    }

    /**
     * 解析 fallback remove 参数。
     *
     * @param tokens 命令 token。
     * @return fallback 目标。
     */
    private FallbackEntry parseFallbackRemove(List<String> tokens) {
        if (tokens == null || tokens.size() < 3) {
            return null;
        }
        FallbackEntry entry = new FallbackEntry("", "");
        for (int i = 2; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            if ("--provider".equals(token) || "--provider-key".equals(token)) {
                entry.provider = value;
                i++;
            } else if ("--model".equals(token) || "--default-model".equals(token)) {
                entry.model = value;
                i++;
            } else if (!token.startsWith("--") && StrUtil.isBlank(entry.provider)) {
                entry.provider = token;
            }
        }
        entry.normalize();
        return entry;
    }

    /**
     * 从 YAML Map 中读取文本值。
     *
     * @param map YAML 映射。
     * @param key 字段名。
     * @return 字段文本。
     */
    private String readMapText(Map<Object, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return StrUtil.nullToEmpty(value == null ? "" : String.valueOf(value)).trim();
    }

    /**
     * 判断文本是否为正整数。
     *
     * @param value 文本值。
     * @return 正整数返回 true。
     */
    private boolean isPositiveInteger(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return Integer.parseInt(value) > 0;
    }

    /** 汇总 provider key，包含启动配置和 runtime/config.yml 动态配置。 */
    private java.util.LinkedHashSet<String> providerKeysForAuth() {
        java.util.LinkedHashSet<String> providers = new java.util.LinkedHashSet<String>();
        if (appConfig != null && appConfig.getProviders() != null) {
            providers.addAll(appConfig.getProviders().keySet());
        }
        for (String key : configResolver().fileValues().keySet()) {
            if (key.startsWith("providers.")) {
                String remaining = key.substring("providers.".length());
                int dot = remaining.indexOf('.');
                if (dot > 0) {
                    providers.add(remaining.substring(0, dot));
                }
            }
        }
        for (RuntimeProviderSetupSpec.ProviderTemplate template : RuntimeProviderSetupSpec.providers()) {
            providers.add(template.getSlug());
        }
        return providers;
    }

    /**
     * 判断 provider API Key 是否可用。
     *
     * @param providerKey provider 键。
     * @return 已配置且非占位密钥返回 true。
     */
    private boolean providerApiKeyConfigured(String providerKey) {
        String key = StrUtil.nullToEmpty(providerKey).trim();
        String fileValue = configResolver().get("providers." + key + ".apiKey");
        if (SecretValueGuard.hasUsableSecret(fileValue)) {
            return true;
        }
        AppConfig.ProviderConfig provider = providerByKey(key);
        return provider != null && SecretValueGuard.hasUsableSecret(provider.getApiKey());
    }

    /** 读取 provider 展示名。 */
    private String providerName(String providerKey) {
        String key = StrUtil.nullToEmpty(providerKey).trim();
        String fileValue = configResolver().get("providers." + key + ".name");
        if (StrUtil.isNotBlank(fileValue)) {
            return fileValue;
        }
        AppConfig.ProviderConfig provider = providerByKey(key);
        if (provider != null) {
            return StrUtil.blankToDefault(provider.getName(), key);
        }
        RuntimeProviderSetupSpec.ProviderTemplate template = RuntimeProviderSetupSpec.provider(key);
        return template == null ? key : StrUtil.blankToDefault(template.getName(), key);
    }

    /** 读取 provider 默认模型。 */
    private String providerModel(String providerKey) {
        String key = StrUtil.nullToEmpty(providerKey).trim();
        String fileValue = configResolver().get("providers." + key + ".defaultModel");
        if (StrUtil.isNotBlank(fileValue)) {
            return fileValue;
        }
        if (key.equals(activeProviderKey())) {
            String active = activeModel(activeProvider());
            if (StrUtil.isNotBlank(active)) {
                return active;
            }
        }
        AppConfig.ProviderConfig provider = providerByKey(key);
        if (provider != null && StrUtil.isNotBlank(provider.getDefaultModel())) {
            return StrUtil.nullToEmpty(provider.getDefaultModel()).trim();
        }
        RuntimeProviderSetupSpec.ProviderTemplate template = RuntimeProviderSetupSpec.provider(key);
        return template == null ? "" : template.getDefaultModel();
    }

    /** 读取 provider 方言。 */
    private String providerDialect(String providerKey) {
        String key = StrUtil.nullToEmpty(providerKey).trim();
        String fileValue = configResolver().get("providers." + key + ".dialect");
        if (StrUtil.isNotBlank(fileValue)) {
            return fileValue;
        }
        AppConfig.ProviderConfig provider = providerByKey(key);
        if (provider != null && StrUtil.isNotBlank(provider.getDialect())) {
            return StrUtil.nullToEmpty(provider.getDialect()).trim();
        }
        RuntimeProviderSetupSpec.ProviderTemplate template = RuntimeProviderSetupSpec.provider(key);
        return template == null ? "" : template.getDialect();
    }

    /** 读取 provider baseUrl。 */
    private String providerBaseUrl(String providerKey) {
        String key = StrUtil.nullToEmpty(providerKey).trim();
        String fileValue = configResolver().get("providers." + key + ".baseUrl");
        if (StrUtil.isNotBlank(fileValue)) {
            return fileValue;
        }
        AppConfig.ProviderConfig provider = providerByKey(key);
        if (provider != null && StrUtil.isNotBlank(provider.getBaseUrl())) {
            return StrUtil.nullToEmpty(provider.getBaseUrl()).trim();
        }
        RuntimeProviderSetupSpec.ProviderTemplate template = RuntimeProviderSetupSpec.provider(key);
        return template == null ? "" : template.getBaseUrl();
    }

    /** 读取指定 provider 配置。 */
    private AppConfig.ProviderConfig providerByKey(String providerKey) {
        if (appConfig == null || appConfig.getProviders() == null) {
            return null;
        }
        return appConfig.getProviders().get(StrUtil.nullToEmpty(providerKey).trim());
    }

    /**
     * 返回渠道配置命令用法。
     *
     * @return 面向终端用户的用法文本。
     */
    private String gatewaySetUsage() {
        return "用法：solonclaw setup gateway <feishu|dingtalk|wecom|weixin|qqbot|yuanbao> "
                + "--enabled true [--app-id <id>] [--app-secret <secret>]";
    }

    /**
     * 按常见 shell 规则切分参数，支持单引号与双引号包裹的值。
     *
     * @param input 输入文本。
     * @return 参数列表。
     */
    private List<String> shellTokens(String input) {
        java.util.ArrayList<String> tokens = new java.util.ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !singleQuoted && !doubleQuoted) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 提取本地管理命令的第一个动作参数，用于渲染说明时保留用户输入意图。
     *
     * @param rawValue 用户输入的完整命令。
     * @param command 命令名。
     * @param defaultAction 默认动作。
     * @return 规范化后的动作名。
     */
    private String commandAction(String rawValue, String command, String defaultAction) {
        List<String> tokens = shellTokens(rawValue);
        if (tokens.size() >= 2 && command.equalsIgnoreCase(tokens.get(0))) {
            return tokens.get(1);
        }
        return defaultAction;
    }

    /**
     * 清理本地命令前缀。
     *
     * @param input 输入文本。
     * @return 不含 slash 的小写命令。
     */
    private String commandKey(String input) {
        return commandText(input).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 清理本地命令前缀，并保留配置键和值的原始大小写。
     *
     * @param input 输入文本。
     * @return 不含 slash 的命令文本。
     */
    private String commandText(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    /**
     * 脱敏配置值。
     *
     * @param key 配置键。
     * @param value 配置值。
     * @return 适合终端显示的文本。
     */
    private String safeConfigValue(String key, Object value) {
        String text = String.valueOf(value);
        String lower = StrUtil.nullToEmpty(key).toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("apikey")
                || lower.contains("api_key")
                || lower.contains("accesskey")
                || lower.contains("privatekey")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("password")) {
            return StrUtil.isBlank(text) ? "" : "***";
        }
        if (lower.contains("url")) {
            return SecretRedactor.maskUrl(text);
        }
        return text;
    }

    /** 模型配置命令解析结果。 */
    private static class ModelSetRequest {
        /** provider 键，写入 model.providerKey 与 providers.<key>。 */
        private String providerKey;

        /** provider 展示名。 */
        private String providerName;

        /** 用户是否显式传入 provider 展示名。 */
        private boolean providerNameProvided;

        /** OpenAI 兼容或其他方言基础地址。 */
        private String baseUrl;

        /** 用户是否显式传入 Base URL。 */
        private boolean baseUrlProvided;

        /** API Key，输出时必须脱敏。 */
        private String apiKey;

        /** 默认模型名称。 */
        private String model;

        /** 用户是否显式传入模型名称，避免漏参数时沿用旧默认值误写配置。 */
        private boolean modelProvided;

        /** provider 方言。 */
        private String dialect;

        /** 用户是否显式传入 provider 方言。 */
        private boolean dialectProvided;
    }

    /** 本地认证写入命令解析结果。 */
    private static class AuthSetRequest {
        /** provider 键，写入 providers.<key>。 */
        private String providerKey;

        /** provider 展示名或用户传入的 label。 */
        private String providerName;

        /** 用户是否显式传入 provider 展示名。 */
        private boolean providerNameProvided;

        /** API Key，输出必须脱敏。 */
        private String apiKey;

        /** provider 基础地址。 */
        private String baseUrl;

        /** 用户是否显式传入 Base URL。 */
        private boolean baseUrlProvided;

        /** provider 默认模型。 */
        private String model;

        /** 用户是否显式传入模型。 */
        private boolean modelProvided;

        /** provider 方言。 */
        private String dialect;

        /** 用户是否显式传入方言。 */
        private boolean dialectProvided;

        /** 是否同时切换为当前活动模型 provider。 */
        private boolean activate;
    }

    /** fallback 链路中的单个 provider/model 目标。 */
    private static class FallbackEntry {
        /** provider 键，必须能命中 providers 配置。 */
        private String provider;

        /** fallback 使用的模型；为空时可回退到 provider 默认模型。 */
        private String model;

        /**
         * 创建 fallback 目标。
         *
         * @param provider provider 键。
         * @param model 模型名称。
         */
        private FallbackEntry(String provider, String model) {
            this.provider = provider;
            this.model = model;
            normalize();
        }

        /** 清理字段空白，保持比较和写入稳定。 */
        private void normalize() {
            provider = StrUtil.nullToEmpty(provider).trim();
            model = StrUtil.nullToEmpty(model).trim();
        }

        /**
         * 判断是否指向同一个 fallback 目标。
         *
         * @param other 另一个目标。
         * @return provider 和 model 都相同返回 true。
         */
        private boolean sameTarget(FallbackEntry other) {
            return other != null && provider.equals(other.provider) && model.equals(other.model);
        }

        /**
         * 返回适合终端展示的目标文本。
         *
         * @return provider/model 文本。
         */
        private String display() {
            return provider + " / " + StrUtil.blankToDefault(model, "(provider default)");
        }
    }

    /** 国内渠道配置命令解析结果。 */
    private static class GatewaySetRequest {
        /** 国内渠道标识，只允许已确认保留的渠道。 */
        private String channel;

        /** 待写入 runtime/config.yml 的渠道字段和值，字段已归一到 AppConfig 命名。 */
        private final Map<String, String> values = new LinkedHashMap<String, String>();

        /** 用户传入但当前渠道初始化命令未支持的参数名，用于给出明确反馈。 */
        private String unsupportedField;
    }
}
