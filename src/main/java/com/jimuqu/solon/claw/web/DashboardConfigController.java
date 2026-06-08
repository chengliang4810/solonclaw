package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 配置接口。 */
@Controller
public class DashboardConfigController {
    /** 注入配置服务，用于调用对应业务能力。 */
    private final DashboardConfigService configService;

    /**
     * 创建控制台配置控制器实例，并注入运行所需依赖。
     *
     * @param configService 配置Service配置对象。
     */
    public DashboardConfigController(DashboardConfigService configService) {
        this.configService = configService;
    }

    /**
     * 执行配置相关逻辑。
     *
     * @return 返回配置。
     */
    @Mapping(value = "/api/config", method = MethodType.GET)
    public Map<String, Object> config() {
        return DashboardResponse.ok(configService.getConfig());
    }

    /**
     * 执行defaults相关逻辑。
     *
     * @return 返回defaults结果。
     */
    @Mapping(value = "/api/config/defaults", method = MethodType.GET)
    public Map<String, Object> defaults() {
        return DashboardResponse.ok(configService.getDefaults());
    }

    /**
     * 执行结构相关逻辑。
     *
     * @return 返回结构结果。
     */
    @Mapping(value = "/api/config/schema", method = MethodType.GET)
    public Map<String, Object> schema() {
        return DashboardResponse.ok(configService.getSchema());
    }

    /**
     * 执行原始相关逻辑。
     *
     * @return 返回原始结果。
     */
    @Mapping(value = "/api/config/raw", method = MethodType.GET)
    public Map<String, Object> raw() {
        return DashboardResponse.ok(configService.getRaw());
    }

    /**
     * 执行诊断相关逻辑。
     *
     * @return 返回诊断结果。
     */
    @Mapping(value = "/api/config/diagnostics", method = MethodType.GET)
    public Map<String, Object> diagnostics() {
        return DashboardResponse.ok(configService.diagnostics());
    }

    /**
     * 执行save，服务于控制台配置主流程相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回save结果。
     */
    @Mapping(value = "/api/config", method = MethodType.PUT)
    public Map<String, Object> save(Context context) throws Exception {
        return safeConfig(
                context,
                new ConfigAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return configService.saveConfig(
                                ONode.deserialize(
                                        body(context).get("config").toJson(), LinkedHashMap.class));
                    }
                });
    }

    /**
     * 保存原始。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回原始结果。
     */
    @Mapping(value = "/api/config/raw", method = MethodType.PUT)
    public Map<String, Object> saveRaw(Context context) throws Exception {
        return safeConfig(
                context,
                new ConfigAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return configService.saveRaw(body(context).get("yaml_text").getString());
                    }
                });
    }

    /**
     * 生成安全展示用的配置。
     *
     * @param context 当前请求或运行上下文。
     * @param action 操作参数。
     * @return 返回safe配置。
     */
    private Map<String, Object> safeConfig(Context context, ConfigAction action) {
        try {
            return DashboardResponse.ok(action.run());
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("CONFIG_BAD_REQUEST", e.getMessage());
        } catch (Exception e) {
            context.status(400);
            return DashboardResponse.error("CONFIG_BAD_REQUEST", e.getMessage());
        }
    }

    /**
     * 执行正文相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回body结果。
     */
    private ONode body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return new ONode();
        }
        try {
            ONode node = ONode.ofJson(raw);
            Object data = node.toData();
            if (data instanceof Map) {
                return node;
            }
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    /** 定义配置Action的抽象契约，供不同运行时实现保持一致行为。 */
    private interface ConfigAction {
        /**
         * 执行异步任务主体。
         *
         * @return 返回运行结果。
         */
        Map<String, Object> run() throws Exception;
    }
}
