package com.jimuqu.solon.claw.support.update;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;

/** 版本检查与在线更新服务。 */
public class AppUpdateService {
    /** 缓存TTLMILLIS的统一常量值。 */
    private static final long CACHE_TTL_MILLIS = 60L * 60L * 1000L;

    /** 最大GitHubJSONREDIRECTS的统一常量值。 */
    private static final int MAX_GITHUB_JSON_REDIRECTS = 5;

    /** 注入应用配置，用于应用更新。 */
    private final AppConfig appConfig;

    /** 注入版本服务，用于调用对应业务能力。 */
    private final AppVersionService versionService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 保存exit执行器执行组件，负责调度异步或定时任务。 */
    private final ScheduledExecutorService exitExecutor =
            BoundedExecutorFactory.scheduled("self-update-exit", 1);

    /** 记录应用更新中的最近一次错误消息。 */
    private volatile String lastErrorMessage;

    /** 记录应用更新中的最近一次错误时间。 */
    private volatile long lastErrorAt;

    /**
     * 创建App更新服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param versionService 版本服务依赖。
     */
    public AppUpdateService(AppConfig appConfig, AppVersionService versionService) {
        this(appConfig, versionService, null);
    }

    /**
     * 创建App更新服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param versionService 版本服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public AppUpdateService(
            AppConfig appConfig,
            AppVersionService versionService,
            SecurityPolicyService securityPolicyService) {
        this.appConfig = appConfig;
        this.versionService = versionService;
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
    }

    /**
     * 读取版本状态。
     *
     * @param forceRefresh forceRefresh 参数。
     * @return 返回读取到的版本状态。
     */
    public VersionStatus getVersionStatus(boolean forceRefresh) {
        ReleaseInfo latest =
                forceRefresh ? fetchAndCacheLatestRelease() : loadCachedLatestRelease();
        String current = versionService.currentVersion();
        VersionStatus status = new VersionStatus();
        status.setCurrentVersion(current);
        status.setCurrentTag(versionService.currentTag());
        status.setDeploymentMode(versionService.deploymentMode());
        status.setRepo(versionService.releaseRepo());
        status.setReleaseApiUrl(versionService.releaseApiUrl());
        status.setTagsApiUrl(versionService.tagsApiUrl());
        status.setUpdateErrorMessage(lastErrorMessage);
        status.setUpdateErrorAt(lastErrorAt);
        if (latest != null) {
            status.setLatestVersion(latest.getVersion());
            status.setLatestTag(latest.getTag());
            status.setReleaseUrl(latest.getHtmlUrl());
            status.setPublishedAt(latest.getPublishedAt());
            status.setJarAssetUrl(latest.getJarAssetUrl());
            status.setJarAssetName(latest.getJarAssetName());
            status.setVersionSource(latest.getSource());
            status.setUpdateAvailable(
                    AppVersionService.compareVersions(current, latest.getVersion()) < 0);
        }
        return status;
    }

    /**
     * 格式化版本Report。
     *
     * @param forceRefresh forceRefresh 参数。
     * @return 返回版本Report结果。
     */
    public String formatVersionReport(boolean forceRefresh) {
        VersionStatus status = getVersionStatus(forceRefresh);
        StringBuilder buffer = new StringBuilder();
        appendLine(buffer, "当前版本", status.getCurrentTag());
        appendLine(buffer, "部署方式", status.getDeploymentMode());
        appendLine(buffer, "发布仓库", status.getRepo());
        appendLine(buffer, "Release API", status.getReleaseApiUrl());
        appendLine(buffer, "Tags API", status.getTagsApiUrl());
        if (StrUtil.isBlank(status.getLatestTag())) {
            if (StrUtil.isNotBlank(status.getUpdateErrorMessage())) {
                appendLine(buffer, "最新版本", "检查失败");
                appendLine(buffer, "失败原因", status.getUpdateErrorMessage());
            } else {
                appendLine(buffer, "最新版本", "尚未检查或暂不可用");
            }
            return buffer.toString().trim();
        }
        appendLine(buffer, "最新版本", status.getLatestTag());
        if (StrUtil.isNotBlank(status.getVersionSource())) {
            appendLine(buffer, "版本来源", status.getVersionSource());
        }
        if (StrUtil.isNotBlank(status.getPublishedAt())) {
            appendLine(buffer, "发布时间", status.getPublishedAt());
        }
        appendLine(buffer, "更新状态", status.isUpdateAvailable() ? "可升级" : "已是最新");
        if (StrUtil.isNotBlank(status.getReleaseUrl())) {
            appendLine(buffer, "发布页", status.getReleaseUrl());
        }
        if ("docker".equals(status.getDeploymentMode())) {
            appendLine(buffer, "在线升级", "Docker 部署不支持进程内自更新，请在宿主机拉取新镜像并重建容器。");
        } else if ("jar".equals(status.getDeploymentMode()) && status.isUpdateAvailable()) {
            appendLine(buffer, "在线升级", "可执行 `/version update` 自动下载并重启到最新 jar。");
        } else if ("jar".equals(status.getDeploymentMode())) {
            appendLine(buffer, "在线升级", "当前 jar 已是最新，无需升级。");
        } else {
            appendLine(buffer, "在线升级", "当前为开发态运行，建议通过 Git/IDE 更新代码。");
        }
        return buffer.toString().trim();
    }

    /**
     * 启动更新。
     *
     * @return 返回更新结果。
     */
    public UpdateResult startUpdate() {
        VersionStatus status = getVersionStatus(true);
        if (StrUtil.isBlank(status.getLatestTag())) {
            return UpdateResult.error(
                    "无法检查最新版本：" + StrUtil.blankToDefault(status.getUpdateErrorMessage(), "未知错误"));
        }
        if (!status.isUpdateAvailable()) {
            return UpdateResult.ok("当前已是最新版本：" + status.getCurrentTag());
        }
        if ("docker".equals(status.getDeploymentMode())) {
            return UpdateResult.ok(
                    "检测到 Docker 部署，不能由进程内直接替换镜像。\n"
                            + "最新版本: "
                            + status.getLatestTag()
                            + "\n"
                            + "请在宿主机执行：\n"
                            + "docker compose pull\n"
                            + "docker compose up -d");
        }
        if (!"jar".equals(status.getDeploymentMode())) {
            return UpdateResult.ok(
                    "当前不是 jar 部署，不能执行在线升级。\n"
                            + "最新版本: "
                            + status.getLatestTag()
                            + "\n"
                            + "请通过 Git 或重新构建方式升级。");
        }
        if (versionService.isWindows()) {
            return UpdateResult.ok(
                    "Windows 下暂未启用 jar 自更新。\n"
                            + "最新版本: "
                            + status.getLatestTag()
                            + "\n"
                            + "请下载最新 jar 后手动替换。");
        }
        if (StrUtil.isBlank(status.getJarAssetUrl())) {
            return UpdateResult.error("未找到可下载的 jar 资产。当前仅检测到版本标签，尚无对应 Release 附件。");
        }

        try {
            File currentJar = versionService.currentJarFile();
            if (currentJar == null || !currentJar.isFile()) {
                return UpdateResult.error("未找到当前运行的 jar 文件，无法执行在线升级。");
            }

            File updateDir = new File(versionService.workspaceHome(), "update");
            File logsDir = new File(versionService.workspaceHome(), "logs");
            FileUtil.mkdir(updateDir);
            FileUtil.mkdir(logsDir);

            File downloadedJar =
                    new File(
                            updateDir,
                            "solonclaw-"
                                    + AppVersionService.stripLeadingV(status.getLatestTag())
                                    + ".jar.download");
            downloadAsset(status.getJarAssetUrl(), downloadedJar);

            File argsFile = new File(updateDir, "restart-args.json");
            ONode argsNode = new ONode();
            for (String arg : versionService.startupArgs()) {
                argsNode.add(arg);
            }
            FileUtil.writeUtf8String(argsNode.toJson(), argsFile);

            File updateLog = new File(logsDir, "update.log");
            List<String> command = new ArrayList<String>();
            command.add(versionService.javaExecutable());
            command.add("-cp");
            command.add(currentJar.getAbsolutePath());
            command.add(SelfUpdateLauncher.class.getName());
            command.add(currentJar.getAbsolutePath());
            command.add(downloadedJar.getAbsolutePath());
            command.add(argsFile.getAbsolutePath());
            command.add(updateLog.getAbsolutePath());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(versionService.workspaceHome());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(updateLog));
            builder.start();

            scheduleCurrentProcessExit();

            return UpdateResult.ok("已开始在线升级到 " + status.getLatestTag() + "，应用将在几秒后自动重启。");
        } catch (Exception e) {
            return UpdateResult.error("启动在线升级失败：" + safeError(e));
        }
    }

    /** 执行调度当前进程退出相关逻辑。 */
    protected void scheduleCurrentProcessExit() {
        exitExecutor.schedule(
                new Runnable() {
                    /** 执行异步任务主体。 */
                    @Override
                    public void run() {
                        System.exit(0);
                    }
                },
                3L,
                TimeUnit.SECONDS);
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        exitExecutor.shutdownNow();
    }

    /**
     * 加载Cached Latest Release。
     *
     * @return 返回Cached Latest Release结果。
     */
    protected ReleaseInfo loadCachedLatestRelease() {
        File cacheFile = cacheFile();
        if (cacheFile.isFile()) {
            try {
                ONode node = ONode.ofJson(FileUtil.readUtf8String(cacheFile));
                long timestamp = node.get("timestamp").getLong(0L);
                if (System.currentTimeMillis() - timestamp < CACHE_TTL_MILLIS) {
                    clearLastError();
                    return ReleaseInfo.fromNode(node.get("release"));
                }
            } catch (Exception e) {
                setLastError("加载更新缓存失败，已忽略过期或损坏缓存: " + safeError(e));
            }
        }
        return null;
    }

    /**
     * 拉取And缓存LatestRelease。
     *
     * @return 返回fetch And缓存Latest Release结果。
     */
    protected ReleaseInfo fetchAndCacheLatestRelease() {
        ReleaseInfo releaseInfo = fetchLatestReleaseFromRemote();
        if (releaseInfo == null) {
            return null;
        }
        clearLastError();
        try {
            ONode node = new ONode();
            node.set("timestamp", System.currentTimeMillis());
            node.set("release", releaseInfo.toNode());
            FileUtil.mkParentDirs(cacheFile());
            FileUtil.writeUtf8String(node.toJson(), cacheFile());
        } catch (Exception e) {
            setLastError("写入更新缓存失败: " + safeError(e));
        }
        return releaseInfo;
    }

    /**
     * 拉取LatestReleaseFromRemote。
     *
     * @return 返回fetch Latest Release From Remote结果。
     */
    protected ReleaseInfo fetchLatestReleaseFromRemote() {
        ApiFetchResult releaseResult = executeGithubJson(versionService.releaseApiUrl());
        if (releaseResult.getStatusCode() >= 200 && releaseResult.getStatusCode() < 300) {
            ReleaseInfo releaseInfo = parseReleaseInfo(releaseResult.getBody());
            if (releaseInfo != null) {
                return releaseInfo;
            }
        }

        if (releaseResult.getStatusCode() == 404) {
            ApiFetchResult tagsResult = executeGithubJson(versionService.tagsApiUrl());
            if (tagsResult.getStatusCode() >= 200 && tagsResult.getStatusCode() < 300) {
                ReleaseInfo tagInfo = parseTagInfo(tagsResult.getBody());
                if (tagInfo != null) {
                    clearLastError();
                    return tagInfo;
                }
            }
            setLastError(buildGithubError("GitHub Tags API 请求失败", tagsResult));
            return null;
        }

        setLastError(buildGithubError("GitHub Release API 请求失败", releaseResult));
        return null;
    }

    /**
     * 执行downloadAsset相关逻辑。
     *
     * @param assetUrl 待校验或访问的地址参数。
     * @param target target 参数。
     */
    protected void downloadAsset(String assetUrl, File target) {
        ensureTrustedUpdateAssetUrl(assetUrl);
        BoundedAttachmentIO.downloadHutoolToFile(
                assetUrl,
                target,
                60000,
                BoundedAttachmentIO.UPDATE_JAR_MAX_BYTES,
                updateAssetSecurityPolicy());
    }

    /**
     * 更新Asset安全策略。
     *
     * @return 返回Asset安全策略结果。
     */
    protected SecurityPolicyService updateAssetSecurityPolicy() {
        return new TrustedUpdateAssetSecurityPolicyService(appConfig);
    }

    /**
     * 确保Trusted更新Asset URL。
     *
     * @param assetUrl 待校验或访问的地址参数。
     */
    protected void ensureTrustedUpdateAssetUrl(String assetUrl) {
        try {
            URI uri = URI.create(assetUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || StrUtil.isBlank(host)) {
                throw new IllegalArgumentException("Update asset URL must be HTTPS");
            }
            String normalizedHost = host.toLowerCase();
            if (!isTrustedUpdateAssetHost(normalizedHost)) {
                throw new IllegalArgumentException(
                        "Update asset URL host is not trusted: " + normalizedHost);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid update asset URL");
        }
    }

    /**
     * 判断是否Trusted更新Asset Host。
     *
     * @param normalizedHost normalized主机参数。
     * @return 如果Trusted更新Asset Host满足条件则返回 true，否则返回 false。
     */
    protected boolean isTrustedUpdateAssetHost(String normalizedHost) {
        return "github.com".equals(normalizedHost)
                || "api.github.com".equals(normalizedHost)
                || "objects.githubusercontent.com".equals(normalizedHost)
                || "release-assets.githubusercontent.com".equals(normalizedHost)
                || "github-releases.githubusercontent.com".equals(normalizedHost)
                || normalizedHost.endsWith(".githubusercontent.com");
    }

    /** 提供Trusted更新Asset安全策略相关业务能力，封装调用方不需要感知的运行细节。 */
    private class TrustedUpdateAssetSecurityPolicyService extends SecurityPolicyService {
        /**
         * 创建Trusted更新Asset安全策略服务实例，并注入运行所需依赖。
         *
         * @param appConfig 应用运行配置。
         */
        private TrustedUpdateAssetSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        /**
         * 检查URL。
         *
         * @param url 待校验或访问的 URL。
         * @return 返回URL结果。
         */
        @Override
        public UrlVerdict checkUrl(String url) {
            return checkUpdateAssetUrl(url, false);
        }

        /**
         * 检查URL 块ing私聊。
         *
         * @param url 待校验或访问的 URL。
         * @return 返回URL 块ing私聊结果。
         */
        @Override
        public UrlVerdict checkUrlBlockingPrivate(String url) {
            return checkUpdateAssetUrl(url, true);
        }

        /**
         * 检查更新Asset URL。
         *
         * @param url 待校验或访问的 URL。
         * @param blockPrivate 阻断Private参数。
         * @return 返回更新Asset URL结果。
         */
        private UrlVerdict checkUpdateAssetUrl(String url, boolean blockPrivate) {
            UrlVerdict verdict = super.checkUrl(url);
            if (blockPrivate) {
                verdict = super.checkUrlBlockingPrivate(url);
            }
            if (!verdict.isAllowed()) {
                return verdict;
            }
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (!"https".equalsIgnoreCase(scheme) || StrUtil.isBlank(host)) {
                    return UrlVerdict.block(url, "Update asset URL must be HTTPS");
                }
                String normalizedHost = host.toLowerCase();
                if (!isTrustedUpdateAssetHost(normalizedHost)) {
                    return UrlVerdict.block(
                            url, "Update asset URL host is not trusted: " + normalizedHost);
                }
                return UrlVerdict.allow();
            } catch (Exception e) {
                return UrlVerdict.block(url, "Invalid update asset URL");
            }
        }
    }

    /**
     * 执行缓存文件相关逻辑。
     *
     * @return 返回缓存文件结果。
     */
    private File cacheFile() {
        return new File(versionService.workspaceHome(), ".update_check.json");
    }

    /**
     * 追加Line。
     *
     * @param buffer buffer 参数。
     * @param label label 参数。
     * @param value 待规范化或校验的原始值。
     */
    private void appendLine(StringBuilder buffer, String label, String value) {
        if (StrUtil.isBlank(label) || StrUtil.isBlank(value)) {
            return;
        }
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(label).append(": ").append(value);
    }

    /**
     * 执行GitHub JSON。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回GitHub JSON结果。
     */
    protected ApiFetchResult executeGithubJson(String url) {
        return executeGithubJson(url, 0, url);
    }

    /**
     * 执行GitHub JSON。
     *
     * @param url 待校验或访问的 URL。
     * @param redirectCount 文件或目录路径参数。
     * @param initialUrl 待校验或访问的地址参数。
     * @return 返回GitHub JSON结果。
     */
    private ApiFetchResult executeGithubJson(String url, int redirectCount, String initialUrl) {
        ApiFetchResult result = new ApiFetchResult();
        result.setUrl(url);
        try {
            SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
            if (!verdict.isAllowed()) {
                throw new IllegalArgumentException(
                        "GitHub API URL blocked: "
                                + SecretRedactor.maskUrl(url)
                                + "，"
                                + verdict.getMessage());
            }
            HttpRequest request =
                    HttpRequest.get(url)
                            .header(Header.ACCEPT, "application/vnd.github+json")
                            .header(Header.USER_AGENT, "solonclaw")
                            .timeout(5000)
                            .setFollowRedirects(false);
            Proxy proxy = resolveProxy();
            if (proxy != null) {
                request.setProxy(proxy);
            }
            String token =
                    StrUtil.firstNonBlank(
                            RuntimeConfigResolver.getValue("solonclaw.integrations.github.token"),
                            RuntimeConfigResolver.getValue(
                                    "solonclaw.integrations.github.cliToken"));
            if (StrUtil.isNotBlank(token) && sameOrigin(initialUrl, url)) {
                request.header(Header.AUTHORIZATION, "Bearer " + token.trim());
            }
            HttpResponse response = request.execute();
            try {
                if (isRedirect(response.getStatus())) {
                    if (redirectCount >= MAX_GITHUB_JSON_REDIRECTS) {
                        throw new IllegalStateException("GitHub API redirect count exceeds limit");
                    }
                    String location = response.header("Location");
                    if (StrUtil.isBlank(location)) {
                        throw new IllegalStateException("GitHub API redirect missing Location");
                    }
                    String redirectUrl = resolveRedirectUrl(url, location);
                    response.close();
                    return executeGithubJson(redirectUrl, redirectCount + 1, initialUrl);
                }
                result.setStatusCode(response.getStatus());
                result.setBody(
                        BoundedAttachmentIO.readHutoolText(
                                response, BoundedAttachmentIO.JSON_MAX_BYTES));
            } finally {
                response.close();
            }
        } catch (Exception e) {
            result.setStatusCode(-1);
            result.setErrorMessage(
                    e.getClass().getSimpleName() + ": " + SecretRedactor.redact(e.getMessage()));
        }
        return result;
    }

    /**
     * 判断是否Redirect。
     *
     * @param status 状态参数。
     * @return 如果Redirect满足条件则返回 true，否则返回 false。
     */
    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 解析Redirect URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param location location 参数。
     * @return 返回解析后的Redirect URL。
     */
    private String resolveRedirectUrl(String baseUrl, String location) {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "GitHub API redirect URL is invalid: " + SecretRedactor.maskUrl(location), e);
        }
    }

    /**
     * 执行sameOrigin相关逻辑。
     *
     * @param initialUrl 待校验或访问的地址参数。
     * @param url 待校验或访问的 URL。
     * @return 返回same Origin结果。
     */
    private boolean sameOrigin(String initialUrl, String url) {
        try {
            URI initial = URI.create(initialUrl);
            URI current = URI.create(url);
            return StrUtil.equalsIgnoreCase(initial.getScheme(), current.getScheme())
                    && StrUtil.equalsIgnoreCase(initial.getHost(), current.getHost())
                    && effectivePort(initial) == effectivePort(current);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行生效端口相关逻辑。
     *
     * @param uri 待校验或访问的地址参数。
     * @return 返回生效Port结果。
     */
    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
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
     * 解析Release Info。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回解析后的Release Info。
     */
    protected ReleaseInfo parseReleaseInfo(String body) {
        try {
            ONode node = ONode.ofJson(body);
            ReleaseInfo releaseInfo = new ReleaseInfo();
            releaseInfo.setTag(node.get("tag_name").getString());
            if (StrUtil.isBlank(releaseInfo.getTag())) {
                setLastError("Release API 响应缺少 tag_name");
                return null;
            }
            releaseInfo.setVersion(AppVersionService.stripLeadingV(releaseInfo.getTag()));
            releaseInfo.setHtmlUrl(node.get("html_url").getString());
            releaseInfo.setPublishedAt(node.get("published_at").getString());
            releaseInfo.setSource("release");
            ONode assets = node.get("assets");
            for (int i = 0; i < assets.size(); i++) {
                ONode asset = assets.get(i);
                String name = asset.get("name").getString();
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                if (name.startsWith("solonclaw-") && name.endsWith(".jar")) {
                    releaseInfo.setJarAssetName(name);
                    releaseInfo.setJarAssetUrl(asset.get("browser_download_url").getString());
                    break;
                }
            }
            return releaseInfo;
        } catch (Exception e) {
            setLastError(e.getClass().getSimpleName() + ": " + safeError(e));
            return null;
        }
    }

    /**
     * 解析Tag Info。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回解析后的Tag Info。
     */
    protected ReleaseInfo parseTagInfo(String body) {
        try {
            ONode node = ONode.ofJson(body);
            if (!node.isArray() || node.size() == 0) {
                setLastError("Tags API 响应为空");
                return null;
            }
            ONode first = node.get(0);
            String tag = first.get("name").getString();
            if (StrUtil.isBlank(tag)) {
                setLastError("Tags API 响应缺少 name");
                return null;
            }
            ReleaseInfo releaseInfo = new ReleaseInfo();
            releaseInfo.setTag(tag);
            releaseInfo.setVersion(AppVersionService.stripLeadingV(tag));
            releaseInfo.setHtmlUrl("https://github.com/" + versionService.releaseRepo() + "/tags");
            releaseInfo.setPublishedAt(null);
            releaseInfo.setSource("tag");
            return releaseInfo;
        } catch (Exception e) {
            setLastError(e.getClass().getSimpleName() + ": " + safeError(e));
            return null;
        }
    }

    /**
     * 构建GitHub Error。
     *
     * @param prefix prefix 参数。
     * @param result 结果响应或执行结果。
     * @return 返回创建好的GitHub Error。
     */
    private String buildGithubError(String prefix, ApiFetchResult result) {
        if (result == null) {
            return prefix + "，未知错误";
        }
        if (StrUtil.isNotBlank(result.getErrorMessage())) {
            return prefix + "，" + result.getErrorMessage();
        }
        if (result.getStatusCode() > 0) {
            String body = safeErrorBody(result.getBody());
            return prefix
                    + "，HTTP "
                    + result.getStatusCode()
                    + (StrUtil.isBlank(body) ? "" : " " + body);
        }
        return prefix + "，未知错误";
    }

    /**
     * 生成安全展示用的错误正文。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回safe Error Body结果。
     */
    private String safeErrorBody(String body) {
        String text =
                SecretRedactor.redact(StrUtil.nullToEmpty(body), 1000)
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .trim();
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }

    /**
     * 解析Proxy。
     *
     * @return 返回解析后的Proxy。
     */
    private Proxy resolveProxy() {
        String proxyUrl = versionService.updateProxyUrl();
        if (StrUtil.isBlank(proxyUrl)) {
            return null;
        }
        try {
            return ProxyUrlSupport.parseProxy(proxyUrl);
        } catch (Exception e) {
            setLastError("更新代理地址解析失败: " + SecretRedactor.maskUrl(proxyUrl) + "，" + safeError(e));
            return null;
        }
    }

    /**
     * 写入Last Error。
     *
     * @param message 平台消息或错误消息。
     */
    private void setLastError(String message) {
        this.lastErrorMessage = SecretRedactor.redact(StrUtil.nullToEmpty(message), 1000).trim();
        this.lastErrorAt = System.currentTimeMillis();
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Exception e) {
        if (e == null) {
            return "Exception";
        }
        return SecretRedactor.redact(
                StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 1000);
    }

    /** 清理Last Error。 */
    private void clearLastError() {
        this.lastErrorMessage = null;
        this.lastErrorAt = 0L;
    }

    /** 表示更新结果，携带调用方后续判断所需信息。 */
    public static class UpdateResult {
        /** 是否启用错误。 */
        private final boolean error;

        /** 记录更新中的消息。 */
        private final String message;

        /**
         * 创建更新结果实例，并注入运行所需依赖。
         *
         * @param error 错误参数。
         * @param message 平台消息或错误消息。
         */
        private UpdateResult(boolean error, String message) {
            this.error = error;
            this.message = message;
        }

        /**
         * 构造成功结果。
         *
         * @param message 平台消息或错误消息。
         * @return 返回ok结果。
         */
        public static UpdateResult ok(String message) {
            return new UpdateResult(false, message);
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param message 平台消息或错误消息。
         * @return 返回error结果。
         */
        public static UpdateResult error(String message) {
            return new UpdateResult(
                    true, SecretRedactor.redact(StrUtil.nullToEmpty(message), 1000));
        }

        /**
         * 判断是否Error。
         *
         * @return 如果Error满足条件则返回 true，否则返回 false。
         */
        public boolean isError() {
            return error;
        }

        /**
         * 读取消息。
         *
         * @return 返回读取到的消息。
         */
        public String getMessage() {
            return message;
        }
    }

    /** 承载版本状态相关状态和辅助逻辑。 */
    public static class VersionStatus {
        /** 记录版本状态中的当前版本。 */
        private String currentVersion;

        /** 记录版本状态中的当前Tag。 */
        private String currentTag;

        /** 记录版本状态中的latest版本。 */
        private String latestVersion;

        /** 记录版本状态中的latestTag。 */
        private String latestTag;

        /** 记录版本状态中的deployment模式。 */
        private String deploymentMode;

        /** 记录版本状态中的repo。 */
        private String repo;

        /** 记录版本状态中的releaseURL。 */
        private String releaseUrl;

        /** 记录版本状态中的releaseApiURL。 */
        private String releaseApiUrl;

        /** 记录版本状态中的tagsApiURL。 */
        private String tagsApiUrl;

        /** 记录版本状态中的published时间。 */
        private String publishedAt;

        /** 记录版本状态中的jarAssetURL。 */
        private String jarAssetUrl;

        /** 记录版本状态中的jarAsset名称。 */
        private String jarAssetName;

        /** 是否启用更新Available。 */
        private boolean updateAvailable;

        /** 记录版本状态中的更新错误消息。 */
        private String updateErrorMessage;

        /** 记录版本状态中的更新错误时间。 */
        private long updateErrorAt;

        /** 记录版本状态中的版本来源。 */
        private String versionSource;

        /**
         * 读取当前版本。
         *
         * @return 返回读取到的当前版本。
         */
        public String getCurrentVersion() {
            return currentVersion;
        }

        /**
         * 写入当前版本。
         *
         * @param currentVersion current版本参数。
         */
        public void setCurrentVersion(String currentVersion) {
            this.currentVersion = currentVersion;
        }

        /**
         * 读取当前Tag。
         *
         * @return 返回读取到的当前Tag。
         */
        public String getCurrentTag() {
            return currentTag;
        }

        /**
         * 写入当前Tag。
         *
         * @param currentTag currentTag 参数。
         */
        public void setCurrentTag(String currentTag) {
            this.currentTag = currentTag;
        }

        /**
         * 读取Latest版本。
         *
         * @return 返回读取到的Latest版本。
         */
        public String getLatestVersion() {
            return latestVersion;
        }

        /**
         * 写入Latest版本。
         *
         * @param latestVersion latest版本参数。
         */
        public void setLatestVersion(String latestVersion) {
            this.latestVersion = latestVersion;
        }

        /**
         * 读取Latest Tag。
         *
         * @return 返回读取到的Latest Tag。
         */
        public String getLatestTag() {
            return latestTag;
        }

        /**
         * 写入Latest Tag。
         *
         * @param latestTag latestTag 参数。
         */
        public void setLatestTag(String latestTag) {
            this.latestTag = latestTag;
        }

        /**
         * 读取Deployment模式。
         *
         * @return 返回读取到的Deployment模式。
         */
        public String getDeploymentMode() {
            return deploymentMode;
        }

        /**
         * 写入Deployment模式。
         *
         * @param deploymentMode deployment模式参数。
         */
        public void setDeploymentMode(String deploymentMode) {
            this.deploymentMode = deploymentMode;
        }

        /**
         * 读取Repo。
         *
         * @return 返回读取到的Repo。
         */
        public String getRepo() {
            return repo;
        }

        /**
         * 写入Repo。
         *
         * @param repo repo 参数。
         */
        public void setRepo(String repo) {
            this.repo = repo;
        }

        /**
         * 读取Release URL。
         *
         * @return 返回读取到的Release URL。
         */
        public String getReleaseUrl() {
            return releaseUrl;
        }

        /**
         * 写入Release URL。
         *
         * @param releaseUrl 待校验或访问的地址参数。
         */
        public void setReleaseUrl(String releaseUrl) {
            this.releaseUrl = releaseUrl;
        }

        /**
         * 读取Release Api URL。
         *
         * @return 返回读取到的Release Api URL。
         */
        public String getReleaseApiUrl() {
            return releaseApiUrl;
        }

        /**
         * 写入Release Api URL。
         *
         * @param releaseApiUrl 待校验或访问的地址参数。
         */
        public void setReleaseApiUrl(String releaseApiUrl) {
            this.releaseApiUrl = releaseApiUrl;
        }

        /**
         * 读取Tags Api URL。
         *
         * @return 返回读取到的Tags Api URL。
         */
        public String getTagsApiUrl() {
            return tagsApiUrl;
        }

        /**
         * 写入Tags Api URL。
         *
         * @param tagsApiUrl 待校验或访问的地址参数。
         */
        public void setTagsApiUrl(String tagsApiUrl) {
            this.tagsApiUrl = tagsApiUrl;
        }

        /**
         * 读取Published时间。
         *
         * @return 返回读取到的Published时间。
         */
        public String getPublishedAt() {
            return publishedAt;
        }

        /**
         * 写入Published时间。
         *
         * @param publishedAt publishedAt 参数。
         */
        public void setPublishedAt(String publishedAt) {
            this.publishedAt = publishedAt;
        }

        /**
         * 读取Jar Asset URL。
         *
         * @return 返回读取到的Jar Asset URL。
         */
        public String getJarAssetUrl() {
            return jarAssetUrl;
        }

        /**
         * 写入Jar Asset URL。
         *
         * @param jarAssetUrl 待校验或访问的地址参数。
         */
        public void setJarAssetUrl(String jarAssetUrl) {
            this.jarAssetUrl = jarAssetUrl;
        }

        /**
         * 读取Jar Asset名称。
         *
         * @return 返回读取到的Jar Asset名称。
         */
        public String getJarAssetName() {
            return jarAssetName;
        }

        /**
         * 写入Jar Asset名称。
         *
         * @param jarAssetName jarAsset名称参数。
         */
        public void setJarAssetName(String jarAssetName) {
            this.jarAssetName = jarAssetName;
        }

        /**
         * 判断是否更新Available。
         *
         * @return 如果更新Available满足条件则返回 true，否则返回 false。
         */
        public boolean isUpdateAvailable() {
            return updateAvailable;
        }

        /**
         * 写入更新Available。
         *
         * @param updateAvailable updateAvailable 参数。
         */
        public void setUpdateAvailable(boolean updateAvailable) {
            this.updateAvailable = updateAvailable;
        }

        /**
         * 读取更新Error消息。
         *
         * @return 返回读取到的更新Error消息。
         */
        public String getUpdateErrorMessage() {
            return updateErrorMessage;
        }

        /**
         * 写入更新Error消息。
         *
         * @param updateErrorMessage update错误消息参数。
         */
        public void setUpdateErrorMessage(String updateErrorMessage) {
            this.updateErrorMessage = updateErrorMessage;
        }

        /**
         * 读取更新Error时间。
         *
         * @return 返回读取到的更新Error时间。
         */
        public long getUpdateErrorAt() {
            return updateErrorAt;
        }

        /**
         * 写入更新Error时间。
         *
         * @param updateErrorAt update错误At参数。
         */
        public void setUpdateErrorAt(long updateErrorAt) {
            this.updateErrorAt = updateErrorAt;
        }

        /**
         * 读取版本来源。
         *
         * @return 返回读取到的版本来源。
         */
        public String getVersionSource() {
            return versionSource;
        }

        /**
         * 写入版本来源。
         *
         * @param versionSource 版本来源参数。
         */
        public void setVersionSource(String versionSource) {
            this.versionSource = versionSource;
        }
    }

    /** 承载ReleaseInfo相关状态和辅助逻辑。 */
    protected static class ReleaseInfo {
        /** 记录ReleaseInfo中的tag。 */
        private String tag;

        /** 记录ReleaseInfo中的版本。 */
        private String version;

        /** 记录ReleaseInfo中的htmlURL。 */
        private String htmlUrl;

        /** 记录ReleaseInfo中的published时间。 */
        private String publishedAt;

        /** 记录ReleaseInfo中的jarAssetURL。 */
        private String jarAssetUrl;

        /** 记录ReleaseInfo中的jarAsset名称。 */
        private String jarAssetName;

        /** 记录ReleaseInfo中的来源。 */
        private String source;

        /**
         * 读取Tag。
         *
         * @return 返回读取到的Tag。
         */
        public String getTag() {
            return tag;
        }

        /**
         * 写入Tag。
         *
         * @param tag tag 参数。
         */
        public void setTag(String tag) {
            this.tag = tag;
        }

        /**
         * 读取版本。
         *
         * @return 返回读取到的版本。
         */
        public String getVersion() {
            return version;
        }

        /**
         * 写入版本。
         *
         * @param version 版本参数。
         */
        public void setVersion(String version) {
            this.version = version;
        }

        /**
         * 读取Html URL。
         *
         * @return 返回读取到的Html URL。
         */
        public String getHtmlUrl() {
            return htmlUrl;
        }

        /**
         * 写入Html URL。
         *
         * @param htmlUrl 待校验或访问的地址参数。
         */
        public void setHtmlUrl(String htmlUrl) {
            this.htmlUrl = htmlUrl;
        }

        /**
         * 读取Published时间。
         *
         * @return 返回读取到的Published时间。
         */
        public String getPublishedAt() {
            return publishedAt;
        }

        /**
         * 写入Published时间。
         *
         * @param publishedAt publishedAt 参数。
         */
        public void setPublishedAt(String publishedAt) {
            this.publishedAt = publishedAt;
        }

        /**
         * 读取Jar Asset URL。
         *
         * @return 返回读取到的Jar Asset URL。
         */
        public String getJarAssetUrl() {
            return jarAssetUrl;
        }

        /**
         * 写入Jar Asset URL。
         *
         * @param jarAssetUrl 待校验或访问的地址参数。
         */
        public void setJarAssetUrl(String jarAssetUrl) {
            this.jarAssetUrl = jarAssetUrl;
        }

        /**
         * 读取Jar Asset名称。
         *
         * @return 返回读取到的Jar Asset名称。
         */
        public String getJarAssetName() {
            return jarAssetName;
        }

        /**
         * 写入Jar Asset名称。
         *
         * @param jarAssetName jarAsset名称参数。
         */
        public void setJarAssetName(String jarAssetName) {
            this.jarAssetName = jarAssetName;
        }

        /**
         * 读取来源。
         *
         * @return 返回读取到的来源。
         */
        public String getSource() {
            return source;
        }

        /**
         * 写入来源。
         *
         * @param source 来源参数。
         */
        public void setSource(String source) {
            this.source = source;
        }

        /**
         * 转换为Node。
         *
         * @return 返回转换后的Node。
         */
        public ONode toNode() {
            return new ONode()
                    .set("tag", tag)
                    .set("version", version)
                    .set("htmlUrl", htmlUrl)
                    .set("publishedAt", publishedAt)
                    .set("jarAssetUrl", jarAssetUrl)
                    .set("jarAssetName", jarAssetName)
                    .set("source", source);
        }

        /**
         * 从输入转换Node。
         *
         * @param node 节点参数。
         * @return 返回Node结果。
         */
        public static ReleaseInfo fromNode(ONode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            ReleaseInfo releaseInfo = new ReleaseInfo();
            releaseInfo.setTag(node.get("tag").getString());
            releaseInfo.setVersion(node.get("version").getString());
            releaseInfo.setHtmlUrl(node.get("htmlUrl").getString());
            releaseInfo.setPublishedAt(node.get("publishedAt").getString());
            releaseInfo.setJarAssetUrl(node.get("jarAssetUrl").getString());
            releaseInfo.setJarAssetName(node.get("jarAssetName").getString());
            releaseInfo.setSource(node.get("source").getString());
            return releaseInfo;
        }
    }

    /** 表示ApiFetch结果，携带调用方后续判断所需信息。 */
    protected static class ApiFetchResult {
        /** 记录ApiFetch中的状态Code。 */
        private int statusCode;

        /** 记录ApiFetch中的正文。 */
        private String body;

        /** 记录ApiFetch中的错误消息。 */
        private String errorMessage;

        /** 记录ApiFetch中的URL。 */
        private String url;

        /** 创建Api Fetch结果实例。 */
        public ApiFetchResult() {}

        /**
         * 读取状态Code。
         *
         * @return 返回读取到的状态Code。
         */
        public int getStatusCode() {
            return statusCode;
        }

        /**
         * 写入状态Code。
         *
         * @param statusCode 状态Code参数。
         */
        public void setStatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        /**
         * 读取Body。
         *
         * @return 返回读取到的Body。
         */
        public String getBody() {
            return body;
        }

        /**
         * 写入Body。
         *
         * @param body 请求体或消息正文内容。
         */
        public void setBody(String body) {
            this.body = body;
        }

        /**
         * 读取Error消息。
         *
         * @return 返回读取到的Error消息。
         */
        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * 写入Error消息。
         *
         * @param errorMessage 错误消息参数。
         */
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        /**
         * 读取URL。
         *
         * @return 返回读取到的URL。
         */
        public String getUrl() {
            return url;
        }

        /**
         * 写入URL。
         *
         * @param url 待校验或访问的 URL。
         */
        public void setUrl(String url) {
            this.url = url;
        }
    }
}
