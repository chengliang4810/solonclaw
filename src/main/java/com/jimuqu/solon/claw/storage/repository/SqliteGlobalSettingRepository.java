package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;

/** SQLite 全局设置仓储。 */
@RequiredArgsConstructor
public class SqliteGlobalSettingRepository extends SqliteRepositorySupport implements GlobalSettingRepository {
    /** 记录SQLiteGlobal设置中的数据库。 */
    private final SqliteDatabase database;

    @Override
    protected Connection getConnection() throws SQLException {
        return database.openConnection();
    }

    /**
     * 获取当前注册项或配置项。
     *
     * @param key 配置键或映射键。
     * @return 返回get结果。
     */
    @Override
    public String get(String key) throws SQLException {
        return queryOne(
                "select setting_value from global_settings where setting_key = ?",
                stmt -> stmt.setString(1, key),
                rs -> rs.getString(1)
        );
    }

    /**
     * 执行set相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    @Override
    public void set(String key, String value) throws SQLException {
        executeUpdate(
                "insert or replace into global_settings (setting_key, setting_value, updated_at) values (?, ?, ?)",
                stmt -> {
                    stmt.setString(1, key);
                    stmt.setString(2, safeValue(value));
                    stmt.setLong(3, System.currentTimeMillis());
                }
        );
    }

    /**
     * 执行remove相关逻辑。
     *
     * @param key 配置键或映射键。
     */
    @Override
    public void remove(String key) throws SQLException {
        executeUpdate(
                "delete from global_settings where setting_key = ?",
                stmt -> stmt.setString(1, key)
        );
    }
}