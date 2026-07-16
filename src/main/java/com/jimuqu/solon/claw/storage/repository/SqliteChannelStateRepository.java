package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SQLite 渠道状态仓储实现。 */
@RequiredArgsConstructor
public class SqliteChannelStateRepository extends SqliteRepositorySupport implements ChannelStateRepository {
    /** 记录SQLite渠道状态中的数据库。 */
    private final SqliteDatabase database;

    @Override
    protected Connection getConnection() throws SQLException {
        return database.openConnection();
    }

    /**
     * 获取当前注册项或配置项。
     *
     * @param platform 平台参数。
     * @param scopeKey scope键标识或键值。
     * @param stateKey 状态键标识或键值。
     * @return 返回get结果。
     */
    @Override
    public String get(PlatformType platform, String scopeKey, String stateKey) throws SQLException {
        return queryOne(
                "select state_value from channel_states where platform = ? and scope_key = ? and state_key = ?",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, safeValue(scopeKey));
                    stmt.setString(3, safeValue(stateKey));
                },
                rs -> rs.getString(1)
        );
    }

    /**
     * 执行put相关逻辑。
     *
     * @param platform 平台参数。
     * @param scopeKey scope键标识或键值。
     * @param stateKey 状态键标识或键值。
     * @param stateValue 状态值参数。
     */
    @Override
    public void put(PlatformType platform, String scopeKey, String stateKey, String stateValue) throws SQLException {
        executeUpdate(
                "insert or replace into channel_states (platform, scope_key, state_key, state_value, updated_at) values (?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, safeValue(scopeKey));
                    stmt.setString(3, safeValue(stateKey));
                    stmt.setString(4, safeValue(stateValue));
                    stmt.setLong(5, System.currentTimeMillis());
                }
        );
    }

    /**
     * 执行delete，服务于SQLite渠道状态主流程相关逻辑。
     *
     * @param platform 平台参数。
     * @param scopeKey scope键标识或键值。
     * @param stateKey 状态键标识或键值。
     */
    @Override
    public void delete(PlatformType platform, String scopeKey, String stateKey) throws SQLException {
        executeUpdate(
                "delete from channel_states where platform = ? and scope_key = ? and state_key = ?",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, safeValue(scopeKey));
                    stmt.setString(3, safeValue(stateKey));
                }
        );
    }

    /**
     * 执行列表相关逻辑。
     *
     * @param platform 平台参数。
     * @param scopeKey scope键标识或键值。
     * @return 返回list结果。
     */
    @Override
    public List<StateItem> list(PlatformType platform, String scopeKey) throws SQLException {
        return queryList(
                "select state_key, state_value, updated_at from channel_states where platform = ? and scope_key = ? order by state_key asc",
                stmt -> {
                    stmt.setString(1, platformKey(platform));
                    stmt.setString(2, safeValue(scopeKey));
                },
                rs -> new StateItem(
                        rs.getString("state_key"),
                        rs.getString("state_value"),
                        rs.getLong("updated_at")
                )
        );
    }
}