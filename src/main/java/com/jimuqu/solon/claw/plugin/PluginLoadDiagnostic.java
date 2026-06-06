package com.jimuqu.solon.claw.plugin;

/** 单个插件发现或加载结果。 */
public class PluginLoadDiagnostic {
    /** 记录插件Load诊断中的插件名称。 */
    private final String pluginName;

    /** 记录插件Load诊断中的状态。 */
    private final PluginLoadStatus status;

    /** 记录插件Load诊断中的原因。 */
    private final String reason;

    /** 记录插件Load诊断中的消息。 */
    private final String message;

    /**
     * 创建插件Load诊断实例，并注入运行所需依赖。
     *
     * @param pluginName 插件名称参数。
     * @param status 状态参数。
     * @param reason 原因参数。
     * @param message 平台消息或错误消息。
     */
    public PluginLoadDiagnostic(
            String pluginName, PluginLoadStatus status, String reason, String message) {
        this.pluginName = pluginName;
        this.status = status;
        this.reason = reason;
        this.message = message;
    }

    /**
     * 读取插件名称。
     *
     * @return 返回读取到的插件名称。
     */
    public String getPluginName() {
        return pluginName;
    }

    /**
     * 读取状态。
     *
     * @return 返回读取到的状态。
     */
    public PluginLoadStatus getStatus() {
        return status;
    }

    /**
     * 读取Reason。
     *
     * @return 返回读取到的Reason。
     */
    public String getReason() {
        return reason;
    }

    /**
     * 读取消息。
     *
     * @return 返回读取到的消息。
     */
    public String getMessage() {
        return message;
    }
}
