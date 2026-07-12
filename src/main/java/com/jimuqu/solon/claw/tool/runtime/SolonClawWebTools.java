package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.talents.web.CodeSearchTalent;
import org.noear.solon.ai.talents.web.WebfetchTalent;
import org.noear.solon.ai.talents.web.WebsearchTalent;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供Solon项目Web工具能力，供 Agent 运行时按安全策略调用。 */
public class SolonClawWebTools {
    /** 记录 Web 工具中的可降级异常，日志不输出 URL、查询内容或网页正文。 */
    private static final Logger log = LoggerFactory.getLogger(SolonClawWebTools.class);

    /** 网页提取完整正文在工作区中的受限缓存目录。 */
    private static final String WEB_EXTRACT_CACHE_DIR = ".solonclaw/cache/web-extract";

    /** 每个工作区最多保留的网页提取正文数量，按最近写入淘汰。 */
    private static final int WEB_EXTRACT_CACHE_MAX_FILES = 20;

    /** 保护网页提取缓存清理过程，避免同一 JVM 的并发调用误删刚写入的文件。 */
    private static final Object WEB_EXTRACT_CACHE_LOCK = new Object();

    /** BRAVEFREEBACKEND的统一常量值。 */
    private static final String BRAVE_FREE_BACKEND = "brave-free";

    /** DDGSBACKEND的统一常量值。 */
    private static final String DDGS_BACKEND = "ddgs";

    /** BRAVE搜索ENDPO整型的统一常量值。 */
    private static final String BRAVE_SEARCH_ENDPOINT =
            "https://api.search.brave.com/res/v1/web/search";

    /** DDGS搜索ENDPO整型的统一常量值。 */
    private static final String DDGS_SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/";

    /** DDGS结果LINK正则的统一常量值。 */
    private static final Pattern DDGS_RESULT_LINK_PATTERN =
            Pattern.compile(
                    "(?is)<a\\b[^>]*class\\s*=\\s*['\"][^'\"]*result__a[^'\"]*['\"][^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>");

    /** DDGSSNIPPET正则的统一常量值。 */
    private static final Pattern DDGS_SNIPPET_PATTERN =
            Pattern.compile(
                    "(?is)<a\\b[^>]*class\\s*=\\s*['\"][^'\"]*result__snippet[^'\"]*['\"][^>]*>(.*?)</a>|<div\\b[^>]*class\\s*=\\s*['\"][^'\"]*result__snippet[^'\"]*['\"][^>]*>(.*?)</div>");

    /** 构建不会泄露敏感查询参数的 URL 阻断消息。 */
    private static String blockedMessage(SecurityPolicyService.UrlVerdict verdict) {
        return "BLOCKED: URL 安全策略阻止访问："
                + verdict.getMessage()
                + "\nURL: "
                + SecretRedactor.maskUrl(verdict.getUrl())
                + "\n请换用公开、可信且符合网站访问策略的地址。";
    }

    /**
     * 执行check相关逻辑。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     */
    private static void check(
            SecurityPolicyService securityPolicyService,
            String toolName,
            Map<String, Object> args) {
        if (securityPolicyService == null) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkToolArgs(toolName, args);
        if (!verdict.isAllowed()) {
            if (verdict.isApprovalRequired()) {
                throw new IllegalArgumentException(
                        "APPROVAL_REQUIRED: "
                                + verdict.getMessage()
                                + " url="
                                + SecretRedactor.maskUrl(verdict.getUrl())
                                + "。请先在对话审批该单次操作。");
            }
            throw new IllegalArgumentException(blockedMessage(verdict));
        }
    }

    /**
     * 检查URL。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param url 待校验或访问的 URL。
     */
    private static void checkUrl(SecurityPolicyService securityPolicyService, String url) {
        if (securityPolicyService == null || StrUtil.isBlank(url)) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkReturnedUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(blockedMessage(verdict));
        }
    }

    /**
     * 检查Final Document Urls。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param document document 参数。
     */
    private static void checkFinalDocumentUrls(
            SecurityPolicyService securityPolicyService, Document document) {
        if (document == null) {
            return;
        }
        checkReturnedTextUrls(securityPolicyService, document.getContent());
        for (String url : securityPolicyService.extractUrlishValues(document.getMetadata())) {
            checkUrl(securityPolicyService, url);
        }
        List<String> keys =
                Arrays.asList(
                        "url", "sourceURL", "sourceUrl", "source_url", "finalUrl", "final_url");
        for (String key : keys) {
            Object value = document.getMetadata(key);
            if (value != null) {
                checkUrl(securityPolicyService, String.valueOf(value));
            }
        }
    }

    /**
     * 检查Returned Urls。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param value 待规范化或校验的原始值。
     */
    private static void checkReturnedUrls(
            SecurityPolicyService securityPolicyService, Object value) {
        if (securityPolicyService == null || value == null) {
            return;
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        checkReturnedUrls(securityPolicyService, value, visited);
    }

    /**
     * 检查Returned Urls。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param value 待规范化或校验的原始值。
     * @param visited visited 参数。
     */
    private static void checkReturnedUrls(
            SecurityPolicyService securityPolicyService, Object value, Set<Object> visited) {
        if (value == null || visited.contains(value)) {
            return;
        }
        visited.add(value);
        if (value instanceof Document) {
            checkFinalDocumentUrls(securityPolicyService, (Document) value);
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

    /**
     * 检查Returned Text Urls。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param text 待处理文本。
     */
    private static void checkReturnedTextUrls(
            SecurityPolicyService securityPolicyService, String text) {
        if (securityPolicyService == null) {
            return;
        }
        for (String url : securityPolicyService.extractReturnedTextUrls(text)) {
            SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkReturnedUrl(url);
            if (!verdict.isAllowed() && !"URL 解析失败".equals(verdict.getMessage())) {
                throw new IllegalArgumentException(blockedMessage(verdict));
            }
        }
    }

    /** 提供Safe Webfetch工具能力，供 Agent 运行时按安全策略调用。 */
    public static class SafeWebfetchTool {
        /** 注入安全策略服务，用于调用对应业务能力。 */
        private final SecurityPolicyService securityPolicyService;

        /** 记录安全Webfetch中的委托。 */
        private final WebfetchTalent delegate;

        /**
         * 创建Safe Webfetch工具实例，并注入运行所需依赖。
         *
         * @param securityPolicyService 安全策略服务依赖。
         */
        public SafeWebfetchTool(SecurityPolicyService securityPolicyService) {
            this(securityPolicyService, new GuardedWebfetchTalent(securityPolicyService));
        }

        /**
         * 创建Safe Webfetch工具实例，并注入运行所需依赖。
         *
         * @param securityPolicyService 安全策略服务依赖。
         * @param delegate 委派参数。
         */
        public SafeWebfetchTool(
                SecurityPolicyService securityPolicyService, WebfetchTalent delegate) {
            this.securityPolicyService = securityPolicyService;
            this.delegate = delegate;
        }

        /**
         * 执行webfetch相关逻辑。
         *
         * @param url 待校验或访问的 URL。
         * @param format 格式参数。
         * @param timeoutSeconds 超时时间，单位为秒。
         * @return 返回webfetch结果。
         */
        @ToolMapping(name = "webfetch", description = "从 URL 获取网页内容。返回格式支持 markdown, text 或 html。")
        public Document webfetch(
                @Param(name = "url", description = "目标网页的完整 URL（必须包含 http:// 或 https://）")
                        String url,
                @Param(
                                name = "format",
                                required = false,
                                defaultValue = "markdown",
                                description = "返回格式：'markdown', 'text', 'html'")
                        String format,
                @Param(name = "timeout", required = false, description = "超时时间（秒），最大 120 秒")
                        Integer timeoutSeconds)
                throws Exception {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("url", url);
            check(securityPolicyService, ToolNameConstants.WEBFETCH, args);
            Document document =
                    documentFromTalent(
                            delegate.webfetch(url, format, timeoutSeconds), "Web fetch: " + url);
            checkReturnedUrls(securityPolicyService, document);
            return safeDocument(document);
        }
    }

    /** 提供批量网页提取能力，复用单页 webfetch 的安全检查与后端实现。 */
    public static class SafeWebExtractTool {
        /** 单页网页提取工具。 */
        private final SafeWebfetchTool webfetchTool;

        /** 文件工具可读取的工作区根目录；为空时保留直接构造工具的无落盘行为。 */
        private final Path workspaceRoot;

        /**
         * 创建批量网页提取工具。
         *
         * @param securityPolicyService 安全策略服务依赖。
         */
        public SafeWebExtractTool(SecurityPolicyService securityPolicyService) {
            this(securityPolicyService, new GuardedWebfetchTalent(securityPolicyService), null);
        }

        /**
         * 创建可注入网页后端的批量提取工具，便于复用和定向测试。
         *
         * @param securityPolicyService 安全策略服务依赖。
         * @param delegate 单页网页提取后端。
         */
        public SafeWebExtractTool(
                SecurityPolicyService securityPolicyService, WebfetchTalent delegate) {
            this(securityPolicyService, delegate, null);
        }

        /**
         * 创建可将截断正文保存至工作区的批量提取工具。
         *
         * @param securityPolicyService 安全策略服务依赖。
         * @param delegate 单页网页提取后端。
         * @param workspaceDir 文件工具允许读取的工作区根目录。
         */
        public SafeWebExtractTool(
                SecurityPolicyService securityPolicyService,
                WebfetchTalent delegate,
                String workspaceDir) {
            this(new SafeWebfetchTool(securityPolicyService, delegate), workspaceDir);
        }

        /**
         * 复用同一安全网页抓取工具，并将截断正文保存到对应工作区。
         *
         * @param webfetchTool 已配置的单页网页提取工具。
         * @param workspaceDir 文件工具允许读取的工作区根目录。
         */
        public SafeWebExtractTool(SafeWebfetchTool webfetchTool, String workspaceDir) {
            if (webfetchTool == null) {
                throw new IllegalArgumentException("webfetchTool is required");
            }
            this.webfetchTool = webfetchTool;
            this.workspaceRoot = resolveWorkspaceRoot(workspaceDir);
        }

        /**
         * 按输入顺序提取最多五个网页，并为每个输入独立返回成功或错误结果。
         *
         * @param urls URL 字符串或包含 url/href 的搜索结果对象。
         * @param format 返回格式。
         * @param charLimit 每页最大返回字符数。
         * @return 返回包含 results 数组的 JSON 文本。
         */
        @ToolMapping(
                name = "web_extract",
                description =
                        "Extract up to five web pages in one call. Each item may be a URL string or an object containing url/href; results preserve input order and report per-item errors.")
        public String webExtract(
                @Param(
                                name = "urls",
                                description =
                                        "URL list, maximum five items. Items may be strings or objects with url/href.")
                        List<Object> urls,
                @Param(
                                name = "format",
                                required = false,
                                defaultValue = "markdown",
                                description = "markdown, text, or html")
                        String format,
                @Param(
                                name = "char_limit",
                                required = false,
                                defaultValue = "15000",
                                description = "Per-page character budget, minimum 2000.")
                        Integer charLimit) {
            if (urls == null || urls.isEmpty()) {
                return ToolResultEnvelope.error("urls is required").toJson();
            }
            if (urls.size() > 5) {
                return ToolResultEnvelope.error("urls accepts at most 5 items").toJson();
            }
            String safeFormat =
                    StrUtil.blankToDefault(format, "markdown").trim().toLowerCase(Locale.ROOT);
            if (!"markdown".equals(safeFormat)
                    && !"text".equals(safeFormat)
                    && !"html".equals(safeFormat)) {
                return ToolResultEnvelope.error("format must be markdown, text, or html").toJson();
            }
            int safeCharLimit = charLimit == null ? 15000 : charLimit.intValue();
            if (safeCharLimit < 2000) {
                return ToolResultEnvelope.error("char_limit must be at least 2000").toJson();
            }
            List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < urls.size(); i++) {
                Object item = urls.get(i);
                String url = extractUrl(item);
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("url", SecretRedactor.maskUrl(url));
                result.put("title", "");
                result.put("content", "");
                if (StrUtil.isBlank(url)) {
                    result.put("error", "url is required");
                    results.add(result);
                    continue;
                }
                try {
                    Document document = webfetchTool.webfetch(url, safeFormat, null);
                    String content =
                            document == null ? "" : StrUtil.nullToEmpty(document.getContent());
                    result.put(
                            "title",
                            SecretRedactor.redact(
                                    document == null ? "" : document.getTitle(), 500));
                    if (StrUtil.isBlank(content)) {
                        result.put("error", "Content was inaccessible or not found");
                    } else {
                        String safeContent = SecretRedactor.redact(content);
                        String contentPath =
                                safeContent.length() > safeCharLimit
                                        ? persistFullContent(safeContent)
                                        : null;
                        result.put(
                                "content",
                                truncateExtractContent(safeContent, safeCharLimit, contentPath));
                        result.put("content_chars", Integer.valueOf(safeContent.length()));
                        if (StrUtil.isNotBlank(contentPath)) {
                            result.put("content_path", contentPath);
                            result.put("content_truncated", Boolean.TRUE);
                            result.put(
                                    "hint",
                                    "Full content is saved at "
                                            + contentPath
                                            + ". Use read_file with offset and limit to continue.");
                        }
                        result.put("error", null);
                    }
                } catch (Exception e) {
                    result.put(
                            "error",
                            SecretRedactor.redact(
                                    StrUtil.blankToDefault(
                                            e.getMessage(), e.getClass().getSimpleName()),
                                    1000));
                }
                results.add(result);
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("results", results);
            return ONode.serialize(response);
        }

        /**
         * 从字符串或搜索结果对象中读取 URL。
         *
         * @param item URL 输入项。
         * @return URL 文本，不可识别时返回空串。
         */
        private static String extractUrl(Object item) {
            if (item instanceof CharSequence) {
                return item.toString().trim();
            }
            if (item instanceof Map) {
                Object value = ((Map<?, ?>) item).get("url");
                if (value == null) {
                    value = ((Map<?, ?>) item).get("href");
                }
                return value == null ? "" : String.valueOf(value).trim();
            }
            return "";
        }

        /**
         * 按三比一保留页首与页尾，保证单页返回不超过模型指定字符预算。
         *
         * @param content 网页正文。
         * @param charLimit 字符预算。
         * @return 完整或截断后的正文。
         */
        private static String truncateExtractContent(
                String content, int charLimit, String contentPath) {
            String safe = SecretRedactor.redact(StrUtil.nullToEmpty(content));
            if (safe.length() <= charLimit) {
                return safe;
            }
            String marker =
                    StrUtil.isBlank(contentPath)
                            ? "\n\n[content truncated]\n\n"
                            : "\n\n[content truncated; full content saved at "
                                    + contentPath
                                    + "; use read_file with offset and limit]\n\n";
            int payload = Math.max(0, charLimit - marker.length());
            int head = payload * 3 / 4;
            int tail = payload - head;
            return safe.substring(0, head) + marker + safe.substring(safe.length() - tail);
        }

        /**
         * 将完整且已脱敏的网页正文写入工作区缓存，使模型可通过文件工具分段继续读取。
         *
         * @param content 已完成安全处理的网页正文。
         * @return 相对工作区的可读取路径；无法安全落盘时返回空值。
         */
        private String persistFullContent(String content) {
            if (workspaceRoot == null) {
                return null;
            }
            synchronized (WEB_EXTRACT_CACHE_LOCK) {
                try {
                    Path cacheDir = resolveCacheDirectory();
                    if (cacheDir == null) {
                        return null;
                    }
                    Path file =
                            cacheDir.resolve("web-extract-" + UUID.randomUUID().toString() + ".txt")
                                    .normalize();
                    if (!file.startsWith(cacheDir)) {
                        return null;
                    }
                    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
                    pruneCache(cacheDir, file);
                    return workspaceRoot.relativize(file).toString().replace('\\', '/');
                } catch (Exception e) {
                    log.warn("网页提取正文缓存失败 error={}", e.getClass().getSimpleName());
                    return null;
                }
            }
        }

        /**
         * 解析并验证缓存目录，拒绝通过工作区内符号链接把网页正文写到工作区外。
         *
         * @return 已解析的工作区内缓存目录；不安全或不可用时返回空值。
         */
        private Path resolveCacheDirectory() throws IOException {
            Path root = workspaceRoot.toRealPath();
            Path cacheDir = root.resolve(WEB_EXTRACT_CACHE_DIR).normalize();
            if (!cacheDir.startsWith(root)) {
                return null;
            }
            Path current = root;
            for (Path part : Paths.get(WEB_EXTRACT_CACHE_DIR)) {
                current = current.resolve(part);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current)
                            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        return null;
                    }
                } else {
                    Files.createDirectory(current);
                }
            }
            Path realCacheDir = cacheDir.toRealPath();
            return realCacheDir.startsWith(root) ? realCacheDir : null;
        }

        /**
         * 删除最早的缓存文件，确保网页正文缓存数量有上限且不跟随符号链接。
         *
         * @param cacheDir 已验证的缓存目录。
         * @param retained 刚写入且必须保留的文件。
         */
        private void pruneCache(Path cacheDir, Path retained) throws IOException {
            List<Path> files = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> entries = Files.list(cacheDir)) {
                entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .forEach(files::add);
            }
            files.sort(
                    (left, right) -> {
                        try {
                            return Files.getLastModifiedTime(left, LinkOption.NOFOLLOW_LINKS)
                                    .compareTo(
                                            Files.getLastModifiedTime(
                                                    right, LinkOption.NOFOLLOW_LINKS));
                        } catch (IOException e) {
                            return 0;
                        }
                    });
            int excess = files.size() - WEB_EXTRACT_CACHE_MAX_FILES;
            for (Path file : files) {
                if (excess <= 0) {
                    break;
                }
                if (!file.equals(retained)) {
                    Files.deleteIfExists(file);
                    excess--;
                }
            }
        }

        /**
         * 规范化工作区根目录，仅接受存在的目录以避免写入当前进程的任意相对位置。
         *
         * @param workspaceDir 运行时提供的工作区目录。
         * @return 规范化后的工作区目录；不可用时返回空值。
         */
        private static Path resolveWorkspaceRoot(String workspaceDir) {
            if (StrUtil.isBlank(workspaceDir)) {
                return null;
            }
            try {
                Path root = Paths.get(workspaceDir).toAbsolutePath().normalize();
                return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                        ? root.toRealPath()
                        : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 提供Safe Websearch工具能力，供 Agent 运行时按安全策略调用。 */
    public static class SafeWebsearchTool {
        /** 注入安全策略服务，用于调用对应业务能力。 */
        private final SecurityPolicyService securityPolicyService;

        /** 记录安全Websearch中的委托；为空时按需延迟创建 Solon AI 后端，避免非 Solon AI 后端提前初始化 MCP。 */
        private WebsearchTalent delegate;

        /** 注入应用配置，用于安全Websearch。 */
        private final AppConfig appConfig;

        /** 保存Web搜索Providers集合，维持调用顺序或去重语义。 */
        private List<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider> webSearchProviders;

        /**
         * 创建Safe Websearch工具实例，并注入运行所需依赖。
         *
         * @param securityPolicyService 安全策略服务依赖。
         */
        public SafeWebsearchTool(SecurityPolicyService securityPolicyService) {
            this(securityPolicyService, null, null);
        }

        /**
         * 创建Safe Websearch工具实例，并注入运行所需依赖。
         *
         * @param securityPolicyService 安全策略服务依赖。
         * @param delegate 委派参数。
         */
        public SafeWebsearchTool(
                SecurityPolicyService securityPolicyService, WebsearchTalent delegate) {
            this(securityPolicyService, delegate, null);
        }

        /**
         * 创建Safe Websearch工具实例，并注入运行所需依赖。
         *
         * @param securityPolicyService 安全策略服务依赖。
         * @param delegate 委派参数。
         * @param appConfig 应用运行配置。
         */
        public SafeWebsearchTool(
                SecurityPolicyService securityPolicyService,
                WebsearchTalent delegate,
                AppConfig appConfig) {
            this.securityPolicyService = securityPolicyService;
            this.delegate = delegate;
            this.appConfig = appConfig;
        }

        /**
         * 写入Web搜索Providers。
         *
         * @param providers 能力提供方列表。
         */
        public void setWebSearchProviders(
                List<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider> providers) {
            this.webSearchProviders =
                    providers == null
                            ? Collections
                                    .<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider>
                                            emptyList()
                            : providers;
        }

        /**
         * 创建 Solon AI websearch 委托，集中封装延迟初始化边界，便于插件后端绕过 MCP/Jackson 初始化。
         *
         * @return 返回 Solon AI websearch 委托。
         */
        protected WebsearchTalent createSolonAiWebsearchTalent() {
            return new WebsearchTalent();
        }

        /**
         * 读取或创建 Solon AI websearch 委托，初始化失败时转换为低敏业务错误。
         *
         * @return 返回可调用的 Solon AI websearch 委托。
         */
        private WebsearchTalent solonAiDelegate() {
            if (delegate != null) {
                return delegate;
            }
            try {
                delegate = createSolonAiWebsearchTalent();
                return delegate;
            } catch (Throwable e) {
                throw new IllegalStateException(
                        "Solon AI websearch backend is unavailable; configure searchBackend=exa, brave-free, or ddgs, then retry.",
                        e);
            }
        }

        /**
         * 执行websearch相关逻辑。
         *
         * @param query 查询参数。
         * @param numResults numResults响应或执行结果。
         * @param livecrawl livecrawl 参数。
         * @param type 类型参数。
         * @param contextMaxCharacters 上下文MaxCharacters上下文。
         * @return 返回websearch结果。
         */
        @ToolMapping(name = "websearch", description = "执行实时web搜索")
        public Document websearch(
                @Param(name = "query", description = "查询关键字") String query,
                @Param(
                                name = "numResults",
                                required = false,
                                defaultValue = "8",
                                description = "返回的结果数量")
                        Integer numResults,
                @Param(
                                name = "livecrawl",
                                required = false,
                                defaultValue = "fallback",
                                description = "实时爬行模式 (fallback/preferred)")
                        String livecrawl,
                @Param(
                                name = "type",
                                required = false,
                                defaultValue = "auto",
                                description = "搜索类型 (auto/fast/deep)")
                        String type,
                @Param(
                                name = "contextMaxCharacters",
                                required = false,
                                defaultValue = "10000",
                                description = "针对LLM优化的最大字符数")
                        Integer contextMaxCharacters)
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
            try {
                Document document =
                        documentFromTalent(
                                solonAiDelegate()
                                        .websearch(
                                                query,
                                                numResults,
                                                livecrawl,
                                                type,
                                                contextMaxCharacters),
                                "Web search: " + query);
                checkReturnedUrls(securityPolicyService, document);
                return safeDocument(document);
            } catch (IllegalStateException e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(
                        "Solon AI websearch backend is unavailable; configure searchBackend=exa, brave-free, or ddgs, then retry.",
                        e);
            }
        }

        /**
         * 执行try插件提供方相关逻辑。
         *
         * @param backend backend 参数。
         * @param query 查询参数。
         * @param limit 最大返回数量。
         * @return 返回try插件提供方结果。
         */
        private Document tryPluginProvider(String backend, String query, int limit) {
            if (webSearchProviders == null
                    || webSearchProviders.isEmpty()
                    || StrUtil.isBlank(backend)) {
                return null;
            }
            for (com.jimuqu.solon.claw.plugin.provider.WebSearchProvider provider :
                    webSearchProviders) {
                if (provider == null || !backend.equals(normalizedProviderName(provider))) {
                    continue;
                }
                Document result = tryProvider(provider, query, limit);
                if (result != null) {
                    return result;
                }
            }
            if (BRAVE_FREE_BACKEND.equals(backend)
                    || DDGS_BACKEND.equals(backend)
                    || "solon-ai".equals(backend)) {
                return null;
            }
            return null;
        }

        /**
         * 尝试调用单个插件搜索提供方；不可用或调用失败时返回空，由调用方继续后备链。
         *
         * @param provider 搜索提供方。
         * @param query 查询文本。
         * @param limit 最大返回数量。
         * @return 成功时返回搜索文档，否则返回空。
         */
        private Document tryProvider(
                com.jimuqu.solon.claw.plugin.provider.WebSearchProvider provider,
                String query,
                int limit) {
            try {
                if (!provider.isAvailable()) {
                    return null;
                }
                List<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider.SearchResult> results =
                        provider.search(query, limit);
                return toProviderDocument(results, query, normalizedProviderName(provider));
            } catch (RuntimeException e) {
                log.warn("Web 搜索插件调用失败，继续使用后备后端: {}", e.getClass().getSimpleName());
                return null;
            }
        }

        /** 将插件提供方名称统一为配置使用的后端格式。 */
        private String normalizedProviderName(
                com.jimuqu.solon.claw.plugin.provider.WebSearchProvider provider) {
            try {
                return StrUtil.nullToEmpty(provider == null ? null : provider.name())
                        .trim()
                        .replace('_', '-')
                        .toLowerCase(Locale.ROOT);
            } catch (RuntimeException e) {
                log.warn("Web 搜索插件名称读取失败，继续使用后备后端: {}", e.getClass().getSimpleName());
                return "";
            }
        }

        /**
         * 转换为提供方Document。
         *
         * @param results results响应或执行结果。
         * @param query 查询参数。
         * @param backend backend 参数。
         * @return 返回转换后的提供方Document。
         */
        private Document toProviderDocument(
                List<com.jimuqu.solon.claw.plugin.provider.WebSearchProvider.SearchResult> results,
                String query,
                String backend) {
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
            result.put("status", "success");
            result.put("data", data);
            result.put("provider", backend);
            return new Document(ONode.serialize(result)).title("Web search: " + query);
        }

        /**
         * 执行normalized搜索Backend相关逻辑。
         *
         * @return 返回normalized搜索Backend结果。
         */
        private String normalizedSearchBackend() {
            if (appConfig == null || appConfig.getWeb() == null) {
                return "";
            }
            return StrUtil.nullToEmpty(appConfig.getWeb().getSearchBackend())
                    .trim()
                    .replace('_', '-')
                    .toLowerCase(Locale.ROOT);
        }

        /**
         * 执行brave搜索相关逻辑。
         *
         * @param query 查询参数。
         * @param numResults numResults响应或执行结果。
         * @return 返回brave搜索结果。
         */
        private Document braveSearch(String query, Integer numResults) {
            String apiKey = resolveBraveSearchApiKey();
            if (!SecretValueGuard.hasUsableSecret(apiKey, 8)) {
                throw new IllegalStateException("BRAVE_SEARCH_API_KEY is not set");
            }
            int limit = Math.max(1, Math.min(numResults == null ? 8 : numResults.intValue(), 20));
            checkSearchEndpoint(BRAVE_SEARCH_ENDPOINT);
            String body = executeBraveSearchRequest(query, limit, apiKey);
            Object parsed = ONode.ofJson(body).toData();
            Map<String, Object> root =
                    parsed instanceof Map
                            ? castMap(parsed)
                            : Collections.<String, Object>emptyMap();
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
                    item.put(
                            "description",
                            StrUtil.nullToEmpty(stringValue(hit.get("description"))));
                    item.put("position", Integer.valueOf(web.size() + 1));
                    web.add(item);
                }
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", web);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "success");
            result.put("data", data);
            result.put("provider", BRAVE_FREE_BACKEND);
            return new Document(ONode.serialize(result)).title("Web search: " + query);
        }

        /**
         * 执行ddgs搜索相关逻辑。
         *
         * @param query 查询参数。
         * @param numResults numResults响应或执行结果。
         * @return 返回ddgs搜索结果。
         */
        private Document ddgsSearch(String query, Integer numResults) {
            int limit = Math.max(1, Math.min(numResults == null ? 8 : numResults.intValue(), 20));
            checkSearchEndpoint(DDGS_SEARCH_ENDPOINT);
            String body = executeDdgsSearchRequest(query, limit);
            List<Map<String, Object>> web = parseDdgsResults(body, limit);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("web", web);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "success");
            result.put("data", data);
            result.put("provider", DDGS_BACKEND);
            return new Document(ONode.serialize(result)).title("Web search: " + query);
        }

        /**
         * 解析Ddgs Results。
         *
         * @param body 请求体或消息正文内容。
         * @param limit 最大返回数量。
         * @return 返回解析后的Ddgs Results。
         */
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

        /**
         * 执行nextDdgsSnippet相关逻辑。
         *
         * @param snippetMatcher snippetMatcher 参数。
         * @return 返回next Ddgs Snippet结果。
         */
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

        /**
         * 规范化Ddgs URL。
         *
         * @param rawUrl 待校验或访问的地址参数。
         * @return 返回Ddgs URL结果。
         */
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
                } catch (Exception e) {
                    logRecoverableFailure("normalize-ddgs-url", e);
                    return encoded;
                }
            }
            return value;
        }

        /**
         * 清理Html文本。
         *
         * @param rawHtml 原始Html参数。
         * @return 返回clean Html Text结果。
         */
        private String cleanHtmlText(String rawHtml) {
            String text = HtmlUtil.cleanHtmlTag(StrUtil.nullToEmpty(rawHtml));
            text = HtmlUtil.unescape(text);
            return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        }

        /**
         * 执行cast映射相关逻辑。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回cast Map结果。
         */
        @SuppressWarnings("unchecked")
        private Map<String, Object> castMap(Object value) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return Collections.emptyMap();
        }

        /**
         * 将输入对象转换为去除首尾空白的字符串。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回string Value结果。
         */
        private String stringValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        /**
         * 检查搜索Endpoint。
         *
         * @param url 待校验或访问的 URL。
         */
        protected void checkSearchEndpoint(String url) {
            checkUrl(securityPolicyService, url);
        }

        /**
         * 执行Brave搜索请求。
         *
         * @param query 查询参数。
         * @param limit 最大返回数量。
         * @param apiKey api键标识或键值。
         * @return 返回Brave搜索请求结果。
         */
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

        /**
         * 执行Ddgs搜索请求。
         *
         * @param query 查询参数。
         * @param limit 最大返回数量。
         * @return 返回Ddgs搜索请求结果。
         */
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

        /**
         * 解析Brave搜索Api键。
         *
         * @return 返回解析后的Brave搜索Api键。
         */
        private String resolveBraveSearchApiKey() {
            String configured =
                    appConfig == null || appConfig.getWeb() == null
                            ? ""
                            : appConfig.getWeb().getBraveSearchApiKey();
            return StrUtil.blankToDefault(
                    configured, ProfileRuntimeScope.environmentValue("BRAVE_SEARCH_API_KEY"));
        }
    }

    /** 提供Safe Code搜索工具能力，供 Agent 运行时按安全策略调用。 */
    public static class SafeCodeSearchTool {
        /** 注入安全策略服务，用于调用对应业务能力。 */
        private final SecurityPolicyService securityPolicyService;

        /** 记录安全Code搜索中的委托。 */
        private final CodeSearchTalent delegate;

        /**
         * 创建Safe Code搜索工具实例，并注入运行所需依赖。
         *
         * @param securityPolicyService 安全策略服务依赖。
         */
        public SafeCodeSearchTool(SecurityPolicyService securityPolicyService) {
            this(securityPolicyService, new CodeSearchTalent());
        }

        /**
         * 创建Safe Code搜索工具实例，并注入运行所需依赖。
         *
         * @param securityPolicyService 安全策略服务依赖。
         * @param delegate 委派参数。
         */
        public SafeCodeSearchTool(
                SecurityPolicyService securityPolicyService, CodeSearchTalent delegate) {
            this.securityPolicyService = securityPolicyService;
            this.delegate = delegate;
        }

        /**
         * 执行codesearch相关逻辑。
         *
         * @param query 查询参数。
         * @param tokensNum tokenNum参数。
         * @return 返回codesearch结果。
         */
        @ToolMapping(
                name = "codesearch",
                description = "使用 Exa Code API 搜索并获取任何编程任务的相关上下文。适用于框架、库、SDK、API 和代码模式查询。")
        public Object codesearch(
                @Param(name = "query", description = "搜索查询词，用于查找 API、库和 SDK 的相关上下文。") String query,
                @Param(
                                name = "tokensNum",
                                required = false,
                                defaultValue = "5000",
                                description = "返回的 Token 数量 (1000-50000)。默认为 5000。")
                        Integer tokensNum)
                throws Throwable {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("query", query);
            args.put("tokensNum", tokensNum);
            check(securityPolicyService, ToolNameConstants.CODESEARCH, args);
            Object result = delegate.codesearch(query, tokensNum);
            checkReturnedUrls(securityPolicyService, result);
            return safeValue(result);
        }
    }

    /**
     * 生成安全展示用的Document。
     *
     * @param document document 参数。
     * @return 返回safe Document结果。
     */
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

    /**
     * 将 Solon AI 4 Talent 返回的纯文本包装成项目原有的 Document 结果，保留安全检查和下游展示契约。
     *
     * @param content Talent 返回的网页或搜索文本。
     * @param title 文档标题。
     * @return 返回可继续执行安全扫描的 Document。
     */
    private static Document documentFromTalent(String content, String title) {
        return new Document(StrUtil.nullToEmpty(content)).title(title);
    }

    /**
     * 生成安全展示用的元数据。
     *
     * @param metadata 元数据参数。
     * @return 返回safe元数据结果。
     */
    private static Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        if (metadata == null || metadata.isEmpty()) {
            return safe;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            safe.put(
                    SecretRedactor.redact(String.valueOf(entry.getKey()), 200),
                    safeValue(entry.getValue()));
        }
        return safe;
    }

    /**
     * 生成安全展示用的值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Value结果。
     */
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
                safe.put(
                        SecretRedactor.redact(String.valueOf(entry.getKey()), 200),
                        safeValue(entry.getValue()));
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

    /**
     * 判断是否Empty Structured Value。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Empty Structured Value满足条件则返回 true，否则返回 false。
     */
    private static boolean isEmptyStructuredValue(Object value) {
        return value instanceof Map && ((Map<?, ?>) value).isEmpty();
    }

    /**
     * 执行structuredPojo值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回structured Pojo Value结果。
     */
    private static Object structuredPojoValue(Object value) {
        if (!shouldStructurePojo(value)) {
            return value;
        }
        try {
            return ONode.deserialize(ONode.serialize(value), Object.class);
        } catch (Throwable e) {
            logRecoverableFailure("structure-pojo", e);
            return value;
        }
    }

    /**
     * 记录可恢复 Web 工具异常，只写阶段和异常类型，避免泄露 URL、token 或网页内容。
     *
     * @param stage 降级阶段。
     * @param error 异常对象。
     */
    private static void logRecoverableFailure(String stage, Throwable error) {
        if (log.isDebugEnabled()) {
            log.debug("web tool fallback. stage={} error={}", stage, exceptionSummary(error));
        }
    }

    /**
     * 生成低敏异常摘要，仅保留异常类型。
     *
     * @param error 异常对象。
     * @return 返回异常类型摘要。
     */
    private static String exceptionSummary(Throwable error) {
        return error == null ? "unknown" : error.getClass().getName();
    }

    /**
     * 判断是否需要Structure Pojo。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Structure Pojo满足条件则返回 true，否则返回 false。
     */
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
