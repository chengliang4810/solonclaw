package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 日志接口。 */
@Controller
public class DashboardLogsController {
    /** 注入logs服务，用于调用对应业务能力。 */
    private final DashboardLogsService logsService;

    /**
     * 创建控制台Logs控制器实例，并注入运行所需依赖。
     *
     * @param logsService logs服务依赖。
     */
    public DashboardLogsController(DashboardLogsService logsService) {
        this.logsService = logsService;
    }

    /**
     * 执行logs相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回logs结果。
     */
    @Mapping(value = "/api/logs", method = MethodType.GET)
    public Map<String, Object> logs(Context context) {
        String file = context.param("file");
        int lines = context.paramAsInt("lines", 100);
        String level = context.param("level");
        String component = context.param("component");

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("file", file == null ? "agent" : file);
        result.put("lines", logsService.read(file, lines, level, component));
        return result;
    }
}
