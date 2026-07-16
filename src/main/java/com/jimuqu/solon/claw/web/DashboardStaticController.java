package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import com.jimuqu.solon.claw.support.RuntimePathGuard;
import java.io.File;
import java.io.InputStream;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.DownloadedFile;

/** Dashboard 静态资源兜底输出，绕过当前打包后静态文件处理器返回空内容的问题。 */
@Controller
public class DashboardStaticController {
    /** Vite 带 hash 的 chunk 可长期缓存；入口页由 DashboardPageController 禁用缓存。 */
    private static final String HASHED_ASSET_CACHE_CONTROL = "public, max-age=31536000, immutable";

    /** 非 hash 静态资源保守重验，避免 favicon/logo 这类固定文件名长期陈旧。 */
    private static final String STATIC_RESOURCE_CACHE_CONTROL = "no-cache";

    /** 缺失资源不缓存，避免部署切换时浏览器记住旧 chunk 的 404。 */
    private static final String MISSING_RESOURCE_CACHE_CONTROL = "no-store, max-age=0";

    /** 记录控制台静态资源中的路径保护。 */
    private final RuntimePathGuard pathGuard;

    /**
     * 创建控制台静态资源控制器实例，并注入运行所需依赖。
     *
     * @param pathGuard 文件或目录路径参数。
     */
    public DashboardStaticController(RuntimePathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    /**
     * 执行assets相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回assets结果。
     */
    @Mapping("/assets/**")
    public Object assets(Context context) {
        return renderResource(context, "static" + context.path());
    }

    /**
     * 执行faviconIco相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回favicon Ico结果。
     */
    @Mapping("/favicon.ico")
    public Object faviconIco(Context context) {
        return renderResource(context, "static/favicon.ico");
    }

    /**
     * 执行faviconSvg相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回favicon Svg结果。
     */
    @Mapping("/favicon.svg")
    public Object faviconSvg(Context context) {
        return renderResource(context, "static/favicon.svg");
    }

    /**
     * 执行logo相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回logo结果。
     */
    @Mapping("/logo.png")
    public Object logo(Context context) {
        return renderResource(context, "static/logo.png");
    }

    /**
     * 渲染资源。
     *
     * @param context 当前请求或运行上下文。
     * @param resourcePath 文件或目录路径参数。
     * @return 返回render Resource结果。
     */
    private Object renderResource(Context context, String resourcePath) {
        File devFile = loadDevFile(resourcePath);
        if (devFile != null) {
            applyFoundResourceCacheHeaders(context, resourcePath);
            return new DownloadedFile(
                            contentType(resourcePath),
                            FileUtil.readBytes(devFile),
                            devFile.getName())
                    .asAttachment(false);
        }

        byte[] bytes = loadClasspathBytes(resourcePath);
        if (bytes == null) {
            context.status(404);
            context.contentType("text/plain;charset=UTF-8");
            context.headerSet("Cache-Control", MISSING_RESOURCE_CACHE_CONTROL);
            return "Not found";
        }

        applyFoundResourceCacheHeaders(context, resourcePath);
        return new DownloadedFile(contentType(resourcePath), bytes, fileName(resourcePath))
                .asAttachment(false);
    }

    /**
     * 写入已命中静态资源的缓存策略。
     *
     * @param context 当前请求上下文。
     * @param resourcePath 静态资源路径。
     */
    private void applyFoundResourceCacheHeaders(Context context, String resourcePath) {
        context.headerSet(
                "Cache-Control",
                resourcePath.startsWith("static/assets/")
                        ? HASHED_ASSET_CACHE_CONTROL
                        : STATIC_RESOURCE_CACHE_CONTROL);
    }

    /**
     * 加载Dev文件。
     *
     * @param resourcePath 文件或目录路径参数。
     * @return 返回Dev文件结果。
     */
    private File loadDevFile(String resourcePath) {
        String relative =
                resourcePath.startsWith("static/")
                        ? resourcePath.substring("static/".length())
                        : resourcePath;
        if (relative.contains("..") || relative.startsWith("/") || relative.startsWith("\\")) {
            return null;
        }
        File devFile =
                pathGuard.requireUnderWebDist(
                        new File(
                                System.getProperty("user.dir"),
                                "web/dist/" + relative.replace('/', File.separatorChar)));
        if (devFile.exists() && devFile.isFile()) {
            return devFile;
        }
        return null;
    }

    /**
     * 加载Classpath Bytes。
     *
     * @param resourcePath 文件或目录路径参数。
     * @return 返回Classpath Bytes结果。
     */
    private byte[] loadClasspathBytes(String resourcePath) {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            return null;
        }

        return IoUtil.readBytes(stream);
    }

    /**
     * 执行文件名称相关逻辑。
     *
     * @param resourcePath 文件或目录路径参数。
     * @return 返回文件名称结果。
     */
    private String fileName(String resourcePath) {
        int idx = resourcePath.lastIndexOf('/');
        return idx >= 0 ? resourcePath.substring(idx + 1) : resourcePath;
    }

    /**
     * 执行content类型相关逻辑。
     *
     * @param resourcePath 文件或目录路径参数。
     * @return 返回content类型结果。
     */
    private String contentType(String resourcePath) {
        String lower = resourcePath.toLowerCase();
        if (lower.endsWith(".js")) {
            return "application/javascript;charset=UTF-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css;charset=UTF-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lower.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".html")) {
            return "text/html;charset=UTF-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json;charset=UTF-8";
        }
        return "application/octet-stream";
    }
}
