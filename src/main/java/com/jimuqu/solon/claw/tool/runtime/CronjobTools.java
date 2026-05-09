package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Jimuqu cronjob tool. */
@RequiredArgsConstructor
public class CronjobTools {
    private final CronJobService cronJobService;
    private final String sourceKey;

    @ToolMapping(
            name = "cronjob",
            description =
                    "Manage scheduled cron jobs. Use action='list' or action='next' to inspect jobs before remove; never guess job IDs. action can be create/add, list, inspect/show/detail, next/upcoming, update/edit, pause/disable/stop, resume/enable/start, remove/delete/rm, run/run_now/trigger, or history. Jobs run in fresh sessions, so prompts must be self-contained. Cron jobs should not recursively schedule more cron jobs. Supports per-job skills, delivery, script, workdir, context_from, enabled_toolsets, wrap_response, model, provider, and base_url pinning.")
    public String cronjob(
            @Param(
                            name = "action",
                            description =
                                    "动作：create/add、list、update/edit、pause/disable/stop、resume/enable/start、remove/delete/rm、run/run_now/trigger、history")
                    String action,
            @Param(name = "job_id", description = "任务 ID；update/pause/resume/remove/run/history 必填，先 list 再使用", required = false)
                    String jobId,
            @Param(name = "name", description = "任务名", required = false) String name,
            @Param(name = "schedule", description = "cron、every 2h、30m 或 ISO 时间", required = false) String schedule,
            @Param(name = "prompt", description = "任务提示词", required = false) String prompt,
            @Param(
                            name = "deliver",
                            description =
                                    "省略时自动投递回当前来源；仅在用户要求投递到别处时设置。local 不投递，origin 回原会话，platform:chat_id[:thread_id] 指定目标，支持字符串、数组或逗号分隔多目标")
                    Object deliver,
            @Param(name = "skill", description = "单个技能名；兼容字符串或数组", required = false) Object skill,
            @Param(name = "skills", description = "技能列表；支持数组、JSON 数组或逗号分隔字符串", required = false) Object skills,
            @Param(name = "repeat", description = "重复次数；0 表示无限", required = false) Integer repeat,
            @Param(name = "include_disabled", description = "list 时是否包含暂停任务；工具调用默认包含，传 false 可只看启用任务", required = false)
                    Boolean includeDisabled,
            @Param(name = "wrap_response", description = "是否包装定时任务投递结果", required = false) Boolean wrapResponse,
            @Param(name = "script", description = "runtime/scripts 下的相对脚本路径；update 时传空字符串清空", required = false)
                    String script,
            @Param(name = "workdir", description = "绝对工作目录；会注入项目上下文并设置工具 cwd，update 时传空字符串清空", required = false)
                    String workdir,
            @Param(
                            name = "no_agent",
                            description =
                                    "是否跳过 Agent 直接投递脚本输出；true 时必须设置 script，非空 stdout 原样投递，空 stdout 静默，非零退出发送错误")
                    Boolean noAgent,
            @Param(name = "context_from", description = "上游 job id 列表；注入最近完成输出，update 传空数组清空", required = false)
                    Object contextFrom,
            @Param(name = "depends_on", description = "context_from 的别名；上游 job id 列表，update 传空数组清空", required = false)
                    Object dependsOn,
            @Param(name = "enabled_toolsets", description = "工具集限制列表，例如 web、terminal、file、delegation；update 传空数组清空", required = false)
                    Object enabledToolsets,
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
        if ("disable".equals(normalized) || "stop".equals(normalized)) {
            normalized = "pause";
        }
        if ("enable".equals(normalized) || "start".equals(normalized)) {
            normalized = "resume";
        }
        if ("delete".equals(normalized) || "rm".equals(normalized)) {
            normalized = "remove";
        }
        if ("run_now".equals(normalized) || "trigger".equals(normalized)) {
            normalized = "run";
        }
        if ("show".equals(normalized) || "detail".equals(normalized)) {
            normalized = "inspect";
        }
        if ("upcoming".equals(normalized)) {
            normalized = "next";
        }
        if ("capabilities".equals(normalized) || "policy".equals(normalized) || "status".equals(normalized)) {
            Map<String, Object> policy = cronjobPolicy();
            return ToolResultEnvelope.ok("Cronjob tool policy")
                    .data("policy", policy)
                    .data("actions", policy.get("actions"))
                    .data("delivery", policy.get("delivery"))
                    .data("skill_binding", policy.get("skill_binding"))
                    .preview("cronjob policy: add/edit/pause/resume/run/remove/history")
                    .toJson();
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

        if ("next".equals(normalized)) {
            List<CronJobRecord> jobs =
                    cronJobService.listBySource(sourceKey, includeDisabled == null || includeDisabled.booleanValue());
            List<CronJobRecord> upcoming = upcoming(jobs, limit == null ? 5 : limit.intValue());
            return ToolResultEnvelope.ok("Listed upcoming cron jobs")
                    .data("jobs", views(upcoming))
                    .data("count", Integer.valueOf(upcoming.size()))
                    .data("limit", Integer.valueOf(safeLimit(limit == null ? 5 : limit.intValue())))
                    .preview(preview(upcoming))
                    .toJson();
        }

        if ("create".equals(normalized)) {
            Map<String, Object> createBody =
                    body(
                            name,
                            schedule,
                            prompt,
                            deliver,
                            skill,
                            skills,
                            repeat,
                            wrapResponse,
                            script,
                            workdir,
                            noAgent,
                            contextFrom,
                            dependsOn,
                            enabledToolsets,
                            model,
                            provider,
                            baseUrl);
            applyDefaultOriginDelivery(createBody);
            CronJobRecord job = cronJobService.create(sourceKey, createBody);
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
            return ToolResultEnvelope.error("job_id is required for action: " + safeText(normalized)).toJson();
        }

        if ("inspect".equals(normalized)) {
            CronJobRecord job = cronJobService.require(jobId);
            Map<String, Object> view = formattedView(job);
            int historyLimit = safeLimit(limit == null ? 5 : limit.intValue());
            List<CronJobRunRecord> runs = cronJobService.history(jobId, historyLimit);
            return ToolResultEnvelope.ok("Cron job details: " + job.getJobId())
                    .data("job_id", job.getJobId())
                    .data("job", view)
                    .data("runs", runViews(runs))
                    .data("run_count", Integer.valueOf(runs.size()))
                    .data("limit", Integer.valueOf(historyLimit))
                    .data("message", "Cron job '" + job.getName() + "' details.")
                    .preview(job.getJobId() + " " + job.getName() + " " + job.getStatus())
                    .toJson();
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
            Map<String, Object> view = formattedView(job);
            return ToolResultEnvelope.ok("Cron job queued for immediate run: " + job.getName())
                    .data("job", view)
                    .data("triggered", Boolean.TRUE)
                    .data("next_run_at", view.get("next_run_at"))
                    .data(
                            "trigger_message",
                            "Cron job '"
                                    + job.getName()
                                    + "' will run on the next scheduler tick.")
                    .preview(job.getJobId() + " " + job.getName() + " TRIGGERED")
                    .toJson();
        } else {
            return ToolResultEnvelope.error("Unsupported cronjob action: " + safeText(action)).toJson();
        }
        return ToolResultEnvelope.ok("Cron job action completed: " + normalized)
                .data("job", formattedView(job))
                .preview(job.getJobId() + " " + job.getName() + " " + job.getStatus())
                .toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error(safeError(e)).toJson();
        }
    }

    private String safeError(Exception e) {
        String message = e == null ? "" : e.getMessage();
        if ((message == null || message.length() == 0) && e != null) {
            message = e.getClass().getSimpleName();
        }
        return safeText(message);
    }

    private String safeText(String text) {
        return SecretRedactor.redact(text, 1000);
    }

    private Map<String, Object> cronjobPolicy() {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put(
                "actions",
                Arrays.asList(
                        "create",
                        "add",
                        "update",
                        "edit",
                        "pause",
                        "disable",
                        "stop",
                        "resume",
                        "enable",
                        "start",
                        "run",
                        "run_now",
                        "trigger",
                        "remove",
                        "delete",
                        "history",
                        "inspect",
                        "list",
                        "next"));
        policy.put("sourceScopedList", Boolean.TRUE);
        policy.put("freshSessionRuns", Boolean.TRUE);
        policy.put("selfContainedPromptRequired", Boolean.TRUE);
        policy.put("recursiveCronCreationDiscouraged", Boolean.TRUE);

        Map<String, Object> schedule = new LinkedHashMap<String, Object>();
        schedule.put("cronExpressionSupported", Boolean.TRUE);
        schedule.put("intervalSupported", Boolean.TRUE);
        schedule.put("onceSupported", Boolean.TRUE);
        schedule.put("nextRunPreview", Boolean.TRUE);
        schedule.put("repeatLimitSupported", Boolean.TRUE);
        policy.put("schedule", schedule);

        Map<String, Object> delivery = new LinkedHashMap<String, Object>();
        delivery.put("originDefaultOnCreate", Boolean.TRUE);
        delivery.put("localDeliverySupported", Boolean.TRUE);
        delivery.put("originDeliverySupported", Boolean.TRUE);
        delivery.put("explicitPlatformTargetsSupported", Boolean.TRUE);
        delivery.put("multiTargetDeliverySupported", Boolean.TRUE);
        delivery.put("threadTargetSupported", Boolean.TRUE);
        delivery.put("wrapResponseSupported", Boolean.TRUE);
        delivery.put(
                "supportedPlatforms",
                Arrays.asList("MEMORY", "FEISHU", "DINGTALK", "WECOM", "WEIXIN", "QQBOT", "YUANBAO"));
        policy.put("delivery", delivery);

        Map<String, Object> skillBinding = new LinkedHashMap<String, Object>();
        skillBinding.put("singleSkillSupported", Boolean.TRUE);
        skillBinding.put("multipleSkillsSupported", Boolean.TRUE);
        skillBinding.put("skillRewriteSupported", Boolean.TRUE);
        skillBinding.put("contextFromSupported", Boolean.TRUE);
        skillBinding.put("dependsOnAliasSupported", Boolean.TRUE);
        skillBinding.put("enabledToolsetsSupported", Boolean.TRUE);
        policy.put("skill_binding", skillBinding);

        Map<String, Object> execution = new LinkedHashMap<String, Object>();
        execution.put("manualRunSupported", Boolean.TRUE);
        execution.put("pauseResumeSupported", Boolean.TRUE);
        execution.put("historySupported", Boolean.TRUE);
        execution.put("noAgentScriptSupported", Boolean.TRUE);
        execution.put("scriptMustStayInRuntimeScripts", Boolean.TRUE);
        execution.put("workdirSecurityChecked", Boolean.TRUE);
        execution.put("modelPinSupported", Boolean.TRUE);
        execution.put("providerPinSupported", Boolean.TRUE);
        execution.put("baseUrlPinSupported", Boolean.TRUE);
        execution.put("dangerousCommandApprovalApplied", Boolean.TRUE);
        execution.put("promptThreatScanApplied", Boolean.TRUE);
        execution.put("secretRedactionApplied", Boolean.TRUE);
        policy.put("execution", execution);
        return policy;
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

    private void applyDefaultOriginDelivery(Map<String, Object> body) {
        if (!body.containsKey("deliver")) {
            body.put("deliver", "origin");
        }
        if (!body.containsKey("origin")) {
            body.put("origin", originFromSourceKey());
        }
    }

    private Map<String, Object> originFromSourceKey() {
        String[] parts = SourceKeySupport.split(sourceKey);
        Map<String, Object> origin = new LinkedHashMap<String, Object>();
        origin.put("platform", parts[0]);
        origin.put("chat_id", parts[1]);
        origin.put("user_id", parts[2]);
        return origin;
    }

    private List<Map<String, Object>> views(List<CronJobRecord> jobs) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRecord job : jobs) {
            result.add(formattedView(job));
        }
        return result;
    }

    private List<Map<String, Object>> runViews(List<CronJobRunRecord> runs) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRunRecord run : runs) {
            result.add(cronJobService.runToView(run));
        }
        return result;
    }

    private List<CronJobRecord> upcoming(List<CronJobRecord> jobs, int limit) {
        List<CronJobRecord> result = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : jobs) {
            if (job == null || job.getNextRunAt() <= 0L) {
                continue;
            }
            if ("PAUSED".equalsIgnoreCase(job.getStatus()) || "COMPLETED".equalsIgnoreCase(job.getStatus())) {
                continue;
            }
            result.add(job);
        }
        Collections.sort(
                result,
                new Comparator<CronJobRecord>() {
                    @Override
                    public int compare(CronJobRecord left, CronJobRecord right) {
                        long delta = left.getNextRunAt() - right.getNextRunAt();
                        if (delta < 0L) {
                            return -1;
                        }
                        if (delta > 0L) {
                            return 1;
                        }
                        String leftId = left.getJobId() == null ? "" : left.getJobId();
                        String rightId = right.getJobId() == null ? "" : right.getJobId();
                        return leftId.compareTo(rightId);
                    }
                });
        int safeLimit = safeLimit(limit);
        if (result.size() <= safeLimit) {
            return result;
        }
        return new ArrayList<CronJobRecord>(result.subList(0, safeLimit));
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return 5;
        }
        return Math.min(limit, 50);
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
