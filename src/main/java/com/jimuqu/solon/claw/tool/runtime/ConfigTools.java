package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 运行时配置工具。 */
@RequiredArgsConstructor
public class ConfigTools {
    /** 保存运行时设置服务集合，维持调用顺序或去重语义。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 注入消息网关运行时刷新服务，用于调用对应业务能力。 */
    private final GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

    /** 注入应用配置，用于配置。 */
    private final AppConfig appConfig;

    /**
     * 执行配置Get相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回配置Get结果。
     */
    @ToolMapping(
            name = "config_get",
            description =
                    "Read a whitelisted runtime config key, such as llm.model or channels.weixin.enabled.")
    public String configGet(@Param(name = "key", description = "配置键，例如 llm.model") String key) {
        try {
            Object value = runtimeSettingsService.getConfigValue(key);
            Object safeValue = safeValue(key, value);
            String preview = safePreview(key, value);
            return ToolResultEnvelope.ok("读取运行时配置：" + safeText(key, 400))
                    .data("key", safeText(key, 400))
                    .data("value", safeValue)
                    .data(
                            "redacted",
                            Boolean.valueOf(runtimeSettingsService.isSecretConfigKey(key)))
                    .preview(preview)
                    .toJson();
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * 执行配置Set相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回配置Set结果。
     */
    @ToolMapping(
            name = "config_set",
            description =
                    "Update a whitelisted runtime config key. Global config changes take effect on the next message.")
    public String configSet(
            @Param(name = "key", description = "配置键，例如 llm.model 或 channels.weixin.enabled")
                    String key,
            @Param(name = "value", description = "新的配置值，列表键使用逗号分隔") String value) {
        try {
            if (runtimeSettingsService.isSecretConfigKey(key)) {
                throw new IllegalArgumentException(key + " 是密钥配置，请使用 config_set_secret 更新。");
            }
            runtimeSettingsService.setConfigValue(key, value);
            Object current = runtimeSettingsService.getConfigValue(key);
            return ToolResultEnvelope.ok("已更新运行时配置：" + safeText(key, 400))
                    .data("key", safeText(key, 400))
                    .data("value", safeValue(key, current))
                    .data("note", "takes effect on the next message")
                    .preview(safeText(key, 400) + "=" + safePreview(key, current))
                    .toJson();
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * 执行配置刷新相关逻辑。
     *
     * @param reconnectChannels reconnectChannels 参数。
     * @return 返回配置刷新结果。
     */
    @ToolMapping(
            name = "config_refresh",
            description =
                    "Validate runtime/config.yml first, then refresh runtime config. If validation fails, do not refresh.")
    public String configRefresh(
            @Param(name = "reconnectChannels", description = "是否重连渠道连接；默认 false", required = false)
                    Boolean reconnectChannels) {
        try {
            GatewayRuntimeRefreshService.RefreshResult result =
                    Boolean.TRUE.equals(reconnectChannels)
                            ? gatewayRuntimeRefreshService.refreshNow()
                            : gatewayRuntimeRefreshService.refreshConfigOnly();
            ToolResultEnvelope envelope =
                    result.isSuccess()
                            ? ToolResultEnvelope.ok(result.getMessage())
                            : ToolResultEnvelope.error(result.getMessage());
            return envelope.data("refreshed", Boolean.valueOf(result.isRefreshed()))
                    .data("reconnectedChannels", Boolean.valueOf(result.isReconnectedChannels()))
                    .data("configFile", safeText(result.getConfigFile(), 400))
                    .data("message", safeText(result.getMessage(), 1000))
                    .preview(safeText(result.getMessage(), 1000))
                    .toJson();
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * 执行配置环境变量Probe相关逻辑。
     *
     * @param names names 参数。
     * @return 返回配置Env Probe结果。
     */
    @ToolMapping(
            name = "config_env_probe",
            description =
                    "Explain how subprocess env names are allowed, blocked, or force-passed without exposing values.")
    public String configEnvProbe(
            @Param(name = "names", description = "要探测的环境变量名列表；可传 JSON 数组或逗号/换行分隔文本") String names) {
        try {
            List<String> requestedNames = parseProbeNames(names);
            List<Map<String, Object>> decisions =
                    SubprocessEnvironmentSanitizer.probeDecisions(
                            envProbeInput(requestedNames), appConfig, true);
            return ToolResultEnvelope.ok("已分析子进程环境变量放行策略")
                    .data("requestedCount", Integer.valueOf(requestedNames.size()))
                    .data("requestedNames", safeTextList(requestedNames, 120))
                    .data("decisionCategories", SubprocessEnvironmentSanitizer.decisionCategories())
                    .data("decisions", decisions)
                    .preview(
                            "env probe: "
                                    + safeText(String.valueOf(requestedNames.size()), 32)
                                    + " items")
                    .toJson();
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * 执行配置Set密钥相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回配置Set密钥结果。
     */
    @ToolMapping(
            name = "config_set_secret",
            description =
                    "Update a whitelisted runtime secret key, such as providers.default.apiKey.")
    public String configSetSecret(
            @Param(name = "key", description = "配置键，例如 providers.default.apiKey") String key,
            @Param(name = "value", description = "新的密钥值") String value) {
        try {
            runtimeSettingsService.setSecretValue(key, value);
            return ToolResultEnvelope.ok("已更新运行时密钥：" + safeText(key, 400))
                    .data("key", safeText(key, 400))
                    .data("note", "takes effect on the next message")
                    .preview(safeText(key, 400) + "=***")
                    .toJson();
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * 执行错误相关逻辑。
     *
     * @param e 捕获到的异常。
     * @return 返回error结果。
     */
    private String error(Exception e) {
        return ToolResultEnvelope.error(
                        SecretRedactor.redact(
                                e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage(),
                                1000))
                .toJson();
    }

    /**
     * 执行环境变量Probe输入相关逻辑。
     *
     * @param names names 参数。
     * @return 返回env Probe输入结果。
     */
    private Map<String, String> envProbeInput(List<String> names) {
        Map<String, String> env = new LinkedHashMap<String, String>();
        for (String name : names) {
            if (name != null) {
                env.put(name, "__redacted__");
            }
        }
        return env;
    }

    /**
     * 解析Probe Names。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的Probe Names。
     */
    private List<String> parseProbeNames(String raw) {
        List<String> values = new ArrayList<String>();
        String text = SecretRedactor.stripDisplayControls(raw == null ? "" : raw).trim();
        if (text.length() == 0) {
            return values;
        }
        if (text.startsWith("[")) {
            Object data = org.noear.snack4.ONode.ofJson(text).toData();
            if (data instanceof List) {
                List<?> items = (List<?>) data;
                for (Object item : items) {
                    addProbeName(values, item == null ? null : String.valueOf(item));
                }
                return values;
            }
        }
        for (String item : text.split("[,\\r\\n]+")) {
            addProbeName(values, item);
        }
        return values;
    }

    /**
     * 追加Probe名称。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param raw 原始输入值。
     */
    private void addProbeName(List<String> values, String raw) {
        String value = SecretRedactor.stripDisplayControls(raw == null ? "" : raw).trim();
        if (value.length() > 0) {
            values.add(value);
        }
    }

    /**
     * 生成安全展示用的文本列表。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param maxLength 最大保留字符数。
     * @return 返回safe Text List结果。
     */
    private List<Object> safeTextList(List<String> values, int maxLength) {
        List<Object> items = new ArrayList<Object>();
        if (values == null) {
            return items;
        }
        for (String value : values) {
            if (value != null) {
                items.add(safeText(value, maxLength));
            }
        }
        return items;
    }

    /**
     * 生成安全展示用的值。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Value结果。
     */
    private Object safeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (!runtimeSettingsService.isSecretConfigKey(key)) {
            return value;
        }
        return "***";
    }

    /**
     * 生成安全展示用的预览。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Preview结果。
     */
    private String safePreview(String key, Object value) {
        if (value == null) {
            return "";
        }
        if (runtimeSettingsService.isSecretConfigKey(key)) {
            return safeText(key, 400) + "=***";
        }
        return SecretRedactor.redact(String.valueOf(value), 1000);
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe Text结果。
     */
    private String safeText(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /** 提供配置Get工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class ConfigGetTool {
        /** 记录配置Get中的委托。 */
        private final ConfigTools delegate;

        /**
         * 执行配置Get相关逻辑。
         *
         * @param key 配置键或映射键。
         * @return 返回配置Get结果。
         */
        @ToolMapping(
                name = "config_get",
                description =
                        "Read a whitelisted runtime config key, such as llm.model or channels.weixin.enabled.")
        public String configGet(@Param(name = "key", description = "配置键，例如 llm.model") String key) {
            return delegate.configGet(key);
        }

        /**
         * 执行配置Read相关逻辑。
         *
         * @param key 配置键或映射键。
         * @return 返回配置Read结果。
         */
        @ToolMapping(
                name = "config_read",
                description =
                        "Alias of config_get. Read a whitelisted runtime config key with secret redaction.")
        public String configRead(
                @Param(name = "key", description = "配置键，例如 llm.model") String key) {
            return delegate.configGet(key);
        }

        /**
         * 执行配置环境变量Probe相关逻辑。
         *
         * @param names names 参数。
         * @return 返回配置Env Probe结果。
         */
        @ToolMapping(
                name = "config_env_probe",
                description =
                        "Explain how subprocess env names are allowed, blocked, or force-passed without exposing values.")
        public String configEnvProbe(
                @Param(name = "names", description = "要探测的环境变量名列表；可传 JSON 数组或逗号/换行分隔文本")
                        String names) {
            return delegate.configEnvProbe(names);
        }
    }

    /** 提供配置Set工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class ConfigSetTool {
        /** 记录配置Set中的委托。 */
        private final ConfigTools delegate;

        /**
         * 执行配置Set相关逻辑。
         *
         * @param key 配置键或映射键。
         * @param value 待规范化或校验的原始值。
         * @return 返回配置Set结果。
         */
        @ToolMapping(
                name = "config_set",
                description =
                        "Update a whitelisted runtime config key. Global config changes take effect on the next message.")
        public String configSet(
                @Param(name = "key", description = "配置键，例如 llm.model 或 channels.weixin.enabled")
                        String key,
                @Param(name = "value", description = "新的配置值，列表键使用逗号分隔") String value) {
            return delegate.configSet(key, value);
        }

        /**
         * 执行配置写入相关逻辑。
         *
         * @param key 配置键或映射键。
         * @param value 待规范化或校验的原始值。
         * @return 返回配置Write结果。
         */
        @ToolMapping(
                name = "config_write",
                description =
                        "Alias of config_set. Update a whitelisted non-secret runtime config key.")
        public String configWrite(
                @Param(name = "key", description = "配置键，例如 llm.model 或 channels.weixin.enabled")
                        String key,
                @Param(name = "value", description = "新的配置值，列表键使用逗号分隔") String value) {
            return delegate.configSet(key, value);
        }
    }

    /** 提供配置Set密钥工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class ConfigSetSecretTool {
        /** 记录配置Set密钥中的委托。 */
        private final ConfigTools delegate;

        /**
         * 执行配置Set密钥相关逻辑。
         *
         * @param key 配置键或映射键。
         * @param value 待规范化或校验的原始值。
         * @return 返回配置Set密钥结果。
         */
        @ToolMapping(
                name = "config_set_secret",
                description =
                        "Update a whitelisted runtime secret key, such as providers.default.apiKey.")
        public String configSetSecret(
                @Param(name = "key", description = "配置键，例如 providers.default.apiKey") String key,
                @Param(name = "value", description = "新的密钥值") String value) {
            return delegate.configSetSecret(key, value);
        }

        /**
         * 执行配置更新密钥相关逻辑。
         *
         * @param key 配置键或映射键。
         * @param value 待规范化或校验的原始值。
         * @return 返回配置更新密钥结果。
         */
        @ToolMapping(
                name = "config_update_secret",
                description =
                        "Alias of config_set_secret. Update a whitelisted runtime secret key.")
        public String configUpdateSecret(
                @Param(name = "key", description = "配置键，例如 providers.default.apiKey") String key,
                @Param(name = "value", description = "新的密钥值") String value) {
            return delegate.configSetSecret(key, value);
        }
    }

    /** 提供配置刷新工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class ConfigRefreshTool {
        /** 记录配置刷新中的委托。 */
        private final ConfigTools delegate;

        /**
         * 执行配置刷新相关逻辑。
         *
         * @param reconnectChannels reconnectChannels 参数。
         * @return 返回配置刷新结果。
         */
        @ToolMapping(
                name = "config_refresh",
                description =
                        "Validate runtime/config.yml first, then refresh runtime config. If validation fails, do not refresh.")
        public String configRefresh(
                @Param(
                                name = "reconnectChannels",
                                description = "是否重连渠道连接；默认 false",
                                required = false)
                        Boolean reconnectChannels) {
            return delegate.configRefresh(reconnectChannels);
        }
    }
}
