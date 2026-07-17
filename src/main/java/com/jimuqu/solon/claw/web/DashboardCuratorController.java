package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import java.util.Map;
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
     * 查询技能整理器运行状态和 AI 配置摘要。
     *
     * @return 返回整理器状态。
     */
    @Mapping(value = "/api/curator/status", method = MethodType.GET)
    public Map<String, Object> status() {
        return DashboardResponse.ok(curatorService.status());
    }

    /**
     * 暂停后台技能整理。
     *
     * @return 返回暂停后的状态。
     */
    @Mapping(value = "/api/curator/pause", method = MethodType.POST)
    public Map<String, Object> pause() {
        return DashboardResponse.ok(curatorService.pause());
    }

    /**
     * 恢复后台技能整理。
     *
     * @return 返回恢复后的状态。
     */
    @Mapping(value = "/api/curator/resume", method = MethodType.POST)
    public Map<String, Object> resume() {
        return DashboardResponse.ok(curatorService.resume());
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
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
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
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
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
            return DashboardResponse.error(context, 400, "CURATOR_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 400, "CURATOR_BAD_REQUEST", e);
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
