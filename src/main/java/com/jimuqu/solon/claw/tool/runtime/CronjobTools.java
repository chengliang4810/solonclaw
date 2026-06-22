package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供Cronjob工具能力，供 Agent 运行时按安全策略调用。 */
@RequiredArgsConstructor
public class CronjobTools {
    /** 工具视图中的本地 ISO 时间格式，保持原有 yyyy-MM-dd'T'HH:mm:ssXXX 展示。 */
    private static final DateTimeFormatter ISO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** 注入定时任务任务服务，用于调用对应业务能力。 */
    private final CronJobService cronJobService;

    /** 记录Cronjob中的来源键。 */
    private final String sourceKey;

    /**
     * 执行cronjob相关逻辑。
     *
     * @param action 操作参数。
     * @param jobId job标识。
     * @param name 名称参数。
     * @param schedule schedule 参数。
     * @param prompt 提示词参数。
     * @param deliver deliver 参数。
     * @param deliverChatId deliver聊天标识。
     * @param deliverThreadId deliverThread标识。
     * @param skill 技能参数。
     * @param skills 技能参数。
     * @param addSkill add技能参数。
     * @param addSkills add技能参数。
     * @param removeSkill remove技能参数。
     * @param removeSkills remove技能参数。
     * @param clearSkills clear技能参数。
     * @param repeat repeat 参数。
     * @param includeDisabled includeDisabled 参数。
     * @param wrapResponse wrap响应响应或执行结果。
     * @param script script 参数。
     * @param workdir 命令执行工作目录。
     * @param noAgent noAgent 参数。
     * @param contextFrom 上下文From上下文。
     * @param dependsOn dependsOn 参数。
     * @param enabledToolsets 启用状态Toolsets开关值。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     * @param enabled 启用状态开关值。
     * @param jobStatus job状态参数。
     * @param state 状态参数。
     * @param pausedReason paused原因参数。
     * @param triggerType trigger类型参数。
     * @param limit 最大返回数量。
     * @param reason 原因参数。
     * @return 返回cronjob结果。
     */
    @ToolMapping(
            name = "cronjob",
            description =
                    "管理定时任务。删除任务前必须先用 action='list'、action='status' 或 action='next' 查看任务，禁止猜测 job_id。action 可使用 create/add、list、status、inspect/show/detail、next/upcoming、update/edit、pause/disable/stop、resume/enable/start、remove/delete/rm、run/run_now/trigger/retry/rerun 或 history。任务会在独立会话中运行，因此 prompt 必须自包含；定时任务不应递归创建新的定时任务。支持按任务绑定 skills、delivery、deliver_chat_id、deliver_thread_id、script、workdir、no_agent、context_from、enabled_toolsets、wrap_response、model、provider 和 base_url。用户明确要求 no_agent=true 或 wrap_response=false 时，必须在工具参数中显式传入对应布尔值；仅设置 script 不会隐式启用 no_agent。")
    public String cronjob(
            @Param(
                            name = "action",
                            description =
                                    "动作：create/add、list、status、update/edit、pause/disable/stop、resume/enable/start、remove/delete/rm、run/run_now/trigger/retry/rerun、history")
                    String action,
            @Param(
                            name = "job_id",
                            description =
                                    "任务 ID；update/pause/resume/remove/run/history 必填，先 list 再使用",
                            required = false)
                    String jobId,
            @Param(name = "name", description = "任务名", required = false) String name,
            @Param(name = "schedule", description = "cron、every 2h、30m 或 ISO 时间", required = false)
                    String schedule,
            @Param(name = "prompt", description = "任务提示词", required = false) String prompt,
            @Param(
                            name = "deliver",
                            description =
                                    "省略时自动投递回当前来源；仅在用户要求投递到别处时设置。local 不投递，origin 回原会话，platform:chat_id[:thread_id] 指定目标，支持字符串、数组或逗号分隔多目标",
                            required = false)
                    Object deliver,
            @Param(
                            name = "deliver_chat_id",
                            description = "指定投递会话 ID；update 时传空字符串清空",
                            required = false)
                    String deliverChatId,
            @Param(
                            name = "deliver_thread_id",
                            description = "指定投递线程 ID；update 时传空字符串清空",
                            required = false)
                    String deliverThreadId,
            @Param(name = "skill", description = "单个技能名；兼容字符串或数组", required = false) Object skill,
            @Param(name = "skills", description = "技能列表；支持数组、JSON 数组或逗号分隔字符串", required = false)
                    Object skills,
            @Param(name = "add_skill", description = "update 时追加单个技能，不会重复添加", required = false)
                    Object addSkill,
            @Param(
                            name = "add_skills",
                            description = "update 时追加技能列表，支持数组、JSON 数组或逗号分隔字符串",
                            required = false)
                    Object addSkills,
            @Param(name = "remove_skill", description = "update 时移除单个技能", required = false)
                    Object removeSkill,
            @Param(
                            name = "remove_skills",
                            description = "update 时移除技能列表，支持数组、JSON 数组或逗号分隔字符串",
                            required = false)
                    Object removeSkills,
            @Param(name = "clear_skills", description = "update 时清空所有技能绑定", required = false)
                    Boolean clearSkills,
            @Param(name = "repeat", description = "重复次数；0 表示无限", required = false) Integer repeat,
            @Param(
                            name = "include_disabled",
                            description = "list 时是否包含暂停任务；工具调用默认包含，传 false 可只看启用任务",
                            required = false)
                    Boolean includeDisabled,
            @Param(name = "wrap_response", description = "是否包装定时任务投递结果", required = false)
                    Boolean wrapResponse,
            @Param(
                            name = "script",
                            description = "runtime/scripts 下的相对脚本路径；update 时传空字符串清空",
                            required = false)
                    String script,
            @Param(
                            name = "workdir",
                            description = "绝对工作目录；会注入项目上下文并设置工具 cwd，update 时传空字符串清空",
                            required = false)
                    String workdir,
            @Param(
                            name = "no_agent",
                            description =
                                    "是否跳过 Agent 直接投递脚本输出；true 时必须设置 script，非空 stdout 原样投递，空 stdout 静默，非零退出发送错误",
                            required = false)
                    Boolean noAgent,
            @Param(
                            name = "context_from",
                            description = "上游 job id 列表；注入最近完成输出，update 传空数组清空",
                            required = false)
                    Object contextFrom,
            @Param(
                            name = "depends_on",
                            description = "context_from 的别名；上游 job id 列表，update 传空数组清空",
                            required = false)
                    Object dependsOn,
            @Param(
                            name = "enabled_toolsets",
                            description = "工具集限制列表，例如 web、terminal、file、delegation；update 传空数组清空",
                            required = false)
                    Object enabledToolsets,
            @Param(
                            name = "model",
                            description = "任务固定模型；支持字符串或 {provider, model} 对象",
                            required = false)
                    Object model,
            @Param(name = "provider", description = "任务固定 provider", required = false)
                    String provider,
            @Param(name = "base_url", description = "任务固定模型 API base URL", required = false)
                    String baseUrl,
            @Param(name = "enabled", description = "编辑任务启用状态；false 会暂停，true 会恢复", required = false)
                    Boolean enabled,
            @Param(
                            name = "status",
                            description = "编辑任务状态：active、paused、completed 等",
                            required = false)
                    String jobStatus,
            @Param(name = "state", description = "status 的别名；编辑任务状态", required = false)
                    String state,
            @Param(name = "paused_reason", description = "任务暂停原因；仅暂停状态下生效", required = false)
                    String pausedReason,
            @Param(name = "trigger_type", description = "run/retry 时写入执行历史的短触发来源", required = false)
                    String triggerType,
            @Param(name = "limit", description = "history 返回条数", required = false) Integer limit,
            @Param(name = "reason", description = "pause 时记录的暂停原因", required = false) String reason)
            throws Exception {
        try {
            String normalized =
                    action == null ? "list" : action.trim().toLowerCase(java.util.Locale.ROOT);
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
            if ("run_now".equals(normalized)
                    || "trigger".equals(normalized)
                    || "retry".equals(normalized)
                    || "rerun".equals(normalized)) {
                normalized = "run";
            }
            if ("show".equals(normalized) || "detail".equals(normalized)) {
                normalized = "inspect";
            }
            if ("upcoming".equals(normalized)) {
                normalized = "next";
            }
            if ("capabilities".equals(normalized) || "policy".equals(normalized)) {
                Map<String, Object> policy = cronJobService.policy();
                return ToolResultEnvelope.ok("Cronjob 工具策略")
                        .data("policy", policy)
                        .data("actions", policy.get("actions"))
                        .data("action_syntax", policy.get("action_syntax"))
                        .data("update_fields", policy.get("update_fields"))
                        .data("clear_fields", policy.get("clear_fields"))
                        .data("status_fields", policy.get("status_fields"))
                        .data("history_fields", policy.get("history_fields"))
                        .data("delivery", policy.get("delivery"))
                        .data("skill_binding", policy.get("skill_binding"))
                        .data("execution", policy.get("execution"))
                        .data("runtime_isolation", policy.get("runtime_isolation"))
                        .preview(
                                "cronjob 策略：add/edit/pause/resume/run/remove/history、skills、delivery、wrap_response")
                        .toJson();
            }

            if ("status".equals(normalized)) {
                List<CronJobRecord> jobs =
                        cronJobService.listAll(
                                includeDisabled == null || includeDisabled.booleanValue());
                Map<String, Object> status = statusView(jobs, limit == null ? 5 : limit.intValue());
                return ToolResultEnvelope.ok("Cronjob 状态")
                        .data("cron_status", status)
                        .data("count", status.get("total"))
                        .data("next", status.get("next"))
                        .data("recent_failures", status.get("recent_failures"))
                        .preview(statusPreview(status))
                        .toJson();
            }

            if ("list".equals(normalized)) {
                List<CronJobRecord> jobs =
                        cronJobService.listAll(
                                includeDisabled == null || includeDisabled.booleanValue());
                Map<String, Object> status = statusView(jobs, limit == null ? 5 : limit.intValue());
                return ToolResultEnvelope.ok("已列出定时任务")
                        .data("count", status.get("total"))
                        .data("cron_status", status)
                        .data("total", status.get("total"))
                        .data("active", status.get("active"))
                        .data("paused", status.get("paused"))
                        .data("completed", status.get("completed"))
                        .data("due", status.get("due"))
                        .data("next", status.get("next"))
                        .data("recent_failures", status.get("recent_failures"))
                        .data("jobs", views(jobs))
                        .preview(listPreview(jobs, status))
                        .toJson();
            }

            if ("next".equals(normalized)) {
                List<CronJobRecord> jobs =
                        cronJobService.listAll(
                                includeDisabled == null || includeDisabled.booleanValue());
                List<CronJobRecord> upcoming = upcoming(jobs, limit == null ? 5 : limit.intValue());
                return ToolResultEnvelope.ok("已列出即将运行的定时任务")
                        .data("jobs", views(upcoming))
                        .data("count", Integer.valueOf(upcoming.size()))
                        .data(
                                "limit",
                                Integer.valueOf(safeLimit(limit == null ? 5 : limit.intValue())))
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
                                deliverChatId,
                                deliverThreadId,
                                skill,
                                skills,
                                addSkill,
                                addSkills,
                                removeSkill,
                                removeSkills,
                                clearSkills,
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
                                baseUrl,
                                enabled,
                                jobStatus,
                                state,
                                pausedReason);
                applyDefaultOriginDelivery(createBody);
                CronJobRecord duplicate =
                        cronJobService.findDuplicateCreateJob(sourceKey, createBody);
                if (duplicate != null) {
                    Map<String, Object> view = formattedView(duplicate);
                    return ToolResultEnvelope.ok("已存在相同定时任务：" + duplicate.getJobId())
                            .data("job_id", duplicate.getJobId())
                            .data("name", safeText(duplicate.getName()))
                            .data("skill", view.get("skill"))
                            .data("skills", view.get("skills"))
                            .data("schedule", duplicate.getCronExpr())
                            .data("repeat", repeatDisplay(duplicate))
                            .data("deliver", safeText(duplicate.getDeliverPlatform()))
                            .data("wrap_response", Boolean.valueOf(duplicate.isWrapResponse()))
                            .data("no_agent", Boolean.valueOf(duplicate.isNoAgent()))
                            .data("script", safeObjectText(view.get("script")))
                            .data("next_run_at", Long.valueOf(duplicate.getNextRunAt()))
                            .data("next_run_at_iso", view.get("next_run_at_iso"))
                            .data("job", view)
                            .data("deduped", Boolean.TRUE)
                            .data("message", "相同定时任务已存在，已复用 '" + safeText(duplicate.getName()) + "'。")
                            .preview(
                                    safeText(
                                            duplicate.getJobId()
                                                    + " "
                                                    + duplicate.getName()
                                                    + " DEDUPED"))
                            .toJson();
                }
                CronJobRecord job = cronJobService.create(sourceKey, createBody);
                Map<String, Object> view = formattedView(job);
                return ToolResultEnvelope.ok("已创建定时任务：" + job.getJobId())
                        .data("job_id", job.getJobId())
                        .data("name", safeText(job.getName()))
                        .data("skill", view.get("skill"))
                        .data("skills", view.get("skills"))
                        .data("schedule", job.getCronExpr())
                        .data("repeat", repeatDisplay(job))
                        .data("deliver", safeText(job.getDeliverPlatform()))
                        .data("wrap_response", Boolean.valueOf(job.isWrapResponse()))
                        .data("no_agent", Boolean.valueOf(job.isNoAgent()))
                        .data("script", safeObjectText(view.get("script")))
                        .data("next_run_at", Long.valueOf(job.getNextRunAt()))
                        .data("next_run_at_iso", view.get("next_run_at_iso"))
                        .data("job", view)
                        .data("deduped", Boolean.FALSE)
                        .data("message", "定时任务 '" + safeText(job.getName()) + "' 已创建。")
                        .preview(safeText(job.getJobId() + " " + job.getName() + " ACTIVE"))
                        .toJson();
            }

            if (jobId == null || jobId.trim().length() == 0) {
                return ToolResultEnvelope.error(
                                "action=" + safeText(normalized) + " 需要提供 job_id。")
                        .toJson();
            }

            if ("inspect".equals(normalized)) {
                CronJobRecord job = cronJobService.require(jobId);
                Map<String, Object> view = formattedView(job);
                int historyLimit = safeLimit(limit == null ? 5 : limit.intValue());
                List<CronJobRunRecord> runs = cronJobService.history(jobId, historyLimit);
                return ToolResultEnvelope.ok("定时任务详情：" + job.getJobId())
                        .data("job_id", job.getJobId())
                        .data("job", view)
                        .data("runs", runViews(runs))
                        .data("run_count", Integer.valueOf(runs.size()))
                        .data("limit", Integer.valueOf(historyLimit))
                        .data("message", "定时任务 '" + safeText(job.getName()) + "' 的详情。")
                        .preview(
                                safeText(
                                        job.getJobId()
                                                + " "
                                                + job.getName()
                                                + " "
                                                + job.getStatus()))
                        .toJson();
            }

            if ("history".equals(normalized)) {
                List<CronJobRunRecord> runs =
                        cronJobService.history(jobId, limit == null ? 20 : limit.intValue());
                return ToolResultEnvelope.ok("已列出定时任务运行历史")
                        .data("job_id", jobId)
                        .data("runs", runViews(runs))
                        .data("count", Integer.valueOf(runs.size()))
                        .preview(previewRuns(runs))
                        .toJson();
            }

            CronJobRecord job;
            if ("update".equals(normalized)) {
                Map<String, Object> updateBody =
                        body(
                                name,
                                schedule,
                                prompt,
                                deliver,
                                deliverChatId,
                                deliverThreadId,
                                skill,
                                skills,
                                addSkill,
                                addSkills,
                                removeSkill,
                                removeSkills,
                                clearSkills,
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
                                baseUrl,
                                enabled,
                                jobStatus,
                                state,
                                pausedReason);
                if (updateBody.isEmpty()) {
                    return ToolResultEnvelope.error("未提供任何更新内容。").toJson();
                }
                job = cronJobService.update(jobId, updateBody);
            } else if ("pause".equals(normalized)) {
                job = cronJobService.pause(jobId, pauseReason(reason, "通过 cronjob 工具暂停"));
            } else if ("resume".equals(normalized)) {
                job = cronJobService.resume(jobId);
            } else if ("remove".equals(normalized)) {
                job = cronJobService.remove(jobId);
                return ToolResultEnvelope.ok("定时任务 '" + safeText(job.getName()) + "' 已删除。")
                        .data("message", "定时任务 '" + safeText(job.getName()) + "' 已删除。")
                        .data("removed_job", removedView(job))
                        .preview(safeText(job.getJobId() + " " + job.getName() + " REMOVED"))
                        .toJson();
            } else if ("run".equals(normalized)) {
                job = cronJobService.trigger(jobId, runTriggerType(triggerType, reason));
                Map<String, Object> view = formattedView(job);
                return ToolResultEnvelope.ok("定时任务已加入立即运行队列：" + safeText(job.getName()))
                        .data("job", view)
                        .data("triggered", Boolean.TRUE)
                        .data("next_run_at", view.get("next_run_at"))
                        .data(
                                "trigger_message",
                                "定时任务 '"
                                        + safeText(job.getName())
                                        + "' 将在下一次调度 tick 运行。")
                        .preview(safeText(job.getJobId() + " " + job.getName() + " TRIGGERED"))
                        .toJson();
            } else {
                return ToolResultEnvelope.error("不支持的 cronjob action：" + safeText(action))
                        .toJson();
            }
            return ToolResultEnvelope.ok("定时任务操作已完成：" + normalized)
                    .data("job", formattedView(job))
                    .preview(safeText(job.getJobId() + " " + job.getName() + " " + job.getStatus()))
                    .toJson();
        } catch (Exception e) {
            return ToolResultEnvelope.error(safeError(e)).toJson();
        }
    }

    /**
     * 运行Trigger类型。
     *
     * @param triggerType trigger类型参数。
     * @param reason 原因参数。
     * @return 返回Trigger类型结果。
     */
    private String runTriggerType(String triggerType, String reason) {
        String raw = triggerType == null || triggerType.trim().length() == 0 ? reason : triggerType;
        String normalized = cronJobService.normalizeTriggerType(raw, "manual");
        return "scheduled".equals(normalized) ? "manual" : normalized;
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Exception e) {
        String message = e == null ? "" : e.getMessage();
        if ((message == null || message.length() == 0) && e != null) {
            message = e.getClass().getSimpleName();
        }
        return safeText(message);
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param text 待处理文本。
     * @return 返回safe Text结果。
     */
    private String safeText(String text) {
        return SecretRedactor.redact(text, 1000);
    }

    /**
     * 执行cronjob相关逻辑。
     *
     * @param action 操作参数。
     * @param jobId job标识。
     * @param name 名称参数。
     * @param schedule schedule 参数。
     * @param prompt 提示词参数。
     * @param deliver deliver 参数。
     * @param skill 技能参数。
     * @param skills 技能参数。
     * @param repeat repeat 参数。
     * @param includeDisabled includeDisabled 参数。
     * @param wrapResponse wrap响应响应或执行结果。
     * @param script script 参数。
     * @param workdir 命令执行工作目录。
     * @param noAgent noAgent 参数。
     * @param contextFrom 上下文From上下文。
     * @param enabledToolsets 启用状态Toolsets开关值。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     * @return 返回cronjob结果。
     */
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
                null,
                null,
                skill,
                skills,
                null,
                null,
                null,
                null,
                null,
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
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 执行cronjob相关逻辑。
     *
     * @param action 操作参数。
     * @param jobId job标识。
     * @param name 名称参数。
     * @param schedule schedule 参数。
     * @param prompt 提示词参数。
     * @param deliver deliver 参数。
     * @param deliverChatId deliver聊天标识。
     * @param deliverThreadId deliverThread标识。
     * @param skill 技能参数。
     * @param skills 技能参数。
     * @param addSkill add技能参数。
     * @param addSkills add技能参数。
     * @param removeSkill remove技能参数。
     * @param removeSkills remove技能参数。
     * @param clearSkills clear技能参数。
     * @param repeat repeat 参数。
     * @param includeDisabled includeDisabled 参数。
     * @param wrapResponse wrap响应响应或执行结果。
     * @param script script 参数。
     * @param workdir 命令执行工作目录。
     * @param noAgent noAgent 参数。
     * @param contextFrom 上下文From上下文。
     * @param dependsOn dependsOn 参数。
     * @param enabledToolsets 启用状态Toolsets开关值。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     * @param limit 最大返回数量。
     * @param reason 原因参数。
     * @return 返回cronjob结果。
     */
    public String cronjob(
            String action,
            String jobId,
            String name,
            String schedule,
            String prompt,
            Object deliver,
            String deliverChatId,
            String deliverThreadId,
            Object skill,
            Object skills,
            Object addSkill,
            Object addSkills,
            Object removeSkill,
            Object removeSkills,
            Boolean clearSkills,
            Integer repeat,
            Boolean includeDisabled,
            Boolean wrapResponse,
            String script,
            String workdir,
            Boolean noAgent,
            Object contextFrom,
            Object dependsOn,
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
                deliverChatId,
                deliverThreadId,
                skill,
                skills,
                addSkill,
                addSkills,
                removeSkill,
                removeSkills,
                clearSkills,
                repeat,
                includeDisabled,
                wrapResponse,
                script,
                workdir,
                noAgent,
                contextFrom,
                dependsOn,
                enabledToolsets,
                model,
                provider,
                baseUrl,
                null,
                null,
                null,
                null,
                null,
                limit,
                reason);
    }

    /**
     * 执行cronjob相关逻辑。
     *
     * @param action 操作参数。
     * @param jobId job标识。
     * @param name 名称参数。
     * @param schedule schedule 参数。
     * @param prompt 提示词参数。
     * @param deliver deliver 参数。
     * @param skill 技能参数。
     * @param skills 技能参数。
     * @param repeat repeat 参数。
     * @param includeDisabled includeDisabled 参数。
     * @param wrapResponse wrap响应响应或执行结果。
     * @param script script 参数。
     * @param workdir 命令执行工作目录。
     * @param noAgent noAgent 参数。
     * @param contextFrom 上下文From上下文。
     * @param enabledToolsets 启用状态Toolsets开关值。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     * @param limit 最大返回数量。
     * @return 返回cronjob结果。
     */
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
                null,
                null,
                skill,
                skills,
                null,
                null,
                null,
                null,
                null,
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
                null,
                null,
                null,
                null,
                limit,
                null);
    }

    /**
     * 执行cronjob相关逻辑。
     *
     * @param action 操作参数。
     * @param jobId job标识。
     * @param name 名称参数。
     * @param schedule schedule 参数。
     * @param prompt 提示词参数。
     * @param deliver deliver 参数。
     * @param deliverChatId deliver聊天标识。
     * @param deliverThreadId deliverThread标识。
     * @param skill 技能参数。
     * @param skills 技能参数。
     * @param repeat repeat 参数。
     * @param includeDisabled includeDisabled 参数。
     * @param wrapResponse wrap响应响应或执行结果。
     * @param script script 参数。
     * @param workdir 命令执行工作目录。
     * @param noAgent noAgent 参数。
     * @param contextFrom 上下文From上下文。
     * @param dependsOn dependsOn 参数。
     * @param enabledToolsets 启用状态Toolsets开关值。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     * @param limit 最大返回数量。
     * @param reason 原因参数。
     * @return 返回cronjob结果。
     */
    public String cronjob(
            String action,
            String jobId,
            String name,
            String schedule,
            String prompt,
            Object deliver,
            String deliverChatId,
            String deliverThreadId,
            Object skill,
            Object skills,
            Integer repeat,
            Boolean includeDisabled,
            Boolean wrapResponse,
            String script,
            String workdir,
            Boolean noAgent,
            Object contextFrom,
            Object dependsOn,
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
                deliverChatId,
                deliverThreadId,
                skill,
                skills,
                null,
                null,
                null,
                null,
                null,
                repeat,
                includeDisabled,
                wrapResponse,
                script,
                workdir,
                noAgent,
                contextFrom,
                dependsOn,
                enabledToolsets,
                model,
                provider,
                baseUrl,
                null,
                null,
                null,
                null,
                null,
                limit,
                reason);
    }

    /**
     * 执行cronjob相关逻辑。
     *
     * @param action 操作参数。
     * @param jobId job标识。
     * @param name 名称参数。
     * @param schedule schedule 参数。
     * @param prompt 提示词参数。
     * @param deliver deliver 参数。
     * @param deliverChatId deliver聊天标识。
     * @param deliverThreadId deliverThread标识。
     * @param skill 技能参数。
     * @param skills 技能参数。
     * @param repeat repeat 参数。
     * @param includeDisabled includeDisabled 参数。
     * @param wrapResponse wrap响应响应或执行结果。
     * @param script script 参数。
     * @param workdir 命令执行工作目录。
     * @param noAgent noAgent 参数。
     * @param contextFrom 上下文From上下文。
     * @param dependsOn dependsOn 参数。
     * @param enabledToolsets 启用状态Toolsets开关值。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     * @param enabled 启用状态开关值。
     * @param status 状态参数。
     * @param state 状态参数。
     * @param pausedReason paused原因参数。
     * @param limit 最大返回数量。
     * @param reason 原因参数。
     * @return 返回cronjob结果。
     */
    public String cronjob(
            String action,
            String jobId,
            String name,
            String schedule,
            String prompt,
            Object deliver,
            String deliverChatId,
            String deliverThreadId,
            Object skill,
            Object skills,
            Integer repeat,
            Boolean includeDisabled,
            Boolean wrapResponse,
            String script,
            String workdir,
            Boolean noAgent,
            Object contextFrom,
            Object dependsOn,
            Object enabledToolsets,
            Object model,
            String provider,
            String baseUrl,
            Boolean enabled,
            String status,
            String state,
            String pausedReason,
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
                deliverChatId,
                deliverThreadId,
                skill,
                skills,
                null,
                null,
                null,
                null,
                null,
                repeat,
                includeDisabled,
                wrapResponse,
                script,
                workdir,
                noAgent,
                contextFrom,
                dependsOn,
                enabledToolsets,
                model,
                provider,
                baseUrl,
                enabled,
                status,
                state,
                pausedReason,
                null,
                limit,
                reason);
    }

    /**
     * 执行cronjob相关逻辑。
     *
     * @param action 操作参数。
     * @param jobId job标识。
     * @param name 名称参数。
     * @param schedule schedule 参数。
     * @param prompt 提示词参数。
     * @param deliver deliver 参数。
     * @param skill 技能参数。
     * @param skills 技能参数。
     * @param repeat repeat 参数。
     * @param includeDisabled includeDisabled 参数。
     * @param wrapResponse wrap响应响应或执行结果。
     * @param script script 参数。
     * @param workdir 命令执行工作目录。
     * @param noAgent noAgent 参数。
     * @param contextFrom 上下文From上下文。
     * @param enabledToolsets 启用状态Toolsets开关值。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     * @param limit 最大返回数量。
     * @param reason 原因参数。
     * @return 返回cronjob结果。
     */
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
                null,
                null,
                skill,
                skills,
                null,
                null,
                null,
                null,
                null,
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
                null,
                null,
                null,
                null,
                limit,
                reason);
    }

    /**
     * 执行正文相关逻辑。
     *
     * @param name 名称参数。
     * @param schedule schedule 参数。
     * @param prompt 提示词参数。
     * @param deliver deliver 参数。
     * @param deliverChatId deliver聊天标识。
     * @param deliverThreadId deliverThread标识。
     * @param skill 技能参数。
     * @param skills 技能参数。
     * @param addSkill add技能参数。
     * @param addSkills add技能参数。
     * @param removeSkill remove技能参数。
     * @param removeSkills remove技能参数。
     * @param clearSkills clear技能参数。
     * @param repeat repeat 参数。
     * @param wrapResponse wrap响应响应或执行结果。
     * @param script script 参数。
     * @param workdir 命令执行工作目录。
     * @param noAgent noAgent 参数。
     * @param contextFrom 上下文From上下文。
     * @param dependsOn dependsOn 参数。
     * @param enabledToolsets 启用状态Toolsets开关值。
     * @param model 模型名称。
     * @param provider 模型或能力提供方。
     * @param baseUrl 待校验或访问的地址参数。
     * @param enabled 启用状态开关值。
     * @param status 状态参数。
     * @param state 状态参数。
     * @param pausedReason paused原因参数。
     * @return 返回body结果。
     */
    private Map<String, Object> body(
            String name,
            String schedule,
            String prompt,
            Object deliver,
            String deliverChatId,
            String deliverThreadId,
            Object skill,
            Object skills,
            Object addSkill,
            Object addSkills,
            Object removeSkill,
            Object removeSkills,
            Boolean clearSkills,
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
            String baseUrl,
            Boolean enabled,
            String status,
            String state,
            String pausedReason) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        put(body, "name", name);
        put(body, "schedule", schedule);
        put(body, "prompt", prompt);
        put(body, "deliver", deliver);
        put(body, "deliver_chat_id", deliverChatId);
        put(body, "deliver_thread_id", deliverThreadId);
        if (Boolean.TRUE.equals(clearSkills)) {
            body.put("skills", new ArrayList<String>());
        } else if (skill != null || skills != null) {
            put(body, "skill", skill);
            put(body, "skills", skills);
        } else if (addSkill != null
                || addSkills != null
                || removeSkill != null
                || removeSkills != null) {
            put(body, "skills_delta", skillDelta(addSkill, addSkills, removeSkill, removeSkills));
        }
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
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        put(body, "status", status);
        put(body, "state", state);
        put(body, "paused_reason", pausedReason);
        return body;
    }

    /**
     * 执行技能Delta相关逻辑。
     *
     * @param addSkill add技能参数。
     * @param addSkills add技能参数。
     * @param removeSkill remove技能参数。
     * @param removeSkills remove技能参数。
     * @return 返回技能Delta结果。
     */
    private Map<String, Object> skillDelta(
            Object addSkill, Object addSkills, Object removeSkill, Object removeSkills) {
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        List<String> add = new ArrayList<String>();
        addAllStrings(add, addSkill);
        addAllStrings(add, addSkills);
        List<String> remove = new ArrayList<String>();
        addAllStrings(remove, removeSkill);
        addAllStrings(remove, removeSkills);
        delta.put("add", add);
        delta.put("remove", remove);
        return delta;
    }

    /**
     * 追加全部字符串s。
     *
     * @param result 结果响应或执行结果。
     * @param value 待规范化或校验的原始值。
     */
    @SuppressWarnings("unchecked")
    private void addAllStrings(List<String> result, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<Object>) value) {
                addString(result, item);
            }
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            Object data = ONode.ofJson(text).toData();
            if (data instanceof Iterable) {
                for (Object item : (Iterable<Object>) data) {
                    addString(result, item);
                }
                return;
            }
        }
        for (String part : text.split(",")) {
            addString(result, part);
        }
    }

    /**
     * 追加字符串。
     *
     * @param result 结果响应或执行结果。
     * @param value 待规范化或校验的原始值。
     */
    private void addString(List<String> result, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.length() == 0 || result.contains(text)) {
            return;
        }
        result.add(text);
    }

    /**
     * 执行put相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private void put(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    /**
     * 应用默认Origin投递。
     *
     * @param body 请求体或消息正文内容。
     */
    private void applyDefaultOriginDelivery(Map<String, Object> body) {
        if (!body.containsKey("deliver")) {
            body.put("deliver", "origin");
        }
        if (!body.containsKey("origin")) {
            body.put("origin", originFromSourceKey());
        }
    }

    /**
     * 执行originFrom来源键相关逻辑。
     *
     * @return 返回origin From来源键结果。
     */
    private Map<String, Object> originFromSourceKey() {
        String[] parts = SourceKeySupport.split(sourceKey);
        Map<String, Object> origin = new LinkedHashMap<String, Object>();
        origin.put("platform", parts[0]);
        origin.put("chat_id", parts[1]);
        origin.put("user_id", parts[2]);
        if (StrUtil.isNotBlank(parts[3])) {
            origin.put("thread_id", parts[3]);
        }
        return origin;
    }

    /**
     * 执行views相关逻辑。
     *
     * @param jobs jobs 参数。
     * @return 返回views结果。
     */
    private List<Map<String, Object>> views(List<CronJobRecord> jobs) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRecord job : jobs) {
            result.add(formattedView(job));
        }
        return result;
    }

    /**
     * 运行Views。
     *
     * @param runs runs 参数。
     * @return 返回Views结果。
     */
    private List<Map<String, Object>> runViews(List<CronJobRunRecord> runs) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRunRecord run : runs) {
            result.add(cronJobService.runToView(run));
        }
        return result;
    }

    /**
     * 执行upcoming相关逻辑。
     *
     * @param jobs jobs 参数。
     * @param limit 最大返回数量。
     * @return 返回upcoming结果。
     */
    private List<CronJobRecord> upcoming(List<CronJobRecord> jobs, int limit) {
        List<CronJobRecord> result = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : jobs) {
            if (job == null || job.getNextRunAt() <= 0L) {
                continue;
            }
            if ("PAUSED".equalsIgnoreCase(job.getStatus())
                    || "COMPLETED".equalsIgnoreCase(job.getStatus())) {
                continue;
            }
            result.add(job);
        }
        Collections.sort(
                result,
                new Comparator<CronJobRecord>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param left 左侧比较对象。
                     * @param right 右侧比较对象。
                     * @return 返回compare结果。
                     */
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

    /**
     * 执行状态视图相关逻辑。
     *
     * @param jobs jobs 参数。
     * @param limit 最大返回数量。
     * @return 返回状态视图。
     */
    private Map<String, Object> statusView(List<CronJobRecord> jobs, int limit) {
        int safeLimit = safeLimit(limit);
        int active = 0;
        int paused = 0;
        int completed = 0;
        int due = 0;
        long now = System.currentTimeMillis();
        List<CronJobRecord> next = new ArrayList<CronJobRecord>();
        List<Map<String, Object>> recentFailures = new ArrayList<Map<String, Object>>();
        for (CronJobRecord job : jobs) {
            if (job == null) {
                continue;
            }
            String status = job.getStatus() == null ? "" : job.getStatus();
            if ("PAUSED".equalsIgnoreCase(status)) {
                paused++;
            } else if ("COMPLETED".equalsIgnoreCase(status)) {
                completed++;
            } else {
                active++;
                if (job.getNextRunAt() > 0L) {
                    next.add(job);
                    if (job.getNextRunAt() <= now) {
                        due++;
                    }
                }
            }
            if (isFailed(job)) {
                recentFailures.add(failureView(job));
            }
        }
        List<Map<String, Object>> limitedNext = views(upcoming(next, safeLimit));
        if (recentFailures.size() > safeLimit) {
            recentFailures =
                    new ArrayList<Map<String, Object>>(recentFailures.subList(0, safeLimit));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("total", Integer.valueOf(jobs.size()));
        result.put("active", Integer.valueOf(active));
        result.put("paused", Integer.valueOf(paused));
        result.put("completed", Integer.valueOf(completed));
        result.put("due", Integer.valueOf(due));
        result.put("limit", Integer.valueOf(safeLimit));
        result.put("next", limitedNext);
        result.put("recent_failures", recentFailures);
        return result;
    }

    /**
     * 判断是否Failed。
     *
     * @param job job 参数。
     * @return 如果Failed满足条件则返回 true，否则返回 false。
     */
    private boolean isFailed(CronJobRecord job) {
        return "error".equalsIgnoreCase(job.getLastStatus())
                || notBlank(job.getLastError())
                || notBlank(job.getLastDeliveryError());
    }

    /**
     * 判断文本是否包含非空白内容。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回not Blank结果。
     */
    private boolean notBlank(String value) {
        return value != null && value.trim().length() > 0;
    }

    /**
     * 执行failure视图相关逻辑。
     *
     * @param job job 参数。
     * @return 返回failure视图。
     */
    private Map<String, Object> failureView(CronJobRecord job) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("job_id", job.getJobId());
        result.put("name", safeText(job.getName()));
        result.put("last_status", job.getLastStatus());
        result.put("last_error", safeText(job.getLastError()));
        result.put("last_delivery_error", safeText(job.getLastDeliveryError()));
        result.put(
                "last_run_at", job.getLastRunAt() <= 0L ? null : Long.valueOf(job.getLastRunAt()));
        return result;
    }

    /**
     * 执行状态预览相关逻辑。
     *
     * @param status 状态参数。
     * @return 返回状态Preview结果。
     */
    private String statusPreview(Map<String, Object> status) {
        return "Cron 状态：total="
                + status.get("total")
                + ", active="
                + status.get("active")
                + ", paused="
                + status.get("paused")
                + ", due="
                + status.get("due")
                + ", failures="
                + ((List<?>) status.get("recent_failures")).size();
    }

    /**
     * 生成安全展示用的限制。
     *
     * @param limit 最大返回数量。
     * @return 返回safe限制结果。
     */
    private int safeLimit(int limit) {
        if (limit <= 0) {
            return 5;
        }
        return Math.min(limit, 50);
    }

    /**
     * 执行formatted视图相关逻辑。
     *
     * @param job job 参数。
     * @return 返回formatted视图。
     */
    private Map<String, Object> formattedView(CronJobRecord job) {
        Map<String, Object> base = cronJobService.toView(job);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("job_id", safeText(job.getJobId()));
        result.put("name", safeText(job.getName()));
        result.put("skill", safeValue(base.get("skill")));
        result.put("skills", safeValue(base.get("skills")));
        result.put("prompt_preview", safeValue(base.get("prompt_preview")));
        result.put("model", safeText(job.getModel()));
        result.put("provider", safeText(job.getProvider()));
        result.put("base_url", safeObjectText(base.get("base_url")));
        result.put("schedule", safeText(job.getCronExpr()));
        result.put("schedule_detail", safeValue(base.get("schedule")));
        result.put("schedule_display", safeValue(base.get("schedule_display")));
        result.put("repeat", repeatDisplay(job));
        result.put("deliver", safeValue(base.get("deliver")));
        result.put("deliver_chat_id", safeObjectText(base.get("deliver_chat_id")));
        result.put("deliver_thread_id", safeObjectText(base.get("deliver_thread_id")));
        result.put("next_run_at", base.get("next_run_at"));
        result.put("next_run_at_iso", isoTime(base.get("next_run_at")));
        result.put("last_run_at", base.get("last_run_at"));
        result.put("last_run_at_iso", isoTime(base.get("last_run_at")));
        result.put("last_status", safeText(job.getLastStatus()));
        result.put("last_delivery_error", safeText(job.getLastDeliveryError()));
        result.put("enabled", base.get("enabled"));
        result.put("state", safeValue(base.get("state")));
        result.put("paused_at", base.get("paused_at"));
        result.put("paused_at_iso", isoTime(base.get("paused_at")));
        result.put("paused_reason", safeText(job.getPausedReason()));
        result.put("created_at", base.get("created_at"));
        result.put("created_at_iso", isoTime(base.get("created_at")));
        result.put("wrap_response", Boolean.valueOf(job.isWrapResponse()));
        put(result, "script", safeObjectText(base.get("script")));
        result.put("no_agent", Boolean.valueOf(job.isNoAgent()));
        Object contextFrom = base.get("context_from");
        if (contextFrom instanceof Iterable && ((Iterable<?>) contextFrom).iterator().hasNext()) {
            Object safeContextFrom = safeValue(contextFrom);
            result.put("context_from", safeContextFrom);
            result.put("depends_on", safeContextFrom);
        }
        Object enabledToolsets = base.get("enabled_toolsets");
        if (enabledToolsets instanceof Iterable
                && ((Iterable<?>) enabledToolsets).iterator().hasNext()) {
            result.put("enabled_toolsets", safeValue(enabledToolsets));
        }
        put(result, "workdir", safeObjectText(base.get("workdir")));
        return result;
    }

    /**
     * 将工具视图中的毫秒时间转换为本地 ISO 时间，避免 Agent 在回复用户时自行换算出错。
     *
     * @param value 工具视图中的毫秒时间值。
     * @return 本地时区 ISO 时间；无有效时间时返回 null。
     */
    private String isoTime(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        long millis = ((Number) value).longValue();
        if (millis <= 0L) {
            return null;
        }
        return ISO_TIME_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /**
     * 生成安全展示用的Object文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Object Text结果。
     */
    private String safeObjectText(Object value) {
        return value == null ? null : safeText(String.valueOf(value));
    }

    /**
     * 生成安全展示用的值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Value结果。
     */
    @SuppressWarnings("unchecked")
    private Object safeValue(Object value) {
        if (value instanceof String) {
            return safeText((String) value);
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), safeValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<Object>) value) {
                result.add(safeValue(item));
            }
            return result;
        }
        return value;
    }

    /**
     * 执行removed视图相关逻辑。
     *
     * @param job job 参数。
     * @return 返回removed视图。
     */
    private Map<String, Object> removedView(CronJobRecord job) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", job.getJobId());
        result.put("name", safeText(job.getName()));
        result.put("schedule", safeText(job.getCronExpr()));
        return result;
    }

    /**
     * 执行pause原因相关逻辑。
     *
     * @param reason 原因参数。
     * @param fallback 兜底参数。
     * @return 返回pause Reason结果。
     */
    private String pauseReason(String reason, String fallback) {
        if (reason == null || reason.trim().length() == 0) {
            return fallback;
        }
        return reason.trim();
    }

    /**
     * 执行repeat展示相关逻辑。
     *
     * @param job job 参数。
     * @return 返回repeat展示结果。
     */
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

    /**
     * 执行预览相关逻辑。
     *
     * @param jobs jobs 参数。
     * @return 返回preview结果。
     */
    private String preview(List<CronJobRecord> jobs) {
        if (jobs.isEmpty()) {
            return "没有定时任务";
        }
        StringBuilder buffer = new StringBuilder();
        for (CronJobRecord job : jobs) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(safeText(job.getJobId() + " " + job.getName() + " " + job.getStatus()));
        }
        return buffer.toString();
    }

    /**
     * 生成列表动作的紧凑预览，确保长列表被截断时仍能先看到关键统计。
     *
     * @param jobs 当前列表动作返回的定时任务记录。
     * @param status 由同一批记录计算出的总数、活跃数、到期数和失败摘要。
     * @return 返回先展示统计、再展示任务样例的列表预览文本。
     */
    private String listPreview(List<CronJobRecord> jobs, Map<String, Object> status) {
        String summary = statusPreview(status);
        if (jobs.isEmpty()) {
            return summary + "\n没有定时任务";
        }
        List<CronJobRecord> sample = jobs;
        Object limit = status.get("limit");
        if (limit instanceof Number && jobs.size() > ((Number) limit).intValue()) {
            sample = new ArrayList<CronJobRecord>(jobs.subList(0, ((Number) limit).intValue()));
        }
        return summary + "\n" + preview(sample);
    }

    /**
     * 执行预览运行相关逻辑。
     *
     * @param runs runs 参数。
     * @return 返回preview运行结果。
     */
    private String previewRuns(List<CronJobRunRecord> runs) {
        if (runs.isEmpty()) {
            return "没有定时任务运行历史";
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
