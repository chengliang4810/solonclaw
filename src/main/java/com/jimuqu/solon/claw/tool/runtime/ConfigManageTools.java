package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 Dashboard 配置元数据只读查询工具。 */
public class ConfigManageTools {
    /** Dashboard 配置服务，用于读取配置结构、默认值和诊断信息。 */
    private final DashboardConfigService configService;

    /**
     * 创建配置管理工具。
     *
     * @param configService Dashboard 配置服务。
     */
    public ConfigManageTools(DashboardConfigService configService) {
        this.configService = configService;
    }

    /**
     * 查询配置结构、默认值或诊断信息。
     *
     * @param action 操作名称。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "config_manage",
            description =
                    "Inspect dashboard config metadata. Actions: current, schema, defaults, diagnostics.")
    public String configManage(
            @Param(name = "action", description = "current, schema, defaults, diagnostics")
                    String action) {
        try {
            if (configService == null) {
                return ToolResultEnvelope.error("config service unavailable").toJson();
            }
            Map<String, Object> result = run(action);
            return ToolResultEnvelope.ok("配置元数据查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行配置元数据只读查询。
     *
     * @param action 操作名称。
     * @return 返回 Dashboard 配置服务结果。
     */
    private Map<String, Object> run(String action) {
        String normalized = action == null ? "schema" : action.trim().toLowerCase(Locale.ROOT);
        if ("current".equals(normalized) || "config".equals(normalized)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("config", redactPasswordFields(configService.getConfig()));
            return result;
        }
        if ("defaults".equals(normalized)) {
            return configService.getDefaults();
        }
        if ("diagnostics".equals(normalized) || "diagnostic".equals(normalized)) {
            return configService.diagnostics();
        }
        return configService.getSchema();
    }

    /**
     * 遮盖配置中的密钥字段，避免自然语言工具泄露 Dashboard 原始配置值。
     *
     * @param config 当前配置结构。
     * @return 返回已遮盖密钥的配置副本。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> redactPasswordFields(Map<String, Object> config) {
        Map<String, Object> redacted = deepCopy(config);
        Object fieldsValue = configService.getSchema().get("fields");
        if (!(fieldsValue instanceof Map)) {
            return redacted;
        }
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) fieldsValue).entrySet()) {
            Object definition = entry.getValue();
            if (definition instanceof Map
                    && "password".equals(((Map<String, Object>) definition).get("type"))) {
                String key = entry.getKey();
                setNested(redacted, key, "********");
            }
        }
        return redacted;
    }

    /**
     * 复制配置 Map，避免修改 Dashboard 服务返回对象。
     *
     * @param input 输入配置。
     * @return 返回复制后的配置。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = deepCopy((Map<String, Object>) value);
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    /**
     * 写入嵌套配置值。
     *
     * @param root 配置根对象。
     * @param key 点分隔配置键。
     * @param value 待写入值。
     */
    @SuppressWarnings("unchecked")
    private void setNested(Map<String, Object> root, String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map)) {
                return;
            }
            cursor = (Map<String, Object>) next;
        }
        if (cursor.containsKey(parts[parts.length - 1])) {
            cursor.put(parts[parts.length - 1], value);
        }
    }
}
