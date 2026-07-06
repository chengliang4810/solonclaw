package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.ConfigFlattenSupport;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.support.BasicValueSupport;
import com.jimuqu.solon.claw.support.RuntimeSetupSpec;
import com.jimuqu.solon.claw.tool.runtime.SubprocessEnvironmentSanitizer;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.noear.solon.core.util.Assert;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Dashboard 配置读写与 schema 服务。 */
public class DashboardConfigService {
    /** 根用户前缀列表的统一常量值。 */
    private static final List<String> ROOT_PREFIXES = Arrays.asList("approvals.", "security.");

    /** SOLONCLAWPASSTHROUGH前缀列表的统一常量值。 */
    private static final List<String> SOLONCLAW_PASSTHROUGH_PREFIXES =
            Arrays.asList("solonclaw.channels.wecom.groups.", "solonclaw.terminal.");

    /** WindowsDRIVE路径的统一常量值。 */
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");

    /** 写入LOCK的统一常量值。 */
    private static final Object WRITE_LOCK = new Object();

    /** 注入应用配置，用于控制台配置。 */
    private final AppConfig appConfig;

    /** 注入消息网关工作区配置刷新服务，用于调用对应业务能力。 */
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;

    /** 保存fields映射，便于按键快速查询。 */
    private final Map<String, FieldDefinition> fields =
            new LinkedHashMap<String, FieldDefinition>();

    /** 保存categoryOrder集合，维持调用顺序或去重语义。 */
    private final List<String> categoryOrder =
            Arrays.asList(
                    "general", "agent", "compression", "proactive", "security", "messaging");

    /**
     * 创建控制台配置服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
    public DashboardConfigService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this.appConfig = appConfig;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        registerFields();
    }

    /**
     * 读取配置。
     *
     * @return 返回读取到的配置。
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> response = toNestedFieldMap(resolveCurrentValues());
        response.put("platform_catalog", RuntimeSetupSpec.domesticChannelCatalog());
        return response;
    }

    /**
     * 读取Defaults。
     *
     * @return 返回读取到的Defaults。
     */
    public Map<String, Object> getDefaults() {
        return toNestedFieldMap(resolveDefaultValues());
    }

    /**
     * 读取结构。
     *
     * @return 返回读取到的结构。
     */
    public Map<String, Object> getSchema() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        Map<String, Object> fieldMaps = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, FieldDefinition> entry : fields.entrySet()) {
            fieldMaps.put(entry.getKey(), entry.getValue().toSchemaMap());
        }
        response.put("fields", fieldMaps);
        response.put("category_order", categoryOrder);
        return response;
    }

    /**
     * 读取原始。
     *
     * @return 返回读取到的原始。
     */
    public Map<String, Object> getRaw() {
        return Collections.<String, Object>singletonMap("yaml", dumpYaml(resolveCurrentValues()));
    }

    /**
     * 执行诊断相关逻辑。
     *
     * @return 返回诊断结果。
     */
    public Map<String, Object> diagnostics() {
        return RuntimeConfigResolver.initialize(appConfig.getRuntime().getHome())
                .diagnostics(appConfig);
    }

    /**
     * 保存配置。
     *
     * @param nestedConfig nested配置对象。
     * @return 返回配置。
     */
    public Map<String, Object> saveConfig(Map<String, Object> nestedConfig) {
        Map<String, Object> flat = flattenFieldMap(nestedConfig);
        validateKeys(flat.keySet());
        validateValues(flat);
        writeOverrideFile(flat);
        gatewayRuntimeRefreshService.refreshNow();
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 保存原始。
     *
     * @param yamlText YAML文本参数。
     * @return 返回原始结果。
     */
    public Map<String, Object> saveRaw(String yamlText) {
        Map<String, Object> flat = loadFieldMap(yamlText);
        validateKeys(flat.keySet());
        validateValues(flat);
        writeOverrideFile(flat);
        gatewayRuntimeRefreshService.refreshNow();
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 保存Partial Flat。
     *
     * @param flatUpdates flatUpdates 参数。
     * @return 返回Partial Flat结果。
     */
    public Map<String, Object> savePartialFlat(Map<String, Object> flatUpdates) {
        return savePartialFlat(flatUpdates, true);
    }

    /**
     * 保存Partial Flat。
     *
     * @param flatUpdates flatUpdates 参数。
     * @param reconnectChannels reconnectChannels 参数。
     * @return 返回Partial Flat结果。
     */
    public Map<String, Object> savePartialFlat(
            Map<String, Object> flatUpdates, boolean reconnectChannels) {
        Map<String, Object> normalizedUpdates = normalizeFlatUpdates(flatUpdates);
        validateKeys(normalizedUpdates.keySet());
        Map<String, Object> merged = mergeBaseValues();
        merged.putAll(normalizedUpdates);
        validateValues(merged);
        writeOverrideFile(merged);
        if (reconnectChannels) {
            gatewayRuntimeRefreshService.refreshNow();
        } else {
            gatewayRuntimeRefreshService.refreshConfigOnly();
        }
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 规范化Flat Updates。
     *
     * @param flatUpdates flatUpdates 参数。
     * @return 返回Flat Updates结果。
     */
    private Map<String, Object> normalizeFlatUpdates(Map<String, Object> flatUpdates) {
        Assert.notNull(flatUpdates, "config updates are required");
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : flatUpdates.entrySet()) {
            String canonicalKey = canonicalFieldKey(entry.getKey());
            if (fields.containsKey(canonicalKey)) {
                normalized.put(canonicalKey, entry.getValue());
            } else {
                normalized.put(entry.getKey(), entry.getValue());
            }
        }
        return normalized;
    }

    /** 注册Fields。 */
    private void registerFields() {
        addField(new FieldDefinition("model.providerKey", "string", "general", "当前默认模型 provider 键"));
        addField(new FieldDefinition("providers.default.name", "string", "general", "默认 provider 名称"));
        addField(
                new FieldDefinition(
                        "providers.default.baseUrl", "string", "general", "默认 provider API 地址"));
        addField(
                new FieldDefinition(
                        "providers.default.defaultModel", "string", "general", "默认 provider 模型名"));
        addField(
                new FieldDefinition("providers.default.dialect", "select", "general", "默认 provider 协议")
                        .options("openai", "openai-responses", "ollama", "gemini", "anthropic"));
        addField(new FieldDefinition("llm.stream", "boolean", "general", "是否启用流式输出"));
        addField(
                new FieldDefinition("llm.reasoningEffort", "select", "general", "默认推理强度")
                        .options("minimal", "low", "medium", "high"));
        addField(new FieldDefinition("llm.temperature", "number", "general", "采样温度"));
        addField(new FieldDefinition("llm.maxTokens", "number", "general", "最大输出 token"));
        addField(
                new FieldDefinition("llm.contextWindowTokens", "number", "general", "上下文窗口 token（0=自动识别）"));
        addField(
                new FieldDefinition(
                        "llm.contextFallbackTokens", "number", "general", "自动识别失败兜底 token"));
        addField(
                new FieldDefinition(
                        "llm.modelsDevRefreshEnabled",
                        "boolean",
                        "general",
                        "启用 models.dev 在线目录刷新"));
        addField(new FieldDefinition("llm.promptCache.enabled", "boolean", "general", "启用提示词缓存策略"));
        addField(
                new FieldDefinition("llm.promptCache.ttl", "select", "general", "提示词缓存 TTL")
                        .options("5m", "1h"));
        addField(
                new FieldDefinition("llm.promptCache.layout", "select", "general", "提示词缓存布局")
                        .options("system_and_3"));
        addField(
                new FieldDefinition("display.toolProgress", "select", "general", "默认工具进度模式")
                        .options("off", "new", "all", "verbose"));
        addField(
                new FieldDefinition(
                        "display.showReasoning", "boolean", "general", "默认允许 reasoning 进入聊天窗口"));
        addField(
                new FieldDefinition("display.resumeDisplay", "select", "general", "恢复会话历史展示")
                        .options("full", "minimal"));
        addField(new FieldDefinition("display.toolPreviewLength", "number", "general", "工具参数预览长度"));
        addField(
                new FieldDefinition(
                        "display.progressThrottleMs", "number", "general", "reasoning/进度消息节流毫秒"));
        addField(
                new FieldDefinition(
                        "display.metadataFooter.enabled",
                        "boolean",
                        "general",
                        "最终回复 metadata footer 开关"));
        addField(
                new FieldDefinition(
                        "display.metadataFooter.fields", "list", "general", "metadata footer 字段列表"));
        addField(
                new FieldDefinition(
                        "display.platforms.feishu.metadataFooter.enabled",
                        "boolean",
                        "messaging",
                        "飞书 metadata footer 覆盖开关"));
        addField(
                new FieldDefinition(
                        "display.platforms.dingtalk.metadataFooter.enabled",
                        "boolean",
                        "messaging",
                        "钉钉 metadata footer 覆盖开关"));
        addField(
                new FieldDefinition(
                        "display.platforms.wecom.metadataFooter.enabled",
                        "boolean",
                        "messaging",
                        "企微 metadata footer 覆盖开关"));
        addField(
                new FieldDefinition(
                        "display.platforms.weixin.metadataFooter.enabled",
                        "boolean",
                        "messaging",
                        "微信 metadata footer 覆盖开关"));
        addField(
                new FieldDefinition(
                        "display.platforms.qqbot.metadataFooter.enabled",
                        "boolean",
                        "messaging",
                        "QQBot metadata footer 覆盖开关"));
        addField(
                new FieldDefinition(
                        "display.platforms.yuanbao.metadataFooter.enabled",
                        "boolean",
                        "messaging",
                        "元宝 metadata footer 覆盖开关"));
        addField(new FieldDefinition("scheduler.enabled", "boolean", "general", "启用定时调度"));
        addField(new FieldDefinition("scheduler.tickSeconds", "number", "general", "调度轮询周期（秒）"));
        addField(
                new FieldDefinition(
                        "scheduler.wrapResponse", "boolean", "general", "默认包装定时任务投递回复"));
        addProactiveFields();

        addField(new FieldDefinition("learning.enabled", "boolean", "agent", "启用主回复后的自动学习"));
        addField(
                new FieldDefinition(
                        "learning.toolCallThreshold", "number", "agent", "触发学习所需的最少工具调用数"));
        addField(
                new FieldDefinition(
                        "learning.auxiliaryTimeoutSeconds", "number", "agent", "自动学习辅助模型调用总超时（秒）"));
        addField(
                new FieldDefinition(
                        "skills.curator.enabled", "boolean", "agent", "启用技能后台维护 Curator"));
        addField(
                new FieldDefinition(
                        "skills.curator.intervalHours", "number", "agent", "Curator 巡检周期（小时）"));
        addField(
                new FieldDefinition(
                        "skills.curator.minIdleHours", "number", "agent", "Curator 最小空闲窗口（小时）"));
        addField(
                new FieldDefinition(
                        "skills.curator.staleAfterDays", "number", "agent", "技能多久未使用后标记 stale"));
        addField(
                new FieldDefinition(
                        "skills.curator.archiveAfterDays", "number", "agent", "技能多久未使用后归档"));
        addField(
                new FieldDefinition("task.busyPolicy", "select", "agent", "运行中输入策略")
                        .options("queue", "steer", "interrupt", "reject"));
        addField(
                new FieldDefinition("task.toolOutputInlineLimit", "number", "agent", "工具输出内联字节上限"));
        addField(
                new FieldDefinition(
                        "task.toolOutputTurnBudget", "number", "agent", "单轮工具输出累计预算字节"));
        addField(new FieldDefinition("task.toolOutputMaxLines", "number", "agent", "工具文件读取最大行数"));
        addField(
                new FieldDefinition(
                        "task.toolOutputMaxLineLength", "number", "agent", "工具输出单行最大长度"));
        addField(
                new FieldDefinition(
                        "agent.heartbeat.intervalMinutes",
                        "number",
                        "agent",
                        "heartbeat 轮询间隔（分钟，0 表示关闭）"));
        addField(new FieldDefinition("rollback.enabled", "boolean", "agent", "启用 checkpoint 回滚"));
        addField(
                new FieldDefinition(
                        "rollback.maxCheckpointsPerSource",
                        "number",
                        "agent",
                        "每个来源保留的最大 checkpoint 数"));
        addField(new FieldDefinition("react.maxSteps", "number", "agent", "主代理最大推理步数"));
        addField(new FieldDefinition("react.retryMax", "number", "agent", "主代理决策重试次数"));
        addField(new FieldDefinition("react.retryDelayMs", "number", "agent", "主代理决策重试基础延迟（毫秒）"));
        addField(new FieldDefinition("react.delegateMaxSteps", "number", "agent", "子代理最大推理步数"));
        addField(new FieldDefinition("react.delegateRetryMax", "number", "agent", "子代理决策重试次数"));
        addField(
                new FieldDefinition(
                        "react.delegateRetryDelayMs", "number", "agent", "子代理决策重试基础延迟（毫秒）"));
        addField(
                new FieldDefinition(
                        "react.summarizationEnabled",
                        "boolean",
                        "compression",
                        "启用 ReAct 工作记忆摘要守卫"));
        addField(
                new FieldDefinition(
                        "react.summarizationMaxMessages",
                        "number",
                        "compression",
                        "ReAct 摘要触发消息阈值"));
        addField(
                new FieldDefinition(
                        "react.summarizationMaxTokens",
                        "number",
                        "compression",
                        "ReAct 摘要触发 token 阈值"));
        addField(
                new FieldDefinition(
                        "react.toolLoopWarningsEnabled", "boolean", "security", "启用重复工具调用软提醒"));
        addField(
                new FieldDefinition(
                        "react.toolLoopHardStopEnabled", "boolean", "security", "启用重复工具调用硬停"));
        addField(
                new FieldDefinition(
                        "react.toolLoopExactFailureWarnAfter", "number", "security", "相同参数失败提醒阈值"));
        addField(
                new FieldDefinition(
                        "react.toolLoopExactFailureBlockAfter",
                        "number",
                        "security",
                        "相同参数失败硬停阈值"));
        addField(
                new FieldDefinition(
                        "react.toolLoopSameToolFailureWarnAfter",
                        "number",
                        "security",
                        "同一工具失败提醒阈值"));
        addField(
                new FieldDefinition(
                        "react.toolLoopSameToolFailureHaltAfter",
                        "number",
                        "security",
                        "同一工具失败硬停阈值"));
        addField(
                new FieldDefinition(
                        "react.toolLoopNoProgressWarnAfter", "number", "security", "只读工具无进展提醒阈值"));
        addField(
                new FieldDefinition(
                        "react.toolLoopNoProgressBlockAfter", "number", "security", "只读工具无进展硬停阈值"));
        addField(
                new FieldDefinition(
                        "workspace", "string", "security", "Agent 工作区目录，支持相对运行 Jar 的相对路径"));
        addField(
                new FieldDefinition(
                        "agent.personalities.helpful.description",
                        "string",
                        "agent",
                        "helpful 人格描述"));
        addField(
                new FieldDefinition(
                        "agent.personalities.helpful.systemPrompt",
                        "text",
                        "agent",
                        "helpful 人格系统提示词"));
        addField(
                new FieldDefinition(
                        "agent.personalities.concise.description",
                        "string",
                        "agent",
                        "concise 人格描述"));
        addField(
                new FieldDefinition(
                        "agent.personalities.concise.systemPrompt",
                        "text",
                        "agent",
                        "concise 人格系统提示词"));
        addField(
                new FieldDefinition(
                        "agent.personalities.technical.description",
                        "string",
                        "agent",
                        "technical 人格描述"));
        addField(
                new FieldDefinition(
                        "agent.personalities.technical.systemPrompt",
                        "text",
                        "agent",
                        "technical 人格系统提示词"));
        addField(
                new FieldDefinition(
                        "agent.personalities.technical.tone", "string", "agent", "technical 人格语气"));
        addField(
                new FieldDefinition(
                        "agent.personalities.technical.style",
                        "string",
                        "agent",
                        "technical 人格风格"));

        addField(new FieldDefinition("compression.enabled", "boolean", "compression", "启用上下文压缩"));
        addField(
                new FieldDefinition(
                        "compression.thresholdPercent", "number", "compression", "触发压缩的阈值比例"));
        addField(
                new FieldDefinition(
                        "compression.summaryModel", "string", "compression", "可选压缩/工作记忆摘要模型"));
        addField(
                new FieldDefinition(
                        "compression.protectHeadMessages", "number", "compression", "头部保护消息数"));
        addField(new FieldDefinition("compression.tailRatio", "number", "compression", "尾部保护比例"));

        addField(new FieldDefinition("gateway.allowedUsers", "list", "security", "全局允许用户列表"));
        addField(new FieldDefinition("gateway.allowAllUsers", "boolean", "security", "是否全局允许所有用户"));
        addField(
                new FieldDefinition(
                        "gateway.injectionSecret", "password", "security", "HTTP 网关注入签名密钥"));
        addField(
                new FieldDefinition(
                        "gateway.injectionMaxBodyBytes",
                        "number",
                        "security",
                        "HTTP 网关注入最大请求体字节数"));
        addField(
                new FieldDefinition(
                        "gateway.injectionReplayWindowSeconds",
                        "number",
                        "security",
                        "HTTP 网关注入重放窗口秒数"));
        addPlatformToolsetFields();
        addField(
                new FieldDefinition(
                        "security.allowPrivateUrls", "boolean", "security", "允许 URL 工具访问内网地址"));
        addField(
                new FieldDefinition(
                        "browser.rewriteLoopbackUrls",
                        "boolean",
                        "security",
                        "容器浏览器访问宿主机 loopback 地址改写"));
        addField(
                new FieldDefinition(
                        "browser.loopbackHostAlias", "string", "security", "容器浏览器访问宿主机的主机别名"));
        addField(
                new FieldDefinition(
                        "security.websiteBlocklist.enabled", "boolean", "security", "启用网站阻断策略"));
        addField(
                new FieldDefinition(
                        "security.websiteBlocklist.domains", "list", "security", "网站阻断域名列表"));
        addField(
                new FieldDefinition(
                        "security.websiteBlocklist.sharedFiles", "list", "security", "共享网站阻断列表文件"));
        addField(
                new FieldDefinition(
                        "security.tirithEnabled", "boolean", "security", "启用 Tirith 命令扫描"));
        addField(
                new FieldDefinition("security.tirithPath", "string", "security", "Tirith 可执行文件路径"));
        addField(
                new FieldDefinition(
                        "security.tirithTimeoutSeconds", "number", "security", "Tirith 扫描超时秒数"));
        addField(
                new FieldDefinition(
                        "security.tirithFailOpen", "boolean", "security", "Tirith 不可用时放行"));
        addField(
                new FieldDefinition("security.fileGuardrailMode", "select", "security", "文件安全预检模式")
                        .options("strict", "bypass"));
        addField(
                new FieldDefinition("security.urlGuardrailMode", "select", "security", "URL 安全预检模式")
                        .options("strict", "bypass"));
        addField(
                new FieldDefinition("security.guardrailMode", "select", "security", "危险命令审批模式")
                        .options("approval", "strict", "bypass", "smart"));
        addField(
                new FieldDefinition(
                                "security.guardrailCronMode", "select", "security", "Cron 危险命令策略")
                        .options("approval", "strict", "bypass", "approve"));
        addField(
                new FieldDefinition(
                                "security.guardrailCronScope", "select", "security", "Cron 审批记忆范围")
                        .options("job", "session", "global"));
        addField(new FieldDefinition("approvals.timeoutSeconds", "number", "security", "本地审批超时秒数"));
        addField(
                new FieldDefinition(
                        "approvals.gatewayTimeoutSeconds", "number", "security", "渠道审批超时秒数"));
        addField(
                new FieldDefinition(
                        "approvals.mcpReloadConfirm", "boolean", "security", "MCP reload 需要确认"));
        addField(new FieldDefinition("terminal.credentialFiles", "list", "security", "终端凭据文件挂载清单"));
        addField(
                new FieldDefinition(
                        "terminal.envPassthrough", "list", "security", "终端子进程环境变量放行清单"));
        addField(new FieldDefinition("terminal.sudoPassword", "password", "security", "sudo 密码"));

        addChannelFields("feishu");
        addField(
                new FieldDefinition("channels.feishu.domain", "select", "messaging", "飞书/Lark 租户域")
                        .options("feishu", "lark"));
        addField(
                new FieldDefinition(
                        "channels.feishu.websocketUrl", "string", "messaging", "飞书 websocket 地址"));
        addField(
                new FieldDefinition("channels.feishu.dmPolicy", "select", "messaging", "飞书私聊策略")
                        .options("open", "allowlist", "disabled", "pairing"));
        addField(
                new FieldDefinition("channels.feishu.groupPolicy", "select", "messaging", "飞书群聊策略")
                        .options("open", "allowlist", "disabled"));
        addField(
                new FieldDefinition(
                        "channels.feishu.groupAllowedUsers",
                        "list",
                        "messaging",
                        "飞书群聊 allowlist"));
        addField(
                new FieldDefinition(
                        "channels.feishu.requireMention",
                        "boolean",
                        "messaging",
                        "飞书群聊是否必须提及机器人"));
        addField(
                new FieldDefinition(
                        "channels.feishu.freeResponseChats",
                        "list",
                        "messaging",
                        "飞书免提及响应群聊列表"));
        addField(
                new FieldDefinition(
                        "channels.feishu.botOpenId", "string", "messaging", "飞书 bot Open ID"));
        addField(
                new FieldDefinition(
                        "channels.feishu.botUserId", "string", "messaging", "飞书 bot User ID"));
        addField(
                new FieldDefinition(
                        "channels.feishu.botName", "string", "messaging", "飞书 bot 展示名"));
        addField(
                new FieldDefinition(
                                "channels.feishu.toolProgress", "select", "messaging", "飞书工具进度模式")
                        .options("off", "new", "all", "verbose"));
        addField(
                new FieldDefinition(
                        "channels.feishu.comment.enabled", "boolean", "messaging", "飞书文档评论智能回复开关"));
        addField(
                new FieldDefinition(
                        "channels.feishu.comment.pairingFile", "string", "messaging", "飞书评论配对文件"));

        addChannelFields("dingtalk");
        addField(
                new FieldDefinition(
                        "channels.dingtalk.coolAppCode",
                        "string",
                        "messaging",
                        "可选钉钉 Cool App 编码"));
        addField(
                new FieldDefinition(
                        "channels.dingtalk.streamUrl", "string", "messaging", "钉钉 stream 地址"));
        addField(
                new FieldDefinition(
                                "channels.dingtalk.toolProgress", "select", "messaging", "钉钉工具进度模式")
                        .options("off", "new", "all", "verbose"));
        addField(
                new FieldDefinition(
                        "channels.dingtalk.progressCardTemplateId",
                        "string",
                        "messaging",
                        "钉钉长任务进度卡模板 ID"));
        addField(
                new FieldDefinition("channels.dingtalk.dmPolicy", "select", "messaging", "钉钉私聊策略")
                        .options("open", "allowlist", "disabled", "pairing"));
        addField(
                new FieldDefinition(
                                "channels.dingtalk.groupPolicy", "select", "messaging", "钉钉群聊策略")
                        .options("open", "allowlist", "disabled"));
        addField(
                new FieldDefinition(
                        "channels.dingtalk.groupAllowedUsers",
                        "list",
                        "messaging",
                        "钉钉群聊 allowlist"));
        addField(
                new FieldDefinition(
                        "channels.dingtalk.requireMention",
                        "boolean",
                        "messaging",
                        "钉钉群聊是否必须提及机器人"));
        addField(
                new FieldDefinition(
                        "channels.dingtalk.freeResponseChats",
                        "list",
                        "messaging",
                        "钉钉免提及响应群聊列表"));
        addField(
                new FieldDefinition(
                        "channels.dingtalk.aiCardStreaming.enabled",
                        "boolean",
                        "messaging",
                        "钉钉 AI Card 增量更新开关"));

        addChannelFields("wecom");
        addField(
                new FieldDefinition(
                        "channels.wecom.websocketUrl", "string", "messaging", "企微 websocket 地址"));
        addField(
                new FieldDefinition(
                                "channels.wecom.toolProgress", "select", "messaging", "企微工具进度模式")
                        .options("off", "new", "all", "verbose"));
        addField(
                new FieldDefinition("channels.wecom.dmPolicy", "select", "messaging", "企微私聊策略")
                        .options("open", "allowlist", "disabled", "pairing"));
        addField(
                new FieldDefinition("channels.wecom.groupPolicy", "select", "messaging", "企微群聊策略")
                        .options("open", "allowlist", "disabled"));
        addField(
                new FieldDefinition(
                        "channels.wecom.groupAllowedUsers", "list", "messaging", "企微群聊 allowlist"));

        addChannelFields("weixin");
        addField(
                new FieldDefinition(
                        "channels.weixin.accountId", "string", "messaging", "微信 iLink accountId"));
        addField(
                new FieldDefinition(
                        "channels.weixin.baseUrl", "string", "messaging", "微信 iLink API 地址"));
        addField(
                new FieldDefinition(
                        "channels.weixin.cdnBaseUrl", "string", "messaging", "微信 CDN 地址"));
        addField(
                new FieldDefinition(
                        "channels.weixin.longPollUrl", "string", "messaging", "微信 long-poll 地址"));
        addField(
                new FieldDefinition("channels.weixin.dmPolicy", "select", "messaging", "微信私聊策略")
                        .options("open", "allowlist", "disabled", "pairing"));
        addField(
                new FieldDefinition("channels.weixin.groupPolicy", "select", "messaging", "微信群聊策略")
                        .options("open", "allowlist", "disabled"));
        addField(
                new FieldDefinition(
                        "channels.weixin.groupAllowedUsers",
                        "list",
                        "messaging",
                        "微信群聊 allowlist"));
        addField(
                new FieldDefinition(
                        "channels.weixin.splitMultilineMessages",
                        "boolean",
                        "messaging",
                        "微信多行消息拆分"));
        addField(
                new FieldDefinition(
                        "channels.weixin.textBatchDelaySeconds",
                        "number",
                        "messaging",
                        "微信入站文本批量延迟（秒）"));
        addField(
                new FieldDefinition(
                        "channels.weixin.textBatchSplitDelaySeconds",
                        "number",
                        "messaging",
                        "微信长文本分片批量延迟（秒）"));
        addField(
                new FieldDefinition(
                                "channels.weixin.toolProgress", "select", "messaging", "微信工具进度模式")
                        .options("off", "new", "all", "verbose"));
        addField(
                new FieldDefinition(
                        "channels.weixin.sendChunkDelaySeconds",
                        "number",
                        "messaging",
                        "微信分片发送间隔（秒）"));
        addField(
                new FieldDefinition(
                        "channels.weixin.sendChunkRetries", "number", "messaging", "微信分片重试次数"));
        addField(
                new FieldDefinition(
                        "channels.weixin.sendChunkRetryDelaySeconds",
                        "number",
                        "messaging",
                        "微信分片重试间隔（秒）"));

        addChannelFields("qqbot");
        addField(
                new FieldDefinition(
                        "channels.qqbot.apiDomain", "string", "messaging", "QQBot API 域名"));
        addField(
                new FieldDefinition(
                        "channels.qqbot.websocketUrl",
                        "string",
                        "messaging",
                        "QQBot websocket 地址"));
        addField(
                new FieldDefinition(
                        "channels.qqbot.markdownSupport",
                        "boolean",
                        "messaging",
                        "QQBot Markdown 消息开关"));
        addField(
                new FieldDefinition(
                                "channels.qqbot.toolProgress",
                                "select",
                                "messaging",
                                "QQBot 工具进度模式")
                        .options("off", "new", "all", "verbose"));
        addField(
                new FieldDefinition("channels.qqbot.dmPolicy", "select", "messaging", "QQBot 私聊策略")
                        .options("open", "allowlist", "disabled", "pairing"));
        addField(
                new FieldDefinition(
                                "channels.qqbot.groupPolicy", "select", "messaging", "QQBot 群聊策略")
                        .options("open", "allowlist", "disabled"));
        addField(
                new FieldDefinition(
                        "channels.qqbot.groupAllowedUsers",
                        "list",
                        "messaging",
                        "QQBot 群聊 allowlist"));

        addChannelFields("yuanbao");
        addField(
                new FieldDefinition(
                        "channels.yuanbao.apiDomain", "string", "messaging", "腾讯元宝 API 域名"));
        addField(
                new FieldDefinition(
                        "channels.yuanbao.websocketUrl",
                        "string",
                        "messaging",
                        "腾讯元宝 websocket 地址"));
        addField(
                new FieldDefinition(
                                "channels.yuanbao.toolProgress",
                                "select",
                                "messaging",
                                "腾讯元宝工具进度模式")
                        .options("off", "new", "all", "verbose"));
        addField(
                new FieldDefinition("channels.yuanbao.dmPolicy", "select", "messaging", "腾讯元宝私聊策略")
                        .options("open", "allowlist", "disabled", "pairing"));
        addField(
                new FieldDefinition(
                                "channels.yuanbao.groupPolicy", "select", "messaging", "腾讯元宝群聊策略")
                        .options("open", "allowlist", "disabled"));
        addField(
                new FieldDefinition(
                        "channels.yuanbao.groupAllowedUsers",
                        "list",
                        "messaging",
                        "腾讯元宝群聊 allowlist"));
        addField(
                new FieldDefinition(
                        "gateway.processingReactionsEnabled",
                        "boolean",
                        "messaging",
                        "启用处理状态表情回应"));
    }

    /**
     * 追加渠道Fields。
     *
     * @param name 名称参数。
     */
    private void addChannelFields(String name) {
        FieldDefinition enabledField =
                new FieldDefinition(
                        "channels." + name + ".enabled",
                        "boolean",
                        "messaging",
                        channelLabel(name) + "渠道开关");
        addField(enabledField);
        addField(
                new FieldDefinition(
                        "channels." + name + ".allowedUsers",
                        "list",
                        "messaging",
                        channelLabel(name) + "允许用户列表"));
        addField(
                new FieldDefinition(
                        "channels." + name + ".allowAllUsers",
                        "boolean",
                        "messaging",
                        channelLabel(name) + "是否允许所有用户"));
        addField(
                new FieldDefinition(
                                "channels." + name + ".unauthorizedDmBehavior",
                                "select",
                                "messaging",
                                channelLabel(name) + "未授权私聊行为")
                        .options("pair", "ignore"));
    }

    /** 追加平台工具集Fields。 */
    private void addPlatformToolsetFields() {
        for (String name :
                Arrays.asList("feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao")) {
            addField(
                    new FieldDefinition(
                            "gateway.platforms." + name.toUpperCase() + ".enabledToolsets",
                            "list",
                            "security",
                            channelLabel(name) + "允许工具集"));
            addField(
                    new FieldDefinition(
                            "gateway.platforms." + name.toUpperCase() + ".disabledToolsets",
                            "list",
                            "security",
                            channelLabel(name) + "禁用工具集"));
            addField(
                    new FieldDefinition(
                            "gateway.platforms." + name.toUpperCase() + ".approvalRequired",
                            "boolean",
                            "security",
                            channelLabel(name) + "强制审批"));
        }
    }

    /** 追加主动协作安全配置字段，仅暴露频率、门控和展示前缀，不开放提示词编辑。 */
    private void addProactiveFields() {
        addField(new FieldDefinition("proactive.enabled", "boolean", "proactive", "启用主动协作"));
        addField(
                new FieldDefinition(
                        "proactive.intervalMinutes", "number", "proactive", "主动协作检查间隔（分钟）"));
        addField(
                new FieldDefinition(
                        "proactive.initialDelaySeconds", "number", "proactive", "启动后首次检查延迟（秒）"));
        addField(
                new FieldDefinition(
                        "proactive.dailyMaxContacts", "number", "proactive", "每日最多主动联系次数"));
        addField(
                new FieldDefinition(
                        "proactive.cooldownMinutes", "number", "proactive", "两次主动联系之间的冷却分钟数"));
        addField(
                new FieldDefinition(
                        "proactive.quietStartHour", "number", "proactive", "免打扰开始小时"));
        addField(
                new FieldDefinition(
                        "proactive.quietEndHour", "number", "proactive", "免打扰结束小时"));
        addField(
                new FieldDefinition(
                        "proactive.minConfidenceToContact",
                        "number",
                        "proactive",
                        "允许主动联系的最低置信度"));
        addField(
                new FieldDefinition(
                        "proactive.llmDecisionEnabled",
                        "boolean",
                        "proactive",
                        "启用大模型发送判断"));
        addField(
                new FieldDefinition(
                        "proactive.llmPolishEnabled", "boolean", "proactive", "启用大模型文案润色"));
        addField(
                new FieldDefinition(
                        "proactive.maxCandidatesPerTick",
                        "number",
                        "proactive",
                        "每次检查最多生成候选数"));
        addField(
                new FieldDefinition(
                        "proactive.maxContactsPerTick",
                        "number",
                        "proactive",
                        "每次检查最多主动联系次数"));
        addField(
                new FieldDefinition(
                        "proactive.repositoryCheckEnabled",
                        "boolean",
                        "proactive",
                        "启用相关仓库更新观察"));
        addField(
                new FieldDefinition(
                        "proactive.repositoryCheckIntervalMinutes",
                        "number",
                        "proactive",
                        "同一仓库更新观察间隔（分钟）"));
        addField(
                new FieldDefinition(
                        "proactive.careCheckinEnabled",
                        "boolean",
                        "proactive",
                        "启用低频工作关心问候"));
        addField(
                new FieldDefinition(
                        "proactive.careCheckinAfterIdleHours",
                        "number",
                        "proactive",
                        "低频关心问候所需空闲小时数"));
        addField(
                new FieldDefinition(
                        "proactive.deliveryPreviewPrefix",
                        "string",
                        "proactive",
                        "主动协作消息前缀"));
    }

    /**
     * 执行渠道Label相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回渠道Label结果。
     */
    private String channelLabel(String name) {
        if ("feishu".equals(name)) {
            return "飞书";
        }
        if ("dingtalk".equals(name)) {
            return "钉钉";
        }
        if ("wecom".equals(name)) {
            return "企微";
        }
        if ("weixin".equals(name)) {
            return "微信";
        }
        if ("qqbot".equals(name)) {
            return "QQBot";
        }
        if ("yuanbao".equals(name)) {
            return "腾讯元宝";
        }
        return name;
    }

    /**
     * 追加Field。
     *
     * @param definition definition 参数。
     */
    private void addField(FieldDefinition definition) {
        fields.put(definition.key, definition);
    }

    /**
     * 解析当前Values。
     *
     * @return 返回解析后的当前Values。
     */
    private Map<String, Object> resolveCurrentValues() {
        Map<String, Object> defaults = resolveDefaultValues();
        Map<String, Object> overrides = loadOverrideFields();
        Map<String, Object> current = new LinkedHashMap<String, Object>();
        for (FieldDefinition field : fields.values()) {
            Object value =
                    overrides.containsKey(field.key)
                            ? overrides.get(field.key)
                            : defaults.get(field.key);
            current.put(field.key, value);
        }
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            if (isSupportedPassthroughKey(entry.getKey())) {
                current.put(entry.getKey(), entry.getValue());
            }
        }
        return current;
    }

    /**
     * 解析默认Values。
     *
     * @return 返回解析后的默认Values。
     */
    private Map<String, Object> resolveDefaultValues() {
        Map<String, Object> raw = loadFieldMap(loadClasspathAppYaml());
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        for (FieldDefinition field : fields.values()) {
            defaults.put(field.key, raw.get(field.key));
        }
        return defaults;
    }

    /**
     * 加载Classpath App YAML。
     *
     * @return 返回Classpath App YAML结果。
     */
    private String loadClasspathAppYaml() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("app.yml");
        if (stream == null) {
            return "";
        }
        return IoUtil.read(stream, StandardCharsets.UTF_8);
    }

    /**
     * 加载Override Fields。
     *
     * @return 返回Override Fields结果。
     */
    private Map<String, Object> loadOverrideFields() {
        File configFile = new File(appConfig.getRuntime().getConfigFile());
        if (!configFile.exists()) {
            return Collections.emptyMap();
        }
        return loadFieldMap(FileUtil.readUtf8String(configFile));
    }

    /**
     * 加载Field Map。
     *
     * @param yamlText YAML文本参数。
     * @return 返回Field Map结果。
     */
    private Map<String, Object> loadFieldMap(String yamlText) {
        if (StrUtil.isBlank(yamlText)) {
            return Collections.emptyMap();
        }

        Object parsed = new Yaml().load(yamlText);
        if (!(parsed instanceof Map)) {
            return Collections.emptyMap();
        }

        Map<String, Object> flattened = new LinkedHashMap<String, Object>();
        ConfigFlattenSupport.flatten("", (Map<?, ?>) parsed, flattened);

        Map<String, Object> fieldValues = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            String key = canonicalFieldKey(entry.getKey());
            if (key.startsWith("solonclaw.")) {
                key = key.substring("solonclaw.".length());
            }
            String canonicalKey = canonicalFieldKey(key);
            if (fields.containsKey(canonicalKey)) {
                fieldValues.put(canonicalKey, entry.getValue());
            } else if (isSupportedPassthroughKey(entry.getKey())) {
                fieldValues.put(entry.getKey(), entry.getValue());
            }
        }
        return fieldValues;
    }

    /**
     * 执行flattenField映射相关逻辑。
     *
     * @param nested nested 参数。
     * @return 返回flatten Field Map结果。
     */
    private Map<String, Object> flattenFieldMap(Map<String, Object> nested) {
        Assert.notNull(nested, "config body is required");
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        flattenNested("", nested, output);

        Map<String, Object> filtered = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            String canonicalKey = canonicalFieldKey(entry.getKey());
            if (fields.containsKey(canonicalKey)) {
                filtered.put(canonicalKey, entry.getValue());
            } else if (isSupportedPassthroughKey(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /**
     * 执行flattenNested相关逻辑。
     *
     * @param prefix prefix 参数。
     * @param input 输入参数。
     * @param output 命令执行输出文本。
     */
    @SuppressWarnings("unchecked")
    private void flattenNested(
            String prefix, Map<String, Object> input, Map<String, Object> output) {
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = prefix.length() == 0 ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flattenNested(key, (Map<String, Object>) value, output);
            } else {
                output.put(key, value);
            }
        }
    }

    /**
     * 转换为Nested Field Map。
     *
     * @param flat flat 参数。
     * @return 返回转换后的Nested Field Map。
     */
    private Map<String, Object> toNestedFieldMap(Map<String, Object> flat) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            setNestedValue(root, entry.getKey(), entry.getValue());
        }
        return root;
    }

    /**
     * 写入Nested Value。
     *
     * @param root root 参数。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> root, String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object current = cursor.get(parts[i]);
            if (!(current instanceof Map)) {
                current = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], current);
            }
            cursor = (Map<String, Object>) current;
        }
        cursor.put(parts[parts.length - 1], value);
    }

    /**
     * 校验Keys。
     *
     * @param keys 候选键列表。
     */
    private void validateKeys(Iterable<String> keys) {
        for (String key : keys) {
            String canonicalKey = canonicalFieldKey(key);
            if (!fields.containsKey(canonicalKey) && !isSupportedPassthroughKey(key)) {
                throw new IllegalStateException("Unsupported config key: " + key);
            }
        }
    }

    /**
     * 校验Values。
     *
     * @param values 待规范化或校验的原始值集合。
     */
    private void validateValues(Map<String, Object> values) {
        validateCredentialFiles(values.get("terminal.credentialFiles"));
        validateEnvPassthrough(
                values.get("terminal.envPassthrough"), "solonclaw.terminal.envPassthrough");
        validateWebsiteSharedFiles(values.get("security.websiteBlocklist.sharedFiles"));
    }

    /**
     * 校验Env Passthrough。
     *
     * @param rawValue 原始值参数。
     * @param configKey 配置键标识或键值。
     */
    private void validateEnvPassthrough(Object rawValue, String configKey) {
        SubprocessEnvironmentSanitizer.validateConfiguredEnvPassthrough(
                normalizeStringList(rawValue, configKey), configKey);
    }

    /**
     * 校验凭据Files。
     *
     * @param rawValue 原始值参数。
     */
    private void validateCredentialFiles(Object rawValue) {
        for (String path : normalizePathList(rawValue)) {
            if (StrUtil.isBlank(path)) {
                continue;
            }
            String value = path.trim();
            if (BasicValueSupport.containsControlCharacter(value)) {
                throw new IllegalStateException(
                        "solonclaw.terminal.credentialFiles contains an invalid control character");
            }
            if (startsWithHomePath(value)) {
                throw new IllegalStateException(
                        "solonclaw.terminal.credentialFiles must use workspace-relative paths");
            }
            if (isAbsolutePathText(value)) {
                throw new IllegalStateException(
                        "solonclaw.terminal.credentialFiles must use workspace-relative paths");
            }
            if (containsTraversal(value)) {
                throw new IllegalStateException(
                        "solonclaw.terminal.credentialFiles must not contain path traversal");
            }
        }
    }

    /**
     * 校验Website Shared Files。
     *
     * @param rawValue 原始值参数。
     */
    private void validateWebsiteSharedFiles(Object rawValue) {
        for (String path : normalizePathList(rawValue)) {
            if (StrUtil.isBlank(path)) {
                continue;
            }
            String value = path.trim();
            if (BasicValueSupport.containsControlCharacter(value)) {
                throw new IllegalStateException(
                        "security.websiteBlocklist.sharedFiles contains an invalid control character");
            }
            if (containsTraversal(value)) {
                throw new IllegalStateException(
                        "security.websiteBlocklist.sharedFiles must not contain path traversal");
            }
            if (value.startsWith("~") && !startsWithHomePath(value)) {
                throw new IllegalStateException(
                        "security.websiteBlocklist.sharedFiles only supports ~/ home paths");
            }
        }
    }

    /**
     * 规范化路径List。
     *
     * @param rawValue 原始值参数。
     * @return 返回路径List结果。
     */
    private List<String> normalizePathList(Object rawValue) {
        return normalizeStringList(rawValue, "Path list config");
    }

    /**
     * 规范化String List。
     *
     * @param rawValue 原始值参数。
     * @param configKey 配置键标识或键值。
     * @return 返回String List结果。
     */
    @SuppressWarnings("unchecked")
    private List<String> normalizeStringList(Object rawValue, String configKey) {
        if (rawValue == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        if (rawValue instanceof List) {
            for (Object item : (List<Object>) rawValue) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        if (rawValue instanceof String) {
            String text = (String) rawValue;
            if (StrUtil.isBlank(text)) {
                return Collections.emptyList();
            }
            for (String item : text.split(",")) {
                values.add(item);
            }
            return values;
        }
        throw new IllegalStateException(configKey + " must be a list or comma-separated string");
    }

    /**
     * 判断是否以主渠道路径开头。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回starts With主渠道路径。
     */
    private boolean startsWithHomePath(String value) {
        return value.startsWith("~/") || value.startsWith("~\\");
    }

    /**
     * 判断是否Absolute路径Text。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Absolute路径Text满足条件则返回 true，否则返回 false。
     */
    private boolean isAbsolutePathText(String value) {
        String path = StrUtil.nullToEmpty(value).trim();
        return new File(path).isAbsolute()
                || path.startsWith("/")
                || path.startsWith("\\")
                || WINDOWS_DRIVE_PATH.matcher(path).matches();
    }

    /**
     * 判断是否包含Traversal。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Traversal结果。
     */
    private boolean containsTraversal(String value) {
        String normalized = StrUtil.nullToEmpty(value).replace('\\', '/');
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
    }

    /**
     * 合并Base Values。
     *
     * @return 返回Base Values结果。
     */
    private Map<String, Object> mergeBaseValues() {
        return new LinkedHashMap<String, Object>(loadOverrideFields());
    }

    /**
     * 判断是否Supported Passthrough键。
     *
     * @param key 配置键或映射键。
     * @return 如果Supported Passthrough键满足条件则返回 true，否则返回 false。
     */
    private boolean isSupportedPassthroughKey(String key) {
        if (key == null) {
            return false;
        }
        for (String prefix : ROOT_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : SOLONCLAW_PASSTHROUGH_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否根用户Passthrough键。
     *
     * @param key 配置键或映射键。
     * @return 如果根用户Passthrough键满足条件则返回 true，否则返回 false。
     */
    private boolean isRootPassthroughKey(String key) {
        if (key == null) {
            return false;
        }
        for (String prefix : ROOT_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行规范Field键相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回规范Field键结果。
     */
    private String canonicalFieldKey(String key) {
        if (key == null) {
            return "";
        }
        if (key.startsWith("solonclaw.")) {
            return key.substring("solonclaw.".length());
        }
        return key;
    }

    /**
     * 写入Override文件。
     *
     * @param fieldValues field值s参数。
     */
    private void writeOverrideFile(Map<String, Object> fieldValues) {
        synchronized (WRITE_LOCK) {
            Map<String, Object> root = loadRawConfigRoot();
            Map<String, Object> solonclaw = ensureSolonClawRoot(root);
            clearManagedFields(solonclaw);
            clearRootPassthroughFields(root);
            for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
                String key = entry.getKey();
                if (isRootPassthroughKey(key)) {
                    setNestedValue(root, entry.getKey(), entry.getValue());
                } else if (key.startsWith("solonclaw.")) {
                    setNestedValue(root, key, entry.getValue());
                } else {
                    setNestedValue(solonclaw, canonicalFieldKey(key), entry.getValue());
                }
            }

            File configFile = new File(appConfig.getRuntime().getConfigFile());
            FileUtil.mkParentDirs(configFile);
            File temp = new File(configFile.getParentFile(), configFile.getName() + ".tmp");
            FileUtil.writeUtf8String(dump(root), temp);
            try {
                try {
                    Files.move(
                            temp.toPath(),
                            configFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomicFailed) {
                    Files.move(
                            temp.toPath(),
                            configFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to write config file", e);
            }
        }
    }

    /**
     * 执行dumpYAML相关逻辑。
     *
     * @param fieldValues field值s参数。
     * @return 返回dump YAML结果。
     */
    private String dumpYaml(Map<String, Object> fieldValues) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        Map<String, Object> solonclaw = new LinkedHashMap<String, Object>();
        root.put("solonclaw", solonclaw);
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            setNestedValue(solonclaw, entry.getKey(), entry.getValue());
        }
        return dump(root);
    }

    /**
     * 执行dump相关逻辑。
     *
     * @param root root 参数。
     * @return 返回dump结果。
     */
    private String dump(Map<String, Object> root) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(1);
        return new Yaml(options).dump(root);
    }

    /**
     * 加载原始配置根用户。
     *
     * @return 返回原始配置根用户结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRawConfigRoot() {
        File configFile = new File(appConfig.getRuntime().getConfigFile());
        if (!configFile.exists()) {
            return new LinkedHashMap<String, Object>();
        }
        Object parsed = new Yaml().load(FileUtil.readUtf8String(configFile));
        if (!(parsed instanceof Map)) {
            return new LinkedHashMap<String, Object>();
        }
        return BasicValueSupport.sanitizeMap((Map<?, ?>) parsed);
    }

    /**
     * 确保Solon项目根用户。
     *
     * @param root root 参数。
     * @return 返回Solon项目根用户结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureSolonClawRoot(Map<String, Object> root) {
        Object current = root.get("solonclaw");
        if (current instanceof Map) {
            return (Map<String, Object>) current;
        }
        Map<String, Object> solonclaw = new LinkedHashMap<String, Object>();
        root.put("solonclaw", solonclaw);
        return solonclaw;
    }

    /**
     * 清理Managed Fields，服务于控制台配置主流程。
     *
     * @param jimuqu jimuqu 参数。
     */
    private void clearManagedFields(Map<String, Object> jimuqu) {
        for (String key : fields.keySet()) {
            removeNestedValue(jimuqu, key);
        }
        for (String prefix : SOLONCLAW_PASSTHROUGH_PREFIXES) {
            removeNestedPrefix(jimuqu, prefix.substring("solonclaw.".length()));
        }
    }

    /**
     * 清理根用户Passthrough Fields。
     *
     * @param root root 参数。
     */
    private void clearRootPassthroughFields(Map<String, Object> root) {
        for (String prefix : ROOT_PREFIXES) {
            removeNestedPrefix(root, prefix);
        }
    }

    /**
     * 解析Typed Value。
     *
     * @param type 类型参数。
     * @param raw 原始输入值。
     * @return 返回解析后的Typed Value。
     */
    private Object parseTypedValue(String type, String raw) {
        if ("boolean".equals(type)) {
            return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
        }
        if ("number".equals(type)) {
            try {
                return raw.contains(".") ? Double.valueOf(raw) : Integer.valueOf(raw);
            } catch (Exception e) {
                return 0;
            }
        }
        if ("list".equals(type)) {
            List<String> values = new ArrayList<String>();
            for (String item : raw.split(",")) {
                if (StrUtil.isNotBlank(item)) {
                    values.add(item.trim());
                }
            }
            return values;
        }
        return raw;
    }

    /**
     * 移除Nested Value。
     *
     * @param root root 参数。
     * @param key 配置键或映射键。
     * @return 返回Nested Value结果。
     */
    @SuppressWarnings("unchecked")
    private boolean removeNestedValue(Map<String, Object> root, String key) {
        String[] parts = key.split("\\.");
        List<Map<String, Object>> parents = new ArrayList<Map<String, Object>>();
        List<String> keys = new ArrayList<String>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object current = cursor.get(parts[i]);
            if (!(current instanceof Map)) {
                return false;
            }
            parents.add(cursor);
            keys.add(parts[i]);
            cursor = (Map<String, Object>) current;
        }
        Object removed = cursor.remove(parts[parts.length - 1]);
        if (removed == null) {
            return false;
        }
        for (int i = parents.size() - 1; i >= 0; i--) {
            Object current = parents.get(i).get(keys.get(i));
            if (current instanceof Map && ((Map<?, ?>) current).isEmpty()) {
                parents.get(i).remove(keys.get(i));
            } else {
                break;
            }
        }
        return true;
    }

    /**
     * 移除Nested Prefix。
     *
     * @param root root 参数。
     * @param prefix prefix 参数。
     */
    @SuppressWarnings("unchecked")
    private void removeNestedPrefix(Map<String, Object> root, String prefix) {
        String normalized = prefix;
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.length() == 0) {
            return;
        }
        String[] parts = normalized.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object current = cursor.get(parts[i]);
            if (!(current instanceof Map)) {
                return;
            }
            cursor = (Map<String, Object>) current;
        }
        cursor.remove(parts[parts.length - 1]);
    }

    /** 承载FieldDefinition相关状态和辅助逻辑。 */
    private static class FieldDefinition {
        /** 记录FieldDefinition中的键。 */
        private final String key;

        /** 记录FieldDefinition中的类型。 */
        private final String type;

        /** 记录FieldDefinition中的category。 */
        private final String category;

        /** 记录FieldDefinition中的描述。 */
        private final String description;

        /** 保存options集合，维持调用顺序或去重语义。 */
        private List<String> options = Collections.emptyList();

        /**
         * 创建Field Definition实例，并注入运行所需依赖。
         *
         * @param key 配置键或映射键。
         * @param type 类型参数。
         * @param category 分类参数。
         * @param description 描述参数。
         */
        private FieldDefinition(String key, String type, String category, String description) {
            this.key = key;
            this.type = type;
            this.category = category;
            this.description = description;
        }

        /**
         * 执行options相关逻辑。
         *
         * @param values 待规范化或校验的原始值集合。
         * @return 返回options结果。
         */
        private FieldDefinition options(String... values) {
            this.options = Arrays.asList(values);
            return this;
        }

        /**
         * 转换为结构Map。
         *
         * @return 返回转换后的结构Map。
         */
        private Map<String, Object> toSchemaMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("type", type);
            result.put("category", category);
            result.put("description", description);
            if (!options.isEmpty()) {
                result.put("options", options);
            }
            return result;
        }
    }
}
