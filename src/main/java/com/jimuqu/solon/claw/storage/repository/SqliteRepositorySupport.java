package com.jimuqu.solon.claw.storage.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** SQLite 仓库支持基类，封装通用的数据库操作模板方法。 提供连接管理、查询映射、事务管理等通用功能，消除子类中的重复代码。 */
public abstract class SqliteRepositorySupport {

    /** 行映射器函数式接口。 */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** 语句绑定器函数式接口。 */
    @FunctionalInterface
    public interface StatementBinder {
        void bind(PreparedStatement stmt) throws SQLException;
    }

    /** 事务回调函数式接口。 */
    @FunctionalInterface
    public interface TransactionCallback<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
     * 获取数据库连接。
     *
     * @return 数据库连接
     * @throws SQLException 如果获取连接失败
     */
    protected abstract Connection getConnection() throws SQLException;

    /**
     * 查询单条记录。
     *
     * @param sql SQL查询语句
     * @param binder 参数绑定器
     * @param mapper 行映射器
     * @return 查询结果，如果没有记录则返回null
     * @throws SQLException 如果查询失败
     */
    protected <T> T queryOne(String sql, StatementBinder binder, RowMapper<T> mapper)
            throws SQLException {
        try (Connection connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) {
                binder.bind(statement);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return mapper.map(resultSet);
                }
            }
        }
        return null;
    }

    /**
     * 查询记录列表。
     *
     * @param sql SQL查询语句
     * @param binder 参数绑定器
     * @param mapper 行映射器
     * @return 查询结果列表
     * @throws SQLException 如果查询失败
     */
    protected <T> List<T> queryList(String sql, StatementBinder binder, RowMapper<T> mapper)
            throws SQLException {
        List<T> results = new ArrayList<>();
        try (Connection connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) {
                binder.bind(statement);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(mapper.map(resultSet));
                }
            }
        }
        return results;
    }

    /**
     * 执行更新操作（INSERT, UPDATE, DELETE）。
     *
     * @param sql SQL更新语句
     * @param binder 参数绑定器
     * @return 受影响的行数
     * @throws SQLException 如果更新失败
     */
    protected int executeUpdate(String sql, StatementBinder binder) throws SQLException {
        try (Connection connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) {
                binder.bind(statement);
            }
            return statement.executeUpdate();
        }
    }

    /**
     * 查询单个整数值。
     *
     * @param sql SQL查询语句
     * @param binder 参数绑定器
     * @return 查询结果，如果没有记录则返回0
     * @throws SQLException 如果查询失败
     */
    protected int queryInt(String sql, StatementBinder binder) throws SQLException {
        try (Connection connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) {
                binder.bind(statement);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        }
        return 0;
    }

    /**
     * 在事务中执行操作。
     *
     * @param callback 事务回调
     * @return 事务执行结果
     * @throws SQLException 如果事务执行失败
     */
    protected <T> T inTransaction(TransactionCallback<T> callback) throws SQLException {
        Connection connection = getConnection();
        try {
            connection.setAutoCommit(false);
            T result = callback.execute(connection);
            connection.commit();
            return result;
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new SQLException("事务执行失败", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                /* 关闭连接前重置自动提交失败可忽略 */
            }
            connection.close();
        }
    }

    /**
     * 获取平台类型键。
     *
     * @param platform 平台类型
     * @return 平台类型字符串
     */
    protected static String platformKey(Object platform) {
        if (platform == null) {
            return "UNKNOWN";
        }
        if (platform instanceof Enum) {
            return ((Enum<?>) platform).name();
        }
        return platform.toString();
    }

    /**
     * 安全获取字符串值。
     *
     * @param value 原始值
     * @return 如果原始值为null则返回空字符串，否则返回原始值
     */
    protected static String safeValue(String value) {
        return value == null ? "" : value;
    }
}
