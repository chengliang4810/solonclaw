package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.TuiRuntimeProtocolService;
import com.jimuqu.solon.claw.web.DomesticQrSetupService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 TUI 运行时 setup 状态、选项和页面级写入动作工具。 */
public class TuiRuntimeManageTools {
    /** TUI 运行时协议服务，复用独立终端前端的 setup/model/channel/config 契约。 */
    private final TuiRuntimeProtocolService protocolService;

    /**
     * 创建 TUI 运行时管理工具。
     *
     * @param appConfig 应用配置。
     */
    public TuiRuntimeManageTools(AppConfig appConfig) {
        this(appConfig, null, null);
    }

    /**
     * 创建带二维码绑定能力的 TUI 运行时管理工具。
     *
     * @param appConfig 应用配置。
     * @param weixinQrSetupService 微信二维码 setup 服务。
     * @param domesticQrSetupService 飞书、钉钉二维码 setup 服务。
     */
    public TuiRuntimeManageTools(
            AppConfig appConfig,
            WeixinQrSetupService weixinQrSetupService,
            DomesticQrSetupService domesticQrSetupService) {
        this.protocolService =
                new TuiRuntimeProtocolService(
                        appConfig, weixinQrSetupService, domesticQrSetupService);
    }

    /**
     * 查询或维护 TUI 运行时 setup、模型、渠道或配置状态。
     *
     * @param action 操作名称。
     * @param channel 渠道名，仅 channel_status 使用。
     * @param key 配置键，或 model_save_key 的 provider 标识。
     * @param value 配置值，或 model_save_key 的 API Key。
     * @param values 渠道保存字段，仅 channel_save 使用。
     * @param ticket 二维码票据，仅 channel_qr_get 使用。
     * @param sessionId 会话标识，可选。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "tui_runtime_manage",
            description =
                    "Inspect or operate TUI runtime setup. Actions: setup_status, setup.status, model_options, model.options, model_save_key, model.save_key, channel_options, channel.options, channel_status, channel.status, channel_save, channel.save, channel_qr_start, channel.qr.start, channel_qr_get, channel.qr.get, config_get, config.get.")
    public String tuiRuntimeManage(
            @Param(
                            name = "action",
                            description =
                                    "setup_status, setup.status, model_options, model.options, model_save_key, model.save_key, channel_options, channel.options, channel_status, channel.status, channel_save, channel.save, channel_qr_start, channel.qr.start, channel_qr_get, channel.qr.get, config_get, config.get")
                    String action,
            @Param(
                            name = "channel",
                            required = false,
                            description = "Channel for channel_status/channel_save/channel_qr_*")
                    String channel,
            @Param(
                            name = "key",
                            required = false,
                            description = "Config key for config_get or provider slug for model_save_key")
                    String key,
            @Param(
                            name = "value",
                            required = false,
                            description = "API key for model_save_key")
                    String value,
            @Param(
                            name = "values",
                            required = false,
                            description = "Channel field values for channel_save")
                    Map<String, String> values,
            @Param(
                            name = "ticket",
                            required = false,
                            description = "QR ticket for channel_qr_get")
                    String ticket,
            @Param(name = "sessionId", required = false, description = "Optional session id")
                    String sessionId) {
        try {
            Map<String, Object> result = run(action, channel, key, value, values, ticket, sessionId);
            return ToolResultEnvelope.ok("TUI 运行时操作完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行 TUI 运行时动作。
     *
     * @param action 操作名称。
     * @param channel 渠道名。
     * @param key 配置键。
     * @param value 配置值。
     * @param values 渠道配置字段。
     * @param ticket 二维码票据。
     * @param sessionId 会话标识。
     * @return 返回 TUI 协议服务结果。
     */
    private Map<String, Object> run(
            String action,
            String channel,
            String key,
            String value,
            Map<String, String> values,
            String ticket,
            String sessionId) {
        String normalized =
                action == null ? "setup_status" : action.trim().toLowerCase(Locale.ROOT);
        if ("setup_status".equals(normalized) || "setup.status".equals(normalized)) {
            return protocolService.setupStatus();
        }
        if ("model_options".equals(normalized) || "model.options".equals(normalized)) {
            return protocolService.modelOptions(sessionId);
        }
        if ("model_save_key".equals(normalized) || "model.save_key".equals(normalized)) {
            return protocolService.modelSaveKey(key, value, sessionId);
        }
        if ("channel_options".equals(normalized) || "channel.options".equals(normalized)) {
            return protocolService.channelOptions();
        }
        if ("channel_status".equals(normalized) || "channel.status".equals(normalized)) {
            return protocolService.channelStatus(channel);
        }
        if ("channel_save".equals(normalized) || "channel.save".equals(normalized)) {
            return protocolService.channelSave(channel, safeValues(values), sessionId);
        }
        if ("channel_qr_start".equals(normalized) || "channel.qr.start".equals(normalized)) {
            return protocolService.channelQrStart(channel, sessionId);
        }
        if ("channel_qr_get".equals(normalized) || "channel.qr.get".equals(normalized)) {
            return protocolService.channelQrGet(channel, ticket, sessionId);
        }
        if ("config_get".equals(normalized) || "config.get".equals(normalized)) {
            return protocolService.configGet(key);
        }
        return protocolService.setupStatus();
    }

    /**
     * 复制渠道字段，避免调用方复用可变 Map 时影响 setup 写入过程。
     *
     * @param values 原始渠道字段。
     * @return 可安全传入协议服务的字段副本。
     */
    private Map<String, String> safeValues(Map<String, String> values) {
        return values == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(values);
    }
}
