package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
import java.util.List;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard provider 管理接口。 */
@Controller
public class DashboardProviderController {
    /** 注入提供方服务，用于调用对应业务能力。 */
    private final DashboardProviderService providerService;

    /**
     * 创建控制台提供方控制器实例，并注入运行所需依赖。
     *
     * @param providerService 提供方Service标识或键值。
     */
    public DashboardProviderController(DashboardProviderService providerService) {
        this.providerService = providerService;
    }

    /**
     * 执行providers相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回providers结果。
     */
    @Mapping(value = "/api/providers", method = MethodType.GET)
    public Map<String, Object> providers(Context context) {
        try {
            return providerService.listProviders(DashboardProfileContext.requestedProfile(context));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        }
    }

    /**
     * 执行项目Models相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回项目Models结果。
     */
    @Mapping(value = "/api/models", method = MethodType.GET)
    public Map<String, Object> JimuquModels(Context context) {
        try {
            return DashboardResponse.ok(
                    providerService.JimuquModels(
                            DashboardProfileContext.requestedProfile(context)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        }
    }

    /**
     * 执行项目模型健康检查相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回项目模型健康检查结果。
     */
    @Mapping(value = "/api/models/health", method = MethodType.GET)
    public Map<String, Object> JimuquModelHealth(Context context) {
        try {
            return DashboardResponse.ok(
                    providerService.health(DashboardProfileContext.requestedProfile(context)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        }
    }

    /**
     * 执行create，服务于控制台提供方主流程相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回create结果。
     */
    @Mapping(value = "/api/providers", method = MethodType.POST)
    public Map<String, Object> create(Context context) throws Exception {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            return providerService.createProvider(
                    body, DashboardProfileContext.requestedProfile(context, body));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PROVIDER_BAD_REQUEST", e);
        }
    }

    /**
     * 列出Models。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回Models列表。
     */
    @Mapping(value = "/api/providers/models", method = MethodType.POST)
    public Map<String, Object> listModels(Context context) throws Exception {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            return DashboardResponse.ok(
                    providerService.listRemoteModels(
                            body, DashboardProfileContext.requestedProfile(context, body)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PROVIDER_MODELS_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 502, "PROVIDER_MODELS_FETCH_FAILED", e);
        }
    }

    /**
     * 执行validate相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回validate结果。
     */
    @Mapping(value = "/api/providers/validate", method = MethodType.POST)
    public Map<String, Object> validate(Context context) throws Exception {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            return DashboardResponse.ok(
                    providerService.validateProvider(
                            body, DashboardProfileContext.requestedProfile(context, body)));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PROVIDER_VALIDATE_BAD_REQUEST", e);
        }
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param providerKey 提供方键标识或键值。
     * @param context 当前请求或运行上下文。
     * @return 返回更新结果。
     */
    @Mapping(value = "/api/providers/{providerKey}", method = MethodType.PUT)
    public Map<String, Object> update(String providerKey, Context context) throws Exception {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            return providerService.updateProvider(
                    providerKey, body, DashboardProfileContext.requestedProfile(context, body));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PROVIDER_BAD_REQUEST", e);
        }
    }

    /**
     * 执行delete，服务于控制台提供方主流程相关逻辑。
     *
     * @param providerKey 提供方键标识或键值。
     * @param context 当前请求或运行上下文。
     * @return 返回delete结果。
     */
    @Mapping(value = "/api/providers/{providerKey}", method = MethodType.DELETE)
    public Map<String, Object> delete(String providerKey, Context context) {
        try {
            return providerService.deleteProvider(
                    providerKey, DashboardProfileContext.requestedProfile(context));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PROVIDER_BAD_REQUEST", e);
        }
    }

    /**
     * 更新默认。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回默认结果。
     */
    @Mapping(value = "/api/model/default", method = MethodType.PUT)
    public Map<String, Object> updateDefault(Context context) throws Exception {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            return providerService.updateDefaultModel(
                    body.get("providerKey") == null ? "" : String.valueOf(body.get("providerKey")),
                    body.get("model") == null ? "" : String.valueOf(body.get("model")),
                    DashboardProfileContext.requestedProfile(context, body));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PROVIDER_BAD_REQUEST", e);
        }
    }

    /**
     * 更新Fallbacks。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回Fallbacks结果。
     */
    @Mapping(value = "/api/model/fallbacks", method = MethodType.PUT)
    public Map<String, Object> updateFallbacks(Context context) throws Exception {
        try {
            Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
            Object items = body.get("fallbackProviders");
            return providerService.updateFallbackProviders(
                    items instanceof List ? (List<Map<String, Object>>) items : null,
                    DashboardProfileContext.requestedProfile(context, body));
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PROVIDER_BAD_REQUEST", e);
        }
    }
}
