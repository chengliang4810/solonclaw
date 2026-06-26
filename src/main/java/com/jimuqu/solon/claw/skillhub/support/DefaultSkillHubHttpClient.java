package com.jimuqu.solon.claw.skillhub.support;

import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.UrlOriginSupport;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 默认 HTTP 客户端。 */
public class DefaultSkillHubHttpClient implements SkillHubHttpClient {
    /** JSON的统一常量值。 */
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 最大受控REDIRECTS的统一常量值。 */
    private static final int MAX_GUARDED_REDIRECTS = 5;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录默认技能中心HTTPClient中的client。 */
    private final OkHttpClient client;

    /** 创建默认技能中心HTTP Client实例。 */
    public DefaultSkillHubHttpClient() {
        this(null);
    }

    /**
     * 创建默认技能中心HTTP Client实例，并注入运行所需依赖。
     *
     * @param securityPolicyService 安全策略服务依赖。
     */
    public DefaultSkillHubHttpClient(SecurityPolicyService securityPolicyService) {
        this.securityPolicyService = securityPolicyService;
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS);
        if (securityPolicyService != null) {
            builder.followRedirects(false).followSslRedirects(false);
        }
        this.client = builder.build();
    }

    /**
     * 读取Text。
     *
     * @param url 待校验或访问的 URL。
     * @param headers headers 参数。
     * @return 返回读取到的Text。
     */
    @Override
    public String getText(String url, Map<String, String> headers) throws Exception {
        Response response = executeGet(url, headers);
        try {
            if (!response.isSuccessful()) {
                throw new IllegalStateException(httpFailure(response.code(), url));
            }
            return response.body() == null ? "" : response.body().string();
        } finally {
            response.close();
        }
    }

    /**
     * 根据tes读取对应数据。
     *
     * @param url 待校验或访问的 URL。
     * @param headers headers 参数。
     * @return 返回按tes读取得到的结果。
     */
    @Override
    public byte[] getBytes(String url, Map<String, String> headers) throws Exception {
        Response response = executeGet(url, headers);
        try {
            if (!response.isSuccessful()) {
                throw new IllegalStateException(httpFailure(response.code(), url));
            }
            return response.body() == null ? new byte[0] : response.body().bytes();
        } finally {
            response.close();
        }
    }

    /**
     * 执行postJSON相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param headers headers 参数。
     * @param jsonBody JSON正文参数。
     * @return 返回post JSON结果。
     */
    @Override
    public String postJson(String url, Map<String, String> headers, String jsonBody)
            throws Exception {
        RequestBody body = RequestBody.create(jsonBody == null ? "{}" : jsonBody, JSON);
        Request.Builder builder = new Request.Builder().url(url).post(body);
        for (Map.Entry<String, String> entry : safeHeaders(headers).entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        Response response = executeWithRedirectGuard(builder.build(), url, 0);
        try {
            if (!response.isSuccessful()) {
                throw new IllegalStateException(httpFailure(response.code(), url));
            }
            return response.body() == null ? "" : response.body().string();
        } finally {
            response.close();
        }
    }

    /**
     * 执行Get。
     *
     * @param url 待校验或访问的 URL。
     * @param headers headers 参数。
     * @return 返回Get结果。
     */
    private Response executeGet(String url, Map<String, String> headers) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> entry : safeHeaders(headers).entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return executeWithRedirectGuard(builder.build(), url, 0);
    }

    /**
     * 执行With Redirect保护。
     *
     * @param request 当前请求对象。
     * @param url 待校验或访问的 URL。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回With Redirect保护结果。
     */
    private Response executeWithRedirectGuard(Request request, String url, int redirectCount)
            throws Exception {
        assertSafeUrl(url);
        Response response = client.newCall(request).execute();
        if (securityPolicyService == null || !isRedirect(response.code())) {
            return response;
        }
        try {
            if (redirectCount >= MAX_GUARDED_REDIRECTS) {
                throw new IllegalStateException(
                        "Skills Hub HTTP redirect count exceeds limit: " + MAX_GUARDED_REDIRECTS);
            }
            String location = response.header("Location");
            if (location == null || location.trim().length() == 0) {
                throw new IllegalStateException("Skills Hub HTTP redirect missing Location header");
            }
            String nextUrl = resolveRedirectUrl(url, location);
            Request nextRequest = redirectRequest(request, url, nextUrl, response.code());
            return executeWithRedirectGuard(nextRequest, nextUrl, redirectCount + 1);
        } finally {
            response.close();
        }
    }

    /**
     * 判断是否Redirect。
     *
     * @param status 状态参数。
     * @return 如果Redirect满足条件则返回 true，否则返回 false。
     */
    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 执行redirect请求相关逻辑。
     *
     * @param request 当前请求对象。
     * @param currentUrl 待校验或访问的地址参数。
     * @param nextUrl 待校验或访问的地址参数。
     * @param status 状态参数。
     * @return 返回redirect请求结果。
     */
    private Request redirectRequest(
            Request request, String currentUrl, String nextUrl, int status) {
        boolean sameOrigin = UrlOriginSupport.sameOrigin(currentUrl, nextUrl);
        Request.Builder builder =
                sameOrigin ? request.newBuilder().url(nextUrl) : new Request.Builder().url(nextUrl);
        if (status == 303
                || ((status == 301 || status == 302)
                        && !"GET".equalsIgnoreCase(request.method())
                        && !"HEAD".equalsIgnoreCase(request.method()))) {
            builder.get();
        } else if (!sameOrigin) {
            if ("GET".equalsIgnoreCase(request.method())
                    || "HEAD".equalsIgnoreCase(request.method())) {
                builder.method(request.method(), null);
            } else {
                builder.get();
            }
        }
        return builder.build();
    }

    /**
     * 解析Redirect URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param location location 参数。
     * @return 返回解析后的Redirect URL。
     */
    private String resolveRedirectUrl(String baseUrl, String location) {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Skills Hub HTTP redirect URL is invalid: " + SecretRedactor.maskUrl(location),
                    e);
        }
    }

    /**
     * 生成安全展示用的Headers。
     *
     * @param headers headers 参数。
     * @return 返回safe Headers结果。
     */
    private Map<String, String> safeHeaders(Map<String, String> headers) {
        return headers == null ? Collections.<String, String>emptyMap() : headers;
    }

    /**
     * 执行HTTPFailure相关逻辑。
     *
     * @param status 状态参数。
     * @param url 待校验或访问的 URL。
     * @return 返回HTTP Failure结果。
     */
    private String httpFailure(int status, String url) {
        return "HTTP " + status + " for " + SecretRedactor.maskUrl(url);
    }

    /**
     * 执行assert安全URL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     */
    private void assertSafeUrl(String url) {
        if (securityPolicyService == null) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkUrlBlockingPrivate(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Skills Hub HTTP URL blocked by security policy: "
                            + verdict.getMessage()
                            + " ("
                            + SecretRedactor.maskUrl(verdict.getUrl())
                            + ")");
        }
    }
}
