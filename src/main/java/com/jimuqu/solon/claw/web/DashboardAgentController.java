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
    /** 注入Agent服务，用于调用对应业务能力。 */
    private final DashboardAgentService agentService;

    /**
     * 创建控制台Agent控制器实例，并注入运行所需依赖。
     *
     * @param agentService Agent服务依赖。
     */
    public DashboardAgentController(DashboardAgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 执行agents相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回agents结果。
     */
    @Mapping(value = "/api/agents", method = MethodType.GET)
    public Map<String, Object> agents(Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.list(context.param("session_id"));
                    }
                });
    }

    /**
     * 执行create，服务于控制台Agent主流程相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回create结果。
     */
    @Mapping(value = "/api/agents", method = MethodType.POST)
    public Map<String, Object> create(Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.create(body(context));
                    }
                });
    }

    /**
     * 获取当前注册项或配置项。
     *
     * @param name 名称参数。
     * @param context 当前请求或运行上下文。
     * @return 返回get结果。
     */
    @Mapping(value = "/api/agents/{name}", method = MethodType.GET)
    public Map<String, Object> get(String name, Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.get(name, context.param("session_id"));
                    }
                });
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param name 名称参数。
     * @param context 当前请求或运行上下文。
     * @return 返回更新结果。
     */
    @Mapping(value = "/api/agents/{name}", method = MethodType.PUT)
    public Map<String, Object> update(String name, Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.update(name, body(context));
                    }
                });
    }

    /**
     * 执行activate相关逻辑。
     *
     * @param name 名称参数。
     * @param context 当前请求或运行上下文。
     * @return 返回activate结果。
     */
    @Mapping(value = "/api/agents/{name}/activate", method = MethodType.POST)
    public Map<String, Object> activate(String name, Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.activate(name, body(context));
                    }
                });
    }

    /**
     * 执行delete，服务于控制台Agent主流程相关逻辑。
     *
     * @param name 名称参数。
     * @param context 当前请求或运行上下文。
     * @return 返回delete结果。
     */
    @Mapping(value = "/api/agents/{name}", method = MethodType.DELETE)
    public Map<String, Object> delete(String name, Context context) throws Exception {
        return safeAgent(
                context,
                new AgentAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return agentService.delete(name);
                    }
                });
    }

    /**
     * 执行正文相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回body结果。
     */
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
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    /**
     * 生成安全展示用的Agent。
     *
     * @param context 当前请求或运行上下文。
     * @param action 操作参数。
     * @return 返回safe Agent结果。
     */
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

    /** 定义Agent Action的抽象契约，供不同运行时实现保持一致行为。 */
    private interface AgentAction {
        /**
         * 执行异步任务主体。
         *
         * @return 返回运行结果。
         */
        Map<String, Object> run() throws Exception;
    }
}
