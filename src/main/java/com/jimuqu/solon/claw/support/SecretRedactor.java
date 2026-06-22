package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 承载密钥脱敏器相关状态和辅助逻辑。 */
public final class SecretRedactor {
    /** 记录脱敏解码失败的低敏诊断日志，不输出待脱敏内容或异常消息。 */
    private static final Logger log = LoggerFactory.getLogger(SecretRedactor.class);

    /** BEARER的统一常量值。 */
    private static final Pattern BEARER =
            Pattern.compile(
                    "(?i)(Authorization:\\s*Bearer\\s+|\\bbearer\\s+)([A-Za-z0-9._~+/-]+=*)");

    /** 环境变量ASSIGNMENT的统一常量值。 */
    private static final Pattern ENV_ASSIGNMENT =
            Pattern.compile(
                    "\\b([A-Z0-9_]{0,50}(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH)[A-Z0-9_]{0,50})(=)(['\"]?)(\\S+)\\3");

    /** 终端键值的统一常量值。 */
    private static final Pattern SHELL_KEY_VALUE =
            Pattern.compile(
                    "(?i)\\b(api[_-]?key|apikey|token|secret|password|authorization|access[_-]?token|refresh[_-]?token|bearer[_-]?token|client[_-]?secret|private[_-]?key)(=)([^\\s,;&=\"#'}]+)");

    /** JSONFIELD的统一常量值。 */
    private static final Pattern JSON_FIELD =
            Pattern.compile(
                    "(?i)(\"(?:api_?key|token|secret|password|access_?token|refresh_?token|auth_?token|bearer_?token|client_?secret|secret_?value|raw_?secret|secret_?input|key_?material|private_?key|authorization)\")(\\s*:\\s*\")([^\"]+)(\")");

    /** 配置文件冒号字段的统一常量值，用于覆盖 YAML 等非 JSON 格式中的密钥。 */
    private static final Pattern CONFIG_COLON_FIELD =
            Pattern.compile(
                    "(?im)^([ \\t-]*[A-Za-z0-9_.-]*(?:api[_-]?key|apikey|token|secret|password|passwd|credential|access[_-]?token|refresh[_-]?token|bearer[_-]?token|client[_-]?secret|private[_-]?key)[A-Za-z0-9_.-]*[ \\t]*:[ \\t]*)(['\"]?)([^\\r\\n#]+?)(\\2)([ \\t]*(?:#.*)?)$");

    /** URLUSERINFO的统一常量值。 */
    private static final Pattern URL_USERINFO =
            Pattern.compile("(?i)\\b(https?|wss?|ftp)://([^/?#\\s:@]+):([^/?#\\s@]+)@");

    /** ENCODEDURLUSERINFO的统一常量值。 */
    private static final Pattern ENCODED_URL_USERINFO =
            Pattern.compile(
                    "(?i)\\b(https?|wss?|ftp)://([^/?#\\s@]+)(%(?:25){0,3}3a)([^/?#\\s@]+)@");

    /** SCHEMELESSURLUSERINFO的统一常量值。 */
    private static final Pattern SCHEMELESS_URL_USERINFO =
            Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_./:-])([A-Za-z0-9._~+%-]{1,80}):(?!//)([^\\s/@?#]+)@([A-Za-z0-9._~%-]+(?:\\:[0-9]{1,5})?(?:[/#?]|\\b))");

    /** SENSITIVEURLUSERINFO的统一常量值。 */
    private static final Pattern SENSITIVE_URL_USERINFO =
            Pattern.compile("(?i)\\b(?:https?|wss?|ftp)://[^/?#\\s:@]+:[^/?#\\s@]+@[^\\s]+");

    /** DBCONNSTR的统一常量值。 */
    private static final Pattern DB_CONNSTR =
            Pattern.compile(
                    "(?i)\\b((?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|amqp)://[^:\\s/@]+:)([^@\\s]+)(@)");

    /** 私聊键的统一常量值。 */
    private static final Pattern PRIVATE_KEY =
            Pattern.compile(
                    "-----BEGIN[A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END[A-Z ]*PRIVATE KEY-----");

    /** JWT的统一常量值。 */
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_=-]{4,}){0,2}");

    /** SENSITIVE查询名称列表的统一常量值。 */
    private static final String SENSITIVE_QUERY_NAMES =
            "access_token|refresh_token|id_token|auth_token|oauth_token|authorization|proxy_authorization|bearer_token|code_verifier|client_assertion|saml_response|samlresponse|token|access_key|secret_key|session_token|api_key|apikey|client_secret|password|private_key|auth|jwt|session|secret|key|code|signature|security_token|x-amz-signature|x_amz_signature|x_amz_credential|x_amz_security_token|x_goog_signature|x_goog_credential|x_oss_signature|x_oss_security_token|x_cos_signature|x_cos_security_token|x_obs_signature|x_obs_security_token|x_ms_signature";

    /** SENSITIVE查询的统一常量值。 */
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile("(?i)([?&;](?:" + SENSITIVE_QUERY_NAMES + ")=)[^&;#\\s]+");

    /** 敏感路径识别规则；只隐藏凭据、密钥、令牌等高风险路径，普通项目路径仍可展示。 */
    private static final Pattern SENSITIVE_PATH =
            Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])("
                            + "(?:[A-Za-z]:)?(?:[\\\\/][^\\s\"'<>|;?#]+)*[\\\\/](?:\\.env(?:\\.[^\\s\"'<>|;?#]+)?|\\.ssh|\\.gnupg|id_(?:rsa|dsa|ecdsa|ed25519)|[^\\s\"'<>|;?#]*(?:credential|secret|token|password|passwd|private[_-]?key)[^\\s\"'<>|;?#]*)(?:[\\\\/][^\\s\"'<>|;?#]+)*"
                            + "|~[\\\\/][^\\s\"'<>|;?#]*(?:\\.ssh|\\.gnupg|credential|secret|token|password|passwd|private[_-]?key)[^\\s\"'<>|;?#]*(?:[\\\\/][^\\s\"'<>|;?#]+)*"
                            + "|(?:^|(?<=[\\s\"'=:]))(?:[^\\s\"'<>|;?#]+[\\\\/])*skills[\\\\/]\\.hub(?:[\\\\/][^\\s\"'<>|;?#]+)*"
                            + "|(?:(?<=^)|(?<=[\\s\"'=:()]))(?:\\.env(?:\\.[^\\s\"'<>|;?#)]+)?|\\.ssh[\\\\/][^\\s\"'<>|;?#)]+|credentials?[\\\\/][^\\s\"'<>|;?#)]+|secrets?[\\\\/][^\\s\"'<>|;?#)]+|credentials|secrets|id_(?:rsa|dsa|ecdsa|ed25519)|\\.credentials\\.json|credentials\\.json|application_default_credentials\\.json)(?![A-Za-z0-9_.-])"
                            + ")");

    /** ENCODEDSEPARATOR的统一常量值。 */
    private static final String ENCODED_SEPARATOR =
            "(?:_|-|&#95;|&#x5[fF];|&lowbar;|%5[fF]|%255[fF])";

    /** 敏感文件名识别规则；用于隐藏命令输出或诊断文本中的独立凭据文件名。 */
    private static final Pattern SENSITIVE_FILE_TOKEN =
            Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_.-])(?:[^\\s\"'<>|;?#=\\\\/]*?(?:"
                            + "credential"
                            + "|client"
                            + ENCODED_SEPARATOR
                            + "secret"
                            + "|api"
                            + ENCODED_SEPARATOR
                            + "key"
                            + "|access"
                            + ENCODED_SEPARATOR
                            + "token"
                            + "|refresh"
                            + ENCODED_SEPARATOR
                            + "token"
                            + "|private"
                            + ENCODED_SEPARATOR
                            + "key"
                            + ")[^\\s\"'<>|;?#=\\\\/]*\\.[A-Za-z0-9]{1,12})(?![A-Za-z0-9_.-])");

    /** SENSITIVEURL路径SEGMENT的统一常量值。 */
    private static final Pattern SENSITIVE_URL_PATH_SEGMENT =
            Pattern.compile(
                    "(?i)/(?:(?:access|refresh|id)"
                            + ENCODED_SEPARATOR
                            + "token|token|api"
                            + ENCODED_SEPARATOR
                            + "key|client"
                            + ENCODED_SEPARATOR
                            + "secret|private"
                            + ENCODED_SEPARATOR
                            + "key|credential|credentials|secret|password|auth|jwt|session|signature)(?:[/:=][^/?#\\s&;]+)+");

    /** 敏感URL文件SEGMENT的统一常量值，用于隐藏 URL 路径中的凭据文件名。 */
    private static final Pattern SENSITIVE_URL_FILE_SEGMENT =
            Pattern.compile(
                    "(?i)/[^/?#\\s&;]*?(?:credential|client"
                            + ENCODED_SEPARATOR
                            + "secret|api"
                            + ENCODED_SEPARATOR
                            + "key|access"
                            + ENCODED_SEPARATOR
                            + "token|refresh"
                            + ENCODED_SEPARATOR
                            + "token|private"
                            + ENCODED_SEPARATOR
                            + "key)[^/?#\\s&;]*\\.[A-Za-z0-9]{1,12}(?=$|[?#&;])");

    /** PREFIX密钥的统一常量值。 */
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

    /** EMBEDDEDPREFIX密钥的统一常量值。 */
    private static final Pattern EMBEDDED_PREFIX_SECRET =
            Pattern.compile(
                    "(?i)((?:^|[^A-Za-z0-9])(?:[A-Za-z0-9_.-]{0,80})(?:ghp_|github_pat_|sk-|sk_|sk_live_|sk_test_|xox[baprs]-|hf_|npm_|pypi-|gsk_|tvly-|exa_|brv_))[A-Za-z0-9_-]{10,}");

    /** 展示控制的统一常量值。 */
    private static final Pattern DISPLAY_CONTROL =
            Pattern.compile(
                    "[\\u0000-\\u0008\\u000B-\\u001F\\u007F\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    /** 默认最大LENGTH的统一常量值。 */
    private static final int DEFAULT_MAX_LENGTH = 8000;

    /** 创建密钥脱敏器实例。 */
    private SecretRedactor() {}

    /**
     * 脱敏文本中的密钥、令牌、带凭据的 URL 和敏感凭据路径；普通绝对路径允许保留。
     *
     * @param text 待处理文本。
     * @return 返回redact结果。
     */
    public static String redact(String text) {
        return redact(text, DEFAULT_MAX_LENGTH);
    }

    /**
     * 脱敏文本中的密钥、令牌、带凭据的 URL 和敏感凭据路径；普通绝对路径允许保留。
     *
     * @param text 待处理文本。
     * @param maxLength 最大保留字符数。
     * @return 返回redact结果。
     */
    public static String redact(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String result = stripDisplayControls(text);
        result = BEARER.matcher(result).replaceAll("$1***");
        result = ENV_ASSIGNMENT.matcher(result).replaceAll("$1$2$3***$3");
        result = SHELL_KEY_VALUE.matcher(result).replaceAll("$1$2***");
        result = JSON_FIELD.matcher(result).replaceAll("$1$2***$4");
        result = CONFIG_COLON_FIELD.matcher(result).replaceAll("$1$2***$4$5");
        result = PREFIX_SECRET.matcher(result).replaceAll("***");
        result = EMBEDDED_PREFIX_SECRET.matcher(result).replaceAll("$1***");
        result = PRIVATE_KEY.matcher(result).replaceAll("[REDACTED PRIVATE KEY]");
        result = DB_CONNSTR.matcher(result).replaceAll("$1***$3");
        result = JWT.matcher(result).replaceAll("***");
        result = SENSITIVE_URL_USERINFO.matcher(result).replaceAll("[REDACTED_URL_CREDENTIAL]");
        result = redactUrlUserinfo(result);
        result = redactEncodedSensitiveQuery(result);
        result = SENSITIVE_QUERY.matcher(result).replaceAll("$1***");
        result = redactEncodedSensitiveQuery(result);
        int limit = Math.max(128, maxLength);
        if (result.length() > limit) {
            return result.substring(0, limit)
                    + "\n...[truncated, totalLength="
                    + result.length()
                    + "]";
        }
        return result;
    }

    /**
     * 脱敏token Only。
     *
     * @param text 待处理文本。
     * @param maxLength 最大保留字符数。
     * @return 返回token Only结果。
     */
    public static String redactTokensOnly(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String result = stripDisplayControls(text);
        result = PREFIX_SECRET.matcher(result).replaceAll("***");
        result = EMBEDDED_PREFIX_SECRET.matcher(result).replaceAll("$1***");
        result = JWT.matcher(result).replaceAll("***");
        int limit = Math.max(128, maxLength);
        if (result.length() > limit) {
            return result.substring(0, limit)
                    + "\n...[truncated, totalLength="
                    + result.length()
                    + "]";
        }
        return result;
    }

    /**
     * 脱敏Object。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Object结果。
     */
    public static Object redactObject(Object value) {
        if (value instanceof String) {
            return redact((String) value);
        }
        return value;
    }

    /**
     * 脱敏文本中的敏感凭据路径和独立凭据文件名，避免安全拒绝、诊断和工具输出暴露真实位置。
     *
     * @param value 待处理文本。
     * @return 返回隐藏敏感路径后的文本。
     */
    public static String redactSensitivePaths(String value) {
        if (value == null) {
            return null;
        }
        String result = SENSITIVE_PATH.matcher(value).replaceAll("[REDACTED_PATH]");
        return SENSITIVE_FILE_TOKEN.matcher(result).replaceAll("[REDACTED_PATH]");
    }

    /**
     * 判断是否包含密钥Liketoken。
     *
     * @param text 待处理文本。
     * @return 返回contains密钥Like token结果。
     */
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
        } catch (Exception e) {
            log.debug("密钥特征URL解码失败，按未命中兜底 error={}", exceptionSummary(e));
            return false;
        }
    }

    /**
     * 脱敏URL。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回URL结果。
     */
    public static String maskUrl(String value) {
        if (StrUtil.isBlank(value)) {
            return value;
        }
        String result = stripDisplayControls(value);
        result = redactUrlUserinfo(result);
        result = DB_CONNSTR.matcher(result).replaceAll("$1***$3");
        result = redactEncodedSensitiveQuery(result);
        result = SENSITIVE_QUERY.matcher(result).replaceAll("$1***");
        result = redactEncodedSensitiveQuery(result);
        result = SENSITIVE_URL_PATH_SEGMENT.matcher(result).replaceAll("/[REDACTED_URL_SECRET]");
        result = SENSITIVE_URL_FILE_SEGMENT.matcher(result).replaceAll("/[REDACTED_URL_SECRET]");
        return PREFIX_SECRET.matcher(result).replaceAll("***");
    }

    /**
     * 剥离展示Controls。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip展示Controls结果。
     */
    public static String stripDisplayControls(String value) {
        if (value == null) {
            return null;
        }
        return DISPLAY_CONTROL.matcher(value).replaceAll("");
    }

    /**
     * 脱敏URL Userinfo。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回URL Userinfo结果。
     */
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
        return redactSchemelessUrlUserinfo(buffer.toString());
    }

    /**
     * 脱敏Encoded URL Userinfo。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Encoded URL Userinfo结果。
     */
    private static String redactEncodedUrlUserinfo(String value) {
        Matcher matcher = ENCODED_URL_USERINFO.matcher(value);
        StringBuffer buffer = new StringBuffer(value.length());
        while (matcher.find()) {
            matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(
                            matcher.group(1)
                                    + "://"
                                    + matcher.group(2)
                                    + matcher.group(3)
                                    + "***@"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 脱敏Schemeless URL Userinfo。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Schemeless URL Userinfo结果。
     */
    private static String redactSchemelessUrlUserinfo(String value) {
        Matcher matcher = SCHEMELESS_URL_USERINFO.matcher(value);
        StringBuffer buffer = new StringBuffer(value.length());
        while (matcher.find()) {
            matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(matcher.group(1) + ":***@" + matcher.group(3)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 脱敏Encoded Sensitive Query。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Encoded Sensitive Query结果。
     */
    private static String redactEncodedSensitiveQuery(String value) {
        StringBuilder buffer = new StringBuilder(value.length());
        int start = 0;
        while (start < value.length()) {
            int question = value.indexOf('?', start);
            int amp = value.indexOf('&', start);
            int semicolon = value.indexOf(';', start);
            int hash = value.indexOf('#', start);
            int separator = minPositive(minPositive(question, amp), minPositive(semicolon, hash));
            if (separator < 0 || separator + 1 >= value.length()) {
                buffer.append(value.substring(start));
                break;
            }
            buffer.append(value, start, separator + 1);
            int end = nextParameterEnd(value, separator + 1, value.charAt(separator));
            String parameter = value.substring(separator + 1, end);
            buffer.append(redactEncodedSensitiveParameter(parameter));
            start = end;
        }
        return buffer.toString();
    }

    /**
     * 执行min正数相关逻辑。
     *
     * @param first first 参数。
     * @param second second 参数。
     * @return 返回min Positive结果。
     */
    private static int minPositive(int first, int second) {
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }

    /**
     * 执行next参数End相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param start start 参数。
     * @param separator separator 参数。
     * @return 返回next Parameter End结果。
     */
    private static int nextParameterEnd(String value, int start, char separator) {
        for (int i = start; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '&' || ch == ';' || (separator != '#' && ch == '#')) {
                return i;
            }
            if (isUrlParameterTextBoundary(ch)) {
                return i;
            }
        }
        return value.length();
    }

    /**
     * 判断是否URL Parameter Text Boundary。
     *
     * @param ch ch 参数。
     * @return 如果URL Parameter Text Boundary满足条件则返回 true，否则返回 false。
     */
    private static boolean isUrlParameterTextBoundary(char ch) {
        return Character.isWhitespace(ch)
                || ch == '"'
                || ch == '\''
                || ch == '`'
                || ch == '<'
                || ch == '>'
                || ch == ')'
                || ch == ']'
                || ch == '}';
    }

    /**
     * 脱敏Encoded Sensitive Parameter。
     *
     * @param parameter 参数参数。
     * @return 返回Encoded Sensitive Parameter结果。
     */
    private static String redactEncodedSensitiveParameter(String parameter) {
        int equals = parameter.indexOf('=');
        if (equals <= 0 || equals + 1 >= parameter.length()) {
            return parameter;
        }
        String name = parameter.substring(0, equals);
        String decodedName = normalizeSensitiveQueryName(decodeRepeated(name));
        if (!decodedName.matches("(?i)(?:" + SENSITIVE_QUERY_NAMES + ")")) {
            return name
                    + "="
                    + redactEmbeddedEncodedSensitiveQuery(parameter.substring(equals + 1));
        }
        return name + "=***";
    }

    /**
     * 脱敏Embedded Encoded Sensitive Query。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Embedded Encoded Sensitive Query结果。
     */
    private static String redactEmbeddedEncodedSensitiveQuery(String value) {
        if (StrUtil.isBlank(value)) {
            return value;
        }
        boolean hasRawSeparator =
                value.indexOf('?') >= 0 || value.indexOf('#') >= 0 || value.indexOf(';') >= 0;
        String decoded = decodeRepeated(value);
        boolean hasDecodedSeparator =
                decoded.indexOf('?') >= 0
                        || decoded.indexOf('#') >= 0
                        || decoded.indexOf(';') >= 0
                        || decoded.indexOf('&') >= 0;
        if (!hasRawSeparator && !hasDecodedSeparator) {
            return value;
        }
        if (!hasRawSeparator && !redactEncodedSensitiveQuery(decoded).equals(decoded)) {
            return "***";
        }
        return redactEncodedSensitiveQuery(value);
    }

    /**
     * 解码Repeated。
     *
     * @param raw 原始输入值。
     * @return 返回decode Repeated结果。
     */
    private static String decodeRepeated(String raw) {
        String value = StrUtil.nullToEmpty(raw);
        for (int i = 0; i < 4; i++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(value, "UTF-8");
            } catch (Exception e) {
                log.debug("脱敏URL重复解码失败，保留当前值兜底 error={}", exceptionSummary(e));
                return value;
            }
            if (decoded.equals(value)) {
                return decoded;
            }
            value = decoded;
        }
        return value;
    }

    /**
     * 规范化Sensitive Query名称。
     *
     * @param raw 原始输入值。
     * @return 返回Sensitive Query名称结果。
     */
    private static String normalizeSensitiveQueryName(String raw) {
        String name = StrUtil.nullToEmpty(raw);
        name = name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        name = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        name = name.toLowerCase(Locale.ROOT);
        name = name.replace('-', '_').replace('.', '_');
        return name.replaceAll("\\s+", "_");
    }

    /**
     * 生成异常类型摘要，避免日志携带原始密钥、URL、路径或异常消息。
     *
     * @param error 异常对象。
     * @return 仅包含异常类型的安全摘要。
     */
    private static String exceptionSummary(Exception error) {
        if (error == null) {
            return "unknown";
        }
        return error.getClass().getSimpleName();
    }
}
