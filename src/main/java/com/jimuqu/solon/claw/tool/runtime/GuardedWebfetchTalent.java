package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.noear.solon.ai.talents.web.WebfetchTalent;

/** 受控网页抓取实现，逐跳校验重定向目标并限制响应体，避免网页工具绕过 URL 安全策略或耗尽内存。 */
public class GuardedWebfetchTalent extends WebfetchTalent {
    /** 网页正文最大字节数，与原有网页工具的 5MB 用户可见限制保持一致。 */
    private static final long MAX_RESPONSE_BYTES = 5L * 1024L * 1024L;

    /** 默认请求超时，单位为毫秒。 */
    private static final int DEFAULT_TIMEOUT_MILLIS = 30000;

    /** 单次网页请求允许的最大超时，单位为毫秒。 */
    private static final int MAX_TIMEOUT_MILLIS = 120000;

    /** 与原网页抓取实现一致的浏览器标识，减少公开站点因默认客户端而拒绝请求。 */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    /** URL 安全策略，在每次初始请求和重定向跳转前进行校验。 */
    private final SecurityPolicyService securityPolicyService;

    /**
     * 创建受控网页抓取实现。
     *
     * @param securityPolicyService 用于逐跳阻断私网和云元数据 URL 的安全策略。
     */
    public GuardedWebfetchTalent(SecurityPolicyService securityPolicyService) {
        if (securityPolicyService == null) {
            throw new IllegalArgumentException("Webfetch requires URL security policy");
        }
        this.securityPolicyService = securityPolicyService;
    }

    /**
     * 读取公开网页正文，并在重定向前和读取过程中执行 URL 与响应体边界控制。
     *
     * @param url 待获取的 HTTP(S) URL。
     * @param format 返回格式，支持 markdown、text 与 html。
     * @param timeoutSeconds 超时秒数，最大 120 秒。
     * @return 已按请求格式转换的网页正文或图片 data URL。
     * @throws Exception URL、重定向、响应状态或响应体超出安全边界时抛出异常。
     */
    @Override
    public String webfetch(String url, String format, Integer timeoutSeconds) throws Exception {
        validateUrl(url);
        String safeFormat =
                StrUtil.blankToDefault(format, "markdown").trim().toLowerCase(Locale.ROOT);
        BoundedAttachmentIO.HutoolDownloadResult response =
                BoundedAttachmentIO.downloadHutoolResult(
                        url,
                        timeoutMillis(timeoutSeconds),
                        MAX_RESPONSE_BYTES,
                        securityPolicyService,
                        Collections.singletonMap("User-Agent", DEFAULT_USER_AGENT));
        return render(response.getData(), response.getContentType(), safeFormat);
    }

    /**
     * 校验网页地址的协议前缀，保持原网页工具只接受 HTTP(S) URL 的契约。
     *
     * @param url 待校验地址。
     */
    private void validateUrl(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
    }

    /**
     * 将工具入参规范为受限毫秒超时，避免整数溢出和无限等待。
     *
     * @param timeoutSeconds 调用方提供的超时秒数。
     * @return 实际使用的毫秒超时。
     */
    private int timeoutMillis(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds.intValue() <= 0) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        long requestedMillis = timeoutSeconds.longValue() * 1000L;
        return (int) Math.min(requestedMillis, (long) MAX_TIMEOUT_MILLIS);
    }

    /**
     * 按网页工具格式契约渲染已受限读取的响应数据。
     *
     * @param data 已限制最大字节数的响应数据。
     * @param contentType 响应内容类型。
     * @param format 请求的输出格式。
     * @return 转换后的网页正文或图片 data URL。
     */
    private String render(byte[] data, String contentType, String format) {
        String normalizedContentType =
                StrUtil.blankToDefault(contentType, "")
                        .split(";", 2)[0]
                        .trim()
                        .toLowerCase(Locale.ROOT);
        if (isBinaryImage(normalizedContentType)) {
            return "data:"
                    + normalizedContentType
                    + ";base64,"
                    + Base64.getEncoder().encodeToString(data == null ? new byte[0] : data);
        }
        String content = new String(data == null ? new byte[0] : data, resolveCharset(contentType));
        if (!"text/html".equals(normalizedContentType)) {
            return content;
        }
        if ("markdown".equals(format)) {
            return FlexmarkHtmlConverter.builder().build().convert(content);
        }
        if ("text".equals(format)) {
            org.jsoup.nodes.Document html = Jsoup.parse(content);
            html.select("script, style, noscript, iframe, object, embed").remove();
            return html.text().trim();
        }
        return content;
    }

    /**
     * 判断响应是否应保持为图片 data URL，保持原网页抓取的图片结果语义。
     *
     * @param contentType 已规范化的 MIME 类型。
     * @return 普通位图图片时返回 true。
     */
    private boolean isBinaryImage(String contentType) {
        return contentType.startsWith("image/")
                && !contentType.contains("svg")
                && !contentType.contains("vnd.fastbidsheet");
    }

    /**
     * 从响应 Content-Type 读取字符集，非法或缺失时安全回退 UTF-8。
     *
     * @param contentType 原始响应内容类型。
     * @return 用于解码文本正文的字符集。
     */
    private Charset resolveCharset(String contentType) {
        if (contentType != null) {
            String[] parts = contentType.split(";");
            for (String part : parts) {
                String value = part.trim();
                if (!value.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
                    continue;
                }
                String charsetName = value.substring("charset=".length()).trim();
                if (charsetName.length() > 1
                        && charsetName.startsWith("\"")
                        && charsetName.endsWith("\"")) {
                    charsetName = charsetName.substring(1, charsetName.length() - 1);
                }
                try {
                    return Charset.forName(charsetName);
                } catch (IllegalCharsetNameException e) {
                    break;
                } catch (UnsupportedCharsetException e) {
                    break;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
