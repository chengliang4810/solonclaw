package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class CheckpointRollbackTest {
    @Test
    void shouldRollbackLatestStructuredFileWrite() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-a:user-a";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);

        File file = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "sample.txt");
        FileUtil.writeUtf8String("v1", file);
        env.checkpointService.createCheckpoint(
                sourceKey, session.getSessionId(), Collections.singletonList(file));
        FileUtil.writeUtf8String("v2", file);

        env.checkpointService.rollbackLatest(sourceKey);
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v1");
    }

    @Test
    void shouldSkipExcludedCheckpointFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-a:user-a";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);

        File file = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "debug.log");
        FileUtil.writeUtf8String("v1", file);

        String checkpointId =
                env.checkpointService
                        .createCheckpoint(sourceKey, session.getSessionId(), Collections.singletonList(file))
                        .getCheckpointId();
        FileUtil.writeUtf8String("v2", file);

        env.checkpointService.rollback(checkpointId);
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v2");

        Map<String, Object> preview = env.checkpointService.preview(checkpointId);
        assertThat((List<?>) preview.get("files")).isEmpty();
        assertThat(String.valueOf(preview.get("skipped"))).contains("debug.log").contains("*.log");
    }

    @Test
    void shouldSkipOversizeCheckpointFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getRollback().setMaxFileSizeMb(1);
        String sourceKey = "MEMORY:room-a:user-a";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);

        File file = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "weights.bin");
        FileUtil.writeBytes(new byte[1024 * 1024 + 1], file);

        String checkpointId =
                env.checkpointService
                        .createCheckpoint(sourceKey, session.getSessionId(), Collections.singletonList(file))
                        .getCheckpointId();

        Map<String, Object> preview = env.checkpointService.preview(checkpointId);
        assertThat((List<?>) preview.get("files")).isEmpty();
        assertThat(String.valueOf(preview.get("skipped"))).contains("weights.bin").contains("too_large:1mb");
    }

    @Test
    void shouldRedactUnsafeCheckpointRollbackPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File runtimeHome = new File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
        File checkpointDir = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "checkpoints", "unsafe");
        File manifest = FileUtil.file(checkpointDir, "manifest.json");
        File outside =
                new File(
                        runtimeHome.getParentFile(),
                        "checkpoint-token=ghp_checkpointsecret-report.txt");
        String manifestJson =
                "{\"files\":[{\"path\":\""
                        + escapeJson(outside.getAbsolutePath())
                        + "\",\"exists\":false}],\"skipped\":[]}";
        FileUtil.writeUtf8String(manifestJson, manifest);
        insertCheckpoint(env, "unsafe-checkpoint", checkpointDir, manifest);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> env.checkpointService.rollback("unsafe-checkpoint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside allowed roots")
                .hasMessageNotContaining(runtimeHome.getParent())
                .hasMessageNotContaining("ghp_checkpointsecret");
    }

    @Test
    void shouldRedactUnsafeCheckpointSnapshotPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File runtimeHome = new File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
        File checkpointDir = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "checkpoints", "unsafe-snapshot");
        File target = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "restore-target.txt");
        File manifest = FileUtil.file(checkpointDir, "manifest.json");
        File outsideSnapshot =
                new File(
                        runtimeHome.getParentFile(),
                        "snapshot-token=ghp_snapshotsecret.bak");
        FileUtil.writeUtf8String("snapshot", outsideSnapshot);
        String manifestJson =
                "{\"files\":[{\"path\":\""
                        + escapeJson(target.getAbsolutePath())
                        + "\",\"exists\":true,\"snapshot\":\""
                        + escapeJson(outsideSnapshot.getAbsolutePath())
                        + "\"}],\"skipped\":[]}";
        FileUtil.writeUtf8String(manifestJson, manifest);
        insertCheckpoint(env, "unsafe-snapshot", checkpointDir, manifest);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> env.checkpointService.rollback("unsafe-snapshot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside checkpoint directory")
                .hasMessageNotContaining(runtimeHome.getParent())
                .hasMessageNotContaining("ghp_snapshotsecret");
    }

    private static void insertCheckpoint(
            TestEnvironment env, String checkpointId, File checkpointDir, File manifest)
            throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into checkpoints (checkpoint_id, source_key, session_id, checkpoint_dir, manifest_path, created_at, restored_at) values (?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, checkpointId);
            statement.setString(2, "MEMORY:room-a:user-a");
            statement.setString(3, "session-a");
            statement.setString(4, checkpointDir.getAbsolutePath());
            statement.setString(5, manifest.getAbsolutePath());
            statement.setLong(6, System.currentTimeMillis());
            statement.setLong(7, 0L);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
