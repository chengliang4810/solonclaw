package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.CronSchedulerTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.CronjobTools;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;

public class CronjobToolsSchedulerTest {
    @Test
    void shouldMatchJimuquCronjobToolIncludeDisabledAndWrapResponse() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig
                .getProviders()
                .get("default")
                .setModels(
                        java.util.Arrays.asList(
                                "gpt-5.4",
                                "gpt-5.2",
                                "gpt-5.3",
                                "claude-sonnet-4",
                                "tool-object-model",
                                "tool-json-model"));
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-room:user");

        String createdJson =
                tools.cronjob(
                        "add",
                        null,
                        "tool-job",
                        "30m",
                        "tool prompt",
                        "local",
                        null,
                        null,
                        null,
                        null,
                        Boolean.FALSE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        Map<?, ?> created = (Map<?, ?>) ONode.ofJson(createdJson).toData();
        String jobId = String.valueOf(created.get("job_id"));
        assertThat(created.get("message")).isEqualTo("定时任务 'tool-job' 已创建。");
        assertThat(created.get("schedule")).isEqualTo("30m");
        assertThat(created.get("repeat")).isEqualTo("forever");
        assertThat(created.get("wrap_response")).isEqualTo(Boolean.FALSE);
        assertThat(created.get("no_agent")).isEqualTo(Boolean.FALSE);
        assertThat(created.get("next_run_at")).isInstanceOf(Number.class);
        assertThat(String.valueOf(created.get("next_run_at_iso"))).contains("T");
        assertThat(String.valueOf(created.get("next_run_at_iso")))
                .matches(".*(?:Z|[+-]\\d{2}:\\d{2})$");
        assertThat(((Map<?, ?>) created.get("job")).get("wrap_response")).isEqualTo(Boolean.FALSE);
        assertThat(((Map<?, ?>) created.get("job")).get("no_agent")).isEqualTo(Boolean.FALSE);
        assertThat(((Map<?, ?>) created.get("job")).get("schedule")).isEqualTo("30m");
        assertThat(((Map<?, ?>) created.get("job")).get("next_run_at")).isInstanceOf(Number.class);
        assertThat(String.valueOf(((Map<?, ?>) created.get("job")).get("next_run_at_iso")))
                .isEqualTo(String.valueOf(created.get("next_run_at_iso")));

        String duplicateJson =
                tools.cronjob(
                        "create",
                        null,
                        "tool-job",
                        "30m",
                        "tool prompt",
                        "local",
                        null,
                        null,
                        null,
                        null,
                        Boolean.FALSE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        Map<?, ?> duplicate = (Map<?, ?>) ONode.ofJson(duplicateJson).toData();
        assertThat(duplicate.get("job_id")).isEqualTo(jobId);
        assertThat(duplicate.get("deduped")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(duplicate.get("next_run_at_iso")))
                .isEqualTo(String.valueOf(created.get("next_run_at_iso")));
        assertThat(env.cronJobRepository.listBySource("MEMORY:tool-room:user")).hasSize(1);

        Map<?, ?> deliveryPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-delivery-target",
                                                "30m",
                                                "delivery prompt",
                                                "FEISHU",
                                                "chat-tool",
                                                "thread-tool",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        String deliveryJobId = String.valueOf(deliveryPayload.get("job_id"));
        Map<?, ?> deliveryJob = (Map<?, ?>) deliveryPayload.get("job");
        assertThat(deliveryJob.get("deliver")).isEqualTo("FEISHU");
        assertThat(deliveryJob.get("deliver_chat_id")).isEqualTo("chat-tool");
        assertThat(deliveryJob.get("deliver_thread_id")).isEqualTo("thread-tool");

        Map<String, Object> localMode = new LinkedHashMap<String, Object>();
        localMode.put("mode", "local");
        Map<?, ?> localModePayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-local-mode-delivery",
                                                "30m",
                                                "local mode prompt",
                                                localMode,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        String localModeJobId = String.valueOf(localModePayload.get("job_id"));
        assertThat(localModePayload.get("deliver")).isEqualTo("local");
        assertThat(((Map<?, ?>) localModePayload.get("job")).get("deliver")).isEqualTo("local");
        assertThat(env.cronJobRepository.findById(localModeJobId).getDeliverPlatform())
                .isEqualTo("local");

        Map<?, ?> clearedDeliveryPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "update",
                                                deliveryJobId,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "",
                                                "",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        Map<?, ?> clearedDeliveryJob = (Map<?, ?>) clearedDeliveryPayload.get("job");
        assertThat(clearedDeliveryJob.get("deliver_chat_id")).isNull();
        assertThat(clearedDeliveryJob.get("deliver_thread_id")).isNull();
        tools.cronjob(
                "remove",
                deliveryJobId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        tools.cronjob(
                "remove",
                localModeJobId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        CronJobRecord secretJob = job("job-tool-secret", "MEMORY:tool-room:user");
        secretJob.setName("secret token=ghp_crontoolname12345");
        secretJob.setPrompt("prompt Authorization: Bearer ghp_crontoolprompt12345");
        secretJob.setDeliverPlatform("FEISHU");
        secretJob.setDeliverChatId("chat-ghp_crontoolchat12345");
        secretJob.setDeliverThreadId("thread-ghp_crontoolthread12345");
        secretJob.setScript("script-ghp_crontoolscript12345.py");
        secretJob.setWorkdir("/tmp/token-workdir-ghp_crontoolworkdir12345");
        secretJob.setModel("model-ghp_crontoolmodel12345");
        secretJob.setProvider("provider-ghp_crontoolprovider12345");
        secretJob.setStatus("PAUSED");
        secretJob.setPausedReason("paused token=ghp_crontoolpaused12345");
        env.cronJobRepository.save(secretJob);
        String secretInspect = cronjobInspect(tools, secretJob.getJobId());
        assertThat(secretInspect)
                .contains("Bearer ***")
                .doesNotContain("ghp_crontoolname12345")
                .doesNotContain("ghp_crontoolprompt12345")
                .doesNotContain("ghp_crontoolchat12345")
                .doesNotContain("ghp_crontoolthread12345")
                .doesNotContain("ghp_crontoolscript12345")
                .doesNotContain("ghp_crontoolworkdir12345")
                .doesNotContain("ghp_crontoolmodel12345")
                .doesNotContain("ghp_crontoolprovider12345")
                .doesNotContain("ghp_crontoolpaused12345")
                .doesNotContain("u:p@example.com");
        service.remove(secretJob.getJobId());

        File scriptsDir = FileUtil.file(env.appConfig.getRuntime().getHome(), "scripts");
        FileUtil.mkdir(scriptsDir);
        FileUtil.writeString(
                "print('metadata')",
                FileUtil.file(scriptsDir, "metadata.py"),
                StandardCharsets.UTF_8);
        File workdir =
                FileUtil.file(env.appConfig.getRuntime().getHome(), "projects/cron-metadata");
        FileUtil.mkdir(workdir);
        Map<?, ?> metadata =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-metadata",
                                                "30m",
                                                null,
                                                "local",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "metadata.py",
                                                workdir.getAbsolutePath(),
                                                Boolean.TRUE,
                                                jobId,
                                                "terminal,file",
                                                null,
                                                null))
                                .toData();
        Map<?, ?> metadataJob = (Map<?, ?>) metadata.get("job");
        String metadataJobId = String.valueOf(metadata.get("job_id"));
        assertThat(metadataJob.get("script")).isEqualTo("metadata.py");
        assertThat(metadataJob.get("workdir")).isEqualTo("workspace://projects/cron-metadata");
        assertThat(metadataJob.get("no_agent")).isEqualTo(Boolean.TRUE);
        assertThat(metadataJob.get("context_from"))
                .isEqualTo(java.util.Collections.singletonList(jobId));
        assertThat(metadataJob.get("depends_on"))
                .isEqualTo(java.util.Collections.singletonList(jobId));
        assertThat(metadataJob.get("enabled_toolsets"))
                .isEqualTo(java.util.Arrays.asList("terminal", "file"));
        Map<?, ?> aliasPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-depends-on",
                                                "30m",
                                                "depends payload prompt",
                                                "local",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                java.util.Collections.singletonList(jobId),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        Map<?, ?> aliasJob = (Map<?, ?>) aliasPayload.get("job");
        String aliasJobId = String.valueOf(aliasPayload.get("job_id"));
        assertThat(aliasJob.get("context_from"))
                .isEqualTo(java.util.Collections.singletonList(jobId));
        assertThat(aliasJob.get("depends_on"))
                .isEqualTo(java.util.Collections.singletonList(jobId));
        tools.cronjob(
                "remove",
                aliasJobId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        tools.cronjob(
                "remove",
                metadataJobId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        Map<?, ?> arrayPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-array-payload",
                                                "30m",
                                                "array payload prompt",
                                                java.util.Arrays.asList(
                                                        "origin", "MEMORY:array-room"),
                                                java.util.Collections.singletonList("watcher"),
                                                java.util.Arrays.asList("reporter", "watcher"),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                java.util.Collections.singletonList(jobId),
                                                java.util.Arrays.asList("web", "file"),
                                                null,
                                                null))
                                .toData();
        Map<?, ?> arrayJob = (Map<?, ?>) arrayPayload.get("job");
        String arrayJobId = String.valueOf(arrayPayload.get("job_id"));
        assertThat(arrayPayload.get("deliver")).isEqualTo("origin,MEMORY:array-room");
        assertThat(arrayPayload.get("skill")).isEqualTo("reporter");
        assertThat(arrayPayload.get("skills"))
                .isEqualTo(java.util.Arrays.asList("reporter", "watcher"));
        assertThat(arrayJob.get("context_from"))
                .isEqualTo(java.util.Collections.singletonList(jobId));
        assertThat(arrayJob.get("enabled_toolsets"))
                .isEqualTo(java.util.Arrays.asList("web", "file"));
        tools.cronjob(
                "remove",
                arrayJobId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        tools.cronjob(
                "pause",
                jobId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "waiting for upstream fix");
        Map<?, ?> pausedTool = (Map<?, ?>) ONode.ofJson(cronjobList(tools, Boolean.TRUE)).toData();
        List<?> pausedJobs = (List<?>) pausedTool.get("jobs");
        assertThat(((Map<?, ?>) pausedJobs.get(0)).get("paused_reason"))
                .isEqualTo("waiting for upstream fix");

        Map<?, ?> inspected =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "inspect", jobId, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null, null))
                                .toData();
        assertThat(inspected.get("job_id")).isEqualTo(jobId);
        assertThat(((Map<?, ?>) inspected.get("job")).get("paused_reason"))
                .isEqualTo("waiting for upstream fix");
        Map<?, ?> showAlias =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "show", jobId, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();
        Map<?, ?> detailAlias =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "detail", jobId, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();
        assertThat(showAlias.get("summary").toString()).contains("定时任务详情");
        assertThat(detailAlias.get("summary").toString()).contains("定时任务详情");

        Map<?, ?> defaultList =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "list", null, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();
        assertThat(defaultList.get("count")).isEqualTo(Integer.valueOf(1));
        List<?> defaultJobs = (List<?>) defaultList.get("jobs");
        assertThat(((Map<?, ?>) defaultJobs.get(0)).get("state")).isEqualTo("paused");

        Map<?, ?> filteredList =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "list",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Boolean.FALSE,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        assertThat(filteredList.get("count")).isEqualTo(Integer.valueOf(0));

        Map<?, ?> allList = (Map<?, ?>) ONode.ofJson(cronjobList(tools, Boolean.TRUE)).toData();
        assertThat(allList.get("count")).isEqualTo(Integer.valueOf(1));
        List<?> jobs = (List<?>) allList.get("jobs");
        Map<?, ?> listedJob = (Map<?, ?>) jobs.get(0);
        assertThat(listedJob.get("state")).isEqualTo("paused");
        assertThat(listedJob.get("repeat")).isEqualTo("forever");
        assertThat(listedJob.get("schedule")).isEqualTo("30m");

        Map<?, ?> triggered =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "run", jobId, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();
        assertThat(triggered.get("triggered")).isEqualTo(Boolean.TRUE);
        assertThat(triggered.get("trigger_message").toString()).contains("下一次调度 tick");
        assertThat(triggered.get("next_run_at")).isNotNull();
        assertThat(triggered.get("summary").toString()).contains("立即运行队列");

        Map<?, ?> removed =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "remove", jobId, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();
        assertThat(removed.get("message")).isEqualTo("定时任务 'tool-job' 已删除。");
        assertThat(((Map<?, ?>) removed.get("removed_job")).get("schedule")).isEqualTo("30m");

        Map<?, ?> missingJob =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "pause",
                                                "missing-job",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        assertThat(missingJob.get("status")).isEqualTo("error");
        assertThat(missingJob.get("error")).asString().contains("Job not found");

        Map<?, ?> toolModel =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-model",
                                                "30m",
                                                "tool model prompt",
                                                "local",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "tool-object-model",
                                                "default"))
                                .toData();
        Map<?, ?> modelJob = (Map<?, ?>) toolModel.get("job");
        assertThat(modelJob.get("model")).isEqualTo("tool-object-model");
        assertThat(modelJob.get("provider")).isEqualTo("default");
        assertThat(modelJob.containsKey("base_url")).isFalse();

        Map<?, ?> emptyUpdate =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "edit",
                                                "missing-job",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        assertThat(emptyUpdate.get("status")).isEqualTo("error");
        assertThat(emptyUpdate.get("error")).isEqualTo("未提供任何更新内容。");
    }

    /** 固化 Web 长会话回归中验证过的 prompt 型任务默认行为，避免误判为 no_agent 脚本任务。 */
    @Test
    void shouldDefaultPromptCronjobToolCreateToAgentModeWhenNoAgentOmitted() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-feedback-room:user");

        Map<?, ?> payload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-feedback-prompt",
                                                "1m",
                                                "WEB_LOOP_TOOL_FEEDBACK_CORRECTION_OK",
                                                "origin",
                                                null,
                                                null,
                                                Integer.valueOf(1),
                                                null,
                                                Boolean.TRUE,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();

        assertThat(payload.get("status")).isEqualTo("success");
        assertThat(payload.get("no_agent")).isEqualTo(Boolean.FALSE);
        assertThat(payload.get("script")).isNull();
        Map<?, ?> jobView = (Map<?, ?>) payload.get("job");
        assertThat(jobView.get("no_agent")).isEqualTo(Boolean.FALSE);
        assertThat(jobView.get("script")).isNull();

        CronJobRecord record =
                env.cronJobRepository.findById(String.valueOf(payload.get("job_id")));
        assertThat(record.isNoAgent()).isFalse();
        assertThat(record.getScript()).isNull();
        assertThat(record.getPrompt()).isEqualTo("WEB_LOOP_TOOL_FEEDBACK_CORRECTION_OK");
    }

    @Test
    void shouldListCronJobsAcrossChannelSourcesFromTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "wechat-created-job");
        body.put("schedule", "30m");
        body.put("prompt", "created from weixin");
        body.put("deliver", "origin");
        Map<String, Object> origin = new LinkedHashMap<String, Object>();
        origin.put("platform", "WEIXIN");
        origin.put("chat_id", "wx-chat");
        origin.put("user_id", "wx-user");
        body.put("origin", origin);
        service.create("WEIXIN:wx-chat:wx-user", body);

        CronjobTools feishuTools = new CronjobTools(service, "FEISHU:fs-chat:fs-user");
        Map<?, ?> listed = (Map<?, ?>) ONode.ofJson(cronjobList(feishuTools)).toData();

        assertThat(listed.get("count")).isEqualTo(Integer.valueOf(1));
        assertThat(String.valueOf(listed.get("preview"))).contains("wechat-created-job");
    }

    @Test
    void shouldExposeCronjobListStatusSummaryBeforeLongJobPreview() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:list-summary-room:user");

        CronJobRecord due = job("list-summary-due", "MEMORY:list-summary-room:user");
        due.setName("due job");
        env.cronJobRepository.save(due);

        CronJobRecord paused = job("list-summary-paused", "MEMORY:list-summary-room:user");
        paused.setName("paused job");
        paused.setStatus("PAUSED");
        env.cronJobRepository.save(paused);

        CronJobRecord completed = job("list-summary-completed", "MEMORY:list-summary-room:user");
        completed.setName("completed job");
        completed.setStatus("COMPLETED");
        env.cronJobRepository.save(completed);

        Map<?, ?> listed = (Map<?, ?>) ONode.ofJson(cronjobList(tools)).toData();

        assertThat(listed.get("count")).isEqualTo(Integer.valueOf(3));
        assertThat(listed.get("total")).isEqualTo(Integer.valueOf(3));
        assertThat(listed.get("active")).isEqualTo(Integer.valueOf(1));
        assertThat(listed.get("paused")).isEqualTo(Integer.valueOf(1));
        assertThat(listed.get("completed")).isEqualTo(Integer.valueOf(1));
        assertThat(listed.get("due")).isEqualTo(Integer.valueOf(1));
        Map<?, ?> status = (Map<?, ?>) listed.get("cron_status");
        assertThat(status.get("total")).isEqualTo(Integer.valueOf(3));
        assertThat(status.get("active")).isEqualTo(Integer.valueOf(1));
        assertThat(status.get("due")).isEqualTo(Integer.valueOf(1));
        assertThat(String.valueOf(listed.get("preview")))
                .startsWith("Cron 状态：total=3, active=1, paused=1, due=1");
    }

    @Test
    void shouldClearCronjobToolEditableMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig
                .getProviders()
                .get("default")
                .setModels(
                        java.util.Arrays.asList(
                                "gpt-5.4", "gpt-5.2", "gpt-5.3", "claude-sonnet-4", "clear-model"));
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-clear-room:user");

        File scriptsDir = FileUtil.file(env.appConfig.getRuntime().getHome(), "scripts");
        FileUtil.mkdir(scriptsDir);
        FileUtil.writeString(
                "print('clear')", FileUtil.file(scriptsDir, "clear.py"), StandardCharsets.UTF_8);
        File workdir = FileUtil.file(env.appConfig.getRuntime().getHome(), "projects/cron-clear");
        FileUtil.mkdir(workdir);

        Map<?, ?> upstreamPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-clear-upstream",
                                                "30m",
                                                "upstream prompt",
                                                "local",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        String upstreamJobId = String.valueOf(upstreamPayload.get("job_id"));

        Map<?, ?> createPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-clear-target",
                                                "30m",
                                                null,
                                                "local",
                                                null,
                                                null,
                                                null,
                                                null,
                                                Boolean.TRUE,
                                                "clear.py",
                                                workdir.getAbsolutePath(),
                                                Boolean.TRUE,
                                                upstreamJobId,
                                                "terminal,file",
                                                "clear-model",
                                                "default"))
                                .toData();
        String jobId = String.valueOf(createPayload.get("job_id"));
        Map<?, ?> createdJob = (Map<?, ?>) createPayload.get("job");
        assertThat(createdJob.get("script")).isEqualTo("clear.py");
        assertThat(createdJob.get("workdir")).isEqualTo("workspace://projects/cron-clear");
        assertThat(createdJob.get("context_from"))
                .isEqualTo(java.util.Collections.singletonList(upstreamJobId));
        assertThat(createdJob.get("enabled_toolsets"))
                .isEqualTo(java.util.Arrays.asList("terminal", "file"));
        assertThat(createdJob.get("model")).isEqualTo("clear-model");
        assertThat(createdJob.get("provider")).isEqualTo("default");

        Map<?, ?> updatePayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "update",
                                                jobId,
                                                null,
                                                null,
                                                "agent prompt after clearing",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "",
                                                "",
                                                Boolean.FALSE,
                                                java.util.Collections.emptyList(),
                                                java.util.Collections.emptyList(),
                                                "",
                                                ""))
                                .toData();
        Map<?, ?> updatedJob = (Map<?, ?>) updatePayload.get("job");
        assertThat(updatedJob.get("script")).isNull();
        assertThat(updatedJob.get("workdir")).isNull();
        assertThat(updatedJob.get("no_agent")).isEqualTo(Boolean.FALSE);
        assertThat(updatedJob.get("context_from")).isNull();
        assertThat(updatedJob.get("depends_on")).isNull();
        assertThat(updatedJob.get("enabled_toolsets")).isNull();
        assertThat(updatedJob.get("model")).isNull();
        assertThat(updatedJob.get("provider")).isNull();
        assertThat(updatedJob.containsKey("base_url")).isFalse();
        assertThat(((CronJobRecord) service.require(jobId)).getPrompt())
                .isEqualTo("agent prompt after clearing");
    }

    @Test
    void shouldIncrementallyEditCronjobToolSkills() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-skill-room:user");

        Map<?, ?> createPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-skill-target",
                                                "30m",
                                                "skill prompt",
                                                "local",
                                                null,
                                                null,
                                                null,
                                                java.util.Arrays.asList("alpha", "beta"),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        String jobId = String.valueOf(createPayload.get("job_id"));
        assertThat(((Map<?, ?>) createPayload.get("job")).get("skills"))
                .isEqualTo(java.util.Arrays.asList("alpha", "beta"));

        Map<?, ?> updatePayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "update",
                                                jobId,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "gamma",
                                                "[\"delta\",\"gamma\"]",
                                                "alpha",
                                                "missing",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        assertThat(((Map<?, ?>) updatePayload.get("job")).get("skills"))
                .isEqualTo(java.util.Arrays.asList("beta", "gamma", "delta"));

        Map<?, ?> clearPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "update",
                                                jobId,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Boolean.TRUE,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        assertThat(((Map<?, ?>) clearPayload.get("job")).get("skills"))
                .isEqualTo(java.util.Collections.emptyList());
    }

    @Test
    void shouldEditCronjobToolStateFields() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-state-room:user");

        Map<?, ?> createPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-state-target",
                                                "30m",
                                                "state prompt",
                                                "local",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        String jobId = String.valueOf(createPayload.get("job_id"));

        Map<?, ?> pausedPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "update",
                                                jobId,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Boolean.FALSE,
                                                null,
                                                null,
                                                "waiting on upstream",
                                                null,
                                                null))
                                .toData();
        Map<?, ?> pausedJob = (Map<?, ?>) pausedPayload.get("job");
        assertThat(pausedJob.get("enabled")).isEqualTo(Boolean.FALSE);
        assertThat(pausedJob.get("state")).isEqualTo("paused");
        assertThat(pausedJob.get("paused_reason")).isEqualTo("waiting on upstream");

        Map<?, ?> resumedPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "update", jobId, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, "active",
                                                null, null, null))
                                .toData();
        Map<?, ?> resumedJob = (Map<?, ?>) resumedPayload.get("job");
        assertThat(resumedJob.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(resumedJob.get("state")).isEqualTo("scheduled");
        assertThat(resumedJob.get("paused_reason")).isNull();
    }

    @Test
    void shouldExposeUpcomingCronJobsThroughCronjobTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-next-room:user");

        Map<?, ?> laterPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "later-tool-job",
                                                "30m",
                                                "later prompt",
                                                "local",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        Map<?, ?> soonPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "soon-tool-job",
                                                "30m",
                                                "soon prompt",
                                                "local",
                                                null,
                                                null,
                                                Integer.valueOf(2),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        Map<?, ?> pausedPayload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "paused-tool-job",
                                                "30m",
                                                "paused prompt",
                                                "local",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        CronJobRecord later =
                env.cronJobRepository.findById(String.valueOf(laterPayload.get("job_id")));
        CronJobRecord soon =
                env.cronJobRepository.findById(String.valueOf(soonPayload.get("job_id")));
        CronJobRecord paused =
                env.cronJobRepository.findById(String.valueOf(pausedPayload.get("job_id")));
        long now = System.currentTimeMillis();
        later.setNextRunAt(now + 120000L);
        soon.setNextRunAt(now + 60000L);
        paused.setNextRunAt(now + 30000L);
        paused.setStatus("PAUSED");
        env.cronJobRepository.update(later);
        env.cronJobRepository.update(soon);
        env.cronJobRepository.update(paused);

        Map<?, ?> next =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "next",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Integer.valueOf(1)))
                                .toData();

        assertThat(next.get("summary")).isEqualTo("已列出即将运行的定时任务");
        assertThat(next.get("count")).isEqualTo(Integer.valueOf(1));
        assertThat(next.get("limit")).isEqualTo(Integer.valueOf(1));
        List<?> nextJobs = (List<?>) next.get("jobs");
        assertThat(((Map<?, ?>) nextJobs.get(0)).get("job_id")).isEqualTo(soon.getJobId());
        assertThat(next.get("preview").toString())
                .contains("soon-tool-job")
                .doesNotContain("paused-tool-job");

        Map<?, ?> upcomingAlias =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "upcoming",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Integer.valueOf(5)))
                                .toData();
        List<?> allUpcoming = (List<?>) upcomingAlias.get("jobs");
        assertThat(allUpcoming).hasSize(2);
        assertThat(((Map<?, ?>) allUpcoming.get(0)).get("job_id")).isEqualTo(soon.getJobId());
        assertThat(((Map<?, ?>) allUpcoming.get(1)).get("job_id")).isEqualTo(later.getJobId());
    }

    @Test
    void shouldRedactSecretsFromCronjobToolErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-room:user");

        Map<?, ?> unsupported =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "inspect --token=ghp_cronaction12345",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        Map<?, ?> missing =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "pause",
                                                "job_token=ghp_cronjob12345",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();

        assertThat(String.valueOf(unsupported.get("error")))
                .contains("token=***")
                .doesNotContain("ghp_cronaction12345");
        assertThat(String.valueOf(missing.get("error")))
                .contains("token=***")
                .doesNotContain("ghp_cronjob12345");
    }

    @Test
    void shouldRedactSecretsFromCronjobToolSuccessResultsOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-room:user");
        env.cronJobRepository.save(job("job-ghp_crondep12345", "MEMORY:tool-room:user"));

        String createdJson =
                tools.cronjob(
                        "create",
                        null,
                        "name-ghp_cronname12345",
                        "30m",
                        "prompt Authorization: Bearer ghp_cronprompt12345",
                        "feishu:chat-ghp_crondeliver12345",
                        "chat-ghp_cronchat12345",
                        "thread-ghp_cronthread12345",
                        "skill-ghp_cronskill12345",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "job-ghp_crondep12345",
                        null,
                        "terminal-ghp_crontoolset12345",
                        null,
                        null,
                        null,
                        null);

        Map<?, ?> created = (Map<?, ?>) ONode.ofJson(createdJson).toData();
        String createdText = String.valueOf(created);
        String jobId = String.valueOf(created.get("job_id"));

        assertThat(createdText)
                .contains("ghp_***")
                .contains("Bearer ***")
                .doesNotContain("ghp_cronname12345")
                .doesNotContain("ghp_cronprompt12345")
                .doesNotContain("ghp_crondeliver12345")
                .doesNotContain("ghp_cronchat12345")
                .doesNotContain("ghp_cronthread12345")
                .doesNotContain("ghp_cronskill12345")
                .doesNotContain("ghp_crondep12345")
                .doesNotContain("ghp_crontoolset12345");

        Map<?, ?> inspected =
                (Map<?, ?>) ONode.ofJson(cronjobInspect(tools, jobId, Integer.valueOf(5))).toData();

        assertThat(String.valueOf(inspected))
                .contains("ghp_***")
                .doesNotContain("ghp_cronprompt12345")
                .doesNotContain("ghp_cronskill12345")
                .doesNotContain("ghp_crondep12345")
                .doesNotContain("ghp_crontoolset12345");

        CronJobRecord record = env.cronJobRepository.findById(jobId);
        assertThat(record.getPrompt()).contains("ghp_cronprompt12345");
        assertThat(record.getSkillsJson()).contains("ghp_cronskill12345");
        assertThat(record.getContextFromJson()).contains("ghp_crondep12345");
        assertThat(record.getEnabledToolsetsJson()).contains("ghp_crontoolset12345");
    }

    @Test
    void shouldDefaultCronjobToolDeliveryToOrigin() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:tool-origin-room:tool-user");

        Map<?, ?> created =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "tool-origin",
                                                "30m",
                                                "origin prompt",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();

        assertThat(created.get("deliver")).isEqualTo("origin");
        Map<?, ?> job = (Map<?, ?>) created.get("job");
        assertThat(job.get("deliver")).isEqualTo("origin");
        String jobId = String.valueOf(created.get("job_id"));
        CronJobRecord record = env.cronJobRepository.findById(jobId);
        assertThat(record.getDeliverPlatform()).isEqualTo("origin");
        Map<?, ?> origin = (Map<?, ?>) ONode.ofJson(record.getOriginJson()).toData();
        assertThat(origin.get("platform")).isEqualTo("MEMORY");
        assertThat(origin.get("chat_id")).isEqualTo("tool-origin-room");
        assertThat(origin.get("user_id")).isEqualTo("tool-user");
    }

    @Test
    void shouldExposeJimuquCronjobSchemaGuidance() throws Exception {
        Method method = cronjobToolMethod();
        ToolMapping mapping = method.getAnnotation(ToolMapping.class);

        assertThat(mapping.description())
                .contains("create/add")
                .contains("inspect/show/detail")
                .contains("next/upcoming")
                .contains("status")
                .contains("update/edit")
                .contains("pause/disable/stop")
                .contains("resume/enable/start")
                .contains("remove/delete/rm")
                .contains("run/run_now/trigger/retry/rerun")
                .contains("禁止猜测 job_id")
                .contains("定时任务不应递归创建新的定时任务")
                .contains("no_agent")
                .contains("必须在工具参数中显式传入对应布尔值")
                .contains("仅设置 script 不会隐式启用 no_agent");
        assertThat(paramDescription(method, "job_id")).contains("先 list 再使用");
        assertThat(paramDescription(method, "deliver")).contains("省略时自动投递回当前来源");
        assertThat(paramRequired(method, "deliver")).isFalse();
        assertThat(paramDescription(method, "deliver_chat_id")).contains("投递会话 ID");
        assertThat(paramDescription(method, "deliver_thread_id")).contains("投递线程 ID");
        assertThat(paramDescription(method, "include_disabled")).contains("默认包含");
        assertThat(paramDescription(method, "script")).contains("传空字符串清空");
        assertThat(paramDescription(method, "workdir")).contains("传空字符串清空");
        assertThat(paramDescription(method, "no_agent"))
                .contains("必须设置 script")
                .contains("空 stdout 静默");
        assertThat(paramRequired(method, "no_agent")).isFalse();
        assertThat(paramDescription(method, "context_from")).contains("update 传空数组清空");
        assertThat(paramDescription(method, "enabled_toolsets")).contains("update 传空数组清空");
        assertThat(paramDescription(method, "enabled")).contains("false 会暂停").contains("true 会恢复");
        assertThat(paramDescription(method, "status"))
                .contains("active")
                .contains("paused")
                .contains("completed");
        assertThat(paramDescription(method, "state")).contains("status 的别名");
        assertThat(paramDescription(method, "paused_reason")).contains("暂停原因");
    }

    @Test
    void shouldExposeNoAgentAndWrapResponseInMethodToolProviderSchema() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:schema-room:user");

        FunctionTool tool = cronjobFunctionTool(new MethodToolProvider(tools));
        ONode schema = ONode.ofJson(tool.inputSchema());
        ONode properties = schema.get("properties");

        assertThat(properties.get("no_agent").get("type").getString()).isEqualTo("boolean");
        assertThat(properties.get("wrap_response").get("type").getString()).isEqualTo("boolean");
        assertThat(properties.get("script").get("type").getString()).isEqualTo("string");
        assertThat(properties.get("no_agent").get("description").getString())
                .contains("必须设置 script")
                .contains("空 stdout 静默");
        assertThat(properties.get("wrap_response").get("description").getString())
                .contains("是否包装定时任务投递结果");

        FunctionTool sanitized =
                com.jimuqu.solon.claw.tool.runtime.SanitizedFunctionTool.wrap(tool);
        ONode sanitizedSchema = ONode.ofJson(sanitized.inputSchema());
        ONode sanitizedProperties = sanitizedSchema.get("properties");
        assertThat(sanitizedProperties.get("no_agent").get("type").getString())
                .isEqualTo("boolean");
        assertThat(sanitizedProperties.get("wrap_response").get("type").getString())
                .isEqualTo("boolean");
    }

    @Test
    void shouldExposeCronjobPolicyThroughTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:policy-room:user");

        Map<?, ?> payload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "policy", null, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();
        Map<?, ?> policy = (Map<?, ?>) payload.get("policy");
        Map<?, ?> delivery = (Map<?, ?>) policy.get("delivery");
        Map<?, ?> skillBinding = (Map<?, ?>) policy.get("skill_binding");
        Map<?, ?> execution = (Map<?, ?>) policy.get("execution");
        Map<?, ?> runtimeIsolation = (Map<?, ?>) policy.get("runtime_isolation");

        assertThat(String.valueOf(payload.get("preview")))
                .contains("skills")
                .contains("delivery")
                .contains("wrap_response");
        assertThat(String.valueOf(payload.get("update_fields"))).contains("wrap_response");
        assertThat(String.valueOf(payload.get("action_syntax")))
                .contains("/cron run|trigger|retry|rerun <job-id>");
        assertThat(String.valueOf(payload.get("clear_fields"))).contains("deliver_thread_id");
        assertThat(String.valueOf(payload.get("status_fields"))).contains("recent_failures");
        assertThat(String.valueOf(payload.get("history_fields"))).contains("delivery_result");
        assertThat(payload.get("delivery")).isEqualTo(delivery);
        assertThat(payload.get("skill_binding")).isEqualTo(skillBinding);
        assertThat(payload.get("execution")).isEqualTo(execution);
        assertThat(payload.get("runtime_isolation")).isEqualTo(runtimeIsolation);
        assertThat(String.valueOf(policy.get("actions")))
                .contains("add")
                .contains("edit")
                .contains("pause")
                .contains("disable")
                .contains("stop")
                .contains("resume")
                .contains("enable")
                .contains("start")
                .contains("run_now")
                .contains("retry")
                .contains("rerun")
                .contains("remove")
                .contains("history");
        assertThat(String.valueOf(policy.get("action_syntax")))
                .contains("--add-skill name")
                .contains("--deliver target")
                .contains("pause|disable|stop");
        assertThat(policy.get("sourceScopedList")).isEqualTo(Boolean.TRUE);
        assertThat(policy.get("freshSessionRuns")).isEqualTo(Boolean.TRUE);
        assertThat(runtimeIsolation.get("sourceBoundSessionRuns")).isEqualTo(Boolean.TRUE);
        assertThat(runtimeIsolation.get("sessionBinding")).isEqualTo("source_key");
        assertThat(String.valueOf(runtimeIsolation.get("disabledToolsets")))
                .contains("cronjob")
                .contains("messaging")
                .contains("clarify");
        assertThat(runtimeIsolation.get("autoDeliveryContext")).isEqualTo(Boolean.TRUE);
        assertThat(runtimeIsolation.get("localDeliveryHistoryOnly")).isEqualTo(Boolean.TRUE);
        assertThat(runtimeIsolation.get("tickLockFile")).isEqualTo("workspace/jobs/cron.tick.lock");
        assertThat(runtimeIsolation.get("inactivityTimeoutSeconds"))
                .isEqualTo(Integer.valueOf(600));
        assertThat(runtimeIsolation.get("oneShotGraceWindowSeconds"))
                .isEqualTo(Integer.valueOf(120));
        assertThat(runtimeIsolation.get("protectedDisabledOverridesEnabledToolsets"))
                .isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(runtimeIsolation.get("protectedDisabledToolsets")))
                .contains("cronjob")
                .contains("messaging")
                .contains("clarify");
        assertThat(String.valueOf(policy.get("update_fields")))
                .contains("deliver_chat_id")
                .contains("wrap_response")
                .contains("enabled_toolsets")
                .contains("enabledToolsets")
                .contains("paused_reason");
        assertThat(String.valueOf(policy.get("clear_fields")))
                .contains("deliver_thread_id")
                .contains("context_from")
                .doesNotContain("base_url");
        assertThat(String.valueOf(policy.get("status_fields")))
                .contains("recent_failures")
                .contains("due");
        assertThat(String.valueOf(policy.get("history_fields")))
                .contains("delivery_result")
                .contains("summary");
        assertThat(delivery.get("originDefaultOnCreate")).isEqualTo(Boolean.TRUE);
        assertThat(delivery.get("explicitChatTargetSupported")).isEqualTo(Boolean.TRUE);
        assertThat(delivery.get("threadTargetSupported")).isEqualTo(Boolean.TRUE);
        assertThat(delivery.get("multiTargetDeliverySupported")).isEqualTo(Boolean.TRUE);
        assertThat(delivery.get("wrapResponseSupported")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(delivery.get("clearFlags")))
                .contains("--clear-deliver-thread-id");
        assertThat(String.valueOf(delivery.get("wrapFlags")))
                .contains("--raw")
                .contains("--no-wrap-response");
        assertThat(String.valueOf(delivery.get("wrapResponsePolicy"))).contains("原始输出");
        assertThat(String.valueOf(delivery.get("supportedPlatforms")))
                .contains("FEISHU")
                .contains("DINGTALK")
                .contains("WEIXIN")
                .contains("YUANBAO");
        assertThat(String.valueOf(delivery.get("targetModes")))
                .contains("platform:chat_id:thread_id")
                .contains("多个目标");
        assertThat(skillBinding.get("singleSkillSupported")).isEqualTo(Boolean.TRUE);
        assertThat(skillBinding.get("multipleSkillsSupported")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(skillBinding.get("replaceFlags"))).contains("--skills a,b");
        assertThat(String.valueOf(skillBinding.get("appendFlags"))).contains("--add-skill name");
        assertThat(String.valueOf(skillBinding.get("removeFlags"))).contains("--remove-skills a,b");
        assertThat(String.valueOf(skillBinding.get("clearFlags"))).contains("--clear-skills");
        assertThat(skillBinding.get("contextFromSupported")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(skillBinding.get("dependencyFlags")))
                .contains("--depends-on job-id");
        assertThat(skillBinding.get("enabledToolsetsSupported")).isEqualTo(Boolean.TRUE);
        assertThat(skillBinding.get("enabledToolsetsAliasSupported")).isEqualTo(Boolean.TRUE);
        assertThat(skillBinding.get("protectedDisabledOverridesEnabledToolsets"))
                .isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(skillBinding.get("enabledToolsetsFields")))
                .contains("enabledToolsets");
        assertThat(String.valueOf(skillBinding.get("protectedDisabledToolsets")))
                .contains("clarify");
        assertThat(execution.get("manualRunSupported")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("retryAliasSupported")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("pauseResumeSupported")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("stateEditSupported")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("pausedReasonEditSupported")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("historySupported")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("statusOverviewSupported")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("scriptMustStayInRuntimeScripts")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("dangerousCommandApprovalApplied")).isEqualTo(Boolean.TRUE);
        assertThat(execution.get("promptThreatScanApplied")).isEqualTo(Boolean.TRUE);

        Map<?, ?> capabilities =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "capabilities",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        assertThat(capabilities.get("execution")).isEqualTo(execution);
        assertThat(capabilities.get("delivery")).isEqualTo(delivery);
        assertThat(capabilities.get("skill_binding")).isEqualTo(skillBinding);
        assertThat(capabilities.get("runtime_isolation")).isEqualTo(runtimeIsolation);
    }

    @Test
    void shouldExposeCronjobGuideThroughTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:guide-room:user");

        Map<?, ?> payload =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "guide", null, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();

        assertThat(payload.get("status")).isEqualTo("success");
        assertThat(payload.get("guide")).isInstanceOf(Map.class);
        assertThat(String.valueOf(payload.get("preview"))).contains("cronjob");
    }

    @Test
    void shouldExposeCronjobGlobalStatusAndRetryAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:status-room:user");

        CronJobRecord active = job("status-active", "MEMORY:status-room:user");
        active.setName("active job");
        active.setNextRunAt(System.currentTimeMillis() - 1000L);
        env.cronJobRepository.save(active);

        CronJobRecord failed = job("status-failed", "MEMORY:status-room:user");
        failed.setName("failed job");
        failed.setNextRunAt(System.currentTimeMillis() + 60000L);
        failed.setLastStatus("error");
        failed.setLastError("network failed token=ghp_cronstatus12345\u202E");
        failed.setLastDeliveryError("delivery failed api_key=sk-cronstatus-secret12345\u202E");
        failed.setLastRunAt(System.currentTimeMillis() - 2000L);
        env.cronJobRepository.save(failed);

        CronJobRecord otherSource = job("status-other", "MEMORY:other-room:user");
        otherSource.setLastStatus("error");
        env.cronJobRepository.save(otherSource);
        service.pause("status-active", "maintenance");

        Map<?, ?> status =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "status",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Boolean.TRUE,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Integer.valueOf(10)))
                                .toData();
        Map<?, ?> overview = (Map<?, ?>) status.get("cron_status");
        assertThat(overview.get("total")).isEqualTo(Integer.valueOf(3));
        assertThat(overview.get("active")).isEqualTo(Integer.valueOf(2));
        assertThat(overview.get("paused")).isEqualTo(Integer.valueOf(1));
        assertThat(String.valueOf(overview.get("recent_failures")))
                .contains("status-failed")
                .contains("status-other")
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain("ghp_cronstatus12345")
                .doesNotContain("sk-cronstatus-secret12345")
                .doesNotContain("\u202E");

        Map<?, ?> inspected =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "inspect",
                                                "status-failed",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Integer.valueOf(1)))
                                .toData();
        assertThat(String.valueOf(inspected.get("job")))
                .contains("api_key=***")
                .doesNotContain("sk-cronstatus-secret12345")
                .doesNotContain("\u202E");

        Map<?, ?> retried =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "retry",
                                                "status-failed",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        assertThat(retried.get("triggered")).isEqualTo(Boolean.TRUE);
        assertThat(env.cronJobRepository.findById("status-failed").getNextRunAt())
                .isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @Test
    void shouldSupportCronjobEnableDisableAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronjobTools tools = new CronjobTools(service, "MEMORY:alias-room:user");

        Map<?, ?> created =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "create",
                                                null,
                                                "alias-job",
                                                "30m",
                                                "alias prompt",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                                .toData();
        String jobId = String.valueOf(created.get("job_id"));

        Map<?, ?> stopped =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "stop", jobId, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();
        assertThat(((Map<?, ?>) stopped.get("job")).get("state")).isEqualTo("paused");
        assertThat(env.cronJobRepository.findById(jobId).getStatus()).isEqualTo("PAUSED");

        Map<?, ?> enabled =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "enable", jobId, null, null, null, null, null, null,
                                                null, null, null, null, null, null, null, null,
                                                null, null))
                                .toData();
        assertThat(((Map<?, ?>) enabled.get("job")).get("state")).isEqualTo("scheduled");
        assertThat(env.cronJobRepository.findById(jobId).getStatus()).isEqualTo("ACTIVE");

        tools.cronjob(
                "disable", jobId, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        assertThat(env.cronJobRepository.findById(jobId).getStatus()).isEqualTo("PAUSED");

        tools.cronjob(
                "start", jobId, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        assertThat(env.cronJobRepository.findById(jobId).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldExposeCronRunHistoryThroughSchedulerAndTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        CronJobRecord job = job("job-history", "MEMORY:history-room:user");
        env.cronJobRepository.save(job);

        DefaultCronScheduler scheduler =
                new DefaultCronScheduler(
                        env.appConfig,
                        env.cronJobRepository,
                        service,
                        env.conversationOrchestrator,
                        env.deliveryService,
                        env.gatewayPolicyRepository);
        scheduler.tick();

        assertThat(env.cronJobRepository.listRuns("job-history", 5)).hasSize(1);
        assertThat(env.cronJobRepository.listRuns("job-history", 5).get(0).getStatus())
                .isEqualTo("ok");
        assertThat(env.cronJobRepository.listRuns("job-history", 5).get(0).getOutput())
                .contains("scheduled prompt");

        CronjobTools tools = new CronjobTools(service, "MEMORY:history-room:user");
        Map<?, ?> history =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "history",
                                                "job-history",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Integer.valueOf(3)))
                                .toData();
        assertThat(history.get("count")).isEqualTo(Integer.valueOf(1));
        Map<?, ?> run = (Map<?, ?>) ((List<?>) history.get("runs")).get(0);
        assertThat(run.get("status")).isEqualTo("ok");
        assertThat(run.get("trigger")).isEqualTo("scheduled");
        assertThat(run.get("finished")).isEqualTo(Boolean.TRUE);
        assertThat(((Number) run.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0L);

        Map<?, ?> inspect =
                (Map<?, ?>)
                        ONode.ofJson(
                                        tools.cronjob(
                                                "inspect",
                                                "job-history",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Integer.valueOf(1)))
                                .toData();
        assertThat(((Map<?, ?>) inspect.get("job")).get("job_id")).isEqualTo("job-history");
        assertThat(inspect.get("run_count")).isEqualTo(Integer.valueOf(1));
        assertThat(inspect.get("limit")).isEqualTo(Integer.valueOf(1));
        Map<?, ?> inspectedRun = (Map<?, ?>) ((List<?>) inspect.get("runs")).get(0);
        assertThat(inspectedRun.get("status")).isEqualTo("ok");
        assertThat(inspectedRun.get("output")).asString().contains("scheduled prompt");
    }

    @Test
    void shouldSupportCronEnabledToolsetsCamelAliasAndFilterProtectedToolsets() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        Map<String, Object> createBody = new LinkedHashMap<String, Object>();
        createBody.put("name", "alias-create");
        createBody.put("schedule", "30m");
        createBody.put("prompt", "alias create prompt");
        createBody.put(
                "enabledToolsets",
                java.util.Arrays.asList("web", "cronjob", "messaging", "clarify", "file"));
        CronJobRecord created = service.create("MEMORY:alias-cron:user", createBody);
        assertThat(service.toView(created).get("enabled_toolsets"))
                .isEqualTo(java.util.Arrays.asList("web", "file"));

        Map<String, Object> updateBody = new LinkedHashMap<String, Object>();
        updateBody.put(
                "enabledToolsets", java.util.Arrays.asList("terminal", "cron", "send", "clarify"));
        CronJobRecord updated = service.update(created.getJobId(), updateBody);
        assertThat(service.toView(updated).get("enabled_toolsets"))
                .isEqualTo(java.util.Collections.singletonList("terminal"));
    }

    @Test
    void shouldApplyCronEnabledToolsetsToScheduledAgentRun() throws Exception {
        RecordingToolLlmGateway gateway = new RecordingToolLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "web-only");
        body.put("schedule", "30m");
        body.put("prompt", "check web");
        body.put("enabled_toolsets", java.util.Collections.singletonList("web"));
        CronJobRecord job = service.create("MEMORY:web-cron:user", body);
        job.setNextRunAt(System.currentTimeMillis() - 1000L);
        env.cronJobRepository.update(job);

        DefaultCronScheduler scheduler =
                new DefaultCronScheduler(
                        env.appConfig,
                        env.cronJobRepository,
                        service,
                        env.conversationOrchestrator,
                        env.deliveryService,
                        env.gatewayPolicyRepository,
                        env.dangerousCommandApprovalService);
        scheduler.tick();

        assertThat(gateway.toolObjectsText)
                .contains("SafeWebsearchTool")
                .contains("SafeWebfetchTool")
                .contains("SafeCodeSearchTool")
                .doesNotContain("SolonClawShellSkill")
                .doesNotContain("CronjobTools")
                .doesNotContain("SolonClawFileReadWriteSkill");
        assertThat(gateway.systemPrompt).contains("websearch");
    }

    @Test
    void shouldDisableRecursiveCronAndMessagingToolsDuringCronRuns() throws Exception {
        RecordingToolLlmGateway gateway = new RecordingToolLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "all-tools-with-guard");
        body.put("schedule", "30m");
        body.put("prompt", "use safe tools");
        body.put(
                "enabled_toolsets",
                java.util.Arrays.asList("all", "cronjob", "messaging", "clarify"));
        CronJobRecord job = service.create("MEMORY:guarded-cron:user", body);
        job.setNextRunAt(System.currentTimeMillis() - 1000L);
        env.cronJobRepository.update(job);

        DefaultCronScheduler scheduler =
                new DefaultCronScheduler(
                        env.appConfig,
                        env.cronJobRepository,
                        service,
                        env.conversationOrchestrator,
                        env.deliveryService,
                        env.gatewayPolicyRepository,
                        env.dangerousCommandApprovalService);
        scheduler.tick();

        assertThat(gateway.toolObjectsText)
                .contains("SolonClawShellSkill")
                .doesNotContain("CronjobTools")
                .doesNotContain("MessagingTools");
        assertThat(gateway.systemPrompt)
                .contains("execute_shell")
                .doesNotContain("cronjob")
                .doesNotContain("send_message")
                .doesNotContain("clarify");
    }

    @Test
    void shouldInjectCronRuntimeHintAndKeepMessagingToolDisabled() throws Exception {
        RecordingToolLlmGateway gateway = new RecordingToolLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "cron-runtime-hint");
        body.put("schedule", "30m");
        body.put("prompt", "Summarize the daily status.");
        body.put("enabled_toolsets", java.util.Collections.singletonList("all"));
        CronJobRecord job = service.create("MEMORY:cron-runtime-hint:user", body);
        job.setNextRunAt(System.currentTimeMillis() - 1000L);
        env.cronJobRepository.update(job);

        DefaultCronScheduler scheduler =
                new DefaultCronScheduler(
                        env.appConfig,
                        env.cronJobRepository,
                        service,
                        env.conversationOrchestrator,
                        env.deliveryService,
                        env.gatewayPolicyRepository,
                        env.dangerousCommandApprovalService);
        scheduler.tick();

        assertThat(gateway.userMessage)
                .startsWith("[IMPORTANT: 你正在以定时任务身份运行。")
                .contains("最终回复会自动投递给用户")
                .contains("不要调用 send_message")
                .contains("只回复 \"[SILENT]\"")
                .contains("Summarize the daily status.");
        assertThat(gateway.toolObjectsText).doesNotContain("MessagingTools");
        assertThat(gateway.systemPrompt).doesNotContain("send_message");
    }

    @Test
    void shouldFallbackToCronSchedulerEnabledToolsetsWhenJobUnset() throws Exception {
        RecordingToolLlmGateway gateway = new RecordingToolLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        env.appConfig
                .getScheduler()
                .setEnabledToolsets(
                        java.util.Arrays.asList("web", "cronjob", "messaging", "clarify"));
        CronJobService service = new CronJobService(env.appConfig, env.cronJobRepository);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "cron-platform-web");
        body.put("schedule", "30m");
        body.put("prompt", "check web with platform default");
        CronJobRecord job = service.create("MEMORY:platform-cron:user", body);
        job.setNextRunAt(System.currentTimeMillis() - 1000L);
        env.cronJobRepository.update(job);

        DefaultCronScheduler scheduler =
                new DefaultCronScheduler(
                        env.appConfig,
                        env.cronJobRepository,
                        service,
                        env.conversationOrchestrator,
                        env.deliveryService,
                        env.gatewayPolicyRepository,
                        env.dangerousCommandApprovalService);
        scheduler.tick();

        assertThat(gateway.toolObjectsText)
                .contains("SafeWebsearchTool")
                .contains("SafeWebfetchTool")
                .contains("SafeCodeSearchTool")
                .doesNotContain("SolonClawShellSkill")
                .doesNotContain("CronjobTools")
                .doesNotContain("MessagingTools")
                .doesNotContain("ClarifyTools");
        assertThat(gateway.systemPrompt)
                .contains("websearch")
                .doesNotContain("cronjob")
                .doesNotContain("send_message")
                .doesNotContain("clarify");
    }

    private static class RecordingToolLlmGateway extends FakeLlmGateway {
        private String toolObjectsText;
        private String systemPrompt;
        private String userMessage;

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                java.util.List<Object> toolObjects)
                throws Exception {
            this.systemPrompt = systemPrompt;
            this.userMessage = userMessage;
            this.toolObjectsText = String.valueOf(toolObjects);
            return super.chat(session, systemPrompt, userMessage, toolObjects);
        }
    }
}
