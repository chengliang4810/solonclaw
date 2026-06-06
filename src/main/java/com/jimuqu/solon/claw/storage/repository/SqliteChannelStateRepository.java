package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SQLite 渠道状态仓储实现。 */
@RequiredArgsConstructor
public class SqliteChannelStateRepository implements ChannelStateRepository {
    /** 记录SQLite渠道状态中的数据库。 */
    private final SqliteDatabase database;

    /**
     * 获取当前注册项或配置项。
     *
     * @param platform 平台参数。
     * @param scopeKey scope键标识或键值。
     * @param stateKey 状态键标识或键值。
     * @return 返回get结果。
     */
    @Override
    public String get(PlatformType platform, String scopeKey, String stateKey) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select state_value from channel_states where platform = ? and scope_key = ? and state_key = ?");
            statement.setString(1, key(platform));
            statement.setString(2, safe(scopeKey));
            statement.setString(3, safe(stateKey));
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? resultSet.getString(1) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
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
    public void put(PlatformType platform, String scopeKey, String stateKey, String stateValue)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into channel_states (platform, scope_key, state_key, state_value, updated_at) values (?, ?, ?, ?, ?)");
            statement.setString(1, key(platform));
            statement.setString(2, safe(scopeKey));
            statement.setString(3, safe(stateKey));
            statement.setString(4, safe(stateValue));
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 执行delete，服务于SQLite渠道状态主流程相关逻辑。
     *
     * @param platform 平台参数。
     * @param scopeKey scope键标识或键值。
     * @param stateKey 状态键标识或键值。
     */
    @Override
    public void delete(PlatformType platform, String scopeKey, String stateKey) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "delete from channel_states where platform = ? and scope_key = ? and state_key = ?");
            statement.setString(1, key(platform));
            statement.setString(2, safe(scopeKey));
            statement.setString(3, safe(stateKey));
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /**
     * 执行列表相关逻辑。
     *
     * @param platform 平台参数。
     * @param scopeKey scope键标识或键值。
     * @return 返回list结果。
     */
    @Override
    public List<StateItem> list(PlatformType platform, String scopeKey) throws Exception {
        List<StateItem> items = new ArrayList<StateItem>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select state_key, state_value, updated_at from channel_states where platform = ? and scope_key = ? order by state_key asc");
            statement.setString(1, key(platform));
            statement.setString(2, safe(scopeKey));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    items.add(
                            new StateItem(
                                    resultSet.getString("state_key"),
                                    resultSet.getString("state_value"),
                                    resultSet.getLong("updated_at")));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return items;
    }

    /**
     * 执行键相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回键结果。
     */
    private String key(PlatformType platform) {
        return platform == null ? "UNKNOWN" : platform.name();
    }

    /**
     * 执行安全相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe结果。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
