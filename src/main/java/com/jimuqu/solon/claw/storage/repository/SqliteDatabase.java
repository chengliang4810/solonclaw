package com.jimuqu.solon.claw.storage.repository;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/** SqliteDatabase 实现。 */
public class SqliteDatabase {
    /** 记录SQLite数据库中的jdbcURL。 */
    private final String jdbcUrl;

    /** 记录SQLite数据库中的连接Lock。 */
    private final ReentrantLock connectionLock = new ReentrantLock(true);

    /** 记录SQLite数据库中的shared连接。 */
    private Connection sharedConnection;

    /**
     * 创建SQLite数据库实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SqliteDatabase(AppConfig appConfig) throws SQLException {
        FileUtil.mkParentDirs(appConfig.getRuntime().getStateDb());
        this.jdbcUrl = "jdbc:sqlite:" + appConfig.getRuntime().getStateDb();
        initSchema();
    }

    /**
     * 打开Connection。
     *
     * @return 返回Connection结果。
     */
    public Connection openConnection() throws SQLException {
        connectionLock.lock();
        try {
            return lockReleasingConnection(sharedConnection());
        } catch (SQLException e) {
            connectionLock.unlock();
            throw e;
        } catch (RuntimeException e) {
            connectionLock.unlock();
            throw e;
        }
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        connectionLock.lock();
        try {
            closeQuietly(sharedConnection);
            sharedConnection = null;
        } finally {
            connectionLock.unlock();
        }
    }

    /**
     * 执行shared连接相关逻辑。
     *
     * @return 返回shared Connection结果。
     */
    private Connection sharedConnection() throws SQLException {
        if (sharedConnection == null || sharedConnection.isClosed()) {
            try {
                sharedConnection = DriverManager.getConnection(jdbcUrl);
                Statement statement = sharedConnection.createStatement();
                try {
                    statement.execute("pragma busy_timeout=5000");
                    statement.execute("pragma journal_mode=WAL");
                    statement.execute("pragma secure_delete=ON");
                    statement.execute("pragma cell_size_check=ON");
                    statement.execute("pragma synchronous=FULL");
                } finally {
                    statement.close();
                }
            } catch (SQLException e) {
                closeQuietly(sharedConnection);
                sharedConnection = null;
                throw e;
            }
        }
        return sharedConnection;
    }

    /**
     * 执行lockReleasing连接相关逻辑。
     *
     * @param delegate 委派参数。
     * @return 返回lock Releasing Connection结果。
     */
    private Connection lockReleasingConnection(final Connection delegate) {
        InvocationHandler handler =
                new InvocationHandler() {
                    /** 是否启用closed。 */
                    private boolean closed;

                    /**
                     * 执行invoke相关逻辑。
                     *
                     * @param proxy 代理参数。
                     * @param method method 参数。
                     * @param args 工具或命令参数。
                     * @return 返回invoke结果。
                     */
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args)
                            throws Throwable {
                        if ("close".equals(method.getName())
                                && method.getParameterTypes().length == 0) {
                            if (!closed) {
                                closed = true;
                                connectionLock.unlock();
                            }
                            return null;
                        }
                        if ("isClosed".equals(method.getName())
                                && method.getParameterTypes().length == 0) {
                            return Boolean.valueOf(closed || delegate.isClosed());
                        }
                        if (closed) {
                            throw new SQLException("Connection is closed");
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                };
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(), new Class[] {Connection.class}, handler);
    }

    /**
     * 关闭Quietly。
     *
     * @param connection 连接参数。
     */
    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }

    /** 初始化结构。 */
    private void initSchema() throws SQLException {
        Connection connection = openConnection();
        try {
            Statement statement = connection.createStatement();
            statement.execute(
                    "create table if not exists sessions ("
                            + "session_id text primary key,"
                            + "source_key text not null,"
                            + "branch_name text,"
                            + "parent_session_id text,"
                            + "model_override text,"
                            + "service_tier_override text,"
                            + "reasoning_effort_override text,"
                            + "active_agent_name text,"
                            + "platform_message_id text,"
                            + "metadata_json text,"
                            + "ndjson text,"
                            + "title text,"
                            + "compressed_summary text,"
                            + "system_prompt_snapshot text,"
                            + "agent_snapshot_json text,"
                            + "goal_state_json text,"
                            + "last_learning_at integer not null default 0,"
                            + "last_compression_at integer not null default 0,"
                            + "last_compression_input_tokens integer not null default 0,"
                            + "compression_failure_count integer not null default 0,"
                            + "last_compression_failed_at integer not null default 0,"
                            + "last_input_tokens integer not null default 0,"
                            + "last_output_tokens integer not null default 0,"
                            + "last_reasoning_tokens integer not null default 0,"
                            + "last_cache_read_tokens integer not null default 0,"
                            + "last_cache_write_tokens integer not null default 0,"
                            + "last_total_tokens integer not null default 0,"
                            + "cumulative_input_tokens integer not null default 0,"
                            + "cumulative_output_tokens integer not null default 0,"
                            + "cumulative_reasoning_tokens integer not null default 0,"
                            + "cumulative_cache_read_tokens integer not null default 0,"
                            + "cumulative_cache_write_tokens integer not null default 0,"
                            + "cumulative_total_tokens integer not null default 0,"
                            + "last_usage_at integer not null default 0,"
                            + "last_resolved_provider text,"
                            + "last_resolved_model text,"
                            + "created_at integer not null,"
                            + "updated_at integer not null"
                            + ")");
            try {
                statement.execute("alter table sessions add column branch_name text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column parent_session_id text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column model_override text");
            } catch (Exception ignored) {
            }
            addColumn(statement, "sessions", "service_tier_override text");
            addColumn(statement, "sessions", "reasoning_effort_override text");
            try {
                statement.execute("alter table sessions add column active_agent_name text");
            } catch (Exception ignored) {
            }
            addColumn(statement, "sessions", "platform_message_id text");
            addColumn(statement, "sessions", "metadata_json text");
            try {
                statement.execute("alter table sessions add column ndjson text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column title text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column compressed_summary text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column system_prompt_snapshot text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column agent_snapshot_json text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column goal_state_json text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_learning_at integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_compression_at integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_compression_input_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column compression_failure_count integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_compression_failed_at integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_input_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_output_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_reasoning_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_cache_read_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_cache_write_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_total_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column cumulative_input_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column cumulative_output_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column cumulative_reasoning_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column cumulative_cache_read_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column cumulative_cache_write_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column cumulative_total_tokens integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table sessions add column last_usage_at integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column last_resolved_provider text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table sessions add column last_resolved_model text");
            } catch (Exception ignored) {
            }
            statement.execute(
                    "create index if not exists idx_sessions_source on sessions(source_key)");
            statement.execute(
                    "create index if not exists idx_sessions_parent on sessions(parent_session_id)");
            initSessionSearchIndex(statement);
            statement.execute(
                    "create table if not exists bindings ("
                            + "source_key text primary key,"
                            + "session_id text not null"
                            + ")");
            statement.execute(
                    "create table if not exists tool_toggles ("
                            + "source_key text not null,"
                            + "tool_name text not null,"
                            + "enabled integer not null,"
                            + "primary key (source_key, tool_name)"
                            + ")");
            statement.execute(
                    "create table if not exists skill_states ("
                            + "source_key text not null,"
                            + "skill_name text not null,"
                            + "enabled integer not null,"
                            + "primary key (source_key, skill_name)"
                            + ")");
            statement.execute(
                    "create table if not exists global_settings ("
                            + "setting_key text primary key,"
                            + "setting_value text not null,"
                            + "updated_at integer not null"
                            + ")");
            statement.execute(
                    "create table if not exists cron_jobs ("
                            + "job_id text primary key,"
                            + "name text not null,"
                            + "cron_expr text not null,"
                            + "prompt text not null,"
                            + "source_key text not null,"
                            + "deliver_platform text,"
                            + "deliver_chat_id text,"
                            + "deliver_thread_id text,"
                            + "origin_json text,"
                            + "skills_json text,"
                            + "repeat_times integer not null default 0,"
                            + "repeat_completed integer not null default 0,"
                            + "script text,"
                            + "workdir text,"
                            + "no_agent integer not null default 0,"
                            + "context_from_json text,"
                            + "enabled_toolsets_json text,"
                            + "model text,"
                            + "provider text,"
                            + "base_url text,"
                            + "wrap_response integer not null default 1,"
                            + "last_status text,"
                            + "last_error text,"
                            + "last_delivery_error text,"
                            + "pending_trigger_type text,"
                            + "paused_at integer not null default 0,"
                            + "paused_reason text,"
                            + "last_output text,"
                            + "status text not null,"
                            + "next_run_at integer not null,"
                            + "last_run_at integer not null,"
                            + "created_at integer not null,"
                            + "updated_at integer not null"
                            + ")");
            statement.execute(
                    "create index if not exists idx_cron_jobs_source on cron_jobs(source_key)");
            statement.execute(
                    "create index if not exists idx_cron_jobs_next_run on cron_jobs(next_run_at)");
            addColumn(statement, "cron_jobs", "deliver_thread_id text");
            addColumn(statement, "cron_jobs", "origin_json text");
            addColumn(statement, "cron_jobs", "skills_json text");
            addColumn(statement, "cron_jobs", "repeat_times integer not null default 0");
            addColumn(statement, "cron_jobs", "repeat_completed integer not null default 0");
            addColumn(statement, "cron_jobs", "script text");
            addColumn(statement, "cron_jobs", "workdir text");
            addColumn(statement, "cron_jobs", "no_agent integer not null default 0");
            addColumn(statement, "cron_jobs", "context_from_json text");
            addColumn(statement, "cron_jobs", "enabled_toolsets_json text");
            addColumn(statement, "cron_jobs", "model text");
            addColumn(statement, "cron_jobs", "provider text");
            addColumn(statement, "cron_jobs", "base_url text");
            addColumn(statement, "cron_jobs", "wrap_response integer not null default 1");
            addColumn(statement, "cron_jobs", "last_status text");
            addColumn(statement, "cron_jobs", "last_error text");
            addColumn(statement, "cron_jobs", "last_delivery_error text");
            addColumn(statement, "cron_jobs", "pending_trigger_type text");
            addColumn(statement, "cron_jobs", "paused_at integer not null default 0");
            addColumn(statement, "cron_jobs", "paused_reason text");
            addColumn(statement, "cron_jobs", "last_output text");
            statement.execute(
                    "create table if not exists cron_runs ("
                            + "run_id text primary key,"
                            + "job_id text not null,"
                            + "source_key text,"
                            + "trigger_type text,"
                            + "attempt integer not null default 0,"
                            + "started_at integer not null,"
                            + "finished_at integer,"
                            + "status text not null,"
                            + "summary text,"
                            + "output text,"
                            + "error text,"
                            + "delivery_error text,"
                            + "delivery_result_json text"
                            + ")");
            addColumn(statement, "cron_runs", "source_key text");
            addColumn(statement, "cron_runs", "trigger_type text");
            addColumn(statement, "cron_runs", "attempt integer not null default 0");
            addColumn(statement, "cron_runs", "output text");
            addColumn(statement, "cron_runs", "error text");
            addColumn(statement, "cron_runs", "delivery_error text");
            addColumn(statement, "cron_runs", "delivery_result_json text");
            statement.execute(
                    "create index if not exists idx_cron_runs_job_started on cron_runs(job_id, started_at desc)");
            statement.execute(
                    "create table if not exists home_channels ("
                            + "platform text primary key,"
                            + "chat_id text not null,"
                            + "thread_id text,"
                            + "chat_name text,"
                            + "updated_at integer not null"
                            + ")");
            addColumn(statement, "home_channels", "thread_id text");
            statement.execute(
                    "create table if not exists approved_users ("
                            + "platform text not null,"
                            + "user_id text not null,"
                            + "user_name text,"
                            + "approved_at integer not null,"
                            + "approved_by text,"
                            + "primary key (platform, user_id)"
                            + ")");
            statement.execute(
                    "create table if not exists pairing_requests ("
                            + "platform text not null,"
                            + "code text not null,"
                            + "user_id text not null,"
                            + "user_name text,"
                            + "chat_id text,"
                            + "created_at integer not null,"
                            + "expires_at integer not null,"
                            + "primary key (platform, code)"
                            + ")");
            statement.execute(
                    "create table if not exists pairing_rate_limits ("
                            + "platform text not null,"
                            + "user_id text not null,"
                            + "requested_at integer not null,"
                            + "failed_attempts integer not null,"
                            + "lockout_until integer not null,"
                            + "primary key (platform, user_id)"
                            + ")");
            statement.execute(
                    "create table if not exists platform_admins ("
                            + "platform text primary key,"
                            + "user_id text not null,"
                            + "user_name text,"
                            + "chat_id text,"
                            + "created_at integer not null"
                            + ")");
            statement.execute(
                    "create table if not exists checkpoints ("
                            + "checkpoint_id text primary key,"
                            + "source_key text not null,"
                            + "session_id text,"
                            + "checkpoint_dir text not null,"
                            + "manifest_path text not null,"
                            + "created_at integer not null,"
                            + "restored_at integer not null default 0"
                            + ")");
            statement.execute(
                    "create index if not exists idx_checkpoints_source_created on checkpoints(source_key, created_at desc)");
            statement.execute(
                    "create table if not exists agent_runs ("
                            + "run_id text primary key,"
                            + "session_id text not null,"
                            + "source_key text,"
                            + "run_kind text,"
                            + "parent_run_id text,"
                            + "agent_name text,"
                            + "agent_snapshot_json text,"
                            + "status text not null,"
                            + "phase text,"
                            + "busy_policy text,"
                            + "backgrounded integer not null default 0,"
                            + "input_preview text,"
                            + "final_reply_preview text,"
                            + "provider text,"
                            + "model text,"
                            + "attempts integer not null default 0,"
                            + "context_estimate_tokens integer not null default 0,"
                            + "context_window_tokens integer not null default 0,"
                            + "compression_count integer not null default 0,"
                            + "fallback_count integer not null default 0,"
                            + "tool_call_count integer not null default 0,"
                            + "subtask_count integer not null default 0,"
                            + "input_tokens integer not null default 0,"
                            + "output_tokens integer not null default 0,"
                            + "total_tokens integer not null default 0,"
                            + "queued_at integer not null default 0,"
                            + "started_at integer not null,"
                            + "heartbeat_at integer not null default 0,"
                            + "last_activity_at integer not null default 0,"
                            + "finished_at integer not null default 0,"
                            + "exit_reason text,"
                            + "recoverable integer not null default 0,"
                            + "recovery_hint text,"
                            + "error text"
                            + ")");
            addColumn(statement, "agent_runs", "run_kind text");
            addColumn(statement, "agent_runs", "parent_run_id text");
            try {
                statement.execute("alter table agent_runs add column agent_name text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table agent_runs add column agent_snapshot_json text");
            } catch (Exception ignored) {
            }
            addColumn(statement, "agent_runs", "phase text");
            addColumn(statement, "agent_runs", "busy_policy text");
            addColumn(statement, "agent_runs", "backgrounded integer not null default 0");
            addColumn(
                    statement, "agent_runs", "context_estimate_tokens integer not null default 0");
            addColumn(statement, "agent_runs", "context_window_tokens integer not null default 0");
            addColumn(statement, "agent_runs", "compression_count integer not null default 0");
            addColumn(statement, "agent_runs", "fallback_count integer not null default 0");
            addColumn(statement, "agent_runs", "tool_call_count integer not null default 0");
            addColumn(statement, "agent_runs", "subtask_count integer not null default 0");
            addColumn(statement, "agent_runs", "queued_at integer not null default 0");
            addColumn(statement, "agent_runs", "heartbeat_at integer not null default 0");
            addColumn(statement, "agent_runs", "last_activity_at integer not null default 0");
            addColumn(statement, "agent_runs", "exit_reason text");
            addColumn(statement, "agent_runs", "recoverable integer not null default 0");
            addColumn(statement, "agent_runs", "recovery_hint text");
            statement.execute(
                    "create index if not exists idx_agent_runs_session_started on agent_runs(session_id, started_at desc)");
            statement.execute(
                    "create index if not exists idx_agent_runs_parent on agent_runs(parent_run_id)");
            statement.execute(
                    "create index if not exists idx_agent_runs_status_activity on agent_runs(status, last_activity_at)");
            statement.execute(
                    "create table if not exists usage_events ("
                            + "event_id text primary key,"
                            + "session_id text,"
                            + "run_id text,"
                            + "source_key text,"
                            + "provider text,"
                            + "model text,"
                            + "input_tokens integer not null default 0,"
                            + "output_tokens integer not null default 0,"
                            + "cache_read_tokens integer not null default 0,"
                            + "cache_write_tokens integer not null default 0,"
                            + "reasoning_tokens integer not null default 0,"
                            + "total_tokens integer not null default 0,"
                            + "request_count integer not null default 0,"
                            + "cost_micros integer not null default 0,"
                            + "currency text,"
                            + "price_source text,"
                            + "price_source_url text,"
                            + "pricing_version text,"
                            + "price_fetched_at integer not null default 0,"
                            + "raw_usage_json text,"
                            + "pricing_available integer not null default 0,"
                            + "unpriced_input_tokens integer not null default 0,"
                            + "unpriced_output_tokens integer not null default 0,"
                            + "unpriced_cache_read_tokens integer not null default 0,"
                            + "unpriced_cache_write_tokens integer not null default 0,"
                            + "unpriced_reasoning_tokens integer not null default 0,"
                            + "priced_at integer not null default 0,"
                            + "created_at integer not null,"
                            + "backfill_approximate integer not null default 0"
                            + ")");
            addColumn(statement, "usage_events", "request_count integer not null default 0");
            addColumn(statement, "usage_events", "price_source_url text");
            addColumn(statement, "usage_events", "pricing_version text");
            addColumn(statement, "usage_events", "price_fetched_at integer not null default 0");
            addColumn(statement, "usage_events", "raw_usage_json text");
            statement.execute(
                    "create index if not exists idx_usage_events_created on usage_events(created_at desc)");
            statement.execute(
                    "create index if not exists idx_usage_events_run on usage_events(run_id)");
            statement.execute(
                    "create table if not exists agent_run_events ("
                            + "event_id text primary key,"
                            + "run_id text not null,"
                            + "session_id text,"
                            + "source_key text,"
                            + "event_type text not null,"
                            + "phase text,"
                            + "severity text,"
                            + "attempt_no integer not null default 0,"
                            + "provider text,"
                            + "model text,"
                            + "summary text,"
                            + "metadata_json text,"
                            + "created_at integer not null"
                            + ")");
            addColumn(statement, "agent_run_events", "phase text");
            addColumn(statement, "agent_run_events", "severity text");
            statement.execute(
                    "create index if not exists idx_agent_run_events_run_time on agent_run_events(run_id, created_at asc)");
            initAgentRunEventSearchIndex(statement);
            statement.execute(
                    "create table if not exists run_control_commands ("
                            + "command_id text primary key,"
                            + "run_id text,"
                            + "source_key text,"
                            + "command text not null,"
                            + "payload_json text,"
                            + "status text not null,"
                            + "created_at integer not null,"
                            + "handled_at integer not null default 0"
                            + ")");
            statement.execute(
                    "create index if not exists idx_run_control_commands_run on run_control_commands(run_id, created_at asc)");
            statement.execute(
                    "create index if not exists idx_run_control_commands_pending on run_control_commands(run_id, command, status, created_at desc)");
            statement.execute(
                    "create table if not exists queued_run_messages ("
                            + "queue_id text primary key,"
                            + "run_id text,"
                            + "session_id text,"
                            + "source_key text not null,"
                            + "message_text text,"
                            + "message_json text,"
                            + "status text not null,"
                            + "busy_policy text,"
                            + "created_at integer not null,"
                            + "started_at integer not null default 0,"
                            + "finished_at integer not null default 0,"
                            + "error text"
                            + ")");
            statement.execute(
                    "create index if not exists idx_queued_run_messages_source_status on queued_run_messages(source_key, session_id, status, created_at asc)");
            statement.execute(
                    "create table if not exists tool_calls ("
                            + "tool_call_id text primary key,"
                            + "run_id text not null,"
                            + "session_id text,"
                            + "source_key text,"
                            + "tool_name text not null,"
                            + "status text not null,"
                            + "args_preview text,"
                            + "result_preview text,"
                            + "result_ref text,"
                            + "error text,"
                            + "read_only integer not null default 0,"
                            + "interruptible integer not null default 0,"
                            + "side_effecting integer not null default 0,"
                            + "result_indexable integer not null default 1,"
                            + "output_limit_bytes integer not null default 0,"
                            + "result_size_bytes integer not null default 0,"
                            + "execution_policy text,"
                            + "started_at integer not null,"
                            + "finished_at integer not null default 0,"
                            + "duration_ms integer not null default 0"
                            + ")");
            addColumn(statement, "tool_calls", "read_only integer not null default 0");
            addColumn(statement, "tool_calls", "result_indexable integer not null default 1");
            addColumn(statement, "tool_calls", "output_limit_bytes integer not null default 0");
            addColumn(statement, "tool_calls", "result_size_bytes integer not null default 0");
            addColumn(statement, "tool_calls", "execution_policy text");
            statement.execute(
                    "create index if not exists idx_tool_calls_run_started on tool_calls(run_id, started_at asc)");
            statement.execute(
                    "create table if not exists approval_audit_events ("
                            + "event_id text primary key,"
                            + "session_id text,"
                            + "event_type text not null,"
                            + "choice text,"
                            + "outcome text,"
                            + "status text,"
                            + "approved integer not null default 0,"
                            + "approver text,"
                            + "tool_name text,"
                            + "approval_id text,"
                            + "approval_key text,"
                            + "command_hash text,"
                            + "command_preview text,"
                            + "description text,"
                            + "pattern_keys_json text,"
                            + "created_at integer not null,"
                            + "approval_created_at integer not null default 0,"
                            + "approval_expires_at integer not null default 0"
                            + ")");
            addColumn(statement, "approval_audit_events", "outcome text");
            addColumn(statement, "approval_audit_events", "status text");
            addColumn(statement, "approval_audit_events", "approved integer not null default 0");
            statement.execute(
                    "create index if not exists idx_approval_audit_events_created on approval_audit_events(created_at desc)");
            statement.execute(
                    "create index if not exists idx_approval_audit_events_session on approval_audit_events(session_id, created_at desc)");
            statement.execute(
                    "create table if not exists subagent_runs ("
                            + "subagent_id text primary key,"
                            + "parent_run_id text,"
                            + "child_run_id text,"
                            + "parent_source_key text,"
                            + "child_source_key text,"
                            + "session_id text,"
                            + "name text,"
                            + "goal_preview text,"
                            + "status text not null,"
                            + "active integer not null default 0,"
                            + "interrupt_requested integer not null default 0,"
                            + "depth integer not null default 1,"
                            + "task_index integer not null default 0,"
                            + "output_tail_json text,"
                            + "error text,"
                            + "started_at integer not null,"
                            + "finished_at integer not null default 0,"
                            + "heartbeat_at integer not null default 0"
                            + ")");
            addColumn(statement, "subagent_runs", "active integer not null default 0");
            addColumn(statement, "subagent_runs", "interrupt_requested integer not null default 0");
            statement.execute(
                    "create index if not exists idx_subagent_runs_parent on subagent_runs(parent_run_id, started_at asc)");
            statement.execute(
                    "create table if not exists run_recoveries ("
                            + "recovery_id text primary key,"
                            + "run_id text not null,"
                            + "session_id text,"
                            + "source_key text,"
                            + "recovery_type text not null,"
                            + "status text not null,"
                            + "summary text,"
                            + "payload_json text,"
                            + "created_at integer not null,"
                            + "resolved_at integer not null default 0"
                            + ")");
            statement.execute(
                    "create index if not exists idx_run_recoveries_run on run_recoveries(run_id, created_at asc)");
            statement.execute(
                    "create table if not exists task_todos ("
                            + "todo_id text primary key,"
                            + "run_id text,"
                            + "session_id text,"
                            + "source_key text,"
                            + "content text not null,"
                            + "status text not null,"
                            + "sort_order integer not null default 0,"
                            + "created_at integer not null,"
                            + "updated_at integer not null"
                            + ")");
            statement.execute(
                    "create index if not exists idx_task_todos_session on task_todos(session_id, sort_order asc)");
            statement.execute(
                    "create table if not exists mcp_servers ("
                            + "server_id text primary key,"
                            + "name text not null,"
                            + "transport text not null,"
                            + "endpoint text,"
                            + "command text,"
                            + "args_json text,"
                            + "auth_json text,"
                            + "oauth_json text,"
                            + "capabilities_json text,"
                            + "status text not null,"
                            + "tools_json text,"
                            + "last_tools_hash text,"
                            + "last_tools_json text,"
                            + "last_error text,"
                            + "enabled integer not null default 1,"
                            + "created_at integer not null,"
                            + "updated_at integer not null,"
                            + "last_checked_at integer not null default 0,"
                            + "last_tools_changed_at integer not null default 0"
                            + ")");
            addColumn(statement, "mcp_servers", "oauth_json text");
            addColumn(statement, "mcp_servers", "capabilities_json text");
            addColumn(statement, "mcp_servers", "last_tools_hash text");
            addColumn(statement, "mcp_servers", "last_tools_json text");
            addColumn(statement, "mcp_servers", "last_tools_changed_at integer not null default 0");
            statement.execute(
                    "create table if not exists curator_reports ("
                            + "report_id text primary key,"
                            + "status text not null,"
                            + "summary text,"
                            + "report_path text,"
                            + "report_json text,"
                            + "started_at integer not null,"
                            + "finished_at integer not null"
                            + ")");
            statement.execute(
                    "create table if not exists skill_improvements ("
                            + "improvement_id text primary key,"
                            + "session_id text,"
                            + "run_id text,"
                            + "skill_name text not null,"
                            + "action text not null,"
                            + "summary text,"
                            + "changed_files_json text,"
                            + "evidence_json text,"
                            + "needs_review integer not null default 0,"
                            + "created_at integer not null"
                            + ")");
            statement.execute(
                    "create table if not exists channel_media ("
                            + "media_id text primary key,"
                            + "platform text not null,"
                            + "chat_id text,"
                            + "message_id text,"
                            + "kind text,"
                            + "original_name text,"
                            + "mime_type text,"
                            + "local_path text,"
                            + "remote_id text,"
                            + "status text not null,"
                            + "error text,"
                            + "size_bytes integer not null default 0,"
                            + "created_at integer not null,"
                            + "updated_at integer not null,"
                            + "expires_at integer not null default 0"
                            + ")");
            statement.execute(
                    "create index if not exists idx_channel_media_platform_chat on channel_media(platform, chat_id, updated_at desc)");
            statement.execute(
                    "create table if not exists channel_states ("
                            + "platform text not null,"
                            + "scope_key text not null,"
                            + "state_key text not null,"
                            + "state_value text,"
                            + "updated_at integer not null,"
                            + "primary key (platform, scope_key, state_key)"
                            + ")");
            statement.execute(
                    "create index if not exists idx_channel_states_platform_scope on channel_states(platform, scope_key)");
            statement.execute(
                    "create table if not exists proactive_observations ("
                            + "observation_id text primary key,"
                            + "tick_id text not null,"
                            + "collector text not null,"
                            + "source_key text,"
                            + "summary text,"
                            + "payload_json text,"
                            + "status text not null,"
                            + "error text,"
                            + "created_at integer not null"
                            + ")");
            statement.execute(
                    "create table if not exists proactive_candidates ("
                            + "candidate_id text primary key,"
                            + "source_type text not null,"
                            + "source_ref text not null,"
                            + "source_key text,"
                            + "subject_type text,"
                            + "subject_ref text,"
                            + "topic text,"
                            + "title text,"
                            + "summary text,"
                            + "reason text,"
                            + "action_offer text,"
                            + "evidence_json text,"
                            + "confidence real not null default 0,"
                            + "priority integer not null default 0,"
                            + "dedup_key text,"
                            + "state_hash text,"
                            + "created_at integer not null,"
                            + "expires_at integer not null default 0,"
                            + "status text not null default 'PENDING',"
                            + "last_decision_id text,"
                            + "updated_at integer not null"
                            + ")");
            statement.execute(
                    "create table if not exists proactive_decisions ("
                            + "decision_id text primary key,"
                            + "tick_id text not null,"
                            + "candidate_id text,"
                            + "source_key text,"
                            + "decision text not null,"
                            + "reason text,"
                            + "message text,"
                            + "delivery_platform text,"
                            + "delivery_chat_id text,"
                            + "delivery_thread_id text,"
                            + "delivery_status text,"
                            + "delivery_error text,"
                            + "metadata_json text,"
                            + "created_at integer not null"
                            + ")");
            statement.execute(
                    "create table if not exists proactive_source_snapshots ("
                            + "source_type text not null,"
                            + "source_ref text not null,"
                            + "state_hash text not null,"
                            + "payload_json text,"
                            + "checked_at integer not null,"
                            + "primary key (source_type, source_ref)"
                            + ")");
            statement.execute(
                    "create index if not exists idx_proactive_candidates_pending on proactive_candidates(status, priority desc, created_at)");
            statement.execute(
                    "create index if not exists idx_proactive_candidates_dedup on proactive_candidates(dedup_key, state_hash)");
            statement.execute(
                    "create index if not exists idx_proactive_decisions_created on proactive_decisions(created_at)");
            statement.execute(
                    "create index if not exists idx_proactive_decisions_source_created on proactive_decisions(source_key, created_at)");
            statement.execute(
                    "create table if not exists agent_profiles ("
                            + "agent_name text primary key,"
                            + "display_name text,"
                            + "description text,"
                            + "role_prompt text,"
                            + "default_model text,"
                            + "model text,"
                            + "allowed_tools_json text,"
                            + "skills_json text,"
                            + "memory text,"
                            + "enabled integer not null default 1,"
                            + "last_used_at integer not null default 0,"
                            + "created_at integer not null,"
                            + "updated_at integer not null"
                            + ")");
            try {
                statement.execute("alter table agent_profiles add column display_name text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table agent_profiles add column description text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute("alter table agent_profiles add column default_model text");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table agent_profiles add column enabled integer not null default 1");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "alter table agent_profiles add column last_used_at integer not null default 0");
            } catch (Exception ignored) {
            }
            try {
                statement.execute(
                        "update agent_profiles set default_model = model where (default_model is null or default_model = '') and model is not null");
            } catch (Exception ignored) {
            }
            statement.execute("drop table if exists project_events");
            statement.execute("drop table if exists project_questions");
            statement.execute("drop table if exists project_runs");
            statement.execute("drop table if exists project_todos");
            statement.execute("drop table if exists project_agents");
            statement.execute("drop table if exists projects");
            statement.close();
        } finally {
            connection.close();
        }
    }

    /** 封装结果Set Meta辅助逻辑，降低主流程中的重复实现。 */
    private static class ResultSetMetaSupport {
        /**
         * 关闭当前组件持有的运行资源。
         *
         * @param resultSet 结果Set响应或执行结果。
         */
        private static void close(java.sql.ResultSet resultSet) {
            if (resultSet == null) {
                return;
            }
            try {
                resultSet.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 初始化会话搜索Index。
     *
     * @param statement statement 参数。
     */
    private void initSessionSearchIndex(Statement statement) {
        try {
            ResultSetMetaSupport.close(
                    statement.executeQuery("select tool_names from sessions_fts limit 1"));
        } catch (Exception ignored) {
            try {
                statement.execute("drop table if exists sessions_fts");
            } catch (Exception ignoredDrop) {
            }
        }
        try {
            statement.execute(
                    "create virtual table if not exists sessions_fts using fts5(session_id, title, compressed_summary, ndjson, tool_names, tool_calls)");
            statement.execute(
                    "insert into sessions_fts (session_id, title, compressed_summary, ndjson, tool_names, tool_calls) "
                            + "select s.session_id, s.title, s.compressed_summary, s.ndjson, '', '' from sessions s "
                            + "where not exists (select 1 from sessions_fts f where f.session_id = s.session_id)");
        } catch (Exception ignored) {
            // 这里保留兜底路径，避免兼容输入导致主流程中断。
        }
    }

    /**
     * 初始化Agent运行事件搜索Index。
     *
     * @param statement statement 参数。
     */
    private void initAgentRunEventSearchIndex(Statement statement) {
        try {
            statement.execute(
                    "create virtual table if not exists agent_run_events_fts using fts5(run_id, session_id, source_key, event_type, summary, metadata_json)");
        } catch (Exception ignored) {
            // 保留此处实现约束，避免后续维护时破坏既有行为。
        }
    }

    /**
     * 追加Column。
     *
     * @param statement statement 参数。
     * @param tableName table名称参数。
     * @param columnDefinition columnDefinition 参数。
     */
    private void addColumn(Statement statement, String tableName, String columnDefinition) {
        try {
            statement.execute("alter table " + tableName + " add column " + columnDefinition);
        } catch (Exception ignored) {
        }
    }
}
