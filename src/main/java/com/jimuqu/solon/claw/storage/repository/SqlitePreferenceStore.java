package com.jimuqu.solon.claw.storage.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;

/** SqlitePreferenceStore 实现。 */
@RequiredArgsConstructor
public class SqlitePreferenceStore extends SqliteRepositorySupport {
    /** GLOBAL范围的统一常量值。 */
    public static final String GLOBAL_SCOPE = "__global__";

    /** 记录SQLitePreferenceStore中的数据库。 */
    private final SqliteDatabase database;

    @Override
    protected Connection getConnection() throws SQLException {
        return database.openConnection();
    }

    /**
     * 判断是否工具启用。
     *
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @return 如果工具启用满足条件则返回 true，否则返回 false。
     */
    public boolean isToolEnabled(String sourceKey, String toolName) throws SQLException {
        return readScopedBoolean("tool_toggles", "tool_name", sourceKey, toolName, true);
    }

    /**
     * 判断是否工具启用。
     *
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @param defaultValue 默认值参数。
     * @return 如果工具启用满足条件则返回 true，否则返回 false。
     */
    public boolean isToolEnabled(String sourceKey, String toolName, boolean defaultValue) throws SQLException {
        return readScopedBoolean("tool_toggles", "tool_name", sourceKey, toolName, defaultValue);
    }

    /**
     * 判断是否存在Scoped工具Toggle。
     *
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @return 如果Scoped工具Toggle满足条件则返回 true，否则返回 false。
     */
    public boolean hasScopedToolToggle(String sourceKey, String toolName) throws SQLException {
        return readBooleanIfPresent("tool_toggles", "tool_name", sourceKey, toolName) != null;
    }

    /**
     * 写入工具启用。
     *
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @param enabled 启用状态开关值。
     */
    public void setToolEnabled(String sourceKey, String toolName, boolean enabled) throws SQLException {
        writeBoolean("tool_toggles", "tool_name", sourceKey, toolName, enabled);
    }

    /**
     * 判断是否工具启用 Global。
     *
     * @param toolName 工具名称。
     * @return 如果工具启用 Global满足条件则返回 true，否则返回 false。
     */
    public boolean isToolEnabledGlobal(String toolName) throws SQLException {
        return readBoolean("tool_toggles", "tool_name", GLOBAL_SCOPE, toolName, true);
    }

    /**
     * 写入工具启用 Global。
     *
     * @param toolName 工具名称。
     * @param enabled 启用状态开关值。
     */
    public void setToolEnabledGlobal(String toolName, boolean enabled) throws SQLException {
        writeBoolean("tool_toggles", "tool_name", GLOBAL_SCOPE, toolName, enabled);
    }

    /**
     * 判断是否技能启用。
     *
     * @param sourceKey 渠道来源键。
     * @param skillName 技能名称参数。
     * @return 如果技能启用满足条件则返回 true，否则返回 false。
     */
    public boolean isSkillEnabled(String sourceKey, String skillName) throws SQLException {
        return readScopedBoolean("skill_states", "skill_name", sourceKey, skillName, true);
    }

    /**
     * 写入技能启用。
     *
     * @param sourceKey 渠道来源键。
     * @param skillName 技能名称参数。
     * @param enabled 启用状态开关值。
     */
    public void setSkillEnabled(String sourceKey, String skillName, boolean enabled) throws SQLException {
        writeBoolean("skill_states", "skill_name", sourceKey, skillName, enabled);
    }

    /**
     * 判断是否技能启用 Global。
     *
     * @param skillName 技能名称参数。
     * @return 如果技能启用 Global满足条件则返回 true，否则返回 false。
     */
    public boolean isSkillEnabledGlobal(String skillName) throws SQLException {
        return readBoolean("skill_states", "skill_name", GLOBAL_SCOPE, skillName, true);
    }

    /**
     * 写入技能启用 Global。
     *
     * @param skillName 技能名称参数。
     * @param enabled 启用状态开关值。
     */
    public void setSkillEnabledGlobal(String skillName, boolean enabled) throws SQLException {
        writeBoolean("skill_states", "skill_name", GLOBAL_SCOPE, skillName, enabled);
    }

    /**
     * 读取Scoped Boolean。
     *
     * @param tableName table名称参数。
     * @param nameColumn 名称Column参数。
     * @param sourceKey 渠道来源键。
     * @param nameValue 名称值参数。
     * @param defaultValue 默认值参数。
     * @return 返回读取到的Scoped Boolean。
     */
    private boolean readScopedBoolean(
            String tableName, String nameColumn, String sourceKey, String nameValue, boolean defaultValue) throws SQLException {
        Boolean scoped = readBooleanIfPresent(tableName, nameColumn, sourceKey, nameValue);
        if (scoped != null) {
            return scoped.booleanValue();
        }

        Boolean global = readBooleanIfPresent(tableName, nameColumn, GLOBAL_SCOPE, nameValue);
        if (global != null) {
            return global.booleanValue();
        }

        return defaultValue;
    }

    /**
     * 读取Boolean。
     *
     * @param tableName table名称参数。
     * @param nameColumn 名称Column参数。
     * @param sourceKey 渠道来源键。
     * @param nameValue 名称值参数。
     * @param defaultValue 默认值参数。
     * @return 返回读取到的Boolean。
     */
    private boolean readBoolean(
            String tableName, String nameColumn, String sourceKey, String nameValue, boolean defaultValue) throws SQLException {
        Boolean value = readBooleanIfPresent(tableName, nameColumn, sourceKey, nameValue);
        return value == null ? defaultValue : value.booleanValue();
    }

    /**
     * 读取Boolean If Present。
     *
     * @param tableName table名称参数。
     * @param nameColumn 名称Column参数。
     * @param sourceKey 渠道来源键。
     * @param nameValue 名称值参数。
     * @return 返回读取到的Boolean If Present。
     */
    private Boolean readBooleanIfPresent(String tableName, String nameColumn, String sourceKey, String nameValue) throws SQLException {
        String sql = "select enabled from " + tableName + " where source_key = ? and " + nameColumn + " = ?";
        return queryOne(
                sql,
                stmt -> {
                    stmt.setString(1, sourceKey);
                    stmt.setString(2, nameValue);
                },
                rs -> rs.getInt(1) == 1
        );
    }

    /**
     * 写入Boolean。
     *
     * @param tableName table名称参数。
     * @param nameColumn 名称Column参数。
     * @param sourceKey 渠道来源键。
     * @param nameValue 名称值参数。
     * @param enabled 启用状态开关值。
     */
    private void writeBoolean(String tableName, String nameColumn, String sourceKey, String nameValue, boolean enabled) throws SQLException {
        String sql = "insert or replace into " + tableName + " (source_key, " + nameColumn + ", enabled) values (?, ?, ?)";
        executeUpdate(
                sql,
                stmt -> {
                    stmt.setString(1, sourceKey);
                    stmt.setString(2, nameValue);
                    stmt.setInt(3, enabled ? 1 : 0);
                }
        );
    }
}