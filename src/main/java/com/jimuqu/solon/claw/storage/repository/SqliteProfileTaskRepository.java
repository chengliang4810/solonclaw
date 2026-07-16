package com.jimuqu.solon.claw.storage.repository;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ProfileTaskAttemptRecord;
import com.jimuqu.solon.claw.core.model.ProfileTaskRecord;
import com.jimuqu.solon.claw.core.repository.ProfileTaskRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.noear.snack4.ONode;

/** 使用 default Profile SQLite 保存并原子调度跨 Profile 协作任务。 */
public class SqliteProfileTaskRepository extends SqliteRepositorySupport
        implements ProfileTaskRepository {
    /** default Profile 数据库。 */
    private final SqliteDatabase database;

    /** 创建共享任务仓储。 */
    public SqliteProfileTaskRepository(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    protected Connection getConnection() throws SQLException {
        return database.openConnection();
    }

    /** 保存经过信任边界校验的新任务。 */
    @Override
    public ProfileTaskRecord save(final ProfileTaskRecord task) throws Exception {
        validateNewTask(task);
        return inTransaction(
                connection -> {
                    for (String dependencyId : task.getDependencyIds()) {
                        if (task.getTaskId().equals(dependencyId)) {
                            throw new IllegalArgumentException(
                                    "Profile task cannot depend on itself");
                        }
                        if (find(connection, dependencyId) == null) {
                            throw new IllegalArgumentException(
                                    "Profile task dependency does not exist: " + dependencyId);
                        }
                    }
                    long now = System.currentTimeMillis();
                    task.setStatus(dependenciesCompleted(connection, task) ? "READY" : "PENDING");
                    task.setAttemptCount(0);
                    task.setExecutionToken(null);
                    task.setCreatedAt(now);
                    task.setUpdatedAt(now);
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "insert into profile_tasks(task_id, source_profile, target_profile, source_key, title, prompt, status, dependency_ids_json, attempt_count, max_attempts, timeout_minutes, created_at, updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        bindTask(statement, task);
                        statement.executeUpdate();
                    }
                    return task;
                });
    }

    /** 查询单个任务。 */
    @Override
    public ProfileTaskRecord findById(String taskId) throws Exception {
        return queryOne(
                "select * from profile_tasks where task_id = ?",
                statement -> statement.setString(1, taskId),
                this::mapTask);
    }

    /** 按创建顺序列出任务。 */
    @Override
    public List<ProfileTaskRecord> listAll() throws Exception {
        return queryList(
                "select * from profile_tasks order by created_at desc", null, this::mapTask);
    }

    /** 按目标 Profile 查询任务。 */
    @Override
    public List<ProfileTaskRecord> listByTargetProfile(String targetProfile) throws Exception {
        return queryList(
                "select * from profile_tasks where target_profile=? order by created_at desc",
                statement -> statement.setString(1, targetProfile),
                this::mapTask);
    }

    /** 检查 Profile 作为来源或目标时是否仍有待处理任务。 */
    @Override
    public boolean hasActiveTasks(String profile) throws Exception {
        Integer count =
                queryOne(
                        "select count(*) as active_count from profile_tasks where (source_profile=? or target_profile=?) and status in ('PENDING','READY','RUNNING')",
                        statement -> {
                            statement.setString(1, profile);
                            statement.setString(2, profile);
                        },
                        result -> Integer.valueOf(result.getInt("active_count")));
        return count != null && count.intValue() > 0;
    }

    /** 原子认领一个任务，同时强制全局并发和每目标 Profile 并发均不超限。 */
    @Override
    public ProfileTaskRecord claimNext(final int globalConcurrency) throws Exception {
        return inTransaction(
                connection -> {
                    if (countRunning(connection, null) >= Math.max(1, globalConcurrency)) {
                        return null;
                    }
                    List<ProfileTaskRecord> candidates = listDispatchable(connection);
                    for (ProfileTaskRecord task : candidates) {
                        if (countRunning(connection, task.getTargetProfile()) > 0
                                || !dependenciesCompleted(connection, task)) {
                            continue;
                        }
                        String token = "ptx-" + IdSupport.newId();
                        long now = System.currentTimeMillis();
                        int attempt = task.getAttemptCount() + 1;
                        try (PreparedStatement update =
                                connection.prepareStatement(
                                        "update profile_tasks set status='RUNNING', attempt_count=?, execution_token=?, updated_at=? where task_id=? and status in ('READY','PENDING') and attempt_count < max_attempts")) {
                            update.setInt(1, attempt);
                            update.setString(2, token);
                            update.setLong(3, now);
                            update.setString(4, task.getTaskId());
                            if (update.executeUpdate() != 1) {
                                continue;
                            }
                        }
                        try (PreparedStatement insert =
                                connection.prepareStatement(
                                        "insert into profile_task_attempts(task_id, attempt, execution_token, prompt, status, started_at) values(?,?,?,?, 'RUNNING',?)")) {
                            insert.setString(1, task.getTaskId());
                            insert.setInt(2, attempt);
                            insert.setString(3, token);
                            insert.setString(4, task.getPrompt());
                            insert.setLong(5, now);
                            insert.executeUpdate();
                        }
                        task.setStatus("RUNNING");
                        task.setAttemptCount(attempt);
                        task.setExecutionToken(token);
                        task.setUpdatedAt(now);
                        return task;
                    }
                    return null;
                });
    }

    /** 仅持有当前执行令牌的运行者可以落最终结果。 */
    @Override
    public boolean finishAttempt(
            final String taskId,
            final String executionToken,
            final String status,
            final String result,
            final String error)
            throws Exception {
        if (!isAttemptTerminal(status)) {
            throw new IllegalArgumentException("Invalid Profile task attempt status: " + status);
        }
        return inTransaction(
                connection -> {
                    long now = System.currentTimeMillis();
                    ProfileTaskRecord current = find(connection, taskId);
                    if (current == null
                            || !"RUNNING".equals(current.getStatus())
                            || !executionToken.equals(current.getExecutionToken())) {
                        return false;
                    }
                    String taskStatus =
                            !"COMPLETED".equals(status)
                                            && current.getAttemptCount() >= current.getMaxAttempts()
                                    ? "BLOCKED"
                                    : status;
                    int updated;
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "update profile_tasks set status=?, result=?, error=?, execution_token=null, updated_at=? where task_id=? and status='RUNNING' and execution_token=?")) {
                        statement.setString(1, taskStatus);
                        statement.setString(2, result);
                        statement.setString(3, error);
                        statement.setLong(4, now);
                        statement.setString(5, taskId);
                        statement.setString(6, executionToken);
                        updated = statement.executeUpdate();
                    }
                    if (updated == 1) {
                        try (PreparedStatement statement =
                                connection.prepareStatement(
                                        "update profile_task_attempts set status=?, result=?, error=?, completed_at=? where task_id=? and execution_token=? and status='RUNNING'")) {
                            statement.setString(1, status);
                            statement.setString(2, result);
                            statement.setString(3, error);
                            statement.setLong(4, now);
                            statement.setString(5, taskId);
                            statement.setString(6, executionToken);
                            statement.executeUpdate();
                        }
                        if ("COMPLETED".equals(taskStatus)) {
                            refreshDependents(connection, taskId);
                        } else {
                            blockDependents(connection, taskId);
                        }
                    }
                    return updated == 1;
                });
    }

    /** 失败后只接受原分配者显式发起的重试，不在仓储内自动重试。 */
    @Override
    public boolean retry(final String taskId, final String prompt) throws Exception {
        if (StrUtil.isBlank(prompt)) {
            throw new IllegalArgumentException("Profile task retry prompt is required");
        }
        return inTransaction(
                connection -> {
                    ProfileTaskRecord task = find(connection, taskId);
                    if (task == null
                            || !("FAILED".equals(task.getStatus())
                                    || "TIMED_OUT".equals(task.getStatus())
                                    || "INTERRUPTED".equals(task.getStatus()))
                            || task.getAttemptCount() >= task.getMaxAttempts()) {
                        return false;
                    }
                    String next = dependenciesCompleted(connection, task) ? "READY" : "PENDING";
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "update profile_tasks set prompt=?, status=?, result=null, error=null, updated_at=? where task_id=? and status=?")) {
                        statement.setString(1, prompt.trim());
                        statement.setString(2, next);
                        statement.setLong(3, System.currentTimeMillis());
                        statement.setString(4, taskId);
                        statement.setString(5, task.getStatus());
                        return statement.executeUpdate() == 1;
                    }
                });
    }

    /** 取消未完成任务；运行中的迟到结果会被执行令牌 CAS 拒绝。 */
    @Override
    public boolean cancel(String taskId) throws Exception {
        return inTransaction(
                connection -> {
                    ProfileTaskRecord task = find(connection, taskId);
                    if (task == null
                            || "COMPLETED".equals(task.getStatus())
                            || "CANCELLED".equals(task.getStatus())) {
                        return false;
                    }
                    long now = System.currentTimeMillis();
                    try (PreparedStatement attempts =
                            connection.prepareStatement(
                                    "update profile_task_attempts set status='CANCELLED', completed_at=? where task_id=? and status='RUNNING'")) {
                        attempts.setLong(1, now);
                        attempts.setString(2, taskId);
                        attempts.executeUpdate();
                    }
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "update profile_tasks set status='CANCELLED', execution_token=null, updated_at=? where task_id=?")) {
                        statement.setLong(1, now);
                        statement.setString(2, taskId);
                        statement.executeUpdate();
                    }
                    blockDependents(connection, taskId);
                    return true;
                });
    }

    /** 启动时中断遗留运行，保留 attempt 并等待原分配者决定是否重试。 */
    @Override
    public int interruptRunning(final String reason) throws Exception {
        return inTransaction(
                connection -> {
                    long now = System.currentTimeMillis();
                    try (PreparedStatement attempts =
                            connection.prepareStatement(
                                    "update profile_task_attempts set status='INTERRUPTED', error=?, completed_at=? where status='RUNNING'")) {
                        attempts.setString(1, reason);
                        attempts.setLong(2, now);
                        attempts.executeUpdate();
                    }
                    try (PreparedStatement tasks =
                            connection.prepareStatement(
                                    "update profile_tasks set status='INTERRUPTED', error=?, execution_token=null, updated_at=? where status='RUNNING'")) {
                        tasks.setString(1, reason);
                        tasks.setLong(2, now);
                        return tasks.executeUpdate();
                    }
                });
    }

    /** 查询不可覆盖的执行历史。 */
    @Override
    public List<ProfileTaskAttemptRecord> listAttempts(String taskId) throws Exception {
        return queryList(
                "select task_id, attempt, prompt, status, result, error, started_at, completed_at from profile_task_attempts where task_id=? order by attempt",
                statement -> statement.setString(1, taskId),
                result -> {
                    ProfileTaskAttemptRecord attempt = new ProfileTaskAttemptRecord();
                    attempt.setTaskId(result.getString("task_id"));
                    attempt.setAttempt(result.getInt("attempt"));
                    attempt.setPrompt(result.getString("prompt"));
                    attempt.setStatus(result.getString("status"));
                    attempt.setResult(result.getString("result"));
                    attempt.setError(result.getString("error"));
                    attempt.setStartedAt(result.getLong("started_at"));
                    attempt.setCompletedAt(result.getLong("completed_at"));
                    return attempt;
                });
    }

    /** 校验创建边界。 */
    private static void validateNewTask(ProfileTaskRecord task) {
        if (task == null
                || StrUtil.isBlank(task.getTaskId())
                || StrUtil.isBlank(task.getSourceProfile())
                || StrUtil.isBlank(task.getTargetProfile())
                || StrUtil.isBlank(task.getSourceKey())
                || StrUtil.isBlank(task.getTitle())
                || StrUtil.isBlank(task.getPrompt())) {
            throw new IllegalArgumentException("Profile task required fields are missing");
        }
        if ("default".equalsIgnoreCase(task.getTargetProfile().trim())) {
            throw new IllegalArgumentException(
                    "default Profile cannot receive collaboration tasks");
        }
        task.setMaxAttempts(task.getMaxAttempts() <= 0 ? 5 : task.getMaxAttempts());
        int timeout = task.getTimeoutMinutes() <= 0 ? 30 : task.getTimeoutMinutes();
        if (timeout > 240) {
            throw new IllegalArgumentException("Profile task timeout cannot exceed 240 minutes");
        }
        task.setTimeoutMinutes(timeout);
        if (task.getDependencyIds() == null) {
            task.setDependencyIds(new ArrayList<String>());
        }
    }

    /** 写入新任务参数。 */
    private static void bindTask(PreparedStatement statement, ProfileTaskRecord task)
            throws SQLException {
        statement.setString(1, task.getTaskId());
        statement.setString(2, task.getSourceProfile().trim());
        statement.setString(3, task.getTargetProfile().trim());
        statement.setString(4, task.getSourceKey().trim());
        statement.setString(5, task.getTitle().trim());
        statement.setString(6, task.getPrompt().trim());
        statement.setString(7, task.getStatus());
        statement.setString(8, ONode.serialize(task.getDependencyIds()));
        statement.setInt(9, task.getAttemptCount());
        statement.setInt(10, task.getMaxAttempts());
        statement.setInt(11, task.getTimeoutMinutes());
        statement.setLong(12, task.getCreatedAt());
        statement.setLong(13, task.getUpdatedAt());
    }

    /** 映射任务。 */
    @SuppressWarnings("unchecked")
    private ProfileTaskRecord mapTask(ResultSet result) throws SQLException {
        ProfileTaskRecord task = new ProfileTaskRecord();
        task.setTaskId(result.getString("task_id"));
        task.setSourceProfile(result.getString("source_profile"));
        task.setTargetProfile(result.getString("target_profile"));
        task.setSourceKey(result.getString("source_key"));
        task.setTitle(result.getString("title"));
        task.setPrompt(result.getString("prompt"));
        task.setStatus(result.getString("status"));
        List<Object> raw = ONode.deserialize(result.getString("dependency_ids_json"), List.class);
        List<String> dependencies = new ArrayList<String>();
        if (raw != null) {
            for (Object value : raw) {
                dependencies.add(String.valueOf(value));
            }
        }
        task.setDependencyIds(dependencies);
        task.setAttemptCount(result.getInt("attempt_count"));
        task.setMaxAttempts(result.getInt("max_attempts"));
        task.setTimeoutMinutes(result.getInt("timeout_minutes"));
        task.setExecutionToken(result.getString("execution_token"));
        task.setResult(result.getString("result"));
        task.setError(result.getString("error"));
        task.setCreatedAt(result.getLong("created_at"));
        task.setUpdatedAt(result.getLong("updated_at"));
        return task;
    }

    /** 在当前事务查询任务。 */
    private ProfileTaskRecord find(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select * from profile_tasks where task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapTask(result) : null;
            }
        }
    }

    /** 查询候选任务。 */
    private List<ProfileTaskRecord> listDispatchable(Connection connection) throws SQLException {
        List<ProfileTaskRecord> tasks = new ArrayList<ProfileTaskRecord>();
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "select * from profile_tasks where status in ('READY','PENDING') and attempt_count < max_attempts order by created_at");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                tasks.add(mapTask(result));
            }
        }
        return tasks;
    }

    /** 查询可在依赖成功后恢复的等待任务。 */
    private List<ProfileTaskRecord> listRecoverableDependents(Connection connection)
            throws SQLException {
        List<ProfileTaskRecord> tasks = new ArrayList<ProfileTaskRecord>();
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "select * from profile_tasks where status in ('PENDING','BLOCKED') and attempt_count < max_attempts order by created_at");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                tasks.add(mapTask(result));
            }
        }
        return tasks;
    }

    /** 判断全部 AND 依赖均完成。 */
    private boolean dependenciesCompleted(Connection connection, ProfileTaskRecord task)
            throws SQLException {
        for (String dependencyId : task.getDependencyIds()) {
            ProfileTaskRecord dependency = find(connection, dependencyId);
            if (dependency == null || !"COMPLETED".equals(dependency.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /** 统计运行中任务。 */
    private int countRunning(Connection connection, String targetProfile) throws SQLException {
        String sql =
                targetProfile == null
                        ? "select count(*) from profile_tasks where status='RUNNING'"
                        : "select count(*) from profile_tasks where status='RUNNING' and target_profile=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (targetProfile != null) {
                statement.setString(1, targetProfile);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /** 前置任务完成时把满足 AND 依赖的等待任务提升为 READY。 */
    private void refreshDependents(Connection connection, String completedTaskId)
            throws SQLException {
        for (ProfileTaskRecord candidate : listRecoverableDependents(connection)) {
            if (candidate.getDependencyIds().contains(completedTaskId)
                    && dependenciesCompleted(connection, candidate)) {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "update profile_tasks set status='READY', error=null, updated_at=? where task_id=? and status in ('PENDING','BLOCKED')")) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setString(2, candidate.getTaskId());
                    statement.executeUpdate();
                }
            }
        }
    }

    /** 前置任务未成功结束时阻塞直接或间接依赖任务，等待分配者处理。 */
    private void blockDependents(Connection connection, String taskId) throws SQLException {
        for (ProfileTaskRecord candidate : listDispatchable(connection)) {
            if (!candidate.getDependencyIds().contains(taskId)) {
                continue;
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "update profile_tasks set status='BLOCKED', error=?, updated_at=? where task_id=? and status in ('READY','PENDING')")) {
                statement.setString(1, "Dependency did not complete: " + taskId);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, candidate.getTaskId());
                statement.executeUpdate();
            }
            blockDependents(connection, candidate.getTaskId());
        }
    }

    /** 单次执行允许的终态。 */
    private static boolean isAttemptTerminal(String status) {
        return "COMPLETED".equals(status)
                || "FAILED".equals(status)
                || "TIMED_OUT".equals(status)
                || "INTERRUPTED".equals(status);
    }
}
