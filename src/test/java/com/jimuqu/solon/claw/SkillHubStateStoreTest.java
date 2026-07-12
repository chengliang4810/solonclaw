package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class SkillHubStateStoreTest {
    /** 多个 Store 实例并发写入不同安装记录时不得互相覆盖。 */
    @Test
    void shouldKeepConcurrentInstallsFromSeparateStoreInstances() throws Exception {
        File skillsDir = Files.createTempDirectory("skillhub-state-concurrent").toFile();
        SkillHubStateStore first = new SkillHubStateStore(skillsDir);
        SkillHubStateStore second = new SkillHubStateStore(skillsDir);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstTask =
                    executor.submit(
                            () -> {
                                await(start);
                                for (int index = 0; index < 100; index++) {
                                    first.recordInstall(record("first-" + index));
                                }
                            });
            Future<?> secondTask =
                    executor.submit(
                            () -> {
                                await(start);
                                for (int index = 0; index < 100; index++) {
                                    second.recordInstall(record("second-" + index));
                                }
                            });
            start.countDown();
            firstTask.get(10, TimeUnit.SECONDS);
            secondTask.get(10, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(new SkillHubStateStore(skillsDir).listInstalled()).hasSize(200);
    }

    /** 损坏的 lock 状态必须按空状态读取，并在后续写入时恢复为有效 JSON。 */
    @Test
    void shouldRecoverFromCorruptedInstallLockOnNextWrite() throws Exception {
        File skillsDir = Files.createTempDirectory("skillhub-state-corrupted").toFile();
        FileUtil.mkdir(FileUtil.file(skillsDir, ".hub"));
        File lockFile = FileUtil.file(skillsDir, ".hub", "lock.json");
        FileUtil.writeUtf8String("{\"installed\":", lockFile);
        SkillHubStateStore stateStore = new SkillHubStateStore(skillsDir);

        assertThat(stateStore.listInstalled()).isEmpty();
        stateStore.recordInstall(record("recovered-skill"));

        assertThat(new SkillHubStateStore(skillsDir).listInstalled())
                .extracting(HubInstallRecord::getName)
                .containsExactly("recovered-skill");
        assertThat(FileUtil.readUtf8String(lockFile)).contains("\"version\":1");
    }

    /** 构造满足安装记录路径约束的测试记录。 */
    private static HubInstallRecord record(String name) {
        HubInstallRecord record = new HubInstallRecord();
        record.setName(name);
        record.setSource("test");
        record.setIdentifier(name);
        record.setTrustLevel("community");
        record.setScanVerdict("safe");
        record.setContentHash("sha256:" + name);
        record.setInstallPath(name);
        return record;
    }

    /** 等待并发测试统一起跑。 */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for shared Skill Hub state", e);
        }
    }
}
