package com.jimuqu.solon.claw.provider;

import cn.hutool.core.util.StrUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 为云浏览器 Provider 提供共享的 Chrome DevTools Protocol 动作实现。
 *
 * <p>具体 Provider 只负责创建和释放远端会话，本类使用会话返回的 CDP WebSocket 完成导航、点击、输入、截图和内容提取。
 */
public abstract class CdpBrowserProvider implements BrowserProvider {
    /** 浏览器 CDP 连接日志，只记录固定阶段和异常类型，不记录地址、页面内容或输入文本。 */
    private static final Logger log = LoggerFactory.getLogger(CdpBrowserProvider.class);

    /** 单条 CDP WebSocket 消息允许的最大字符数，避免异常页面响应无限占用内存。 */
    private static final int MAX_CDP_MESSAGE_CHARS = 48 * 1024 * 1024;

    /** 单张截图允许解码的最大字节数。 */
    private static final int MAX_SCREENSHOT_BYTES = 24 * 1024 * 1024;

    /** 复用现有 OkHttp 依赖维护 CDP WebSocket 连接。 */
    private static final OkHttpClient HTTP_CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(10L, TimeUnit.SECONDS)
                    .readTimeout(0L, TimeUnit.MILLISECONDS)
                    .pingInterval(20L, TimeUnit.SECONDS)
                    .build();

    /** 按远端会话标识保存惰性建立的 CDP 连接。 */
    private final ConcurrentMap<String, CdpSession> sessions =
            new ConcurrentHashMap<String, CdpSession>();

    /**
     * 注册远端 Provider 创建的 CDP 会话，并返回统一浏览器会话对象。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param connectUrl CDP WebSocket 连接地址。
     * @return 可交给浏览器运行时保存的会话对象。
     */
    protected final BrowserSession registerCdpSession(String sessionId, String connectUrl) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(connectUrl)) {
            throw new IllegalArgumentException(
                    "Browser provider returned an incomplete CDP session");
        }
        CdpSession created = new CdpSession(connectUrl);
        CdpSession previous = sessions.put(sessionId, created);
        if (previous != null) {
            previous.close();
        }
        return new BrowserSession(sessionId, connectUrl);
    }

    /**
     * 释放本地 CDP 连接；具体 Provider 随后仍需调用自己的远端会话关闭 API。
     *
     * @param sessionId 远端 Provider 会话标识。
     */
    protected final void releaseCdpSession(String sessionId) {
        CdpSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
        }
    }

    /**
     * 使用 CDP 导航当前页面。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param url 目标 URL。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 浏览器动作结果。
     */
    @Override
    public final BrowserActionResult navigate(String sessionId, String url, int timeoutSeconds) {
        try {
            return requireSession(sessionId).navigate(url, timeoutSeconds);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 点击 CSS 选择器匹配的页面元素。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param selector CSS 选择器。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 浏览器动作结果。
     */
    @Override
    public final BrowserActionResult click(String sessionId, String selector, int timeoutSeconds) {
        try {
            return requireSession(sessionId).click(selector, timeoutSeconds);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 向 CSS 选择器匹配的页面元素输入文本。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param selector CSS 选择器。
     * @param text 输入文本，结果和日志中不回显原文。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 浏览器动作结果。
     */
    @Override
    public final BrowserActionResult type(
            String sessionId, String selector, String text, int timeoutSeconds) {
        try {
            return requireSession(sessionId).type(selector, text, timeoutSeconds);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 截取页面并写入调用方已通过安全校验的路径。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param path 截图输出路径。
     * @param fullPage 是否截取完整页面。
     * @return 浏览器动作结果。
     */
    @Override
    public final BrowserActionResult screenshot(String sessionId, String path, boolean fullPage) {
        try {
            return requireSession(sessionId).screenshot(path, fullPage);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 提取 CSS 选择器匹配元素的文本或 HTML。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param selector CSS 选择器，空值表示整个文档。
     * @param format 提取格式，支持 text、html 和 value。
     * @return 浏览器动作结果。
     */
    @Override
    public final BrowserActionResult extract(String sessionId, String selector, String format) {
        try {
            return requireSession(sessionId).extract(selector, format);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 生成页面文本快照和交互元素引用。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param full 是否包含完整页面文本。
     * @return 页面快照结果。
     */
    @Override
    public final BrowserActionResult snapshot(String sessionId, boolean full) {
        try {
            return requireSession(sessionId).snapshot(full);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 滚动页面。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param direction up 或 down。
     * @param pixels 滚动像素数。
     * @return 页面滚动结果。
     */
    @Override
    public final BrowserActionResult scroll(String sessionId, String direction, int pixels) {
        try {
            return requireSession(sessionId).scroll(direction, pixels);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 返回上一页。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 历史导航结果。
     */
    @Override
    public final BrowserActionResult back(String sessionId, int timeoutSeconds) {
        try {
            return requireSession(sessionId).back(timeoutSeconds);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 派发键盘按键。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param key 按键名称。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 按键动作结果。
     */
    @Override
    public final BrowserActionResult press(String sessionId, String key, int timeoutSeconds) {
        try {
            return requireSession(sessionId).press(key, timeoutSeconds);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 枚举页面图片。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @return 图片列表结果。
     */
    @Override
    public final BrowserActionResult getImages(String sessionId) {
        try {
            return requireSession(sessionId).getImages();
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 读取控制台消息或执行 JavaScript 表达式。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param clear 读取后是否清空控制台缓冲。
     * @param expression 可选 JavaScript 表达式。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 控制台结果。
     */
    @Override
    public final BrowserActionResult console(
            String sessionId, boolean clear, String expression, int timeoutSeconds) {
        try {
            return requireSession(sessionId).console(clear, expression, timeoutSeconds);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用现有 WebSocket 发送原始 CDP 命令。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param method CDP 方法名。
     * @param params CDP 参数。
     * @param targetId 可选页面 Target 标识。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 原始 CDP 结果。
     */
    @Override
    public final BrowserActionResult cdp(
            String sessionId,
            String method,
            Map<String, Object> params,
            String targetId,
            int timeoutSeconds) {
        try {
            return requireSession(sessionId).cdp(method, params, targetId, timeoutSeconds);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 使用 CDP 响应阻塞页面的 JavaScript 对话框。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @param action accept 或 dismiss。
     * @param promptText prompt 输入文本。
     * @param dialogId 可选对话框标识。
     * @param timeoutSeconds 超时时间，单位秒。
     * @return 对话框处理结果。
     */
    @Override
    public final BrowserActionResult dialog(
            String sessionId,
            String action,
            String promptText,
            String dialogId,
            int timeoutSeconds) {
        try {
            return requireSession(sessionId).dialog(action, promptText, dialogId, timeoutSeconds);
        } catch (CdpException e) {
            return failed(e);
        }
    }

    /**
     * 查找已注册的 CDP 会话。
     *
     * @param sessionId 远端 Provider 会话标识。
     * @return 对应 CDP 会话。
     */
    private CdpSession requireSession(String sessionId) throws CdpException {
        CdpSession session = sessions.get(sessionId);
        if (session == null) {
            throw new CdpException("session_not_found", "Browser provider session was not found");
        }
        return session;
    }

    /**
     * 把内部 CDP 异常转换为稳定且不含敏感上下文的 Provider 错误。
     *
     * @param error CDP 执行异常。
     * @return 浏览器失败结果。
     */
    private BrowserActionResult failed(CdpException error) {
        logRecoverableFailure(error.getStage(), error);
        return BrowserActionResult.fail(error.getCode(), error.getSafeMessage());
    }

    /**
     * 记录可恢复 CDP 失败的固定摘要。
     *
     * @param stage 固定阶段名。
     * @param error 原始异常。
     */
    private void logRecoverableFailure(String stage, Throwable error) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "浏览器 CDP 动作失败：stage={}, error={}",
                    StrUtil.blankToDefault(stage, "unknown"),
                    error == null ? "unknown" : error.getClass().getSimpleName());
        }
    }

    /** 维护单个远端浏览器会话的 CDP 连接、目标页和请求响应关联。 */
    private static final class CdpSession {
        /** 原始 CDP WebSocket 地址，仅用于建立连接，禁止输出到日志或结果。 */
        private final String connectUrl;

        /** CDP 请求递增标识。 */
        private final AtomicLong requestIds = new AtomicLong();

        /** 等待 CDP 响应的请求集合。 */
        private final ConcurrentMap<Long, CompletableFuture<Map<String, Object>>> pending =
                new ConcurrentHashMap<Long, CompletableFuture<Map<String, Object>>>();

        /** 有界保存页面控制台消息。 */
        private final List<Map<String, Object>> consoleMessages =
                new CopyOnWriteArrayList<Map<String, Object>>();

        /** 保存当前阻塞页面的原生 JavaScript 对话框。 */
        private final List<Map<String, Object>> pendingDialogs =
                new CopyOnWriteArrayList<Map<String, Object>>();

        /** 生成稳定的对话框标识。 */
        private final AtomicLong dialogIds = new AtomicLong();

        /** 当前 WebSocket 建连完成信号。 */
        private volatile CountDownLatch openLatch = new CountDownLatch(1);

        /** 当前 WebSocket 实例。 */
        private volatile WebSocket webSocket;

        /** 当前 WebSocket 是否已成功打开。 */
        private volatile boolean connected;

        /** 当前 WebSocket 最近一次失败。 */
        private volatile Throwable connectionFailure;

        /** 当前页面 Target 标识。 */
        private volatile String targetId;

        /** 当前扁平化 Target 会话标识。 */
        private volatile String targetSessionId;

        /**
         * 创建远端 CDP 会话包装。
         *
         * @param connectUrl 原始 CDP WebSocket 地址。
         */
        private CdpSession(String connectUrl) {
            this.connectUrl = connectUrl;
        }

        /**
         * 导航页面并等待文档进入可交互状态。
         *
         * @param url 目标 URL。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 浏览器动作结果。
         */
        private synchronized BrowserActionResult navigate(String url, int timeoutSeconds)
                throws CdpException {
            if (StrUtil.isBlank(url)) {
                throw new CdpException("invalid_url", "Browser navigation URL is required");
            }
            ensurePage(timeoutSeconds);
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("url", url);
            Map<String, Object> result = requestPage("Page.navigate", params, timeoutSeconds);
            if (StrUtil.isNotBlank(stringValue(result.get("errorText")))) {
                throw new CdpException("navigation_failed", "Browser navigation failed");
            }
            waitUntilReady(timeoutSeconds);
            String currentUrl = currentUrl(timeoutSeconds, url);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("url", url);
            return BrowserActionResult.ok("navigated", currentUrl, details);
        }

        /**
         * 点击页面元素。
         *
         * @param selector CSS 选择器。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 浏览器动作结果。
         */
        private synchronized BrowserActionResult click(String selector, int timeoutSeconds)
                throws CdpException {
            String normalizedSelector = resolveSelector(selector);
            ensurePage(timeoutSeconds);
            String expression =
                    "(() => { try { const element = document.querySelector("
                            + jsonString(normalizedSelector)
                            + "); if (!element) return {ok:false,code:'element_not_found'}; if"
                            + " (element.disabled) return {ok:false,code:'element_disabled'};"
                            + " element.scrollIntoView({block:'center',inline:'center'});"
                            + " element.click(); return"
                            + " {ok:true,tag:(element.tagName||'').toLowerCase()}; } catch (error)"
                            + " { return {ok:false,code:'invalid_selector'}; } })()";
            Map<String, Object> action = asMap(evaluate(expression, timeoutSeconds));
            assertDomAction(action);
            pauseAfterMutation();
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("selector", normalizedSelector);
            details.put("tag", stringValue(action.get("tag")));
            return BrowserActionResult.ok("clicked", currentUrl(timeoutSeconds, ""), details);
        }

        /**
         * 向页面元素输入文本并派发 input/change 事件。
         *
         * @param selector CSS 选择器。
         * @param text 输入文本。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 浏览器动作结果。
         */
        private synchronized BrowserActionResult type(
                String selector, String text, int timeoutSeconds) throws CdpException {
            String normalizedSelector = resolveSelector(selector);
            String normalizedText = text == null ? "" : text;
            ensurePage(timeoutSeconds);
            String expression =
                    "(() => { try { const element = document.querySelector("
                            + jsonString(normalizedSelector)
                            + "); if (!element) return {ok:false,code:'element_not_found'};"
                            + " element.focus(); const value = "
                            + jsonString(normalizedText)
                            + "; if (element.isContentEditable) { element.textContent = value; }"
                            + " else if ('value' in element) { const proto ="
                            + " Object.getPrototypeOf(element); const descriptor ="
                            + " Object.getOwnPropertyDescriptor(proto,'value'); if (descriptor &&"
                            + " descriptor.set) descriptor.set.call(element,value); else"
                            + " element.value = value; } else return"
                            + " {ok:false,code:'element_not_editable'}; element.dispatchEvent(new"
                            + " Event('input',{bubbles:true})); element.dispatchEvent(new"
                            + " Event('change',{bubbles:true})); return"
                            + " {ok:true,tag:(element.tagName||'').toLowerCase()}; } catch (error)"
                            + " { return {ok:false,code:'invalid_selector'}; } })()";
            Map<String, Object> action = asMap(evaluate(expression, timeoutSeconds));
            assertDomAction(action);
            pauseAfterMutation();
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("selector", normalizedSelector);
            details.put("tag", stringValue(action.get("tag")));
            details.put("textLength", Integer.valueOf(normalizedText.length()));
            return BrowserActionResult.ok("typed", currentUrl(timeoutSeconds, ""), details);
        }

        /**
         * 截取页面并写入目标文件。
         *
         * @param path 截图输出路径。
         * @param fullPage 是否截取完整页面。
         * @return 浏览器动作结果。
         */
        private synchronized BrowserActionResult screenshot(String path, boolean fullPage)
                throws CdpException {
            if (StrUtil.isBlank(path)) {
                throw new CdpException("invalid_path", "Browser screenshot path is required");
            }
            ensurePage(60);
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("format", "png");
            params.put("fromSurface", Boolean.TRUE);
            params.put("captureBeyondViewport", Boolean.valueOf(fullPage));
            Map<String, Object> result = requestPage("Page.captureScreenshot", params, 60);
            String encoded = stringValue(result.get("data"));
            if (StrUtil.isBlank(encoded)) {
                throw new CdpException("screenshot_failed", "Browser screenshot returned no image");
            }
            if (encoded.length() > ((MAX_SCREENSHOT_BYTES * 4L) / 3L) + 8L) {
                throw new CdpException(
                        "screenshot_too_large", "Browser screenshot exceeded the size limit");
            }
            byte[] image;
            try {
                image = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                throw new CdpException(
                        "screenshot_failed", "Browser screenshot data was invalid", e);
            }
            if (image.length > MAX_SCREENSHOT_BYTES) {
                throw new CdpException(
                        "screenshot_too_large", "Browser screenshot exceeded the size limit");
            }
            Path output = Paths.get(path).toAbsolutePath().normalize();
            try {
                Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(
                        output,
                        image,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new CdpException(
                        "screenshot_write_failed", "Browser screenshot could not be saved", e);
            }
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("path", output.toString());
            details.put("fullPage", Boolean.valueOf(fullPage));
            details.put("byteSize", Integer.valueOf(image.length));
            return BrowserActionResult.ok("screenshot", currentUrl(10, ""), details);
        }

        /**
         * 提取页面元素内容。
         *
         * @param selector CSS 选择器，空值表示整个文档。
         * @param format 提取格式。
         * @return 浏览器动作结果。
         */
        private synchronized BrowserActionResult extract(String selector, String format)
                throws CdpException {
            ensurePage(60);
            String normalizedFormat =
                    StrUtil.blankToDefault(format, "text").trim().toLowerCase(Locale.ROOT);
            if (!"text".equals(normalizedFormat)
                    && !"html".equals(normalizedFormat)
                    && !"value".equals(normalizedFormat)) {
                throw new CdpException(
                        "unsupported_format",
                        "Browser extract format must be text, html, or value");
            }
            String selectorExpression =
                    StrUtil.isBlank(selector) ? "null" : jsonString(selector.trim());
            String expression =
                    "(() => { try { const selector = "
                            + selectorExpression
                            + "; const element = selector ? document.querySelector(selector) :"
                            + " document.documentElement; if (!element) return"
                            + " {ok:false,code:'element_not_found'}; const format = "
                            + jsonString(normalizedFormat)
                            + "; let content; if (format === 'html') content = element.outerHTML ||"
                            + " ''; else if (format === 'value') content = ('value' in element) ?"
                            + " String(element.value) : String(element.textContent || ''); else"
                            + " content = String(element.innerText || element.textContent || '');"
                            + " return {ok:true,content:content}; } catch (error) { return"
                            + " {ok:false,code:'invalid_selector'}; } })()";
            Map<String, Object> action = asMap(evaluate(expression, 60));
            assertDomAction(action);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("selector", StrUtil.nullToEmpty(selector));
            details.put("format", normalizedFormat);
            details.put("content", stringValue(action.get("content")));
            return BrowserActionResult.ok("extracted", currentUrl(10, ""), details);
        }

        /**
         * 生成带交互元素 ref 的页面快照。
         *
         * @param full 是否包含完整正文。
         * @return 页面快照结果。
         */
        private synchronized BrowserActionResult snapshot(boolean full) throws CdpException {
            ensurePage(60);
            String expression =
                    "(() => { const attr='data-solonclaw-ref';"
                            + " document.querySelectorAll('['+attr+']').forEach(e=>e.removeAttribute(attr));"
                            + " const"
                            + " nodes=[...document.querySelectorAll('a[href],button,input,textarea,select,summary,[role=button],[role=link],[onclick],[contenteditable=true]')];"
                            + " const refs={}; const lines=[]; nodes.slice(0,500).forEach((e,i)=>{"
                            + " const ref='e'+(i+1); e.setAttribute(attr,ref); const"
                            + " tag=(e.tagName||'').toLowerCase(); const"
                            + " role=e.getAttribute('role')||''; const type=e.getAttribute('type')||'';"
                            + " const name=e.getAttribute('name')||''; const"
                            + " label=(e.getAttribute('aria-label')||e.getAttribute('alt')||e.getAttribute('placeholder')||e.innerText||e.value||'').trim().replace(/\\s+/g,'"
                            + " ').slice(0,160);"
                            + " refs['@'+ref]={tag:tag,role:role,type:type,name:name,label:label};"
                            + " lines.push('[@'+ref+'] <'+tag+'>'+(label?' '+label:'')); }); const"
                            + " body="
                            + (full
                                    ? "String(document.body ? document.body.innerText :"
                                            + " '').trim().slice(0,20000)"
                                    : "''")
                            + "; return {ok:true,snapshot:(body?body+'\\n"
                            + "\\n"
                            + "':'')+lines.join('\\n"
                            + "'),refs:refs,elementCount:Object.keys(refs).length}; })()";
            Map<String, Object> action = asMap(evaluate(expression, 60));
            assertDomAction(action);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("snapshot", stringValue(action.get("snapshot")));
            details.put("refs", asMap(action.get("refs")));
            details.put("elementCount", action.get("elementCount"));
            details.put("full", Boolean.valueOf(full));
            details.put("pendingDialogs", new ArrayList<Map<String, Object>>(pendingDialogs));
            return BrowserActionResult.ok("snapshot", currentUrl(10, ""), details);
        }

        /**
         * 按固定像素滚动页面。
         *
         * @param direction up 或 down。
         * @param pixels 滚动像素数。
         * @return 页面滚动结果。
         */
        private synchronized BrowserActionResult scroll(String direction, int pixels)
                throws CdpException {
            String normalized = StrUtil.nullToEmpty(direction).trim().toLowerCase(Locale.ROOT);
            if (!"up".equals(normalized) && !"down".equals(normalized)) {
                throw new CdpException(
                        "invalid_direction", "Browser scroll direction must be up or down");
            }
            int distance = Math.max(1, Math.min(Math.abs(pixels), 5000));
            ensurePage(60);
            Object position =
                    evaluate(
                            "(() => { window.scrollBy(0,"
                                    + ("up".equals(normalized) ? -distance : distance)
                                    + "); return Math.round(window.scrollY); })()",
                            60);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("direction", normalized);
            details.put("pixels", Integer.valueOf(distance));
            details.put("scrollY", position);
            return BrowserActionResult.ok("scrolled", currentUrl(10, ""), details);
        }

        /**
         * 使用 CDP 页面历史返回上一项。
         *
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 历史导航结果。
         */
        private synchronized BrowserActionResult back(int timeoutSeconds) throws CdpException {
            ensurePage(timeoutSeconds);
            Map<String, Object> history =
                    requestPage(
                            "Page.getNavigationHistory",
                            Collections.<String, Object>emptyMap(),
                            timeoutSeconds);
            int currentIndex = intValue(history.get("currentIndex"), -1);
            List<Object> entries = asList(history.get("entries"));
            if (currentIndex <= 0 || currentIndex > entries.size() - 1) {
                throw new CdpException("history_empty", "Browser history has no previous page");
            }
            Map<String, Object> previous = asMap(entries.get(currentIndex - 1));
            Object entryId = previous.get("id");
            if (!(entryId instanceof Number)) {
                throw new CdpException("history_invalid", "Browser history entry was invalid");
            }
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("entryId", entryId);
            requestPage("Page.navigateToHistoryEntry", params, timeoutSeconds);
            pauseAfterMutation();
            waitUntilReady(timeoutSeconds);
            return BrowserActionResult.ok(
                    "back", currentUrl(timeoutSeconds, stringValue(previous.get("url"))));
        }

        /**
         * 使用 Input.dispatchKeyEvent 派发按键。
         *
         * @param key 按键名称。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 按键动作结果。
         */
        private synchronized BrowserActionResult press(String key, int timeoutSeconds)
                throws CdpException {
            String normalized = StrUtil.nullToEmpty(key).trim();
            if (normalized.length() == 0 || normalized.length() > 40) {
                throw new CdpException("invalid_key", "Browser key is required");
            }
            ensurePage(timeoutSeconds);
            KeySpec spec = KeySpec.resolve(normalized);
            Map<String, Object> down = spec.toParams("keyDown");
            requestPage("Input.dispatchKeyEvent", down, timeoutSeconds);
            requestPage("Input.dispatchKeyEvent", spec.toParams("keyUp"), timeoutSeconds);
            pauseAfterMutation();
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("key", spec.key);
            return BrowserActionResult.ok("pressed", currentUrl(timeoutSeconds, ""), details);
        }

        /**
         * 枚举页面图片 URL、替代文本和自然尺寸。
         *
         * @return 图片枚举结果。
         */
        private synchronized BrowserActionResult getImages() throws CdpException {
            ensurePage(60);
            Object value =
                    evaluate(
                            "(() =>"
                                    + " [...document.images].map(img=>({src:img.currentSrc||img.src||'',alt:img.alt||'',width:img.naturalWidth||0,height:img.naturalHeight||0})).filter(img=>img.src&&!img.src.startsWith('data:')).slice(0,500))()",
                            60);
            List<Object> images = asList(value);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("images", images);
            details.put("count", Integer.valueOf(images.size()));
            return BrowserActionResult.ok("images", currentUrl(10, ""), details);
        }

        /**
         * 读取控制台缓冲或执行表达式。
         *
         * @param clear 读取后是否清空缓冲。
         * @param expression 可选表达式。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 控制台结果。
         */
        private synchronized BrowserActionResult console(
                boolean clear, String expression, int timeoutSeconds) throws CdpException {
            ensurePage(timeoutSeconds);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            if (StrUtil.isNotBlank(expression)) {
                details.put("result", evaluate(expression, timeoutSeconds));
                details.put("resultType", "javascript");
            } else {
                details.put("messages", new ArrayList<Map<String, Object>>(consoleMessages));
                details.put("count", Integer.valueOf(consoleMessages.size()));
            }
            if (clear) {
                consoleMessages.clear();
            }
            return BrowserActionResult.ok("console", currentUrl(timeoutSeconds, ""), details);
        }

        /**
         * 发送原始 CDP 命令。
         *
         * @param method CDP 方法名。
         * @param params CDP 参数。
         * @param requestedTargetId 可选 Target 标识。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 原始 CDP 结果。
         */
        private synchronized BrowserActionResult cdp(
                String method,
                Map<String, Object> params,
                String requestedTargetId,
                int timeoutSeconds)
                throws CdpException {
            String normalizedMethod = StrUtil.nullToEmpty(method).trim();
            if (normalizedMethod.length() == 0) {
                throw new CdpException("invalid_cdp_method", "CDP method is required");
            }
            ensureConnected(timeoutSeconds);
            Map<String, Object> result;
            if (StrUtil.isBlank(requestedTargetId)) {
                result = requestBrowser(normalizedMethod, params, timeoutSeconds);
            } else {
                String requested = requestedTargetId.trim();
                ensurePage(timeoutSeconds);
                if (requested.equals(targetId) || "page".equalsIgnoreCase(requested)) {
                    result = requestPage(normalizedMethod, params, timeoutSeconds);
                } else {
                    String attached = attachTarget(requested, timeoutSeconds);
                    result = request(normalizedMethod, params, attached, timeoutSeconds);
                }
            }
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("method", normalizedMethod);
            details.put("result", result);
            return BrowserActionResult.ok("cdp", currentUrlBestEffort(), details);
        }

        /**
         * 响应当前阻塞中的原生 JavaScript 对话框。
         *
         * @param action accept 或 dismiss。
         * @param promptText prompt 输入文本。
         * @param dialogId 可选对话框标识。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 对话框处理结果。
         */
        private synchronized BrowserActionResult dialog(
                String action, String promptText, String dialogId, int timeoutSeconds)
                throws CdpException {
            String normalized = StrUtil.nullToEmpty(action).trim().toLowerCase(Locale.ROOT);
            if (!"accept".equals(normalized) && !"dismiss".equals(normalized)) {
                throw new CdpException(
                        "invalid_dialog_action", "Dialog action must be accept or dismiss");
            }
            ensurePage(timeoutSeconds);
            Map<String, Object> pending = pendingDialogs.isEmpty() ? null : pendingDialogs.get(0);
            if (pending == null) {
                throw new CdpException("dialog_not_found", "No browser dialog is pending");
            }
            if (StrUtil.isNotBlank(dialogId)
                    && !dialogId.trim().equals(stringValue(pending.get("id")))) {
                throw new CdpException("dialog_not_found", "Browser dialog was not found");
            }
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("accept", Boolean.valueOf("accept".equals(normalized)));
            if (StrUtil.isNotBlank(promptText)) {
                params.put("promptText", promptText);
            }
            requestPage("Page.handleJavaScriptDialog", params, timeoutSeconds);
            pendingDialogs.remove(pending);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("action", normalized);
            details.put("dialog", pending);
            return BrowserActionResult.ok("dialog", currentUrlBestEffort(), details);
        }

        /**
         * 建立 CDP 连接并附加到一个页面 Target。
         *
         * @param timeoutSeconds 超时时间，单位秒。
         */
        private void ensurePage(int timeoutSeconds) throws CdpException {
            ensureConnected(timeoutSeconds);
            if (StrUtil.isNotBlank(targetSessionId)) {
                return;
            }
            Map<String, Object> targets =
                    requestBrowser(
                            "Target.getTargets",
                            Collections.<String, Object>emptyMap(),
                            timeoutSeconds);
            targetId = selectPageTarget(asList(targets.get("targetInfos")));
            if (StrUtil.isBlank(targetId)) {
                Map<String, Object> createParams = new LinkedHashMap<String, Object>();
                createParams.put("url", "about:blank");
                Map<String, Object> created =
                        requestBrowser("Target.createTarget", createParams, timeoutSeconds);
                targetId = stringValue(created.get("targetId"));
            }
            if (StrUtil.isBlank(targetId)) {
                throw new CdpException(
                        "cdp_target_missing", "Browser CDP did not expose a page target");
            }
            Map<String, Object> attachParams = new LinkedHashMap<String, Object>();
            attachParams.put("targetId", targetId);
            attachParams.put("flatten", Boolean.TRUE);
            Map<String, Object> attached =
                    requestBrowser("Target.attachToTarget", attachParams, timeoutSeconds);
            targetSessionId = stringValue(attached.get("sessionId"));
            if (StrUtil.isBlank(targetSessionId)) {
                throw new CdpException(
                        "cdp_attach_failed", "Browser CDP could not attach to the page target");
            }
            requestPage("Page.enable", Collections.<String, Object>emptyMap(), timeoutSeconds);
            requestPage("Runtime.enable", Collections.<String, Object>emptyMap(), timeoutSeconds);
        }

        /**
         * 建立 CDP WebSocket 连接。
         *
         * @param timeoutSeconds 超时时间，单位秒。
         */
        private void ensureConnected(int timeoutSeconds) throws CdpException {
            if (connected && webSocket != null && connectionFailure == null) {
                return;
            }
            connectionFailure = null;
            connected = false;
            targetId = null;
            targetSessionId = null;
            openLatch = new CountDownLatch(1);
            WebSocket previous = webSocket;
            if (previous != null) {
                previous.cancel();
            }
            webSocket = null;
            Request request;
            try {
                request = new Request.Builder().url(connectUrl).build();
            } catch (Exception e) {
                throw new CdpException(
                        "invalid_connect_url", "Browser CDP connection URL was invalid", e);
            }
            webSocket = HTTP_CLIENT.newWebSocket(request, new Listener());
            try {
                if (!openLatch.await(Math.max(1, Math.min(timeoutSeconds, 10)), TimeUnit.SECONDS)) {
                    webSocket.cancel();
                    throw new CdpException("cdp_timeout", "Browser CDP connection timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CdpException("interrupted", "Browser action was interrupted", e);
            }
            if (!connected || connectionFailure != null) {
                throw new CdpException(
                        "cdp_connect_failed", "Browser CDP connection failed", connectionFailure);
            }
        }

        /**
         * 发送浏览器级 CDP 请求。
         *
         * @param method CDP 方法名。
         * @param params CDP 参数。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return CDP result 对象。
         */
        private Map<String, Object> requestBrowser(
                String method, Map<String, Object> params, int timeoutSeconds) throws CdpException {
            return request(method, params, null, timeoutSeconds);
        }

        /**
         * 发送页面 Target 范围内的 CDP 请求。
         *
         * @param method CDP 方法名。
         * @param params CDP 参数。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return CDP result 对象。
         */
        private Map<String, Object> requestPage(
                String method, Map<String, Object> params, int timeoutSeconds) throws CdpException {
            if (StrUtil.isBlank(targetSessionId)) {
                throw new CdpException(
                        "cdp_attach_failed", "Browser CDP page session was unavailable");
            }
            return request(method, params, targetSessionId, timeoutSeconds);
        }

        /**
         * 发送 CDP 请求并按 id 等待对应响应。
         *
         * @param method CDP 方法名。
         * @param params CDP 参数。
         * @param sessionId 可选的 Target 会话标识。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return CDP result 对象。
         */
        private Map<String, Object> request(
                String method, Map<String, Object> params, String sessionId, int timeoutSeconds)
                throws CdpException {
            ensureConnected(timeoutSeconds);
            long id = requestIds.incrementAndGet();
            Map<String, Object> command = new LinkedHashMap<String, Object>();
            command.put("id", Long.valueOf(id));
            command.put("method", method);
            command.put("params", params == null ? Collections.<String, Object>emptyMap() : params);
            if (StrUtil.isNotBlank(sessionId)) {
                command.put("sessionId", sessionId);
            }
            CompletableFuture<Map<String, Object>> future =
                    new CompletableFuture<Map<String, Object>>();
            pending.put(Long.valueOf(id), future);
            if (webSocket == null || !webSocket.send(ONode.serialize(command))) {
                pending.remove(Long.valueOf(id));
                throw new CdpException("cdp_send_failed", "Browser CDP request could not be sent");
            }
            Map<String, Object> response;
            try {
                response = future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                pending.remove(Long.valueOf(id));
                throw new CdpException("cdp_timeout", "Browser CDP request timed out", e);
            } catch (InterruptedException e) {
                pending.remove(Long.valueOf(id));
                Thread.currentThread().interrupt();
                throw new CdpException("interrupted", "Browser action was interrupted", e);
            } catch (ExecutionException e) {
                pending.remove(Long.valueOf(id));
                Throwable cause = e.getCause();
                if (cause instanceof CdpException) {
                    throw (CdpException) cause;
                }
                throw new CdpException(
                        "cdp_connection_closed", "Browser CDP connection closed", cause);
            }
            Map<String, Object> error = asMap(response.get("error"));
            if (!error.isEmpty()) {
                throw new CdpException("cdp_error", "Browser CDP rejected the action");
            }
            return asMap(response.get("result"));
        }

        /**
         * 在页面上下文执行只返回 JSON 值的 JavaScript。
         *
         * @param expression JavaScript 表达式。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 页面返回值。
         */
        private Object evaluate(String expression, int timeoutSeconds) throws CdpException {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("expression", expression);
            params.put("returnByValue", Boolean.TRUE);
            params.put("awaitPromise", Boolean.TRUE);
            params.put("userGesture", Boolean.TRUE);
            Map<String, Object> response = requestPage("Runtime.evaluate", params, timeoutSeconds);
            if (!asMap(response.get("exceptionDetails")).isEmpty()) {
                throw new CdpException("javascript_error", "Browser page action failed");
            }
            Map<String, Object> remoteObject = asMap(response.get("result"));
            if ("error".equals(stringValue(remoteObject.get("subtype")))) {
                throw new CdpException("javascript_error", "Browser page action failed");
            }
            return remoteObject.get("value");
        }

        /**
         * 等待页面文档进入 interactive 或 complete 状态。
         *
         * @param timeoutSeconds 超时时间，单位秒。
         */
        private void waitUntilReady(int timeoutSeconds) throws CdpException {
            long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            while (System.nanoTime() < deadline) {
                Object state = evaluate("document.readyState", remainingSeconds(deadline));
                String value = stringValue(state);
                if ("interactive".equals(value) || "complete".equals(value)) {
                    return;
                }
                sleep(100L);
            }
            throw new CdpException("navigation_timeout", "Browser navigation timed out");
        }

        /**
         * 读取当前页面 URL，读取失败时使用调用方提供的安全兜底值。
         *
         * @param timeoutSeconds 超时时间，单位秒。
         * @param fallback URL 兜底值。
         * @return 当前页面 URL。
         */
        private String currentUrl(int timeoutSeconds, String fallback) throws CdpException {
            String value = stringValue(evaluate("window.location.href", timeoutSeconds));
            return StrUtil.blankToDefault(value, fallback);
        }

        /**
         * 尽力读取当前页面 URL，供不会返回页面内容的低层动作补充审计上下文。
         *
         * @return 当前 URL；读取失败返回空字符串。
         */
        private String currentUrlBestEffort() {
            try {
                return StrUtil.isBlank(targetSessionId) ? "" : currentUrl(5, "");
            } catch (CdpException e) {
                return "";
            }
        }

        /**
         * 附加到指定页面 Target。
         *
         * @param requestedTargetId Target 标识。
         * @param timeoutSeconds 超时时间，单位秒。
         * @return 新的扁平化 Target 会话标识。
         */
        private String attachTarget(String requestedTargetId, int timeoutSeconds)
                throws CdpException {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("targetId", requestedTargetId);
            params.put("flatten", Boolean.TRUE);
            String attached =
                    stringValue(
                            requestBrowser("Target.attachToTarget", params, timeoutSeconds)
                                    .get("sessionId"));
            if (StrUtil.isBlank(attached)) {
                throw new CdpException("cdp_attach_failed", "Browser CDP target attach failed");
            }
            return attached;
        }

        /**
         * 校验 DOM 动作返回值并转换稳定错误码。
         *
         * @param action JavaScript 返回对象。
         */
        private void assertDomAction(Map<String, Object> action) throws CdpException {
            if (Boolean.TRUE.equals(action.get("ok"))) {
                return;
            }
            String code =
                    StrUtil.blankToDefault(
                            stringValue(action.get("code")), "browser_action_failed");
            if ("element_not_found".equals(code)) {
                throw new CdpException(code, "Browser element was not found");
            }
            if ("element_disabled".equals(code)) {
                throw new CdpException(code, "Browser element was disabled");
            }
            if ("element_not_editable".equals(code)) {
                throw new CdpException(code, "Browser element does not accept text input");
            }
            if ("invalid_selector".equals(code)) {
                throw new CdpException(code, "Browser selector was invalid");
            }
            throw new CdpException("browser_action_failed", "Browser page action failed");
        }

        /**
         * 校验并规范 CSS 选择器。
         *
         * @param selector 原始选择器。
         * @return 规范化选择器。
         */
        private String requireSelector(String selector) throws CdpException {
            if (StrUtil.isBlank(selector)) {
                throw new CdpException("invalid_selector", "Browser selector is required");
            }
            return selector.trim();
        }

        /**
         * 把 snapshot 返回的 @eN 引用转换为稳定属性选择器，同时继续接受原有 CSS 选择器。
         *
         * @param selectorOrRef CSS 选择器或 @eN 引用。
         * @return 可传给 document.querySelector 的选择器。
         */
        private String resolveSelector(String selectorOrRef) throws CdpException {
            String value = requireSelector(selectorOrRef);
            if (!value.startsWith("@")) {
                return value;
            }
            String ref = value.substring(1);
            if (!ref.matches("e[1-9][0-9]{0,5}")) {
                throw new CdpException("invalid_ref", "Browser element reference was invalid");
            }
            return "[data-solonclaw-ref=\"" + ref + "\"]";
        }

        /** 点击或输入后给页面一个短暂事件循环窗口，让同步触发的导航进入可观测状态。 */
        private void pauseAfterMutation() throws CdpException {
            sleep(150L);
        }

        /**
         * 执行可中断短等待。
         *
         * @param millis 等待毫秒数。
         */
        private void sleep(long millis) throws CdpException {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CdpException("interrupted", "Browser action was interrupted", e);
            }
        }

        /**
         * 从 Target 列表选择普通页面目标。
         *
         * @param rawTargets CDP targetInfos 数组。
         * @return 页面 Target 标识。
         */
        private String selectPageTarget(List<Object> rawTargets) {
            for (Object raw : rawTargets) {
                Map<String, Object> target = asMap(raw);
                if (!"page".equals(stringValue(target.get("type")))) {
                    continue;
                }
                String url = stringValue(target.get("url"));
                if (url.startsWith("devtools://")) {
                    continue;
                }
                String id = stringValue(target.get("targetId"));
                if (StrUtil.isNotBlank(id)) {
                    return id;
                }
            }
            return "";
        }

        /** 关闭当前 CDP WebSocket 并终止所有等待中的请求。 */
        private synchronized void close() {
            connected = false;
            targetId = null;
            targetSessionId = null;
            WebSocket socket = webSocket;
            webSocket = null;
            if (socket != null) {
                socket.close(1000, "session closed");
            }
            failPending(new CdpException("session_closed", "Browser provider session was closed"));
        }

        /**
         * 让所有等待中的请求以同一个安全异常结束。
         *
         * @param error 安全异常。
         */
        private void failPending(CdpException error) {
            for (Map.Entry<Long, CompletableFuture<Map<String, Object>>> entry :
                    new ArrayList<Map.Entry<Long, CompletableFuture<Map<String, Object>>>>(
                            pending.entrySet())) {
                CompletableFuture<Map<String, Object>> future = pending.remove(entry.getKey());
                if (future != null) {
                    future.completeExceptionally(error);
                }
            }
        }

        /**
         * 有界追加控制台消息，避免长时间会话无限增长。
         *
         * @param message 控制台消息。
         */
        private void addConsoleMessage(Map<String, Object> message) {
            consoleMessages.add(message);
            while (consoleMessages.size() > 200) {
                consoleMessages.remove(0);
            }
        }

        /**
         * 有界追加待处理对话框。
         *
         * @param dialog 对话框摘要。
         */
        private void addPendingDialog(Map<String, Object> dialog) {
            pendingDialogs.add(dialog);
            while (pendingDialogs.size() > 10) {
                pendingDialogs.remove(0);
            }
        }

        /** 处理 OkHttp WebSocket 回调并按 CDP 请求 id 分发响应。 */
        private final class Listener extends WebSocketListener {
            /**
             * 标记 WebSocket 已建立。
             *
             * @param socket WebSocket 实例。
             * @param response 握手响应。
             */
            @Override
            public void onOpen(WebSocket socket, Response response) {
                connected = true;
                connectionFailure = null;
                openLatch.countDown();
            }

            /**
             * 解析 CDP 文本响应并完成对应请求。
             *
             * @param socket WebSocket 实例。
             * @param text CDP JSON 文本。
             */
            @Override
            @SuppressWarnings("unchecked")
            public void onMessage(WebSocket socket, String text) {
                if (text == null || text.length() > MAX_CDP_MESSAGE_CHARS) {
                    socket.cancel();
                    onFailure(
                            socket,
                            new IOException("CDP message exceeded the configured limit"),
                            null);
                    return;
                }
                try {
                    Object data = ONode.ofJson(text).toData();
                    if (!(data instanceof Map)) {
                        return;
                    }
                    Map<String, Object> message = (Map<String, Object>) data;
                    Object rawId = message.get("id");
                    if (!(rawId instanceof Number)) {
                        handleEvent(message);
                        return;
                    }
                    Long id = Long.valueOf(((Number) rawId).longValue());
                    CompletableFuture<Map<String, Object>> future = pending.remove(id);
                    if (future != null) {
                        future.complete(new LinkedHashMap<String, Object>(message));
                    }
                } catch (Exception e) {
                    log.debug("浏览器 CDP 响应解析失败：error={}", e.getClass().getSimpleName());
                }
            }

            /**
             * 捕获控制台、JavaScript 异常和原生对话框事件。
             *
             * @param message CDP 事件消息。
             */
            private void handleEvent(Map<String, Object> message) {
                String method = stringValue(message.get("method"));
                Map<String, Object> params = asMap(message.get("params"));
                if ("Runtime.consoleAPICalled".equals(method)) {
                    List<String> parts = new ArrayList<String>();
                    for (Object raw : asList(params.get("args"))) {
                        Map<String, Object> argument = asMap(raw);
                        String value = stringValue(argument.get("value"));
                        if (StrUtil.isBlank(value)) {
                            value = stringValue(argument.get("description"));
                        }
                        if (StrUtil.isNotBlank(value)) {
                            parts.add(value);
                        }
                    }
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put(
                            "type", StrUtil.blankToDefault(stringValue(params.get("type")), "log"));
                    entry.put("text", String.join(" ", parts));
                    entry.put("timestamp", params.get("timestamp"));
                    addConsoleMessage(entry);
                } else if ("Runtime.exceptionThrown".equals(method)) {
                    Map<String, Object> exceptionDetails = asMap(params.get("exceptionDetails"));
                    Map<String, Object> exception = asMap(exceptionDetails.get("exception"));
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("type", "exception");
                    entry.put(
                            "text",
                            StrUtil.blankToDefault(
                                    stringValue(exception.get("description")),
                                    stringValue(exceptionDetails.get("text"))));
                    addConsoleMessage(entry);
                } else if ("Page.javascriptDialogOpening".equals(method)) {
                    Map<String, Object> dialog = new LinkedHashMap<String, Object>();
                    dialog.put("id", "dialog-" + dialogIds.incrementAndGet());
                    dialog.put("type", stringValue(params.get("type")));
                    dialog.put("message", stringValue(params.get("message")));
                    dialog.put("defaultPrompt", stringValue(params.get("defaultPrompt")));
                    addPendingDialog(dialog);
                } else if ("Page.javascriptDialogClosed".equals(method)
                        && !pendingDialogs.isEmpty()) {
                    pendingDialogs.remove(0);
                }
            }

            /**
             * 处理 WebSocket 失败并唤醒所有等待方。
             *
             * @param socket WebSocket 实例。
             * @param error 连接异常。
             * @param response 可选握手响应。
             */
            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                if (socket != webSocket) {
                    return;
                }
                connected = false;
                connectionFailure = error;
                targetId = null;
                targetSessionId = null;
                openLatch.countDown();
                failPending(
                        new CdpException(
                                "cdp_connection_closed", "Browser CDP connection closed", error));
            }

            /**
             * 处理 WebSocket 正常关闭并唤醒等待方。
             *
             * @param socket WebSocket 实例。
             * @param code 关闭码。
             * @param reason 关闭原因，禁止写入日志。
             */
            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                if (socket != webSocket) {
                    return;
                }
                connected = false;
                targetId = null;
                targetSessionId = null;
                openLatch.countDown();
                failPending(
                        new CdpException("cdp_connection_closed", "Browser CDP connection closed"));
            }
        }
    }

    /**
     * 计算截止时间剩余的整秒数，最小返回 1 秒。
     *
     * @param deadlineNanos 单调时钟截止时间。
     * @return 剩余秒数。
     */
    private static int remainingSeconds(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            return 1;
        }
        long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining);
        return (int) Math.max(1L, Math.min(300L, seconds + 1L));
    }

    /**
     * 使用 Snack4 把字符串编码为可安全嵌入 JavaScript 的 JSON 字面量。
     *
     * @param value 原始字符串。
     * @return JSON 字符串字面量。
     */
    private static String jsonString(String value) {
        return ONode.serialize(StrUtil.nullToEmpty(value));
    }

    /**
     * 把任意对象安全转换为字符串。
     *
     * @param value 原始值。
     * @return 空值对应空字符串。
     */
    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 把 Snack4 解析结果转换为字符串键 Map。
     *
     * @param raw 原始对象。
     * @return Map；类型不匹配时返回空 Map。
     */
    private static Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 把 Snack4 解析结果转换为对象列表。
     *
     * @param raw 原始对象。
     * @return List；类型不匹配时返回空 List。
     */
    private static List<Object> asList(Object raw) {
        if (!(raw instanceof List)) {
            return Collections.emptyList();
        }
        return new ArrayList<Object>((List<?>) raw);
    }

    /**
     * 把数字对象转换为 int。
     *
     * @param raw 原始对象。
     * @param fallback 类型不匹配时的兜底值。
     * @return int 值。
     */
    private static int intValue(Object raw, int fallback) {
        return raw instanceof Number ? ((Number) raw).intValue() : fallback;
    }

    /** 表示 Input.dispatchKeyEvent 所需的稳定按键元数据。 */
    private static final class KeySpec {
        /** CDP key 值。 */
        private final String key;

        /** CDP code 值。 */
        private final String code;

        /** 虚拟键码。 */
        private final int virtualKeyCode;

        /** 可选的文本输入值。 */
        private final String text;

        /**
         * 创建按键元数据。
         *
         * @param key CDP key 值。
         * @param code CDP code 值。
         * @param virtualKeyCode 虚拟键码。
         * @param text 可选文本。
         */
        private KeySpec(String key, String code, int virtualKeyCode, String text) {
            this.key = key;
            this.code = code;
            this.virtualKeyCode = virtualKeyCode;
            this.text = text;
        }

        /**
         * 解析常见按键名称或单字符输入。
         *
         * @param rawKey 原始按键名。
         * @return 按键元数据。
         */
        private static KeySpec resolve(String rawKey) {
            String normalized = rawKey.trim();
            String lower = normalized.toLowerCase(Locale.ROOT);
            if ("enter".equals(lower)) {
                return new KeySpec("Enter", "Enter", 13, "\r");
            }
            if ("tab".equals(lower)) {
                return new KeySpec("Tab", "Tab", 9, "");
            }
            if ("escape".equals(lower) || "esc".equals(lower)) {
                return new KeySpec("Escape", "Escape", 27, "");
            }
            if ("backspace".equals(lower)) {
                return new KeySpec("Backspace", "Backspace", 8, "");
            }
            if ("arrowup".equals(lower)) {
                return new KeySpec("ArrowUp", "ArrowUp", 38, "");
            }
            if ("arrowdown".equals(lower)) {
                return new KeySpec("ArrowDown", "ArrowDown", 40, "");
            }
            if ("arrowleft".equals(lower)) {
                return new KeySpec("ArrowLeft", "ArrowLeft", 37, "");
            }
            if ("arrowright".equals(lower)) {
                return new KeySpec("ArrowRight", "ArrowRight", 39, "");
            }
            if (normalized.length() == 1) {
                char character = normalized.charAt(0);
                return new KeySpec(
                        normalized,
                        Character.isLetter(character)
                                ? "Key" + Character.toUpperCase(character)
                                : normalized,
                        Character.toUpperCase(character),
                        normalized);
            }
            return new KeySpec(normalized, normalized, 0, "");
        }

        /**
         * 构造 CDP 按键事件参数。
         *
         * @param type keyDown 或 keyUp。
         * @return CDP 参数。
         */
        private Map<String, Object> toParams(String type) {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("type", type);
            params.put("key", key);
            params.put("code", code);
            if (virtualKeyCode > 0) {
                params.put("windowsVirtualKeyCode", Integer.valueOf(virtualKeyCode));
                params.put("nativeVirtualKeyCode", Integer.valueOf(virtualKeyCode));
            }
            if ("keyDown".equals(type) && StrUtil.isNotBlank(text)) {
                params.put("text", text);
            }
            return params;
        }
    }

    /** 表示不含连接地址、页面内容和输入文本的稳定 CDP 异常。 */
    private static final class CdpException extends Exception {
        /** 稳定错误码。 */
        private final String code;

        /** 面向调用方的安全错误消息。 */
        private final String safeMessage;

        /** 固定诊断阶段名。 */
        private final String stage;

        /**
         * 创建安全 CDP 异常。
         *
         * @param code 稳定错误码。
         * @param safeMessage 安全错误消息。
         */
        private CdpException(String code, String safeMessage) {
            this(code, safeMessage, null);
        }

        /**
         * 创建带原因的安全 CDP 异常。
         *
         * @param code 稳定错误码。
         * @param safeMessage 安全错误消息。
         * @param cause 原始异常，仅用于类型诊断。
         */
        private CdpException(String code, String safeMessage, Throwable cause) {
            super(safeMessage, cause);
            this.code = StrUtil.blankToDefault(code, "cdp_error");
            this.safeMessage = StrUtil.blankToDefault(safeMessage, "Browser CDP action failed");
            this.stage = this.code;
        }

        /**
         * 读取稳定错误码。
         *
         * @return 错误码。
         */
        private String getCode() {
            return code;
        }

        /**
         * 读取安全错误消息。
         *
         * @return 安全错误消息。
         */
        private String getSafeMessage() {
            return safeMessage;
        }

        /**
         * 读取固定诊断阶段名。
         *
         * @return 阶段名。
         */
        private String getStage() {
            return stage;
        }
    }
}
