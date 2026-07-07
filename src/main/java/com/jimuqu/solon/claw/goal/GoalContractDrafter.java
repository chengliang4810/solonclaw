package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用辅助模型把裸目标起草为 5 字段完成契约（/goal draft），失败返回空契约。
 *
 * <p>调用约定对标 {@link LlmGoalJudge}：提交到有界辅助线程池，用 {@code Future.get(timeout)}
 * 兜底超时，任何异常（网络错误、超时、解析失败）一律返回空契约（不抛异常），让上层以无契约方式
 * 设置目标。空契约经 {@link GoalContract#isEmpty()} 判定为 true，{@code /goal draft} 回复
 * 仍能正常落地，仅缺少结构化完成准则。
 */
public class GoalContractDrafter {
    /** 日志器。 */
    private static final Logger log = LoggerFactory.getLogger(GoalContractDrafter.class);

    /** 默认起草超时秒数（配置缺失时兜底）。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** LLM 网关。 */
    private final LlmGateway llmGateway;

    /** goal 配置（复用 judge 超时配置兜底）。 */
    private final AppConfig.GoalConfig config;

    /** auxiliary 调用用的有界线程池（对标 judge 的辅助线程池）。 */
    private final ExecutorService executor =
            BoundedExecutorFactory.fixed("goal-draft-auxiliary", 1, 4);

    /**
     * 构造 drafter。
     *
     * @param llmGateway LLM 网关，发起非流式 auxiliary 聊天。
     * @param config goal 配置，提供超时秒数；为 null 时使用默认配置。
     */
    public GoalContractDrafter(LlmGateway llmGateway, AppConfig.GoalConfig config) {
        this.llmGateway = llmGateway;
        this.config = config == null ? new AppConfig.GoalConfig() : config;
    }

    /**
     * 起草完成契约：把裸目标交给辅助模型转为 5 字段 JSON 契约，解析失败返回空契约。
     *
     * @param objective 裸目标文本。
     * @return 契约；失败返回空契约（{@link GoalContract#isEmpty()} 为 true）。
     */
    public GoalContract draft(String objective) {
        final String userMessage =
                String.format(
                        GoalPromptTemplates.DRAFT_CONTRACT_USER_TEMPLATE,
                        StrUtil.nullToEmpty(objective));
        Future<LlmResult> future =
                executor.submit(
                        () -> {
                            // draft 不绑定真实会话历史，用空 SessionRecord 避免污染
                            SessionRecord draftSession = new SessionRecord();
                            draftSession.setSessionId("goal-draft");
                            return llmGateway.chat(
                                    draftSession,
                                    GoalPromptTemplates.DRAFT_CONTRACT_SYSTEM_PROMPT,
                                    userMessage,
                                    Collections.emptyList());
                        });
        try {
            int timeout = resolveTimeoutSeconds();
            LlmResult result = future.get(timeout, TimeUnit.SECONDS);
            String raw = extractText(result);
            return parseContractJson(raw);
        } catch (Exception e) {
            log.warn("goal contract draft failed, returning empty contract: {}", e.getMessage());
            return new GoalContract();
        }
    }

    /**
     * 解析配置中的起草超时秒数。
     *
     * @return 大于 0 的超时秒数。
     */
    private int resolveTimeoutSeconds() {
        int configured = config.getJudgeTimeoutSeconds();
        return configured > 0 ? configured : DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * 从 LlmResult 提取文本响应：优先 rawResponse，空则取 assistant 消息内容。
     *
     * @param result LLM 调用结果。
     * @return 文本内容，空结果返回空串。
     */
    private String extractText(LlmResult result) {
        if (result == null) {
            return "";
        }
        String raw = StrUtil.nullToEmpty(result.getRawResponse());
        if (StrUtil.isNotBlank(raw)) {
            return raw;
        }
        if (result.getAssistantMessage() != null) {
            if (StrUtil.isNotBlank(result.getAssistantMessage().getResultContent())) {
                return result.getAssistantMessage().getResultContent();
            }
            return StrUtil.nullToEmpty(result.getAssistantMessage().getContent());
        }
        return "";
    }

    /**
     * 解析 5 字段 JSON 契约，失败返回空契约。
     *
     * @param raw 模型原始响应文本。
     * @return 契约；空白或非法 JSON 返回空契约。
     */
    @SuppressWarnings("unchecked")
    private GoalContract parseContractJson(String raw) {
        String text = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(text)) {
            return new GoalContract();
        }
        try {
            Map<String, Object> data = ONode.deserialize(text, LinkedHashMap.class);
            return GoalContract.fromMap(data);
        } catch (Exception e) {
            log.warn("goal contract draft JSON parse failed: {}", e.getMessage());
            return new GoalContract();
        }
    }
}
