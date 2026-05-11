package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.RuntimePathGuard;
import com.jimuqu.solon.claw.web.DashboardMediaService;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DashboardMediaServiceTest {
    @Test
    void shouldRedactMediaReferenceFallbackPaths() throws Exception {
        File runtimeHome = java.nio.file.Files.createTempDirectory("jimuqu-media-runtime").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(runtimeHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(runtimeHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(runtimeHome, "cache").getAbsolutePath());
        config.getRuntime().setStateDb(new File(new File(runtimeHome, "data"), "state.db").getAbsolutePath());
        SqliteDatabase database = new SqliteDatabase(config);
        DashboardMediaService service =
                new DashboardMediaService(
                        database,
                        new RuntimePathGuard(config),
                        new AttachmentCacheService(config) {
                            @Override
                            public String mediaReference(File file) {
                                throw new IllegalArgumentException("forced fallback");
                            }
                        });
        File cached =
                new File(
                        new File(config.getRuntime().getCacheDir(), "media/MEMORY"),
                        "token=ghp_mediafallbacksecret.txt");
        FileUtil.writeUtf8String("cached media", cached);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("mediaId", "media-fallback");
        body.put("platform", "MEMORY");
        body.put("localPath", cached.getAbsolutePath());
        body.put("originalName", cached.getName());
        body.put("kind", "file");
        body.put("mimeType", "text/plain");

        service.indexLocal(body);

        String detail = String.valueOf(service.detail("media-fallback"));
        String download = String.valueOf(service.download("media-fallback"));
        String reference = String.valueOf(service.reference("media-fallback"));

        assertThat(detail)
                .contains("media://unavailable/token=***")
                .doesNotContain(runtimeHome.getAbsolutePath())
                .doesNotContain(cached.getAbsolutePath())
                .doesNotContain("ghp_mediafallbacksecret");
        assertThat(download)
                .contains("media://unavailable/token=***")
                .doesNotContain(runtimeHome.getAbsolutePath())
                .doesNotContain(cached.getAbsolutePath())
                .doesNotContain("ghp_mediafallbacksecret");
        assertThat(reference)
                .contains("media://unavailable/token=***")
                .doesNotContain(runtimeHome.getAbsolutePath())
                .doesNotContain(cached.getAbsolutePath())
                .doesNotContain("ghp_mediafallbacksecret");
    }
}
