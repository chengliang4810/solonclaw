package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;

/** Solon AI web tools wrapped with Hermes-style URL and website policy checks. */
public class HermesWebTools {
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
            return delegate.webfetch(url, format, timeoutSeconds);
        }
    }

    public static class SafeWebsearchTool {
        private final SecurityPolicyService securityPolicyService;
        private final WebsearchTool delegate;

        public SafeWebsearchTool(SecurityPolicyService securityPolicyService) {
            this(securityPolicyService, WebsearchTool.getInstance());
        }

        public SafeWebsearchTool(SecurityPolicyService securityPolicyService, WebsearchTool delegate) {
            this.securityPolicyService = securityPolicyService;
            this.delegate = delegate;
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
            return delegate.websearch(query, numResults, livecrawl, type, contextMaxCharacters);
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
            return delegate.handle(query, tokensNum);
        }
    }
}
