package com.jimuqu.solon.claw.web;

import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 插件状态接口。 */
@Controller
public class DashboardPluginStatusController {
    /** 注入插件状态服务，用于读取插件加载和诊断快照。 */
    private final DashboardPluginStatusService pluginStatusService;

    /**
     * 创建插件状态控制器。
     *
     * @param pluginStatusService 插件状态服务。
     */
    public DashboardPluginStatusController(DashboardPluginStatusService pluginStatusService) {
        this.pluginStatusService = pluginStatusService;
    }

    /**
     * 读取插件加载状态。
     *
     * @return Dashboard 响应包装后的插件状态。
     */
    @Mapping(value = "/api/plugins/status", method = MethodType.GET)
    public Map<String, Object> status() {
        return DashboardResponse.ok(pluginStatusService.status());
    }
}
