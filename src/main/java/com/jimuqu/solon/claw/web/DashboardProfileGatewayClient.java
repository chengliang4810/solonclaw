package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.profile.ProfileGatewayStatus;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.noear.snack4.ONode;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.UploadedFile;

/** 将非当前 Profile 的有状态 Dashboard 操作转发到其独立本机网关。 */
public final class DashboardProfileGatewayClient {
    /** JSON 请求正文类型。 */
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 普通 Profile API 使用的有界 HTTP 客户端。 */
    private static final OkHttpClient JSON_CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(5L, TimeUnit.SECONDS)
                    .readTimeout(30L, TimeUnit.SECONDS)
                    .writeTimeout(30L, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();

    /** SSE 使用的无读取超时客户端，运行结束由目标网关关闭响应。 */
    private static final OkHttpClient STREAM_CLIENT =
            JSON_CLIENT.newBuilder().readTimeout(0L, TimeUnit.MILLISECONDS).build();

    /** 固定为本机回环地址和目标 Profile 独立端口的基础 URL。 */
    private final HttpUrl baseUrl;

    /** 目标 Profile 的 Dashboard Bearer Token；只进入请求头，不写日志。 */
    private final String token;

    /** 目标 Profile 名，用于低敏错误说明。 */
    private final String profile;

    /**
     * 根据已校验的 Profile Scope 创建本机网关客户端。
     *
     * @param profileContext Profile 请求上下文。
     * @param scope 非当前 Profile Scope。
     * @param incomingAuthorization 原请求 Authorization，配置令牌为空时作为显式回退。
     */
    public DashboardProfileGatewayClient(
            DashboardProfileContext profileContext,
            DashboardProfileContext.Scope scope,
            String incomingAuthorization) {
        if (profileContext == null || scope == null || scope.isCurrent()) {
            throw new IllegalArgumentException(
                    "Profile gateway client requires a non-current profile.");
        }
        ProfileGatewayStatus status = profileContext.gatewayStatus(scope);
        if (!status.isRunning() || status.getPort() == null) {
            throw new DashboardProfileGatewayException(
                    409,
                    "PROFILE_GATEWAY_NOT_RUNNING",
                    "Profile '" + scope.getName() + "' gateway is not running.");
        }
        this.baseUrl =
                new HttpUrl.Builder()
                        .scheme("http")
                        .host("127.0.0.1")
                        .port(status.getPort().intValue())
                        .build();
        this.profile = scope.getName();
        String configured =
                scope.getConfig() == null || scope.getConfig().getDashboard() == null
                        ? ""
                        : StrUtil.nullToEmpty(scope.getConfig().getDashboard().getAccessToken())
                                .trim();
        this.token = StrUtil.isNotBlank(configured) ? configured : bearer(incomingAuthorization);
        if (StrUtil.isBlank(this.token)) {
            throw new DashboardProfileGatewayException(
                    409,
                    "PROFILE_GATEWAY_AUTH_UNAVAILABLE",
                    "Profile '" + scope.getName() + "' gateway access token is not configured.");
        }
    }

    /** 测试专用构造路径，仍只接受调用方提供的本机 HTTP 地址。 */
    DashboardProfileGatewayClient(String endpoint, String token, String profile) {
        HttpUrl parsed = HttpUrl.parse(endpoint);
        if (parsed == null
                || !"http".equals(parsed.scheme())
                || !"127.0.0.1".equals(parsed.host())) {
            throw new IllegalArgumentException(
                    "Profile gateway test endpoint must use 127.0.0.1 HTTP.");
        }
        this.baseUrl = parsed;
        this.token = StrUtil.nullToEmpty(token).trim();
        this.profile = StrUtil.blankToDefault(profile, "test");
    }

    /**
     * 转发无正文 JSON 请求并解包目标 Dashboard 响应。
     *
     * @param method HTTP 方法。
     * @param query 查询参数。
     * @param pathSegments 路径段。
     * @return 目标接口数据。
     */
    public Map<String, Object> request(
            String method, Map<String, String> query, String... pathSegments) {
        return request(method, query, null, pathSegments);
    }

    /**
     * 转发 JSON 请求并解包目标 Dashboard 响应。
     *
     * @param method HTTP 方法。
     * @param query 查询参数。
     * @param body JSON 对象正文；空值表示无正文。
     * @param pathSegments 路径段。
     * @return 目标接口数据。
     */
    public Map<String, Object> request(
            String method,
            Map<String, String> query,
            Map<String, Object> body,
            String... pathSegments) {
        Request.Builder request = authorized(url(query, pathSegments));
        String normalized = StrUtil.nullToEmpty(method).trim().toUpperCase();
        RequestBody requestBody =
                body == null ? null : RequestBody.create(JSON, ONode.serialize(body));
        if ("GET".equals(normalized)) {
            request.get();
        } else if ("DELETE".equals(normalized) && requestBody == null) {
            request.delete();
        } else {
            if (requestBody == null) {
                requestBody = RequestBody.create(JSON, "{}");
            }
            request.method(normalized, requestBody);
        }
        Response response = execute(JSON_CLIENT, request.build());
        try {
            String text = response.body() == null ? "" : response.body().string();
            return decode(response.code(), text);
        } catch (DashboardProfileGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw failed("read response", e);
        } finally {
            response.close();
        }
    }

    /**
     * 转发 multipart 上传，使附件写入目标 Profile 的缓存目录。
     *
     * @param files Solon 上传文件。
     * @return 目标附件引用。
     */
    public Map<String, Object> upload(UploadedFile[] files) {
        MultipartBody.Builder multipart = new MultipartBody.Builder().setType(MultipartBody.FORM);
        int accepted = 0;
        if (files != null) {
            for (UploadedFile file : files) {
                if (file == null) {
                    continue;
                }
                try {
                    if (file.isEmpty()) {
                        continue;
                    }
                    MediaType mediaType =
                            MediaType.parse(
                                    StrUtil.blankToDefault(
                                            file.getContentType(), "application/octet-stream"));
                    multipart.addFormDataPart(
                            "file",
                            StrUtil.blankToDefault(file.getName(), "upload.bin"),
                            RequestBody.create(mediaType, file.getContentAsBytes()));
                    accepted++;
                } catch (Exception e) {
                    throw new DashboardProfileGatewayException(
                            400,
                            "PROFILE_UPLOAD_FAILED",
                            "Failed to read uploaded file for profile '" + profile + "'.",
                            e);
                }
            }
        }
        if (accepted == 0) {
            throw new IllegalArgumentException("至少上传一个文件。");
        }
        Request request =
                authorized(url(Collections.<String, String>emptyMap(), "api", "chat", "uploads"))
                        .post(multipart.build())
                        .build();
        Response response = execute(JSON_CLIENT, request);
        try {
            String text = response.body() == null ? "" : response.body().string();
            return decode(response.code(), text);
        } catch (DashboardProfileGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw failed("read upload response", e);
        } finally {
            response.close();
        }
    }

    /**
     * 将目标 Profile 的 SSE 字节流原样写回当前 Dashboard 响应。
     *
     * @param context 当前 HTTP 响应上下文。
     * @param pathSegments 目标路径段。
     */
    public void stream(Context context, String... pathSegments) throws Exception {
        Request request =
                authorized(url(Collections.<String, String>emptyMap(), pathSegments)).get().build();
        Response response = execute(STREAM_CLIENT, request);
        try {
            if (!response.isSuccessful()) {
                String text = response.body() == null ? "" : response.body().string();
                decode(response.code(), text);
                return;
            }
            context.status(response.code());
            context.contentType(
                    StrUtil.blankToDefault(
                            response.header("Content-Type"), "text/event-stream;charset=UTF-8"));
            copyHeader(response, context, "Cache-Control");
            copyHeader(response, context, "X-Accel-Buffering");
            ResponseBody body = response.body();
            if (body == null) {
                return;
            }
            InputStream input = body.byteStream();
            OutputStream output = context.outputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                output.flush();
            }
        } finally {
            response.close();
        }
    }

    /** 执行本机请求并把连接失败转换为稳定 Profile 错误。 */
    private Response execute(OkHttpClient client, Request request) {
        try {
            return client.newCall(request).execute();
        } catch (Exception e) {
            throw failed("connect", e);
        }
    }

    /** 构建只包含受控回环地址、端口和路径段的 URL。 */
    private HttpUrl url(Map<String, String> query, String... pathSegments) {
        HttpUrl.Builder builder = baseUrl.newBuilder();
        if (pathSegments != null) {
            for (String segment : pathSegments) {
                builder.addPathSegment(StrUtil.nullToEmpty(segment));
            }
        }
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getValue() != null) {
                    builder.addQueryParameter(entry.getKey(), entry.getValue());
                }
            }
        }
        return builder.build();
    }

    /** 创建携带目标 Profile 令牌的请求。 */
    private Request.Builder authorized(HttpUrl url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, text/event-stream");
    }

    /** 解析目标 Dashboard 的包装或裸 Map 响应。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(int status, String text) {
        Object parsed;
        try {
            parsed =
                    StrUtil.isBlank(text)
                            ? new LinkedHashMap<String, Object>()
                            : ONode.deserialize(text, Object.class);
        } catch (Exception e) {
            if (status >= 200 && status < 300) {
                throw new DashboardProfileGatewayException(
                        502,
                        "PROFILE_GATEWAY_INVALID_RESPONSE",
                        "Profile '" + profile + "' gateway returned invalid JSON.",
                        e);
            }
            throw targetFailure(status, "Profile gateway request failed.");
        }
        if (!(parsed instanceof Map)) {
            throw new DashboardProfileGatewayException(
                    502,
                    "PROFILE_GATEWAY_INVALID_RESPONSE",
                    "Profile '" + profile + "' gateway returned a non-object response.");
        }
        Map<String, Object> map = (Map<String, Object>) parsed;
        if (status < 200 || status >= 300 || Boolean.FALSE.equals(map.get("success"))) {
            throw targetFailure(status, responseMessage(map));
        }
        Object data = map.get("data");
        if (Boolean.TRUE.equals(map.get("success")) && data instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) data);
        }
        return new LinkedHashMap<String, Object>(map);
    }

    /** 从目标错误响应提取低敏说明。 */
    private String responseMessage(Map<String, Object> map) {
        String message = text(map.get("error"));
        if (StrUtil.isBlank(message)) {
            message = text(map.get("detail"));
        }
        if (StrUtil.isBlank(message)) {
            message = text(map.get("message"));
        }
        return StrUtil.blankToDefault(message, "Profile gateway request failed.");
    }

    /** 创建保留目标 HTTP 状态的网关异常。 */
    private DashboardProfileGatewayException targetFailure(int status, String message) {
        if (status == 401 || status == 403) {
            return new DashboardProfileGatewayException(
                    403,
                    "PROFILE_GATEWAY_AUTH_FAILED",
                    "Profile '"
                            + profile
                            + "' gateway rejected the forwarded credential; verify its Dashboard access token.");
        }
        int safeStatus = status >= 400 && status <= 599 ? status : 502;
        return new DashboardProfileGatewayException(
                safeStatus,
                "PROFILE_GATEWAY_REQUEST_FAILED",
                "Profile '" + profile + "' gateway: " + message);
    }

    /** 创建网络失败异常。 */
    private DashboardProfileGatewayException failed(String action, Throwable cause) {
        return new DashboardProfileGatewayException(
                503,
                "PROFILE_GATEWAY_UNAVAILABLE",
                "Profile '" + profile + "' gateway could not " + action + ".",
                cause);
    }

    /** 复制目标响应中允许透传的单个头。 */
    private void copyHeader(Response response, Context context, String name) {
        String value = response.header(name);
        if (StrUtil.isNotBlank(value)) {
            context.headerSet(name, value);
        }
    }

    /** 从 Bearer Authorization 头提取原始令牌。 */
    private static String bearer(String authorization) {
        String value = StrUtil.nullToEmpty(authorization).trim();
        if (value.length() < 7 || !"bearer ".equalsIgnoreCase(value.substring(0, 7))) {
            return "";
        }
        return value.substring(7).trim();
    }

    /** 将可选响应字段转换为文本。 */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
