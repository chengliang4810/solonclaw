package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 执行控制台技能维护相关HTTP入口，负责请求参数转换与响应输出相关逻辑。 */
@Controller
public class DashboardCuratorController {
    /** 注入技能维护服务，用于调用对应业务能力。 */
    private final DashboardCuratorService curatorService;

    /**
     * 创建控制台技能维护控制器实例，并注入运行所需依赖。
     *
     * @param curatorService curator服务依赖。
     */
    public DashboardCuratorController(DashboardCuratorService curatorService) {
        this.curatorService = curatorService;
    }

    /**
     * 执行列表相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回list结果。
     */
    @Mapping(value = "/api/curator", method = MethodType.GET)
    public Map<String, Object> list(Context context) throws Exception {
        return DashboardResponse.ok(curatorService.list(context.paramAsInt("limit", 20)));
    }

    /**
     * 执行异步任务主体。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回运行结果。
     */
    @Mapping(value = "/api/curator/run", method = MethodType.POST)
    public Map<String, Object> run(Context context) throws Exception {
        return DashboardResponse.ok(
                curatorService.run(Boolean.parseBoolean(context.param("force"))));
    }

    /**
     * 执行详情相关逻辑。
     *
     * @param reportId report标识。
     * @return 返回detail结果。
     */
    @Mapping(value = "/api/curator/{reportId}", method = MethodType.GET)
    public Map<String, Object> detail(String reportId) throws Exception {
        return DashboardResponse.ok(curatorService.detail(reportId));
    }

    /**
     * 执行improvements相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回improvements结果。
     */
    @Mapping(value = "/api/curator/improvements", method = MethodType.GET)
    public Map<String, Object> improvements(Context context) throws Exception {
        return DashboardResponse.ok(curatorService.improvements(context.paramAsInt("limit", 20)));
    }

    /**
     * 执行apply相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回apply结果。
     */
    @Mapping(value = "/api/curator/apply", method = MethodType.POST)
    public Map<String, Object> apply(Context context) throws Exception {
        return safeCurator(
                context,
                new CuratorAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = body(context);
                        return curatorService.apply(read(body, "skill"), read(body, "suggestion"));
                    }
                });
    }

    /**
     * 执行忽略相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回忽略结果。
     */
    @Mapping(value = "/api/curator/ignore", method = MethodType.POST)
    public Map<String, Object> ignore(Context context) throws Exception {
        return safeCurator(
                context,
                new CuratorAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = body(context);
                        return curatorService.ignore(read(body, "skill"), read(body, "suggestion"));
                    }
                });
    }

    /**
     * 执行read相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @return 返回read结果。
     */
    private String read(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 执行正文相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回body结果。
     */
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
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    /**
     * 生成安全展示用的技能维护。
     *
     * @param context 当前请求或运行上下文。
     * @param action 操作参数。
     * @return 返回safe技能维护结果。
     */
    private Map<String, Object> safeCurator(Context context, CuratorAction action)
            throws Exception {
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

    /** 定义技能维护Action的抽象契约，供不同运行时实现保持一致行为。 */
    private interface CuratorAction {
        /**
         * 执行异步任务主体。
         *
         * @return 返回运行结果。
         */
        Map<String, Object> run() throws Exception;
    }
}
