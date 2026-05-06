package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.net.URLDecoder;
import java.util.regex.Pattern;

/** Redacts common secrets before returning logs/session details to dashboard clients. */
public final class SecretRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern KEY_VALUE =
            Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization|client[_-]?secret)(\\s*[:=]\\s*)([^\\s,;\"'}]+)");
    private static final Pattern URL_USERINFO =
            Pattern.compile("(?i)(https?://)([^/?#\\s@]+)@");
    private static final String SENSITIVE_QUERY_NAMES =
            "access_token|refresh_token|id_token|token|api_key|apikey|client_secret|password|auth|jwt|session|secret|key|code|signature|x-amz-signature";
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile("(?i)([?&](?:" + SENSITIVE_QUERY_NAMES + ")=)[^&#\\s]+");
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
    private static final int DEFAULT_MAX_LENGTH = 8000;

    private SecretRedactor() {}

    public static String redact(String text) {
        return redact(text, DEFAULT_MAX_LENGTH);
    }

    public static String redact(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String result = BEARER.matcher(text).replaceAll("Bearer ***");
        result = KEY_VALUE.matcher(result).replaceAll("$1$2***");
        result = PREFIX_SECRET.matcher(result).replaceAll("***");
        result = URL_USERINFO.matcher(result).replaceAll("$1***@");
        result = SENSITIVE_QUERY.matcher(result).replaceAll("$1***");
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
        String result = URL_USERINFO.matcher(value).replaceAll("$1***@");
        result = SENSITIVE_QUERY.matcher(result).replaceAll("$1***");
        return PREFIX_SECRET.matcher(result).replaceAll("***");
    }
}
