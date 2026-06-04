package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 启动时恢复近期因 Agent pending 中断而未完成的会话。 */
public class PendingSessionRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(PendingSessionRecoveryService.class);
    private static final int MAX_AUTO_RESUME_SESSIONS = 20;

    private final AppConfig appConfig;
    private final SessionRepository sessionRepository;
    private final ConversationOrchestrator conversationOrchestrator;

    public PendingSessionRecoveryService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.conversationOrchestrator = conversationOrchestrator;
    }

    public int recoverRecentPendingSessions() {
        if (sessionRepository == null || conversationOrchestrator == null) {
            return 0;
        }
        long windowMillis = recoveryWindowMillis();
        long updatedAfter = System.currentTimeMillis() - windowMillis;
        int recovered = 0;
        try {
            List<SessionRecord> candidates =
                    sessionRepository.listPendingAgentSessions(
                            updatedAfter, MAX_AUTO_RESUME_SESSIONS);
            for (SessionRecord session : candidates) {
                if (!shouldAutoResume(session, updatedAfter)) {
                    continue;
                }
                if (resume(session)) {
                    recovered++;
                }
            }
        } catch (Exception e) {
            log.warn("recoverRecentPendingSessions failed: error={}", safeError(e));
        }
        return recovered;
    }

    private boolean shouldAutoResume(SessionRecord session, long updatedAfter) {
        if (session == null || StrUtil.isBlank(session.getSourceKey())) {
            return false;
        }
        if (session.getUpdatedAt() < updatedAfter) {
            return false;
        }
        try {
            SqliteAgentSession agentSession = new SqliteAgentSession(session);
            return agentSession.isPending()
                    && ResumePendingSupport.isGatewayInterruptionReason(
                            agentSession.getPendingReason());
        } catch (Exception e) {
            log.debug(
                    "skip pending auto-resume: sessionId={}, error={}",
                    session == null ? "" : session.getSessionId(),
                    safeError(e));
            return false;
        }
    }

    private boolean resume(SessionRecord session) {
        try {
            SqliteAgentSession agentSession = new SqliteAgentSession(session);
            String pendingReason = agentSession.getPendingReason();
            long pendingMarkedAt = agentSession.getPendingMarkedAt();
            GatewayReply reply =
                    conversationOrchestrator.resumePending(
                            session.getSourceKey(),
                            session.getSessionId(),
                            ConversationEventSink.noop());
            if (reply != null && !reply.isError()) {
                log.info(
                        "auto-resumed pending session: sourceKey={}, sessionId={}, reason={}, markedAt={}",
                        session.getSourceKey(),
                        session.getSessionId(),
                        StrUtil.blankToDefault(pendingReason, "unknown"),
                        Long.valueOf(pendingMarkedAt));
                return true;
            }
            log.warn(
                    "auto-resume pending session returned error: sourceKey={}, sessionId={}, reply={}",
                    session.getSourceKey(),
                    session.getSessionId(),
                    reply == null ? "" : reply.getContent());
        } catch (Exception e) {
            log.warn(
                    "auto-resume pending session failed: sourceKey={}, sessionId={}, error={}",
                    session.getSourceKey(),
                    session.getSessionId(),
                    safeError(e));
        }
        return false;
    }

    private long recoveryWindowMillis() {
        int staleAfterMinutes =
                appConfig == null || appConfig.getTask() == null
                        ? 60
                        : appConfig.getTask().getStaleAfterMinutes();
        long minutes = Math.max(1L, staleAfterMinutes);
        return minutes * 60L * 1000L;
    }

    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message;
        return SecretRedactor.redact(value, 1000);
    }
}
