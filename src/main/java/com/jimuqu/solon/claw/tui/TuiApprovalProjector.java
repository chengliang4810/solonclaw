package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects pending approvals and approval observer events into TUI events. */
public class TuiApprovalProjector implements DangerousCommandApprovalService.ApprovalObserver {
    private final SessionRepository sessionRepository;
    private final DangerousCommandApprovalService approvalService;
    private final TuiGatewayEventSink eventSink;

    public TuiApprovalProjector(
            SessionRepository sessionRepository,
            DangerousCommandApprovalService approvalService,
            TuiGatewayEventSink eventSink) {
        this.sessionRepository = sessionRepository;
        this.approvalService = approvalService;
        this.eventSink = eventSink;
    }

    public Map<String, Object> pendingSnapshot(String sessionId) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> approvals = new ArrayList<Map<String, Object>>();
        if (approvalService != null && sessionRepository != null && StrUtil.isNotBlank(sessionId)) {
            try {
                SessionRecord record = sessionRepository.findById(sessionId);
                for (DangerousCommandApprovalService.PendingApproval pending :
                        approvalService.listPendingApprovals(record)) {
                    approvals.add(toApproval(pending, "pending", sessionId, ""));
                }
            } catch (Exception ignored) {
            }
        }
        payload.put("session_id", safe(sessionId, 160));
        payload.put("approvals", approvals);
        payload.put("pending_count", Integer.valueOf(approvals.size()));
        return payload;
    }

    public Map<String, Object> resolve(
            String sessionId, String selector, String scope, boolean approve, String approver)
            throws Exception {
        if (approvalService == null || sessionRepository == null || StrUtil.isBlank(sessionId)) {
            throw new IllegalStateException("approval service unavailable");
        }
        SessionRecord record = sessionRepository.findById(sessionId);
        if (record == null) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        SqliteAgentSession agentSession = new SqliteAgentSession(record, sessionRepository);
        boolean ok;
        if (approve) {
            ok =
                    approvalService.approve(
                            agentSession,
                            selector,
                            approvalScope(scope),
                            StrUtil.blankToDefault(approver, "tui"));
        } else {
            ok = approvalService.reject(agentSession, selector, StrUtil.blankToDefault(approver, "tui"));
        }
        Map<String, Object> payload = pendingSnapshot(sessionId);
        payload.put("ok", Boolean.valueOf(ok));
        payload.put("choice", approve ? "approve" : "deny");
        payload.put("selector", safe(selector, 400));
        return payload;
    }

    @Override
    public void onApprovalRequest(DangerousCommandApprovalService.ApprovalRequestEvent event) {
        if (eventSink == null || event == null) {
            return;
        }
        Map<String, Object> payload =
                toApproval(event.getPendingApproval(), "pending", event.getSessionId(), "");
        eventSink.publish(
                new TuiEvent(
                        "approval.request",
                        event.getSessionId(),
                        approvalSeq(event.getPendingApproval()),
                        payload));
    }

    @Override
    public void onApprovalResponse(DangerousCommandApprovalService.ApprovalResponseEvent event) {
        if (eventSink == null || event == null) {
            return;
        }
        Map<String, Object> payload =
                toApproval(
                        event.getPendingApproval(),
                        "deny".equals(event.getChoice()) ? "denied" : "approved",
                        event.getSessionId(),
                        event.getChoice());
        payload.put("approver", safe(event.getApprover(), 200));
        eventSink.publish(
                new TuiEvent(
                        "approval.response",
                        event.getSessionId(),
                        System.currentTimeMillis() * 1000L + 700L,
                        payload));
    }

    private DangerousCommandApprovalService.ApprovalScope approvalScope(String value) {
        String normalized = StrUtil.blankToDefault(value, "once").trim().toLowerCase(java.util.Locale.ROOT);
        if ("session".equals(normalized)) {
            return DangerousCommandApprovalService.ApprovalScope.SESSION;
        }
        if ("always".equals(normalized)) {
            return DangerousCommandApprovalService.ApprovalScope.ALWAYS;
        }
        return DangerousCommandApprovalService.ApprovalScope.ONCE;
    }

    private Map<String, Object> toApproval(
            DangerousCommandApprovalService.PendingApproval pending,
            String status,
            String sessionId,
            String choice) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (pending == null) {
            return payload;
        }
        String selector = DangerousCommandApprovalService.approvalSelector(pending);
        payload.put("id", safe(selector, 400));
        payload.put("approvalId", safe(selector, 400));
        payload.put("approval_id", safe(pending.getApprovalId(), 200));
        payload.put("selector", safe(selector, 400));
        payload.put("session_id", safe(sessionId, 160));
        payload.put("tool_name", safe(pending.getToolName(), 160));
        payload.put("title", "需要审批：" + safe(StrUtil.blankToDefault(pending.getToolName(), "工具调用"), 120));
        payload.put(
                "reason",
                safe(StrUtil.blankToDefault(pending.getDescription(), "该操作需要人工确认"), 1000));
        payload.put("command", safe(pending.getCommand(), 3000));
        payload.put("pattern_keys", safeList(pending.effectivePatternKeys(), 400));
        payload.put("risk", risk(pending));
        payload.put("createdAt", Long.valueOf(pending.getCreatedAt()));
        payload.put("created_at", Long.valueOf(pending.getCreatedAt()));
        payload.put("expiresAt", Long.valueOf(pending.getExpiresAt()));
        payload.put("expires_at", Long.valueOf(pending.getExpiresAt()));
        payload.put("status", StrUtil.blankToDefault(status, "pending"));
        payload.put("choice", safe(choice, 80));
        payload.put("permanent_allowed", Boolean.valueOf(pending.isPermanentApprovalAllowed()));
        return payload;
    }

    private String risk(DangerousCommandApprovalService.PendingApproval pending) {
        if (pending == null) {
            return "medium";
        }
        for (String key : pending.effectivePatternKeys()) {
            String value = StrUtil.nullToEmpty(key).toLowerCase(java.util.Locale.ROOT);
            if (value.contains("hardline")
                    || value.contains("delete")
                    || value.contains("credential")
                    || value.contains("private")) {
                return "high";
            }
        }
        return pending.isPermanentApprovalAllowed() ? "medium" : "high";
    }

    private long approvalSeq(DangerousCommandApprovalService.PendingApproval pending) {
        long base = pending == null ? 0L : pending.getCreatedAt();
        return (base <= 0L ? System.currentTimeMillis() : base) * 1000L + 700L;
    }

    private List<String> safeList(List<String> values, int maxLength) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> safeValues = new ArrayList<String>();
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                safeValues.add(safe(value, maxLength));
            }
        }
        return safeValues;
    }

    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }
}
