package com.jimuqu.solon.claw.tool.runtime;

import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.SENSITIVE_URL_PARAMETER_NAMES;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 封装 URL、主机名和敏感参数文本规范化逻辑，主安全服务只保留策略编排。 */
final class SecurityUrlTextSupport {
    /** URL 文本解析日志，日志内容只输出脱敏异常摘要。 */
    private static final Logger log = LoggerFactory.getLogger(SecurityUrlTextSupport.class);

    private SecurityUrlTextSupport() {}

/**
 * 规范化Rule。
 *
 * @param raw 原始输入值。
 * @return 返回Rule结果。
 */
static String normalizeRule(String raw) {
    String value = normalizeUrlText(raw).toLowerCase(Locale.ROOT);
    if (value.length() == 0 || value.startsWith("#")) {
        return "";
    }
    value = stripInlineRuleComment(value);
    if (value.contains("://")) {
        URI uri = parseUri(value);
        value = uri == null ? value : extractUriHost(uri);
    }
    int slash = value.indexOf('/');
    if (slash >= 0) {
        value = value.substring(0, slash);
    }
    value = stripRulePort(value);
    value = normalizeHost(value);
    return value.startsWith("www.") ? value.substring(4) : value;
}

/**
 * 剥离内联RuleComment。
 *
 * @param value 待规范化或校验的原始值。
 * @return 返回strip Inline Rule Comment结果。
 */
private static String stripInlineRuleComment(String value) {
    for (int i = 1; i < value.length(); i++) {
        if (value.charAt(i) == '#' && Character.isWhitespace(value.charAt(i - 1))) {
            return value.substring(0, i).trim();
        }
    }
    return value;
}

/**
 * 剥离Rule端口。
 *
 * @param value 待规范化或校验的原始值。
 * @return 返回strip Rule Port结果。
 */
private static String stripRulePort(String value) {
    if (StrUtil.isBlank(value) || value.startsWith("[") || value.indexOf(':') < 0) {
        return value;
    }
    int colon = value.lastIndexOf(':');
    if (colon <= 0 || value.indexOf(':') != colon || colon + 1 >= value.length()) {
        return value;
    }
    for (int i = colon + 1; i < value.length(); i++) {
        if (!Character.isDigit(value.charAt(i))) {
            return value;
        }
    }
    return value.substring(0, colon);
}

/**
 * 提取Schemeless Host。
 *
 * @param raw 原始输入值。
 * @return 返回Schemeless Host结果。
 */
static String extractSchemelessHost(String raw) {
    String value = normalizeUrlText(raw);
    if (value.contains("://")) {
        return "";
    }
    int slash = value.indexOf('/');
    if (slash >= 0) {
        value = value.substring(0, slash);
    }
    int question = value.indexOf('?');
    if (question >= 0) {
        value = value.substring(0, question);
    }
    int hash = value.indexOf('#');
    if (hash >= 0) {
        value = value.substring(0, hash);
    }
    int at = value.lastIndexOf('@');
    if (at >= 0) {
        value = value.substring(at + 1);
    }
    if (value.startsWith("[") && value.contains("]")) {
        return normalizeHost(value.substring(0, value.indexOf(']') + 1));
    }
    int colon = value.lastIndexOf(':');
    if (colon > 0 && value.indexOf(':') == colon) {
        value = value.substring(0, colon);
    }
    return normalizeHost(value.startsWith("www.") ? value.substring(4) : value);
}

/**
 * 解析URI。
 *
 * @param url 待校验或访问的 URL。
 * @return 返回解析后的URI。
 */
static URI parseUri(String url) {
    try {
        return URI.create(url);
    } catch (Exception e) {
        log.debug(
                "URI parsing failed; treating URL text as unparsable: {}",
                ErrorTextSupport.summaryWithType(e));
        return null;
    }
}

/**
 * 判断是否存在用户Info。
 *
 * @param uri 待校验或访问的地址参数。
 * @return 如果用户Info满足条件则返回 true，否则返回 false。
 */
static boolean hasUserInfo(URI uri) {
    if (uri == null) {
        return false;
    }
    if (StrUtil.isNotBlank(uri.getRawUserInfo()) || StrUtil.isNotBlank(uri.getUserInfo())) {
        return true;
    }
    String authority = StrUtil.nullToEmpty(uri.getRawAuthority());
    if (authority.length() == 0) {
        authority = StrUtil.nullToEmpty(uri.getAuthority());
    }
    return authority.indexOf('@') >= 0;
}

/**
 * 判断是否存在Sensitive URL Parameter名称。
 *
 * @param uri 待校验或访问的地址参数。
 * @return 如果Sensitive URL Parameter名称满足条件则返回 true，否则返回 false。
 */
static boolean hasSensitiveUrlParameterName(URI uri) {
    if (uri == null) {
        return false;
    }
    return containsSensitivePathCredentialName(uri.getRawPath())
            || containsSensitiveParameterName(uri.getRawPath())
            || containsSensitiveParameterName(uri.getRawQuery())
            || containsSensitiveParameterName(uri.getRawFragment());
}

/**
 * 判断是否存在Sensitive Schemeless URL Parameter名称。
 *
 * @param raw 原始输入值。
 * @return 如果Sensitive Schemeless URL Parameter名称满足条件则返回 true，否则返回 false。
 */
static boolean hasSensitiveSchemelessUrlParameterName(String raw) {
    URI uri = parseUri("http://" + raw);
    if (uri == null) {
        return containsSensitiveParameterName(raw);
    }
    return hasSensitiveUrlParameterName(uri);
}

/**
 * 判断是否包含Sensitive路径凭据名称。
 *
 * @param rawPath 文件或目录路径参数。
 * @return 返回contains Sensitive路径凭据名称结果。
 */
private static boolean containsSensitivePathCredentialName(String rawPath) {
    String value = StrUtil.nullToEmpty(rawPath);
    if (value.length() == 0) {
        return false;
    }
    String[] segments = value.split("[/\\\\]+");
    for (int i = 0; i < segments.length; i++) {
        String segment = decodeUrlComponent(segments[i]).trim();
        if (segment.length() == 0) {
            continue;
        }
        String name = segment;
        int equals = name.indexOf('=');
        int colon = name.indexOf(':');
        int delimiter = -1;
        if (equals >= 0 && colon >= 0) {
            delimiter = Math.min(equals, colon);
        } else if (equals >= 0) {
            delimiter = equals;
        } else if (colon >= 0) {
            delimiter = colon;
        }
        if (delimiter >= 0) {
            name = name.substring(0, delimiter);
            if (isSensitiveUrlParameterName(name)) {
                return true;
            }
            continue;
        }
        if (isSensitiveUrlParameterName(name)
                && hasFollowingPathCredentialValue(segments, i + 1)) {
            return true;
        }
    }
    return false;
}

/**
 * 判断是否存在Following路径凭据Value。
 *
 * @param segments segments 参数。
 * @param start start 参数。
 * @return 如果Following路径凭据Value满足条件则返回 true，否则返回 false。
 */
private static boolean hasFollowingPathCredentialValue(String[] segments, int start) {
    for (int i = start; i < segments.length; i++) {
        if (decodeUrlComponent(segments[i]).trim().length() > 0) {
            return true;
        }
    }
    return false;
}

/**
 * 判断是否包含Sensitive参数名称。
 *
 * @param rawParameters 原始Parameters参数。
 * @return 返回contains Sensitive Parameter名称结果。
 */
static boolean containsSensitiveParameterName(String rawParameters) {
    String value = StrUtil.nullToEmpty(rawParameters);
    if (value.length() == 0) {
        return false;
    }
    if (containsSignedUrlParameterSet(value)) {
        return true;
    }
    if (containsSensitiveParameterNameInCandidate(value)) {
        return true;
    }
    String decoded = decodeUrlComponent(value);
    if (!decoded.equals(value)) {
        if (containsSignedUrlParameterSet(decoded)) {
            return true;
        }
        return containsSensitiveParameterNameInCandidate(decoded);
    }
    return false;
}

/**
 * 判断是否包含Sensitive参数名称InCandidate。
 *
 * @param value 待规范化或校验的原始值。
 * @return 返回contains Sensitive Parameter名称In Candidate结果。
 */
private static boolean containsSensitiveParameterNameInCandidate(String value) {
    String[] parameters = value.split("[&;]");
    for (String parameter : parameters) {
        String normalizedParameter = StrUtil.nullToEmpty(parameter);
        String name = normalizedParameter;
        int question = name.indexOf('?');
        if (question >= 0) {
            name = name.substring(question + 1);
        }
        int hash = name.indexOf('#');
        if (hash >= 0) {
            name = name.substring(hash + 1);
        }
        int equals = name.indexOf('=');
        String rawParameterValue = "";
        if (equals >= 0) {
            rawParameterValue = name.substring(equals + 1);
            name = name.substring(0, equals);
        }
        if (containsNestedSensitiveParameterName(rawParameterValue)) {
            return true;
        }
        if (isStrongSensitiveUrlParameterName(name)
                || (isSensitiveUrlParameterName(name)
                        && !isGenericSignatureParameterName(name)
                        && looksLikeSensitiveUrlParameterValue(rawParameterValue))) {
            return true;
        }
    }
    return false;
}

/**
 * 判断是否包含NestedSensitive参数名称。
 *
 * @param rawValue 原始值参数。
 * @return 返回contains Nested Sensitive Parameter名称结果。
 */
private static boolean containsNestedSensitiveParameterName(String rawValue) {
    String value = decodeUrlComponent(rawValue);
    if (containsStructuredSensitiveParameterName(value)) {
        return true;
    }
    if (value.equals(StrUtil.nullToEmpty(rawValue))) {
        return false;
    }
    if (value.indexOf('?') < 0 && value.indexOf('&') < 0 && value.indexOf(';') < 0) {
        return false;
    }
    return containsSensitiveParameterName(value);
}

/**
 * 判断是否包含StructuredSensitive参数名称。
 *
 * @param value 待规范化或校验的原始值。
 * @return 返回contains Structured Sensitive Parameter名称结果。
 */
private static boolean containsStructuredSensitiveParameterName(String value) {
    Matcher matcher =
            Pattern.compile("(?iu)[\"']?([A-Za-z][A-Za-z0-9_.-]{2,})[\"']?\\s*[:=]")
                    .matcher(StrUtil.nullToEmpty(value));
    while (matcher.find()) {
        if (isSensitiveUrlParameterName(matcher.group(1))) {
            return true;
        }
    }
    return false;
}

/**
 * 判断是否包含SignedURL参数Set。
 *
 * @param rawParameters 原始Parameters参数。
 * @return 返回contains Signed URL Parameter Set结果。
 */
private static boolean containsSignedUrlParameterSet(String rawParameters) {
    String[] parameters = StrUtil.nullToEmpty(rawParameters).split("[&;]");
    boolean signature = false;
    boolean accessKey = false;
    boolean credential = false;
    boolean expires = false;
    for (String parameter : parameters) {
        String name = parameterName(parameter);
        if (name.length() == 0) {
            continue;
        }
        if ("signature".equals(name)) {
            signature = true;
        }
        if ("awsaccesskeyid".equals(name)
                || "ossaccesskeyid".equals(name)
                || "accesskeyid".equals(name)
                || "access_key_id".equals(name)) {
            accessKey = true;
        }
        if ("credential".equals(name)
                || name.endsWith("_credential")
                || "security_token".equals(name)
                || name.endsWith("_security_token")) {
            credential = true;
        }
        if ("expires".equals(name) || "expiration".equals(name) || name.endsWith("_expires")) {
            expires = true;
        }
    }
    return signature && (accessKey || credential || expires);
}

/**
 * 执行参数名称相关逻辑。
 *
 * @param rawParameter 原始参数参数。
 * @return 返回parameter名称结果。
 */
private static String parameterName(String rawParameter) {
    String name = StrUtil.nullToEmpty(rawParameter);
    int question = name.indexOf('?');
    if (question >= 0) {
        name = name.substring(question + 1);
    }
    int hash = name.indexOf('#');
    if (hash >= 0) {
        name = name.substring(hash + 1);
    }
    int equals = name.indexOf('=');
    if (equals >= 0) {
        name = name.substring(0, equals);
    }
    return normalizeSensitiveParameterName(name);
}

/**
 * 判断是否Strong Sensitive URL Parameter名称。
 *
 * @param rawName 原始名称参数。
 * @return 如果Strong Sensitive URL Parameter名称满足条件则返回 true，否则返回 false。
 */
static boolean isStrongSensitiveUrlParameterName(String rawName) {
    String name = normalizeSensitiveParameterName(rawName);
    return "access_token".equals(name)
            || "refresh_token".equals(name)
            || "id_token".equals(name)
            || "auth_token".equals(name)
            || "oauth_token".equals(name)
            || "authorization".equals(name)
            || "proxy_authorization".equals(name)
            || "bearer_token".equals(name)
            || "code_verifier".equals(name)
            || "client_assertion".equals(name)
            || "saml_response".equals(name)
            || "samlresponse".equals(name)
            || "access_key".equals(name)
            || "secret_key".equals(name)
            || "session_token".equals(name)
            || "client_secret".equals(name)
            || "api_key".equals(name)
            || "apikey".equals(name)
            || "password".equals(name)
            || "private_key".equals(name)
            || name.startsWith("x_amz_")
            || name.startsWith("x_goog_")
            || name.startsWith("x_oss_")
            || name.startsWith("x_cos_")
            || name.startsWith("x_obs_")
            || name.startsWith("x_ms_")
            || "security_token".equals(name);
}

/**
 * 判断是否Generic签名Parameter名称。
 *
 * @param rawName 原始名称参数。
 * @return 如果Generic签名Parameter名称满足条件则返回 true，否则返回 false。
 */
private static boolean isGenericSignatureParameterName(String rawName) {
    return "signature".equals(normalizeSensitiveParameterName(rawName));
}

/**
 * 判断是否Sensitive URL Parameter名称。
 *
 * @param rawName 原始名称参数。
 * @return 如果Sensitive URL Parameter名称满足条件则返回 true，否则返回 false。
 */
private static boolean isSensitiveUrlParameterName(String rawName) {
    String name = normalizeSensitiveParameterName(rawName);
    for (String sensitiveName : SENSITIVE_URL_PARAMETER_NAMES) {
        if (name.equals(normalizeSensitiveParameterName(sensitiveName))) {
            return true;
        }
    }
    return false;
}

/**
 * 判断是否具有SensitiveURL参数值特征。
 *
 * @param rawValue 原始值参数。
 * @return 返回looks Like Sensitive URL Parameter Value结果。
 */
private static boolean looksLikeSensitiveUrlParameterValue(String rawValue) {
    String value = decodeUrlComponent(rawValue).trim();
    return value.length() >= 8 || SecretRedactor.containsSecretLikeToken(value);
}

/**
 * 规范化Sensitive Parameter名称。
 *
 * @param rawName 原始名称参数。
 * @return 返回Sensitive Parameter名称结果。
 */
static String normalizeSensitiveParameterName(String rawName) {
    String name = decodeUrlComponent(rawName).trim();
    name = name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
    name = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
    name = name.toLowerCase(Locale.ROOT);
    name = name.replace('-', '_').replace('.', '_');
    name = name.replaceAll("\\s+", "_");
    return name;
}

/**
 * 解码URLComponent。
 *
 * @param raw 原始输入值。
 * @return 返回decode URL Component结果。
 */
private static String decodeUrlComponent(String raw) {
    String value = StrUtil.nullToEmpty(raw);
    for (int i = 0; i < 4; i++) {
        String decoded;
        try {
            decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            log.debug(
                    "URL component decoding failed; returning current normalized text: {}",
                    ErrorTextSupport.summaryWithType(e));
            return value;
        }
        decoded = normalizeUrlText(decoded);
        if (decoded.equals(value)) {
            return decoded;
        }
        value = decoded;
    }
    return value;
}

/**
 * 判断是否存在Schemeless用户Info。
 *
 * @param raw 原始输入值。
 * @return 如果Schemeless用户Info满足条件则返回 true，否则返回 false。
 */
static boolean hasSchemelessUserInfo(String raw) {
    String value = normalizeUrlText(raw);
    if (value.length() == 0 || value.contains("://")) {
        return false;
    }
    int slash = value.indexOf('/');
    String authority = slash >= 0 ? value.substring(0, slash) : value;
    int question = authority.indexOf('?');
    if (question >= 0) {
        authority = authority.substring(0, question);
    }
    int hash = authority.indexOf('#');
    if (hash >= 0) {
        authority = authority.substring(0, hash);
    }
    int at = authority.lastIndexOf('@');
    if (at <= 0 || at + 1 >= authority.length()) {
        return false;
    }
    String userInfo = authority.substring(0, at);
    String host = authority.substring(at + 1);
    return userInfo.length() > 0 && extractSchemelessHost(host).length() > 0;
}

/**
 * 提取URI Host。
 *
 * @param uri 待校验或访问的地址参数。
 * @return 返回URI Host结果。
 */
static String extractUriHost(URI uri) {
    if (uri == null) {
        return "";
    }
    String host = normalizeHost(uri.getHost());
    if (StrUtil.isNotBlank(host)) {
        return host;
    }
    String authority = StrUtil.nullToEmpty(uri.getRawAuthority());
    if (authority.length() == 0) {
        authority = StrUtil.nullToEmpty(uri.getAuthority());
    }
    authority = normalizeUrlText(authority);
    int at = authority.lastIndexOf('@');
    if (at >= 0) {
        authority = authority.substring(at + 1);
    }
    if (authority.startsWith("[") && authority.contains("]")) {
        return normalizeHost(authority.substring(0, authority.indexOf(']') + 1));
    }
    int colon = authority.lastIndexOf(':');
    if (colon > 0 && authority.indexOf(':') == colon) {
        authority = authority.substring(0, colon);
    }
    return normalizeHost(authority);
}

/**
 * 规范化URL Text。
 *
 * @param raw 原始输入值。
 * @return 返回URL Text结果。
 */
static String normalizeUrlText(String raw) {
    String value = StrUtil.nullToEmpty(raw).replace("\u0000", "");
    value = TerminalAnsiSanitizer.stripAnsi(value);
    value = SecretRedactor.stripDisplayControls(value);
    value = Normalizer.normalize(value, Normalizer.Form.NFKC);
    value = HtmlUtil.unescape(value);
    return value.trim();
}

/**
 * 规范化Host。
 *
 * @param host 主机参数。
 * @return 返回Host结果。
 */
static String normalizeHost(String host) {
    String value = decodeHostText(host).toLowerCase(Locale.ROOT);
    if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
        value = value.substring(1, value.length() - 1);
    }
    while (value.endsWith(".")) {
        value = value.substring(0, value.length() - 1);
    }
    value = toAsciiHost(value);
    return value;
}

/**
 * 解码主机文本。
 *
 * @param host 主机参数。
 * @return 返回decode Host Text结果。
 */
private static String decodeHostText(String host) {
    String value = normalizeUrlText(host);
    for (int i = 0; i < 4; i++) {
        String decoded;
        try {
            decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            log.debug(
                    "Host text decoding failed; returning current normalized host: {}",
                    ErrorTextSupport.summaryWithType(e));
            return value;
        }
        decoded = normalizeUrlText(decoded);
        if (decoded.equals(value)) {
            return decoded;
        }
        value = decoded;
    }
    return value;
}

/**
 * 转换为Ascii Host。
 *
 * @param host 主机参数。
 * @return 返回转换后的Ascii Host。
 */
private static String toAsciiHost(String host) {
    if (StrUtil.isBlank(host) || host.indexOf(':') >= 0) {
        return host;
    }
    if (host.startsWith("*.")) {
        return "*." + toAsciiHost(host.substring(2));
    }
    try {
        return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (Exception e) {
        log.debug(
                "Host ASCII normalization failed; keeping normalized host: {}",
                ErrorTextSupport.summaryWithType(e));
        return host;
    }
}


}
