package com.jimuqu.solon.claw.storage.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 承载SQLite执行器相关状态和辅助逻辑。 */
public class SqliteExecutor {
    /** 记录SQLite执行器中的数据库。 */
    private final SqliteDatabase database;

    /**
     * 创建SQLite执行器实例，并注入运行所需依赖。
     *
     * @param database database 参数。
     */
    public SqliteExecutor(SqliteDatabase database) {
        this.database = database;
    }

    /**
     * 执行查询相关逻辑。
     *
     * @param sql sql 参数。
     * @param binder binder 参数。
     * @param mapper mapper 参数。
     * @return 返回query结果。
     */
    public <T> T query(String sql, Binder binder, RowMapper<T> mapper) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                if (binder != null) {
                    binder.bind(statement);
                }
                ResultSet resultSet = statement.executeQuery();
                try {
                    return mapper.map(resultSet);
                } finally {
                    resultSet.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param sql sql 参数。
     * @param binder binder 参数。
     * @return 返回更新结果。
     */
    public int update(String sql, Binder binder) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                if (binder != null) {
                    binder.bind(statement);
                }
                return statement.executeUpdate();
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 执行transaction相关逻辑。
     *
     * @param callback 回调参数。
     * @return 返回transaction结果。
     */
    public <T> T transaction(TransactionCallback<T> callback) throws Exception {
        Connection connection = database.openConnection();
        boolean oldAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            T result = callback.doInTransaction(connection);
            connection.commit();
            return result;
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
            connection.close();
        }
    }

    /** 定义Binder的抽象契约，供不同运行时实现保持一致行为。 */
    public interface Binder {
        /**
         * 执行bind相关逻辑。
         *
         * @param statement statement 参数。
         */
        void bind(PreparedStatement statement) throws Exception;
    }

    /** 定义Row Mapper的抽象契约，供不同运行时实现保持一致行为。 */
    public interface RowMapper<T> {
        /**
         * 执行map相关逻辑。
         *
         * @param resultSet 结果Set响应或执行结果。
         * @return 返回map结果。
         */
        T map(ResultSet resultSet) throws Exception;
    }

    /** 定义Transaction Callback的抽象契约，供不同运行时实现保持一致行为。 */
    public interface TransactionCallback<T> {
        /**
         * 执行doInTransaction相关逻辑。
         *
         * @param connection 连接参数。
         * @return 返回do In Transaction结果。
         */
        T doInTransaction(Connection connection) throws Exception;
    }
}
