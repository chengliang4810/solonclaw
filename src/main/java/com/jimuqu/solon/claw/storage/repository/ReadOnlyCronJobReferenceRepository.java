package com.jimuqu.solon.claw.storage.repository;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 以 SQLite 只读模式查询 Cron 的 Provider/模型引用，禁止 schema 初始化和数据写入。 */
public final class ReadOnlyCronJobReferenceRepository {
    /** SQLite 只读 URI；mode=ro 会在数据库文件不存在或发生写入时直接失败。 */
    private final String jdbcUrl;

    /**
     * 创建 Cron 引用只读仓储。
     *
     * @param stateDb 目标 Profile 的 data/state.db。
     * @throws IOException 数据库文件不存在或不是普通文件。
     */
    public ReadOnlyCronJobReferenceRepository(Path stateDb) throws IOException {
        if (stateDb == null || !Files.isRegularFile(stateDb)) {
            throw new IOException("Profile cron database does not exist: " + stateDb);
        }
        Path normalized = stateDb.toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + normalized.toUri().toASCIIString() + "?mode=ro";
    }

    /**
     * 查询固定绑定到指定 Provider 的最小 Cron 引用。
     *
     * @param providerKey Provider 键。
     * @return 仅包含任务 ID、Provider 和模型的引用列表；数据库尚无 Cron 表时返回空列表。
     * @throws Exception 只读连接或查询失败。
     */
    public List<CronJobRecord> listByProvider(String providerKey) throws Exception {
        String normalizedProvider = StrUtil.nullToEmpty(providerKey).trim();
        if (StrUtil.isBlank(normalizedProvider)) {
            return Collections.emptyList();
        }
        try (Connection connection = openConnection()) {
            if (!hasCronJobsTable(connection)) {
                return Collections.emptyList();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "select job_id, provider, model from cron_jobs where trim(provider) = ?")) {
                statement.setString(1, normalizedProvider);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<CronJobRecord> result = new ArrayList<CronJobRecord>();
                    while (resultSet.next()) {
                        CronJobRecord job = new CronJobRecord();
                        job.setJobId(resultSet.getString("job_id"));
                        job.setProvider(resultSet.getString("provider"));
                        job.setModel(resultSet.getString("model"));
                        result.add(job);
                    }
                    return result;
                }
            }
        }
    }

    /**
     * 查询全部 Cron Provider/模型引用。
     *
     * @return 仅包含任务 ID、Provider 和模型的引用列表；数据库尚无 Cron 表时返回空列表。
     * @throws Exception 只读连接或查询失败。
     */
    public List<CronJobRecord> listAll() throws Exception {
        try (Connection connection = openConnection()) {
            if (!hasCronJobsTable(connection)) {
                return Collections.emptyList();
            }
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "select job_id, provider, model from cron_jobs");
                    ResultSet resultSet = statement.executeQuery()) {
                List<CronJobRecord> result = new ArrayList<CronJobRecord>();
                while (resultSet.next()) {
                    CronJobRecord job = new CronJobRecord();
                    job.setJobId(resultSet.getString("job_id"));
                    job.setProvider(resultSet.getString("provider"));
                    job.setModel(resultSet.getString("model"));
                    result.add(job);
                }
                return result;
            }
        }
    }

    /** 打开启用 query_only 的短生命周期只读连接。 */
    private Connection openConnection() throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("pragma query_only=ON");
                statement.execute("pragma busy_timeout=5000");
            }
            return connection;
        } catch (Exception e) {
            connection.close();
            throw e;
        }
    }

    /** 判断当前数据库是否已经存在 Cron 主表。 */
    private boolean hasCronJobsTable(Connection connection) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "select 1 from sqlite_master where type = 'table' and name = 'cron_jobs' limit 1")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
