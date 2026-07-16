package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteProfileTaskRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 跨 Profile 协作任务共享仓储和调度边界测试。 */
class ProfileTaskRepositoryTest {
    /** 临时共享数据库。 */
    private SqliteDatabase database;

    /** 被测仓储。 */
    private SqliteProfileTaskRepository repository;

    /** 创建隔离数据库。 */
    @BeforeEach
    void setUp() throws Exception {
        Path home = Files.createTempDirectory("profile-task-test");
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        database = new SqliteDatabase(config);
        repository = new SqliteProfileTaskRepository(database);
    }

    /** 关闭共享连接。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** default 不可接收，依赖完成前不可认领，完成后才提升为可执行。 */
    @Test
    void shouldEnforceTargetAndAndDependencies() throws Exception {
        assertThatThrownBy(() -> repository.save(task("bad", "default")))
                .isInstanceOf(IllegalArgumentException.class);

        ProfileTaskRecord first = task("first", "researcher");
        repository.save(first);
        ProfileTaskRecord second = task("second", "writer");
        second.setDependencyIds(Collections.singletonList("first"));
        repository.save(second);

        ProfileTaskRecord claimedFirst = repository.claimNext(5);
        assertThat(claimedFirst.getTaskId()).isEqualTo("first");
        assertThat(repository.claimNext(5)).isNull();
        assertThat(
                        repository.finishAttempt(
                                "first",
                                claimedFirst.getExecutionToken(),
                                "COMPLETED",
                                "facts",
                                null))
                .isTrue();
        assertThat(repository.claimNext(5).getTaskId()).isEqualTo("second");
    }

    /** 同一 Profile 串行、不同 Profile 并行，并拒绝已超时执行的迟到结果。 */
    @Test
    void shouldEnforceConcurrencyAndExecutionToken() throws Exception {
        repository.save(task("a", "researcher"));
        repository.save(task("b", "researcher"));
        repository.save(task("c", "writer"));

        ProfileTaskRecord a = repository.claimNext(5);
        ProfileTaskRecord c = repository.claimNext(5);
        assertThat(a.getTargetProfile()).isEqualTo("researcher");
        assertThat(c.getTargetProfile()).isEqualTo("writer");
        assertThat(repository.claimNext(5)).isNull();

        assertThat(repository.finishAttempt("a", "stale-token", "COMPLETED", "late", null))
                .isFalse();
        assertThat(
                        repository.finishAttempt(
                                "a", a.getExecutionToken(), "TIMED_OUT", null, "timeout"))
                .isTrue();
        assertThat(repository.retry("a", "补充上下文后重试")).isTrue();
        assertThat(repository.findById("a").getAttemptCount()).isEqualTo(1);
    }

    /** 前置任务重试成功后，按依赖链逐层恢复被阻塞任务。 */
    @Test
    void shouldRestoreBlockedDependentsAfterRetrySucceeds() throws Exception {
        repository.save(task("a", "researcher"));
        ProfileTaskRecord b = task("b", "writer");
        b.setDependencyIds(Collections.singletonList("a"));
        repository.save(b);
        ProfileTaskRecord c = task("c", "reviewer");
        c.setDependencyIds(Collections.singletonList("b"));
        repository.save(c);

        ProfileTaskRecord firstAttempt = repository.claimNext(5);
        repository.finishAttempt(
                "a", firstAttempt.getExecutionToken(), "FAILED", null, "missing context");
        assertThat(repository.findById("b").getStatus()).isEqualTo("BLOCKED");
        assertThat(repository.findById("c").getStatus()).isEqualTo("BLOCKED");

        assertThat(repository.retry("a", "complete context")).isTrue();
        ProfileTaskRecord retried = repository.claimNext(5);
        repository.finishAttempt("a", retried.getExecutionToken(), "COMPLETED", "facts", null);
        ProfileTaskRecord restoredB = repository.claimNext(5);
        assertThat(restoredB.getTaskId()).isEqualTo("b");
        assertThat(restoredB.getError()).isNull();

        repository.finishAttempt("b", restoredB.getExecutionToken(), "COMPLETED", "draft", null);
        assertThat(repository.claimNext(5).getTaskId()).isEqualTo("c");
    }

    /** 构造最小合法任务。 */
    private static ProfileTaskRecord task(String id, String target) {
        ProfileTaskRecord task = new ProfileTaskRecord();
        task.setTaskId(id);
        task.setSourceProfile("default");
        task.setTargetProfile(target);
        task.setSourceKey("MEMORY:room:user");
        task.setTitle(id);
        task.setPrompt("execute " + id);
        return task;
    }
}
