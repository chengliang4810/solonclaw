package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.model.ProfileTaskAttemptRecord;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import java.util.List;

/** default 控制面中的跨 Profile 协作任务仓储。 */
public interface ProfileTaskRepository {
    /** 保存新任务。 */
    ProfileTaskRecord save(ProfileTaskRecord task) throws Exception;

    /** 查询任务。 */
    ProfileTaskRecord findById(String taskId) throws Exception;

    /** 列出全部任务。 */
    List<ProfileTaskRecord> listAll() throws Exception;

    /** 查询目标智能体的任务。 */
    List<ProfileTaskRecord> listByTargetProfile(String targetProfile) throws Exception;

    /** 判断智能体是否仍有不可删除的未结束协作任务。 */
    boolean hasActiveTasks(String profile) throws Exception;

    /** 原子认领一个依赖已完成、目标 Profile 当前空闲的任务。 */
    ProfileTaskRecord claimNext(int globalConcurrency) throws Exception;

    /** 完成当前执行并更新任务状态。 */
    boolean finishAttempt(
            String taskId, String executionToken, String status, String result, String error)
            throws Exception;

    /** 由原分配者修改描述后显式放回队列；禁止机械自动重试。 */
    boolean retry(String taskId, String prompt) throws Exception;

    /** 取消未结束任务。 */
    boolean cancel(String taskId) throws Exception;

    /** 把进程遗留的运行中任务标记为中断。 */
    int interruptRunning(String reason) throws Exception;

    /** 查询任务执行历史。 */
    List<ProfileTaskAttemptRecord> listAttempts(String taskId) throws Exception;
}
