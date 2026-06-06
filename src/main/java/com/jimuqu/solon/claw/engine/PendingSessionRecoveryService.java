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
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(PendingSessionRecoveryService.class);

    /** 最大AUTORESUMESESSIONS的统一常量值。 */
    private static final int MAX_AUTO_RESUME_SESSIONS = 20;

    /** 注入应用配置，用于待恢复会话恢复。 */
    private final AppConfig appConfig;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录待恢复会话恢复中的对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /**
     * 创建Pending会话Recovery服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     */
    public PendingSessionRecoveryService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.conversationOrchestrator = conversationOrchestrator;
    }

    /**
     * 恢复Recent待恢复Sessions。
     *
     * @return 返回recover Recent Pending Sessions结果。
     */
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

    /**
     * 判断是否需要Auto Resume。
     *
     * @param session 会话参数。
     * @param updatedAfter updatedAfter 参数。
     * @return 如果Auto Resume满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 执行resume相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回resume结果。
     */
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

    /**
     * 执行恢复窗口Millis相关逻辑。
     *
     * @return 返回recovery Window Millis结果。
     */
    private long recoveryWindowMillis() {
        int staleAfterMinutes =
                appConfig == null || appConfig.getTask() == null
                        ? 60
                        : appConfig.getTask().getStaleAfterMinutes();
        long minutes = Math.max(1L, staleAfterMinutes);
        return minutes * 60L * 1000L;
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 错误参数。
     * @return 返回safe Error结果。
     */
    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = StrUtil.isBlank(message) ? error.getClass().getSimpleName() : message;
        return SecretRedactor.redact(value, 1000);
    }
}
