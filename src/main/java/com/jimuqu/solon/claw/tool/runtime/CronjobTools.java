package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
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
                    "Manage scheduled cron jobs. action can be create, list, update, pause, resume, remove, run, or history. Supports per-job model, provider, and base_url pinning.")
    public String cronjob(
            @Param(name = "action", description = "create、list、update、pause、resume、remove、run、history") String action,
            @Param(name = "job_id", description = "任务 ID", required = false) String jobId,
            @Param(name = "name", description = "任务名", required = false) String name,
            @Param(name = "schedule", description = "cron、every 2h、30m 或 ISO 时间", required = false) String schedule,
            @Param(name = "prompt", description = "任务提示词", required = false) String prompt,
            @Param(name = "deliver", description = "local、origin、平台名、platform:chat_id[:thread_id]，支持字符串、数组或逗号分隔多目标", required = false) Object deliver,
            @Param(name = "skill", description = "单个技能名；兼容字符串或数组", required = false) Object skill,
            @Param(name = "skills", description = "技能列表；支持数组、JSON 数组或逗号分隔字符串", required = false) Object skills,
            @Param(name = "repeat", description = "重复次数；0 表示无限", required = false) Integer repeat,
            @Param(name = "include_disabled", description = "list 时是否包含暂停任务", required = false) Boolean includeDisabled,
            @Param(name = "wrap_response", description = "是否包装定时任务投递结果", required = false) Boolean wrapResponse,
            @Param(name = "script", description = "runtime/scripts 下的相对脚本路径", required = false) String script,
            @Param(name = "workdir", description = "绝对工作目录", required = false) String workdir,
            @Param(name = "no_agent", description = "是否跳过 Agent 直接投递脚本输出", required = false) Boolean noAgent,
            @Param(name = "context_from", description = "上游 job id 列表；支持数组、JSON 数组或逗号分隔字符串", required = false) Object contextFrom,
            @Param(name = "depends_on", description = "上游 job id 列表别名", required = false) Object dependsOn,
            @Param(name = "enabled_toolsets", description = "工具集列表；支持数组、JSON 数组或逗号分隔字符串", required = false) Object enabledToolsets,
            @Param(name = "model", description = "任务固定模型；支持字符串或 {provider, model} 对象", required = false) Object model,
            @Param(name = "provider", description = "任务固定 provider", required = false) String provider,
            @Param(name = "base_url", description = "任务固定模型 API base URL", required = false) String baseUrl,
            @Param(name = "limit", description = "history 返回条数", required = false) Integer limit,
            @Param(name = "reason", description = "pause 时记录的暂停原因", required = false) String reason)
            throws Exception {
        try {
        String normalized = action == null ? "list" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("add".equals(normalized)) {
            normalized = "create";
        }
        if ("edit".equals(normalized)) {
            normalized = "update";
        }
        if ("delete".equals(normalized) || "rm".equals(normalized)) {
            normalized = "remove";
        }
        if ("run_now".equals(normalized) || "trigger".equals(normalized)) {
            normalized = "run";
        }

        if ("list".equals(normalized)) {
            List<CronJobRecord> jobs =
                    cronJobService.listBySource(sourceKey, includeDisabled == null || includeDisabled.booleanValue());
            return ToolResultEnvelope.ok("Listed cron jobs")
                    .data("jobs", views(jobs))
                    .data("count", Integer.valueOf(jobs.size()))
                    .preview(preview(jobs))
                    .toJson();
        }

        if ("create".equals(normalized)) {
            CronJobRecord job = cronJobService.create(sourceKey, body(name, schedule, prompt, deliver, skill, skills, repeat, wrapResponse, script, workdir, noAgent, contextFrom, dependsOn, enabledToolsets, model, provider, baseUrl));
            Map<String, Object> view = formattedView(job);
            return ToolResultEnvelope.ok("Created cron job: " + job.getJobId())
                    .data("job_id", job.getJobId())
                    .data("name", job.getName())
                    .data("skill", view.get("skill"))
                    .data("skills", view.get("skills"))
                    .data("schedule", job.getCronExpr())
                    .data("repeat", repeatDisplay(job))
                    .data("deliver", job.getDeliverPlatform())
                    .data("next_run_at", Long.valueOf(job.getNextRunAt()))
                    .data("job", view)
                    .data("message", "Cron job '" + job.getName() + "' created.")
                    .preview(job.getJobId() + " " + job.getName() + " ACTIVE")
                    .toJson();
        }

        if (jobId == null || jobId.trim().length() == 0) {
            return ToolResultEnvelope.error("job_id is required for action: " + normalized).toJson();
        }

        if ("history".equals(normalized)) {
            List<CronJobRunRecord> runs =
                    cronJobService.history(jobId, limit == null ? 20 : limit.intValue());
            return ToolResultEnvelope.ok("Listed cron run history")
                    .data("job_id", jobId)
                    .data("runs", runViews(runs))
                    .data("count", Integer.valueOf(runs.size()))
                    .preview(previewRuns(runs))
                    .toJson();
        }

        CronJobRecord job;
        if ("update".equals(normalized)) {
            Map<String, Object> updateBody =
                    body(name, schedule, prompt, deliver, skill, skills, repeat, wrapResponse, script, workdir, noAgent, contextFrom, dependsOn, enabledToolsets, model, provider, baseUrl);
            if (updateBody.isEmpty()) {
                return ToolResultEnvelope.error("No updates provided.").toJson();
            }
            job = cronJobService.update(jobId, updateBody);
        } else if ("pause".equals(normalized)) {
            job = cronJobService.pause(jobId, pauseReason(reason, "paused by cronjob tool"));
        } else if ("resume".equals(normalized)) {
            job = cronJobService.resume(jobId);
        } else if ("remove".equals(normalized)) {
            job = cronJobService.remove(jobId);
            return ToolResultEnvelope.ok("Cron job '" + job.getName() + "' removed.")
                    .data("message", "Cron job '" + job.getName() + "' removed.")
                    .data("removed_job", removedView(job))
                    .preview(job.getJobId() + " " + job.getName() + " REMOVED")
                    .toJson();
        } else if ("run".equals(normalized)) {
            job = cronJobService.trigger(jobId);
        } else {
            return ToolResultEnvelope.error("Unsupported cronjob action: " + action).toJson();
        }
        return ToolResultEnvelope.ok("Cron job action completed: " + normalized)
                .data("job", formattedView(job))
                .preview(job.getJobId() + " " + job.getName() + " " + job.getStatus())
                .toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error(e.getMessage()).toJson();
        }
    }

    public String cronjob(
            String action,
            String jobId,
            String name,
            String schedule,
            String prompt,
            Object deliver,
            Object skill,
            Object skills,
            Integer repeat,
            Boolean includeDisabled,
            Boolean wrapResponse,
            String script,
            String workdir,
            Boolean noAgent,
            Object contextFrom,
            Object enabledToolsets,
            Object model,
            String provider,
            String baseUrl)
            throws Exception {
        return cronjob(
                action,
                jobId,
                name,
                schedule,
                prompt,
                deliver,
                skill,
                skills,
                repeat,
                includeDisabled,
                wrapResponse,
                script,
                workdir,
                noAgent,
                contextFrom,
                null,
                enabledToolsets,
                model,
                provider,
                baseUrl,
                null,
                null);
    }

    public String cronjob(
            String action,
            String jobId,
            String name,
            String schedule,
            String prompt,
            Object deliver,
            Object skill,
            Object skills,
            Integer repeat,
            Boolean includeDisabled,
            Boolean wrapResponse,
            String script,
            String workdir,
            Boolean noAgent,
            Object contextFrom,
            Object enabledToolsets,
            Object model,
            String provider,
            String baseUrl,
            Integer limit)
            throws Exception {
        return cronjob(
                action,
                jobId,
                name,
                schedule,
                prompt,
                deliver,
                skill,
                skills,
                repeat,
                includeDisabled,
                wrapResponse,
                script,
                workdir,
                noAgent,
                contextFrom,
                null,
                enabledToolsets,
                model,
                provider,
                baseUrl,
                limit,
                null);
    }

    public String cronjob(
            String action,
            String jobId,
            String name,
            String schedule,
            String prompt,
            Object deliver,
            Object skill,
            Object skills,
            Integer repeat,
            Boolean includeDisabled,
            Boolean wrapResponse,
            String script,
            String workdir,
            Boolean noAgent,
            Object contextFrom,
            Object enabledToolsets,
            Object model,
            String provider,
            String baseUrl,
            Integer limit,
            String reason)
            throws Exception {
        return cronjob(
                action,
                jobId,
                name,
                schedule,
                prompt,
                deliver,
                skill,
                skills,
                repeat,
                includeDisabled,
                wrapResponse,
                script,
                workdir,
                noAgent,
                contextFrom,
                null,
                enabledToolsets,
                model,
                provider,
                baseUrl,
                limit,
                reason);
    }

    private Map<String, Object> body(
            String name,
            String schedule,
            String prompt,
            Object deliver,
            Object skill,
            Object skills,
            Integer repeat,
            Boolean wrapResponse,
            String script,
            String workdir,
            Boolean noAgent,
            Object contextFrom,
            Object dependsOn,
            Object enabledToolsets,
            Object model,
            String provider,
            String baseUrl) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        put(body, "name", name);
        put(body, "schedule", schedule);
        put(body, "prompt", prompt);
        put(body, "deliver", deliver);
        put(body, "skill", skill);
        put(body, "skills", skills);
        if (repeat != null) {
            body.put("repeat", repeat);
        }
        if (wrapResponse != null) {
            body.put("wrap_response", wrapResponse);
        }
        put(body, "script", script);
        put(body, "workdir", workdir);
        if (noAgent != null) {
            body.put("no_agent", noAgent);
        }
        put(body, "context_from", contextFrom);
        put(body, "depends_on", dependsOn);
        put(body, "enabled_toolsets", enabledToolsets);
        put(body, "model", model);
        put(body, "provider", provider);
        put(body, "base_url", baseUrl);
        return body;
    }

    private void put(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private List<Map<String, Object>> views(List<CronJobRecord> jobs) {
        List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
        for (CronJobRecord job : jobs) {
            result.add(formattedView(job));
        }
        return result;
    }

    private List<Map<String, Object>> runViews(List<CronJobRunRecord> runs) {
        List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
        for (CronJobRunRecord run : runs) {
            result.add(cronJobService.runToView(run));
        }
        return result;
    }

    private Map<String, Object> formattedView(CronJobRecord job) {
        Map<String, Object> base = cronJobService.toView(job);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("job_id", job.getJobId());
        result.put("name", job.getName());
        result.put("skill", base.get("skill"));
        result.put("skills", base.get("skills"));
        result.put("prompt_preview", base.get("prompt_preview"));
        result.put("model", job.getModel());
        result.put("provider", job.getProvider());
        result.put("base_url", job.getBaseUrl());
        result.put("schedule", job.getCronExpr());
        result.put("schedule_detail", base.get("schedule"));
        result.put("schedule_display", base.get("schedule_display"));
        result.put("repeat", repeatDisplay(job));
        result.put("deliver", base.get("deliver"));
        result.put("next_run_at", base.get("next_run_at"));
        result.put("last_run_at", base.get("last_run_at"));
        result.put("last_status", job.getLastStatus());
        result.put("last_delivery_error", job.getLastDeliveryError());
        result.put("enabled", base.get("enabled"));
        result.put("state", base.get("state"));
        result.put("paused_at", base.get("paused_at"));
        result.put("paused_reason", job.getPausedReason());
        result.put("wrap_response", Boolean.valueOf(job.isWrapResponse()));
        put(result, "script", job.getScript());
        if (job.isNoAgent()) {
            result.put("no_agent", Boolean.TRUE);
        }
        Object contextFrom = base.get("context_from");
        if (contextFrom instanceof Iterable && ((Iterable<?>) contextFrom).iterator().hasNext()) {
            result.put("context_from", contextFrom);
            result.put("depends_on", contextFrom);
        }
        Object enabledToolsets = base.get("enabled_toolsets");
        if (enabledToolsets instanceof Iterable && ((Iterable<?>) enabledToolsets).iterator().hasNext()) {
            result.put("enabled_toolsets", enabledToolsets);
        }
        put(result, "workdir", job.getWorkdir());
        return result;
    }

    private Map<String, Object> removedView(CronJobRecord job) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", job.getJobId());
        result.put("name", job.getName());
        result.put("schedule", job.getCronExpr());
        return result;
    }

    private String pauseReason(String reason, String fallback) {
        if (reason == null || reason.trim().length() == 0) {
            return fallback;
        }
        return reason.trim();
    }

    private String repeatDisplay(CronJobRecord job) {
        int times = job.getRepeatTimes();
        int completed = job.getRepeatCompleted();
        if (times <= 0) {
            return "forever";
        }
        if (times == 1) {
            return completed == 0 ? "once" : "1/1";
        }
        return completed > 0 ? completed + "/" + times : times + " times";
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

    private String previewRuns(List<CronJobRunRecord> runs) {
        if (runs.isEmpty()) {
            return "No cron run history";
        }
        StringBuilder buffer = new StringBuilder();
        for (CronJobRunRecord run : runs) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(run.getRunId())
                    .append(' ')
                    .append(run.getStatus())
                    .append(' ')
                    .append(run.getStartedAt());
            if (run.getDeliveryError() != null) {
                buffer.append(" delivery_error");
            }
        }
        return buffer.toString();
    }
}
