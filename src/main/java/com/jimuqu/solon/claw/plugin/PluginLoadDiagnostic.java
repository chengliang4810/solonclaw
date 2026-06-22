package com.jimuqu.solon.claw.plugin;

/** 单个插件发现或加载结果。 */
public class PluginLoadDiagnostic {
    /** 插件清单中的唯一名称；清单解析失败时可能为空。 */
    private final String pluginName;

    /** 本次发现或加载动作的最终状态。 */
    private final PluginLoadStatus status;

    /** 机器可读的诊断原因，用于 dashboard/API 侧分类展示。 */
    private final String reason;

    /** 已脱敏的用户可读消息，不能包含插件密钥或环境变量值。 */
    private final String message;

    /**
     * 创建插件加载诊断记录。
     *
     * @param pluginName 插件名称。
     * @param status 加载状态。
     * @param reason 机器可读原因。
     * @param message 已脱敏的展示消息。
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
     * @return 插件清单中的名称。
     */
    public String getPluginName() {
        return pluginName;
    }

    /**
     * 读取加载状态。
     *
     * @return 加载、跳过或失败状态。
     */
    public PluginLoadStatus getStatus() {
        return status;
    }

    /**
     * 读取机器可读原因。
     *
     * @return 诊断原因标识。
     */
    public String getReason() {
        return reason;
    }

    /**
     * 读取展示消息。
     *
     * @return 已脱敏的人类可读诊断消息。
     */
    public String getMessage() {
        return message;
    }
}
