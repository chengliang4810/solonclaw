package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.plugin.AgentPluginManager;
import com.jimuqu.solon.claw.plugin.AgentPluginManifest;
import com.jimuqu.solon.claw.plugin.PluginLoadDiagnostic;
import com.jimuqu.solon.claw.plugin.PluginLoadStatus;
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

    /**
     * 创建运行时控制面命令处理器。
     *
     * @param appConfig 应用配置。
     * @param globalSettingRepository 全局设置仓储。
     * @param pluginManager 插件管理器。
     */
    DefaultRuntimeCommandHandler(
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            AgentPluginManager pluginManager) {
        this.appConfig = appConfig;
        this.globalSettingRepository = globalSettingRepository;
        this.pluginManager = pluginManager;
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
     * 执行主动提醒启停和状态命令。
     *
     * @param args 工具或命令参数。
     * @param sourceKey 当前渠道用户来源键，用于隔离人工重试候选。
     * @return 返回主动协作命令结果。
     */
    GatewayReply handleProactive(String args, String sourceKey) throws Exception {
        SlashCommandLine.ActionTail parsed = SlashCommandLine.parseActionTail(args, "status");
        String action = parsed.getAction();
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
            reply = GatewayReply.ok("已暂停主动提醒。后续不会主动联系，直到使用 /proactive resume。");
        } else if (GatewayCommandConstants.ACTION_RESUME.equals(action)
                || "on".equals(action)
                || "enable".equals(action)) {
            setProactiveSetting("proactive.enabled", "true");
            if (appConfig != null && appConfig.getProactive() != null) {
                appConfig.getProactive().setEnabled(true);
            }
            reply = GatewayReply.ok("已恢复主动提醒。系统仍会遵守检查间隔、话题间隔和免打扰时段。");
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
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        return "主动提醒"
                + (config.isEnabled() ? "已启用" : "已暂停")
                + "，检查间隔 "
                + config.getIntervalHours()
                + " 小时，同话题至少间隔 "
                + config.getTopicCooldownHours()
                + " 小时。";
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
        return "用法：/proactive status|pause|resume";
    }
}
