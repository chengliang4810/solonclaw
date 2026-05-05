package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Hermes-style cronjob tool. */
@RequiredArgsConstructor
public class CronjobTools {
    private final CronJobService cronJobService;
    private final String sourceKey;

    @ToolMapping(
            name = "cronjob",
            description =
                    "Manage scheduled cron jobs. action can be create, list, update, pause, resume, remove, or run.")
    public String cronjob(
            @Param(name = "action", description = "create、list、update、pause、resume、remove、run") String action,
            @Param(name = "job_id", description = "任务 ID", required = false) String jobId,
            @Param(name = "name", description = "任务名", required = false) String name,
            @Param(name = "schedule", description = "cron、every 2h、30m 或 ISO 时间", required = false) String schedule,
            @Param(name = "prompt", description = "任务提示词", required = false) String prompt,
            @Param(name = "deliver", description = "local、origin 或平台名", required = false) String deliver,
            @Param(name = "skills", description = "逗号分隔技能列表", required = false) String skills,
            @Param(name = "repeat", description = "重复次数；0 表示无限", required = false) Integer repeat,
            @Param(name = "script", description = "runtime/scripts 下的相对脚本路径", required = false) String script,
            @Param(name = "workdir", description = "绝对工作目录", required = false) String workdir,
            @Param(name = "no_agent", description = "是否跳过 Agent 直接投递脚本输出", required = false) Boolean noAgent,
            @Param(name = "context_from", description = "逗号分隔上游 job id", required = false) String contextFrom,
            @Param(name = "enabled_toolsets", description = "逗号分隔工具集", required = false) String enabledToolsets)
            throws Exception {
        String normalized = action == null ? "list" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("delete".equals(normalized) || "rm".equals(normalized)) {
            normalized = "remove";
        }
        if ("run_now".equals(normalized) || "trigger".equals(normalized)) {
            normalized = "run";
        }

        if ("list".equals(normalized)) {
            List<CronJobRecord> jobs = cronJobService.listBySource(sourceKey, true);
            return ToolResultEnvelope.ok("Listed cron jobs")
                    .data("jobs", views(jobs))
                    .data("count", Integer.valueOf(jobs.size()))
                    .preview(preview(jobs))
                    .toJson();
        }

        if ("create".equals(normalized)) {
            CronJobRecord job = cronJobService.create(sourceKey, body(name, schedule, prompt, deliver, skills, repeat, script, workdir, noAgent, contextFrom, enabledToolsets));
            return ToolResultEnvelope.ok("Created cron job: " + job.getJobId())
                    .data("job_id", job.getJobId())
                    .data("job", cronJobService.toView(job))
                    .preview(job.getJobId() + " " + job.getName() + " ACTIVE")
                    .toJson();
        }

        if (jobId == null || jobId.trim().length() == 0) {
            return ToolResultEnvelope.error("job_id is required for action: " + normalized).toJson();
        }

        CronJobRecord job;
        if ("update".equals(normalized)) {
            job = cronJobService.update(jobId, body(name, schedule, prompt, deliver, skills, repeat, script, workdir, noAgent, contextFrom, enabledToolsets));
        } else if ("pause".equals(normalized)) {
            job = cronJobService.pause(jobId, "paused by cronjob tool");
        } else if ("resume".equals(normalized)) {
            job = cronJobService.resume(jobId);
        } else if ("remove".equals(normalized)) {
            job = cronJobService.remove(jobId);
        } else if ("run".equals(normalized)) {
            job = cronJobService.trigger(jobId);
        } else {
            return ToolResultEnvelope.error("Unsupported cronjob action: " + action).toJson();
        }
        return ToolResultEnvelope.ok("Cron job action completed: " + normalized)
                .data("job", cronJobService.toView(job))
                .preview(job.getJobId() + " " + job.getName() + " " + job.getStatus())
                .toJson();
    }

    private Map<String, Object> body(
            String name,
            String schedule,
            String prompt,
            String deliver,
            String skills,
            Integer repeat,
            String script,
            String workdir,
            Boolean noAgent,
            String contextFrom,
            String enabledToolsets) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        put(body, "name", name);
        put(body, "schedule", schedule);
        put(body, "prompt", prompt);
        put(body, "deliver", deliver);
        put(body, "skills", skills);
        if (repeat != null) {
            body.put("repeat", repeat);
        }
        put(body, "script", script);
        put(body, "workdir", workdir);
        if (noAgent != null) {
            body.put("no_agent", noAgent);
        }
        put(body, "context_from", contextFrom);
        put(body, "enabled_toolsets", enabledToolsets);
        return body;
    }

    private void put(Map<String, Object> body, String key, String value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private List<Map<String, Object>> views(List<CronJobRecord> jobs) {
        List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
        for (CronJobRecord job : jobs) {
            result.add(cronJobService.toView(job));
        }
        return result;
    }

    private String preview(List<CronJobRecord> jobs) {
        if (jobs.isEmpty()) {
            return "No cron jobs";
        }
        StringBuilder buffer = new StringBuilder();
        for (CronJobRecord job : jobs) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(job.getJobId()).append(' ').append(job.getName()).append(' ').append(job.getStatus());
        }
        return buffer.toString();
    }
}
