package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.storage.repository.SqliteCronJobRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SQLite 定时任务仓储并发状态保护测试。 */
class SqliteCronJobRepositoryTest {
    /** 临时 SQLite 数据库。 */
    private SqliteDatabase database;

    /** 被测定时任务仓储。 */
    private SqliteCronJobRepository repository;

    /** 创建隔离的定时任务数据库。 */
    @BeforeEach
    void setUp() throws Exception {
        Path home = Files.createTempDirectory("cron-job-repository-test");
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        database = new SqliteDatabase(config);
        repository = new SqliteCronJobRepository(database);
    }

    /** 关闭临时数据库持有的共享连接。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 运行结果回写不得覆盖执行期间由用户设置的暂停状态。 */
    @Test
    void shouldPreservePausedStatusWhenRunResultArrives() throws Exception {
        for (String nextStatus : new String[] {"ACTIVE", "COMPLETED"}) {
            CronJobRecord job = job("paused-" + nextStatus.toLowerCase());
            repository.save(job);
            repository.updateStatus(job.getJobId(), "PAUSED");

            repository.markRunResult(
                    job.getJobId(), 100L, 200L, "success", null, "done", 3, nextStatus);

            CronJobRecord stored = repository.findById(job.getJobId());
            assertThat(stored.getStatus()).isEqualTo("PAUSED");
            assertThat(stored.getLastRunAt()).isEqualTo(100L);
            assertThat(stored.getNextRunAt()).isEqualTo(200L);
            assertThat(stored.getLastStatus()).isEqualTo("success");
            assertThat(stored.getLastOutput()).isEqualTo("done");
            assertThat(stored.getRepeatCompleted()).isEqualTo(3);
        }
    }

    /** 未暂停任务仍应按照运行结果进入完成状态。 */
    @Test
    void shouldApplyRunResultStatusWhenJobIsNotPaused() throws Exception {
        CronJobRecord job = job("active");
        repository.save(job);

        repository.markRunResult(job.getJobId(), 100L, 0L, "success", null, "done", 1, "COMPLETED");

        assertThat(repository.findById(job.getJobId()).getStatus()).isEqualTo("COMPLETED");
    }

    /** 创建满足仓储持久化约束的定时任务记录。 */
    private CronJobRecord job(String jobId) {
        CronJobRecord job = new CronJobRecord();
        job.setJobId(jobId);
        job.setName(jobId);
        job.setCronExpr("0 * * * *");
        job.setPrompt("test");
        job.setSourceKey("MEMORY:test:user");
        job.setStatus("ACTIVE");
        job.setCreatedAt(1L);
        job.setUpdatedAt(1L);
        return job;
    }
}
