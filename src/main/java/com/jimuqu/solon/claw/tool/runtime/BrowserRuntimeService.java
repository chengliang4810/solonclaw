package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.media.VisionAnalysisService;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.net.URI;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供浏览器运行时相关业务能力，封装调用方不需要感知的运行细节。 */
public class BrowserRuntimeService {
    /** 浏览器运行时内部降级日志，只记录阶段和异常类型，避免泄露页面或凭据信息。 */
    private static final Logger log = LoggerFactory.getLogger(BrowserRuntimeService.class);

    /** 默认最大并发数的统一常量值。 */
    private static final int DEFAULT_MAX_CONCURRENCY = 2;

    /** 默认超时时间秒数的统一常量值。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** 发现不安全跳转后清空页面时允许的最长等待秒数。 */
    private static final int UNSAFE_PAGE_CLEAR_TIMEOUT_SECONDS = 10;

    /** 浏览器快照和正文返回给模型的最大字符数。 */
    private static final int MAX_BROWSER_CONTENT_CHARS = 8000;

    /** 普通浏览器详情字符串返回给模型的最大字符数。 */
    private static final int MAX_BROWSER_DETAIL_CHARS = 500;

    /** 单个浏览器详情集合允许返回的最大元素数。 */
    private static final int MAX_BROWSER_DETAIL_ITEMS = 500;

    /** 浏览器详情递归清理允许进入的最大层级。 */
    private static final int MAX_BROWSER_DETAIL_DEPTH = 8;

    /** 注入应用配置，用于浏览器运行时。 */
    private final AppConfig appConfig;

    /** 保存providers集合，维持调用顺序或去重语义。 */
    private final List<BrowserProvider> providers;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 浏览器截图视觉分析器；未注入时只返回截图能力状态。 */
    private final BrowserVisionAnalyzer visionAnalyzer;

    /** 记录浏览器运行时中的maxConcurrency。 */
    private final int maxConcurrency;

    /** 保存leases映射，便于按键快速查询。 */
    private final ConcurrentMap<String, Lease> leases = new ConcurrentHashMap<String, Lease>();

    /**
     * 创建浏览器运行时服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param providers 能力提供方列表。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public BrowserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> providers,
            SecurityPolicyService securityPolicyService) {
        this(appConfig, providers, securityPolicyService, null, DEFAULT_MAX_CONCURRENCY);
    }

    /**
     * 创建浏览器运行时服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param providers 能力提供方列表。
     * @param securityPolicyService 安全策略服务依赖。
     * @param maxConcurrency maxConcurrency 参数。
     */
    public BrowserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> providers,
            SecurityPolicyService securityPolicyService,
            int maxConcurrency) {
        this(appConfig, providers, securityPolicyService, null, maxConcurrency);
    }

    /**
     * 创建带图片理解能力的浏览器运行时服务。
     *
     * @param appConfig 应用运行配置。
     * @param providers 能力提供方列表。
     * @param securityPolicyService 安全策略服务依赖。
     * @param visionAnalyzer 浏览器截图视觉分析器。
     */
    public BrowserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> providers,
            SecurityPolicyService securityPolicyService,
            BrowserVisionAnalyzer visionAnalyzer) {
        this(appConfig, providers, securityPolicyService, visionAnalyzer, DEFAULT_MAX_CONCURRENCY);
    }

    /**
     * 创建带图片理解能力且可配置并发上限的浏览器运行时服务。
     *
     * @param appConfig 应用运行配置。
     * @param providers 能力提供方列表。
     * @param securityPolicyService 安全策略服务依赖。
     * @param visionAnalyzer 浏览器截图视觉分析器。
     * @param maxConcurrency 浏览器会话最大并发数。
     */
    public BrowserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> providers,
            SecurityPolicyService securityPolicyService,
            BrowserVisionAnalyzer visionAnalyzer,
            int maxConcurrency) {
        this.appConfig = appConfig;
        this.providers =
                providers == null
                        ? Collections.<BrowserProvider>emptyList()
                        : new ArrayList<BrowserProvider>(providers);
        this.securityPolicyService = securityPolicyService;
        this.visionAnalyzer = visionAnalyzer;
        this.maxConcurrency = Math.max(0, maxConcurrency);
    }

    /**
     * 创建浏览器会话租约并初始化远程页面。
     *
     * @param taskId 任务标识。
     * @return 返回create结果。
     */
    public BrowserResult create(String taskId) {
        purgeExpiredLeases();
        BrowserProvider provider = selectProvider();
        if (provider == null) {
            return BrowserResult.error("browser_unavailable", "No available browser provider");
        }
        if (!hasLeaseCapacity()) {
            return BrowserResult.error("browser_busy", "Browser concurrency limit reached");
        }
        BrowserProvider.BrowserSession providerSession;
        try {
            providerSession = provider.createSession(StrUtil.blankToDefault(taskId, newTaskId()));
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
        if (providerSession == null || StrUtil.isBlank(providerSession.getSessionId())) {
            return BrowserResult.error(
                    "provider_error", "Browser provider did not create a session");
        }
        String leaseId = "browser-" + UUID.randomUUID().toString();
        Lease lease =
                new Lease(
                        leaseId,
                        provider,
                        providerSession,
                        System.currentTimeMillis() + DEFAULT_TIMEOUT_SECONDS * 1000L);
        if (!tryRegisterLease(lease)) {
            safeClose(provider, providerSession.getSessionId());
            return BrowserResult.error("browser_busy", "Browser concurrency limit reached");
        }
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("provider", safe(provider.name()));
        details.put("sessionId", leaseId);
        details.put("connectUrl", SecretRedactor.maskUrl(providerSession.getConnectUrl()));
        details.put("createdAt", lease.createdAt);
        details.put("timeoutSeconds", Integer.valueOf(DEFAULT_TIMEOUT_SECONDS));
        return BrowserResult.success(leaseId, "created", details);
    }

    /**
     * 导航浏览器会话到目标地址。
     *
     * @param sessionId 当前会话标识。
     * @param url 待校验或访问的 URL。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回navigate结果。
     */
    public BrowserResult navigate(String sessionId, String url, Integer timeoutSeconds) {
        SecurityPolicyService.UrlVerdict verdict = checkUrl(url);
        if (!verdict.isAllowed()) {
            return BrowserResult.error(
                    "security_blocked",
                    verdict.getMessage(),
                    Collections.<String, Object>singletonMap(
                            "url", SecretRedactor.maskUrl(verdict.getUrl())));
        }
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        LoopbackRewrite rewrite = rewriteLoopbackUrl(url);
        String browserUrl = rewrite.isRewritten() ? rewrite.rewrittenUrl : url;
        try {
            BrowserProvider.BrowserActionResult actionResult =
                    lease.provider.navigate(
                            lease.providerSession.getSessionId(),
                            browserUrl,
                            normalizeTimeout(timeoutSeconds));
            BrowserResult result =
                    toBrowserResult(lease, actionResult, "navigated", "url", browserUrl);
            if (!result.isSuccess() || !rewrite.isRewritten()) {
                return result;
            }
            Map<String, Object> details = result.getDetails();
            details.put("requestedUrl", SecretRedactor.maskUrl(url));
            details.put("urlRewrite", rewrite.toDetails());
            return BrowserResult.success(result.getSessionId(), result.getStatus(), details);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /**
     * 点击浏览器页面中的目标元素。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回click结果。
     */
    public BrowserResult click(String sessionId, String selector, Integer timeoutSeconds) {
        return action(sessionId, "clicked", selector, null, timeoutSeconds);
    }

    /**
     * 向浏览器页面中的目标元素输入文本。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param text 待处理文本。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回类型结果。
     */
    public BrowserResult type(
            String sessionId, String selector, String text, Integer timeoutSeconds) {
        return action(sessionId, "typed", selector, text, timeoutSeconds);
    }

    /**
     * 截取浏览器页面截图。
     *
     * @param sessionId 当前会话标识。
     * @param path 文件或目录路径。
     * @param fullPage fullPage 参数。
     * @return 返回screenshot结果。
     */
    public BrowserResult screenshot(String sessionId, String path, Boolean fullPage) {
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        String screenshotPath = resolveScreenshotPath(path);
        SecurityPolicyService.FileVerdict pathVerdict = checkScreenshotPath(screenshotPath);
        if (!pathVerdict.isAllowed()) {
            return BrowserResult.error(
                    "security_blocked",
                    pathVerdict.getMessage(),
                    Collections.<String, Object>singletonMap(
                            "path", ToolWorkspacePathSupport.safePath(pathVerdict.getPath())));
        }
        try {
            BrowserProvider.BrowserActionResult actionResult =
                    lease.provider.screenshot(
                            lease.providerSession.getSessionId(),
                            screenshotPath,
                            fullPage != null && fullPage.booleanValue());
            return toBrowserResult(lease, actionResult, "screenshot", "path", screenshotPath);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /**
     * 从浏览器页面提取指定内容。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param format 格式参数。
     * @return 返回extract结果。
     */
    public BrowserResult extract(String sessionId, String selector, String format) {
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        try {
            BrowserProvider.BrowserActionResult actionResult =
                    lease.provider.extract(
                            lease.providerSession.getSessionId(),
                            selector,
                            StrUtil.blankToDefault(format, "text"));
            return toBrowserResult(lease, actionResult, "extracted", "selector", selector);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /**
     * 获取页面文本快照和交互元素 ref。
     *
     * @param sessionId 当前会话标识。
     * @param full 是否包含完整页面正文。
     * @return 页面快照结果。
     */
    public BrowserResult snapshot(String sessionId, Boolean full) {
        return providerAction(
                sessionId,
                "snapshot",
                lease ->
                        lease.provider.snapshot(
                                lease.providerSession.getSessionId(),
                                full != null && full.booleanValue()));
    }

    /**
     * 滚动当前页面。
     *
     * @param sessionId 当前会话标识。
     * @param direction up 或 down。
     * @param pixels 可选滚动像素数。
     * @return 页面滚动结果。
     */
    public BrowserResult scroll(String sessionId, String direction, Integer pixels) {
        int distance =
                pixels == null ? 500 : Math.max(1, Math.min(Math.abs(pixels.intValue()), 5000));
        return providerAction(
                sessionId,
                "scrolled",
                lease ->
                        lease.provider.scroll(
                                lease.providerSession.getSessionId(), direction, distance));
    }

    /**
     * 返回浏览器历史中的上一页。
     *
     * @param sessionId 当前会话标识。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 历史导航结果。
     */
    public BrowserResult back(String sessionId, Integer timeoutSeconds) {
        return providerAction(
                sessionId,
                "back",
                lease ->
                        lease.provider.back(
                                lease.providerSession.getSessionId(),
                                normalizeTimeout(timeoutSeconds)));
    }

    /**
     * 向当前页面派发键盘按键。
     *
     * @param sessionId 当前会话标识。
     * @param key 按键名称。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 按键动作结果。
     */
    public BrowserResult press(String sessionId, String key, Integer timeoutSeconds) {
        return providerAction(
                sessionId,
                "pressed",
                lease ->
                        lease.provider.press(
                                lease.providerSession.getSessionId(),
                                key,
                                normalizeTimeout(timeoutSeconds)));
    }

    /**
     * 枚举当前页面图片。
     *
     * @param sessionId 当前会话标识。
     * @return 图片列表结果。
     */
    public BrowserResult getImages(String sessionId) {
        return providerAction(
                sessionId,
                "images",
                lease -> lease.provider.getImages(lease.providerSession.getSessionId()));
    }

    /**
     * 截图并如实返回视觉分析能力状态。
     *
     * @param sessionId 当前会话标识。
     * @param question 视觉问题。
     * @param annotate 是否请求标注交互元素。
     * @param path 可选截图输出路径。
     * @return 截图和视觉能力状态。
     */
    public BrowserResult vision(String sessionId, String question, Boolean annotate, String path) {
        if (StrUtil.isBlank(question)) {
            return BrowserResult.error("invalid_question", "Browser vision question is required");
        }
        String screenshotPath = resolveScreenshotPath(path);
        BrowserResult captured = screenshot(sessionId, screenshotPath, Boolean.TRUE);
        if (!captured.isSuccess()) {
            return captured;
        }
        Map<String, Object> details = captured.getDetails();
        details.put("questionLength", Integer.valueOf(question.length()));
        details.put("annotateRequested", Boolean.valueOf(Boolean.TRUE.equals(annotate)));
        details.put("annotated", Boolean.FALSE);
        if (visionAnalyzer == null) {
            details.put("analysisAvailable", Boolean.FALSE);
            details.put("capability", "capture_only");
            details.put(
                    "message",
                    "Screenshot captured; no visual analysis model is attached to the browser runtime");
            return BrowserResult.success(sessionId, "vision_capture_ready", details);
        }

        VisionAnalysisService.VisionAnalysisOutcome outcome;
        try {
            outcome = visionAnalyzer.analyze(screenshotPath, question.trim());
        } catch (Exception e) {
            logRecoverableBrowserFailure("vision_analysis", e);
            outcome = VisionAnalysisService.VisionAnalysisOutcome.fail("Vision analysis failed");
        }
        details.put("analysisAvailable", Boolean.TRUE);
        details.put("capability", "vision_analysis");
        if (outcome == null || !outcome.isSuccess()) {
            String error =
                    outcome == null
                            ? "Vision analysis returned no result"
                            : StrUtil.blankToDefault(outcome.getError(), "Vision analysis failed");
            return BrowserResult.error("vision_analysis_failed", error, details);
        }
        details.put("answer", outcome.getAnswer());
        details.put("provider", outcome.getProvider());
        details.put("model", outcome.getModel());
        details.put("usage", outcome.getUsage());
        return BrowserResult.success(sessionId, "vision_analyzed", details);
    }

    /**
     * 读取控制台消息或执行受控 JavaScript 表达式。
     *
     * @param sessionId 当前会话标识。
     * @param clear 读取后是否清空消息缓冲。
     * @param expression 可选 JavaScript 表达式。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 控制台结果。
     */
    public BrowserResult console(
            String sessionId, Boolean clear, String expression, Integer timeoutSeconds) {
        BrowserResult expressionVerdict = checkBrowserExpression(expression);
        if (expressionVerdict != null) {
            return expressionVerdict;
        }
        return providerAction(
                sessionId,
                "console",
                lease ->
                        lease.provider.console(
                                lease.providerSession.getSessionId(),
                                Boolean.TRUE.equals(clear),
                                expression,
                                normalizeTimeout(timeoutSeconds)));
    }

    /**
     * 发送通过安全校验的原始 CDP 命令。
     *
     * @param sessionId 当前会话标识。
     * @param method CDP 方法名。
     * @param params CDP 参数。
     * @param targetId 可选 Target 标识。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 原始 CDP 结果。
     */
    public BrowserResult cdp(
            String sessionId,
            String method,
            Map<String, Object> params,
            String targetId,
            Integer timeoutSeconds) {
        String normalizedMethod = StrUtil.nullToEmpty(method).trim();
        if (!normalizedMethod.matches("[A-Za-z][A-Za-z0-9]*\\.[A-Za-z][A-Za-z0-9]*")) {
            return BrowserResult.error("invalid_cdp_method", "CDP method name is invalid");
        }
        if (isSensitiveCdpMethod(normalizedMethod)) {
            return BrowserResult.error(
                    "security_blocked", "CDP method can expose browser credentials or storage");
        }
        Map<String, Object> safeParams =
                params == null
                        ? Collections.<String, Object>emptyMap()
                        : new LinkedHashMap<String, Object>(params);
        SecurityPolicyService.UrlVerdict verdict = checkToolArgs(safeParams);
        if (!verdict.isAllowed()) {
            return BrowserResult.error(
                    "security_blocked",
                    verdict.getMessage(),
                    Collections.<String, Object>singletonMap(
                            "url", SecretRedactor.maskUrl(verdict.getUrl())));
        }
        if ("Runtime.evaluate".equals(normalizedMethod)) {
            BrowserResult expressionVerdict =
                    checkBrowserExpression(String.valueOf(safeParams.get("expression")));
            if (expressionVerdict != null) {
                return expressionVerdict;
            }
        }
        return providerAction(
                sessionId,
                "cdp",
                lease ->
                        lease.provider.cdp(
                                lease.providerSession.getSessionId(),
                                normalizedMethod,
                                safeParams,
                                targetId,
                                normalizeTimeout(timeoutSeconds)));
    }

    /**
     * 响应当前页面阻塞中的原生 JavaScript 对话框。
     *
     * @param sessionId 当前会话标识。
     * @param action accept 或 dismiss。
     * @param promptText prompt 输入文本。
     * @param dialogId 可选对话框标识。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 对话框处理结果。
     */
    public BrowserResult dialog(
            String sessionId,
            String action,
            String promptText,
            String dialogId,
            Integer timeoutSeconds) {
        return providerAction(
                sessionId,
                "dialog",
                lease ->
                        lease.provider.dialog(
                                lease.providerSession.getSessionId(),
                                action,
                                promptText,
                                dialogId,
                                normalizeTimeout(timeoutSeconds)));
    }

    /**
     * 关闭当前组件持有的运行资源。
     *
     * @param sessionId 当前会话标识。
     * @return 返回close结果。
     */
    public BrowserResult close(String sessionId) {
        Lease lease = leases.remove(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        lease.closed.set(true);
        safeClose(lease.provider, lease.providerSession.getSessionId());
        return BrowserResult.success(sessionId, "closed", Collections.<String, Object>emptyMap());
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        for (String sessionId : new ArrayList<String>(leases.keySet())) {
            close(sessionId);
        }
    }

    /**
     * 统计当前活跃浏览器租约数量。
     *
     * @return 返回active Lease次数结果。
     */
    public int activeLeaseCount() {
        return leases.size();
    }

    /**
     * 执行action相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param action 操作参数。
     * @param selector 浏览器元素选择器。
     * @param text 待处理文本。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回action结果。
     */
    private BrowserResult action(
            String sessionId, String action, String selector, String text, Integer timeoutSeconds) {
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        try {
            BrowserProvider.BrowserActionResult actionResult;
            if ("clicked".equals(action)) {
                actionResult =
                        lease.provider.click(
                                lease.providerSession.getSessionId(),
                                selector,
                                normalizeTimeout(timeoutSeconds));
            } else {
                actionResult =
                        lease.provider.type(
                                lease.providerSession.getSessionId(),
                                selector,
                                text,
                                normalizeTimeout(timeoutSeconds));
            }
            return toBrowserResult(lease, actionResult, action, "selector", selector);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /**
     * 选择首个可用的浏览器能力提供方。
     *
     * @return 返回select提供方结果。
     */
    private BrowserProvider selectProvider() {
        for (BrowserProvider provider : providers) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    /**
     * 尝试注册浏览器会话租约并检查并发上限。
     *
     * @param lease lease 参数。
     * @return 返回try Register Lease结果。
     */
    private boolean tryRegisterLease(Lease lease) {
        synchronized (leases) {
            if (leases.size() >= maxConcurrency) {
                return false;
            }
            leases.put(lease.id, lease);
            return true;
        }
    }

    /**
     * 判断是否存在Lease Capacity。
     *
     * @return 如果Lease Capacity满足条件则返回 true，否则返回 false。
     */
    private boolean hasLeaseCapacity() {
        synchronized (leases) {
            return leases.size() < maxConcurrency;
        }
    }

    /** 清理已过期租约，避免空闲会话长期占用浏览器并发额度。 */
    private void purgeExpiredLeases() {
        List<Lease> expired = new ArrayList<Lease>();
        synchronized (leases) {
            for (Map.Entry<String, Lease> entry :
                    new ArrayList<Map.Entry<String, Lease>>(leases.entrySet())) {
                Lease lease = entry.getValue();
                if (lease == null || !isExpired(lease)) {
                    continue;
                }
                Lease removed = leases.remove(entry.getKey());
                if (removed != null && removed.closed.compareAndSet(false, true)) {
                    expired.add(removed);
                }
            }
        }
        for (Lease lease : expired) {
            safeClose(lease.provider, lease.providerSession.getSessionId());
        }
    }

    /**
     * 查找Lease。
     *
     * @param sessionId 当前会话标识。
     * @return 返回Lease结果。
     */
    private Lease findLease(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        return leases.get(sessionId);
    }

    /**
     * 判断是否Expired。
     *
     * @param lease lease 参数。
     * @return 如果Expired满足条件则返回 true，否则返回 false。
     */
    private boolean isExpired(Lease lease) {
        return lease.expiresAtMillis > 0L && System.currentTimeMillis() > lease.expiresAtMillis;
    }

    /**
     * 检查URL。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回URL结果。
     */
    private SecurityPolicyService.UrlVerdict checkUrl(String url) {
        if (securityPolicyService == null) {
            return SecurityPolicyService.UrlVerdict.allow();
        }
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("url", url);
        return securityPolicyService.checkToolArgs(ToolNameConstants.BROWSER, args);
    }

    /**
     * 检查结构化浏览器参数中的 URL 和凭据字段。
     *
     * @param args 浏览器参数。
     * @return URL 判定结果。
     */
    private SecurityPolicyService.UrlVerdict checkToolArgs(Map<String, Object> args) {
        if (securityPolicyService == null) {
            return SecurityPolicyService.UrlVerdict.allow();
        }
        return securityPolicyService.checkToolArgs(ToolNameConstants.BROWSER, args);
    }

    /**
     * 阻断会读取浏览器凭据、存储或主动联网的高风险表达式。
     *
     * @param expression JavaScript 表达式。
     * @return 阻断结果；允许时返回 null。
     */
    private BrowserResult checkBrowserExpression(String expression) {
        if (StrUtil.isBlank(expression)) {
            return null;
        }
        String normalized = expression.toLowerCase(java.util.Locale.ROOT);
        String[] blocked = {
            "document.cookie",
            "localstorage",
            "sessionstorage",
            "indexeddb",
            "xmlhttprequest",
            "fetch(",
            "websocket(",
            "navigator.credentials",
            ".password",
            "input[type=password]"
        };
        for (String token : blocked) {
            if (normalized.contains(token)) {
                return BrowserResult.error(
                        "security_blocked",
                        "Browser JavaScript expression uses a sensitive primitive");
            }
        }
        return null;
    }

    /**
     * 判断原始 CDP 方法是否直接读取浏览器凭据或持久化存储。
     *
     * @param method CDP 方法名。
     * @return 敏感方法返回 true。
     */
    private boolean isSensitiveCdpMethod(String method) {
        return "Network.getAllCookies".equals(method)
                || "Network.getCookies".equals(method)
                || "Storage.getCookies".equals(method)
                || "Storage.getTrustTokens".equals(method)
                || "Storage.getSharedStorageMetadata".equals(method)
                || "Storage.getSharedStorageEntries".equals(method)
                || "DOMStorage.getDOMStorageItems".equals(method);
    }

    /**
     * 在统一租约、超时和跳转后 URL 复核边界内执行 Provider 动作。
     *
     * @param sessionId 当前会话标识。
     * @param defaultStatus 默认成功状态。
     * @param action Provider 动作。
     * @return 浏览器动作结果。
     */
    private BrowserResult providerAction(
            String sessionId, String defaultStatus, LeaseAction action) {
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        try {
            return toBrowserResult(lease, action.execute(lease), defaultStatus, null, null);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /** 在已校验的浏览器租约上执行单个 Provider 动作。 */
    private interface LeaseAction {
        /**
         * 执行浏览器 Provider 动作。
         *
         * @param lease 已通过存在性和过期检查的浏览器租约。
         * @return Provider 动作结果。
         * @throws Exception Provider 调用失败时抛出。
         */
        BrowserProvider.BrowserActionResult execute(Lease lease) throws Exception;
    }

    /** 把浏览器截图交给图片理解服务，便于测试和运行时按需注入。 */
    @FunctionalInterface
    public interface BrowserVisionAnalyzer {
        /**
         * 分析本地缓存中的浏览器截图。
         *
         * @param imagePath 已通过截图路径策略的本地图片路径。
         * @param question 用户针对截图提出的问题。
         * @return 图片理解服务结果。
         */
        VisionAnalysisService.VisionAnalysisOutcome analyze(String imagePath, String question);
    }

    /**
     * 校验截图输出路径的写入安全策略。
     *
     * @param path 截图输出路径。
     * @return 文件路径判定结果。
     */
    private SecurityPolicyService.FileVerdict checkScreenshotPath(String path) {
        if (securityPolicyService == null) {
            return SecurityPolicyService.FileVerdict.allow();
        }
        return securityPolicyService.checkPath(path, true);
    }

    /**
     * 为未指定路径的截图生成工作区缓存路径。
     *
     * @param path 调用方提供的可选路径。
     * @return 实际截图输出路径。
     */
    private String resolveScreenshotPath(String path) {
        if (StrUtil.isNotBlank(path)) {
            return path.trim();
        }
        String cacheDir =
                appConfig == null || appConfig.getRuntime() == null
                        ? ""
                        : appConfig.getRuntime().getCacheDir();
        if (StrUtil.isBlank(cacheDir)) {
            String workspaceDir =
                    appConfig == null || appConfig.getWorkspace() == null
                            ? "workspace"
                            : StrUtil.blankToDefault(
                                    appConfig.getWorkspace().getDir(), "workspace");
            cacheDir = Paths.get(workspaceDir, "cache").toString();
        }
        return Paths.get(
                        cacheDir,
                        "browser-screenshots",
                        "browser-" + UUID.randomUUID().toString() + ".png")
                .toString();
    }

    /**
     * 转换为浏览器结果。
     *
     * @param lease lease 参数。
     * @param actionResult action结果响应或执行结果。
     * @param defaultStatus 默认状态参数。
     * @param fallbackKey 兜底键标识或键值。
     * @param fallbackValue 兜底值参数。
     * @return 返回转换后的浏览器结果。
     */
    private BrowserResult toBrowserResult(
            Lease lease,
            BrowserProvider.BrowserActionResult actionResult,
            String defaultStatus,
            String fallbackKey,
            String fallbackValue) {
        if (actionResult == null) {
            return BrowserResult.error(
                    "provider_error", "Browser provider returned no action result");
        }
        if (!actionResult.isSuccess()) {
            return BrowserResult.error(
                    StrUtil.blankToDefault(actionResult.getErrorCode(), "provider_error"),
                    StrUtil.blankToDefault(
                            actionResult.getErrorMessage(), "Browser provider action failed"));
        }
        SecurityPolicyService.UrlVerdict verdict = checkProviderUrl(actionResult.getCurrentUrl());
        if (!verdict.isAllowed()) {
            clearUnsafePage(lease);
            close(lease.id);
            return BrowserResult.error(
                    "security_blocked",
                    verdict.getMessage(),
                    Collections.<String, Object>singletonMap(
                            "url", SecretRedactor.maskUrl(verdict.getUrl())));
        }
        Map<String, Object> details = sanitizeDetails(actionResult.getDetails());
        if (StrUtil.isNotBlank(actionResult.getCurrentUrl())) {
            details.put("currentUrl", SecretRedactor.maskUrl(actionResult.getCurrentUrl()));
        }
        if (StrUtil.isNotBlank(fallbackKey)
                && StrUtil.isNotBlank(fallbackValue)
                && !details.containsKey(fallbackKey)) {
            details.put(fallbackKey, sanitizeFallbackValue(fallbackKey, fallbackValue));
        }
        refreshLease(lease);
        return BrowserResult.success(
                lease.id, StrUtil.blankToDefault(actionResult.getStatus(), defaultStatus), details);
    }

    /**
     * 在关闭租约前将浏览器切离不安全页面，避免远端会话关闭存在延迟时继续保留敏感页面状态。
     *
     * @param lease 已进入不安全最终 URL 的浏览器租约。
     */
    private void clearUnsafePage(Lease lease) {
        try {
            BrowserProvider.BrowserActionResult result =
                    lease.provider.navigate(
                            lease.providerSession.getSessionId(),
                            "about:blank",
                            UNSAFE_PAGE_CLEAR_TIMEOUT_SECONDS);
            if (result == null || !result.isSuccess()) {
                log.warn("浏览器不安全页面清理未完成：stage=clear_unsafe_page");
            }
        } catch (Exception e) {
            log.warn("浏览器不安全页面清理失败：stage=clear_unsafe_page, error={}", exceptionSummary(e));
        }
    }

    /**
     * 刷新浏览器租约过期时间，避免活跃会话在固定创建时间后被误判超时。
     *
     * @param lease 当前浏览器租约。
     */
    private void refreshLease(Lease lease) {
        lease.expiresAtMillis = System.currentTimeMillis() + DEFAULT_TIMEOUT_SECONDS * 1000L;
    }

    /**
     * 检查提供方URL。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回提供方URL结果。
     */
    private SecurityPolicyService.UrlVerdict checkProviderUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return SecurityPolicyService.UrlVerdict.allow();
        }
        return checkUrl(url);
    }

    /**
     * 清理Details。
     *
     * @param rawDetails 原始Details参数。
     * @return 返回Details结果。
     */
    private Map<String, Object> sanitizeDetails(Map<String, Object> rawDetails) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        if (rawDetails == null) {
            return details;
        }
        for (Map.Entry<String, Object> entry : rawDetails.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("text".equalsIgnoreCase(key) || "input".equalsIgnoreCase(key)) {
                details.put(key, "[redacted]");
                details.put(
                        key + "Length",
                        Integer.valueOf(StrUtil.nullToEmpty(String.valueOf(value)).length()));
            } else {
                details.put(key, sanitizeDetailValue(key, value, 0));
            }
        }
        return details;
    }

    /**
     * 递归清理 Provider 返回的 Map、List、数组和标量，避免原始 CDP 或页面对象绕过脱敏边界。
     *
     * @param key 当前值对应的字段名。
     * @param value Provider 返回值。
     * @param depth 当前递归深度。
     * @return 可安全返回给模型的有界值。
     */
    private Object sanitizeDetailValue(String key, Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth >= MAX_BROWSER_DETAIL_DEPTH) {
            return "[truncated: maximum nesting depth reached]";
        }
        String normalizedKey = StrUtil.nullToEmpty(key).trim().toLowerCase(java.util.Locale.ROOT);
        if (isSensitiveDetailKey(normalizedKey)) {
            return "[redacted]";
        }
        if (value instanceof Map) {
            return sanitizeDetailMap((Map<?, ?>) value, depth + 1);
        }
        if (value instanceof Iterable) {
            return sanitizeDetailIterable((Iterable<?>) value, depth + 1);
        }
        if (value.getClass().isArray()) {
            return sanitizeDetailArray(value, depth + 1);
        }
        String text = String.valueOf(value);
        int limit =
                isLongBrowserContentKey(normalizedKey)
                        ? MAX_BROWSER_CONTENT_CHARS
                        : MAX_BROWSER_DETAIL_CHARS;
        if (isUrlDetailKey(normalizedKey)) {
            text = SecretRedactor.maskUrl(text);
        }
        return SecretRedactor.redact(text, limit);
    }

    /**
     * 递归清理浏览器详情 Map，并限制单次返回的键数量。
     *
     * @param rawMap Provider 返回的原始 Map。
     * @param depth 当前递归深度。
     * @return 清理后的有序 Map。
     */
    private Map<String, Object> sanitizeDetailMap(Map<?, ?> rawMap, int depth) {
        Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
        int count = 0;
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if (count >= MAX_BROWSER_DETAIL_ITEMS) {
                sanitized.put(
                        "_truncatedEntries",
                        Integer.valueOf(Math.max(0, rawMap.size() - MAX_BROWSER_DETAIL_ITEMS)));
                break;
            }
            String childKey = SecretRedactor.redact(String.valueOf(entry.getKey()), 200);
            sanitized.put(childKey, sanitizeDetailValue(childKey, entry.getValue(), depth));
            count++;
        }
        return sanitized;
    }

    /**
     * 递归清理浏览器详情集合，并限制单次返回的元素数量。
     *
     * @param values Provider 返回的可迭代集合。
     * @param depth 当前递归深度。
     * @return 清理后的有界列表。
     */
    private List<Object> sanitizeDetailIterable(Iterable<?> values, int depth) {
        List<Object> sanitized = new ArrayList<Object>();
        int count = 0;
        for (Object value : values) {
            if (count >= MAX_BROWSER_DETAIL_ITEMS) {
                sanitized.add("[truncated: additional items omitted]");
                break;
            }
            sanitized.add(sanitizeDetailValue("", value, depth));
            count++;
        }
        return sanitized;
    }

    /**
     * 递归清理浏览器详情数组，并限制单次返回的元素数量。
     *
     * @param values Provider 返回的数组。
     * @param depth 当前递归深度。
     * @return 清理后的有界列表。
     */
    private List<Object> sanitizeDetailArray(Object values, int depth) {
        List<Object> sanitized = new ArrayList<Object>();
        int length = java.lang.reflect.Array.getLength(values);
        int limit = Math.min(length, MAX_BROWSER_DETAIL_ITEMS);
        for (int i = 0; i < limit; i++) {
            sanitized.add(sanitizeDetailValue("", java.lang.reflect.Array.get(values, i), depth));
        }
        if (length > limit) {
            sanitized.add("[truncated: " + (length - limit) + " additional items omitted]");
        }
        return sanitized;
    }

    /**
     * 判断字段是否携带凭据、输入文本或其他不应回显的敏感值。
     *
     * @param normalizedKey 已转换为小写的字段名。
     * @return 敏感字段返回 true。
     */
    private boolean isSensitiveDetailKey(String normalizedKey) {
        return "input".equals(normalizedKey)
                || "prompttext".equals(normalizedKey)
                || normalizedKey.contains("password")
                || normalizedKey.contains("passwd")
                || normalizedKey.contains("secret")
                || normalizedKey.contains("credential")
                || normalizedKey.contains("authorization")
                || normalizedKey.contains("cookie")
                || normalizedKey.contains("api_key")
                || normalizedKey.contains("apikey")
                || normalizedKey.equals("token")
                || normalizedKey.endsWith("token");
    }

    /**
     * 判断字段是否表示 URL，需要隐藏查询凭据和用户信息。
     *
     * @param normalizedKey 已转换为小写的字段名。
     * @return URL 字段返回 true。
     */
    private boolean isUrlDetailKey(String normalizedKey) {
        return normalizedKey.contains("url")
                || "src".equals(normalizedKey)
                || "href".equals(normalizedKey);
    }

    /**
     * 判断字段是否允许返回较长的页面正文内容。
     *
     * @param normalizedKey 已转换为小写的字段名。
     * @return 页面快照或提取正文返回 true。
     */
    private boolean isLongBrowserContentKey(String normalizedKey) {
        return "snapshot".equals(normalizedKey) || "content".equals(normalizedKey);
    }

    /**
     * 清理兜底Value。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回兜底Value结果。
     */
    private Object sanitizeFallbackValue(String key, String value) {
        if (StrUtil.nullToEmpty(key).toLowerCase(java.util.Locale.ROOT).contains("url")) {
            return SecretRedactor.maskUrl(value);
        }
        return safe(value);
    }

    /**
     * 规范化Timeout。
     *
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回Timeout结果。
     */
    private int normalizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.max(1, Math.min(timeoutSeconds.intValue(), 300));
    }

    /**
     * 创建任务标识。
     *
     * @return 返回创建好的任务标识。
     */
    private String newTaskId() {
        return "browser-task-" + UUID.randomUUID().toString();
    }

    /**
     * 执行安全相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe结果。
     */
    private String safe(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 500);
    }

    /**
     * 执行rewriteLoopbackURL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回rewrite Loopback URL结果。
     */
    private LoopbackRewrite rewriteLoopbackUrl(String url) {
        if (appConfig == null
                || appConfig.getSecurity() == null
                || !appConfig.getSecurity().isRewriteBrowserLoopbackUrls()) {
            return LoopbackRewrite.none(url);
        }
        String alias =
                StrUtil.nullToEmpty(appConfig.getSecurity().getBrowserLoopbackHostAlias()).trim();
        if (alias.length() == 0) {
            return LoopbackRewrite.none(url);
        }
        URI uri;
        try {
            uri = URI.create(StrUtil.nullToEmpty(url).trim());
        } catch (Exception e) {
            logRecoverableBrowserFailure("loopback_rewrite_parse", e);
            return LoopbackRewrite.none(url);
        }
        String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return LoopbackRewrite.none(url);
        }
        String host = uri.getHost();
        if (!isLoopbackHost(host)) {
            return LoopbackRewrite.none(url);
        }
        String rewritten = rebuildUrl(uri, alias);
        if (StrUtil.isBlank(rewritten) || rewritten.equals(url)) {
            return LoopbackRewrite.none(url);
        }
        return new LoopbackRewrite(url, rewritten, host, alias);
    }

    /**
     * 判断是否Loopback Host。
     *
     * @param host 主机参数。
     * @return 如果Loopback Host满足条件则返回 true，否则返回 false。
     */
    private boolean isLoopbackHost(String host) {
        String value = StrUtil.nullToEmpty(host).trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if ("localhost".equals(value) || "::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value)) {
            return true;
        }
        if (value.startsWith("::ffff:")) {
            value = value.substring("::ffff:".length());
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                int part = Integer.parseInt(parts[i]);
                if (part < 0 || part > 255) {
                    return false;
                }
            }
            return first == 127;
        } catch (NumberFormatException e) {
            logRecoverableBrowserFailure("loopback_host_parse", e);
            return false;
        }
    }

    /**
     * 执行rebuildURL相关逻辑。
     *
     * @param uri 待校验或访问的地址参数。
     * @param alias 别名参数。
     * @return 返回rebuild URL结果。
     */
    private String rebuildUrl(URI uri, String alias) {
        String host = alias;
        if (host.indexOf(':') >= 0 && !host.startsWith("[") && !host.endsWith("]")) {
            host = "[" + host + "]";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(uri.getScheme()).append("://").append(host);
        if (uri.getPort() >= 0) {
            builder.append(':').append(uri.getPort());
        }
        builder.append(StrUtil.nullToEmpty(uri.getRawPath()));
        if (StrUtil.isNotBlank(uri.getRawQuery())) {
            builder.append('?').append(uri.getRawQuery());
        }
        if (StrUtil.isNotBlank(uri.getRawFragment())) {
            builder.append('#').append(uri.getRawFragment());
        }
        return builder.toString();
    }

    /**
     * 生成安全展示用的关闭。
     *
     * @param provider 模型或能力提供方。
     * @param providerSessionId 提供方会话标识。
     */
    private void safeClose(BrowserProvider provider, String providerSessionId) {
        try {
            provider.closeSession(providerSessionId);
        } catch (Exception e) {
            log.warn("浏览器会话关闭失败：stage=close_session, error={}", exceptionSummary(e));
        }
    }

    /**
     * 记录浏览器运行时的可恢复失败，日志内容只包含固定阶段和异常类型。
     *
     * @param stage 固定阶段名，禁止拼接 URL、页面文本、token 或会话参数。
     * @param error 浏览器提供方或运行时解析抛出的异常。
     */
    private void logRecoverableBrowserFailure(String stage, Throwable error) {
        if (log.isDebugEnabled()) {
            log.debug("浏览器运行时可恢复失败：stage={}, error={}", stage, exceptionSummary(error));
        }
    }

    /**
     * 生成低敏异常摘要，仅暴露异常类型并在发现中断异常时恢复线程中断标记。
     *
     * @param error 原始异常。
     * @return 不含异常消息和堆栈的异常类型摘要。
     */
    private String exceptionSummary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            current = current.getCause();
        }
        String name = error.getClass().getSimpleName();
        return StrUtil.isBlank(name) ? error.getClass().getName() : name;
    }

    /** 承载Lease相关状态和辅助逻辑。 */
    private static class Lease {
        /** 记录Lease中的标识。 */
        private final String id;

        /** 记录Lease中的提供方。 */
        private final BrowserProvider provider;

        /** 记录Lease中的提供方会话。 */
        private final BrowserProvider.BrowserSession providerSession;

        /** 记录Lease中的expires时间Millis。 */
        private volatile long expiresAtMillis;

        /** 记录Lease中的创建时间。 */
        private final String createdAt = Instant.now().toString();

        /** 记录Lease中的closed。 */
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * 创建Lease实例，并注入运行所需依赖。
         *
         * @param id 标识。
         * @param provider 模型或能力提供方。
         * @param providerSession 提供方会话标识或键值。
         * @param expiresAtMillis expiresAtMillis 参数。
         */
        private Lease(
                String id,
                BrowserProvider provider,
                BrowserProvider.BrowserSession providerSession,
                long expiresAtMillis) {
            this.id = id;
            this.provider = provider;
            this.providerSession = providerSession;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    /** 承载LoopbackRewrite相关状态和辅助逻辑。 */
    private static class LoopbackRewrite {
        /** 记录LoopbackRewrite中的originalURL。 */
        private final String originalUrl;

        /** 记录LoopbackRewrite中的rewrittenURL。 */
        private final String rewrittenUrl;

        /** 记录LoopbackRewrite中的original主机。 */
        private final String originalHost;

        /** 记录LoopbackRewrite中的alias主机。 */
        private final String aliasHost;

        /**
         * 创建Loopback Rewrite实例，并注入运行所需依赖。
         *
         * @param originalUrl 待校验或访问的地址参数。
         * @param rewrittenUrl 待校验或访问的地址参数。
         * @param originalHost original主机参数。
         * @param aliasHost alias主机参数。
         */
        private LoopbackRewrite(
                String originalUrl, String rewrittenUrl, String originalHost, String aliasHost) {
            this.originalUrl = originalUrl;
            this.rewrittenUrl = rewrittenUrl;
            this.originalHost = originalHost;
            this.aliasHost = aliasHost;
        }

        /**
         * 执行none相关逻辑。
         *
         * @param url 待校验或访问的 URL。
         * @return 返回none结果。
         */
        private static LoopbackRewrite none(String url) {
            return new LoopbackRewrite(url, url, "", "");
        }

        /**
         * 判断是否Rewritten。
         *
         * @return 如果Rewritten满足条件则返回 true，否则返回 false。
         */
        private boolean isRewritten() {
            return StrUtil.isNotBlank(rewrittenUrl) && !StrUtil.equals(originalUrl, rewrittenUrl);
        }

        /**
         * 转换为Details。
         *
         * @return 返回转换后的Details。
         */
        private Map<String, Object> toDetails() {
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("from", SecretRedactor.maskUrl(StrUtil.nullToEmpty(originalHost)));
            details.put("to", SecretRedactor.maskUrl(StrUtil.nullToEmpty(aliasHost)));
            details.put("originalUrl", SecretRedactor.maskUrl(originalUrl));
            details.put("rewrittenUrl", SecretRedactor.maskUrl(rewrittenUrl));
            return details;
        }
    }

    /** 表示浏览器结果，携带调用方后续判断所需信息。 */
    public static class BrowserResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录浏览器中的会话标识。 */
        private final String sessionId;

        /** 记录浏览器中的状态。 */
        private final String status;

        /** 保存details映射，便于按键快速查询。 */
        private final Map<String, Object> details;

        /** 记录浏览器中的错误。 */
        private final BrowserError error;

        /**
         * 创建浏览器结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param sessionId 当前会话标识。
         * @param status 状态参数。
         * @param details details 参数。
         * @param error 错误参数。
         */
        private BrowserResult(
                boolean success,
                String sessionId,
                String status,
                Map<String, Object> details,
                BrowserError error) {
            this.success = success;
            this.sessionId = sessionId;
            this.status = status;
            this.details =
                    details == null
                            ? Collections.<String, Object>emptyMap()
                            : new LinkedHashMap<String, Object>(details);
            this.error = error;
        }

        /**
         * 执行success相关逻辑。
         *
         * @param sessionId 当前会话标识。
         * @param status 状态参数。
         * @param details details 参数。
         * @return 返回success结果。
         */
        public static BrowserResult success(
                String sessionId, String status, Map<String, Object> details) {
            return new BrowserResult(true, sessionId, status, details, null);
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param code code 参数。
         * @param message 平台消息或错误消息。
         * @return 返回error结果。
         */
        public static BrowserResult error(String code, String message) {
            return error(code, message, Collections.<String, Object>emptyMap());
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param code code 参数。
         * @param message 平台消息或错误消息。
         * @param details details 参数。
         * @return 返回error结果。
         */
        public static BrowserResult error(
                String code, String message, Map<String, Object> details) {
            return new BrowserResult(
                    false,
                    "",
                    "error",
                    details,
                    new BrowserError(code, SecretRedactor.redact(message, 500)));
        }

        /**
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取会话标识。
         *
         * @return 返回读取到的会话标识。
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * 读取状态。
         *
         * @return 返回读取到的状态。
         */
        public String getStatus() {
            return status;
        }

        /**
         * 读取Details。
         *
         * @return 返回读取到的Details。
         */
        public Map<String, Object> getDetails() {
            return new LinkedHashMap<String, Object>(details);
        }

        /**
         * 读取Error。
         *
         * @return 返回读取到的Error。
         */
        public BrowserError getError() {
            return error;
        }

        /**
         * 转换为String。
         *
         * @return 返回转换后的String。
         */
        @Override
        public String toString() {
            return "BrowserResult{success="
                    + success
                    + ", sessionId='"
                    + sessionId
                    + "', status='"
                    + status
                    + "', details="
                    + details
                    + ", error="
                    + error
                    + "}";
        }
    }

    /** 承载浏览器错误相关状态和辅助逻辑。 */
    public static class BrowserError {
        /** 记录浏览器错误中的code。 */
        private final String code;

        /** 记录浏览器错误中的消息。 */
        private final String message;

        /**
         * 创建浏览器Error实例，并注入运行所需依赖。
         *
         * @param code code 参数。
         * @param message 平台消息或错误消息。
         */
        private BrowserError(String code, String message) {
            this.code = code;
            this.message = message;
        }

        /**
         * 读取Code。
         *
         * @return 返回读取到的Code。
         */
        public String getCode() {
            return code;
        }

        /**
         * 读取消息。
         *
         * @return 返回读取到的消息。
         */
        public String getMessage() {
            return message;
        }

        /**
         * 转换为String。
         *
         * @return 返回转换后的String。
         */
        @Override
        public String toString() {
            return "BrowserError{code='" + code + "', message='" + message + "'}";
        }
    }
}
