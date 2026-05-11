package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts common secrets before returning logs/session details to dashboard clients. */
public final class SecretRedactor {
    private static final Pattern BEARER =
            Pattern.compile(
                    "(?i)(Authorization:\\s*Bearer\\s+|\\bbearer\\s+)([A-Za-z0-9._~+/-]+=*)");
    private static final Pattern ENV_ASSIGNMENT =
            Pattern.compile(
                    "\\b([A-Z0-9_]{0,50}(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH)[A-Z0-9_]{0,50})(=)(['\"]?)(\\S+)\\3");
    private static final Pattern SHELL_KEY_VALUE =
            Pattern.compile(
                    "(?i)\\b(api[_-]?key|apikey|token|secret|password|authorization|client[_-]?secret)(=)([^\\s,;\"'}]+)");
    private static final Pattern JSON_FIELD =
            Pattern.compile(
                    "(?i)(\"(?:api_?key|token|secret|password|access_token|refresh_token|auth_token|bearer|secret_value|raw_secret|secret_input|key_material|private_key|authorization)\")(\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern URL_USERINFO =
            Pattern.compile("(?i)\\b(https?|wss?|ftp)://([^/?#\\s:@]+):([^/?#\\s@]+)@");
    private static final Pattern ENCODED_URL_USERINFO =
            Pattern.compile("(?i)\\b(https?|wss?|ftp)://([^/?#\\s@]+)(?:%3a|%3A)([^/?#\\s@]+)@");
    private static final Pattern SENSITIVE_URL_USERINFO =
            Pattern.compile("(?i)\\b(?:https?|wss?|ftp)://[^/?#\\s:@]+:[^/?#\\s@]+@[^\\s]+");
    private static final Pattern DB_CONNSTR =
            Pattern.compile(
                    "(?i)\\b((?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|amqp)://[^:\\s/@]+:)([^@\\s]+)(@)");
    private static final Pattern PRIVATE_KEY =
            Pattern.compile(
                    "-----BEGIN[A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END[A-Z ]*PRIVATE KEY-----");
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_=-]{4,}){0,2}");
    private static final String SENSITIVE_QUERY_NAMES =
            "access_token|refresh_token|id_token|token|api_key|apikey|client_secret|password|auth|jwt|session|secret|key|code|signature|x-amz-signature";
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile("(?i)([?&](?:" + SENSITIVE_QUERY_NAMES + ")=)[^&#\\s]+");
    private static final Pattern SENSITIVE_PATH =
            Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])("
                            + "(?:[A-Za-z]:)?(?:[\\\\/][^\\s\"'<>|;?#]+)*[\\\\/](?:\\.env(?:\\.[^\\s\"'<>|;?#]+)?|\\.ssh|\\.gnupg|id_(?:rsa|dsa|ecdsa|ed25519)|[^\\s\"'<>|;?#]*(?:credential|secret|token|password|passwd|private[_-]?key)[^\\s\"'<>|;?#]*)(?:[\\\\/][^\\s\"'<>|;?#]+)*"
                            + "|~[\\\\/][^\\s\"'<>|;?#]*(?:\\.ssh|\\.gnupg|credential|secret|token|password|passwd|private[_-]?key)[^\\s\"'<>|;?#]*(?:[\\\\/][^\\s\"'<>|;?#]+)*"
                            + "|(?:^|(?<=[\\s\"'=:]))(?:[^\\s\"'<>|;?#]+[\\\\/])*skills[\\\\/]\\.hub(?:[\\\\/][^\\s\"'<>|;?#]+)*"
                            + "|(?:^|(?<=[\\s\"'=:]))(?:\\.env(?:\\.[^\\s\"'<>|;?#]+)?|\\.ssh[\\\\/][^\\s\"'<>|;?#]+|credentials|secrets|credentials?[\\\\/][^\\s\"'<>|;?#]+|secrets?[\\\\/][^\\s\"'<>|;?#]+|id_(?:rsa|dsa|ecdsa|ed25519)|\\.credentials\\.json|credentials\\.json|application_default_credentials\\.json)(?![A-Za-z0-9_.-])"
                            + ")");
    private static final Pattern PREFIX_SECRET =
            Pattern.compile(
                    "(?<![A-Za-z0-9_-])("
                            + "sk-[A-Za-z0-9_-]{10,}"
                            + "|ghp_[A-Za-z0-9]{10,}"
                            + "|github_pat_[A-Za-z0-9_]{10,}"
                            + "|gh[ousr]_[A-Za-z0-9]{10,}"
                            + "|xox[baprs]-[A-Za-z0-9-]{10,}"
                            + "|AIza[A-Za-z0-9_-]{30,}"
                            + "|pplx-[A-Za-z0-9]{10,}"
                            + "|fal_[A-Za-z0-9_-]{10,}"
                            + "|fc-[A-Za-z0-9]{10,}"
                            + "|bb_live_[A-Za-z0-9_-]{10,}"
                            + "|gAAAA[A-Za-z0-9_=-]{20,}"
                            + "|AKIA[A-Z0-9]{16}"
                            + "|sk_live_[A-Za-z0-9]{10,}"
                            + "|sk_test_[A-Za-z0-9]{10,}"
                            + "|rk_live_[A-Za-z0-9]{10,}"
                            + "|SG\\.[A-Za-z0-9_-]{10,}"
                            + "|hf_[A-Za-z0-9]{10,}"
                            + "|r8_[A-Za-z0-9]{10,}"
                            + "|npm_[A-Za-z0-9]{10,}"
                            + "|pypi-[A-Za-z0-9_-]{10,}"
                            + "|do[po]_v1_[A-Za-z0-9]{10,}"
                            + "|am_[A-Za-z0-9_-]{10,}"
                            + "|sk_[A-Za-z0-9_]{10,}"
                            + "|tvly-[A-Za-z0-9]{10,}"
                            + "|exa_[A-Za-z0-9]{10,}"
                            + "|gsk_[A-Za-z0-9]{10,}"
                            + "|syt_[A-Za-z0-9]{10,}"
                            + "|retaindb_[A-Za-z0-9]{10,}"
                            + "|hsk-[A-Za-z0-9]{10,}"
                            + "|mem0_[A-Za-z0-9]{10,}"
                            + "|brv_[A-Za-z0-9]{10,}"
                            + ")(?![A-Za-z0-9_-])");
    private static final Pattern EMBEDDED_PREFIX_SECRET =
            Pattern.compile(
                    "(?i)((?:^|[^A-Za-z0-9])(?:[A-Za-z0-9_.-]{0,80})(?:ghp_|github_pat_|sk-|sk_|sk_live_|sk_test_|xox[baprs]-|hf_|npm_|pypi-|gsk_|tvly-|exa_|brv_))[A-Za-z0-9_-]{10,}");
    private static final Pattern DISPLAY_CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B-\\u001F\\u007F\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");
    private static final int DEFAULT_MAX_LENGTH = 8000;

    private SecretRedactor() {}

    public static String redact(String text) {
        return redact(text, DEFAULT_MAX_LENGTH);
    }

    public static String redact(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String result = stripDisplayControls(text);
        result = BEARER.matcher(result).replaceAll("$1***");
        result = ENV_ASSIGNMENT.matcher(result).replaceAll("$1$2$3***$3");
        result = SHELL_KEY_VALUE.matcher(result).replaceAll("$1$2***");
        result = JSON_FIELD.matcher(result).replaceAll("$1$2***$4");
        result = PREFIX_SECRET.matcher(result).replaceAll("***");
        result = EMBEDDED_PREFIX_SECRET.matcher(result).replaceAll("$1***");
        result = PRIVATE_KEY.matcher(result).replaceAll("[REDACTED PRIVATE KEY]");
        result = DB_CONNSTR.matcher(result).replaceAll("$1***$3");
        result = JWT.matcher(result).replaceAll("***");
        result = SENSITIVE_URL_USERINFO.matcher(result).replaceAll("[REDACTED_PATH]");
        result = redactUrlUserinfo(result);
        result = SENSITIVE_QUERY.matcher(result).replaceAll("$1***");
        result = SENSITIVE_PATH.matcher(result).replaceAll("[REDACTED_PATH]");
        int limit = Math.max(128, maxLength);
        if (result.length() > limit) {
            return result.substring(0, limit)
                    + "\n...[truncated, totalLength="
                    + result.length()
                    + "]";
        }
        return result;
    }

    public static Object redactObject(Object value) {
        if (value instanceof String) {
            return redact((String) value);
        }
        return value;
    }

    public static boolean containsSecretLikeToken(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        if (PREFIX_SECRET.matcher(text).find()) {
            return true;
        }
        try {
            String decoded = URLDecoder.decode(text, "UTF-8");
            return !decoded.equals(text) && PREFIX_SECRET.matcher(decoded).find();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String maskUrl(String value) {
        if (StrUtil.isBlank(value)) {
            return value;
        }
        String result = stripDisplayControls(value);
        result = redactUrlUserinfo(result);
        result = DB_CONNSTR.matcher(result).replaceAll("$1***$3");
        result = SENSITIVE_QUERY.matcher(result).replaceAll("$1***");
        return PREFIX_SECRET.matcher(result).replaceAll("***");
    }

    public static String stripDisplayControls(String value) {
        if (value == null) {
            return null;
        }
        return DISPLAY_CONTROL.matcher(value).replaceAll("");
    }

    private static String redactUrlUserinfo(String value) {
        String result = redactEncodedUrlUserinfo(value);
        Matcher matcher = URL_USERINFO.matcher(result);
        StringBuffer buffer = new StringBuffer(result.length());
        while (matcher.find()) {
            matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(
                            matcher.group(1) + "://" + matcher.group(2) + ":***@"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String redactEncodedUrlUserinfo(String value) {
        Matcher matcher = ENCODED_URL_USERINFO.matcher(value);
        StringBuffer buffer = new StringBuffer(value.length());
        while (matcher.find()) {
            matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(
                            matcher.group(1) + "://" + matcher.group(2) + "%3A***@"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
