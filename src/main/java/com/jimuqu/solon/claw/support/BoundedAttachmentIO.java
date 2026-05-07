package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded attachment/update IO helpers. */
public final class BoundedAttachmentIO {
    public static final long DEFAULT_MAX_BYTES = 32L * 1024L * 1024L;
    public static final long UPDATE_JAR_MAX_BYTES = 200L * 1024L * 1024L;
    public static final long JSON_MAX_BYTES = 1024L * 1024L;
    private static final int MAX_GUARDED_REDIRECTS = 5;

    private BoundedAttachmentIO() {}

    public static byte[] downloadHutool(String url, int timeoutMillis, long maxBytes) {
        HttpResponse response = HttpRequest.get(url).timeout(timeoutMillis).executeAsync();
        try {
            if (response.getStatus() >= 400) {
                throw new IllegalStateException("Download failed, HTTP " + response.getStatus());
            }
            return readHutoolResponse(response, maxBytes);
        } finally {
            response.close();
        }
    }

    public static byte[] downloadHutool(
            String url,
            int timeoutMillis,
            long maxBytes,
            SecurityPolicyService securityPolicyService) {
        if (securityPolicyService == null) {
            return downloadHutool(url, timeoutMillis, maxBytes);
        }
        return downloadHutoolWithRedirectGuard(
                url, timeoutMillis, maxBytes, securityPolicyService, 0);
    }

    public static void downloadHutoolToFile(
            String url, File target, int timeoutMillis, long maxBytes) {
        byte[] data = downloadHutool(url, timeoutMillis, maxBytes);
        FileUtil.mkParentDirs(target);
        FileUtil.writeBytes(data, target);
    }

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

    public static void assertSafeDownloadUrl(
            String url, SecurityPolicyService securityPolicyService) {
        if (securityPolicyService == null) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Attachment download URL blocked: "
                            + SecretRedactor.maskUrl(verdict.getUrl())
                            + " ("
                            + verdict.getMessage()
                            + ")");
        }
    }

    private static byte[] downloadHutoolWithRedirectGuard(
            String url,
            int timeoutMillis,
            long maxBytes,
            SecurityPolicyService securityPolicyService,
            int redirectCount) {
        assertSafeDownloadUrl(url, securityPolicyService);
        HttpResponse response =
                HttpRequest.get(url).timeout(timeoutMillis).setFollowRedirects(false).executeAsync();
        try {
            int status = response.getStatus();
            if (isRedirect(status)) {
                if (redirectCount >= MAX_GUARDED_REDIRECTS) {
                    throw new IllegalStateException(
                            "Download redirect count exceeds limit: " + MAX_GUARDED_REDIRECTS);
                }
                String location = response.header("Location");
                if (StrUtil.isBlank(location)) {
                    throw new IllegalStateException("Download redirect missing Location header");
                }
                String nextUrl = resolveRedirectUrl(url, location);
                return downloadHutoolWithRedirectGuard(
                        nextUrl,
                        timeoutMillis,
                        maxBytes,
                        securityPolicyService,
                        redirectCount + 1);
            }
            if (status >= 400) {
                throw new IllegalStateException("Download failed, HTTP " + status);
            }
            return readHutoolResponse(response, maxBytes);
        } finally {
            response.close();
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String resolveRedirectUrl(String baseUrl, String location) {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Download redirect URL is invalid: " + SecretRedactor.maskUrl(location), e);
        }
    }

    public static String readHutoolText(HttpResponse response, long maxBytes) {
        return new String(readHutoolResponse(response, maxBytes), StandardCharsets.UTF_8);
    }

    public static byte[] readHutoolResponse(HttpResponse response, long maxBytes) {
        String lengthHeader = response.header("Content-Length");
        checkContentLength(lengthHeader, maxBytes);
        InputStream stream = response.bodyStream();
        if (stream == null) {
            return new byte[0];
        }
        return readLimited(stream, maxBytes);
    }

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

    public static byte[] downloadOkHttp(
            OkHttpClient client,
            String url,
            long maxBytes,
            SecurityPolicyService securityPolicyService)
            throws Exception {
        return downloadOkHttpResult(client, url, maxBytes, securityPolicyService).getData();
    }

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
                    throw new IllegalStateException("Download failed, HTTP " + response.code());
                }
                return new OkHttpDownloadResult(
                        readOkHttpResponse(response, maxBytes), response.header("Content-Type"));
            } finally {
                response.close();
            }
        }
        return downloadOkHttpWithRedirectGuard(client, url, maxBytes, securityPolicyService, 0);
    }

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
            if (isRedirect(status)) {
                if (redirectCount >= MAX_GUARDED_REDIRECTS) {
                    throw new IllegalStateException(
                            "Download redirect count exceeds limit: " + MAX_GUARDED_REDIRECTS);
                }
                String location = response.header("Location");
                if (StrUtil.isBlank(location)) {
                    throw new IllegalStateException("Download redirect missing Location header");
                }
                String nextUrl = resolveRedirectUrl(url, location);
                return downloadOkHttpWithRedirectGuard(
                        client,
                        nextUrl,
                        maxBytes,
                        securityPolicyService,
                        redirectCount + 1);
            }
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Download failed, HTTP " + status);
            }
            return new OkHttpDownloadResult(
                    readOkHttpResponse(response, maxBytes), response.header("Content-Type"));
        } finally {
            response.close();
        }
    }

    public static final class OkHttpDownloadResult {
        private final byte[] data;
        private final String contentType;

        private OkHttpDownloadResult(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }

        public byte[] getData() {
            return data;
        }

        public String getContentType() {
            return contentType;
        }
    }

    private static void checkContentLength(String value, long maxBytes) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        try {
            long length = Long.parseLong(value.trim());
            if (length > maxBytes) {
                throw new IllegalStateException("Download exceeds max size: " + length);
            }
        } catch (NumberFormatException ignored) {
        }
    }

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
