package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.noear.snack4.ONode;
import org.noear.solon.core.handle.Context;
import org.noear.solon.net.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dashboard 访问控制与 token 注入服务。 */
public class DashboardAuthService {
    /** 记录 Dashboard 鉴权安全日志。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardAuthService.class);

    /** 默认弱口令常量，仅用于诊断告警，不能作为空配置回退令牌。 */
    private static final String DEFAULT_WEAK_TOKEN = "admin";

    /** 避免默认弱口令告警在测试或热刷新时刷屏。 */
    private static final AtomicBoolean WEAK_TOKEN_WARNING_LOGGED = new AtomicBoolean(false);

    /** 公开API路径列表的统一常量值。 */
    private static final List<String> PUBLIC_API_PATHS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "/api/status",
                            "/api/config/defaults",
                            "/api/config/schema",
                            "/api/model/info",
                            "/api/tui/handshake"));

    /** 注入应用配置，用于控制台认证。 */
    private final AppConfig appConfig;

    /** 保存revealTimestamps集合，维持调用顺序或去重语义。 */
    private final List<Long> revealTimestamps = new ArrayList<Long>();

    /**
     * 创建控制台认证服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public DashboardAuthService(AppConfig appConfig) {
        this.appConfig = appConfig;
        warnWeakDefaultTokenIfNeeded();
    }

    /**
     * 判断是否公开Api路径。
     *
     * @param path 文件或目录路径。
     * @return 如果公开Api路径满足条件则返回 true，否则返回 false。
     */
    public boolean isPublicApiPath(String path) {
        return PUBLIC_API_PATHS.contains(path);
    }

    /**
     * 判断是否公开Api路径。
     *
     * @param path 文件或目录路径。
     * @param method method 参数。
     * @return 如果公开Api路径满足条件则返回 true，否则返回 false。
     */
    public boolean isPublicApiPath(String path, String method) {
        return isPublicApiPath(path)
                || ("GET".equalsIgnoreCase(method)
                        && path != null
                        && path.startsWith("/api/mcp/")
                        && path.endsWith("/oauth/callback"));
    }

    /**
     * 执行会话token相关逻辑。
     *
     * @return 返回会话token结果。
     */
    public String sessionToken() {
        return accessToken();
    }

    /**
     * 判断是否已授权。
     *
     * @param context 当前请求或运行上下文。
     * @return 如果已授权满足条件则返回 true，否则返回 false。
     */
    public boolean isAuthorized(Context context) {
        String auth = context.header("Authorization");
        String token = accessToken();
        return StrUtil.isNotBlank(token) && ("Bearer " + token).equals(auth);
    }

    /**
     * 判断 WebSocket 连接是否允许访问 Dashboard 同级控制面。
     *
     * @param socket WebSocket 握手后的连接对象。
     * @return 本地连接直接放行，远程连接必须携带 Dashboard 访问令牌。
     */
    public boolean isAuthorized(WebSocket socket) {
        if (isLocalRequest(socket)) {
            return true;
        }
        String token = accessToken();
        if (StrUtil.isBlank(token)) {
            return false;
        }
        String auth = webSocketParam(socket, "Authorization");
        if (("Bearer " + token).equals(auth)) {
            return true;
        }
        String queryToken = webSocketParam(socket, "token");
        return token.equals(queryToken) || token.equals(urlDecode(queryToken));
    }

    /**
     * 判断是否可以Reveal token。
     *
     * @param context 当前请求或运行上下文。
     * @return 如果Reveal token满足条件则返回 true，否则返回 false。
     */
    public boolean canRevealToken(Context context) {
        return isAuthorized(context) || isLocalRequest(context);
    }

    /**
     * 判断是否本地请求。
     *
     * @param context 当前请求或运行上下文。
     * @return 如果本地请求满足条件则返回 true，否则返回 false。
     */
    public boolean isLocalRequest(Context context) {
        String ip = context.remoteIp();
        if (StrUtil.isBlank(ip)) {
            return false;
        }
        try {
            return InetAddress.getByName(ip).isLoopbackAddress();
        } catch (Exception e) {
            return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
        }
    }

    /**
     * 判断 WebSocket 连接是否来自本机回环地址。
     *
     * @param socket WebSocket 连接对象。
     * @return 如果远端地址是本地回环地址则返回 true。
     */
    public boolean isLocalRequest(WebSocket socket) {
        if (socket == null) {
            return false;
        }
        InetSocketAddress remoteAddress = socket.remoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return false;
        }
        return remoteAddress.getAddress().isLoopbackAddress();
    }

    /**
     * 返回原始页面内容，避免把 Dashboard 访问令牌注入到浏览器全局变量。
     *
     * @param html Dashboard 前端页面内容。
     * @return 返回未携带访问令牌的页面内容。
     */
    public String injectToken(String html) {
        return html;
    }

    /**
     * 写入未授权。
     *
     * @param context 当前请求或运行上下文。
     */
    public void writeUnauthorized(Context context) {
        context.status(401);
        context.contentType("application/json;charset=UTF-8");
        context.output(ONode.serialize(Collections.singletonMap("detail", "Unauthorized")));
    }

    /**
     * 应用Cors。
     *
     * @param context 当前请求或运行上下文。
     */
    public void applyCors(Context context) {
        String origin = context.header("Origin");
        if (StrUtil.isBlank(origin)) {
            return;
        }

        if (!isAllowedDashboardOrigin(context, origin)) {
            return;
        }

        context.headerSet("Access-Control-Allow-Origin", origin);
        context.headerSet("Vary", "Origin");
        context.headerSet("Access-Control-Allow-Headers", "Authorization, Content-Type");
        context.headerSet("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
    }

    /**
     * 判断是否允许Reveal。
     *
     * @return 如果Reveal满足条件则返回 true，否则返回 false。
     */
    public boolean allowReveal() {
        synchronized (revealTimestamps) {
            long now = System.currentTimeMillis();
            long windowStart = now - 30_000L;
            for (int i = revealTimestamps.size() - 1; i >= 0; i--) {
                if (revealTimestamps.get(i) < windowStart) {
                    revealTimestamps.remove(i);
                }
            }
            if (revealTimestamps.size() >= 5) {
                return false;
            }
            revealTimestamps.add(now);
            return true;
        }
    }

    /**
     * 判断 Dashboard 是否配置了默认弱令牌。
     *
     * @return 若令牌仍为 admin 则返回 true，调用方应展示明显安全告警。
     */
    public boolean hasWeakDefaultToken() {
        return DEFAULT_WEAK_TOKEN.equals(accessToken());
    }

    /** 对显式配置的 Dashboard 默认弱口令输出一次明显告警。 */
    private void warnWeakDefaultTokenIfNeeded() {
        if (hasWeakDefaultToken() && WEAK_TOKEN_WARNING_LOGGED.compareAndSet(false, true)) {
            log.warn(
                    "Dashboard access token is the default weak value 'admin'; set solonclaw.dashboard.accessToken to a high-entropy secret before remote exposure.");
        }
    }

    /**
     * 判断是否Allowed控制台Origin。
     *
     * @param origin origin 参数。
     * @return 如果Allowed控制台Origin满足条件则返回 true，否则返回 false。
     */
    public boolean isAllowedDashboardOrigin(String origin) {
        return isLocalOrigin(origin) || isBoundDashboardOrigin(origin);
    }

    /**
     * 判断浏览器请求 Origin 是否允许访问当前 Dashboard 请求入口。
     *
     * @param context 当前请求上下文，用于识别实际访问 Host 或反向代理 Host。
     * @param origin 浏览器提交的 Origin 头。
     * @return 如果是本机、显式绑定地址或当前请求同源地址则返回 true。
     */
    public boolean isAllowedDashboardOrigin(Context context, String origin) {
        return isAllowedDashboardOrigin(origin) || isSameRequestOrigin(context, origin);
    }

    /**
     * 判断是否本地Origin。
     *
     * @param origin origin 参数。
     * @return 如果本地Origin满足条件则返回 true，否则返回 false。
     */
    private boolean isLocalOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || StrUtil.isBlank(host)) {
                return false;
            }
            if ("localhost".equalsIgnoreCase(host)) {
                return true;
            }
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否绑定控制台Origin。
     *
     * @param origin origin 参数。
     * @return 如果绑定控制台Origin满足条件则返回 true，否则返回 false。
     */
    private boolean isBoundDashboardOrigin(String origin) {
        AppConfig.DashboardConfig dashboard = appConfig == null ? null : appConfig.getDashboard();
        if (dashboard == null || StrUtil.isBlank(dashboard.getBindHost())) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || StrUtil.isBlank(host)) {
                return false;
            }

            String bindHost = dashboard.getBindHost().trim();
            if (isLoopbackHost(bindHost)) {
                return false;
            }
            if ("0.0.0.0".equals(bindHost) || "::".equals(bindHost)) {
                return true;
            }
            if (!host.equalsIgnoreCase(bindHost)) {
                return false;
            }
            int bindPort = dashboard.getBindPort();
            return bindPort <= 0 || normalizeOriginPort(uri) == bindPort;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断 Origin 是否与当前请求入口同源，支持直连 Host 与常见反向代理 Host 头。
     *
     * @param context 当前请求上下文。
     * @param origin 浏览器提交的 Origin 头。
     * @return 如果 scheme、host、port 都匹配当前请求入口则返回 true。
     */
    private boolean isSameRequestOrigin(Context context, String origin) {
        if (context == null || StrUtil.isBlank(origin)) {
            return false;
        }
        try {
            URI originUri = URI.create(origin);
            String originScheme = originUri.getScheme();
            if (!("http".equalsIgnoreCase(originScheme) || "https".equalsIgnoreCase(originScheme))
                    || StrUtil.isBlank(originUri.getHost())) {
                return false;
            }
            String requestHost = firstHeaderValue(context.header("X-Forwarded-Host"));
            if (StrUtil.isBlank(requestHost)) {
                requestHost = firstHeaderValue(context.header("Host"));
            }
            if (StrUtil.isBlank(requestHost)) {
                return false;
            }
            String requestScheme =
                    StrUtil.blankToDefault(firstHeaderValue(context.header("X-Forwarded-Proto")),
                            context.isSecure() ? "https" : "http");
            URI requestUri = URI.create(requestScheme + "://" + requestHost.trim());
            return originUri.getHost().equalsIgnoreCase(requestUri.getHost())
                    && isCompatibleRequestScheme(originScheme, requestScheme)
                    && isCompatibleRequestPort(originUri, requestUri);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断请求协议是否可视为同一 Dashboard 入口，兼容 TLS 在反向代理层终止但未透传协议头的部署。
     *
     * @param originScheme 浏览器 Origin 中的协议。
     * @param requestScheme 服务端看到的请求协议。
     * @return 如果协议一致，或仅为 http/https 代理终止差异，则返回 true。
     */
    private boolean isCompatibleRequestScheme(String originScheme, String requestScheme) {
        if (originScheme.equalsIgnoreCase(requestScheme)) {
            return true;
        }
        return ("https".equalsIgnoreCase(originScheme) && "http".equalsIgnoreCase(requestScheme))
                || ("http".equalsIgnoreCase(originScheme) && "https".equalsIgnoreCase(requestScheme));
    }

    /**
     * 判断 Origin 端口与请求 Host 端口是否匹配；Host 未显式带端口时交给浏览器默认端口处理。
     *
     * @param originUri 浏览器 Origin URI。
     * @param requestUri 当前请求入口 URI。
     * @return 如果端口一致，或请求 Host 没有显式端口，则返回 true。
     */
    private boolean isCompatibleRequestPort(URI originUri, URI requestUri) {
        if (requestUri.getPort() <= 0) {
            return true;
        }
        return normalizeOriginPort(originUri) == normalizeOriginPort(requestUri);
    }

    /**
     * 提取代理头中的第一个值，避免多级代理追加列表影响同源判断。
     *
     * @param value 原始请求头。
     * @return 去掉逗号后续内容的首个头值。
     */
    private String firstHeaderValue(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return StrUtil.subBefore(value, ",", false).trim();
    }

    /**
     * 规范化Origin Port。
     *
     * @param uri 待校验或访问的地址参数。
     * @return 返回Origin Port结果。
     */
    private int normalizeOriginPort(URI uri) {
        int port = uri.getPort();
        if (port > 0) {
            return port;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }

    /**
     * 判断是否Loopback Host。
     *
     * @param host 主机参数。
     * @return 如果Loopback Host满足条件则返回 true，否则返回 false。
     */
    private boolean isLoopbackHost(String host) {
        if (StrUtil.isBlank(host)) {
            return false;
        }
        String normalized = host.trim();
        if ("localhost".equalsIgnoreCase(normalized)) {
            return true;
        }
        try {
            return InetAddress.getByName(normalized).isLoopbackAddress();
        } catch (Exception e) {
            return "127.0.0.1".equals(normalized)
                    || "0:0:0:0:0:0:0:1".equals(normalized)
                    || "::1".equals(normalized);
        }
    }

    /**
     * 执行access token相关逻辑。
     *
     * @return 返回access token结果。
     */
    private String accessToken() {
        String configured =
                appConfig == null || appConfig.getDashboard() == null
                        ? ""
                        : StrUtil.nullToEmpty(appConfig.getDashboard().getAccessToken());
        return configured.trim();
    }

    /** 按大小写不敏感方式读取 WebSocket 握手参数或请求头。 */
    private String webSocketParam(WebSocket socket, String name) {
        if (socket == null || StrUtil.isBlank(name)) {
            return "";
        }
        String value = socket.param(name);
        if (StrUtil.isNotBlank(value)) {
            return value;
        }
        if (socket.paramMap() == null) {
            return "";
        }
        for (String key : socket.paramMap().keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return StrUtil.nullToEmpty(socket.paramMap().get(key));
            }
        }
        return "";
    }

    /** 解码 WebSocket 查询串中的 token，兼容 Solon WebSocket 当前未自动解码的参数。 */
    private String urlDecode(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

}
