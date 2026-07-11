package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import java.util.List;

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

    /**
     * 按本轮实际启用工具计算上下文预算；自定义实现未覆盖时保持原有预算行为。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param resolved 本轮模型配置。
     * @param tools 本轮实际发送给模型的工具对象。
     * @return 返回上下文预算决策。
     */
    default ContextBudgetDecision decide(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            AppConfig.LlmConfig resolved,
            List<Object> tools) {
        return decide(session, systemPrompt, userMessage, resolved);
    }
}
