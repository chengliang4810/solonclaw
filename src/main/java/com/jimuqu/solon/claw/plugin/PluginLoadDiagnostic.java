package com.jimuqu.solon.claw.plugin;

/** 单个插件发现或加载结果。 */
public class PluginLoadDiagnostic {
    private final String pluginName;
    private final PluginLoadStatus status;
    private final String reason;
    private final String message;

    public PluginLoadDiagnostic(
            String pluginName, PluginLoadStatus status, String reason, String message) {
        this.pluginName = pluginName;
        this.status = status;
        this.reason = reason;
        this.message = message;
    }

    public String getPluginName() {
        return pluginName;
    }

    public PluginLoadStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getMessage() {
        return message;
    }
}
