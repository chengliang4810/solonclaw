package com.jimuqu.solon.claw.skillhub.support;

import com.jimuqu.solon.claw.support.SecretRedactor;
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
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_GUARDED_REDIRECTS = 5;

    private final SecurityPolicyService securityPolicyService;
    private final OkHttpClient client;

    public DefaultSkillHubHttpClient() {
        this(null);
    }

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

    private Response executeGet(String url, Map<String, String> headers) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> entry : safeHeaders(headers).entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return executeWithRedirectGuard(builder.build(), url, 0);
    }

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

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private Request redirectRequest(Request request, String currentUrl, String nextUrl, int status) {
        boolean sameOrigin = sameOrigin(currentUrl, nextUrl);
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

    private boolean sameOrigin(String left, String right) {
        try {
            URI a = URI.create(left);
            URI b = URI.create(right);
            return equalsIgnoreCase(a.getScheme(), b.getScheme())
                    && equalsIgnoreCase(a.getHost(), b.getHost())
                    && effectivePort(a) == effectivePort(b);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }

    private String resolveRedirectUrl(String baseUrl, String location) {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Skills Hub HTTP redirect URL is invalid: "
                            + SecretRedactor.maskUrl(location),
                    e);
        }
    }

    private Map<String, String> safeHeaders(Map<String, String> headers) {
        return headers == null ? Collections.<String, String>emptyMap() : headers;
    }

    private String httpFailure(int status, String url) {
        return "HTTP " + status + " for " + SecretRedactor.maskUrl(url);
    }

    private void assertSafeUrl(String url) {
        if (securityPolicyService == null) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
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
