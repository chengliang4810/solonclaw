package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.DownloadedFile;

/** Dashboard SPA 页面入口。 */
@Controller
public class DashboardPageController {
    /** SPA 入口必须每次重新验证，避免旧入口继续引用已下线的前端 chunk。 */
    private static final String INDEX_CACHE_CONTROL =
            "no-store, no-cache, must-revalidate, max-age=0";

    /** 注入认证服务，用于调用对应业务能力。 */
    private final DashboardAuthService authService;

    /**
     * 创建控制台页面控制器实例，并注入运行所需依赖。
     *
     * @param authService 鉴权服务依赖。
     */
    public DashboardPageController(DashboardAuthService authService) {
        this.authService = authService;
    }

    /**
     * 执行索引相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回index结果。
     */
    @Mapping("/")
    public DownloadedFile index(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行索引Html相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回index Html结果。
     */
    @Mapping("/index.html")
    public DownloadedFile indexHtml(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行状态相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回状态。
     */
    @Mapping("/status")
    public DownloadedFile status(Context context) {
        return renderIndex(context);
    }

    /**
     * 将诊断页短路径交给Dashboard单页应用，由前端Hash路由定位诊断页面。
     *
     * @param context 当前请求上下文，用于复用Dashboard入口文件响应。
     * @return 返回Dashboard页面入口。
     */
    @Mapping("/diagnostics")
    public DownloadedFile diagnostics(Context context) {
        return renderIndex(context);
    }

    /**
     * 将TUI运行时短路径交给Dashboard单页应用，由前端Hash路由定位运行时页面。
     *
     * @param context 当前请求上下文，用于复用Dashboard入口文件响应。
     * @return 返回Dashboard页面入口。
     */
    @Mapping("/tui-runtime")
    public DownloadedFile tuiRuntime(Context context) {
        return renderIndex(context);
    }

    /**
     * 将策展页短路径交给Dashboard单页应用，由前端Hash路由定位策展页面。
     *
     * @param context 当前请求上下文，用于复用Dashboard入口文件响应。
     * @return 返回Dashboard页面入口。
     */
    @Mapping("/curator")
    public DownloadedFile curator(Context context) {
        return renderIndex(context);
    }

    /**
     * 将MCP页面短路径交给Dashboard单页应用，由前端Hash路由定位MCP页面。
     *
     * @param context 当前请求上下文，用于复用Dashboard入口文件响应。
     * @return 返回Dashboard页面入口。
     */
    @Mapping("/mcp")
    public DownloadedFile mcp(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行login相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回login结果。
     */
    @Mapping("/login")
    public DownloadedFile login(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行聊天相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回chat结果。
     */
    @Mapping("/chat")
    public DownloadedFile chat(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行控制台前端路由兜底相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回Dashboard页面入口。
     */
    @Mapping("/solonclaw/**")
    public DownloadedFile solonClawRoutes(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行控制台基础路径兜底相关逻辑，保证刷新或分享基础路径时仍能进入前端应用。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回Dashboard页面入口。
     */
    @Mapping("/solonclaw")
    public DownloadedFile solonClawBase(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行sessions相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回sessions结果。
     */
    @Mapping("/sessions")
    public DownloadedFile sessions(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行分析相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回分析结果。
     */
    @Mapping("/analytics")
    public DownloadedFile analytics(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行models相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回models结果。
     */
    @Mapping("/models")
    public DownloadedFile models(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行记忆相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回记忆结果。
     */
    @Mapping("/memory")
    public DownloadedFile memory(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行logs相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回logs结果。
     */
    @Mapping("/logs")
    public DownloadedFile logs(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行gateways相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回gateways结果。
     */
    @Mapping("/gateways")
    public DownloadedFile gateways(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行channels相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回channels结果。
     */
    @Mapping("/channels")
    public DownloadedFile channels(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行agents相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回agents结果。
     */
    @Mapping("/agents")
    public DownloadedFile agents(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行files相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回files结果。
     */
    @Mapping("/files")
    public DownloadedFile files(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行工作区相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回工作区结果。
     */
    @Mapping("/workspace")
    public DownloadedFile workspace(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行定时任务相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回定时任务结果。
     */
    @Mapping("/cron")
    public DownloadedFile cron(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行技能相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回技能结果。
     */
    @Mapping("/skills")
    public DownloadedFile skills(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行配置相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回配置。
     */
    @Mapping("/config")
    public DownloadedFile config(Context context) {
        return renderIndex(context);
    }

    /**
     * 执行环境变量相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回env结果。
     */
    @Mapping("/env")
    public DownloadedFile env(Context context) {
        return renderIndex(context);
    }

    /**
     * 渲染索引。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回render Index结果。
     */
    private DownloadedFile renderIndex(Context context) {
        applyIndexCacheHeaders(context);
        String html = loadIndexHtml();
        if (html == null) {
            context.status(503);
            return new DownloadedFile(
                            "text/plain;charset=UTF-8",
                            "Dashboard frontend not built".getBytes(StandardCharsets.UTF_8),
                            "error.txt")
                    .asAttachment(false);
        }

        String rendered =
                authService.canRevealToken(context) ? authService.injectToken(html) : html;
        return new DownloadedFile(
                        "text/html;charset=UTF-8",
                        rendered.getBytes(StandardCharsets.UTF_8),
                        "index.html")
                .asAttachment(false);
    }

    /**
     * 写入 Dashboard 入口页缓存策略。
     *
     * @param context 当前请求上下文。
     */
    private void applyIndexCacheHeaders(Context context) {
        context.headerSet("Cache-Control", INDEX_CACHE_CONTROL);
        context.headerSet("Pragma", "no-cache");
        context.headerSet("Expires", "0");
    }

    /**
     * 加载Index Html。
     *
     * @return 返回Index Html结果。
     */
    private String loadIndexHtml() {
        File devFile = new File(System.getProperty("user.dir"), "web/dist/index.html");
        if (devFile.exists()) {
            return FileUtil.readUtf8String(devFile);
        }

        InputStream stream = getClass().getClassLoader().getResourceAsStream("static/index.html");
        if (stream == null) {
            return null;
        }

        return IoUtil.read(stream, StandardCharsets.UTF_8);
    }
}
