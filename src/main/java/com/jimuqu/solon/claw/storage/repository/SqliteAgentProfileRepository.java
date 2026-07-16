package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.agent.AgentProfile;
import com.jimuqu.solon.claw.agent.AgentProfileRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** 负责SQLite Agent角色配置数据的持久化读写，隔离底层存储实现。 */
@RequiredArgsConstructor
public class SqliteAgentProfileRepository extends SqliteRepositorySupport
        implements AgentProfileRepository {
    /** 记录SQLiteAgent角色配置中的数据库。 */
    private final SqliteDatabase database;

    @Override
    protected Connection getConnection() throws SQLException {
        return database.openConnection();
    }

    /**
     * 执行save，服务于SQLiteAgent角色配置主流程相关逻辑。
     *
     * @param profile 文件或目录路径参数。
     * @return 返回save结果。
     */
    @Override
    public AgentProfile save(AgentProfile profile) throws SQLException {
        executeUpdate(
                "insert or replace into agent_profiles (agent_name, display_name, description, role_prompt, default_model, model, allowed_tools_json, skills_json, memory, enabled, last_used_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, profile.getAgentName());
                    stmt.setString(2, profile.getDisplayName());
                    stmt.setString(3, profile.getDescription());
                    stmt.setString(4, profile.getRolePrompt());
                    stmt.setString(5, profile.getDefaultModel());
                    stmt.setString(6, profile.getDefaultModel());
                    stmt.setString(7, profile.getAllowedToolsJson());
                    stmt.setString(8, profile.getSkillsJson());
                    stmt.setString(9, profile.getMemory());
                    stmt.setInt(10, profile.isEnabled() ? 1 : 0);
                    stmt.setLong(11, profile.getLastUsedAt());
                    stmt.setLong(12, profile.getCreatedAt());
                    stmt.setLong(13, profile.getUpdatedAt());
                });
        return profile;
    }

    /**
     * 根据名称查找对应数据。
     *
     * @param agentName Agent名称参数。
     * @return 返回按名称查找得到的结果。
     */
    @Override
    public AgentProfile findByName(String agentName) throws SQLException {
        return queryOne(
                "select * from agent_profiles where agent_name = ?",
                stmt -> stmt.setString(1, agentName),
                this::map);
    }

    /**
     * 列出全部。
     *
     * @return 返回全部列表。
     */
    @Override
    public List<AgentProfile> listAll() throws SQLException {
        return queryList("select * from agent_profiles order by updated_at desc", null, this::map);
    }

    /**
     * 根据名称删除对应数据。
     *
     * @param agentName Agent名称参数。
     */
    @Override
    public void deleteByName(String agentName) throws SQLException {
        executeUpdate(
                "delete from agent_profiles where agent_name = ?",
                stmt -> stmt.setString(1, agentName));
    }

    /**
     * 执行map相关逻辑。
     *
     * @param rs rs 参数。
     * @return 返回map结果。
     */
    private AgentProfile map(ResultSet rs) throws SQLException {
        AgentProfile profile = new AgentProfile();
        profile.setAgentName(rs.getString("agent_name"));
        profile.setDisplayName(rs.getString("display_name"));
        profile.setDescription(rs.getString("description"));
        profile.setRolePrompt(rs.getString("role_prompt"));
        profile.setDefaultModel(rs.getString("default_model"));
        profile.setAllowedToolsJson(rs.getString("allowed_tools_json"));
        profile.setSkillsJson(rs.getString("skills_json"));
        profile.setMemory(rs.getString("memory"));
        profile.setEnabled(rs.getInt("enabled") != 0);
        profile.setLastUsedAt(rs.getLong("last_used_at"));
        profile.setCreatedAt(rs.getLong("created_at"));
        profile.setUpdatedAt(rs.getLong("updated_at"));
        return profile;
    }
}
