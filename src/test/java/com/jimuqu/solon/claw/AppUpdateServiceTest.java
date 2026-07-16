package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.DigestUtil;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import java.io.File;

public class AppUpdateServiceTest {
    /** Snack4 序列化必须保留从发布资产基类继承的字段。 */
    @Test
    void shouldSerializeInheritedReleaseAssetFields() {
        AppUpdateService.VersionStatus status = new AppUpdateService.VersionStatus();
        status.setPublishedAt("2026-07-16T00:00:00Z");
        status.setJarAssetUrl("https://example.test/solonclaw.jar");
        status.setJarAssetName("solonclaw.jar");
        status.setSha256AssetUrl("https://example.test/SHA256SUMS");

        ONode serialized = ONode.ofJson(ONode.serialize(status));

        assertThat(serialized.get("publishedAt").getString()).isEqualTo("2026-07-16T00:00:00Z");
        assertThat(serialized.get("jarAssetUrl").getString())
                .isEqualTo("https://example.test/solonclaw.jar");
        assertThat(serialized.get("jarAssetName").getString()).isEqualTo("solonclaw.jar");
        assertThat(serialized.get("sha256AssetUrl").getString())
                .isEqualTo("https://example.test/SHA256SUMS");
    }

    @Test
    void shouldFallbackToTagsWhenReleaseApiReturns404() {
        AppConfig config = new AppConfig();
        // 提供工作区目录，避免写更新缓存时 workspaceHome() 因 home 为 null 抛 NPE（对齐生产路径）
        config.getRuntime().setHome(new File("target/update-test-runtime").getAbsolutePath());
        FakeVersionService versionService = new FakeVersionService(config);
        versionService.setDeploymentMode("docker");
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        versionService.setReleaseRepo("chengliang4810/solonclaw");

        FakeUpdateService service = new FakeUpdateService(config, versionService);
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
        versionService.setReleaseRepo("chengliang4810/solonclaw");

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
    void shouldRequireReleaseAssetsForDockerUpdate() {
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        versionService.setDeploymentMode("docker");
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        versionService.setReleaseRepo("chengliang4810/solonclaw");

        FakeUpdateService service = new FakeUpdateService(new AppConfig(), versionService);
        service.setReleaseStatus(404);
        service.setTagsBody("[{\"name\":\"v0.0.2\"}]");

        AppUpdateService.UpdateResult result = service.startUpdate();

        assertThat(result.isError()).isTrue();
        assertThat(result.getMessage()).contains("jar 资产");
    }

    /** systemd 与 Docker 共用的替换逻辑必须保留旧 JAR 并切换到新 JAR。 */
    @Test
    void shouldReplaceCurrentJarAndKeepBackup() throws Exception {
        AppConfig config = new AppConfig();
        File dir = new File("target/update-atomic-replace-test");
        FileUtil.del(dir);
        FileUtil.mkdir(dir);
        File current = new File(dir, "solonclaw.jar");
        File downloaded = new File(dir, "downloaded.jar");
        FileUtil.writeUtf8String("old", current);
        FileUtil.writeUtf8String("new", downloaded);
        FakeUpdateService service =
                new FakeUpdateService(config, new FakeVersionService(config));

        service.exposeReplaceCurrentJar(current, downloaded);

        assertThat(FileUtil.readUtf8String(current)).isEqualTo("new");
        assertThat(FileUtil.readUtf8String(new File(dir, "solonclaw.jar.previous")))
                .isEqualTo("old");
        assertThat(downloaded).doesNotExist();
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
                                        "https://github.com/chengliang4810/solonclaw/releases/download/v1/app.jar",
                                        new File("target/update-test/app.jar")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attachment download URL blocked")
                .hasMessageContaining("blocked-by-test");
    }

    @Test
    void shouldRedactUpdateParseAndProxyErrors() {
        String leakedToken = "sk-updateparse12345";
        FakeVersionService versionService = new FakeVersionService(new AppConfig());
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        FakeUpdateService parseService = new FakeUpdateService(new AppConfig(), versionService);

        parseService.exposeParseReleaseInfo(
                "{\"tag_name\":\"v0.0.2\",\"broken\":\"api_key=" + leakedToken);

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
        ((FakeUpdateService) downloadService)
                .setReleaseBody(
                        "{\"tag_name\":\"v0.0.2\",\"assets\":[{\"name\":\"solonclaw.jar\",\"browser_download_url\":\"https://github.com/chengliang4810/solonclaw/releases/download/v0.0.2/app.jar\"},{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"https://github.com/chengliang4810/solonclaw/releases/download/v0.0.2/SHA256SUMS\"}]}");

        AppUpdateService.UpdateResult downloadResult = downloadService.startUpdate();

        assertThat(downloadResult.isError()).isTrue();
        assertThat(downloadResult.getMessage()).contains("***").doesNotContain(downloadToken);
    }

    @Test
    void shouldRecognizePublishedJarAndChecksumAssets() {
        AppConfig config = new AppConfig();
        config.getRuntime()
                .setHome(new File("target/update-release-assets-test").getAbsolutePath());
        FakeUpdateService service = new FakeUpdateService(config, new FakeVersionService(config));

        AppUpdateService.VersionStatus status =
                service.statusForRelease(
                        "{\"tag_name\":\"v0.0.2\",\"assets\":[{\"name\":\"solonclaw-0.0.2.jar\",\"browser_download_url\":\"https://github.com/example/ignored.jar\"},{\"name\":\"solonclaw.jar\",\"browser_download_url\":\"https://github.com/example/solonclaw.jar\"},{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"https://github.com/example/SHA256SUMS\"}]}");

        assertThat(status.getJarAssetName()).isEqualTo("solonclaw.jar");
        assertThat(status.getJarAssetUrl()).endsWith("/solonclaw.jar");
        assertThat(status.getSha256AssetUrl()).endsWith("/SHA256SUMS");
    }

    @Test
    void shouldRefuseJarUpdateWithoutChecksumAsset() {
        AppConfig config = new AppConfig();
        config.getRuntime()
                .setHome(new File("target/update-missing-checksum-test").getAbsolutePath());
        FakeVersionService versionService = new FakeVersionService(config);
        versionService.setDeploymentMode("jar");
        versionService.setCurrentVersion("0.0.1");
        versionService.setCurrentTag("v0.0.1");
        FakeUpdateService service = new FakeUpdateService(config, versionService);
        service.setReleaseBody(
                "{\"tag_name\":\"v0.0.2\",\"assets\":[{\"name\":\"solonclaw.jar\",\"browser_download_url\":\"https://github.com/example/solonclaw.jar\"}]}");

        AppUpdateService.UpdateResult result = service.startUpdate();

        assertThat(result.isError()).isTrue();
        assertThat(result.getMessage()).contains("SHA256SUMS").contains("拒绝");
    }

    @Test
    void shouldVerifyChecksumAndRejectMismatchedJar() throws Exception {
        AppConfig config = new AppConfig();
        File workDir = new File("target/update-checksum-test");
        FileUtil.mkdir(workDir);
        FakeVersionService versionService = new FakeVersionService(config);
        FakeUpdateService service = new FakeUpdateService(config, versionService);
        File jar = new File(workDir, "solonclaw.jar");
        File sums = new File(workDir, "SHA256SUMS");
        FileUtil.writeUtf8String("release jar", jar);
        FileUtil.writeUtf8String(DigestUtil.sha256Hex(jar) + "  solonclaw.jar\n", sums);

        service.exposeVerifyDownloadedJar(jar, sums, "solonclaw.jar");
        FileUtil.writeUtf8String(
                DigestUtil.sha256Hex("incorrect checksum") + "  solonclaw.jar\n", sums);

        assertThatThrownBy(() -> service.exposeVerifyDownloadedJar(jar, sums, "solonclaw.jar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256 校验失败");
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

    @Test
    void shouldRedactUpdateResultErrorMessagesAtBoundary() {
        String leakedToken = "ghp_updateresult12345";

        AppUpdateService.UpdateResult result =
                AppUpdateService.UpdateResult.error(
                        "update failed Authorization: Bearer " + leakedToken);

        assertThat(result.isError()).isTrue();
        assertThat(result.getMessage()).contains("Bearer ***").doesNotContain(leakedToken);
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
                java.lang.reflect.Field field =
                        AppUpdateService.class.getDeclaredField("lastErrorMessage");
                field.setAccessible(true);
                return (String) field.get(this);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private AppUpdateService.VersionStatus statusForRelease(String releaseBody) {
            setReleaseBody(releaseBody);
            return getVersionStatus(true);
        }

        private void exposeVerifyDownloadedJar(File jar, File sums, String jarAssetName) {
            verifyDownloadedJar(jar, sums, jarAssetName);
        }

        private void exposeReplaceCurrentJar(File currentJar, File downloadedJar) throws Exception {
            replaceCurrentJar(currentJar, downloadedJar);
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

                @Override
                public UrlVerdict checkUrlBlockingPrivate(String url) {
                    return UrlVerdict.block(url, "blocked-by-test");
                }
            };
        }

        private void exposeDownloadAsset(String assetUrl, File target) {
            downloadAsset(assetUrl, target);
        }
    }

    private static class FakeVersionService extends AppVersionService {
        private String deploymentMode = "dev";
        private String currentVersion = "0.0.1";
        private String currentTag = "v0.0.1";
        private String releaseRepo = "chengliang4810/solonclaw";
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
            if ("jar".equals(deploymentMode) || "docker".equals(deploymentMode)) {
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
