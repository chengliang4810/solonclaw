package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ContextBudgetService;

/** 默认上下文预算估算服务。 */
public class DefaultContextBudgetService implements ContextBudgetService {
    /** 注入应用配置，用于默认上下文预算。 */
    private final AppConfig appConfig;

    /**
     * 创建默认上下文Budget服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public DefaultContextBudgetService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 执行decide相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param resolved resolved 参数。
     * @return 返回decide结果。
     */
    @Override
    public ContextBudgetDecision decide(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            AppConfig.LlmConfig resolved) {
        int contextWindow =
                resolved == null || resolved.getContextWindowTokens() <= 0
                        ? Math.max(1024, appConfig.getLlm().getContextWindowTokens())
                        : Math.max(1024, resolved.getContextWindowTokens());
        int threshold = (int) (contextWindow * appConfig.getCompression().getThresholdPercent());
        int estimated =
                estimate(systemPrompt)
                        + estimate(userMessage)
                        + estimate(session == null ? null : session.getNdjson());

        ContextBudgetDecision decision = new ContextBudgetDecision();
        decision.setContextWindowTokens(contextWindow);
        decision.setThresholdTokens(threshold);
        decision.setEstimatedTokens(estimated);
        decision.setShouldCompress(
                appConfig.getCompression().isEnabled() && estimated >= threshold);
        decision.setReason(decision.isShouldCompress() ? "预计上下文已达到压缩阈值" : "预计上下文未达到压缩阈值");
        return decision;
    }

    /**
     * 执行estimate相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回estimate结果。
     */
    private int estimate(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        return ContextTokenEstimator.estimateForBudget(normalized);
    }
}
