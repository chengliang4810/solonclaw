package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.web.DashboardStatusService;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;

/** 健康检查控制器。 */
@Controller
public class HealthController {
    private static final String SERVICE_NAME = "solon-claw";
    private static final long STARTED_AT_EPOCH_MS = System.currentTimeMillis();
    private static final long STARTED_AT_NANOS = System.nanoTime();

    @Inject(required = false)
    private DashboardStatusService statusService;

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
        String updatedAt = isoNow();
        Map<String, Object> status = runtimeStatusSnapshot();

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> service = new LinkedHashMap<String, Object>();
        Map<String, Object> runtime = new LinkedHashMap<String, Object>();

        service.put("name", SERVICE_NAME);
        service.put("status", "up");

        runtime.put("startedAtEpochMs", Long.valueOf(STARTED_AT_EPOCH_MS));
        runtime.put("currentTimeEpochMs", Long.valueOf(nowEpochMs));
        runtime.put("uptimeMs", Long.valueOf(Math.max(0L, uptimeMs)));
        runtime.put("uptimeSeconds", Long.valueOf(Math.max(0L, uptimeMs / 1000L)));
        runtime.put("pid", parsePid());
        runtime.put("updated_at", updatedAt);
        runtime.put("gateway", gatewaySummary(status, updatedAt));

        result.put("ok", true);
        result.put("status", "ok");
        result.put("platform", SERVICE_NAME);
        result.put("service", service);
        result.put("runtime", runtime);
        result.put("gateway_state", status.get("gateway_state"));
        result.put("platforms", status.get("gateway_platforms"));
        result.put("active_agents", status.get("active_sessions"));
        result.put("pid", runtime.get("pid"));
        result.put("updated_at", updatedAt);
        result.put("gateway", runtime.get("gateway"));
        return result;
    }

    private Map<String, Object> runtimeStatusSnapshot() {
        if (statusService != null) {
            try {
                return withStableRuntimeStatus(statusService.getHealthRuntimeSnapshot());
            } catch (Exception ignored) {
                // Health endpoints must stay available even if runtime state providers fail.
            }
        }
        return withStableRuntimeStatus(null);
    }

    private Map<String, Object> withStableRuntimeStatus(Map<String, Object> status) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (status != null) {
            result.putAll(status);
        }
        if (!result.containsKey("gateway_state")) {
            result.put("gateway_state", null);
        }
        if (!(result.get("gateway_platforms") instanceof Map)) {
            result.put("gateway_platforms", new LinkedHashMap<String, Object>());
        }
        if (!result.containsKey("active_sessions")) {
            result.put("active_sessions", Integer.valueOf(0));
        }
        if (!result.containsKey("gateway_running")) {
            result.put("gateway_running", Boolean.FALSE);
        }
        if (!result.containsKey("gateway_exit_reason")) {
            result.put("gateway_exit_reason", null);
        }
        if (!result.containsKey("gateway_updated_at")) {
            result.put("gateway_updated_at", null);
        }
        return result;
    }

    private Map<String, Object> gatewaySummary(Map<String, Object> status, String fallbackUpdatedAt) {
        Map<String, Object> gateway = new LinkedHashMap<String, Object>();
        gateway.put("state", status.get("gateway_state"));
        gateway.put("running", status.get("gateway_running"));
        gateway.put("platforms", status.get("gateway_platforms"));
        gateway.put("active_agents", status.get("active_sessions"));
        gateway.put("pid", parsePid());
        gateway.put("exit_reason", status.get("gateway_exit_reason"));
        gateway.put("runtime_config_refresh", status.get("runtime_config_refresh"));
        gateway.put(
                "updated_at",
                status.get("gateway_updated_at") == null
                        ? fallbackUpdatedAt
                        : status.get("gateway_updated_at"));
        return gateway;
    }

    private Integer parsePid() {
        try {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int index = runtimeName.indexOf('@');
            String pid = index > 0 ? runtimeName.substring(0, index) : runtimeName;
            return Integer.valueOf(pid);
        } catch (Exception e) {
            return null;
        }
    }

    private String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }
}
