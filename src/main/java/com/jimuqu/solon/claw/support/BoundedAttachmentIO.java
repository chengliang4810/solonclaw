package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 承载受限附件IO相关状态和辅助逻辑。 */
public final class BoundedAttachmentIO {
    /** 记录附件下载头解析失败的低敏诊断日志，不输出 URL 或响应正文。 */
    private static final Logger log = LoggerFactory.getLogger(BoundedAttachmentIO.class);

    /** 默认最大字节的统一常量值。 */
    public static final long DEFAULT_MAX_BYTES = 32L * 1024L * 1024L;

    /** 更新JAR最大字节的统一常量值。 */
    public static final long UPDATE_JAR_MAX_BYTES = 200L * 1024L * 1024L;

    /** JSON最大字节的统一常量值。 */
    public static final long JSON_MAX_BYTES = 1024L * 1024L;

    /** 最大受控REDIRECTS的统一常量值。 */
    private static final int MAX_GUARDED_REDIRECTS = 5;

    /** 错误预览最大字节的统一常量值。 */
    private static final int ERROR_PREVIEW_MAX_BYTES = 4096;

    /** 错误预览最大CHARS的统一常量值。 */
    private static final int ERROR_PREVIEW_MAX_CHARS = 1000;

    /** 创建受限附件IO实例。 */
    private BoundedAttachmentIO() {}

    /**
     * 构建当前策略配置摘要。
     *
     * @return 返回策略Summary结果。
     */
    public static Map<String, Object> policySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("hutoolDownloadGuarded", Boolean.TRUE);
        summary.put("okHttpDownloadGuarded", Boolean.TRUE);
        summary.put("initialUrlChecked", Boolean.TRUE);
        summary.put("redirectUrlCheckedBeforeFollow", Boolean.TRUE);
        summary.put("manualRedirectHandling", Boolean.TRUE);
        summary.put("maxRedirects", Integer.valueOf(MAX_GUARDED_REDIRECTS));
        summary.put("redirectLocationRequired", Boolean.TRUE);
        summary.put("redirectUrlResolvedAgainstCurrentUrl", Boolean.TRUE);
        summary.put("crossHostHeaderForwardingBlocked", Boolean.TRUE);
        summary.put("sameOriginHeadersAllowed", Boolean.TRUE);
        summary.put("blockedUrlMasked", Boolean.TRUE);
        summary.put("contentLengthChecked", Boolean.TRUE);
        summary.put("streamReadBounded", Boolean.TRUE);
        summary.put("defaultMaxBytes", Long.valueOf(DEFAULT_MAX_BYTES));
        summary.put("jsonMaxBytes", Long.valueOf(JSON_MAX_BYTES));
        summary.put("updateJarMaxBytes", Long.valueOf(UPDATE_JAR_MAX_BYTES));
        summary.put("contentTypeCaptured", Boolean.TRUE);
        return summary;
    }

    /**
     * 执行downloadHutool相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param timeoutMillis timeoutMillis 参数。
     * @param maxBytes max字节参数。
     * @return 返回download Hutool结果。
     */
    public static byte[] downloadHutool(String url, int timeoutMillis, long maxBytes) {
        HttpResponse response = HttpRequest.get(url).timeout(timeoutMillis).executeAsync();
        try {
            if (response.getStatus() >= 400) {
                throw new IllegalStateException(
                        safeHutoolHttpError(response, response.getStatus()));
            }
            return readHutoolResponse(response, maxBytes);
        } finally {
            response.close();
        }
    }

    /**
     * 执行downloadHutool相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param timeoutMillis timeoutMillis 参数。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回download Hutool结果。
     */
    public static byte[] downloadHutool(
            String url,
            int timeoutMillis,
            long maxBytes,
            SecurityPolicyService securityPolicyService) {
        return downloadHutoolResult(url, timeoutMillis, maxBytes, securityPolicyService).getData();
    }

    /**
     * 执行downloadHutool结果相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param timeoutMillis timeoutMillis 参数。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回download Hutool结果。
     */
    public static HutoolDownloadResult downloadHutoolResult(
            String url,
            int timeoutMillis,
            long maxBytes,
            SecurityPolicyService securityPolicyService) {
        return downloadHutoolResult(url, timeoutMillis, maxBytes, securityPolicyService, null);
    }

    /**
     * 执行downloadHutool结果相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param timeoutMillis timeoutMillis 参数。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @param headers headers 参数。
     * @return 返回download Hutool结果。
     */
    public static HutoolDownloadResult downloadHutoolResult(
            String url,
            int timeoutMillis,
            long maxBytes,
            SecurityPolicyService securityPolicyService,
            Map<String, String> headers) {
        if (securityPolicyService == null) {
            HttpResponse response =
                    applyHeaders(HttpRequest.get(url).timeout(timeoutMillis), headers)
                            .executeAsync();
            try {
                if (response.getStatus() >= 400) {
                    throw new IllegalStateException(
                            safeHutoolHttpError(response, response.getStatus()));
                }
                return new HutoolDownloadResult(
                        readHutoolResponse(response, maxBytes), response.header("Content-Type"));
            } finally {
                response.close();
            }
        }
        return downloadHutoolWithRedirectGuard(
                url, url, timeoutMillis, maxBytes, securityPolicyService, headers, 0);
    }

    /**
     * 执行downloadHutoolTo文件相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param target target 参数。
     * @param timeoutMillis timeoutMillis 参数。
     * @param maxBytes max字节参数。
     */
    public static void downloadHutoolToFile(
            String url, File target, int timeoutMillis, long maxBytes) {
        byte[] data = downloadHutool(url, timeoutMillis, maxBytes);
        FileUtil.mkParentDirs(target);
        FileUtil.writeBytes(data, target);
    }

    /**
     * 执行downloadHutoolTo文件相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param target target 参数。
     * @param timeoutMillis timeoutMillis 参数。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public static void downloadHutoolToFile(
            String url,
            File target,
            int timeoutMillis,
            long maxBytes,
            SecurityPolicyService securityPolicyService) {
        byte[] data = downloadHutool(url, timeoutMillis, maxBytes, securityPolicyService);
        FileUtil.mkParentDirs(target);
        FileUtil.writeBytes(data, target);
    }

    /**
     * 执行assert安全DownloadURL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public static void assertSafeDownloadUrl(
            String url, SecurityPolicyService securityPolicyService) {
        if (securityPolicyService == null) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkUrlBlockingPrivate(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Attachment download URL blocked: "
                            + SecretRedactor.maskUrl(verdict.getUrl())
                            + " ("
                            + verdict.getMessage()
                            + ")");
        }
    }

    /**
     * 执行downloadHutoolWithRedirect保护相关逻辑。
     *
     * @param initialUrl 待校验或访问的地址参数。
     * @param url 待校验或访问的 URL。
     * @param timeoutMillis timeoutMillis 参数。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回download Hutool With Redirect保护结果。
     */
    private static byte[] downloadHutoolWithRedirectGuard(
            String initialUrl,
            String url,
            int timeoutMillis,
            long maxBytes,
            SecurityPolicyService securityPolicyService,
            int redirectCount) {
        return downloadHutoolWithRedirectGuard(
                        initialUrl,
                        url,
                        timeoutMillis,
                        maxBytes,
                        securityPolicyService,
                        null,
                        redirectCount)
                .getData();
    }

    /**
     * 执行downloadHutoolWithRedirect保护相关逻辑。
     *
     * @param initialUrl 待校验或访问的地址参数。
     * @param url 待校验或访问的 URL。
     * @param timeoutMillis timeoutMillis 参数。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @param headers headers 参数。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回download Hutool With Redirect保护结果。
     */
    private static HutoolDownloadResult downloadHutoolWithRedirectGuard(
            String initialUrl,
            String url,
            int timeoutMillis,
            long maxBytes,
            SecurityPolicyService securityPolicyService,
            Map<String, String> headers,
            int redirectCount) {
        assertSafeDownloadUrl(url, securityPolicyService);
        HttpResponse response =
                applyHeaders(
                                HttpRequest.get(url)
                                        .timeout(timeoutMillis)
                                        .setFollowRedirects(false),
                                UrlOriginSupport.sameOrigin(initialUrl, url) ? headers : null)
                        .executeAsync();
        try {
            int status = response.getStatus();
            if (HttpRedirectSupport.isRedirectStatus(status)) {
                if (redirectCount >= MAX_GUARDED_REDIRECTS) {
                    throw new IllegalStateException(
                            "Download redirect count exceeds limit: " + MAX_GUARDED_REDIRECTS);
                }
                String location = response.header("Location");
                if (StrUtil.isBlank(location)) {
                    throw new IllegalStateException("Download redirect missing Location header");
                }
                String nextUrl =
                        HttpRedirectSupport.resolveLocation(
                                url, location, "Download redirect URL is invalid");
                return downloadHutoolWithRedirectGuard(
                        initialUrl,
                        nextUrl,
                        timeoutMillis,
                        maxBytes,
                        securityPolicyService,
                        headers,
                        redirectCount + 1);
            }
            if (status >= 400) {
                throw new IllegalStateException(safeHutoolHttpError(response, status));
            }
            return new HutoolDownloadResult(
                    readHutoolResponse(response, maxBytes), response.header("Content-Type"));
        } finally {
            response.close();
        }
    }

    /**
     * 应用Headers。
     *
     * @param request 当前请求对象。
     * @param headers headers 参数。
     * @return 返回apply Headers结果。
     */
    private static HttpRequest applyHeaders(HttpRequest request, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return request;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (StrUtil.isBlank(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            request.header(entry.getKey(), entry.getValue());
        }
        return request;
    }

    /**
     * 读取Hutool Text。
     *
     * @param response 当前响应对象。
     * @param maxBytes max字节参数。
     * @return 返回读取到的Hutool Text。
     */
    public static String readHutoolText(HttpResponse response, long maxBytes) {
        return new String(readHutoolResponse(response, maxBytes), StandardCharsets.UTF_8);
    }

    /**
     * 读取Hutool响应。
     *
     * @param response 当前响应对象。
     * @param maxBytes max字节参数。
     * @return 返回读取到的Hutool响应。
     */
    public static byte[] readHutoolResponse(HttpResponse response, long maxBytes) {
        String lengthHeader = response.header("Content-Length");
        checkContentLength(lengthHeader, maxBytes);
        InputStream stream = response.bodyStream();
        if (stream == null) {
            return new byte[0];
        }
        return readLimited(stream, maxBytes);
    }

    /**
     * 读取Ok HTTP响应。
     *
     * @param response 当前响应对象。
     * @param maxBytes max字节参数。
     * @return 返回读取到的Ok HTTP响应。
     */
    public static byte[] readOkHttpResponse(Response response, long maxBytes) throws Exception {
        ResponseBody body = response.body();
        if (body == null) {
            throw new IllegalStateException("Download body is empty");
        }
        long contentLength = body.contentLength();
        if (contentLength > maxBytes) {
            throw new IllegalStateException("Download exceeds max size: " + contentLength);
        }
        return readLimited(body.byteStream(), maxBytes);
    }

    /**
     * 读取Ok HTTP Text。
     *
     * @param response 当前响应对象。
     * @param maxBytes max字节参数。
     * @return 返回读取到的Ok HTTP Text。
     */
    public static String readOkHttpText(Response response, long maxBytes) throws Exception {
        return new String(readOkHttpResponse(response, maxBytes), StandardCharsets.UTF_8);
    }

    /**
     * 执行downloadOkHTTP相关逻辑。
     *
     * @param client client 参数。
     * @param url 待校验或访问的 URL。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回download Ok HTTP结果。
     */
    public static byte[] downloadOkHttp(
            OkHttpClient client,
            String url,
            long maxBytes,
            SecurityPolicyService securityPolicyService)
            throws Exception {
        return downloadOkHttpResult(client, url, maxBytes, securityPolicyService).getData();
    }

    /**
     * 执行downloadOkHTTP结果相关逻辑。
     *
     * @param client client 参数。
     * @param url 待校验或访问的 URL。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回download Ok HTTP结果。
     */
    public static OkHttpDownloadResult downloadOkHttpResult(
            OkHttpClient client,
            String url,
            long maxBytes,
            SecurityPolicyService securityPolicyService)
            throws Exception {
        if (securityPolicyService == null) {
            Request request = new Request.Builder().url(url).build();
            Response response = client.newCall(request).execute();
            try {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException(safeOkHttpError(response, response.code()));
                }
                return new OkHttpDownloadResult(
                        readOkHttpResponse(response, maxBytes), response.header("Content-Type"));
            } finally {
                response.close();
            }
        }
        return downloadOkHttpWithRedirectGuard(client, url, maxBytes, securityPolicyService, 0);
    }

    /**
     * 执行downloadOkHTTPWithRedirect保护相关逻辑。
     *
     * @param client client 参数。
     * @param url 待校验或访问的 URL。
     * @param maxBytes max字节参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回download Ok HTTP With Redirect保护结果。
     */
    private static OkHttpDownloadResult downloadOkHttpWithRedirectGuard(
            OkHttpClient client,
            String url,
            long maxBytes,
            SecurityPolicyService securityPolicyService,
            int redirectCount)
            throws Exception {
        assertSafeDownloadUrl(url, securityPolicyService);
        OkHttpClient guardedClient =
                client.newBuilder().followRedirects(false).followSslRedirects(false).build();
        Request request = new Request.Builder().url(url).build();
        Response response = guardedClient.newCall(request).execute();
        try {
            int status = response.code();
            if (HttpRedirectSupport.isRedirectStatus(status)) {
                if (redirectCount >= MAX_GUARDED_REDIRECTS) {
                    throw new IllegalStateException(
                            "Download redirect count exceeds limit: " + MAX_GUARDED_REDIRECTS);
                }
                String location = response.header("Location");
                if (StrUtil.isBlank(location)) {
                    throw new IllegalStateException("Download redirect missing Location header");
                }
                String nextUrl =
                        HttpRedirectSupport.resolveLocation(
                                url, location, "Download redirect URL is invalid");
                return downloadOkHttpWithRedirectGuard(
                        client, nextUrl, maxBytes, securityPolicyService, redirectCount + 1);
            }
            if (!response.isSuccessful()) {
                throw new IllegalStateException(safeOkHttpError(response, status));
            }
            return new OkHttpDownloadResult(
                    readOkHttpResponse(response, maxBytes), response.header("Content-Type"));
        } finally {
            response.close();
        }
    }

    /** 表示OkHTTPDownload结果，携带调用方后续判断所需信息。 */
    public static final class OkHttpDownloadResult {
        /** 记录OkHTTPDownload中的数据。 */
        private final byte[] data;

        /** 记录OkHTTPDownload中的content类型。 */
        private final String contentType;

        /**
         * 创建Ok HTTP Download结果实例，并注入运行所需依赖。
         *
         * @param data 数据参数。
         * @param contentType content类型参数。
         */
        private OkHttpDownloadResult(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }

        /**
         * 读取Data。
         *
         * @return 返回读取到的Data。
         */
        public byte[] getData() {
            return data;
        }

        /**
         * 读取Content类型。
         *
         * @return 返回读取到的Content类型。
         */
        public String getContentType() {
            return contentType;
        }
    }

    /** 表示HutoolDownload结果，携带调用方后续判断所需信息。 */
    public static final class HutoolDownloadResult {
        /** 记录HutoolDownload中的数据。 */
        private final byte[] data;

        /** 记录HutoolDownload中的content类型。 */
        private final String contentType;

        /**
         * 创建Hutool Download结果实例，并注入运行所需依赖。
         *
         * @param data 数据参数。
         * @param contentType content类型参数。
         */
        private HutoolDownloadResult(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }

        /**
         * 读取Data。
         *
         * @return 返回读取到的Data。
         */
        public byte[] getData() {
            return data;
        }

        /**
         * 读取Content类型。
         *
         * @return 返回读取到的Content类型。
         */
        public String getContentType() {
            return contentType;
        }
    }

    /**
     * 检查Content Length。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxBytes max字节参数。
     */
    private static void checkContentLength(String value, long maxBytes) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        try {
            long length = Long.parseLong(value.trim());
            if (length > maxBytes) {
                throw new IllegalStateException("Download exceeds max size: " + length);
            }
        } catch (NumberFormatException e) {
            log.debug("附件Content-Length解析失败，跳过头部大小预判 error={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 生成安全展示用的HutoolHTTP错误。
     *
     * @param response 当前响应对象。
     * @param status 状态参数。
     * @return 返回safe Hutool HTTP Error结果。
     */
    private static String safeHutoolHttpError(HttpResponse response, int status) {
        if (response == null) {
            return "Download failed, HTTP " + status;
        }
        return safeHttpError(
                status,
                InputStreamPreviewSupport.readUtf8(response.bodyStream(), ERROR_PREVIEW_MAX_BYTES));
    }

    /**
     * 生成安全展示用的OkHTTP错误。
     *
     * @param response 当前响应对象。
     * @param status 状态参数。
     * @return 返回safe Ok HTTP Error结果。
     */
    private static String safeOkHttpError(Response response, int status) {
        if (response == null || response.body() == null) {
            return "Download failed, HTTP " + status;
        }
        return safeHttpError(
                status,
                InputStreamPreviewSupport.readUtf8(
                        response.body().byteStream(), ERROR_PREVIEW_MAX_BYTES));
    }

    /**
     * 生成安全展示用的HTTP错误。
     *
     * @param status 状态参数。
     * @param preview 预览参数。
     * @return 返回safe HTTP Error结果。
     */
    private static String safeHttpError(int status, String preview) {
        String message = "Download failed, HTTP " + status;
        if (StrUtil.isBlank(preview)) {
            return message;
        }
        String safe = preview.replace('\r', ' ').replace('\n', ' ').trim();
        safe = SecretRedactor.redact(safe, ERROR_PREVIEW_MAX_CHARS);
        if (StrUtil.isBlank(safe)) {
            return message;
        }
        return message + ", response preview: " + safe;
    }

    /**
     * 读取Limited。
     *
     * @param stream 流参数。
     * @param maxBytes max字节参数。
     * @return 返回读取到的Limited。
     */
    private static byte[] readLimited(InputStream stream, long maxBytes) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException("Download exceeds max size: " + total);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read bounded stream: " + e.getMessage(), e);
        }
    }
}
