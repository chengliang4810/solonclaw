package com.jimuqu.solon.claw.engine;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ContextBudgetService;
import com.jimuqu.solon.claw.tool.runtime.SanitizedFunctionTool;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;

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
        return decide(
                session, systemPrompt, userMessage, resolved, Collections.<Object>emptyList());
    }

    /**
     * 计算包含本轮实际工具 schema 的上下文预算。
     *
     * @param session 会话参数。
     * @param systemPrompt 系统提示词参数。
     * @param userMessage 用户消息参数。
     * @param resolved 本轮模型配置。
     * @param tools 本轮实际发送给模型的工具对象。
     * @return 返回上下文预算决策。
     */
    @Override
    public ContextBudgetDecision decide(
            SessionRecord session,
            String systemPrompt,
            String userMessage,
            AppConfig.LlmConfig resolved,
            List<Object> tools) {
        int contextWindow =
                resolved == null || resolved.getContextWindowTokens() <= 0
                        ? Math.max(1024, appConfig.getLlm().getContextWindowTokens())
                        : Math.max(1024, resolved.getContextWindowTokens());
        int threshold = effectiveThresholdTokens(contextWindow, resolved);
        int estimated =
                estimate(systemPrompt)
                        + estimate(userMessage)
                        + estimate(session == null ? null : session.getNdjson())
                        + estimateToolSchemas(tools, userMessage);

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
     * 计算为输入保留输出空间后的压缩阈值；输出上限占满窗口时将输入预算钳制为一个 token。
     *
     * @param contextWindow 当前候选模型上下文窗口。
     * @param resolved 本轮模型配置。
     * @return 返回可用于输入预算判断的阈值 token 数。
     */
    private int effectiveThresholdTokens(int contextWindow, AppConfig.LlmConfig resolved) {
        int maxTokens = resolved == null ? 0 : resolved.getMaxTokens();
        int effectiveWindow =
                maxTokens > 0 ? Math.max(1, contextWindow - maxTokens) : contextWindow;
        return Math.max(
                1, (int) (effectiveWindow * appConfig.getCompression().getThresholdPercent()));
    }

    /**
     * 按 Solon AI 实际生成的 FunctionTool 元数据估算工具 schema token。
     *
     * @param toolObjects 本轮启用工具对象。
     * @param userMessage 本轮用户消息，用于解析动态 Talent 工具。
     * @return 返回工具 schema 的估算 token 数。
     */
    private int estimateToolSchemas(List<Object> toolObjects, String userMessage) {
        if (toolObjects == null || toolObjects.isEmpty()) {
            return 0;
        }
        long total = 0L;
        Prompt prompt = Prompt.of(StrUtil.nullToEmpty(userMessage));
        for (Object toolObject : toolObjects) {
            try {
                for (FunctionTool tool : functionTools(toolObject, prompt)) {
                    FunctionTool sanitized = SanitizedFunctionTool.wrap(tool);
                    if (sanitized == null) {
                        continue;
                    }
                    total += estimate(sanitized.name());
                    total += estimate(sanitized.description());
                    total += estimate(sanitized.inputSchema());
                    if (total >= Integer.MAX_VALUE) {
                        return Integer.MAX_VALUE;
                    }
                }
            } catch (RuntimeException ignored) {
                // 单个动态工具 schema 解析失败时只跳过该工具，不能阻断正常模型请求。
            }
        }
        return (int) total;
    }

    /**
     * 将运行时工具对象展开为 Solon AI FunctionTool 集合。
     *
     * @param toolObject 工具对象。
     * @param prompt 本轮提示词。
     * @return 返回展开后的函数工具集合。
     */
    private Collection<FunctionTool> functionTools(Object toolObject, Prompt prompt) {
        if (toolObject == null) {
            return Collections.emptyList();
        }
        if (toolObject instanceof FunctionTool) {
            return Collections.singletonList((FunctionTool) toolObject);
        }
        if (toolObject instanceof ToolProvider) {
            Collection<FunctionTool> tools = ((ToolProvider) toolObject).getTools();
            return tools == null ? Collections.<FunctionTool>emptyList() : tools;
        }
        if (toolObject instanceof Talent) {
            Collection<FunctionTool> tools = ((Talent) toolObject).getTools(prompt);
            return tools == null ? Collections.<FunctionTool>emptyList() : tools;
        }
        Collection<FunctionTool> tools = new MethodToolProvider(toolObject).getTools();
        return tools == null ? Collections.<FunctionTool>emptyList() : tools;
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
