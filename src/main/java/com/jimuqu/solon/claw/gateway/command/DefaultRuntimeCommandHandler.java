package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.plugin.AgentPluginManager;
import com.jimuqu.solon.claw.plugin.AgentPluginManifest;
import com.jimuqu.solon.claw.plugin.PluginLoadDiagnostic;
import com.jimuqu.solon.claw.plugin.PluginLoadStatus;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 处理插件、人格式切换和主动协作等运行时控制面命令。 */
final class DefaultRuntimeCommandHandler {
    /** 应用配置，用于读取人格和主动协作运行配置。 */
    private final AppConfig appConfig;

    /** 全局设置仓储，用于保存人格和主动协作设置覆盖。 */
    private final GlobalSettingRepository globalSettingRepository;

    /** 插件管理器，用于读取插件加载状态和诊断信息。 */
    private final AgentPluginManager pluginManager;

    /** 主动协作诊断服务，用于解释最近一次主动协作决策。 */
    private final ProactiveDiagnosticsService proactiveDiagnosticsService;

    /** 主动协作仓储，用于忽略指定候选。 */
    private final ProactiveRepository proactiveRepository;

    /**
     * 创建运行时控制面命令处理器。
     *
     * @param appConfig 应用配置。
     * @param globalSettingRepository 全局设置仓储。
     * @param pluginManager 插件管理器。
     * @param proactiveDiagnosticsService 主动协作诊断服务。
     * @param proactiveRepository 主动协作仓储。
     */
    DefaultRuntimeCommandHandler(
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            AgentPluginManager pluginManager,
            ProactiveDiagnosticsService proactiveDiagnosticsService,
            ProactiveRepository proactiveRepository) {
        this.appConfig = appConfig;
        this.globalSettingRepository = globalSettingRepository;
        this.pluginManager = pluginManager;
        this.proactiveDiagnosticsService = proactiveDiagnosticsService;
        this.proactiveRepository = proactiveRepository;
    }

    /**
     * 执行Plugins相关逻辑。
     *
     * @return 返回Plugins结果。
     */
    GatewayReply handlePlugins() {
        List<AgentPluginManifest> plugins =
                pluginManager == null
                        ? Collections.<AgentPluginManifest>emptyList()
                        : pluginManager.listPlugins();
        List<PluginLoadDiagnostic> diagnostics =
                pluginManager == null
                        ? Collections.<PluginLoadDiagnostic>emptyList()
                        : pluginManager.diagnostics();
        int loaded = 0;
        int skipped = 0;
        int failed = 0;
        for (PluginLoadDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null || diagnostic.getStatus() == null) {
                continue;
            }
            if (PluginLoadStatus.LOADED == diagnostic.getStatus()) {
                loaded++;
            } else if (PluginLoadStatus.SKIPPED == diagnostic.getStatus()) {
                skipped++;
            } else if (PluginLoadStatus.FAILED == diagnostic.getStatus()) {
                failed++;
            }
        }
        if (loaded == 0 && !plugins.isEmpty()) {
            loaded = plugins.size();
        }

        StringBuilder buffer = new StringBuilder();
        buffer.append("插件状态 loaded=")
                .append(loaded)
                .append(" skipped=")
                .append(skipped)
                .append(" failed=")
                .append(failed);
        if (plugins.isEmpty() && diagnostics.isEmpty()) {
            buffer.append('\n').append("未发现已加载插件。");
        }
        for (AgentPluginManifest manifest : plugins) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(manifest.getName(), "-"))
                    .append(" loaded");
            if (StrUtil.isNotBlank(manifest.getKind())) {
                buffer.append(" kind=").append(manifest.getKind());
            }
            if (StrUtil.isNotBlank(manifest.getVersion())) {
                buffer.append(" version=").append(manifest.getVersion());
            }
            if (StrUtil.isNotBlank(manifest.getDescription())) {
                buffer.append(" - ").append(manifest.getDescription());
            }
        }
        for (PluginLoadDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null || PluginLoadStatus.LOADED == diagnostic.getStatus()) {
                continue;
            }
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(diagnostic.getPluginName(), "-"))
                    .append(' ')
                    .append(String.valueOf(diagnostic.getStatus()).toLowerCase())
                    .append(" reason=")
                    .append(StrUtil.blankToDefault(diagnostic.getReason(), "-"));
        }

        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_PLUGINS);
        reply.getRuntimeMetadata().put("plugin_loaded", Integer.valueOf(loaded));
        reply.getRuntimeMetadata().put("plugin_skipped", Integer.valueOf(skipped));
        reply.getRuntimeMetadata().put("plugin_failed", Integer.valueOf(failed));
        return reply;
    }

    /** 执行人格命令相关逻辑。 */
    GatewayReply handlePersonality(String args) throws Exception {
        Map<String, AppConfig.PersonalityConfig> personalities =
                appConfig.getAgent().getPersonalities();
        if (personalities == null || personalities.isEmpty()) {
            return GatewayReply.error("当前没有可用的人格配置。");
        }
        if (StrUtil.isBlank(args)) {
            StringBuilder buffer = new StringBuilder();
            buffer.append("可用人格：\n");
            buffer.append("- none: 清除人格覆盖\n");
            for (Map.Entry<String, AppConfig.PersonalityConfig> entry : personalities.entrySet()) {
                String description =
                        entry.getValue() == null
                                ? ""
                                : StrUtil.blankToDefault(entry.getValue().getDescription(), "无描述");
                buffer.append("- ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(description)
                        .append('\n');
            }
            buffer.append("当前激活：").append(currentPersonalityName());
            return GatewayReply.ok(buffer.toString().trim());
        }

        if ("none".equalsIgnoreCase(args)
                || "default".equalsIgnoreCase(args)
                || "neutral".equalsIgnoreCase(args)) {
            globalSettingRepository.remove(AgentSettingConstants.ACTIVE_PERSONALITY);
            return GatewayReply.ok("已清除人格覆盖，下一条消息恢复默认行为。");
        }

        String matchedName = null;
        for (String name : personalities.keySet()) {
            if (name.equalsIgnoreCase(args)) {
                matchedName = name;
                break;
            }
        }
        if (matchedName == null) {
            return GatewayReply.error("未知人格：" + args);
        }
        globalSettingRepository.set(AgentSettingConstants.ACTIVE_PERSONALITY, matchedName);
        return GatewayReply.ok("已切换人格为：" + matchedName + "，将从下一条消息开始生效。");
    }

    /**
     * 执行主动协作命令相关逻辑；命令只改变控制面设置或候选状态，不触发调度、投递或工具执行。
     *
     * @param args 工具或命令参数。
     * @return 返回主动协作命令结果。
     */
    GatewayReply handleProactive(String args) throws Exception {
        SlashCommandLine.ActionTail parsed = SlashCommandLine.parseActionTail(args, "status");
        String action = parsed.getAction();
        String tail = parsed.getTail();
        GatewayReply reply;
        if ("status".equals(action) || "state".equals(action)) {
            reply = GatewayReply.ok(proactiveStatusText());
        } else if (GatewayCommandConstants.ACTION_PAUSE.equals(action)
                || "off".equals(action)
                || "disable".equals(action)) {
            setProactiveSetting("proactive.enabled", "false");
            if (appConfig != null && appConfig.getProactive() != null) {
                appConfig.getProactive().setEnabled(false);
            }
            reply = GatewayReply.ok("已暂停主动协作。后续不会主动联系，直到使用 /proactive resume。");
        } else if (GatewayCommandConstants.ACTION_RESUME.equals(action)
                || "on".equals(action)
                || "enable".equals(action)) {
            setProactiveSetting("proactive.enabled", "true");
            if (appConfig != null && appConfig.getProactive() != null) {
                appConfig.getProactive().setEnabled(true);
            }
            reply = GatewayReply.ok("已恢复主动协作。系统仍会遵守免打扰、冷却和每日上限。");
        } else if ("why".equals(action)) {
            reply = GatewayReply.ok(proactiveWhyText());
        } else if ("less".equals(action)) {
            int cooldown =
                    Math.min(
                            24 * 60,
                            Math.max(
                                            30,
                                            appConfig.getProactive().getCooldownMinutes())
                                    + 60);
            int dailyMax = Math.max(1, appConfig.getProactive().getDailyMaxContacts() - 1);
            setProactiveSetting("proactive.cooldownMinutes", String.valueOf(cooldown));
            setProactiveSetting("proactive.dailyMaxContacts", String.valueOf(dailyMax));
            appConfig.getProactive().setCooldownMinutes(cooldown);
            appConfig.getProactive().setDailyMaxContacts(dailyMax);
            reply =
                    GatewayReply.ok(
                            "已降低主动联系频率：冷却时间 "
                                    + cooldown
                                    + " 分钟，每日最多 "
                                    + dailyMax
                                    + " 次。");
        } else if ("more".equals(action)) {
            int cooldown = Math.max(15, appConfig.getProactive().getCooldownMinutes() - 60);
            int dailyMax = Math.min(12, appConfig.getProactive().getDailyMaxContacts() + 1);
            setProactiveSetting("proactive.cooldownMinutes", String.valueOf(cooldown));
            setProactiveSetting("proactive.dailyMaxContacts", String.valueOf(dailyMax));
            appConfig.getProactive().setCooldownMinutes(cooldown);
            appConfig.getProactive().setDailyMaxContacts(dailyMax);
            reply =
                    GatewayReply.ok(
                            "已提高主动联系频率：冷却时间 "
                                    + cooldown
                                    + " 分钟，每日最多 "
                                    + dailyMax
                                    + " 次。");
        } else if ("ignore".equals(action)) {
            reply = GatewayReply.ok(ignoreProactiveCandidate(tail));
        } else {
            reply = GatewayReply.error(proactiveUsage());
        }
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_PROACTIVE);
        reply.getRuntimeMetadata().put("action", action);
        return reply;
    }

    /**
     * 执行当前Personality名称相关逻辑。
     *
     * @return 返回当前Personality名称结果。
     */
    String currentPersonalityName() {
        try {
            String value = globalSettingRepository.get(AgentSettingConstants.ACTIVE_PERSONALITY);
            return StrUtil.blankToDefault(value, "default");
        } catch (Exception e) {
            return "default";
        }
    }

    /**
     * 生成主动协作状态文本。
     *
     * @return 主动协作状态文本。
     */
    private String proactiveStatusText() {
        if (proactiveDiagnosticsService != null) {
            return proactiveDiagnosticsService.statusLine();
        }
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        return "主动协作"
                + (config.isEnabled() ? "已启用" : "已暂停")
                + "，检查间隔 "
                + config.getIntervalMinutes()
                + " 分钟，每日最多 "
                + config.getDailyMaxContacts()
                + " 次。";
    }

    /**
     * 生成最近一次主动协作决策解释。
     *
     * @return 决策解释文本。
     */
    private String proactiveWhyText() {
        if (proactiveDiagnosticsService == null) {
            return "主动协作诊断服务尚未启用。";
        }
        ProactiveDecisionRecord decision = proactiveDiagnosticsService.latestDecision();
        if (decision == null) {
            return "暂无主动协作决策记录。可以在 Dashboard 诊断里检查 home channel、免打扰和候选生成状态。";
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("最近一次主动协作决策：")
                .append(StrUtil.blankToDefault(decision.getDecision(), "-"));
        if (StrUtil.isNotBlank(decision.getReason())) {
            buffer.append("\n原因：").append(SecretRedactor.redact(decision.getReason(), 800));
        }
        if (StrUtil.isNotBlank(decision.getDeliveryStatus())) {
            buffer.append("\n投递状态：").append(decision.getDeliveryStatus());
        }
        if (StrUtil.isNotBlank(decision.getDeliveryError())) {
            buffer.append("\n投递错误：").append(SecretRedactor.redact(decision.getDeliveryError(), 500));
        }
        buffer.append("\n时间：").append(formatTimestamp(decision.getCreatedAt()));
        return buffer.toString();
    }

    /**
     * 忽略指定主动协作候选。
     *
     * @param candidateId 候选 ID。
     * @return 用户可见结果。
     */
    private String ignoreProactiveCandidate(String candidateId) throws Exception {
        if (proactiveRepository == null) {
            return "主动协作仓储尚未启用，无法忽略候选。";
        }
        if (StrUtil.isBlank(candidateId)) {
            return proactiveUsage();
        }
        proactiveRepository.markCandidateStatus(
                candidateId.trim(), "IGNORED", "user-command", System.currentTimeMillis());
        return "已忽略主动协作候选：" + candidateId.trim();
    }

    /**
     * 写入主动协作运行时设置覆盖。
     *
     * @param key 设置键。
     * @param value 设置值。
     */
    private void setProactiveSetting(String key, String value) throws Exception {
        if (globalSettingRepository != null) {
            globalSettingRepository.set(key, value);
        }
    }

    /**
     * 生成主动协作命令用法文本。
     *
     * @return 用法文本。
     */
    private String proactiveUsage() {
        return "用法：/proactive status|pause|resume|why|less|more|ignore <candidateId>";
    }

    /**
     * 格式化时间戳。
     *
     * @param timestamp 请求携带的时间戳。
     * @return 返回时间戳结果。
     */
    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "never";
        }
        return DateUtil.formatDateTime(new java.util.Date(timestamp));
    }
}
