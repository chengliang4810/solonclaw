package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard provider 管理接口。 */
@Controller
public class DashboardProviderController {
    private final DashboardProviderService providerService;

    public DashboardProviderController(DashboardProviderService providerService) {
        this.providerService = providerService;
    }

    @Mapping(value = "/api/providers", method = MethodType.GET)
    public Map<String, Object> providers() {
        return providerService.listProviders();
    }

    @Mapping(value = "/api/jimuqu/models", method = MethodType.GET)
    public Map<String, Object> JimuquModels() {
        return DashboardResponse.ok(providerService.JimuquModels());
    }

    @Mapping(value = "/api/jimuqu/models/health", method = MethodType.GET)
    public Map<String, Object> JimuquModelHealth() {
        return DashboardResponse.ok(providerService.health());
    }

    @Mapping(value = "/api/providers", method = MethodType.POST)
    public Map<String, Object> create(Context context) throws Exception {
        try {
            return providerService.createProvider(body(context));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("PROVIDER_BAD_REQUEST", e.getMessage());
        }
    }

    @Mapping(value = "/api/providers/models", method = MethodType.POST)
    public Map<String, Object> listModels(Context context) throws Exception {
        try {
            return DashboardResponse.ok(
                    providerService.listRemoteModels(body(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("PROVIDER_MODELS_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(502);
            return DashboardResponse.error("PROVIDER_MODELS_FETCH_FAILED", e.getMessage());
        }
    }

    @Mapping(value = "/api/providers/{providerKey}", method = MethodType.PUT)
    public Map<String, Object> update(String providerKey, Context context) throws Exception {
        try {
            return providerService.updateProvider(providerKey, body(context));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("PROVIDER_BAD_REQUEST", e.getMessage());
        }
    }

    @Mapping(value = "/api/providers/{providerKey}", method = MethodType.DELETE)
    public Map<String, Object> delete(String providerKey, Context context) {
        try {
            return providerService.deleteProvider(providerKey);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("PROVIDER_BAD_REQUEST", e.getMessage());
        }
    }

    @Mapping(value = "/api/model/default", method = MethodType.PUT)
    public Map<String, Object> updateDefault(Context context) throws Exception {
        try {
            Map<String, Object> body = body(context);
            return providerService.updateDefaultModel(
                    body.get("providerKey") == null ? "" : String.valueOf(body.get("providerKey")),
                    body.get("model") == null ? "" : String.valueOf(body.get("model")));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("PROVIDER_BAD_REQUEST", e.getMessage());
        }
    }

    @Mapping(value = "/api/model/fallbacks", method = MethodType.PUT)
    public Map<String, Object> updateFallbacks(Context context) throws Exception {
        try {
            Map<String, Object> body = body(context);
            Object items = body.get("fallbackProviders");
            return providerService.updateFallbackProviders(
                    items instanceof List ? (List<Map<String, Object>>) items : null);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("PROVIDER_BAD_REQUEST", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Object> body(Context context) {
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
            Object data = ONode.ofJson(raw).toData();
            if (data instanceof Map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) data);
            }
            throw new IllegalArgumentException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }
}
