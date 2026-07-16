package com.jimuqu.solon.claw.profile.task;

import com.jimuqu.solon.claw.core.repository.ProfileTaskRepository;

/** 命名 Profile 子容器向 root 唯一任务控制面提交任务的窄桥，不暴露调度器或数据库。 */
public final class ProfileTaskSubmissionBridge {
    /** root 任务仓储。 */
    private static volatile ProfileTaskRepository repository;

    /** root 协作任务调度器。 */
    private static volatile ProfileTaskCoordinator coordinator;

    /** 禁止实例化。 */
    private ProfileTaskSubmissionBridge() {}

    /** root 启动时安装共享提交端。 */
    public static void install(ProfileTaskRepository value) {
        repository = value;
    }

    /** root 启动时安装执行控制端。 */
    public static void installCoordinator(ProfileTaskCoordinator value) {
        coordinator = value;
    }

    /** 主动取消当前 JVM 中的模型调用；任务状态仍由仓储 CAS 控制。 */
    public static void cancelExecution(String taskId) {
        ProfileTaskCoordinator value = coordinator;
        if (value != null) {
            value.cancelExecution(taskId);
        }
    }

    /**
     * @return 已安装的 root 提交端。
     */
    public static ProfileTaskRepository require() {
        ProfileTaskRepository value = repository;
        if (value == null) {
            throw new IllegalStateException("Profile task control plane is not ready");
        }
        return value;
    }
}
