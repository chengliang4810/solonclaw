package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;

/** 模型调用前的上下文预算服务。 */
public interface ContextBudgetService {
    /**
     * 执行decide相关逻辑。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param resolved resolved 参数。
     * @return 返回decide结果。
     */
    ContextBudgetDecision decide(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            AppConfig.LlmConfig resolved);
}
