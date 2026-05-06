package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SqliteCronJobRepository 实现。 */
@RequiredArgsConstructor
public class SqliteCronJobRepository implements CronJobRepository {
    private final SqliteDatabase database;

    public CronJobRecord save(CronJobRecord job) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into cron_jobs (job_id, name, cron_expr, prompt, source_key, deliver_platform, deliver_chat_id, deliver_thread_id, origin_json, skills_json, repeat_times, repeat_completed, script, workdir, no_agent, context_from_json, enabled_toolsets_json, model, provider, base_url, wrap_response, last_status, last_error, last_delivery_error, paused_at, paused_reason, last_output, status, next_run_at, last_run_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
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
            statement.setString(23, job.getLastError());
            statement.setString(24, job.getLastDeliveryError());
            statement.setLong(25, job.getPausedAt());
            statement.setString(26, job.getPausedReason());
            statement.setString(27, job.getLastOutput());
            statement.setString(28, job.getStatus());
            statement.setLong(29, job.getNextRunAt());
            statement.setLong(30, job.getLastRunAt());
            statement.setLong(31, job.getCreatedAt());
            statement.setLong(32, job.getUpdatedAt());
            statement.executeUpdate();
            statement.close();
            return job;
        } finally {
            connection.close();
        }
    }

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

    public void updateStatus(String jobId, String status) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update cron_jobs set status = ?, paused_at = ?, updated_at = ? where job_id = ?");
            statement.setString(1, status);
            statement.setLong(2, "PAUSED".equalsIgnoreCase(status) ? System.currentTimeMillis() : 0L);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, jobId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public CronJobRecord update(CronJobRecord job) throws Exception {
        job.setUpdatedAt(System.currentTimeMillis());
        return save(job);
    }

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
                            "update cron_jobs set last_run_at = ?, next_run_at = ?, last_status = ?, last_error = ?, last_output = ?, repeat_completed = ?, status = ?, last_delivery_error = null, updated_at = ? where job_id = ?");
            statement.setLong(1, lastRunAt);
            statement.setLong(2, nextRunAt);
            statement.setString(3, status);
            statement.setString(4, error);
            statement.setString(5, output);
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

    @Override
    public void markDeliveryError(String jobId, String error) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update cron_jobs set last_delivery_error = ?, updated_at = ? where job_id = ?");
            statement.setString(1, error);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, jobId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public CronJobRunRecord saveRun(CronJobRunRecord run) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into cron_runs (run_id, job_id, source_key, trigger_type, attempt, started_at, finished_at, status, summary, output, error, delivery_error) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, run.getRunId());
            statement.setString(2, run.getJobId());
            statement.setString(3, run.getSourceKey());
            statement.setString(4, run.getTriggerType());
            statement.setInt(5, run.getAttempt());
            statement.setLong(6, run.getStartedAt());
            statement.setLong(7, run.getFinishedAt());
            statement.setString(8, run.getStatus());
            statement.setString(9, run.getSummary());
            statement.setString(10, run.getOutput());
            statement.setString(11, run.getError());
            statement.setString(12, run.getDeliveryError());
            statement.executeUpdate();
            statement.close();
            return run;
        } finally {
            connection.close();
        }
    }

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
        return record;
    }
}
