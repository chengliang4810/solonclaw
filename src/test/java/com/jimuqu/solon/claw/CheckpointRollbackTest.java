package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.File;
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
}
