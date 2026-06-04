package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.List;

/** Resumes cron jobs that were paused only to wait for guardrail approval. */
public class CronApprovalResumeObserver
        implements DangerousCommandApprovalService.ApprovalObserver {
    private static final String CRON_JOB_PREFIX = "cron-job:";
    private static final String APPROVAL_PAUSE_PREFIX = "waiting for approval:";

    private final CronJobService cronJobService;

    public CronApprovalResumeObserver(CronJobService cronJobService) {
        this.cronJobService = cronJobService;
    }

    @Override
    public void onApprovalRequest(DangerousCommandApprovalService.ApprovalRequestEvent event) {
        // No-op: the scheduler pauses the job when it creates the approval request.
    }

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
        } catch (Exception ignored) {
            // Approval handling must not fail because a best-effort cron resume failed.
        }
    }

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
