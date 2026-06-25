package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.RuntimePathGuard;
import com.jimuqu.solon.claw.web.DashboardMediaService;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DashboardMediaServiceTest {
    @Test
    void shouldRedactMediaReferenceFallbackPaths() throws Exception {
        File workspaceHome = java.nio.file.Files.createTempDirectory("jimuqu-media-runtime").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
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
                .doesNotContain(workspaceHome.getAbsolutePath())
                .doesNotContain(cached.getAbsolutePath())
                .doesNotContain("ghp_mediafallbacksecret");
        assertThat(download)
                .contains("media://unavailable/token=***")
                .doesNotContain(workspaceHome.getAbsolutePath())
                .doesNotContain(cached.getAbsolutePath())
                .doesNotContain("ghp_mediafallbacksecret");
        assertThat(reference)
                .contains("media://unavailable/token=***")
                .doesNotContain(workspaceHome.getAbsolutePath())
                .doesNotContain(cached.getAbsolutePath())
                .doesNotContain("ghp_mediafallbacksecret");
        assertThat(readStoredMediaMetadata(database, "media-fallback"))
                .contains("token=***")
                .doesNotContain("ghp_mediafallbacksecret");
    }

    @Test
    void shouldRedactMediaIndexMetadataBeforeStorage() throws Exception {
        File workspaceHome = java.nio.file.Files.createTempDirectory("jimuqu-media-storage").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        SqliteDatabase database = new SqliteDatabase(config);
        DashboardMediaService service =
                new DashboardMediaService(
                        database, new RuntimePathGuard(config), new AttachmentCacheService(config));
        File cached =
                new File(
                        new File(config.getRuntime().getCacheDir(), "media/MEMORY"),
                        "safe-media.txt");
        FileUtil.writeUtf8String("cached media", cached);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("mediaId", "media-storage-redaction");
        body.put("platform", "MEMORY");
        body.put("localPath", cached.getAbsolutePath());
        body.put("originalName", "report-token=ghp_mediaoriginal12345.txt");
        body.put("remoteId", "remote api_key=sk-media-remote-secret12345");

        service.indexLocal(body);

        String detail = String.valueOf(service.detail("media-storage-redaction"));
        String stored = readStoredMediaMetadata(database, "media-storage-redaction");
        assertThat(detail)
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain("ghp_mediaoriginal12345")
                .doesNotContain("sk-media-remote-secret12345");
        assertThat(stored)
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain("ghp_mediaoriginal12345")
                .doesNotContain("sk-media-remote-secret12345");
    }

    private String readStoredMediaMetadata(SqliteDatabase database, String mediaId)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select original_name, remote_id from channel_media where media_id = ?");
            statement.setString(1, mediaId);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (!resultSet.next()) {
                    return "";
                }
                return resultSet.getString("original_name")
                        + "\n"
                        + resultSet.getString("remote_id");
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }
}
