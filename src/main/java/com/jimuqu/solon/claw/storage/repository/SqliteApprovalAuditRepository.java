package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SQLite 危险命令审批审计仓储。 */
@RequiredArgsConstructor
public class SqliteApprovalAuditRepository extends SqliteRepositorySupport
        implements ApprovalAuditRepository {
    /** 记录SQLite审批审计中的数据库。 */
    private final SqliteDatabase database;

    @Override
    protected Connection getConnection() throws SQLException {
        return database.openConnection();
    }

    /**
     * 执行append相关逻辑。
     *
     * @param event 事件参数。
     */
    @Override
    public void append(ApprovalAuditEvent event) throws SQLException {
        if (event == null || event.getEventId() == null) {
            return;
        }
        executeUpdate(
                "insert or replace into approval_audit_events (event_id, session_id, event_type, choice, outcome, status, approved, approver, tool_name, approval_id, approval_key, command_hash, command_preview, description, pattern_keys_json, created_at, approval_created_at, approval_expires_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stmt -> {
                    stmt.setString(1, event.getEventId());
                    stmt.setString(2, event.getSessionId());
                    stmt.setString(3, event.getEventType());
                    stmt.setString(4, event.getChoice());
                    stmt.setString(5, event.getOutcome());
                    stmt.setString(6, event.getStatus());
                    stmt.setInt(7, event.isApproved() ? 1 : 0);
                    stmt.setString(8, event.getApprover());
                    stmt.setString(9, event.getToolName());
                    stmt.setString(10, event.getApprovalId());
                    stmt.setString(11, event.getApprovalKey());
                    stmt.setString(12, event.getCommandHash());
                    stmt.setString(13, event.getCommandPreview());
                    stmt.setString(14, event.getDescription());
                    stmt.setString(15, event.getPatternKeysJson());
                    stmt.setLong(16, event.getCreatedAt());
                    stmt.setLong(17, event.getApprovalCreatedAt());
                    stmt.setLong(18, event.getApprovalExpiresAt());
                });
    }

    /**
     * 列出Recent。
     *
     * @param limit 最大返回数量。
     * @return 返回Recent列表。
     */
    @Override
    public List<ApprovalAuditEvent> listRecent(int limit) throws SQLException {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500));
        return queryList(
                "select event_id, session_id, event_type, choice, outcome, status, approved, approver, tool_name, approval_id, approval_key, command_hash, command_preview, description, pattern_keys_json, created_at, approval_created_at, approval_expires_at from approval_audit_events order by created_at desc limit ?",
                stmt -> stmt.setInt(1, effectiveLimit),
                this::map);
    }

    /**
     * 执行map相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map结果。
     */
    private ApprovalAuditEvent map(ResultSet resultSet) throws SQLException {
        ApprovalAuditEvent event = new ApprovalAuditEvent();
        event.setEventId(resultSet.getString("event_id"));
        event.setSessionId(resultSet.getString("session_id"));
        event.setEventType(resultSet.getString("event_type"));
        event.setChoice(resultSet.getString("choice"));
        event.setOutcome(resultSet.getString("outcome"));
        event.setStatus(resultSet.getString("status"));
        event.setApproved(resultSet.getInt("approved") != 0);
        event.setApprover(resultSet.getString("approver"));
        event.setToolName(resultSet.getString("tool_name"));
        event.setApprovalId(resultSet.getString("approval_id"));
        event.setApprovalKey(resultSet.getString("approval_key"));
        event.setCommandHash(resultSet.getString("command_hash"));
        event.setCommandPreview(resultSet.getString("command_preview"));
        event.setDescription(resultSet.getString("description"));
        event.setPatternKeysJson(resultSet.getString("pattern_keys_json"));
        event.setCreatedAt(resultSet.getLong("created_at"));
        event.setApprovalCreatedAt(resultSet.getLong("approval_created_at"));
        event.setApprovalExpiresAt(resultSet.getLong("approval_expires_at"));
        return event;
    }
}
