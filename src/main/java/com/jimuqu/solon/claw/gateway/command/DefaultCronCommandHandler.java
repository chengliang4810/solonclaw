package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;

/** 处理 /cron 定时任务命令，避免默认命令服务承载所有定时任务格式化和解析细节。 */
final class DefaultCronCommandHandler {
    /** 注入定时任务业务服务，用于创建、更新、查询和触发任务。 */
    private final CronJobService cronJobService;

    /** 注入调度器，用于手动 tick 和立即运行。 */
    private final DefaultCronScheduler cronScheduler;

    /**
     * 创建 cron 命令处理器。
     *
     * @param cronJobService 定时任务业务服务。
     * @param cronScheduler 定时任务调度器。
     */
    DefaultCronCommandHandler(CronJobService cronJobService, DefaultCronScheduler cronScheduler) {
        this.cronJobService = cronJobService;
        this.cronScheduler = cronScheduler;
    }

    /** 执行定时任务命令相关逻辑。 */
    GatewayReply handle(GatewayMessage message, String args) throws Exception {
        boolean overview = StrUtil.isBlank(args);
        SlashCommandLine.ActionTail parsed =
                SlashCommandLine.parseActionTail(args, GatewayCommandConstants.ACTION_LIST);
        String action = parsed.getAction();
        String tail = parsed.getTail();
        String runTriggerType = "manual";
        if (GatewayCommandConstants.ACTION_ADD.equals(action)) {
            action = GatewayCommandConstants.ACTION_CREATE;
        }
        if ("edit".equals(action)) {
            action = GatewayCommandConstants.ACTION_UPDATE;
        }
        if ("rm".equals(action)
                || GatewayCommandConstants.ACTION_REMOVE.equals(action)
                || GatewayCommandConstants.ACTION_DELETE.equals(action)) {
            action = GatewayCommandConstants.ACTION_DELETE;
        }
        if ("disable".equals(action) || "stop".equals(action)) {
            action = GatewayCommandConstants.ACTION_PAUSE;
        }
        if ("enable".equals(action) || "start".equals(action)) {
            action = GatewayCommandConstants.ACTION_RESUME;
        }
        if ("retry".equals(action) || "rerun".equals(action)) {
            runTriggerType = "retry";
            action = GatewayCommandConstants.ACTION_RUN;
        }
        if ("trigger".equals(action)) {
            action = GatewayCommandConstants.ACTION_RUN;
        }
        if ("upcoming".equals(action)) {
            action = "next";
        }

        if (GatewayCommandConstants.ACTION_LIST.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            List<CronJobRecord> jobs = cronJobService.listAll(options.all);
            String listText = formatCronList(jobs);
            return GatewayReply.ok(overview ? cronOverview(listText) : listText);
        }

        if ("status".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            return GatewayReply.ok(formatCronStatus(message.sourceKey(), options.all));
        }

        if ("next".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            int limit = options.limit == null ? 5 : options.limit.intValue();
            return GatewayReply.ok(formatCronNext(message.sourceKey(), options.all, limit));
        }

        if ("guide".equals(action)
                || "tutorial".equals(action)
                || "capabilities".equals(action)
                || "policy".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            Map<String, Object> guide = cronJobService.guide();
            return GatewayReply.ok(options.json ? ONode.serialize(guide) : formatCronGuide(guide));
        }

        if ("tick".equals(action)) {
            if (cronScheduler == null) {
                return GatewayReply.error("当前运行环境未启用 Cron scheduler，无法手动 tick。");
            }
            cronScheduler.tick();
            return GatewayReply.ok(formatCronStatus(message.sourceKey(), true));
        }

        if ("history".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " history <job-id> [--limit 20]");
            }
            int limit = options.limit == null ? 20 : options.limit.intValue();
            List<CronJobRunRecord> runs = cronJobService.history(options.positionals.get(0), limit);
            return GatewayReply.ok(formatCronHistory(options.positionals.get(0), runs));
        }

        if (GatewayCommandConstants.ACTION_INSPECT.equalsIgnoreCase(action)
                || "show".equals(action)
                || "detail".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法：" + GatewayCommandConstants.SLASH_CRON + " inspect <job-id>");
            }
            String jobId = options.positionals.get(0);
            CronJobRecord job = cronJobService.require(jobId);
            return GatewayReply.ok(formatCronDetail(job));
        }

        if (GatewayCommandConstants.ACTION_CREATE.equalsIgnoreCase(action)) {
            Map<String, Object> body = parseCronCreate(tail);
            if (body == null) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " add <name>|<schedule>|<prompt>|[--skill a,b] 或 "
                                + GatewayCommandConstants.SLASH_CRON
                                + " add \"every 2h\" \"Check server\" [--skill blogwatcher]");
            }

            if (!body.containsKey("deliver")) {
                body.put("deliver", "origin");
            }
            body.put("origin", cronOrigin(message));
            CronJobRecord job = cronJobService.create(message.sourceKey(), body);
            return GatewayReply.ok("已创建定时任务：" + job.getJobId());
        }

        if (GatewayCommandConstants.ACTION_PAUSE.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " pause|disable|stop <job-id> [--reason 原因]");
            }
            String jobId = options.positionals.get(0);
            String reason =
                    StrUtil.blankToDefault(options.reason, joinTail(options.positionals, 1));
            cronJobService.pause(jobId, StrUtil.blankToDefault(reason, "paused by slash command"));
            return GatewayReply.ok("已暂停定时任务：" + jobId);
        }

        if (GatewayCommandConstants.ACTION_RESUME.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " resume|enable|start <job-id>");
            }
            String jobId = options.positionals.get(0);
            cronJobService.resume(jobId);
            return GatewayReply.ok("已恢复定时任务：" + jobId);
        }

        if (GatewayCommandConstants.ACTION_DELETE.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法：" + GatewayCommandConstants.SLASH_CRON + " remove <job-id>");
            }
            String jobId = options.positionals.get(0);
            cronJobService.remove(jobId);
            return GatewayReply.ok("已删除定时任务：" + jobId);
        }

        if (GatewayCommandConstants.ACTION_UPDATE.equalsIgnoreCase(action)) {
            CronEditRequest edit = parseCronEdit(tail);
            if (edit == null) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " edit <job-id> [--schedule ...] [--prompt ...] [--skill ...|--add-skill ...|--remove-skill ...|--clear-skills] [--clear-script|--clear-workdir|--clear-context-from|--clear-toolsets]");
            }
            CronJobRecord job = cronJobService.update(edit.jobId, edit.body);
            return GatewayReply.ok("已更新定时任务：" + job.getJobId());
        }

        if (GatewayCommandConstants.ACTION_RUN.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " run|trigger|retry|rerun <job-id>");
            }
            String jobId = options.positionals.get(0);
            cronJobService.require(jobId);
            runTriggerType = cronRunTriggerType(options, runTriggerType);
            if (cronScheduler == null) {
                cronJobService.trigger(jobId, runTriggerType);
                return GatewayReply.ok("已标记定时任务将在下一次 tick 执行：" + jobId);
            }
            cronScheduler.runNow(jobId, runTriggerType);
            return GatewayReply.ok("已执行定时任务：" + jobId);
        }

        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_CRON
                        + " [list [--all]|inspect|show|next|upcoming|guide|tutorial|capabilities|policy|add|edit|pause|disable|stop|resume|enable|start|remove|delete|run|trigger|retry|rerun|history|status|tick]");
    }

    /**
     * 执行定时任务运行Trigger类型相关逻辑。
     *
     * @param options options 参数。
     * @param fallback 兜底参数。
     * @return 返回定时任务运行Trigger类型结果。
     */
    private String cronRunTriggerType(CronFlagOptions options, String fallback) {
        String value =
                options == null
                        ? null
                        : StrUtil.blankToDefault(options.triggerType, options.reason);
        if (StrUtil.isBlank(value)) {
            return fallback;
        }
        String normalized = normalizeCronTriggerType(value, fallback);
        if ("scheduled".equals(normalized)) {
            return fallback;
        }
        if ("retry".equals(fallback) && "manual".equals(normalized)) {
            return "retry";
        }
        return normalized;
    }

    /**
     * 规范化定时任务Trigger类型。
     *
     * @param value 待规范化或校验的原始值。
     * @param fallback 兜底参数。
     * @return 返回定时任务Trigger类型结果。
     */
    private String normalizeCronTriggerType(String value, String fallback) {
        return cronJobService.normalizeTriggerType(value, fallback);
    }

    /**
     * 格式化定时任务Guide。
     *
     * @param guide guide标识或键值。
     * @return 返回定时任务Guide结果。
     */
    private String formatCronGuide(Map<String, Object> guide) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Cron 自动化指南");
        buffer.append('\n').append("目标：").append(guide.get("objective"));
        buffer.append('\n').append("调度类型：").append(joinGuideList(guide.get("schedule_types")));
        buffer.append('\n').append("可编辑字段：").append(joinGuideList(guide.get("editable_fields")));
        buffer.append('\n').append("动作：").append(joinGuideMapKeys(guide.get("actions")));
        buffer.append('\n').append("动作语法：");
        appendGuideMap(buffer, guide.get("action_syntax"));
        buffer.append('\n').append("别名：");
        appendGuideMap(buffer, guide.get("aliases"));
        buffer.append('\n').append("技能绑定：");
        appendGuideMap(buffer, guide.get("skill_binding"));
        buffer.append('\n').append("投递策略：");
        appendGuideMap(buffer, guide.get("delivery"));
        buffer.append('\n').append("运行模式：");
        appendGuideMap(buffer, guide.get("runtime_modes"));
        buffer.append('\n').append("历史与状态：");
        appendGuideMap(buffer, guide.get("history_and_status"));
        buffer.append('\n').append("安全策略：");
        appendGuideMap(buffer, guide.get("security"));
        buffer.append('\n').append("示例：");
        appendGuideListLines(buffer, guide.get("slash_examples"));
        return buffer.toString();
    }

    /**
     * 追加Guide Map。
     *
     * @param buffer buffer 参数。
     * @param value 待规范化或校验的原始值。
     */
    private void appendGuideMap(StringBuilder buffer, Object value) {
        if (!(value instanceof Map)) {
            buffer.append(' ').append(String.valueOf(value));
            return;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            buffer.append('\n')
                    .append("- ")
                    .append(String.valueOf(entry.getKey()))
                    .append(": ")
                    .append(joinGuideValue(entry.getValue()));
        }
    }

    /**
     * 追加Guide List Lines。
     *
     * @param buffer buffer 参数。
     * @param value 待规范化或校验的原始值。
     */
    private void appendGuideListLines(StringBuilder buffer, Object value) {
        if (!(value instanceof Iterable)) {
            buffer.append('\n').append("- ").append(String.valueOf(value));
            return;
        }
        for (Object item : (Iterable<?>) value) {
            buffer.append('\n').append("- ").append(String.valueOf(item));
        }
    }

    /**
     * 执行joinGuide映射Keys相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回join Guide Map Keys结果。
     */
    private String joinGuideMapKeys(Object value) {
        if (!(value instanceof Map)) {
            return String.valueOf(value);
        }
        StringBuilder buffer = new StringBuilder();
        for (Object key : ((Map<?, ?>) value).keySet()) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(String.valueOf(key));
        }
        return buffer.toString();
    }

    /**
     * 执行joinGuide列表相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回join Guide List结果。
     */
    private String joinGuideList(Object value) {
        if (!(value instanceof Iterable)) {
            return String.valueOf(value);
        }
        StringBuilder buffer = new StringBuilder();
        for (Object item : (Iterable<?>) value) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(String.valueOf(item));
        }
        return buffer.toString();
    }

    /**
     * 执行joinGuide值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回join Guide Value结果。
     */
    private String joinGuideValue(Object value) {
        if (value instanceof Iterable) {
            return joinGuideList(value);
        }
        if (value instanceof Map) {
            return joinGuideMapKeys(value);
        }
        return String.valueOf(value);
    }

    /**
     * 执行定时任务Overview相关逻辑。
     *
     * @param listText 列表文本参数。
     * @return 返回定时任务Overview结果。
     */
    private String cronOverview(String listText) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Cron 定时任务\n")
                .append("命令：\n")
                .append("/cron list - 查看启用中的定时任务\n")
                .append("/cron list --all - 查看全部定时任务，包括已暂停任务\n")
                .append("/cron inspect <job-id> - 查看单个任务详情\n")
                .append("/cron next [--all] [--limit 5] - 查看即将运行的任务\n")
                .append("/cron upcoming [--all] [--limit 5] - next 的别名\n")
                .append("/cron guide [--json] - 查看自动化能力、字段、别名、投递、技能绑定和安全策略\n")
                .append("/cron status [--all] - 查看任务计数、到期任务、最近失败与下次运行\n")
                .append(
                        "/cron add \"every 2h\" \"Check server status\" [--skill blogwatcher] - 创建定时任务\n")
                .append(
                        "/cron edit <job-id> --schedule \"every 4h\" --prompt \"New task\" - 编辑定时任务\n")
                .append("/cron edit <job-id> --skill blogwatcher --skill maps - 替换绑定技能\n")
                .append("/cron edit <job-id> --remove-skill blogwatcher - 移除绑定技能\n")
                .append("/cron edit <job-id> --clear-skills - 清空绑定技能\n")
                .append("/cron edit <job-id> --clear-repeat - 清空重复次数上限，恢复无限重复\n")
                .append(
                        "/cron edit <job-id> --clear-script --clear-workdir --clear-context-from --clear-toolsets - 清空脚本、工作目录、上下文链和工具集限制\n")
                .append(
                        "/cron add \"every 2h\" \"task\" --model gpt-5.4 --provider default --base-url https://api.openai.com --no-wrap-response - 固定模型与投递包装\n")
                .append(
                        "/cron add \"every 2h\" \"task\" --deliver feishu --deliver-chat-id chat --deliver-thread-id thread - 指定投递会话与线程\n")
                .append(
                        "/cron edit <job-id> --clear-deliver-chat-id --clear-deliver-thread-id - 清空投递会话与线程\n")
                .append(
                        "/cron edit <job-id> --clear-model --clear-provider --clear-base-url - 清空任务级模型/provider/base URL 固定值\n")
                .append(
                        "/cron edit <job-id> --no-agent|--agent --wrap-response|--no-wrap-response - 切换脚本直投与回复包装\n")
                .append("/cron pause|disable|stop <job-id> [--reason 原因] - 暂停定时任务\n")
                .append("/cron resume|enable|start <job-id> - 恢复定时任务\n")
                .append("/cron run <job-id> - 立即触发定时任务\n")
                .append("/cron trigger <job-id> - run 的别名\n")
                .append("/cron retry <job-id> - 重跑最近失败或需要复核的定时任务\n")
                .append("/cron tick - 立即执行一次 scheduler tick\n")
                .append("/cron history <job-id> [--limit 20] - 查看执行历史\n")
                .append("/cron remove <job-id> - 删除定时任务\n")
                .append('\n')
                .append(listText);
        return buffer.toString();
    }

    /**
     * 格式化定时任务状态。
     *
     * @param sourceKey 渠道来源键。
     * @param all all 参数。
     * @return 返回定时任务状态。
     */
    private String formatCronStatus(String sourceKey, boolean all) throws Exception {
        List<CronJobRecord> jobs = cronJobService.listAll(true);
        long now = System.currentTimeMillis();
        int active = 0;
        int paused = 0;
        int completed = 0;
        int due = 0;
        int failed = 0;
        int deliveryErrors = 0;
        long nextRunAt = 0L;
        CronJobRecord nextJob = null;
        List<CronJobRecord> recentProblems = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : jobs) {
            String state = cronState(job);
            if ("paused".equals(state)) {
                paused++;
            } else if ("completed".equals(state)) {
                completed++;
            } else {
                active++;
                if (job.getNextRunAt() > 0L && job.getNextRunAt() <= now) {
                    due++;
                }
                if (job.getNextRunAt() > 0L
                        && (nextRunAt <= 0L || job.getNextRunAt() < nextRunAt)) {
                    nextRunAt = job.getNextRunAt();
                    nextJob = job;
                }
            }
            if (StrUtil.isNotBlank(job.getLastStatus())
                    && !"ok".equalsIgnoreCase(job.getLastStatus())) {
                failed++;
                addRecentProblem(recentProblems, job);
            }
            if (StrUtil.isNotBlank(job.getLastDeliveryError())) {
                deliveryErrors++;
                addRecentProblem(recentProblems, job);
            }
        }

        StringBuilder buffer = new StringBuilder("Cron 状态");
        buffer.append('\n').append("范围：全部任务");
        buffer.append('\n').append("总数：").append(jobs.size());
        buffer.append('\n')
                .append("状态：active=")
                .append(active)
                .append(", paused=")
                .append(paused)
                .append(", completed=")
                .append(completed);
        buffer.append('\n').append("已到期：").append(due);
        buffer.append('\n').append("最近失败：").append(failed);
        buffer.append('\n').append("最近投递错误：").append(deliveryErrors);
        if (nextJob == null) {
            buffer.append('\n').append("下次运行：N/A");
        } else {
            buffer.append('\n')
                    .append("下次运行：")
                    .append(formatTimestamp(nextRunAt))
                    .append(" ")
                    .append(nextJob.getJobId())
                    .append(" ")
                    .append(StrUtil.blankToDefault(nextJob.getName(), ""));
        }
        if (!recentProblems.isEmpty()) {
            buffer.append('\n').append("问题任务：");
            for (CronJobRecord job : recentProblems) {
                buffer.append('\n')
                        .append("- ")
                        .append(job.getJobId())
                        .append(" ")
                        .append(StrUtil.blankToDefault(job.getName(), ""))
                        .append(" status=")
                        .append(StrUtil.blankToDefault(job.getLastStatus(), "ok"));
                if (StrUtil.isNotBlank(job.getLastError())) {
                    buffer.append(" error=").append(safeCronText(job.getLastError(), 120));
                }
                if (StrUtil.isNotBlank(job.getLastDeliveryError())) {
                    buffer.append(" delivery=")
                            .append(safeCronText(job.getLastDeliveryError(), 120));
                }
            }
        }
        return buffer.toString();
    }

    /**
     * 格式化定时任务Next。
     *
     * @param sourceKey 渠道来源键。
     * @param all all 参数。
     * @param limit 最大返回数量。
     * @return 返回定时任务Next结果。
     */
    private String formatCronNext(String sourceKey, boolean all, int limit) throws Exception {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 50);
        List<CronJobRecord> jobs = cronJobService.listAll(true);
        List<CronJobRecord> upcoming = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : jobs) {
            String state = cronState(job);
            if (!"scheduled".equals(state) || job.getNextRunAt() <= 0L) {
                continue;
            }
            upcoming.add(job);
        }
        Collections.sort(
                upcoming,
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
                        return StrUtil.blankToDefault(left.getJobId(), "")
                                .compareTo(StrUtil.blankToDefault(right.getJobId(), ""));
                    }
                });

        StringBuilder buffer = new StringBuilder("Cron 即将运行");
        buffer.append('\n').append("范围：全部任务");
        if (upcoming.isEmpty()) {
            buffer.append('\n').append("暂无即将运行的任务。");
            return buffer.toString();
        }
        int count = Math.min(safeLimit, upcoming.size());
        for (int i = 0; i < count; i++) {
            CronJobRecord job = upcoming.get(i);
            buffer.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(formatTimestamp(job.getNextRunAt()))
                    .append(" ")
                    .append(job.getJobId())
                    .append(" ")
                    .append(StrUtil.blankToDefault(job.getName(), ""));
            buffer.append('\n')
                    .append("   Schedule: ")
                    .append(StrUtil.blankToDefault(job.getCronExpr(), ""));
            buffer.append('\n')
                    .append("   Deliver: ")
                    .append(StrUtil.blankToDefault(job.getDeliverPlatform(), "local"));
            if (job.getRepeatTimes() > 0) {
                buffer.append(" Repeat: ").append(formatCronRepeat(job));
            }
        }
        if (upcoming.size() > count) {
            buffer.append('\n').append("还有 ").append(upcoming.size() - count).append(" 个任务未显示。");
        }
        return buffer.toString();
    }

    /**
     * 执行定时任务状态相关逻辑。
     *
     * @param job job 参数。
     * @return 返回定时任务状态。
     */
    private String cronState(CronJobRecord job) {
        if (job == null) {
            return "scheduled";
        }
        if ("PAUSED".equalsIgnoreCase(job.getStatus())) {
            return "paused";
        }
        if ("COMPLETED".equalsIgnoreCase(job.getStatus())) {
            return "completed";
        }
        return "scheduled";
    }

    /**
     * 追加RecentProblem。
     *
     * @param records records 参数。
     * @param job job 参数。
     */
    private void addRecentProblem(List<CronJobRecord> records, CronJobRecord job) {
        if (job == null || records.contains(job) || records.size() >= 5) {
            return;
        }
        records.add(job);
    }

    /**
     * 格式化定时任务历史。
     *
     * @param jobId job标识。
     * @param runs runs 参数。
     * @return 返回定时任务历史结果。
     */
    private String formatCronHistory(String jobId, List<CronJobRunRecord> runs) {
        if (runs == null || runs.isEmpty()) {
            return "定时任务 " + jobId + " 暂无执行历史。";
        }
        StringBuilder buffer = new StringBuilder("Cron 执行历史：").append(jobId);
        for (CronJobRunRecord run : runs) {
            buffer.append('\n')
                    .append("Run: ")
                    .append(run.getRunId())
                    .append('\n')
                    .append("Status: ")
                    .append(StrUtil.blankToDefault(run.getStatus(), "?"))
                    .append(" trigger=")
                    .append(StrUtil.blankToDefault(run.getTriggerType(), "scheduled"))
                    .append(" attempt=")
                    .append(run.getAttempt())
                    .append('\n')
                    .append("Started: ")
                    .append(run.getStartedAt())
                    .append(" Finished: ")
                    .append(run.getFinishedAt());
            if (StrUtil.isNotBlank(run.getError())) {
                buffer.append('\n').append("Error: ").append(safeCronText(run.getError(), 1000));
            }
            if (StrUtil.isNotBlank(run.getDeliveryError())) {
                buffer.append('\n')
                        .append("Delivery error: ")
                        .append(safeCronText(run.getDeliveryError(), 1000));
            }
            if (StrUtil.isNotBlank(run.getOutput())) {
                buffer.append('\n').append("Output: ").append(safeCronText(run.getOutput(), 300));
            }
        }
        return buffer.toString();
    }

    /**
     * 生成安全展示用的定时任务文本。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe定时任务Text结果。
     */
    private String safeCronText(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /**
     * 格式化时间戳。
     *
     * @param timestamp 请求携带的时间戳。
     * @return 返回时间戳结果。
     */
    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "never";
        }
        return DateUtil.formatDateTime(new java.util.Date(timestamp));
    }

    /**
     * 格式化定时任务Detail。
     *
     * @param job job 参数。
     * @return 返回定时任务Detail结果。
     */
    private String formatCronDetail(CronJobRecord job) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Cron 任务详情：").append(job.getJobId()).append('\n');
        buffer.append(formatCronList(Arrays.asList(job)));
        buffer.append('\n')
                .append("History: ")
                .append(GatewayCommandConstants.SLASH_CRON)
                .append(" history ")
                .append(job.getJobId())
                .append(" --limit 20")
                .append('\n')
                .append("Run: ")
                .append(GatewayCommandConstants.SLASH_CRON)
                .append(" run ")
                .append(job.getJobId())
                .append('\n')
                .append("Edit: ")
                .append(GatewayCommandConstants.SLASH_CRON)
                .append(" edit ")
                .append(job.getJobId())
                .append(" --schedule \"...\" --prompt \"...\"");
        return buffer.toString();
    }

    /**
     * 格式化定时任务List。
     *
     * @param jobs jobs 参数。
     * @return 返回定时任务List结果。
     */
    private String formatCronList(List<CronJobRecord> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return "当前没有定时任务。";
        }
        StringBuilder buffer = new StringBuilder("Scheduled Jobs:");
        for (CronJobRecord job : jobs) {
            Map<String, Object> view = cronJobService.toView(job);
            buffer.append('\n')
                    .append("ID: ")
                    .append(job.getJobId())
                    .append('\n')
                    .append("Name: ")
                    .append(StrUtil.blankToDefault(job.getName(), ""))
                    .append('\n')
                    .append("State: ")
                    .append(StrUtil.blankToDefault(String.valueOf(view.get("state")), "scheduled"))
                    .append('\n')
                    .append("Schedule: ")
                    .append(job.getCronExpr())
                    .append('\n')
                    .append("Repeat: ")
                    .append(formatCronRepeat(job))
                    .append('\n')
                    .append("Next run: ")
                    .append(job.getNextRunAt() <= 0 ? "N/A" : String.valueOf(job.getNextRunAt()));
            String deliver = StrUtil.blankToDefault(job.getDeliverPlatform(), "local");
            buffer.append('\n').append("Deliver: ").append(deliver);
            if (StrUtil.isNotBlank(job.getDeliverChatId())) {
                buffer.append('\n').append("Deliver chat: ").append(job.getDeliverChatId());
            }
            if (StrUtil.isNotBlank(job.getDeliverThreadId())) {
                buffer.append('\n').append("Deliver thread: ").append(job.getDeliverThreadId());
            }
            buffer.append('\n').append("Wrap response: ").append(job.isWrapResponse());
            if (StrUtil.isNotBlank(job.getPausedReason())) {
                buffer.append('\n').append("Paused reason: ").append(job.getPausedReason());
            }
            Object skills = view.get("skills");
            if (skills instanceof Iterable) {
                String text = joinIterable((Iterable<?>) skills, ", ");
                if (StrUtil.isNotBlank(text)) {
                    buffer.append('\n').append("Skills: ").append(text);
                }
            }
            if (StrUtil.isNotBlank(job.getScript())) {
                buffer.append('\n').append("Script: ").append(job.getScript());
            }
            if (job.isNoAgent()) {
                buffer.append('\n').append("Mode: no-agent (script stdout delivered directly)");
            }
            if (StrUtil.isNotBlank(job.getWorkdir())) {
                buffer.append('\n').append("Workdir: ").append(job.getWorkdir());
            }
            appendCronListIterable(buffer, "Context from", view.get("context_from"));
            appendCronListIterable(buffer, "Toolsets", view.get("enabled_toolsets"));
            if (StrUtil.isNotBlank(job.getModel())) {
                buffer.append('\n').append("Model: ").append(job.getModel());
            }
            if (StrUtil.isNotBlank(job.getProvider())) {
                buffer.append('\n').append("Provider: ").append(job.getProvider());
            }
            if (StrUtil.isNotBlank(job.getBaseUrl())) {
                buffer.append('\n').append("Base URL: ").append(job.getBaseUrl());
            }
            buffer.append('\n')
                    .append("Prompt: ")
                    .append(StrUtil.blankToDefault(String.valueOf(view.get("prompt_preview")), ""));
            if (job.getLastRunAt() > 0) {
                buffer.append('\n')
                        .append("Last run: ")
                        .append(job.getLastRunAt())
                        .append(" (")
                        .append(StrUtil.blankToDefault(job.getLastStatus(), "?"))
                        .append(")");
            }
            if (StrUtil.isNotBlank(job.getLastDeliveryError())) {
                buffer.append('\n')
                        .append("Delivery failed: ")
                        .append(safeCronText(job.getLastDeliveryError(), 1000));
            }
        }
        return buffer.toString();
    }

    /**
     * 追加定时任务List Iterable。
     *
     * @param buffer buffer 参数。
     * @param label label 参数。
     * @param values 待规范化或校验的原始值集合。
     */
    private void appendCronListIterable(StringBuilder buffer, String label, Object values) {
        if (!(values instanceof Iterable)) {
            return;
        }
        String text = joinIterable((Iterable<?>) values, ", ");
        if (StrUtil.isNotBlank(text)) {
            buffer.append('\n').append(label).append(": ").append(text);
        }
    }

    /**
     * 格式化定时任务Repeat。
     *
     * @param job job 参数。
     * @return 返回定时任务Repeat结果。
     */
    private String formatCronRepeat(CronJobRecord job) {
        if (job.getRepeatTimes() <= 0) {
            return "∞";
        }
        return job.getRepeatCompleted() + "/" + job.getRepeatTimes();
    }

    /**
     * 执行joinIterable相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param delimiter delimiter 参数。
     * @return 返回join Iterable结果。
     */
    private String joinIterable(Iterable<?> values, String delimiter) {
        StringBuilder buffer = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "" : String.valueOf(value).trim();
            if (StrUtil.isBlank(text)) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append(delimiter);
            }
            buffer.append(text);
        }
        return buffer.toString();
    }

    /**
     * 解析定时任务Create。
     *
     * @param tail tail 参数。
     * @return 返回解析后的定时任务Create。
     */
    private Map<String, Object> parseCronCreate(String tail) {
        if (StrUtil.isBlank(tail)) {
            return null;
        }
        if (tail.contains("|")) {
            String[] fields = tail.split("\\|", -1);
            if (fields.length < 3) {
                return null;
            }
            Map<String, Object> body = parseCronOptions(fields, 3);
            body.put("name", fields[0].trim());
            body.put("schedule", fields[1].trim());
            body.put("prompt", fields[2].trim());
            return body;
        }

        CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
        if (options.positionals.isEmpty()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        String schedule = StrUtil.blankToDefault(options.schedule, options.positionals.get(0));
        String prompt = options.prompt;
        if (StrUtil.isBlank(prompt) && options.positionals.size() > 1) {
            prompt = join(options.positionals.subList(1, options.positionals.size()), " ");
        }
        putIfNotBlank(body, "name", options.name);
        putIfNotBlank(body, "schedule", schedule);
        putIfNotBlank(body, "prompt", prompt);
        appendCronFlagOptions(body, options);
        return body;
    }

    /**
     * 执行定时任务Origin相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回定时任务Origin结果。
     */
    private Map<String, Object> cronOrigin(GatewayMessage message) {
        String[] sourceParts = SourceKeySupport.split(message.sourceKey());
        Map<String, Object> origin = new LinkedHashMap<String, Object>();
        origin.put("platform", sourceParts[0]);
        origin.put("chat_id", sourceParts[1]);
        origin.put("user_id", sourceParts[2]);
        if (StrUtil.isNotBlank(message.getThreadId())) {
            origin.put("thread_id", message.getThreadId());
        }
        return origin;
    }

    /**
     * 解析定时任务Edit。
     *
     * @param tail tail 参数。
     * @return 返回解析后的定时任务Edit。
     */
    @SuppressWarnings("unchecked")
    private CronEditRequest parseCronEdit(String tail) throws Exception {
        if (StrUtil.isBlank(tail)) {
            return null;
        }
        if (tail.contains("|")) {
            String[] fields = tail.split("\\|", -1);
            if (fields.length < 2 || StrUtil.isBlank(fields[0])) {
                return null;
            }
            Map<String, Object> body = parseCronOptions(fields, 4);
            if (fields.length > 1 && StrUtil.isNotBlank(fields[1])) {
                body.put("name", fields[1].trim());
            }
            if (fields.length > 2 && StrUtil.isNotBlank(fields[2])) {
                body.put("schedule", fields[2].trim());
            }
            if (fields.length > 3 && StrUtil.isNotBlank(fields[3])) {
                body.put("prompt", fields[3].trim());
            }
            return new CronEditRequest(fields[0].trim(), body);
        }

        CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
        if (options.positionals.isEmpty()) {
            return null;
        }
        String jobId = options.positionals.get(0);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        putIfNotBlank(body, "name", options.name);
        putIfNotBlank(body, "schedule", options.schedule);
        putIfNotBlank(body, "prompt", options.prompt);
        appendCronFlagOptions(body, options);

        List<String> replacementSkills = normalizeList(options.skills);
        List<String> addSkills = normalizeList(options.addSkills);
        Set<String> removeSkills = new LinkedHashSet<String>(normalizeList(options.removeSkills));
        if (options.clearSkills) {
            body.put("skills", new ArrayList<String>());
        } else if (!replacementSkills.isEmpty()) {
            body.put("skills", replacementSkills);
        } else if (!addSkills.isEmpty() || !removeSkills.isEmpty()) {
            CronJobRecord existing = cronJobService.require(jobId);
            List<String> finalSkills = new ArrayList<String>();
            Object existingSkills = cronJobService.toView(existing).get("skills");
            if (existingSkills instanceof Iterable) {
                for (Object item : (Iterable<Object>) existingSkills) {
                    String skill = item == null ? "" : String.valueOf(item).trim();
                    if (StrUtil.isNotBlank(skill) && !removeSkills.contains(skill)) {
                        finalSkills.add(skill);
                    }
                }
            }
            for (String skill : addSkills) {
                if (!finalSkills.contains(skill)) {
                    finalSkills.add(skill);
                }
            }
            body.put("skills", finalSkills);
        }
        return new CronEditRequest(jobId, body);
    }

    /**
     * 解析定时任务Options。
     *
     * @param fields fields 参数。
     * @param start start 参数。
     * @return 返回解析后的定时任务Options。
     */
    private Map<String, Object> parseCronOptions(String[] fields, int start) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        for (int i = start; i < fields.length; i++) {
            String field = fields[i] == null ? "" : fields[i].trim();
            if (StrUtil.isBlank(field)) {
                continue;
            }
            if (field.startsWith("--skill ")) {
                body.put("skills", field.substring("--skill ".length()).trim());
            } else if (field.startsWith("--add-skill ")) {
                body.put("skills", field.substring("--add-skill ".length()).trim());
            } else if (field.startsWith("--add-skills ")) {
                body.put("skills", field.substring("--add-skills ".length()).trim());
            } else if ("--clear-skills".equals(field)) {
                body.put("skills", new ArrayList<String>());
            } else if (field.startsWith("--skills ")) {
                body.put("skills", field.substring("--skills ".length()).trim());
            } else if (field.startsWith("-s ")) {
                body.put("skills", field.substring("-s ".length()).trim());
            } else if (field.startsWith("--deliver ")) {
                body.put("deliver", field.substring("--deliver ".length()).trim());
            } else if (field.startsWith("--deliver-chat-id ")) {
                body.put("deliver_chat_id", field.substring("--deliver-chat-id ".length()).trim());
            } else if (field.startsWith("--deliver_chat_id ")) {
                body.put("deliver_chat_id", field.substring("--deliver_chat_id ".length()).trim());
            } else if ("--clear-deliver-chat-id".equals(field)
                    || "--clear-deliver_chat_id".equals(field)) {
                body.put("deliver_chat_id", null);
            } else if (field.startsWith("--deliver-thread-id ")) {
                body.put(
                        "deliver_thread_id",
                        field.substring("--deliver-thread-id ".length()).trim());
            } else if (field.startsWith("--deliver_thread_id ")) {
                body.put(
                        "deliver_thread_id",
                        field.substring("--deliver_thread_id ".length()).trim());
            } else if ("--clear-deliver-thread-id".equals(field)
                    || "--clear-deliver_thread_id".equals(field)) {
                body.put("deliver_thread_id", null);
            } else if (field.startsWith("--repeat ")) {
                body.put("repeat", Integer.valueOf(field.substring("--repeat ".length()).trim()));
            } else if ("--clear-repeat".equals(field)) {
                body.put("repeat", Integer.valueOf(0));
            } else if (field.startsWith("--script ")) {
                body.put("script", field.substring("--script ".length()).trim());
            } else if ("--clear-script".equals(field)) {
                body.put("script", null);
            } else if (field.startsWith("--workdir ")) {
                body.put("workdir", field.substring("--workdir ".length()).trim());
            } else if ("--clear-workdir".equals(field)) {
                body.put("workdir", null);
            } else if (field.startsWith("--context-from ")) {
                body.put("context_from", field.substring("--context-from ".length()).trim());
            } else if ("--clear-context-from".equals(field)) {
                body.put("context_from", new ArrayList<String>());
            } else if (field.startsWith("--depends-on ")) {
                body.put("depends_on", field.substring("--depends-on ".length()).trim());
            } else if ("--clear-depends-on".equals(field)) {
                body.put("depends_on", new ArrayList<String>());
            } else if (field.startsWith("--toolsets ")) {
                body.put("enabled_toolsets", field.substring("--toolsets ".length()).trim());
            } else if (field.startsWith("--enabled-toolsets ")) {
                body.put(
                        "enabled_toolsets", field.substring("--enabled-toolsets ".length()).trim());
            } else if ("--clear-toolsets".equals(field)
                    || "--clear-enabled-toolsets".equals(field)) {
                body.put("enabled_toolsets", new ArrayList<String>());
            } else if (field.startsWith("--model ")) {
                body.put("model", field.substring("--model ".length()).trim());
            } else if ("--clear-model".equals(field)) {
                body.put("model", null);
            } else if (field.startsWith("--provider ")) {
                body.put("provider", field.substring("--provider ".length()).trim());
            } else if ("--clear-provider".equals(field)) {
                body.put("provider", null);
            } else if (field.startsWith("--base-url ")) {
                body.put("base_url", field.substring("--base-url ".length()).trim());
            } else if (field.startsWith("--base_url ")) {
                body.put("base_url", field.substring("--base_url ".length()).trim());
            } else if ("--clear-base-url".equals(field) || "--clear-base_url".equals(field)) {
                body.put("base_url", null);
            } else if ("--no-agent".equals(field)) {
                body.put("no_agent", Boolean.TRUE);
            } else if ("--agent".equals(field)) {
                body.put("no_agent", Boolean.FALSE);
            } else if ("--wrap-response".equals(field) || "--wrap".equals(field)) {
                body.put("wrap_response", Boolean.TRUE);
            } else if ("--raw".equals(field) || "--no-wrap".equals(field)) {
                body.put("wrap_response", Boolean.FALSE);
            } else if ("--no-wrap-response".equals(field)) {
                body.put("wrap_response", Boolean.FALSE);
            } else if (field.startsWith("--status ")) {
                body.put("status", field.substring("--status ".length()).trim());
            } else if (field.startsWith("--state ")) {
                body.put("state", field.substring("--state ".length()).trim());
            } else if (field.startsWith("--paused-reason ")) {
                body.put("paused_reason", field.substring("--paused-reason ".length()).trim());
            } else if (field.startsWith("--paused_reason ")) {
                body.put("paused_reason", field.substring("--paused_reason ".length()).trim());
            }
        }
        return body;
    }

    /**
     * 追加定时任务Flag Options。
     *
     * @param body 请求体或消息正文内容。
     * @param options options 参数。
     */
    private void appendCronFlagOptions(Map<String, Object> body, CronFlagOptions options) {
        putIfNotBlank(body, "deliver", options.deliver);
        putCronStringOption(body, "deliver_chat_id", options.deliverChatId);
        putCronStringOption(body, "deliver_thread_id", options.deliverThreadId);
        if (options.clearDeliverChatId) {
            body.put("deliver_chat_id", null);
        }
        if (options.clearDeliverThreadId) {
            body.put("deliver_thread_id", null);
        }
        if (options.repeat != null) {
            body.put("repeat", options.repeat);
        }
        if (options.clearRepeat) {
            body.put("repeat", Integer.valueOf(0));
        }
        putCronStringOption(body, "script", options.script);
        putCronStringOption(body, "workdir", options.workdir);
        putIfNotBlank(body, "context_from", options.contextFrom);
        putIfNotBlank(body, "depends_on", options.dependsOn);
        putIfNotBlank(body, "enabled_toolsets", options.enabledToolsets);
        putIfNotBlank(body, "model", options.model);
        putIfNotBlank(body, "provider", options.provider);
        putIfNotBlank(body, "base_url", options.baseUrl);
        putIfNotBlank(body, "status", options.status);
        putIfNotBlank(body, "state", options.state);
        putIfNotBlank(body, "paused_reason", options.pausedReason);
        if (options.clearModel) {
            body.put("model", null);
        }
        if (options.clearProvider) {
            body.put("provider", null);
        }
        if (options.clearBaseUrl) {
            body.put("base_url", null);
        }
        if (options.clearScript) {
            body.put("script", null);
        }
        if (options.clearWorkdir) {
            body.put("workdir", null);
        }
        if (options.clearContextFrom) {
            body.put("context_from", new ArrayList<String>());
        }
        if (options.clearDependsOn) {
            body.put("depends_on", new ArrayList<String>());
        }
        if (options.clearToolsets) {
            body.put("enabled_toolsets", new ArrayList<String>());
        }
        if (options.noAgent) {
            body.put("no_agent", Boolean.TRUE);
        }
        if (options.agent) {
            body.put("no_agent", Boolean.FALSE);
        }
        if (options.wrapResponse) {
            body.put("wrap_response", Boolean.TRUE);
        }
        if (options.raw) {
            body.put("wrap_response", Boolean.FALSE);
        }
        if (!options.skills.isEmpty()) {
            body.put("skills", normalizeList(options.skills));
        }
    }

    /**
     * 解析定时任务Flags。
     *
     * @param tokens token参数。
     * @return 返回解析后的定时任务Flags。
     */
    private CronFlagOptions parseCronFlags(List<String> tokens) {
        CronFlagOptions options = new CronFlagOptions();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("--name".equals(token) && i + 1 < tokens.size()) {
                options.name = tokens.get(++i);
            } else if ("--deliver".equals(token) && i + 1 < tokens.size()) {
                options.deliver = tokens.get(++i);
            } else if (("--deliver-chat-id".equals(token) || "--deliver_chat_id".equals(token))
                    && i + 1 < tokens.size()) {
                options.deliverChatId = tokens.get(++i);
            } else if ("--clear-deliver-chat-id".equals(token)
                    || "--clear-deliver_chat_id".equals(token)) {
                options.clearDeliverChatId = true;
            } else if (("--deliver-thread-id".equals(token) || "--deliver_thread_id".equals(token))
                    && i + 1 < tokens.size()) {
                options.deliverThreadId = tokens.get(++i);
            } else if ("--clear-deliver-thread-id".equals(token)
                    || "--clear-deliver_thread_id".equals(token)) {
                options.clearDeliverThreadId = true;
            } else if ("--repeat".equals(token) && i + 1 < tokens.size()) {
                options.repeat = Integer.valueOf(tokens.get(++i));
            } else if ("--clear-repeat".equals(token)) {
                options.clearRepeat = true;
            } else if ("--limit".equals(token) && i + 1 < tokens.size()) {
                options.limit = Integer.valueOf(tokens.get(++i));
            } else if ("--reason".equals(token) && i + 1 < tokens.size()) {
                options.reason = tokens.get(++i);
            } else if (("--trigger-type".equals(token) || "--trigger_type".equals(token))
                    && i + 1 < tokens.size()) {
                options.triggerType = tokens.get(++i);
            } else if (("--skill".equals(token) || "-s".equals(token)) && i + 1 < tokens.size()) {
                options.skills.add(tokens.get(++i));
            } else if ("--skills".equals(token) && i + 1 < tokens.size()) {
                options.skills.add(tokens.get(++i));
            } else if (("--add-skill".equals(token) || "--add-skills".equals(token))
                    && i + 1 < tokens.size()) {
                options.addSkills.add(tokens.get(++i));
            } else if (("--remove-skill".equals(token) || "--remove-skills".equals(token))
                    && i + 1 < tokens.size()) {
                options.removeSkills.add(tokens.get(++i));
            } else if ("--clear-skills".equals(token)) {
                options.clearSkills = true;
            } else if ("--all".equals(token)) {
                options.all = true;
            } else if ("--prompt".equals(token) && i + 1 < tokens.size()) {
                options.prompt = tokens.get(++i);
            } else if ("--schedule".equals(token) && i + 1 < tokens.size()) {
                options.schedule = tokens.get(++i);
            } else if ("--script".equals(token) && i + 1 < tokens.size()) {
                options.script = tokens.get(++i);
            } else if ("--clear-script".equals(token)) {
                options.clearScript = true;
            } else if ("--workdir".equals(token) && i + 1 < tokens.size()) {
                options.workdir = tokens.get(++i);
            } else if ("--clear-workdir".equals(token)) {
                options.clearWorkdir = true;
            } else if ("--context-from".equals(token) && i + 1 < tokens.size()) {
                options.contextFrom = tokens.get(++i);
            } else if ("--clear-context-from".equals(token)) {
                options.clearContextFrom = true;
            } else if ("--depends-on".equals(token) && i + 1 < tokens.size()) {
                options.dependsOn = tokens.get(++i);
            } else if ("--clear-depends-on".equals(token)) {
                options.clearDependsOn = true;
            } else if (("--toolsets".equals(token) || "--enabled-toolsets".equals(token))
                    && i + 1 < tokens.size()) {
                options.enabledToolsets = tokens.get(++i);
            } else if ("--clear-toolsets".equals(token)
                    || "--clear-enabled-toolsets".equals(token)) {
                options.clearToolsets = true;
            } else if ("--model".equals(token) && i + 1 < tokens.size()) {
                options.model = tokens.get(++i);
            } else if ("--clear-model".equals(token)) {
                options.clearModel = true;
            } else if ("--provider".equals(token) && i + 1 < tokens.size()) {
                options.provider = tokens.get(++i);
            } else if ("--clear-provider".equals(token)) {
                options.clearProvider = true;
            } else if (("--base-url".equals(token) || "--base_url".equals(token))
                    && i + 1 < tokens.size()) {
                options.baseUrl = tokens.get(++i);
            } else if ("--clear-base-url".equals(token) || "--clear-base_url".equals(token)) {
                options.clearBaseUrl = true;
            } else if ("--status".equals(token) && i + 1 < tokens.size()) {
                options.status = tokens.get(++i);
            } else if ("--state".equals(token) && i + 1 < tokens.size()) {
                options.state = tokens.get(++i);
            } else if (("--paused-reason".equals(token) || "--paused_reason".equals(token))
                    && i + 1 < tokens.size()) {
                options.pausedReason = tokens.get(++i);
            } else if ("--no-agent".equals(token)) {
                options.noAgent = true;
            } else if ("--agent".equals(token)) {
                options.agent = true;
            } else if ("--wrap-response".equals(token) || "--wrap".equals(token)) {
                options.wrapResponse = true;
            } else if ("--raw".equals(token)
                    || "--no-wrap".equals(token)
                    || "--no-wrap-response".equals(token)) {
                options.raw = true;
            } else if ("--json".equals(token)) {
                options.json = true;
            } else {
                options.positionals.add(token);
            }
        }
        return options;
    }

    /**
     * 拆分命令Line。
     *
     * @param raw 原始输入值。
     * @return 返回命令Line结果。
     */
    private List<String> splitCommandLine(String raw) {
        return SlashCommandTextSupport.splitCommandLine(raw);
    }

    /**
     * 规范化List。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回List结果。
     */
    private List<String> normalizeList(List<String> values) {
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            for (String part : StrUtil.nullToEmpty(value).split(",")) {
                String text = part.trim();
                if (StrUtil.isNotBlank(text) && !result.contains(text)) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    /**
     * 执行join相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param delimiter delimiter 参数。
     * @return 返回join结果。
     */
    private String join(List<String> values, String delimiter) {
        StringBuilder buffer = new StringBuilder();
        for (String value : values) {
            if (buffer.length() > 0) {
                buffer.append(delimiter);
            }
            buffer.append(value);
        }
        return buffer.toString();
    }

    /**
     * 执行joinTail相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param start start 参数。
     * @return 返回join Tail结果。
     */
    private String joinTail(List<String> values, int start) {
        if (values == null || start >= values.size()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = start; i < values.size(); i++) {
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(values.get(i));
        }
        return buffer.toString();
    }

    /**
     * 写入If Not Blank。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private void putIfNotBlank(Map<String, Object> body, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            body.put(key, value.trim());
        }
    }

    /**
     * 写入定时任务String Option。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private void putCronStringOption(Map<String, Object> body, String key, String value) {
        if (value != null) {
            body.put(key, value.trim());
        }
    }


}
