package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SqliteCronJobRepository 实现。 */
@RequiredArgsConstructor
public class SqliteCronJobRepository extends SqliteRepositorySupport implements CronJobRepository {
    /** 记录SQLite定时任务任务中的数据库。 */
    private final SqliteDatabase database;

    @Override
    protected Connection getConnection() throws SQLException {
        return database.openConnection();
    }

    /**
     * 执行save，服务于SQLite定时任务任务主流程相关逻辑。
     *
     * @param job job 参数。
     * @return 返回save结果。
     */
    public CronJobRecord save(CronJobRecord job) throws SQLException {
        executeUpdate(
                "insert or replace into cron_jobs (job_id, name, cron_expr, prompt, source_key, deliver_platform, deliver_chat_id, deliver_thread_id, origin_json, skills_json, repeat_times, repeat_completed, script, workdir, no_agent, context_from_json, enabled_toolsets_json, model, provider, base_url, wrap_response, last_status, last_error, last_delivery_error, pending_trigger_type, paused_at, paused_reason, last_output, status, next_run_at, last_run_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, job.getJobId());
                    stmt.setString(2, job.getName());
                    stmt.setString(3, job.getCronExpr());
                    stmt.setString(4, job.getPrompt());
                    stmt.setString(5, job.getSourceKey());
                    stmt.setString(6, job.getDeliverPlatform());
                    stmt.setString(7, job.getDeliverChatId());
                    stmt.setString(8, job.getDeliverThreadId());
                    stmt.setString(9, job.getOriginJson());
                    stmt.setString(10, job.getSkillsJson());
                    stmt.setInt(11, job.getRepeatTimes());
                    stmt.setInt(12, job.getRepeatCompleted());
                    stmt.setString(13, job.getScript());
                    stmt.setString(14, job.getWorkdir());
                    stmt.setInt(15, job.isNoAgent() ? 1 : 0);
                    stmt.setString(16, job.getContextFromJson());
                    stmt.setString(17, job.getEnabledToolsetsJson());
                    stmt.setString(18, job.getModel());
                    stmt.setString(19, job.getProvider());
                    stmt.setString(20, job.getBaseUrl());
                    stmt.setInt(21, job.isWrapResponse() ? 1 : 0);
                    stmt.setString(22, job.getLastStatus());
                    stmt.setString(23, redact(job.getLastError(), 2000));
                    stmt.setString(24, redact(job.getLastDeliveryError(), 2000));
                    stmt.setString(25, job.getPendingTriggerType());
                    stmt.setLong(26, job.getPausedAt());
                    stmt.setString(27, job.getPausedReason());
                    stmt.setString(28, redact(job.getLastOutput(), 8000));
                    stmt.setString(29, job.getStatus());
                    stmt.setLong(30, job.getNextRunAt());
                    stmt.setLong(31, job.getLastRunAt());
                    stmt.setLong(32, job.getCreatedAt());
                    stmt.setLong(33, job.getUpdatedAt());
                });
        return job;
    }

    /**
     * 根据标识查找对应数据。
     *
     * @param jobId job标识。
     * @return 返回按标识查找得到的结果。
     */
    public CronJobRecord findById(String jobId) throws SQLException {
        return queryOne(
                "select * from cron_jobs where job_id = ?",
                stmt -> stmt.setString(1, jobId),
                this::map);
    }

    /**
     * 列出根据来源。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回根据来源列表。
     */
    public List<CronJobRecord> listBySource(String sourceKey) throws SQLException {
        return queryList(
                "select * from cron_jobs where source_key = ? order by updated_at desc",
                stmt -> stmt.setString(1, sourceKey),
                this::map);
    }

    /**
     * 列出全部。
     *
     * @return 返回全部列表。
     */
    @Override
    public List<CronJobRecord> listAll() throws SQLException {
        return queryList("select * from cron_jobs order by updated_at desc", null, this::map);
    }

    /**
     * 列出Due。
     *
     * @param nowEpochMillis nowEpochMillis 参数。
     * @return 返回Due列表。
     */
    public List<CronJobRecord> listDue(long nowEpochMillis) throws SQLException {
        return queryList(
                "select * from cron_jobs where status = 'ACTIVE' and (next_run_at <= ? or next_run_at is null or next_run_at = 0) order by next_run_at asc",
                stmt -> stmt.setLong(1, nowEpochMillis),
                this::map);
    }

    /**
     * 执行delete，服务于SQLite定时任务任务主流程相关逻辑。
     *
     * @param jobId job标识。
     */
    public void delete(String jobId) throws SQLException {
        executeUpdate("delete from cron_jobs where job_id = ?", stmt -> stmt.setString(1, jobId));
    }

    /**
     * 更新状态。
     *
     * @param jobId job标识。
     * @param status 状态参数。
     */
    public void updateStatus(String jobId, String status) throws SQLException {
        executeUpdate(
                "update cron_jobs set status = ?, paused_at = ?, updated_at = ? where job_id = ?",
                stmt -> {
                    stmt.setString(1, status);
                    stmt.setLong(
                            2, "PAUSED".equalsIgnoreCase(status) ? System.currentTimeMillis() : 0L);
                    stmt.setLong(3, System.currentTimeMillis());
                    stmt.setString(4, jobId);
                });
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param job job 参数。
     * @return 返回更新结果。
     */
    @Override
    public CronJobRecord update(CronJobRecord job) throws SQLException {
        job.setUpdatedAt(System.currentTimeMillis());
        return save(job);
    }

    /**
     * 标记运行。
     *
     * @param jobId job标识。
     * @param lastRunAt last运行At参数。
     * @param nextRunAt next运行At参数。
     */
    public void markRun(String jobId, long lastRunAt, long nextRunAt) throws SQLException {
        executeUpdate(
                "update cron_jobs set last_run_at = ?, next_run_at = ?, updated_at = ? where job_id = ?",
                stmt -> {
                    stmt.setLong(1, lastRunAt);
                    stmt.setLong(2, nextRunAt);
                    stmt.setLong(3, System.currentTimeMillis());
                    stmt.setString(4, jobId);
                });
    }

    /**
     * 标记运行结果。
     *
     * @param jobId job标识。
     * @param lastRunAt last运行At参数。
     * @param nextRunAt next运行At参数。
     * @param status 状态参数。
     * @param error 错误参数。
     * @param output 命令执行输出文本。
     * @param repeatCompleted repeatCompleted 参数。
     * @param nextStatus next状态参数。
     */
    @Override
    public void markRunResult(
            String jobId,
            long lastRunAt,
            long nextRunAt,
            String status,
            String error,
            String output,
            int repeatCompleted,
            String nextStatus)
            throws SQLException {
        executeUpdate(
                "update cron_jobs set last_run_at = ?, next_run_at = ?, last_status = ?, last_error = ?, last_output = ?, repeat_completed = ?, status = case when status = 'PAUSED' then status else ? end, pending_trigger_type = null, last_delivery_error = null, updated_at = ? where job_id = ?",
                stmt -> {
                    stmt.setLong(1, lastRunAt);
                    stmt.setLong(2, nextRunAt);
                    stmt.setString(3, status);
                    stmt.setString(4, redact(error, 2000));
                    stmt.setString(5, redact(output, 8000));
                    stmt.setInt(6, repeatCompleted);
                    stmt.setString(7, nextStatus);
                    stmt.setLong(8, System.currentTimeMillis());
                    stmt.setString(9, jobId);
                });
    }

    /**
     * 标记投递Error。
     *
     * @param jobId job标识。
     * @param error 错误参数。
     */
    @Override
    public void markDeliveryError(String jobId, String error) throws SQLException {
        executeUpdate(
                "update cron_jobs set last_delivery_error = ?, updated_at = ? where job_id = ?",
                stmt -> {
                    stmt.setString(1, redact(error, 2000));
                    stmt.setLong(2, System.currentTimeMillis());
                    stmt.setString(3, jobId);
                });
    }

    /**
     * 保存运行。
     *
     * @param run 运行参数。
     * @return 返回运行结果。
     */
    @Override
    public CronJobRunRecord saveRun(CronJobRunRecord run) throws SQLException {
        executeUpdate(
                "insert or replace into cron_runs (run_id, job_id, source_key, trigger_type, attempt, started_at, finished_at, status, summary, output, error, delivery_error, delivery_result_json) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, run.getRunId());
                    stmt.setString(2, run.getJobId());
                    stmt.setString(3, run.getSourceKey());
                    stmt.setString(4, run.getTriggerType());
                    stmt.setInt(5, run.getAttempt());
                    stmt.setLong(6, run.getStartedAt());
                    stmt.setLong(7, run.getFinishedAt());
                    stmt.setString(8, run.getStatus());
                    stmt.setString(9, redact(run.getSummary(), 2000));
                    stmt.setString(10, redact(run.getOutput(), 8000));
                    stmt.setString(11, redact(run.getError(), 2000));
                    stmt.setString(12, redact(run.getDeliveryError(), 2000));
                    stmt.setString(13, redact(run.getDeliveryResultJson(), 4000));
                });
        return run;
    }

    /**
     * 列出运行。
     *
     * @param jobId job标识。
     * @param limit 最大返回数量。
     * @return 返回运行列表。
     */
    @Override
    public List<CronJobRunRecord> listRuns(String jobId, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        return queryList(
                "select * from cron_runs where job_id = ? order by started_at desc limit ?",
                stmt -> {
                    stmt.setString(1, jobId);
                    stmt.setInt(2, safeLimit);
                },
                this::mapRun);
    }

    /**
     * 执行map相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map结果。
     */
    private CronJobRecord map(ResultSet resultSet) throws SQLException {
        CronJobRecord record = new CronJobRecord();
        record.setJobId(resultSet.getString("job_id"));
        record.setName(resultSet.getString("name"));
        record.setCronExpr(resultSet.getString("cron_expr"));
        record.setPrompt(resultSet.getString("prompt"));
        record.setSourceKey(resultSet.getString("source_key"));
        record.setDeliverPlatform(resultSet.getString("deliver_platform"));
        record.setDeliverChatId(resultSet.getString("deliver_chat_id"));
        record.setDeliverThreadId(resultSet.getString("deliver_thread_id"));
        record.setOriginJson(resultSet.getString("origin_json"));
        record.setSkillsJson(resultSet.getString("skills_json"));
        record.setRepeatTimes(resultSet.getInt("repeat_times"));
        record.setRepeatCompleted(resultSet.getInt("repeat_completed"));
        record.setScript(resultSet.getString("script"));
        record.setWorkdir(resultSet.getString("workdir"));
        record.setNoAgent(resultSet.getInt("no_agent") == 1);
        record.setContextFromJson(resultSet.getString("context_from_json"));
        record.setEnabledToolsetsJson(resultSet.getString("enabled_toolsets_json"));
        record.setModel(resultSet.getString("model"));
        record.setProvider(resultSet.getString("provider"));
        record.setBaseUrl(resultSet.getString("base_url"));
        record.setWrapResponse(resultSet.getInt("wrap_response") != 0);
        record.setLastStatus(resultSet.getString("last_status"));
        record.setLastError(resultSet.getString("last_error"));
        record.setLastDeliveryError(resultSet.getString("last_delivery_error"));
        record.setPendingTriggerType(resultSet.getString("pending_trigger_type"));
        record.setPausedAt(resultSet.getLong("paused_at"));
        record.setPausedReason(resultSet.getString("paused_reason"));
        record.setLastOutput(resultSet.getString("last_output"));
        record.setStatus(resultSet.getString("status"));
        record.setNextRunAt(resultSet.getLong("next_run_at"));
        record.setLastRunAt(resultSet.getLong("last_run_at"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setUpdatedAt(resultSet.getLong("updated_at"));
        return record;
    }

    /**
     * 执行map运行相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map运行结果。
     */
    private CronJobRunRecord mapRun(ResultSet resultSet) throws SQLException {
        CronJobRunRecord record = new CronJobRunRecord();
        record.setRunId(resultSet.getString("run_id"));
        record.setJobId(resultSet.getString("job_id"));
        record.setSourceKey(resultSet.getString("source_key"));
        record.setTriggerType(resultSet.getString("trigger_type"));
        record.setAttempt(resultSet.getInt("attempt"));
        record.setStartedAt(resultSet.getLong("started_at"));
        record.setFinishedAt(resultSet.getLong("finished_at"));
        record.setStatus(resultSet.getString("status"));
        record.setSummary(resultSet.getString("summary"));
        record.setOutput(resultSet.getString("output"));
        record.setError(resultSet.getString("error"));
        record.setDeliveryError(resultSet.getString("delivery_error"));
        record.setDeliveryResultJson(resultSet.getString("delivery_result_json"));
        return record;
    }

    /**
     * 脱敏文本中的密钥、令牌和敏感路径。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回redact结果。
     */
    private String redact(String value, int maxLength) {
        return value == null ? null : SecretRedactor.redact(value, maxLength);
    }
}
