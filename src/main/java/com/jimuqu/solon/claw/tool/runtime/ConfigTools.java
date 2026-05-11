package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import lombok.RequiredArgsConstructor;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 运行时配置工具。 */
@RequiredArgsConstructor
public class ConfigTools {
    private final RuntimeSettingsService runtimeSettingsService;
    private final GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

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
                    .data("redacted", Boolean.valueOf(runtimeSettingsService.isSecretConfigKey(key)))
                    .preview(preview)
                    .toJson();
        } catch (Exception e) {
            return error(e);
        }
    }

    @ToolMapping(
            name = "config_set",
            description =
                    "Update a whitelisted runtime config key. Global config changes take effect on the next message.")
    public String configSet(
            @Param(name = "key", description = "配置键，例如 llm.model 或 channels.weixin.enabled")
                    String key,
            @Param(name = "value", description = "新的配置值，列表键使用逗号分隔") String value) {
        try {
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
        try {
            GatewayRuntimeRefreshService.RefreshResult result =
                    Boolean.TRUE.equals(reconnectChannels)
                            ? gatewayRuntimeRefreshService.refreshNow()
                            : gatewayRuntimeRefreshService.refreshConfigOnly();
            ToolResultEnvelope envelope =
                    result.isSuccess()
                            ? ToolResultEnvelope.ok(result.getMessage())
                            : ToolResultEnvelope.error(result.getMessage());
            return envelope
                    .data("refreshed", Boolean.valueOf(result.isRefreshed()))
                    .data("reconnectedChannels", Boolean.valueOf(result.isReconnectedChannels()))
                    .data("configFile", safeText(result.getConfigFile(), 400))
                    .data("message", safeText(result.getMessage(), 1000))
                    .preview(safeText(result.getMessage(), 1000))
                    .toJson();
        } catch (Exception e) {
            return error(e);
        }
    }

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

    private String error(Exception e) {
        return ToolResultEnvelope.error(
                        SecretRedactor.redact(
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                                1000))
                .toJson();
    }

    private Object safeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (!runtimeSettingsService.isSecretConfigKey(key)) {
            return value;
        }
        return "***";
    }

    private String safePreview(String key, Object value) {
        if (value == null) {
            return "";
        }
        if (runtimeSettingsService.isSecretConfigKey(key)) {
            return safeText(key, 400) + "=***";
        }
        return SecretRedactor.redact(String.valueOf(value), 1000);
    }

    private String safeText(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    @RequiredArgsConstructor
    public static class ConfigGetTool {
        private final ConfigTools delegate;

        @ToolMapping(
                name = "config_get",
                description =
                        "Read a whitelisted runtime config key, such as llm.model or channels.weixin.enabled.")
        public String configGet(@Param(name = "key", description = "配置键，例如 llm.model") String key) {
            return delegate.configGet(key);
        }

        @ToolMapping(
                name = "config_read",
                description =
                        "Alias of config_get. Read a whitelisted runtime config key with secret redaction.")
        public String configRead(
                @Param(name = "key", description = "配置键，例如 llm.model") String key) {
            return delegate.configGet(key);
        }
    }

    @RequiredArgsConstructor
    public static class ConfigSetTool {
        private final ConfigTools delegate;

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

    @RequiredArgsConstructor
    public static class ConfigSetSecretTool {
        private final ConfigTools delegate;

        @ToolMapping(
                name = "config_set_secret",
                description =
                        "Update a whitelisted runtime secret key, such as providers.default.apiKey.")
        public String configSetSecret(
                @Param(name = "key", description = "配置键，例如 providers.default.apiKey") String key,
                @Param(name = "value", description = "新的密钥值") String value) {
            return delegate.configSetSecret(key, value);
        }

        @ToolMapping(
                name = "config_update_secret",
                description = "Alias of config_set_secret. Update a whitelisted runtime secret key.")
        public String configUpdateSecret(
                @Param(name = "key", description = "配置键，例如 providers.default.apiKey") String key,
                @Param(name = "value", description = "新的密钥值") String value) {
            return delegate.configSetSecret(key, value);
        }
    }

    @RequiredArgsConstructor
    public static class ConfigRefreshTool {
        private final ConfigTools delegate;

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
