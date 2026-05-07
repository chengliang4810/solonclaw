package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 启动时恢复近期因 Agent pending 中断而未完成的会话。 */
public class PendingSessionRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(PendingSessionRecoveryService.class);
    private static final int MAX_AUTO_RESUME_SESSIONS = 20;
    private static final Set<String> AUTO_RESUME_REASONS =
            Collections.unmodifiableSet(
                    new LinkedHashSet<String>(
                            Arrays.asList(
                                    "restart_timeout",
                                    "shutdown_timeout",
                                    "restart_interrupted")));

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
            log.warn("recoverRecentPendingSessions failed", e);
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
            return agentSession.isPending() && isAutoResumeReason(agentSession.getPendingReason());
        } catch (Exception e) {
            log.debug(
                    "skip pending auto-resume: sessionId={}",
                    session == null ? "" : session.getSessionId(),
                    e);
            return false;
        }
    }

    private boolean isAutoResumeReason(String reason) {
        if (StrUtil.isBlank(reason)) {
            return false;
        }
        return AUTO_RESUME_REASONS.contains(reason.trim().toLowerCase(Locale.ROOT));
    }

    private boolean resume(SessionRecord session) {
        try {
            GatewayReply reply =
                    conversationOrchestrator.resumePending(
                            session.getSourceKey(), ConversationEventSink.noop());
            if (reply != null && !reply.isError()) {
                log.info(
                        "auto-resumed pending session: sourceKey={}, sessionId={}",
                        session.getSourceKey(),
                        session.getSessionId());
                return true;
            }
            log.warn(
                    "auto-resume pending session returned error: sourceKey={}, sessionId={}, reply={}",
                    session.getSourceKey(),
                    session.getSessionId(),
                    reply == null ? "" : reply.getContent());
        } catch (Exception e) {
            log.warn(
                    "auto-resume pending session failed: sourceKey={}, sessionId={}",
                    session.getSourceKey(),
                    session.getSessionId(),
                    e);
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
}
