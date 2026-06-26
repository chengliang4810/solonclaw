package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.support.RuntimeProcessSupport;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 健康检查控制器。 */
@Controller
public class HealthController {
    /** 记录健康检查状态采集降级原因，接口仍保持可用兜底响应。 */
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** 服务名称的统一常量值。 */
    private static final String SERVICE_NAME = "solonclaw";

    /** 健康检查时间输出格式，保持原有本地时区偏移语义。 */
    private static final DateTimeFormatter ISO_OFFSET_SECONDS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** STARTED时间EPOCHMS的统一常量值。 */
    private static final long STARTED_AT_EPOCH_MS = System.currentTimeMillis();

    /** STARTED时间NANOS的统一常量值。 */
    private static final long STARTED_AT_NANOS = System.nanoTime();

    /** 注入状态服务，用于调用对应业务能力。 */
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
        runtime.put("pid", RuntimeProcessSupport.currentPid());
        runtime.put("updated_at", updatedAt);
        runtime.put("gateway", gatewaySummary(status, updatedAt));
        runtime.put("capabilities", status.get("runtime_capabilities"));
        runtime.put("status_snapshot", status.get("runtime_status"));

        result.put("ok", true);
        result.put("status", "ok");
        result.put("platform", SERVICE_NAME);
        result.put("service", service);
        result.put("runtime", runtime);
        result.put("gateway_state", status.get("gateway_state"));
        result.put("platforms", status.get("gateway_platforms"));
        result.put("active_agents", status.get("running_agent_runs"));
        result.put("pid", runtime.get("pid"));
        result.put("updated_at", updatedAt);
        result.put("gateway", runtime.get("gateway"));
        result.put("runtime_capabilities", status.get("runtime_capabilities"));
        result.put("runtime_status", status.get("runtime_status"));
        return result;
    }

    /**
     * 执行运行时状态Snapshot相关逻辑。
     *
     * @return 返回运行时状态Snapshot结果。
     */
    private Map<String, Object> runtimeStatusSnapshot() {
        if (statusService != null) {
            try {
                return withStableRuntimeStatus(statusService.getHealthRuntimeSnapshot());
            } catch (Exception e) {
                log.debug("健康检查运行时状态采集失败，使用兜底状态: {}", e.toString());
            }
        }
        return withStableRuntimeStatus(null);
    }

    /**
     * 执行withStable运行时状态相关逻辑。
     *
     * @param status 状态参数。
     * @return 返回with Stable运行时状态。
     */
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
        if (!result.containsKey("running_agent_runs")) {
            result.put("running_agent_runs", Integer.valueOf(0));
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
        if (!(result.get("runtime_capabilities") instanceof Map)) {
            result.put("runtime_capabilities", fallbackRuntimeCapabilities());
        }
        if (!(result.get("runtime_status") instanceof Map)) {
            result.put("runtime_status", fallbackRuntimeStatus(result));
        }
        return result;
    }

    /**
     * 执行兜底运行时Capabilities相关逻辑。
     *
     * @return 返回兜底运行时Capabilities结果。
     */
    private Map<String, Object> fallbackRuntimeCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("schema_version", Integer.valueOf(1));
        capabilities.put("service", SERVICE_NAME);
        capabilities.put("dashboard_first", Boolean.TRUE);
        capabilities.put("supported_model_protocols", supportedModelProtocols());
        capabilities.put("supported_channels", supportedChannels());
        return capabilities;
    }

    /**
     * 执行兜底运行时状态相关逻辑。
     *
     * @param status 状态参数。
     * @return 返回兜底运行时状态。
     */
    private Map<String, Object> fallbackRuntimeStatus(Map<String, Object> status) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("schema_version", Integer.valueOf(1));
        snapshot.put("service", SERVICE_NAME);
        snapshot.put("status", "ok");
        snapshot.put("active_sessions", status.get("active_sessions"));
        snapshot.put("running_agent_runs", status.get("running_agent_runs"));
        snapshot.put("gateway", gatewaySummary(status, isoNow()));
        snapshot.put("diagnostics", fallbackDiagnosticsStatus(status));
        return snapshot;
    }

    /**
     * 执行兜底诊断状态相关逻辑。
     *
     * @param status 状态参数。
     * @return 返回兜底诊断状态。
     */
    private Map<String, Object> fallbackDiagnosticsStatus(Map<String, Object> status) {
        Map<String, Object> diagnostics = new LinkedHashMap<String, Object>();
        diagnostics.put("state", "ok");
        diagnostics.put("gateway_state", status.get("gateway_state"));
        diagnostics.put("runtime_refresh_failed", Boolean.FALSE);
        return diagnostics;
    }

    /**
     * 执行supported模型Protocols相关逻辑。
     *
     * @return 返回supported模型Protocols结果。
     */
    private List<String> supportedModelProtocols() {
        return new ArrayList<String>(
                Arrays.asList("openai", "openai-responses", "ollama", "gemini", "anthropic"));
    }

    /**
     * 执行supportedChannels相关逻辑。
     *
     * @return 返回supported Channels结果。
     */
    private List<String> supportedChannels() {
        return new ArrayList<String>(
                Arrays.asList("feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao"));
    }

    /**
     * 执行消息网关摘要相关逻辑。
     *
     * @param status 状态参数。
     * @param fallbackUpdatedAt 兜底UpdatedAt参数。
     * @return 返回消息网关Summary结果。
     */
    private Map<String, Object> gatewaySummary(
            Map<String, Object> status, String fallbackUpdatedAt) {
        Map<String, Object> gateway = new LinkedHashMap<String, Object>();
        gateway.put("state", status.get("gateway_state"));
        gateway.put("running", status.get("gateway_running"));
        gateway.put("platforms", status.get("gateway_platforms"));
        gateway.put("active_agents", status.get("running_agent_runs"));
        gateway.put("recent_active_sessions", status.get("active_sessions"));
        gateway.put("pid", RuntimeProcessSupport.currentPid());
        gateway.put("exit_reason", status.get("gateway_exit_reason"));
        gateway.put("workspace_config_refresh", status.get("workspace_config_refresh"));
        gateway.put(
                "updated_at",
                status.get("gateway_updated_at") == null
                        ? fallbackUpdatedAt
                        : status.get("gateway_updated_at"));
        return gateway;
    }

    /**
     * 执行isoNow相关逻辑。
     *
     * @return 返回iso Now结果。
     */
    private String isoNow() {
        return ZonedDateTime.now(ZoneId.systemDefault()).format(ISO_OFFSET_SECONDS_FORMATTER);
    }
}
