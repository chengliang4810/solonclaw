package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供模型 provider 管理工具，复用 Dashboard provider 服务。 */
public class ProviderManageTools {
    /** Dashboard provider 服务，用于复用 provider 校验、模型列表和配置落盘逻辑。 */
    private final DashboardProviderService dashboardProviderService;

    /**
     * 创建模型 provider 管理工具。
     *
     * @param dashboardProviderService Dashboard provider 服务。
     */
    public ProviderManageTools(DashboardProviderService dashboardProviderService) {
        this.dashboardProviderService = dashboardProviderService;
    }

    /**
     * 查询或管理模型 provider。
     *
     * @param action 操作名称。
     * @param providerKey provider 标识。
     * @param model 模型名称。
     * @param bodyJson 请求体 JSON。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "provider_manage",
            description =
                    "Manage LLM providers. Actions: list, models, health, models_health, create, update, delete, default_model, set_default_model, fallbacks, remote_models, provider_models, fetch_models, fetch_model_list, validate.")
    public String providerManage(
            @Param(
                            name = "action",
                            description =
                                    "list, models, health, models_health, create, update, delete, default_model, set_default_model, fallbacks, remote_models, provider_models, fetch_models, fetch_model_list, validate")
                    String action,
            @Param(name = "provider_key", required = false, description = "Provider key")
                    String providerKey,
            @Param(name = "model", required = false, description = "Default model name")
                    String model,
            @Param(name = "body_json", required = false, description = "JSON body for mutations")
                    String bodyJson) {
        try {
            if (dashboardProviderService == null) {
                return ToolResultEnvelope.error("provider service unavailable").toJson();
            }
            Map<String, Object> result = run(action, providerKey, model, bodyJson);
            return ToolResultEnvelope.ok("Provider 管理完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行 provider 管理动作。
     *
     * @param action 操作名称。
     * @param providerKey provider 标识。
     * @param model 模型名称。
     * @param bodyJson 请求体 JSON。
     * @return 返回服务结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> run(
            String action, String providerKey, String model, String bodyJson) {
        String normalized =
                action == null ? "list" : action.trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> body = body(bodyJson);
        if ("models".equals(normalized)) {
            return dashboardProviderService.JimuquModels();
        }
        if ("health".equals(normalized) || "models_health".equals(normalized)) {
            return dashboardProviderService.health();
        }
        if ("create".equals(normalized)) {
            return dashboardProviderService.createProvider(body);
        }
        if ("update".equals(normalized)) {
            return dashboardProviderService.updateProvider(providerKey, body);
        }
        if ("delete".equals(normalized)) {
            return dashboardProviderService.deleteProvider(providerKey);
        }
        if ("default_model".equals(normalized) || "set_default_model".equals(normalized)) {
            return dashboardProviderService.updateDefaultModel(providerKey, model);
        }
        if ("fallbacks".equals(normalized)) {
            Object items = body.get("fallbackProviders");
            return dashboardProviderService.updateFallbackProviders(
                    items instanceof List ? (List<Map<String, Object>>) items : null);
        }
        if ("remote_models".equals(normalized)
                || "provider_models".equals(normalized)
                || "fetch_models".equals(normalized)
                || "fetch_model_list".equals(normalized)) {
            return dashboardProviderService.listRemoteModels(body);
        }
        if ("validate".equals(normalized)) {
            return dashboardProviderService.validateProvider(body);
        }
        return dashboardProviderService.listProviders();
    }

    /**
     * 解析 provider 管理请求体 JSON。
     *
     * @param bodyJson 请求体 JSON。
     * @return 返回请求体 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String bodyJson) {
        if (bodyJson == null || bodyJson.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return ONode.deserialize(bodyJson, LinkedHashMap.class);
    }
}
