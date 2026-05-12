package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

public class AppUpdateServiceTest {
    @Test
    void shouldFallbackToTagsWhenReleaseApiReturns404() {
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        versionService.setDeploymentMode("docker");
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        versionService.setReleaseRepo("chengliang4810/solon-claw");

        FakeUpdateService service = new FakeUpdateService(new AppConfig(), versionService);
        service.setReleaseStatus(404);
        service.setTagsBody("[{\"name\":\"v0.0.2\"}]");

        AppUpdateService.VersionStatus status = service.getVersionStatus(true);

        assertThat(status.getLatestTag()).isEqualTo("v0.0.2");
        assertThat(status.getVersionSource()).isEqualTo("tag");
        assertThat(status.isUpdateAvailable()).isTrue();
        assertThat(status.getUpdateErrorMessage()).isNull();
    }

    @Test
    void shouldRenderVersionReportAsMultiLineKeyValuePairs() {
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        versionService.setDeploymentMode("docker");
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        versionService.setReleaseRepo("chengliang4810/solon-claw");

        FakeUpdateService service = new FakeUpdateService(new AppConfig(), versionService);
        service.setReleaseStatus(404);
        service.setTagsBody("[{\"name\":\"v0.0.2\"}]");

        String report = service.formatVersionReport(true);

        assertThat(report).contains("当前版本: v0.0.1");
        assertThat(report).contains("部署方式: docker");
        assertThat(report).contains("最新版本: v0.0.2");
        assertThat(report).contains("版本来源: tag");
        assertThat(report.split("\\R")).allMatch(line -> line.contains(": "));
    }

    @Test
    void shouldAllowDockerUpdateWhenLatestVersionComesFromTags() {
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        versionService.setDeploymentMode("docker");
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        versionService.setReleaseRepo("chengliang4810/solon-claw");

        FakeUpdateService service = new FakeUpdateService(new AppConfig(), versionService);
        service.setReleaseStatus(404);
        service.setTagsBody("[{\"name\":\"v0.0.2\"}]");

        AppUpdateService.UpdateResult result = service.startUpdate();

        assertThat(result.isError()).isFalse();
        assertThat(result.getMessage()).contains("Docker 部署");
        assertThat(result.getMessage()).contains("最新版本: v0.0.2");
        assertThat(result.getMessage()).contains("docker compose pull");
    }

    @Test
    void shouldTrustKnownGithubReleaseAssetHosts() {
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        FakeUpdateService service = new FakeUpdateService(new AppConfig(), versionService);

        service.exposeEnsureTrustedUpdateAssetUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/file.jar");

        assertThatThrownBy(
                        () ->
                                service.exposeEnsureTrustedUpdateAssetUrl(
                                        "https://downloads.example.com/file.jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not trusted");
    }

    @Test
    void shouldApplySecurityPolicyWhenDownloadingUpdateAsset() {
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        PolicyBlockingUpdateService service =
                new PolicyBlockingUpdateService(new AppConfig(), versionService);

        assertThatThrownBy(
                        () ->
                                service.exposeDownloadAsset(
                                        "https://github.com/chengliang4810/solon-claw/releases/download/v1/app.jar",
                                        new File("target/update-test/app.jar")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attachment download URL blocked")
                .hasMessageContaining("blocked-by-test");
    }

    @Test
    void shouldBlockUnsafeGithubJsonRedirectTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/release",
                    exchange -> {
                        exchange.getResponseHeaders()
                                .set(
                                        "Location",
                                        "http://169.254.169.254/latest/meta-data/?token=secret");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            server.start();
            AppConfig config = new AppConfig();
            FakeVersionService versionService = new FakeVersionService(config);
            ExposedUpdateService service =
                    new ExposedUpdateService(
                            config,
                            versionService,
                            new AllowLocalButBlockMetadataSecurityPolicyService(config));

            ApiResultSnapshot result =
                    service.exposeExecuteGithubJson(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/release");

            assertThat(result.statusCode).isEqualTo(-1);
            assertThat(result.errorMessage)
                    .contains("GitHub API URL blocked")
                    .contains("169.254.169.254")
                    .contains("token=***");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactUpdateParseAndProxyErrors() {
        String leakedToken = "sk-updateparse12345";
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        FakeUpdateService parseService = new FakeUpdateService(new AppConfig(), versionService);

        parseService.exposeParseReleaseInfo("{\"tag_name\":\"v0.0.2\",\"broken\":\"api_key=" + leakedToken);

        assertThat(parseService.currentLastError()).doesNotContain(leakedToken);

        String downloadToken = "sk-updatedownload12345";
        AppConfig jarConfig = new AppConfig();
        jarConfig.getRuntime().setHome(new File("target/update-test-runtime").getAbsolutePath());
        FakeVersionService jarVersionService = new FakeVersionService(jarConfig);
        jarVersionService.setDeploymentMode("jar");
        jarVersionService.setCurrentVersion("0.0.1");
        jarVersionService.setCurrentTag("v0.0.1");
        FileUtil.touch(new File("target/fake-current.jar"));
        DownloadFailingUpdateService downloadService =
                new DownloadFailingUpdateService(jarConfig, jarVersionService, downloadToken);
        ((FakeUpdateService) downloadService).setReleaseBody(
                "{\"tag_name\":\"v0.0.2\",\"assets\":[{\"name\":\"solon-claw-0.0.2.jar\",\"browser_download_url\":\"https://github.com/chengliang4810/solon-claw/releases/download/v0.0.2/app.jar\"}]}");

        AppUpdateService.UpdateResult downloadResult = downloadService.startUpdate();

        assertThat(downloadResult.isError()).isTrue();
        assertThat(downloadResult.getMessage()).contains("***").doesNotContain(downloadToken);

    }

    @Test
    void shouldRedactGithubApiErrorBody() {
        String leakedToken = "ghp_updatebody12345";
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        FakeUpdateService service = new FakeUpdateService(new AppConfig(), versionService);
        service.setReleaseStatus(500);
        service.setReleaseBody("{\"message\":\"token=" + leakedToken + "\"}");

        AppUpdateService.VersionStatus status = service.getVersionStatus(true);

        assertThat(status.getUpdateErrorMessage())
                .contains("HTTP 500")
                .contains("token=***")
                .doesNotContain(leakedToken);
    }

    @Test
    void shouldRedactUpdateFetchErrorAtStatusBoundary() {
        String leakedToken = "ghp_updatefetch12345";
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        FakeUpdateService service = new FakeUpdateService(new AppConfig(), versionService);
        service.setReleaseError("transport failed Authorization: Bearer " + leakedToken);

        AppUpdateService.VersionStatus status = service.getVersionStatus(true);

        assertThat(status.getUpdateErrorMessage())
                .contains("GitHub Release API 请求失败")
                .contains("Bearer ***")
                .doesNotContain(leakedToken);
    }

    private static class FakeUpdateService extends AppUpdateService {
        private int releaseStatus = 200;
        private String releaseBody = "";
        private String releaseError;
        private int tagsStatus = 200;
        private String tagsBody = "";
        private final AppVersionService versionService;

        private FakeUpdateService(AppConfig appConfig, AppVersionService versionService) {
            super(appConfig, versionService);
            this.versionService = versionService;
        }

        @Override
        protected ApiFetchResult executeGithubJson(String url) {
            ApiFetchResult result = new ApiFetchResult();
            result.setUrl(url);
            if (url.equals(versionService.releaseApiUrl())) {
                result.setStatusCode(releaseStatus);
                result.setBody(releaseBody);
                result.setErrorMessage(releaseError);
                return result;
            }
            if (url.equals(versionService.tagsApiUrl())) {
                result.setStatusCode(tagsStatus);
                result.setBody(tagsBody);
                return result;
            }
            result.setStatusCode(404);
            return result;
        }

        private void setReleaseStatus(int releaseStatus) {
            this.releaseStatus = releaseStatus;
        }

        private void setReleaseBody(String releaseBody) {
            this.releaseBody = releaseBody;
        }

        private void setReleaseError(String releaseError) {
            this.releaseStatus = -1;
            this.releaseError = releaseError;
        }

        private void setTagsBody(String tagsBody) {
            this.tagsBody = tagsBody;
        }

        private void exposeEnsureTrustedUpdateAssetUrl(String assetUrl) {
            ensureTrustedUpdateAssetUrl(assetUrl);
        }

        private ReleaseInfo exposeParseReleaseInfo(String body) {
            return parseReleaseInfo(body);
        }

        private String currentLastError() {
            try {
                java.lang.reflect.Field field = AppUpdateService.class.getDeclaredField("lastErrorMessage");
                field.setAccessible(true);
                return (String) field.get(this);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static class ExposedUpdateService extends AppUpdateService {
        private ExposedUpdateService(
                AppConfig appConfig,
                AppVersionService versionService,
                SecurityPolicyService securityPolicyService) {
            super(appConfig, versionService, securityPolicyService);
        }

        private ApiResultSnapshot exposeExecuteGithubJson(String url) {
            ApiFetchResult result = executeGithubJson(url);
            return new ApiResultSnapshot(result.getStatusCode(), result.getErrorMessage());
        }
    }

    private static class DownloadFailingUpdateService extends FakeUpdateService {
        private final String leakedToken;

        private DownloadFailingUpdateService(
                AppConfig appConfig, AppVersionService versionService, String leakedToken) {
            super(appConfig, versionService);
            this.leakedToken = leakedToken;
        }

        @Override
        protected void downloadAsset(String assetUrl, File target) {
            throw new IllegalStateException("download failed token=" + leakedToken);
        }
    }

    private static class ApiResultSnapshot {
        private final int statusCode;
        private final String errorMessage;

        private ApiResultSnapshot(int statusCode, String errorMessage) {
            this.statusCode = statusCode;
            this.errorMessage = errorMessage;
        }
    }

    private static class PolicyBlockingUpdateService extends AppUpdateService {
        private PolicyBlockingUpdateService(AppConfig appConfig, AppVersionService versionService) {
            super(appConfig, versionService);
        }

        @Override
        protected SecurityPolicyService updateAssetSecurityPolicy() {
            return new SecurityPolicyService(new AppConfig()) {
                @Override
                public UrlVerdict checkUrl(String url) {
                    return UrlVerdict.block(url, "blocked-by-test");
                }
            };
        }

        private void exposeDownloadAsset(String assetUrl, File target) {
            downloadAsset(assetUrl, target);
        }
    }

    private static class AllowLocalButBlockMetadataSecurityPolicyService
            extends SecurityPolicyService {
        private AllowLocalButBlockMetadataSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        public UrlVerdict checkUrl(String url) {
            if (url != null && url.contains("127.0.0.1")) {
                return UrlVerdict.allow();
            }
            return super.checkUrl(url);
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            if ("127.0.0.1".equals(host)) {
                return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            }
            return new InetAddress[] {InetAddress.getByName(host)};
        }
    }

    private static class FakeVersionService extends AppVersionService {
        private String deploymentMode = "dev";
        private String currentVersion = "0.0.1";
        private String currentTag = "v0.0.1";
        private String releaseRepo = "chengliang4810/solon-claw";
        private String updateProxyUrl;

        private FakeVersionService(AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        public String deploymentMode() {
            return deploymentMode;
        }

        @Override
        public String currentVersion() {
            return currentVersion;
        }

        @Override
        public String currentTag() {
            return currentTag;
        }

        @Override
        public String releaseRepo() {
            return releaseRepo;
        }

        @Override
        public String updateProxyUrl() {
            return updateProxyUrl;
        }

        @Override
        public boolean isWindows() {
            return false;
        }

        @Override
        public File currentJarFile() {
            if ("jar".equals(deploymentMode)) {
                return new File("target/fake-current.jar");
            }
            return null;
        }

        private void setDeploymentMode(String deploymentMode) {
            this.deploymentMode = deploymentMode;
        }

        private void setCurrentVersion(String currentVersion) {
            this.currentVersion = currentVersion;
        }

        private void setCurrentTag(String currentTag) {
            this.currentTag = currentTag;
        }

        private void setReleaseRepo(String releaseRepo) {
            this.releaseRepo = releaseRepo;
        }

        private void setUpdateProxyUrl(String updateProxyUrl) {
            this.updateProxyUrl = updateProxyUrl;
        }
    }
}
