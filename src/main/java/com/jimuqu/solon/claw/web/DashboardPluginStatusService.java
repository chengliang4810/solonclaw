package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.plugin.AgentPluginManager;
import com.jimuqu.solon.claw.plugin.AgentPluginManifest;
import com.jimuqu.solon.claw.plugin.PluginLoadDiagnostic;
import com.jimuqu.solon.claw.plugin.PluginLoadStatus;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard 插件状态服务，复用插件管理器的加载清单和诊断结果。 */
public class DashboardPluginStatusService {
    /** 注入插件生命周期管理器，用于读取最近一次扫描和加载状态。 */
    private final AgentPluginManager pluginManager;

    /**
     * 创建插件状态服务实例。
     *
     * @param pluginManager 插件生命周期管理器。
     */
    public DashboardPluginStatusService(AgentPluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 读取插件状态快照。
     *
     * @return 可安全展示给 Dashboard 的插件状态。
     */
    public Map<String, Object> status() {
        List<AgentPluginManifest> plugins = pluginManager.listPlugins();
        List<PluginLoadDiagnostic> diagnostics = pluginManager.diagnostics();
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("loaded_count", Integer.valueOf(plugins.size()));
        status.put(
                "skipped_count",
                Integer.valueOf(countDiagnostics(diagnostics, PluginLoadStatus.SKIPPED)));
        status.put(
                "failed_count",
                Integer.valueOf(countDiagnostics(diagnostics, PluginLoadStatus.FAILED)));
        status.put("diagnostic_count", Integer.valueOf(diagnostics.size()));
        status.put("plugins", pluginItems(plugins));
        status.put("diagnostics", diagnosticItems(diagnostics));
        return status;
    }

    /**
     * 统计指定插件诊断状态的条目数。
     *
     * @param diagnostics 插件加载诊断列表。
     * @param status 需要统计的状态。
     * @return 匹配状态的诊断数量。
     */
    private int countDiagnostics(List<PluginLoadDiagnostic> diagnostics, PluginLoadStatus status) {
        int count = 0;
        for (PluginLoadDiagnostic diagnostic : diagnostics) {
            if (diagnostic != null && diagnostic.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /**
     * 转换已加载插件清单，避免输出本机完整目录。
     *
     * @param plugins 已加载插件清单。
     * @return Dashboard 可展示的插件列表。
     */
    private List<Map<String, Object>> pluginItems(List<AgentPluginManifest> plugins) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (AgentPluginManifest plugin : plugins) {
            if (plugin == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", safe(plugin.getName(), 160));
            item.put("version", safe(plugin.getVersion(), 80));
            item.put("description", safe(plugin.getDescription(), 300));
            item.put("author", safe(plugin.getAuthor(), 160));
            item.put("kind", safe(plugin.getKind(), 80));
            item.put("source", safe(plugin.getSource(), 80));
            item.put("enabled", Boolean.valueOf(plugin.isEnabled()));
            item.put("auto_load", Boolean.valueOf(plugin.isAutoLoad()));
            item.put("provides_tools", safeList(plugin.getProvidesTools(), 120));
            item.put("directory_ref", directoryRef(plugin.getDirectory()));
            items.add(item);
        }
        return items;
    }

    /**
     * 转换加载诊断记录，保留机器可读原因和脱敏消息。
     *
     * @param diagnostics 插件加载诊断列表。
     * @return Dashboard 可展示的诊断列表。
     */
    private List<Map<String, Object>> diagnosticItems(List<PluginLoadDiagnostic> diagnostics) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (PluginLoadDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("plugin_name", safe(diagnostic.getPluginName(), 160));
            item.put("status", statusText(diagnostic.getStatus()));
            item.put("reason", safe(diagnostic.getReason(), 160));
            item.put("message", safe(diagnostic.getMessage(), 1000));
            items.add(item);
        }
        return items;
    }

    /**
     * 生成插件目录引用，只暴露最后一级目录名。
     *
     * @param directory 插件目录。
     * @return 安全目录引用。
     */
    private String directoryRef(Path directory) {
        if (directory == null || directory.getFileName() == null) {
            return "";
        }
        return "plugin://" + safe(directory.getFileName().toString(), 200);
    }

    /**
     * 转换插件诊断枚举为前端稳定 token。
     *
     * @param status 插件诊断状态。
     * @return 小写状态 token。
     */
    private String statusText(PluginLoadStatus status) {
        return status == null ? "" : status.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 脱敏单个展示文本。
     *
     * @param value 原始文本。
     * @param maxLength 最大展示长度。
     * @return 安全展示文本。
     */
    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), maxLength);
    }

    /**
     * 脱敏字符串列表。
     *
     * @param values 原始字符串列表。
     * @param maxLength 单项最大展示长度。
     * @return 脱敏后的字符串列表。
     */
    private List<String> safeList(List<String> values, int maxLength) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            result.add(safe(value, maxLength));
        }
        return result;
    }
}
