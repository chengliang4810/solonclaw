package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.List;
import java.util.logging.Logger;

/** 承载定时任务审批ResumeObserver相关状态和辅助逻辑。 */
public class CronApprovalResumeObserver
        implements DangerousCommandApprovalService.ApprovalObserver {
    /** 定时任务审批恢复观察器的低敏日志记录器。 */
    private static final Logger LOG = Logger.getLogger(CronApprovalResumeObserver.class.getName());

    /** 定时任务任务PREFIX的统一常量值。 */
    private static final String CRON_JOB_PREFIX = "cron-job:";

    /** 审批PAUSEPREFIX的统一常量值。 */
    private static final String APPROVAL_PAUSE_PREFIX = "waiting for approval:";

    /** 注入定时任务任务服务，用于调用对应业务能力。 */
    private final CronJobService cronJobService;

    /**
     * 创建定时任务审批Resume Observer实例，并注入运行所需依赖。
     *
     * @param cronJobService 定时任务Job服务依赖。
     */
    public CronApprovalResumeObserver(CronJobService cronJobService) {
        this.cronJobService = cronJobService;
    }

    /**
     * 响应审批请求事件。
     *
     * @param event 事件参数。
     */
    @Override
    public void onApprovalRequest(DangerousCommandApprovalService.ApprovalRequestEvent event) {
        // 保留此处实现约束，避免后续维护时破坏既有行为。
    }

    /**
     * 响应审批响应事件。
     *
     * @param event 事件参数。
     */
    @Override
    public void onApprovalResponse(DangerousCommandApprovalService.ApprovalResponseEvent event) {
        if (cronJobService == null || event == null || !event.isApproved()) {
            return;
        }
        String jobId = cronJobId(event.getPatternKeys());
        if (StrUtil.isBlank(jobId)) {
            return;
        }
        try {
            CronJobRecord job = cronJobService.require(jobId);
            if (job == null
                    || !"PAUSED".equalsIgnoreCase(StrUtil.nullToEmpty(job.getStatus()))
                    || !StrUtil.startWith(
                            StrUtil.nullToEmpty(job.getPausedReason()), APPROVAL_PAUSE_PREFIX)) {
                return;
            }
            cronJobService.resume(jobId);
        } catch (Exception e) {
            LOG.fine(
                    "定时任务审批恢复失败，已保持审批主流程继续：jobId="
                            + jobId
                            + ", errorType="
                            + e.getClass().getSimpleName());
        }
    }

    /**
     * 执行定时任务任务标识相关逻辑。
     *
     * @param patternKeys patternKeys 参数。
     * @return 返回定时任务任务标识。
     */
    private String cronJobId(List<String> patternKeys) {
        if (patternKeys == null) {
            return "";
        }
        for (String patternKey : patternKeys) {
            String value = StrUtil.nullToEmpty(patternKey).trim();
            if (!value.startsWith(CRON_JOB_PREFIX)) {
                continue;
            }
            int start = CRON_JOB_PREFIX.length();
            int end = value.indexOf(':', start);
            if (end > start) {
                return value.substring(start, end);
            }
        }
        return "";
    }
}
