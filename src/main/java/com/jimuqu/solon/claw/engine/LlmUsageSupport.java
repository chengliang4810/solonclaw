package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;

/** LLM 用量字段写入与合并辅助逻辑。 */
final class LlmUsageSupport {
    /** 工具类不允许创建实例。 */
    private LlmUsageSupport() {}

    /**
     * 将本轮 LLM 用量写入会话记录，并累加历史用量。
     *
     * @param session 会话记录。
     * @param result LLM 调用结果。
     */
    static void applyUsage(SessionRecord session, LlmResult result) {
        if (session == null || result == null) {
            return;
        }
        session.setLastInputTokens(result.getInputTokens());
        session.setLastOutputTokens(result.getOutputTokens());
        session.setLastReasoningTokens(result.getReasoningTokens());
        session.setLastCacheReadTokens(result.getCacheReadTokens());
        session.setLastCacheWriteTokens(result.getCacheWriteTokens());
        session.setLastTotalTokens(result.getTotalTokens());
        session.setCumulativeInputTokens(
                session.getCumulativeInputTokens() + Math.max(0L, result.getInputTokens()));
        session.setCumulativeOutputTokens(
                session.getCumulativeOutputTokens() + Math.max(0L, result.getOutputTokens()));
        session.setCumulativeReasoningTokens(
                session.getCumulativeReasoningTokens() + Math.max(0L, result.getReasoningTokens()));
        session.setCumulativeCacheReadTokens(
                session.getCumulativeCacheReadTokens() + Math.max(0L, result.getCacheReadTokens()));
        session.setCumulativeCacheWriteTokens(
                session.getCumulativeCacheWriteTokens()
                        + Math.max(0L, result.getCacheWriteTokens()));
        session.setCumulativeTotalTokens(
                session.getCumulativeTotalTokens() + Math.max(0L, result.getTotalTokens()));
        if (result.getTotalTokens() > 0
                || result.getInputTokens() > 0
                || result.getOutputTokens() > 0
                || result.getCacheReadTokens() > 0
                || result.getCacheWriteTokens() > 0) {
            session.setLastUsageAt(System.currentTimeMillis());
        }
        if (StrUtil.isNotBlank(result.getProvider())) {
            session.setLastResolvedProvider(result.getProvider());
        }
        if (StrUtil.isNotBlank(result.getModel())) {
            session.setLastResolvedModel(result.getModel());
        }
    }

    /**
     * 将前置调用已消耗的用量合并进最终结果。
     *
     * @param base 前置调用结果。
     * @param extra 最终结果，方法会原地累加该对象。
     */
    static void mergeUsage(LlmResult base, LlmResult extra) {
        if (base == null || extra == null) {
            return;
        }
        extra.setInputTokens(
                Math.max(0L, extra.getInputTokens()) + Math.max(0L, base.getInputTokens()));
        extra.setOutputTokens(
                Math.max(0L, extra.getOutputTokens()) + Math.max(0L, base.getOutputTokens()));
        extra.setReasoningTokens(
                Math.max(0L, extra.getReasoningTokens()) + Math.max(0L, base.getReasoningTokens()));
        extra.setCacheReadTokens(
                Math.max(0L, extra.getCacheReadTokens()) + Math.max(0L, base.getCacheReadTokens()));
        extra.setCacheWriteTokens(
                Math.max(0L, extra.getCacheWriteTokens())
                        + Math.max(0L, base.getCacheWriteTokens()));
        extra.setRequestCount(
                Math.max(0L, extra.getRequestCount()) + Math.max(0L, base.getRequestCount()));
        extra.setTotalTokens(
                Math.max(0L, extra.getTotalTokens()) + Math.max(0L, base.getTotalTokens()));
    }
}
