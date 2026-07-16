package com.jimuqu.solon.claw.profile.task;

import com.jimuqu.solon.claw.core.repository.ProfileTaskRepository;

/** 命名 Profile 子容器向 root 唯一任务控制面提交任务的窄桥，不暴露调度器或数据库。 */
public final class ProfileTaskSubmissionBridge {
    /** root 任务仓储。 */
    private static volatile ProfileTaskRepository repository;

    /** 禁止实例化。 */
    private ProfileTaskSubmissionBridge() {}

    /** root 启动时安装共享提交端。 */
    public static void install(ProfileTaskRepository value) {
        repository = value;
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
