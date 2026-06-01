package com.jimuqu.solon.claw.bootstrap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;

/** 健康检查控制器。 */
@Controller
public class HealthController {
    private static final String SERVICE_NAME = "solon-claw";
    private static final long STARTED_AT_EPOCH_MS = System.currentTimeMillis();
    private static final long STARTED_AT_NANOS = System.nanoTime();

    /**
     * 返回服务存活状态。
     *
     * @return 健康检查响应
     */
    @Mapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("service", SERVICE_NAME);
        return result;
    }

    /**
     * 返回带运行时摘要的健康检查结果。
     *
     * @return 详细健康检查响应
     */
    @Mapping("/health/detailed")
    public Map<String, Object> detailedHealth() {
        long nowEpochMs = System.currentTimeMillis();
        long uptimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - STARTED_AT_NANOS);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> service = new LinkedHashMap<String, Object>();
        Map<String, Object> runtime = new LinkedHashMap<String, Object>();

        service.put("name", SERVICE_NAME);
        service.put("status", "up");

        runtime.put("startedAtEpochMs", Long.valueOf(STARTED_AT_EPOCH_MS));
        runtime.put("currentTimeEpochMs", Long.valueOf(nowEpochMs));
        runtime.put("uptimeMs", Long.valueOf(Math.max(0L, uptimeMs)));
        runtime.put("uptimeSeconds", Long.valueOf(Math.max(0L, uptimeMs / 1000L)));

        result.put("ok", true);
        result.put("service", service);
        result.put("runtime", runtime);
        return result;
    }
}
