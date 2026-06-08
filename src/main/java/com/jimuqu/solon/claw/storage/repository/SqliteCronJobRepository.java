package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SqliteCronJobRepository 实现。 */
@RequiredArgsConstructor
public class SqliteCronJobRepository implements CronJobRepository {
    /** 记录SQLite定时任务任务中的数据库。 */
    private final SqliteDatabase database;

    /**
     * 执行save，服务于SQLite定时任务任务主流程相关逻辑。
     *
     * @param job job 参数。
     * @return 返回save结果。
     */
    public CronJobRecord save(CronJobRecord job) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into cron_jobs (job_id, name, cron_expr, prompt, source_key, deliver_platform, deliver_chat_id, deliver_thread_id, origin_json, skills_json, repeat_times, repeat_completed, script, workdir, no_agent, context_from_json, enabled_toolsets_json, model, provider, base_url, wrap_response, last_status, last_error, last_delivery_error, pending_trigger_type, paused_at, paused_reason, last_output, status, next_run_at, last_run_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, job.getJobId());
            statement.setString(2, job.getName());
            statement.setString(3, job.getCronExpr());
            statement.setString(4, job.getPrompt());
            statement.setString(5, job.getSourceKey());
            statement.setString(6, job.getDeliverPlatform());
            statement.setString(7, job.getDeliverChatId());
            statement.setString(8, job.getDeliverThreadId());
            statement.setString(9, job.getOriginJson());
            statement.setString(10, job.getSkillsJson());
            statement.setInt(11, job.getRepeatTimes());
            statement.setInt(12, job.getRepeatCompleted());
            statement.setString(13, job.getScript());
            statement.setString(14, job.getWorkdir());
            statement.setInt(15, job.isNoAgent() ? 1 : 0);
            statement.setString(16, job.getContextFromJson());
            statement.setString(17, job.getEnabledToolsetsJson());
            statement.setString(18, job.getModel());
            statement.setString(19, job.getProvider());
            statement.setString(20, job.getBaseUrl());
            statement.setInt(21, job.isWrapResponse() ? 1 : 0);
            statement.setString(22, job.getLastStatus());
            statement.setString(23, redact(job.getLastError(), 2000));
            statement.setString(24, redact(job.getLastDeliveryError(), 2000));
            statement.setString(25, job.getPendingTriggerType());
            statement.setLong(26, job.getPausedAt());
            statement.setString(27, job.getPausedReason());
            statement.setString(28, redact(job.getLastOutput(), 8000));
            statement.setString(29, job.getStatus());
            statement.setLong(30, job.getNextRunAt());
            statement.setLong(31, job.getLastRunAt());
            statement.setLong(32, job.getCreatedAt());
            statement.setLong(33, job.getUpdatedAt());
            statement.executeUpdate();
            statement.close();
            return job;
        } finally {
            connection.close();
        }
    }

    /**
     * 根据标识查找对应数据。
     *
     * @param jobId job标识。
     * @return 返回按标识查找得到的结果。
     */
    public CronJobRecord findById(String jobId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from cron_jobs where job_id = ?");
            statement.setString(1, jobId);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (resultSet.next()) {
                    return map(resultSet);
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }

        return null;
    }

    /**
     * 列出根据来源。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回根据来源列表。
     */
    public List<CronJobRecord> listBySource(String sourceKey) throws Exception {
        List<CronJobRecord> jobs = new ArrayList<CronJobRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from cron_jobs where source_key = ? order by updated_at desc");
            statement.setString(1, sourceKey);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    jobs.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }

        return jobs;
    }

    /**
     * 列出全部。
     *
     * @return 返回全部列表。
     */
    @Override
    public List<CronJobRecord> listAll() throws Exception {
        List<CronJobRecord> jobs = new ArrayList<CronJobRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from cron_jobs order by updated_at desc");
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    jobs.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return jobs;
    }

    /**
     * 列出Due。
     *
     * @param nowEpochMillis nowEpochMillis 参数。
     * @return 返回Due列表。
     */
    public List<CronJobRecord> listDue(long nowEpochMillis) throws Exception {
        List<CronJobRecord> jobs = new ArrayList<CronJobRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from cron_jobs where status = 'ACTIVE' and (next_run_at <= ? or next_run_at is null or next_run_at = 0) order by next_run_at asc");
            statement.setLong(1, nowEpochMillis);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    jobs.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }

        return jobs;
    }

    /**
     * 执行delete，服务于SQLite定时任务任务主流程相关逻辑。
     *
     * @param jobId job标识。
     */
    public void delete(String jobId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("delete from cron_jobs where job_id = ?");
            statement.setString(1, jobId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 更新状态。
     *
     * @param jobId job标识。
     * @param status 状态参数。
     */
    public void updateStatus(String jobId, String status) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update cron_jobs set status = ?, paused_at = ?, updated_at = ? where job_id = ?");
            statement.setString(1, status);
            statement.setLong(
                    2, "PAUSED".equalsIgnoreCase(status) ? System.currentTimeMillis() : 0L);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, jobId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param job job 参数。
     * @return 返回更新结果。
     */
    @Override
    public CronJobRecord update(CronJobRecord job) throws Exception {
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
    public void markRun(String jobId, long lastRunAt, long nextRunAt) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update cron_jobs set last_run_at = ?, next_run_at = ?, updated_at = ? where job_id = ?");
            statement.setLong(1, lastRunAt);
            statement.setLong(2, nextRunAt);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, jobId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
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
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update cron_jobs set last_run_at = ?, next_run_at = ?, last_status = ?, last_error = ?, last_output = ?, repeat_completed = ?, status = ?, pending_trigger_type = null, last_delivery_error = null, updated_at = ? where job_id = ?");
            statement.setLong(1, lastRunAt);
            statement.setLong(2, nextRunAt);
            statement.setString(3, status);
            statement.setString(4, redact(error, 2000));
            statement.setString(5, redact(output, 8000));
            statement.setInt(6, repeatCompleted);
            statement.setString(7, nextStatus);
            statement.setLong(8, System.currentTimeMillis());
            statement.setString(9, jobId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 标记投递Error。
     *
     * @param jobId job标识。
     * @param error 错误参数。
     */
    @Override
    public void markDeliveryError(String jobId, String error) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update cron_jobs set last_delivery_error = ?, updated_at = ? where job_id = ?");
            statement.setString(1, redact(error, 2000));
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, jobId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 保存运行。
     *
     * @param run 运行参数。
     * @return 返回运行结果。
     */
    @Override
    public CronJobRunRecord saveRun(CronJobRunRecord run) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into cron_runs (run_id, job_id, source_key, trigger_type, attempt, started_at, finished_at, status, summary, output, error, delivery_error, delivery_result_json) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, run.getRunId());
            statement.setString(2, run.getJobId());
            statement.setString(3, run.getSourceKey());
            statement.setString(4, run.getTriggerType());
            statement.setInt(5, run.getAttempt());
            statement.setLong(6, run.getStartedAt());
            statement.setLong(7, run.getFinishedAt());
            statement.setString(8, run.getStatus());
            statement.setString(9, redact(run.getSummary(), 2000));
            statement.setString(10, redact(run.getOutput(), 8000));
            statement.setString(11, redact(run.getError(), 2000));
            statement.setString(12, redact(run.getDeliveryError(), 2000));
            statement.setString(13, redact(run.getDeliveryResultJson(), 4000));
            statement.executeUpdate();
            statement.close();
            return run;
        } finally {
            connection.close();
        }
    }

    /**
     * 列出运行。
     *
     * @param jobId job标识。
     * @param limit 最大返回数量。
     * @return 返回运行列表。
     */
    @Override
    public List<CronJobRunRecord> listRuns(String jobId, int limit) throws Exception {
        List<CronJobRunRecord> runs = new ArrayList<CronJobRunRecord>();
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from cron_runs where job_id = ? order by started_at desc limit ?");
            statement.setString(1, jobId);
            statement.setInt(2, safeLimit);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    runs.add(mapRun(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return runs;
    }

    /**
     * 执行map相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map结果。
     */
    private CronJobRecord map(ResultSet resultSet) throws Exception {
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
    private CronJobRunRecord mapRun(ResultSet resultSet) throws Exception {
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
