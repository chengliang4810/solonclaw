package com.jimuqu.solon.claw.web;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.TuiRuntimeProtocolService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 面向独立终端前端的运行时 JSON-RPC 控制器。 */
@Controller
public class DashboardTuiRuntimeController {
    /** JSON-RPC 协议版本。 */
    private static final String JSON_RPC_VERSION = "2.0";

    /** 终端前端运行时协议服务，复用 CLI/setup 的模型与配置写入规则。 */
    private final TuiRuntimeProtocolService protocolService;

    /**
     * 创建终端运行时控制器。
     *
     * @param appConfig 应用配置，用于定位运行时目录与当前 provider。
     * @param weixinQrSetupService 微信二维码 setup 服务。
     * @param domesticQrSetupService 飞书、钉钉二维码 setup 服务。
     */
    public DashboardTuiRuntimeController(
            AppConfig appConfig,
            WeixinQrSetupService weixinQrSetupService,
            DomesticQrSetupService domesticQrSetupService) {
        this.protocolService =
                new TuiRuntimeProtocolService(
                        appConfig, weixinQrSetupService, domesticQrSetupService);
    }

    /**
     * 处理终端前端的轻量 JSON-RPC 请求。
     *
     * @param context 当前 HTTP 请求上下文。
     * @return JSON-RPC 响应；成功返回 result，失败返回 error。
     */
    @Mapping(value = "/api/tui/rpc", method = MethodType.POST)
    public Map<String, Object> rpc(Context context) {
        RpcRequest request;
        try {
            request = parseRequest(context);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return error(null, -32600, e.getMessage());
        }

        try {
            return ok(request.id, dispatch(request.method, request.params));
        } catch (UnsupportedOperationException e) {
            context.status(400);
            return error(request.id, -32601, e.getMessage());
        } catch (IllegalArgumentException e) {
            context.status(400);
            return error(request.id, -32602, e.getMessage());
        } catch (Exception e) {
            context.status(500);
            return error(request.id, -32000, "TUI runtime RPC failed: " + e.getMessage());
        }
    }

    /** 按方法名分发到共享协议服务。 */
    private Map<String, Object> dispatch(String method, Map<String, Object> params) {
        if ("setup.status".equals(method)) {
            return protocolService.setupStatus();
        }
        if ("model.options".equals(method)) {
            return protocolService.modelOptions(stringParam(params, "session_id"));
        }
        if ("model.save_key".equals(method)) {
            return protocolService.modelSaveKey(
                    stringParam(params, "slug"),
                    stringParam(params, "api_key"),
                    stringParam(params, "session_id"));
        }
        if ("channel.options".equals(method)) {
            return protocolService.channelOptions();
        }
        if ("channel.status".equals(method)) {
            return protocolService.channelStatus(stringParam(params, "channel"));
        }
        if ("channel.save".equals(method)) {
            return protocolService.channelSave(
                    stringParam(params, "channel"),
                    stringMapParam(params, "values"),
                    stringParam(params, "session_id"));
        }
        if ("channel.qr.start".equals(method)) {
            return protocolService.channelQrStart(
                    stringParam(params, "channel"), stringParam(params, "session_id"));
        }
        if ("channel.qr.get".equals(method)) {
            return protocolService.channelQrGet(
                    stringParam(params, "channel"),
                    stringParam(params, "ticket"),
                    stringParam(params, "session_id"));
        }
        if ("config.get".equals(method)) {
            return protocolService.configGet(stringParam(params, "key"));
        }
        throw new UnsupportedOperationException("unknown method: " + method);
    }

    /** 解析并校验 JSON-RPC 请求对象。 */
    @SuppressWarnings("unchecked")
    private RpcRequest parseRequest(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (StrUtil.isBlank(raw)) {
            throw new IllegalArgumentException("请求体不能为空 / Request body must not be empty");
        }
        Object data;
        try {
            data = ONode.ofJson(raw).toData();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
        if (!(data instanceof Map)) {
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        }
        Map<String, Object> body = (Map<String, Object>) data;
        Object methodValue = body.get("method");
        if (!(methodValue instanceof String) || StrUtil.isBlank((String) methodValue)) {
            throw new IllegalArgumentException(
                    "JSON-RPC method 不能为空 / JSON-RPC method must not be empty");
        }
        Object paramsValue = body.get("params");
        Map<String, Object> params;
        if (paramsValue == null) {
            params = new LinkedHashMap<String, Object>();
        } else if (paramsValue instanceof Map) {
            params = new LinkedHashMap<String, Object>((Map<String, Object>) paramsValue);
        } else {
            throw new IllegalArgumentException(
                    "JSON-RPC params 必须是对象 / JSON-RPC params must be an object");
        }
        return new RpcRequest(body.get("id"), ((String) methodValue).trim(), params);
    }

    /** 构造 JSON-RPC 成功响应。 */
    private Map<String, Object> ok(Object id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("result", MapUtil.emptyIfNull(result));
        return response;
    }

    /** 构造 JSON-RPC 错误响应，并统一脱敏错误消息。 */
    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", Integer.valueOf(code));
        error.put("message", SecretRedactor.redact(StrUtil.nullToEmpty(message), 1000));

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    /** 安全读取字符串参数。 */
    private String stringParam(Map<String, Object> params, String key) {
        Object value = MapUtil.emptyIfNull(params).get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 安全读取字符串映射参数，供 TUI 保存渠道配置字段。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> stringMapParam(Map<String, Object> params, String key) {
        Object value = MapUtil.emptyIfNull(params).get(key);
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value == null) {
            return result;
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                    "JSON-RPC " + key + " 必须是对象 / JSON-RPC " + key + " must be an object");
        }
        Map<String, Object> source = (Map<String, Object>) value;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(
                    entry.getKey(),
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }

    /** 已校验的 JSON-RPC 请求。 */
    private static class RpcRequest {
        /** 请求标识，原样回传给前端。 */
        private final Object id;

        /** 方法名。 */
        private final String method;

        /** 参数对象。 */
        private final Map<String, Object> params;

        private RpcRequest(Object id, String method, Map<String, Object> params) {
            this.id = id;
            this.method = method;
            this.params = params;
        }
    }
}
