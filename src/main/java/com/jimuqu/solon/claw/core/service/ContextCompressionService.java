package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.SessionRecord;

/** 上下文压缩服务接口。 */
public interface ContextCompressionService {
    /** 在模型调用前按需压缩会话上下文。 */
    SessionRecord compressIfNeeded(SessionRecord session, String systemPrompt, String userMessage)
            throws Exception;

    /** 在模型调用前按需压缩，并返回是否失败/跳过等细节。 */
    default CompressionOutcome compressIfNeededWithOutcome(
            SessionRecord session, String systemPrompt, String userMessage) throws Exception {
        SessionRecord compressed = compressIfNeeded(session, systemPrompt, userMessage);
        return CompressionOutcome.success(compressed, compressed != session);
    }

    /** 强制压缩当前会话。 */
    SessionRecord compressNow(SessionRecord session, String systemPrompt) throws Exception;

    /** 强制压缩当前会话，并返回压缩结果。 */
    default CompressionOutcome compressNowWithOutcome(SessionRecord session, String systemPrompt)
            throws Exception {
        SessionRecord compressed = compressNow(session, systemPrompt);
        return CompressionOutcome.success(compressed, compressed != session);
    }

    /** 强制压缩当前会话，并允许指定关注主题。 */
    SessionRecord compressNow(SessionRecord session, String systemPrompt, String focus)
            throws Exception;

    /** 强制压缩当前会话，并允许指定关注主题，返回压缩结果。 */
    default CompressionOutcome compressNowWithOutcome(
            SessionRecord session, String systemPrompt, String focus) throws Exception {
        SessionRecord compressed = compressNow(session, systemPrompt, focus);
        return CompressionOutcome.success(compressed, compressed != session);
    }

    /**
     * 按当前候选模型的上下文窗口强制压缩，避免并发运行通过共享配置传递模型预算。
     *
     * @param session 当前会话。
     * @param systemPrompt 系统提示词。
     * @param focus 压缩关注主题。
     * @param contextWindowTokens 当前候选模型的上下文窗口 token 数。
     * @return 返回压缩结果。
     */
    default CompressionOutcome compressNowWithOutcome(
            SessionRecord session, String systemPrompt, String focus, int contextWindowTokens)
            throws Exception {
        return compressNowWithOutcome(session, systemPrompt, focus);
    }
}
