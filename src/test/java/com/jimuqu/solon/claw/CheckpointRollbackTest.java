package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

public class CheckpointRollbackTest {
    /** 完整 rollback 同步裁剪最后一轮，并保留可恢复调用前文件状态的反向 checkpoint。 */
    @Test
    void shouldRollbackSessionHistoryAndCreateReverseCheckpoint() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:rollback-session:user";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);
        session.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Arrays.asList(
                                ChatMessage.ofUser("change file"), new AssistantMessage("done"))));
        env.sessionRepository.save(session);
        File file = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "session.txt");
        FileUtil.writeUtf8String("v1", file);
        String checkpointId =
                env.checkpointService
                        .createCheckpoint(
                                sourceKey, session.getSessionId(), Collections.singletonList(file))
                        .getCheckpointId();
        FileUtil.writeUtf8String("v2", file);

        int removed =
                env.checkpointService.rollbackSession(checkpointId, session, env.sessionRepository);

        assertThat(removed).isEqualTo(2);
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v1");
        assertThat(
                        MessageSupport.countMessages(
                                env.sessionRepository.findById(session.getSessionId()).getNdjson()))
                .isZero();
        env.checkpointService.rollbackLatest(sourceKey);
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v2");
    }

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
                        .createCheckpoint(
                                sourceKey, session.getSessionId(), Collections.singletonList(file))
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
                        .createCheckpoint(
                                sourceKey, session.getSessionId(), Collections.singletonList(file))
                        .getCheckpointId();

        Map<String, Object> preview = env.checkpointService.preview(checkpointId);
        assertThat((List<?>) preview.get("files")).isEmpty();
        assertThat(String.valueOf(preview.get("skipped")))
                .contains("weights.bin")
                .contains("too_large:1mb");
    }

    @Test
    void shouldRedactCheckpointPreviewPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-a:user-a";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);

        File file =
                FileUtil.file(
                        env.appConfig.getRuntime().getCacheDir(),
                        "token=ghp_checkpointpreviewsecret.txt");
        FileUtil.writeUtf8String("v1", file);
        String checkpointId =
                env.checkpointService
                        .createCheckpoint(
                                sourceKey, session.getSessionId(), Collections.singletonList(file))
                        .getCheckpointId();

        Map<String, Object> preview = env.checkpointService.preview(checkpointId);
        String previewText = String.valueOf(preview);

        assertThat(previewText)
                .contains("file://token=***")
                .contains("checkpoint://" + checkpointId + "/snapshots/file-0.bak")
                .doesNotContain(env.appConfig.getRuntime().getHome())
                .doesNotContain(env.appConfig.getRuntime().getCacheDir())
                .doesNotContain(file.getAbsolutePath())
                .doesNotContain("ghp_checkpointpreviewsecret");
    }

    @Test
    void shouldRedactCheckpointPreviewIdentifiers() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File checkpointDir =
                FileUtil.file(
                        env.appConfig.getRuntime().getCacheDir(), "checkpoints", "identifiers");
        File manifest = FileUtil.file(checkpointDir, "manifest.json");
        FileUtil.writeUtf8String("{\"files\":[],\"skipped\":[]}", manifest);
        insertCheckpoint(
                env,
                "identifier-checkpoint",
                "MEMORY:room-a:ghp_checkpointsourcepreviewsecret",
                "session-ghp_checkpointsessionpreviewsecret",
                checkpointDir,
                manifest);

        Map<String, Object> preview = env.checkpointService.preview("identifier-checkpoint");
        String previewText = String.valueOf(preview);

        assertThat(previewText)
                .contains("source_key=MEMORY:room-a:***")
                .contains("session_id=session-ghp_***")
                .doesNotContain("ghp_checkpointsourcepreviewsecret")
                .doesNotContain("ghp_checkpointsessionpreviewsecret");
    }

    @Test
    void shouldRedactMissingCheckpointIds() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> env.checkpointService.rollback("missing-ghp_checkpointmissingsecret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未找到 checkpoint")
                .hasMessageNotContaining("ghp_checkpointmissingsecret");
    }

    @Test
    void shouldRedactUnsafeCheckpointRollbackPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File workspaceHome = new File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
        File checkpointDir =
                FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "checkpoints", "unsafe");
        File manifest = FileUtil.file(checkpointDir, "manifest.json");
        File outside =
                new File(
                        workspaceHome.getParentFile(),
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
                .hasMessageNotContaining(workspaceHome.getParent())
                .hasMessageNotContaining("ghp_checkpointsecret");
    }

    @Test
    void shouldRedactUnsafeCheckpointSnapshotPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File workspaceHome = new File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
        File checkpointDir =
                FileUtil.file(
                        env.appConfig.getRuntime().getCacheDir(), "checkpoints", "unsafe-snapshot");
        File target = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "restore-target.txt");
        File manifest = FileUtil.file(checkpointDir, "manifest.json");
        File outsideSnapshot =
                new File(workspaceHome.getParentFile(), "snapshot-token=ghp_snapshotsecret.bak");
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
                .hasMessageNotContaining(workspaceHome.getParent())
                .hasMessageNotContaining("ghp_snapshotsecret");
    }

    private static void insertCheckpoint(
            TestEnvironment env, String checkpointId, File checkpointDir, File manifest)
            throws Exception {
        insertCheckpoint(
                env, checkpointId, "MEMORY:room-a:user-a", "session-a", checkpointDir, manifest);
    }

    private static void insertCheckpoint(
            TestEnvironment env,
            String checkpointId,
            String sourceKey,
            String sessionId,
            File checkpointDir,
            File manifest)
            throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into checkpoints (checkpoint_id, source_key, session_id, checkpoint_dir, manifest_path, created_at, restored_at) values (?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, checkpointId);
            statement.setString(2, sourceKey);
            statement.setString(3, sessionId);
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
