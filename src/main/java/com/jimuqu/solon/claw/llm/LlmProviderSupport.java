package com.jimuqu.solon.claw.llm;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Provider key / dialect / URL 拼装辅助工具。 */
public final class LlmProviderSupport {
    /** DIALECTS的统一常量值。 */
    private static final Set<String> DIALECTS =
            new LinkedHashSet<String>(LlmConstants.SUPPORTED_PROVIDERS);

    /** SENSITIVE查询的统一常量值。 */
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile(
                    "(?i)(?:^|[?&;])(?:access_token|refresh_token|id_token|token|api_key|apikey|client_secret|password|auth|jwt|session|secret|key|code|signature|x-amz-signature)=");

    /** 创建大模型提供方辅助实例。 */
    private LlmProviderSupport() {}

    /**
     * 判断是否Supported协议方言。
     *
     * @param dialect dialect 参数。
     * @return 如果Supported协议方言满足条件则返回 true，否则返回 false。
     */
    public static boolean isSupportedDialect(String dialect) {
        return DIALECTS.contains(normalizeDialect(dialect));
    }

    /**
     * 规范化协议方言。
     *
     * @param dialect dialect 参数。
     * @return 返回协议方言结果。
     */
    public static String normalizeDialect(String dialect) {
        return StrUtil.nullToEmpty(dialect).trim().toLowerCase();
    }

    /**
     * 校验Base URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     */
    public static void validateBaseUrl(String baseUrl) {
        String raw = normalizedBaseCandidate(baseUrl);
        if (raw.length() == 0) {
            return;
        }
        if (SecretRedactor.containsSecretLikeToken(raw) || SENSITIVE_QUERY.matcher(raw).find()) {
            throw new IllegalArgumentException("provider.baseUrl 包含疑似 API key 或 token");
        }

        URI uri = parseProviderUri(raw);
        String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("provider.baseUrl 仅支持 http/https");
        }
        if (StrUtil.isNotBlank(uri.getRawUserInfo()) || hasAuthorityUserInfo(uri)) {
            throw new IllegalArgumentException("provider.baseUrl 不能包含 userinfo 凭据");
        }
        if (StrUtil.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("provider.baseUrl 缺少主机名或端口格式错误");
        }
    }

    /**
     * 判断是否Direct Open Ai Base URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @return 如果Direct Open Ai Base URL满足条件则返回 true，否则返回 false。
     */
    public static boolean isDirectOpenAiBaseUrl(String baseUrl) {
        return baseUrlHostMatches(baseUrl, "api.openai.com");
    }

    /**
     * 执行基础URL主机名相关逻辑。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @return 返回base URL Hostname结果。
     */
    public static String baseUrlHostname(String baseUrl) {
        String raw = normalizedBaseCandidate(baseUrl);
        if (raw.length() == 0) {
            return "";
        }
        String candidate = raw.contains("://") ? raw : "https://" + raw;
        try {
            URI uri = parseProviderUri(candidate);
            return normalizeHostname(uri.getHost());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * 执行基础URL主机Matches相关逻辑。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param domain domain 参数。
     * @return 返回base URL Host Matches结果。
     */
    public static boolean baseUrlHostMatches(String baseUrl, String domain) {
        String host = baseUrlHostname(baseUrl);
        String normalizedDomain = normalizeHostname(domain);
        if (host.length() == 0 || normalizedDomain.length() == 0) {
            return false;
        }
        return host.equals(normalizedDomain) || host.endsWith("." + normalizedDomain);
    }

    /**
     * 构建Api URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param dialect dialect 参数。
     * @return 返回创建好的Api URL。
     */
    public static String buildApiUrl(String baseUrl, String dialect) {
        String raw = StrUtil.nullToEmpty(baseUrl).trim();
        if (raw.length() == 0) {
            return "";
        }

        if (raw.endsWith("#")) {
            return raw.substring(0, raw.length() - 1).trim();
        }

        String normalized = stripTrailingSlash(raw);
        String normalizedDialect = normalizeDialect(dialect);
        if (StrUtil.endWithIgnoreCase(normalized, "/v1/chat/completions")
                || StrUtil.endWithIgnoreCase(normalized, "/v1/responses")
                || StrUtil.endWithIgnoreCase(normalized, "/api/chat")
                || StrUtil.endWithIgnoreCase(normalized, "/v1/messages")) {
            return normalized;
        }
        if (LlmConstants.PROVIDER_OPENAI.equals(normalizedDialect)) {
            if (StrUtil.endWithIgnoreCase(normalized, "/v1")) {
                return normalized + "/chat/completions";
            }
            return normalized + "/v1/chat/completions";
        }
        if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(normalizedDialect)) {
            if (StrUtil.endWithIgnoreCase(normalized, "/v1")) {
                return normalized + "/responses";
            }
            return normalized + "/v1/responses";
        }
        if (LlmConstants.PROVIDER_OLLAMA.equals(normalizedDialect)) {
            return normalized + "/api/chat";
        }
        if (LlmConstants.PROVIDER_GEMINI.equals(normalizedDialect)) {
            if (StrUtil.endWithIgnoreCase(normalized, "/v1beta")
                    || StrUtil.endWithIgnoreCase(normalized, "/v1")) {
                return normalized;
            }
            return normalized + "/v1beta";
        }
        if (LlmConstants.PROVIDER_ANTHROPIC.equals(normalizedDialect)) {
            return normalized + "/v1/messages";
        }
        return normalized;
    }

    /**
     * 构建模型List URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param dialect dialect 参数。
     * @return 返回创建好的模型List URL。
     */
    public static String buildModelListUrl(String baseUrl, String dialect) {
        return buildModelListUrl("", baseUrl, dialect);
    }

    /**
     * 构建模型List URL。
     *
     * @param providerKey 提供方键标识或键值。
     * @param baseUrl 待校验或访问的地址参数。
     * @param dialect dialect 参数。
     * @return 返回创建好的模型List URL。
     */
    public static String buildModelListUrl(String providerKey, String baseUrl, String dialect) {
        String raw = normalizedModelListCandidate(baseUrl);
        if (raw.length() == 0) {
            return "";
        }

        String normalized = stripTrailingSlash(raw);
        String normalizedDialect = normalizeDialect(dialect);
        if (LlmConstants.PROVIDER_OLLAMA.equals(normalizedDialect)) {
            return buildOllamaModelListUrl(normalized);
        }
        if (LlmConstants.PROVIDER_GEMINI.equals(normalizedDialect)) {
            return buildGeminiModelListUrl(normalized);
        }
        if (LlmConstants.PROVIDER_OPENAI.equals(normalizedDialect)
                || LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(normalizedDialect)) {
            return buildOpenAiCompatibleModelListUrl(providerKey, normalized);
        }
        if (LlmConstants.PROVIDER_ANTHROPIC.equals(normalizedDialect)) {
            return buildAnthropicModelListUrl(normalized);
        }
        return normalized;
    }

    /**
     * 执行derive基础URL相关逻辑。
     *
     * @param apiUrl 待校验或访问的地址参数。
     * @param dialect dialect 参数。
     * @return 返回derive Base URL结果。
     */
    public static String deriveBaseUrl(String apiUrl, String dialect) {
        String raw = StrUtil.nullToEmpty(apiUrl).trim();
        String normalizedDialect = normalizeDialect(dialect);
        if (raw.length() == 0) {
            return "";
        }

        if (LlmConstants.PROVIDER_OPENAI.equals(normalizedDialect)
                && StrUtil.endWithIgnoreCase(raw, "/v1/chat/completions")) {
            return raw.substring(0, raw.length() - "/v1/chat/completions".length());
        }
        if (LlmConstants.PROVIDER_OPENAI_RESPONSES.equals(normalizedDialect)
                && StrUtil.endWithIgnoreCase(raw, "/v1/responses")) {
            return raw.substring(0, raw.length() - "/v1/responses".length());
        }
        if (LlmConstants.PROVIDER_OLLAMA.equals(normalizedDialect)
                && StrUtil.endWithIgnoreCase(raw, "/api/chat")) {
            return raw.substring(0, raw.length() - "/api/chat".length());
        }
        if (LlmConstants.PROVIDER_GEMINI.equals(normalizedDialect)
                && StrUtil.endWithIgnoreCase(raw, "/v1beta")) {
            return raw.substring(0, raw.length() - "/v1beta".length());
        }
        if (LlmConstants.PROVIDER_ANTHROPIC.equals(normalizedDialect)
                && StrUtil.endWithIgnoreCase(raw, "/v1/messages")) {
            return raw.substring(0, raw.length() - "/v1/messages".length());
        }

        return raw + "#";
    }

    /**
     * 剥离Trailing斜杠命令。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Trailing Slash结果。
     */
    public static String stripTrailingSlash(String value) {
        String current = StrUtil.nullToEmpty(value).trim();
        while (current.endsWith("/")) {
            current = current.substring(0, current.length() - 1);
        }
        return current;
    }

    /**
     * 构建Open Ai Compatible模型List URL。
     *
     * @param providerKey 提供方键标识或键值。
     * @param normalized normalized 参数。
     * @return 返回创建好的Open Ai Compatible模型List URL。
     */
    private static String buildOpenAiCompatibleModelListUrl(String providerKey, String normalized) {
        if (isCanonicalHttpModelListUrl(normalized)) {
            return normalized;
        }
        if (StrUtil.endWithIgnoreCase(normalized, "/v1/chat/completions")) {
            return normalized.substring(0, normalized.length() - "/chat/completions".length())
                    + "/models";
        }
        if (StrUtil.endWithIgnoreCase(normalized, "/v1/responses")) {
            return normalized.substring(0, normalized.length() - "/responses".length()) + "/models";
        }
        if (isProviderAwareOpenAiCompatibleBase(providerKey, normalized)) {
            return normalized + "/models";
        }
        return StrUtil.endWithIgnoreCase(normalized, "/v1")
                ? normalized + "/models"
                : normalized + "/v1/models";
    }

    /**
     * 构建Anthropic模型List URL。
     *
     * @param normalized normalized 参数。
     * @return 返回创建好的Anthropic模型List URL。
     */
    private static String buildAnthropicModelListUrl(String normalized) {
        if (isCanonicalHttpModelListUrl(normalized)) {
            return normalized;
        }
        if (StrUtil.endWithIgnoreCase(normalized, "/v1/messages")) {
            return normalized.substring(0, normalized.length() - "/messages".length()) + "/models";
        }
        return StrUtil.endWithIgnoreCase(normalized, "/v1")
                ? normalized + "/models"
                : normalized + "/v1/models";
    }

    /**
     * 构建Ollama模型List URL。
     *
     * @param normalized normalized 参数。
     * @return 返回创建好的Ollama模型List URL。
     */
    private static String buildOllamaModelListUrl(String normalized) {
        if (StrUtil.endWithIgnoreCase(normalized, "/api/tags")) {
            return normalized;
        }
        if (StrUtil.endWithIgnoreCase(normalized, "/api/chat")) {
            return normalized.substring(0, normalized.length() - "/chat".length()) + "/tags";
        }
        return StrUtil.endWithIgnoreCase(normalized, "/api")
                ? normalized + "/tags"
                : normalized + "/api/tags";
    }

    /**
     * 构建Gemini模型List URL。
     *
     * @param normalized normalized 参数。
     * @return 返回创建好的Gemini模型List URL。
     */
    private static String buildGeminiModelListUrl(String normalized) {
        if (isGeminiModelListUrl(normalized)) {
            return normalized;
        }
        if (StrUtil.endWithIgnoreCase(normalized, ":generateContent")) {
            normalized = removeLastSegment(normalized);
        } else if (StrUtil.endWithIgnoreCase(normalized, ":streamGenerateContent")) {
            normalized = removeLastSegment(normalized);
        }
        if (isGeminiModelListUrl(normalized)) {
            return normalized;
        }
        if (StrUtil.endWithIgnoreCase(normalized, "/v1beta")
                || StrUtil.endWithIgnoreCase(normalized, "/v1")) {
            return normalized + "/models";
        }
        return normalized + "/v1beta/models";
    }

    /**
     * 判断是否提供方Aware Open Ai Compatible Base。
     *
     * @param providerKey 提供方键标识或键值。
     * @param normalizedBaseUrl 待校验或访问的地址参数。
     * @return 如果提供方Aware Open Ai Compatible Base满足条件则返回 true，否则返回 false。
     */
    private static boolean isProviderAwareOpenAiCompatibleBase(
            String providerKey, String normalizedBaseUrl) {
        if (StrUtil.isBlank(normalizedBaseUrl)) {
            return false;
        }
        String normalizedProviderKey =
                StrUtil.nullToEmpty(providerKey).trim().toLowerCase(Locale.ROOT);
        if ("openrouter".equals(normalizedProviderKey)) {
            return true;
        }
        return baseUrlHostMatches(normalizedBaseUrl, "openrouter.ai")
                || baseUrlHostMatches(normalizedBaseUrl, "moonshot.ai")
                || baseUrlHostMatches(normalizedBaseUrl, "moonshot.cn")
                || baseUrlHostMatches(normalizedBaseUrl, "kimi.com")
                || baseUrlHostMatches(normalizedBaseUrl, "deepseek.com")
                || baseUrlHostMatches(normalizedBaseUrl, "x.ai")
                || baseUrlHostMatches(normalizedBaseUrl, "siliconflow.cn");
    }

    /**
     * 判断是否规范HTTP模型List URL。
     *
     * @param normalizedUrl 待校验或访问的地址参数。
     * @return 如果规范HTTP模型List URL满足条件则返回 true，否则返回 false。
     */
    private static boolean isCanonicalHttpModelListUrl(String normalizedUrl) {
        return StrUtil.endWithIgnoreCase(normalizedUrl, "/models")
                || StrUtil.endWithIgnoreCase(normalizedUrl, "/v1/models")
                || StrUtil.endWithIgnoreCase(normalizedUrl, "/api/v1/models");
    }

    /**
     * 判断是否Gemini模型List URL。
     *
     * @param normalizedUrl 待校验或访问的地址参数。
     * @return 如果Gemini模型List URL满足条件则返回 true，否则返回 false。
     */
    private static boolean isGeminiModelListUrl(String normalizedUrl) {
        return StrUtil.endWithIgnoreCase(normalizedUrl, "/v1/models")
                || StrUtil.endWithIgnoreCase(normalizedUrl, "/v1beta/models");
    }

    /**
     * 移除Last Segment。
     *
     * @param normalizedUrl 待校验或访问的地址参数。
     * @return 返回Last Segment结果。
     */
    private static String removeLastSegment(String normalizedUrl) {
        int slash = normalizedUrl.lastIndexOf('/');
        return slash <= 0 ? normalizedUrl : normalizedUrl.substring(0, slash);
    }

    /**
     * 执行normalized模型列表Candidate相关逻辑。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @return 返回normalized模型List Candidate结果。
     */
    private static String normalizedModelListCandidate(String baseUrl) {
        String raw = normalizedBaseCandidate(baseUrl);
        int queryIndex = raw.indexOf('?');
        if (queryIndex >= 0) {
            raw = raw.substring(0, queryIndex).trim();
        }
        int fragmentIndex = raw.indexOf('#');
        if (fragmentIndex >= 0) {
            raw = raw.substring(0, fragmentIndex).trim();
        }
        return raw;
    }

    /**
     * 执行normalized基础Candidate相关逻辑。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @return 返回normalized Base Candidate结果。
     */
    private static String normalizedBaseCandidate(String baseUrl) {
        String raw = StrUtil.nullToEmpty(baseUrl).trim();
        if (raw.endsWith("#")) {
            raw = raw.substring(0, raw.length() - 1).trim();
        }
        return raw;
    }

    /**
     * 解析提供方URI。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的提供方URI。
     */
    private static URI parseProviderUri(String raw) {
        try {
            URI uri = new URI(raw);
            String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                uri = uri.parseServerAuthority();
                uri.getPort();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("provider.baseUrl 格式无效", e);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("provider.baseUrl 格式无效", e);
        }
    }

    /**
     * 判断是否存在Authority用户Info。
     *
     * @param uri 待校验或访问的地址参数。
     * @return 如果Authority用户Info满足条件则返回 true，否则返回 false。
     */
    private static boolean hasAuthorityUserInfo(URI uri) {
        String authority = StrUtil.nullToEmpty(uri.getRawAuthority());
        if (authority.length() == 0) {
            authority = StrUtil.nullToEmpty(uri.getAuthority());
        }
        return authority.indexOf('@') >= 0;
    }

    /**
     * 规范化Hostname。
     *
     * @param host 主机参数。
     * @return 返回Hostname结果。
     */
    private static String normalizeHostname(String host) {
        String value = StrUtil.nullToEmpty(host).trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
