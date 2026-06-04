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
    private static final Set<String> DIALECTS =
            new LinkedHashSet<String>(LlmConstants.SUPPORTED_PROVIDERS);
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile(
                    "(?i)(?:^|[?&;])(?:access_token|refresh_token|id_token|token|api_key|apikey|client_secret|password|auth|jwt|session|secret|key|code|signature|x-amz-signature)=");

    private LlmProviderSupport() {}

    public static boolean isSupportedDialect(String dialect) {
        return DIALECTS.contains(normalizeDialect(dialect));
    }

    public static String normalizeDialect(String dialect) {
        return StrUtil.nullToEmpty(dialect).trim().toLowerCase();
    }

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

    public static boolean isDirectOpenAiBaseUrl(String baseUrl) {
        return baseUrlHostMatches(baseUrl, "api.openai.com");
    }

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

    public static boolean baseUrlHostMatches(String baseUrl, String domain) {
        String host = baseUrlHostname(baseUrl);
        String normalizedDomain = normalizeHostname(domain);
        if (host.length() == 0 || normalizedDomain.length() == 0) {
            return false;
        }
        return host.equals(normalizedDomain) || host.endsWith("." + normalizedDomain);
    }

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

    public static String buildModelListUrl(String baseUrl, String dialect) {
        return buildModelListUrl("", baseUrl, dialect);
    }

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

    public static String stripTrailingSlash(String value) {
        String current = StrUtil.nullToEmpty(value).trim();
        while (current.endsWith("/")) {
            current = current.substring(0, current.length() - 1);
        }
        return current;
    }

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

    private static boolean isCanonicalHttpModelListUrl(String normalizedUrl) {
        return StrUtil.endWithIgnoreCase(normalizedUrl, "/models")
                || StrUtil.endWithIgnoreCase(normalizedUrl, "/v1/models")
                || StrUtil.endWithIgnoreCase(normalizedUrl, "/api/v1/models");
    }

    private static boolean isGeminiModelListUrl(String normalizedUrl) {
        return StrUtil.endWithIgnoreCase(normalizedUrl, "/v1/models")
                || StrUtil.endWithIgnoreCase(normalizedUrl, "/v1beta/models");
    }

    private static String removeLastSegment(String normalizedUrl) {
        int slash = normalizedUrl.lastIndexOf('/');
        return slash <= 0 ? normalizedUrl : normalizedUrl.substring(0, slash);
    }

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

    private static String normalizedBaseCandidate(String baseUrl) {
        String raw = StrUtil.nullToEmpty(baseUrl).trim();
        if (raw.endsWith("#")) {
            raw = raw.substring(0, raw.length() - 1).trim();
        }
        return raw;
    }

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

    private static boolean hasAuthorityUserInfo(URI uri) {
        String authority = StrUtil.nullToEmpty(uri.getRawAuthority());
        if (authority.length() == 0) {
            authority = StrUtil.nullToEmpty(uri.getAuthority());
        }
        return authority.indexOf('@') >= 0;
    }

    private static String normalizeHostname(String host) {
        String value = StrUtil.nullToEmpty(host).trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
