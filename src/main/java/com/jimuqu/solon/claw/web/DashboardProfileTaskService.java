package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProfileTaskAttemptRecord;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import com.jimuqu.solon.claw.core.repository.ProfileTaskRepository;
import com.jimuqu.solon.claw.profile.task.ProfileTaskCoordinator;
import com.jimuqu.solon.claw.support.IdSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dashboard 协作任务应用服务。 */
public class DashboardProfileTaskService {
    /** 共享任务仓储。 */
    private final ProfileTaskRepository repository;

    /** 协作任务执行协调器，统一线性化取消状态与运行调用。 */
    private final ProfileTaskCoordinator coordinator;

    /** 协作任务默认值和上限。 */
    private final AppConfig.TaskConfig config;

    /** 创建应用服务。 */
    public DashboardProfileTaskService(
            ProfileTaskRepository repository,
            ProfileTaskCoordinator coordinator,
            AppConfig appConfig) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.config = appConfig.getTask();
    }

    /** 创建协作任务。 */
    public ProfileTaskRecord create(Map<String, Object> body) throws Exception {
        ProfileTaskRecord task = new ProfileTaskRecord();
        task.setTaskId("pt-" + IdSupport.newId());
        task.setSourceProfile(text(body, "source_profile", "default"));
        task.setTargetProfile(text(body, "target_profile", null));
        task.setSourceKey("MEMORY:dashboard:dashboard");
        task.setTitle(text(body, "title", "Dashboard collaboration task"));
        task.setPrompt(text(body, "description", null));
        task.setMaxAttempts(
                Math.min(
                        number(body.get("maxAttempts"), config.getProfileTaskMaxAttempts()),
                        config.getProfileTaskMaxAttempts()));
        task.setTimeoutMinutes(
                Math.min(
                        number(
                                body.get("timeout_minutes"),
                                config.getProfileTaskDefaultTimeoutMinutes()),
                        config.getProfileTaskMaxTimeoutMinutes()));
        Object dependencies = body.get("depends_on");
        if (dependencies instanceof List) {
            List<String> ids = new ArrayList<String>();
            for (Object value : (List<?>) dependencies) {
                if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                    ids.add(String.valueOf(value).trim());
                }
            }
            task.setDependencyIds(ids);
        }
        return repository.save(task);
    }

    /** 列出全部任务。 */
    public List<ProfileTaskRecord> list() throws Exception {
        return repository.listAll();
    }

    /** 按执行智能体列出任务；空值表示全部。 */
    public List<ProfileTaskRecord> list(String assignee) throws Exception {
        return StrUtil.isBlank(assignee)
                ? repository.listAll()
                : repository.listByTargetProfile(assignee.trim());
    }

    /** 查询任务与执行历史。 */
    public TaskDetail get(String taskId) throws Exception {
        ProfileTaskRecord task = repository.findById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Profile task does not exist: " + taskId);
        }
        return new TaskDetail(task, repository.listAttempts(taskId));
    }

    /** 由分配者修改描述后显式重试。 */
    public TaskDetail retry(String taskId, Map<String, Object> body) throws Exception {
        String prompt = text(body, "prompt", null);
        if (!repository.retry(taskId, prompt)) {
            throw new IllegalStateException("Profile task cannot be retried in its current state");
        }
        return get(taskId);
    }

    /** 取消任务。 */
    public TaskDetail cancel(String taskId) throws Exception {
        if (!coordinator.cancelTask(taskId)) {
            throw new IllegalStateException(
                    "Profile task cannot be cancelled in its current state");
        }
        return get(taskId);
    }

    /** 读取必填或默认文本。 */
    private static String text(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null || StrUtil.isBlank(String.valueOf(value))
                ? fallback
                : String.valueOf(value).trim();
    }

    /** 读取整数。 */
    private static int number(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Profile task numeric field is invalid");
        }
    }

    /** 任务和不可覆盖的 attempt 历史。 */
    public static final class TaskDetail {
        /** 任务。 */
        private final ProfileTaskRecord task;

        /** 执行历史。 */
        private final List<ProfileTaskAttemptRecord> attempts;

        /** 创建详情。 */
        TaskDetail(ProfileTaskRecord task, List<ProfileTaskAttemptRecord> attempts) {
            this.task = task;
            this.attempts = attempts;
        }

        /**
         * @return 任务。
         */
        public ProfileTaskRecord getTask() {
            return task;
        }

        /**
         * @return 执行历史。
         */
        public List<ProfileTaskAttemptRecord> getAttempts() {
            return attempts;
        }
    }
}
