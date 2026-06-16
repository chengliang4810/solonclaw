package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.proactive.collector.CronFollowupCollector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 定时任务跟进观测采集器测试，覆盖失败、投递错误、超期未运行与暂停原因等主动协作信号。 */
public class CronFollowupCollectorTest {
    /** 测试使用的固定当前时间，避免 due 和回看窗口判断随真实时间漂移。 */
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void shouldNotEmitForSilentOutputAlone() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = activeJob("job-silent");
        job.setLastStatus("ok");
        job.setLastOutput("[SILENT]");
        job.setLastRunAt(NOW - 1_000L);
        job.setNextRunAt(NOW + 60_000L);
        repository.jobs.add(job);
        repository.runs.put(
                "job-silent",
                Collections.singletonList(run("job-silent", "ok", "[SILENT]", null, null)));

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldEmitDeliveryErrorFollowup() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = activeJob("job-delivery");
        job.setLastStatus("ok");
        job.setLastDeliveryError("投递失败 token=secret-token-1234567890");
        job.setLastRunAt(NOW - 5_000L);
        job.setNextRunAt(NOW + 60_000L);
        repository.jobs.add(job);
        repository.runs.put(
                "job-delivery",
                Collections.singletonList(
                        run("job-delivery", "ok", "已完成", null, "Authorization: Bearer sk-test-secret")));

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        ProactiveObservation observation = observationOfType(observations, "cron_delivery_error");
        assertThat(observation.getCollector()).isEqualTo("cron_followup");
        assertThat(observation.getSourceKey()).isEqualTo("source-job-delivery");
        assertThat(observation.getStatus()).isEqualTo("COLLECTED");
        assertThat(observation.getPayload()).containsEntry("jobId", "job-delivery");
        assertThat(observation.getPayload()).containsEntry("lastStatus", "ok");
        Map<String, Object> evidence = evidence(observation);
        assertThat(String.valueOf(evidence.get("deliveryError"))).contains("token=***");
        assertThat(String.valueOf(evidence.get("deliveryError")))
                .doesNotContain("secret-token-1234567890");
        assertThat(String.valueOf(evidence.get("recentRuns")))
                .contains("Authorization: Bearer ***");
    }

    @Test
    void shouldEmitDeliveryErrorForCompletedJobWithoutDueNoise() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = job("job-completed-delivery", "COMPLETED");
        job.setLastStatus("ok");
        job.setLastDeliveryError("投递目标不可用");
        job.setLastRunAt(NOW - 5_000L);
        job.setNextRunAt(0L);
        repository.jobs.add(job);

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        assertThat(observations).extracting(item -> item.getPayload().get("type"))
                .containsExactly("cron_delivery_error");
    }

    @Test
    void shouldDetectRepeatedFailuresFromRecentRuns() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = activeJob("job-failing");
        job.setLastStatus("error");
        job.setLastError("脚本失败");
        job.setLastRunAt(NOW - 1_000L);
        job.setNextRunAt(NOW + 60_000L);
        repository.jobs.add(job);
        repository.runs.put(
                "job-failing",
                Arrays.asList(
                        run("job-failing", "error", "", "第一次失败", null),
                        run("job-failing", "error", "", "第二次失败", null),
                        run("job-failing", "ok", "曾经成功", null, null)));

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        ProactiveObservation observation =
                observationOfType(observations, "cron_repeated_failure");
        assertThat(observation.getPayload()).containsEntry("jobId", "job-failing");
        assertThat(evidence(observation)).containsEntry("lastError", "脚本失败");
        assertThat(evidence(observation)).containsEntry("failureCount", Integer.valueOf(2));
    }

    @Test
    void shouldNotEmitRepeatedFailureWhenLatestRunRecovered() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = activeJob("job-recovered");
        job.setLastStatus("ok");
        job.setLastRunAt(NOW - 1_000L);
        job.setNextRunAt(NOW + 60_000L);
        repository.jobs.add(job);
        repository.runs.put(
                "job-recovered",
                Arrays.asList(
                        run("job-recovered", "ok", "恢复成功", null, null),
                        run("job-recovered", "error", "", "旧失败一", null),
                        run("job-recovered", "error", "", "旧失败二", null)));

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldDetectDueButNotRunJobs() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = activeJob("job-overdue");
        job.setLastStatus("ok");
        job.setLastRunAt(NOW - 2L * 24L * 60L * 60L * 1000L);
        job.setNextRunAt(NOW - 60_000L);
        repository.jobs.add(job);

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        ProactiveObservation observation = observationOfType(observations, "cron_due_not_run");
        assertThat(observation.getPayload()).containsEntry("jobId", "job-overdue");
        assertThat(evidence(observation)).containsEntry("nextRunAt", Long.valueOf(NOW - 60_000L));
        assertThat(evidence(observation)).containsEntry("lastRunAt", Long.valueOf(job.getLastRunAt()));
    }

    @Test
    void shouldNotEmitDueNotRunWhenRecentRunProvesExecutionAfterDueTime() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = activeJob("job-stale-main-row");
        job.setLastStatus("ok");
        job.setLastRunAt(NOW - 2L * 24L * 60L * 60L * 1000L);
        job.setNextRunAt(NOW - 60_000L);
        repository.jobs.add(job);
        CronJobRunRecord run = run("job-stale-main-row", "ok", "已执行", null, null);
        run.setFinishedAt(NOW - 30_000L);
        repository.runs.put("job-stale-main-row", Collections.singletonList(run));

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldNotEmitDueNotRunWhenRunHistoryCannotBeRead() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        repository.throwOnListRuns = true;
        CronJobRecord job = activeJob("job-history-error");
        job.setLastStatus("ok");
        job.setLastRunAt(NOW - 2L * 24L * 60L * 60L * 1000L);
        job.setNextRunAt(NOW - 60_000L);
        repository.jobs.add(job);

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldDetectPausedJobsWithVisibleReasons() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = job("job-paused", "PAUSED");
        job.setPausedReason("等待上游修复 token=secret-token-1234567890");
        job.setPausedAt(NOW - 120_000L);
        job.setLastStatus("ok");
        repository.jobs.add(job);

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        ProactiveObservation observation = observationOfType(observations, "cron_paused_visible_reason");
        assertThat(observation.getPayload()).containsEntry("jobId", "job-paused");
        assertThat(observation.getPayload()).hasSizeLessThanOrEqualTo(8);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) observation.getPayload().get("evidence");
        assertThat(String.valueOf(evidence.get("pausedReason"))).contains("token=***");
        assertThat(String.valueOf(evidence.get("pausedReason")))
                .doesNotContain("secret-token-1234567890");
    }

    @Test
    void shouldEmitActionableOutputFollowup() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord job = activeJob("job-actionable");
        job.setLastStatus("ok");
        job.setLastOutput("检测到需要处理的审批事项，请确认是否继续。");
        job.setLastRunAt(NOW - 1_000L);
        job.setNextRunAt(NOW + 60_000L);
        repository.jobs.add(job);
        repository.runs.put(
                "job-actionable",
                Collections.singletonList(
                        run("job-actionable", "ok", "检测到需要处理的审批事项，请确认是否继续。", null, null)));

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        ProactiveObservation observation = observationOfType(observations, "cron_actionable_output");
        assertThat(observation.getPayload()).containsEntry("jobId", "job-actionable");
        assertThat(String.valueOf(evidence(observation).get("lastOutput"))).contains("需要处理");
    }

    @Test
    void shouldNotEmitForSuccessfulCompletedNormalJobs() throws Exception {
        InMemoryCronJobRepository repository = new InMemoryCronJobRepository();
        CronJobRecord active = activeJob("job-success");
        active.setLastStatus("ok");
        active.setLastOutput("任务执行完成，一切正常。");
        active.setLastRunAt(NOW - 1_000L);
        active.setNextRunAt(NOW + 60_000L);
        CronJobRecord completed = job("job-completed", "COMPLETED");
        completed.setLastStatus("ok");
        completed.setLastOutput("已完成");
        completed.setLastRunAt(NOW - 2_000L);
        repository.jobs.add(active);
        repository.jobs.add(completed);
        repository.runs.put(
                "job-success",
                Collections.singletonList(run("job-success", "ok", "任务执行完成，一切正常。", null, null)));

        List<ProactiveObservation> observations =
                new CronFollowupCollector(repository).collect(context(14));

        assertThat(observations).isEmpty();
    }

    /** 从观测列表中查找指定类型，缺失时让断言输出包含实际类型，便于定位。 */
    private static ProactiveObservation observationOfType(
            List<ProactiveObservation> observations, String type) {
        for (ProactiveObservation observation : observations) {
            if (type.equals(observation.getPayload().get("type"))) {
                return observation;
            }
        }
        assertThat(observations).extracting(item -> item.getPayload().get("type")).contains(type);
        return null;
    }

    /** 读取观测中的 evidence 子载荷，便于验证关键规则证据不会被顶层 payload 数量裁剪丢失。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(ProactiveObservation observation) {
        Object value = observation.getPayload().get("evidence");
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** 构造启用主动协作和定时任务回看窗口的测试 tick 上下文。 */
    private static ProactiveTickContext context(int lookbackDays) {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(true);
        config.getProactive().setCronLookbackDays(lookbackDays);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setConfig(config);
        context.setNowMillis(NOW);
        context.setTickId("tick-cron-followup");
        return context;
    }

    /** 构造默认启用的定时任务记录。 */
    private static CronJobRecord activeJob(String jobId) {
        return job(jobId, "ACTIVE");
    }

    /** 构造测试定时任务记录，保留采集器需要读取的关键字段。 */
    private static CronJobRecord job(String jobId, String status) {
        CronJobRecord job = new CronJobRecord();
        job.setJobId(jobId);
        job.setName("任务 " + jobId);
        job.setSourceKey("source-" + jobId);
        job.setStatus(status);
        job.setNextRunAt(NOW + 60_000L);
        job.setCreatedAt(NOW - 120_000L);
        job.setUpdatedAt(NOW - 1_000L);
        return job;
    }

    /** 构造测试执行历史记录，便于验证 recentRuns 证据载荷。 */
    private static CronJobRunRecord run(
            String jobId, String status, String output, String error, String deliveryError) {
        CronJobRunRecord run = new CronJobRunRecord();
        run.setRunId("run-" + jobId + "-" + status + "-" + System.nanoTime());
        run.setJobId(jobId);
        run.setSourceKey("source-" + jobId);
        run.setTriggerType("scheduled");
        run.setStartedAt(NOW - 10_000L);
        run.setFinishedAt(NOW - 5_000L);
        run.setStatus(status);
        run.setOutput(output);
        run.setSummary(output);
        run.setError(error);
        run.setDeliveryError(deliveryError);
        return run;
    }

    /** 内存定时任务仓储，仅实现采集器测试会访问的读取方法。 */
    private static final class InMemoryCronJobRepository implements CronJobRepository {
        /** 测试预置的任务列表。 */
        private final List<CronJobRecord> jobs = new ArrayList<CronJobRecord>();

        /** 按任务 ID 保存的执行历史，列表顺序模拟仓储最新优先返回。 */
        private final Map<String, List<CronJobRunRecord>> runs =
                new LinkedHashMap<String, List<CronJobRunRecord>>();

        /** 是否模拟执行历史读取异常，用于验证采集器不把未知历史误判为未运行。 */
        private boolean throwOnListRuns;

        @Override
        public CronJobRecord save(CronJobRecord job) {
            throw new UnsupportedOperationException("测试仓储不支持写入任务");
        }

        @Override
        public CronJobRecord findById(String jobId) {
            throw new UnsupportedOperationException("测试仓储不支持按 ID 查询");
        }

        @Override
        public List<CronJobRecord> listBySource(String sourceKey) {
            throw new UnsupportedOperationException("测试仓储不支持按来源查询");
        }

        @Override
        public List<CronJobRecord> listAll() {
            return jobs;
        }

        @Override
        public List<CronJobRecord> listDue(long nowEpochMillis) {
            throw new UnsupportedOperationException("测试仓储不依赖 listDue");
        }

        @Override
        public void delete(String jobId) {
            throw new UnsupportedOperationException("测试仓储不支持删除");
        }

        @Override
        public void updateStatus(String jobId, String status) {
            throw new UnsupportedOperationException("测试仓储不支持状态更新");
        }

        @Override
        public CronJobRecord update(CronJobRecord job) {
            throw new UnsupportedOperationException("测试仓储不支持更新");
        }

        @Override
        public void markRun(String jobId, long lastRunAt, long nextRunAt) {
            throw new UnsupportedOperationException("测试仓储不支持运行标记");
        }

        @Override
        public void markRunResult(
                String jobId,
                long lastRunAt,
                long nextRunAt,
                String status,
                String error,
                String output,
                int repeatCompleted,
                String nextStatus) {
            throw new UnsupportedOperationException("测试仓储不支持运行结果更新");
        }

        @Override
        public void markDeliveryError(String jobId, String error) {
            throw new UnsupportedOperationException("测试仓储不支持投递错误更新");
        }

        @Override
        public CronJobRunRecord saveRun(CronJobRunRecord run) {
            throw new UnsupportedOperationException("测试仓储不支持写入执行历史");
        }

        @Override
        public List<CronJobRunRecord> listRuns(String jobId, int limit) {
        List<CronJobRunRecord> values = runs.get(jobId);
            if (throwOnListRuns) {
                throw new IllegalStateException("执行历史读取失败");
            }
            if (values == null) {
                return Collections.emptyList();
            }
            if (values.size() <= limit) {
                return values;
            }
            return new ArrayList<CronJobRunRecord>(values.subList(0, limit));
        }
    }
}
