package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.TuiRuntimeProtocolService;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 TUI 运行时 setup 状态和选项只读查询工具。 */
public class TuiRuntimeManageTools {
    /** TUI 运行时协议服务，复用独立终端前端的 setup/model/channel/config 读取契约。 */
    private final TuiRuntimeProtocolService protocolService;

    /**
     * 创建 TUI 运行时管理工具。
     *
     * @param appConfig 应用配置。
     */
    public TuiRuntimeManageTools(AppConfig appConfig) {
        this.protocolService = new TuiRuntimeProtocolService(appConfig);
    }

    /**
     * 查询 TUI 运行时 setup、模型、渠道或配置状态。
     *
     * @param action 操作名称。
     * @param channel 渠道名，仅 channel_status 使用。
     * @param key 配置键，仅 config_get 使用。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "tui_runtime_manage",
            description =
                    "Inspect TUI runtime setup. Actions: setup_status, model_options, channel_options, channel_status, config_get.")
    public String tuiRuntimeManage(
            @Param(
                            name = "action",
                            description = "setup_status, model_options, channel_options, channel_status, config_get")
                    String action,
            @Param(name = "channel", required = false, description = "Channel for channel_status")
                    String channel,
            @Param(name = "key", required = false, description = "Config key for config_get")
                    String key) {
        try {
            Map<String, Object> result = run(action, channel, key);
            return ToolResultEnvelope.ok("TUI 运行时查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行只读查询动作。
     *
     * @param action 操作名称。
     * @param channel 渠道名。
     * @param key 配置键。
     * @return 返回 TUI 协议服务结果。
     */
    private Map<String, Object> run(String action, String channel, String key) {
        String normalized =
                action == null ? "setup_status" : action.trim().toLowerCase(Locale.ROOT);
        if ("model_options".equals(normalized)) {
            return protocolService.modelOptions("");
        }
        if ("channel_options".equals(normalized)) {
            return protocolService.channelOptions();
        }
        if ("channel_status".equals(normalized)) {
            return protocolService.channelStatus(channel);
        }
        if ("config_get".equals(normalized)) {
            return protocolService.configGet(key);
        }
        return protocolService.setupStatus();
    }
}
