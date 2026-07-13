package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.storage.repository.SqliteCronJobRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.web.DashboardLogsService;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardLogsServiceTest {
    @Test
    void shouldFilterDashboardLogsByProactiveComponent() throws Exception {
        File workspaceHome =
                Files.createTempDirectory("solonclaw-logs-proactive-component").toFile();
        try {
            AppConfig config = new AppConfig();
            config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
            FileUtil.mkdir(config.getRuntime().getLogsDir());
            File agentLog = FileUtil.file(config.getRuntime().getLogsDir(), "agent.log");
            FileUtil.appendUtf8String(
                    "2026-06-27 10:00:00.000 INFO [main] "
                            + "com.jimuqu.solon.claw.proactive.ProactiveReminderScheduler - proactive-hit\n"
                            + "2026-06-27 10:00:01.000 INFO [main] "
                            + "com.jimuqu.solon.claw.web.DashboardLogsService - web-miss\n",
                    agentLog);

            DashboardLogsService service = new DashboardLogsService(config);
            List<String> lines = service.read("agent", 20, null, "proactive");

            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains("proactive-hit");
        } finally {
            FileUtil.del(workspaceHome);
        }
    }

    @Test
    void shouldIncludeCronRunIndexMatchesInDashboardLogQuery() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-logs-cron-index").toFile();
        SqliteDatabase database = null;
        try {
            AppConfig config = new AppConfig();
            config.getRuntime().setHome(workspaceHome.getAbsolutePath());
            config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
            config.getRuntime()
                    .setStateDb(
                            new File(new File(workspaceHome, "data"), "state.db")
                                    .getAbsolutePath());
            FileUtil.mkdir(config.getRuntime().getLogsDir());

            database = new SqliteDatabase(config);
            SqliteCronJobRepository cronRepository = new SqliteCronJobRepository(database);
            long now = System.currentTimeMillis();
            String marker = "web-loop-log-index-cron-20260614";

            CronJobRecord job = new CronJobRecord();
            job.setJobId("cron-log-index-job-20260614");
            job.setName("Cron log index " + marker);
            job.setCronExpr("1m");
            job.setPrompt("验证定时任务日志索引 " + marker + " api_key=sk-cronjob-secret12345");
            job.setSourceKey("MEMORY:dashboard:cron");
            job.setDeliverPlatform("local");
            job.setScript(marker + ".py");
            job.setNoAgent(true);
            job.setRepeatTimes(1);
            job.setRepeatCompleted(1);
            job.setWrapResponse(false);
            job.setLastStatus("ok");
            job.setLastOutput(marker + " local delivery ok token=ghp_cronjobsecret12345");
            job.setStatus("COMPLETED");
            job.setCreatedAt(now);
            job.setUpdatedAt(now);
            job.setLastRunAt(now);
            cronRepository.save(job);

            CronJobRunRecord run = new CronJobRunRecord();
            run.setRunId("cron-log-index-run-20260614");
            run.setJobId(job.getJobId());
            run.setSourceKey(job.getSourceKey());
            run.setTriggerType("scheduled");
            run.setAttempt(1);
            run.setStartedAt(now);
            run.setFinishedAt(now);
            run.setStatus("ok");
            run.setOutput(marker + " local delivery ok api_key=sk-cronrun-secret12345");
            run.setDeliveryResultJson(
                    "{\"skipped\":\"local\",\"token\":\"ghp_cronrunsecret12345\"}");
            run.setSummary("ok: " + marker + " local delivery ok");
            cronRepository.saveRun(run);

            DashboardLogsService service = new DashboardLogsService(config, null, cronRepository);
            List<String> lines = service.read("agent", 20, null, null, marker);

            assertThat(lines)
                    .anySatisfy(line -> assertThat(line).contains("cron-index:job"))
                    .anySatisfy(line -> assertThat(line).contains("cron-index:run"))
                    .anySatisfy(line -> assertThat(line).contains("skipped"))
                    .anySatisfy(line -> assertThat(line).contains(marker));
            assertThat(String.join("\n", lines))
                    .contains("api_key=***")
                    .contains("token=***")
                    .doesNotContain("sk-cronjob-secret12345")
                    .doesNotContain("ghp_cronjobsecret12345")
                    .doesNotContain("sk-cronrun-secret12345")
                    .doesNotContain("ghp_cronrunsecret12345");
        } finally {
            if (database != null) {
                database.shutdown();
            }
            FileUtil.del(workspaceHome);
        }
    }
}
