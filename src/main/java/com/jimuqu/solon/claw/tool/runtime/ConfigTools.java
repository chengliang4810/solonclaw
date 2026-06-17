package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
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
    /** 运行时配置读写服务，负责白名单校验、密钥写入和配置文件落盘。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 消息网关刷新服务，用于在配置校验通过后刷新或重连渠道运行时。 */
    private final GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

    /** 当前应用配置，用于解释子进程环境变量透传策略。 */
    private final AppConfig appConfig;

    /**
     * 读取白名单内运行时配置，并对密钥类配置做脱敏展示。
     *
     * @param key 配置键或映射键。
     * @return ToolResultEnvelope JSON，包含配置键、脱敏后的值和预览文本。
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
     * 写入白名单内非密钥运行时配置。
     *
     * @param key 配置键或映射键。
     * @param value 用户传入的新配置值，列表型配置仍由 RuntimeSettingsService 解析。
     * @return ToolResultEnvelope JSON，包含写入后的安全展示值。
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
     * 校验 runtime/config.yml 后刷新运行时配置。
     *
     * @param reconnectChannels 是否在配置刷新后重连消息渠道。
     * @return ToolResultEnvelope JSON，包含刷新结果、配置文件路径和重连状态。
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
     * 解释一组环境变量名在子进程执行中的放行、阻断或强制透传决策。
     *
     * @param names JSON 数组、逗号分隔或换行分隔的环境变量名。
     * @return ToolResultEnvelope JSON，不暴露任何环境变量真实值。
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
     * 写入白名单内密钥配置，并在结果中只返回脱敏提示。
     *
     * @param key 配置键或映射键。
     * @param value 新密钥值。
     * @return ToolResultEnvelope JSON，确认密钥键名已更新但不暴露密钥内容。
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
     * 将异常转换为统一的工具错误 JSON。
     *
     * @param e 捕获到的异常。
     * @return 已脱敏的 ToolResultEnvelope 错误 JSON。
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
     * 构造环境变量探测输入，所有值固定为占位符避免泄漏本机真实环境。
     *
     * @param names 用户请求探测的环境变量名。
     * @return 供 SubprocessEnvironmentSanitizer 判断的占位 Map。
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
     * @return 去除控制字符和空白项后的环境变量名列表。
     */
    private List<String> parseProbeNames(String raw) {
        List<String> values = new ArrayList<String>();
        String text = SecretRedactor.stripDisplayControls(raw == null ? "" : raw).trim();
        if (StrUtil.isEmpty(text)) {
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
     * @param values 正在累积的环境变量名列表。
     * @param raw 原始输入值。
     */
    private void addProbeName(List<String> values, String raw) {
        String value = SecretRedactor.stripDisplayControls(raw == null ? "" : raw).trim();
        if (StrUtil.isNotEmpty(value)) {
            values.add(value);
        }
    }

    /**
     * 生成安全展示用的文本列表。
     *
     * @param values 待展示的原始文本列表。
     * @param maxLength 最大保留字符数。
     * @return 每一项都经过 SecretRedactor 处理的列表。
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
     * @return 非密钥原样返回，密钥统一返回星号占位。
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
     * @return 用于工具预览区域的脱敏文本。
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
     * @return 已脱敏并限制长度的文本。
     */
    private String safeText(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /** 提供配置Get工具能力，供 Agent 运行时按安全策略调用。 */
    @RequiredArgsConstructor
    public static class ConfigGetTool {
        /** 复用外层配置工具实现，避免别名工具重复配置读写逻辑。 */
        private final ConfigTools delegate;

        /**
         * 读取白名单内运行时配置。
         *
         * @param key 配置键或映射键。
         * @return 外层 configGet 生成的工具结果 JSON。
         */
        @ToolMapping(
                name = "config_get",
                description =
                        "Read a whitelisted runtime config key, such as llm.model or channels.weixin.enabled.")
        public String configGet(@Param(name = "key", description = "配置键，例如 llm.model") String key) {
            return delegate.configGet(key);
        }

        /**
         * config_get 的兼容别名。
         *
         * @param key 配置键或映射键。
         * @return 外层 configGet 生成的工具结果 JSON。
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
         * 探测子进程环境变量放行策略。
         *
         * @param names JSON 数组、逗号分隔或换行分隔的环境变量名。
         * @return 外层 configEnvProbe 生成的工具结果 JSON。
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
        /** 复用外层配置工具实现，保证别名工具共享同一套校验策略。 */
        private final ConfigTools delegate;

        /**
         * 写入白名单内非密钥运行时配置。
         *
         * @param key 配置键或映射键。
         * @param value 新配置值。
         * @return 外层 configSet 生成的工具结果 JSON。
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
         * config_set 的兼容别名。
         *
         * @param key 配置键或映射键。
         * @param value 新配置值。
         * @return 外层 configSet 生成的工具结果 JSON。
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
        /** 复用外层密钥配置写入逻辑，避免别名工具遗漏脱敏处理。 */
        private final ConfigTools delegate;

        /**
         * 写入白名单内密钥配置。
         *
         * @param key 配置键或映射键。
         * @param value 新密钥值。
         * @return 外层 configSetSecret 生成的工具结果 JSON。
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
         * config_set_secret 的兼容别名。
         *
         * @param key 配置键或映射键。
         * @param value 新密钥值。
         * @return 外层 configSetSecret 生成的工具结果 JSON。
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
        /** 复用外层配置刷新逻辑，确保校验和渠道重连策略一致。 */
        private final ConfigTools delegate;

        /**
         * 校验并刷新运行时配置。
         *
         * @param reconnectChannels 是否重连消息渠道。
         * @return 外层 configRefresh 生成的工具结果 JSON。
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
