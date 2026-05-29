package com.jimuqu.solon.claw.storage.repository;

import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** SQLite 危险命令审批审计仓储。 */
@RequiredArgsConstructor
public class SqliteApprovalAuditRepository implements ApprovalAuditRepository {
    private final SqliteDatabase database;

    @Override
    public void append(ApprovalAuditEvent event) throws Exception {
        if (event == null || event.getEventId() == null) {
            return;
        }
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into approval_audit_events (event_id, session_id, event_type, choice, approver, tool_name, approval_id, approval_key, command_hash, command_preview, description, pattern_keys_json, created_at, approval_created_at, approval_expires_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, event.getEventId());
            statement.setString(2, event.getSessionId());
            statement.setString(3, event.getEventType());
            statement.setString(4, event.getChoice());
            statement.setString(5, event.getApprover());
            statement.setString(6, event.getToolName());
            statement.setString(7, event.getApprovalId());
            statement.setString(8, event.getApprovalKey());
            statement.setString(9, event.getCommandHash());
            statement.setString(10, event.getCommandPreview());
            statement.setString(11, event.getDescription());
            statement.setString(12, event.getPatternKeysJson());
            statement.setLong(13, event.getCreatedAt());
            statement.setLong(14, event.getApprovalCreatedAt());
            statement.setLong(15, event.getApprovalExpiresAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public List<ApprovalAuditEvent> listRecent(int limit) throws Exception {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500));
        List<ApprovalAuditEvent> items = new ArrayList<ApprovalAuditEvent>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select event_id, session_id, event_type, choice, approver, tool_name, approval_id, approval_key, command_hash, command_preview, description, pattern_keys_json, created_at, approval_created_at, approval_expires_at from approval_audit_events order by created_at desc limit ?");
            statement.setInt(1, effectiveLimit);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    items.add(map(resultSet));
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

    private ApprovalAuditEvent map(ResultSet resultSet) throws Exception {
        ApprovalAuditEvent event = new ApprovalAuditEvent();
        event.setEventId(resultSet.getString("event_id"));
        event.setSessionId(resultSet.getString("session_id"));
        event.setEventType(resultSet.getString("event_type"));
        event.setChoice(resultSet.getString("choice"));
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
