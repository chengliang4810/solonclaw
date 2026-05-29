package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HtmlUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import cn.hutool.core.util.StrUtil;
import java.lang.reflect.Array;
import java.net.URLDecoder;
import java.util.Date;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;

/** Solon AI web tools wrapped with Jimuqu URL and website policy checks. */
public class SolonClawWebTools {
    private static final String BRAVE_FREE_BACKEND = "brave-free";
    private static final String DDGS_BACKEND = "ddgs";
    private static final String BRAVE_SEARCH_ENDPOINT =
            "https://api.search.brave.com/res/v1/web/search";
    private static final String DDGS_SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final Pattern DDGS_RESULT_LINK_PATTERN =
            Pattern.compile(
                    "(?is)<a\\b[^>]*class\\s*=\\s*['\"][^'\"]*result__a[^'\"]*['\"][^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>");
    private static final Pattern DDGS_SNIPPET_PATTERN =
            Pattern.compile(
                    "(?is)<a\\b[^>]*class\\s*=\\s*['\"][^'\"]*result__snippet[^'\"]*['\"][^>]*>(.*?)</a>|<div\\b[^>]*class\\s*=\\s*['\"][^'\"]*result__snippet[^'\"]*['\"][^>]*>(.*?)</div>");

    private static String blockedMessage(SecurityPolicyService.UrlVerdict verdict) {
        return "BLOCKED: URL 安全策略阻止访问："
                + verdict.getMessage()
                + "\nURL: "
                + SecretRedactor.maskUrl(verdict.getUrl())
                + "\n请换用公开、可信且符合网站访问策略的地址。";
    }

    private static void check(SecurityPolicyService securityPolicyService, String toolName, Map<String, Object> args) {
        if (securityPolicyService == null) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkToolArgs(toolName, args);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(blockedMessage(verdict));
        }
    }

    private static void checkUrl(SecurityPolicyService securityPolicyService, String url) {
        if (securityPolicyService == null || StrUtil.isBlank(url)) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(blockedMessage(verdict));
        }
    }

    private static void checkFinalDocumentUrls(
            SecurityPolicyService securityPolicyService, Document document) {
        if (document == null) {
            return;
        }
        for (String url : securityPolicyService.extractUrlishValues(document.getMetadata())) {
            checkUrl(securityPolicyService, url);
        }
        List<String> keys =
                Arrays.asList("url", "sourceURL", "sourceUrl", "source_url", "finalUrl", "final_url");
        for (String key : keys) {
            Object value = document.getMetadata(key);
            if (value != null) {
                checkUrl(securityPolicyService, String.valueOf(value));
            }
        }
    }

    private static void checkReturnedUrls(SecurityPolicyService securityPolicyService, Object value) {
        if (securityPolicyService == null || value == null) {
            return;
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        checkReturnedUrls(securityPolicyService, value, visited);
    }

    private static void checkReturnedUrls(
            SecurityPolicyService securityPolicyService, Object value, Set<Object> visited) {
        if (value == null || visited.contains(value)) {
            return;
        }
        visited.add(value);
        if (value instanceof Document) {
            checkFinalDocumentUrls(securityPolicyService, (Document) value);
            checkReturnedUrls(securityPolicyService, ((Document) value).getContent(), visited);
            return;
        }
        if (value instanceof CharSequence) {
            checkReturnedTextUrls(securityPolicyService, value.toString());
            return;
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                checkReturnedUrls(securityPolicyService, item, visited);
            }
            return;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                checkReturnedUrls(securityPolicyService, Array.get(value, i), visited);
            }
            return;
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                checkReturnedUrls(securityPolicyService, item, visited);
            }
            return;
        }
        Object structured = structuredPojoValue(value);
        if (structured != value) {
            checkReturnedUrls(securityPolicyService, structured, visited);
            if (isEmptyStructuredValue(structured)) {
                checkReturnedTextUrls(securityPolicyService, String.valueOf(value));
            }
        } else if (shouldStructurePojo(value)) {
            checkReturnedTextUrls(securityPolicyService, String.valueOf(value));
        }
    }

    private static void checkReturnedTextUrls(SecurityPolicyService securityPolicyService, String text) {
        if (securityPolicyService == null) {
            return;
        }
        for (String url : securityPolicyService.extractUrlishValues(text)) {
            checkUrl(securityPolicyService, url);
        }
    }

    public static class SafeWebfetchTool {
        private final SecurityPolicyService securityPolicyService;
        private final WebfetchTool delegate;

        public SafeWebfetchTool(SecurityPolicyService securityPolicyService) {
            this(securityPolicyService, WebfetchTool.getInstance());
        }

        public SafeWebfetchTool(SecurityPolicyService securityPolicyService, WebfetchTool delegate) {
            this.securityPolicyService = securityPolicyService;
            this.delegate = delegate;
        }

        @ToolMapping(name = "webfetch", description = "从 URL 获取网页内容。返回格式支持 markdown, text 或 html。")
        public Document webfetch(
                @Param(name = "url", description = "目标网页的完整 URL（必须包含 http:// 或 https://）") String url,
                @Param(name = "format", required = false, defaultValue = "markdown", description = "返回格式：'markdown', 'text', 'html'") String format,
                @Param(name = "timeout", required = false, description = "超时时间（秒），最大 120 秒") Integer timeoutSeconds)
                throws Exception {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("url", url);
            check(securityPolicyService, ToolNameConstants.WEBFETCH, args);
            Document document = delegate.webfetch(url, format, timeoutSeconds);
            checkReturnedUrls(securityPolicyService, document);
            return safeDocument(document);
        }
    }

    public static class SafeWebsearchTool {
        private final SecurityPolicyService securityPolicyService;
        private final WebsearchTool delegate;
        private final AppConfig appConfig;
        private List<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider> webSearchProviders;

        public SafeWebsearchTool(SecurityPolicyService securityPolicyService) {
            this(securityPolicyService, WebsearchTool.getInstance(), null);
        }

        public SafeWebsearchTool(SecurityPolicyService securityPolicyService, WebsearchTool delegate) {
            this(securityPolicyService, delegate, null);
        }

        public SafeWebsearchTool(
                SecurityPolicyService securityPolicyService,
                WebsearchTool delegate,
                AppConfig appConfig) {
            this.securityPolicyService = securityPolicyService;
            this.delegate = delegate;
            this.appConfig = appConfig;
        }

        public void setWebSearchProviders(List<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider> providers) {
            this.webSearchProviders = providers;
        }

        @ToolMapping(name = "websearch", description = "执行实时web搜索")
        public Document websearch(
                @Param(name = "query", description = "查询关键字") String query,
                @Param(name = "numResults", required = false, defaultValue = "8", description = "返回的结果数量") Integer numResults,
                @Param(name = "livecrawl", required = false, defaultValue = "fallback", description = "实时爬行模式 (fallback/preferred)") String livecrawl,
                @Param(name = "type", required = false, defaultValue = "auto", description = "搜索类型 (auto/fast/deep)") String type,
                @Param(name = "contextMaxCharacters", required = false, defaultValue = "10000", description = "针对LLM优化的最大字符数") Integer contextMaxCharacters)
                throws Exception {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("query", query);
            check(securityPolicyService, ToolNameConstants.WEBSEARCH, args);
            int limit = Math.max(1, Math.min(numResults == null ? 8 : numResults.intValue(), 20));
            String backend = normalizedSearchBackend();
            Document pluginResult = tryPluginProvider(backend, query, limit);
            if (pluginResult != null) {
                checkReturnedUrls(securityPolicyService, pluginResult);
                return safeDocument(pluginResult);
            }
            if (BRAVE_FREE_BACKEND.equals(backend)) {
                Document document = braveSearch(query, numResults);
                checkReturnedUrls(securityPolicyService, document);
                return safeDocument(document);
            }
            if (DDGS_BACKEND.equals(backend)) {
                Document document = ddgsSearch(query, numResults);
                checkReturnedUrls(securityPolicyService, document);
                return safeDocument(document);
            }
            Document document =
                    delegate.websearch(query, numResults, livecrawl, type, contextMaxCharacters);
            checkReturnedUrls(securityPolicyService, document);
            return safeDocument(document);
        }

        private Document tryPluginProvider(String backend, String query, int limit) {
            if (webSearchProviders == null || webSearchProviders.isEmpty() || StrUtil.isBlank(backend)) {
                return null;
            }
            for (com.jimuqu.solon.claw.plugin.provider.WebSearchProvider provider : webSearchProviders) {
                if (provider.name().equals(backend) && provider.isAvailable()) {
                    List<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider.SearchResult> results =
                            provider.search(query, limit);
                    return toProviderDocument(results, query, backend);
                }
            }
            return null;
        }

        private Document toProviderDocument(
                List<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider.SearchResult> results,
                String query, String backend) {
            List<Map<String, Object>> web = new ArrayList<Map<String, Object>>();
            int pos = 1;
            for (com.jimuqu.solon.claw.plugin.provider.WebSearchProvider.SearchResult r : results) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("title", r.getTitle());
                item.put("url", r.getUrl());
                item.put("description", r.getDescription());
                item.put("position", Integer.valueOf(pos++));
                web.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", web);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("success", Boolean.TRUE);
            result.put("data", data);
            result.put("provider", backend);
            return new Document(ONode.serialize(result)).title("Web search: " + query);
        }

        private String normalizedSearchBackend() {
            if (appConfig == null || appConfig.getWeb() == null) {
                return "";
            }
            return StrUtil.nullToEmpty(appConfig.getWeb().getSearchBackend())
                    .trim()
                    .replace('_', '-')
                    .toLowerCase(Locale.ROOT);
        }

        private Document braveSearch(String query, Integer numResults) {
            String apiKey = resolveBraveSearchApiKey();
            if (!SecretValueGuard.hasUsableSecret(apiKey, 8)) {
                throw new IllegalStateException("BRAVE_SEARCH_API_KEY is not set");
            }
            int limit = Math.max(1, Math.min(numResults == null ? 8 : numResults.intValue(), 20));
            checkSearchEndpoint(BRAVE_SEARCH_ENDPOINT);
            String body = executeBraveSearchRequest(query, limit, apiKey);
            Object parsed = ONode.ofJson(body).toData();
            Map<String, Object> root = parsed instanceof Map ? castMap(parsed) : Collections.<String, Object>emptyMap();
            Map<String, Object> webData = castMap(root.get("web"));
            Object rawResults = webData.get("results");
            List<Map<String, Object>> web = new ArrayList<Map<String, Object>>();
            if (rawResults instanceof Collection) {
                for (Object rawHit : (Collection<?>) rawResults) {
                    if (web.size() >= limit) {
                        break;
                    }
                    Map<String, Object> hit = castMap(rawHit);
                    if (hit.isEmpty()) {
                        continue;
                    }
                    String url = StrUtil.nullToEmpty(stringValue(hit.get("url")));
                    checkUrl(securityPolicyService, url);
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("title", StrUtil.nullToEmpty(stringValue(hit.get("title"))));
                    item.put("url", url);
                    item.put("description", StrUtil.nullToEmpty(stringValue(hit.get("description"))));
                    item.put("position", Integer.valueOf(web.size() + 1));
                    web.add(item);
                }
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", web);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("success", Boolean.TRUE);
            result.put("data", data);
            result.put("provider", BRAVE_FREE_BACKEND);
            return new Document(ONode.serialize(result)).title("Web search: " + query);
        }

        private Document ddgsSearch(String query, Integer numResults) {
            int limit = Math.max(1, Math.min(numResults == null ? 8 : numResults.intValue(), 20));
            checkSearchEndpoint(DDGS_SEARCH_ENDPOINT);
            String body = executeDdgsSearchRequest(query, limit);
            List<Map<String, Object>> web = parseDdgsResults(body, limit);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", web);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("success", Boolean.TRUE);
            result.put("data", data);
            result.put("provider", DDGS_BACKEND);
            return new Document(ONode.serialize(result)).title("Web search: " + query);
        }

        private List<Map<String, Object>> parseDdgsResults(String body, int limit) {
            String html = StrUtil.nullToEmpty(body);
            Matcher linkMatcher = DDGS_RESULT_LINK_PATTERN.matcher(html);
            Matcher snippetMatcher = DDGS_SNIPPET_PATTERN.matcher(html);
            List<Map<String, Object>> web = new ArrayList<Map<String, Object>>();
            while (linkMatcher.find() && web.size() < limit) {
                String url = normalizeDdgsUrl(linkMatcher.group(1));
                if (StrUtil.isBlank(url)) {
                    continue;
                }
                checkUrl(securityPolicyService, url);
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("title", cleanHtmlText(linkMatcher.group(2)));
                item.put("url", url);
                item.put("description", nextDdgsSnippet(snippetMatcher));
                item.put("position", Integer.valueOf(web.size() + 1));
                web.add(item);
            }
            return web;
        }

        private String nextDdgsSnippet(Matcher snippetMatcher) {
            if (snippetMatcher == null || !snippetMatcher.find()) {
                return "";
            }
            String value = snippetMatcher.group(1);
            if (value == null) {
                value = snippetMatcher.group(2);
            }
            return cleanHtmlText(value);
        }

        private String normalizeDdgsUrl(String rawUrl) {
            String value = HtmlUtil.unescape(StrUtil.nullToEmpty(rawUrl)).trim();
            if (value.startsWith("//")) {
                value = "https:" + value;
            }
            int uddg = value.indexOf("uddg=");
            if (uddg >= 0) {
                String encoded = value.substring(uddg + 5);
                int amp = encoded.indexOf('&');
                if (amp >= 0) {
                    encoded = encoded.substring(0, amp);
                }
                try {
                    return URLDecoder.decode(encoded, "UTF-8");
                } catch (Exception ignored) {
                    return encoded;
                }
            }
            return value;
        }

        private String cleanHtmlText(String rawHtml) {
            String text = HtmlUtil.cleanHtmlTag(StrUtil.nullToEmpty(rawHtml));
            text = HtmlUtil.unescape(text);
            return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> castMap(Object value) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return Collections.emptyMap();
        }

        private String stringValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        protected void checkSearchEndpoint(String url) {
            checkUrl(securityPolicyService, url);
        }

        protected String executeBraveSearchRequest(String query, int limit, String apiKey) {
            HttpResponse response =
                    HttpRequest.get(BRAVE_SEARCH_ENDPOINT)
                            .form("q", query)
                            .form("count", Integer.valueOf(limit))
                            .header("X-Subscription-Token", apiKey)
                            .header("Accept", "application/json")
                            .timeout(15000)
                            .execute();
            int status = response.getStatus();
            String body = response.body();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Brave Search returned HTTP " + status);
            }
            if (StrUtil.isBlank(body)) {
                throw new IllegalStateException("Brave Search returned an empty response");
            }
            return body;
        }

        protected String executeDdgsSearchRequest(String query, int limit) {
            HttpResponse response =
                    HttpRequest.get(DDGS_SEARCH_ENDPOINT)
                            .form("q", query)
                            .form("kl", "wt-wt")
                            .form("dc", Integer.valueOf(0))
                            .header("Accept", "text/html,application/xhtml+xml")
                            .header("User-Agent", "Mozilla/5.0 Jimuqu-Agent")
                            .timeout(15000)
                            .execute();
            int status = response.getStatus();
            String body = response.body();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("DuckDuckGo search returned HTTP " + status);
            }
            if (StrUtil.isBlank(body)) {
                throw new IllegalStateException("DuckDuckGo search returned an empty response");
            }
            return body;
        }

        private String resolveBraveSearchApiKey() {
            String configured =
                    appConfig == null || appConfig.getWeb() == null
                            ? ""
                            : appConfig.getWeb().getBraveSearchApiKey();
            return StrUtil.blankToDefault(configured, System.getenv("BRAVE_SEARCH_API_KEY"));
        }
    }

    public static class SafeCodeSearchTool {
        private final SecurityPolicyService securityPolicyService;
        private final CodeSearchTool delegate;

        public SafeCodeSearchTool(SecurityPolicyService securityPolicyService) {
            this(securityPolicyService, CodeSearchTool.getInstance());
        }

        public SafeCodeSearchTool(SecurityPolicyService securityPolicyService, CodeSearchTool delegate) {
            this.securityPolicyService = securityPolicyService;
            this.delegate = delegate;
        }

        @ToolMapping(
                name = "codesearch",
                description =
                        "使用 Exa Code API 搜索并获取任何编程任务的相关上下文。适用于框架、库、SDK、API 和代码模式查询。")
        public Object codesearch(
                @Param(name = "query", description = "搜索查询词，用于查找 API、库和 SDK 的相关上下文。") String query,
                @Param(name = "tokensNum", required = false, defaultValue = "5000", description = "返回的 Token 数量 (1000-50000)。默认为 5000。") Integer tokensNum)
                throws Throwable {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("query", query);
            args.put("tokensNum", tokensNum);
            check(securityPolicyService, ToolNameConstants.CODESEARCH, args);
            Object result = delegate.handle(query, tokensNum);
            checkReturnedUrls(securityPolicyService, result);
            return safeValue(result);
        }
    }

    private static Document safeDocument(Document document) {
        if (document == null) {
            return null;
        }
        Document safe =
                new Document(
                                SecretRedactor.redact(document.getId(), 400),
                                SecretRedactor.redact(document.getContent()),
                                safeMetadata(document.getMetadata()),
                                document.getScore())
                        .embedding(document.getEmbedding());
        return safe;
    }

    private static Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        if (metadata == null || metadata.isEmpty()) {
            return safe;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            safe.put(SecretRedactor.redact(String.valueOf(entry.getKey()), 200), safeValue(entry.getValue()));
        }
        return safe;
    }

    private static Object safeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Document) {
            return safeDocument((Document) value);
        }
        if (value instanceof CharSequence) {
            return SecretRedactor.redact(String.valueOf(value));
        }
        if (value instanceof Map) {
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                safe.put(SecretRedactor.redact(String.valueOf(entry.getKey()), 200), safeValue(entry.getValue()));
            }
            return safe;
        }
        if (value instanceof Collection) {
            List<Object> safe = new ArrayList<Object>();
            for (Object item : (Collection<?>) value) {
                safe.add(safeValue(item));
            }
            return safe;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            List<Object> safe = new ArrayList<Object>();
            for (int i = 0; i < length; i++) {
                safe.add(safeValue(Array.get(value, i)));
            }
            return safe;
        }
        Object structured = structuredPojoValue(value);
        if (structured != value) {
            if (isEmptyStructuredValue(structured)) {
                return SecretRedactor.redact(String.valueOf(value));
            }
            return safeValue(structured);
        }
        if (shouldStructurePojo(value)) {
            return SecretRedactor.redact(String.valueOf(value));
        }
        return value;
    }

    private static boolean isEmptyStructuredValue(Object value) {
        return value instanceof Map && ((Map<?, ?>) value).isEmpty();
    }

    private static Object structuredPojoValue(Object value) {
        if (!shouldStructurePojo(value)) {
            return value;
        }
        try {
            return ONode.deserialize(ONode.serialize(value), Object.class);
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static boolean shouldStructurePojo(Object value) {
        if (value == null
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Date
                || value instanceof Enum) {
            return false;
        }
        Package pkg = value.getClass().getPackage();
        String packageName = pkg == null ? "" : pkg.getName();
        return !(packageName.startsWith("java.") || packageName.startsWith("javax."));
    }
}

