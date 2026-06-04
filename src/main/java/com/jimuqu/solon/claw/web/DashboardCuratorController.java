package com.jimuqu.solon.claw.web;

import java.util.Map;
import java.util.LinkedHashMap;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard Curator endpoints. */
@Controller
public class DashboardCuratorController {
    private final DashboardCuratorService curatorService;

    public DashboardCuratorController(DashboardCuratorService curatorService) {
        this.curatorService = curatorService;
    }

    @Mapping(value = "/api/curator", method = MethodType.GET)
    public Map<String, Object> list(Context context) throws Exception {
        return DashboardResponse.ok(curatorService.list(context.paramAsInt("limit", 20)));
    }

    @Mapping(value = "/api/curator/run", method = MethodType.POST)
    public Map<String, Object> run(Context context) throws Exception {
        return DashboardResponse.ok(
                curatorService.run(Boolean.parseBoolean(context.param("force"))));
    }

    @Mapping(value = "/api/curator/{reportId}", method = MethodType.GET)
    public Map<String, Object> detail(String reportId) throws Exception {
        return DashboardResponse.ok(curatorService.detail(reportId));
    }

    @Mapping(value = "/api/curator/improvements", method = MethodType.GET)
    public Map<String, Object> improvements(Context context) throws Exception {
        return DashboardResponse.ok(curatorService.improvements(context.paramAsInt("limit", 20)));
    }

    @Mapping(value = "/api/curator/apply", method = MethodType.POST)
    public Map<String, Object> apply(Context context) throws Exception {
        return safeCurator(
                context,
                new CuratorAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = body(context);
                        return curatorService.apply(read(body, "skill"), read(body, "suggestion"));
                    }
                });
    }

    @Mapping(value = "/api/curator/ignore", method = MethodType.POST)
    public Map<String, Object> ignore(Context context) throws Exception {
        return safeCurator(
                context,
                new CuratorAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = body(context);
                        return curatorService.ignore(
                                read(body, "skill"), read(body, "suggestion"));
                    }
                });
    }

    private String read(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return ONode.deserialize(node.toJson(), LinkedHashMap.class);
            }
            throw new IllegalArgumentException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    private Map<String, Object> safeCurator(Context context, CuratorAction action) throws Exception {
        try {
            return DashboardResponse.ok(action.run());
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("CURATOR_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("CURATOR_BAD_REQUEST", e.getMessage());
        }
    }

    private interface CuratorAction {
        Map<String, Object> run() throws Exception;
    }
}
