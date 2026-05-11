package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard Agents 管理接口。 */
@Controller
public class DashboardAgentController {
    private final DashboardAgentService agentService;

    public DashboardAgentController(DashboardAgentService agentService) {
        this.agentService = agentService;
    }

    @Mapping(value = "/api/agents", method = MethodType.GET)
    public Map<String, Object> agents(Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.list(context.param("session_id"));
                    }
                });
    }

    @Mapping(value = "/api/agents", method = MethodType.POST)
    public Map<String, Object> create(Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.create(body(context));
                    }
                });
    }

    @Mapping(value = "/api/agents/{name}", method = MethodType.GET)
    public Map<String, Object> get(String name, Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.get(name, context.param("session_id"));
                    }
                });
    }

    @Mapping(value = "/api/agents/{name}", method = MethodType.PUT)
    public Map<String, Object> update(String name, Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.update(name, body(context));
                    }
                });
    }

    @Mapping(value = "/api/agents/{name}/activate", method = MethodType.POST)
    public Map<String, Object> activate(String name, Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.activate(name, body(context));
                    }
                });
    }

    @Mapping(value = "/api/agents/{name}", method = MethodType.DELETE)
    public Map<String, Object> delete(String name, Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.delete(name);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) throws Exception {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (StrUtil.isBlank(raw)) {
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

    private Map<String, Object> safeAgent(Context context, AgentAction action) throws Exception {
        try {
            return DashboardResponse.ok(action.run());
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("AGENT_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("AGENT_BAD_REQUEST", e.getMessage());
        }
    }

    private interface AgentAction {
        Map<String, Object> run() throws Exception;
    }
}
