package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import java.util.List;

/** 定时任务仓储接口。 */
public interface CronJobRepository {
    /** 保存定时任务。 */
    CronJobRecord save(CronJobRecord job) throws Exception;

    /** 通过任务 ID 查询任务。 */
    CronJobRecord findById(String jobId) throws Exception;

    /** 列出来源键下的全部任务。 */
    List<CronJobRecord> listBySource(String sourceKey) throws Exception;

    /** 列出全部任务。 */
    List<CronJobRecord> listAll() throws Exception;

    /** 列出已到执行时间的任务。 */
    List<CronJobRecord> listDue(long nowEpochMillis) throws Exception;

    /** 删除指定任务。 */
    void delete(String jobId) throws Exception;

    /** 更新任务状态。 */
    void updateStatus(String jobId, String status) throws Exception;

    /** 更新完整任务记录。 */
    CronJobRecord update(CronJobRecord job) throws Exception;

    /** 记录任务运行结果时间。 */
    void markRun(String jobId, long lastRunAt, long nextRunAt) throws Exception;

    /** 记录任务运行结果。 */
    void markRunResult(
            String jobId,
            long lastRunAt,
            long nextRunAt,
            String status,
            String error,
            String output,
            int repeatCompleted,
            String nextStatus)
            throws Exception;

    /** 记录投递错误。 */
    void markDeliveryError(String jobId, String error) throws Exception;

    /** 保存任务执行历史。 */
    CronJobRunRecord saveRun(CronJobRunRecord run) throws Exception;

    /** 查询任务执行历史。 */
    List<CronJobRunRecord> listRuns(String jobId, int limit) throws Exception;
}
