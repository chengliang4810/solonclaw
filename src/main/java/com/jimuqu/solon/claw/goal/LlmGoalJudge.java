package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-backed goal judge：复用 LlmGateway 的非流式 auxiliary 通道裁决目标完成度。
 *
 * <p>调用约定（对标 {@code AsyncSkillLearningService.callAuxiliaryChat}）：提交到有界线程池，用 {@code
 * Future.get(timeout)} 兜底超时，超时则取消任务。任何网络异常/超时一律 fail-open 返回 {@code continue}；仅当「模型确实返回了内容、但 JSON
 * 不可解析」时抛出 {@link GoalJudgeUnparseableException}，交由上层 {@link GoalService} 累计
 * consecutiveParseFailures 后自动暂停。
 */
public class LlmGoalJudge implements GoalJudge {
    /** 日志器。 */
    private static final Logger log = LoggerFactory.getLogger(LlmGoalJudge.class);

    /** 默认 judge 超时秒数（配置缺失时兜底）。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** LLM 网关。 */
    private final LlmGateway llmGateway;

    /** 应用配置（提供 goal 子配置与 llm 基线，用于裁决器 provider 覆盖）。 */
    private final AppConfig appConfig;

    /** goal 配置（超时/token/provider）。 */
    private final AppConfig.GoalConfig config;

    /** provider 解析服务，用于把 judgeProvider/judgeModel 解析为已解析配置。 */
    private final LlmProviderService llmProviderService;

    /** auxiliary 调用用的有界线程池（对标技能学习服务的 auxiliary 线程池）。 */
    private final ExecutorService executor =
            BoundedExecutorFactory.fixed("goal-judge-auxiliary", 1, 8);

    /**
     * 构造 judge。
     *
     * @param llmGateway LLM 网关，发起非流式 auxiliary 聊天。
     * @param appConfig 应用配置，提供 goal 子配置与 llm 基线。
     * @param llmProviderService provider 解析服务，把 judgeProvider/judgeModel 解析为已解析配置。
     */
    public LlmGoalJudge(
            LlmGateway llmGateway, AppConfig appConfig, LlmProviderService llmProviderService) {
        this.llmGateway = llmGateway;
        this.appConfig = appConfig == null ? new AppConfig() : appConfig;
        this.config =
                this.appConfig.getGoal() == null
                        ? new AppConfig.GoalConfig()
                        : this.appConfig.getGoal();
        this.llmProviderService = llmProviderService;
    }

    /**
     * 综合目标、上轮回复、子目标和契约，裁决目标是否完成、继续还是等待。
     *
     * @param request 裁决请求，含 goal/lastResponse/subgoals/contract。
     * @return 裁决结果（异常/超时 fail-open 为 continue）。
     */
    @Override
    public GoalJudgeResult judge(GoalJudgeRequest request) {
        final String systemPrompt = GoalPromptTemplates.JUDGE_SYSTEM_PROMPT;
        final String goal = StrUtil.nullToEmpty(request == null ? null : request.getGoal());
        final String lastResponse =
                StrUtil.nullToEmpty(request == null ? null : request.getLastResponse());
        final String userMessage = buildJudgeUserMessage(request, goal, lastResponse);
        Future<LlmResult> future =
                executor.submit(
                        () -> {
                            // judge 不绑定真实会话历史，用空 SessionRecord 避免污染
                            SessionRecord judgeSession = new SessionRecord();
                            judgeSession.setSessionId("goal-judge");
                            // 配置了 judgeProvider 时走已解析的廉价模型；否则按主模型兜底走 chat。
                            AppConfig.LlmConfig resolved = resolveJudgeLlmConfig();
                            if (resolved != null) {
                                return llmGateway.executeOnce(
                                        judgeSession,
                                        systemPrompt,
                                        userMessage,
                                        Collections.emptyList(),
                                        null,
                                        null,
                                        false,
                                        resolved,
                                        null);
                            }
                            return llmGateway.chat(
                                    judgeSession,
                                    systemPrompt,
                                    userMessage,
                                    Collections.emptyList());
                        });
        LlmResult result;
        try {
            int timeoutSec = resolveTimeoutSeconds();
            result = future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("goal judge timeout, fail-open to continue");
            return GoalJudgeResult.continueGoal("judge timeout, fail-open");
        } catch (Exception e) {
            log.warn("goal judge call failed, fail-open to continue: {}", e.getMessage());
            return GoalJudgeResult.continueGoal("judge unavailable, fail-open");
        }
        // 解析放在 try 之外：模型有返回但 JSON 不可解析时抛 GoalJudgeUnparseableException，
        // 交由 GoalService 累计 consecutiveParseFailures；不与 fail-open 分支混为一谈。
        String raw = extractText(result);
        return parseJudgeJson(raw);
    }

    /**
     * 按优先级（契约 > 子目标 > 裸目标）选择裁决器用户提示，对标 {@code GoalService.nextContinuationPrompt} 的优先级约定。
     *
     * <p>契约存在时，若同时携带子目标，则把子目标折叠为「Extra criterion N」追加到契约块， 让裁决器在同一提示里同时看到契约 Verification
     * 与子目标。子目标列表格式化为编号行 「- 1. <text>」，与续轮提示一致。
     *
     * @param request 裁决请求。
     * @param goal 目标文本（已空安全）。
     * @param lastResponse 上轮回复（已空安全）。
     * @return 渲染后的裁决器用户提示。
     */
    private String buildJudgeUserMessage(
            GoalJudgeRequest request, String goal, String lastResponse) {
        if (request == null) {
            return String.format(
                    GoalPromptTemplates.JUDGE_USER_PROMPT_TEMPLATE, goal, lastResponse);
        }
        GoalContract contract = request.getContract();
        boolean hasContract = contract != null && !contract.isEmpty();
        boolean hasSubgoals = request.getSubgoals() != null && !request.getSubgoals().isEmpty();
        if (hasContract) {
            String contractBlock = contract.renderBlock();
            if (hasSubgoals) {
                // 子目标折叠为「Extra criterion N」，与 nextContinuationPrompt 的契约分支一致
                StringBuilder extra = new StringBuilder();
                for (int i = 0; i < request.getSubgoals().size(); i++) {
                    extra.append("\n- Extra criterion ")
                            .append(i + 1)
                            .append(": ")
                            .append(request.getSubgoals().get(i));
                }
                contractBlock = contractBlock + extra.toString();
            }
            return String.format(
                    GoalPromptTemplates.JUDGE_USER_PROMPT_WITH_CONTRACT_TEMPLATE,
                    goal,
                    contractBlock,
                    lastResponse);
        }
        if (hasSubgoals) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < request.getSubgoals().size(); i++) {
                if (i > 0) {
                    sb.append("\n");
                }
                sb.append("- ").append(i + 1).append(". ").append(request.getSubgoals().get(i));
            }
            return String.format(
                    GoalPromptTemplates.JUDGE_USER_PROMPT_WITH_SUBGOALS_TEMPLATE,
                    goal,
                    sb.toString(),
                    lastResponse);
        }
        return String.format(GoalPromptTemplates.JUDGE_USER_PROMPT_TEMPLATE, goal, lastResponse);
    }

    /**
     * 解析配置中的 judge 超时秒数。
     *
     * @return 大于 0 的超时秒数。
     */
    private int resolveTimeoutSeconds() {
        int configured = config.getJudgeTimeoutSeconds();
        return configured > 0 ? configured : DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * 解析裁决器使用的 LLM 配置：当 {@code judgeProvider} 非空时，解析为已解析配置（廉价模型）， 否则返回 {@code null} 表示按主模型兜底走
     * {@code chat(...)}。
     *
     * <p>解析逻辑对标 {@code AgentRunSupervisor.toLlmConfig}：以 {@code appConfig.getLlm()} 为基线， 覆盖
     * provider/dialect/apiUrl/apiKey/model；再设置裁决器专属的非流式与 maxTokens。 provider 解析失败时 fail-open 返回
     * {@code null}（仅记录警告，不中断 goal 循环）。
     *
     * @return 已解析的裁决器 LLM 配置；judgeProvider 留空或解析失败时返回 null。
     */
    private AppConfig.LlmConfig resolveJudgeLlmConfig() {
        String providerKey = StrUtil.nullToEmpty(config.getJudgeProvider()).trim();
        if (StrUtil.isBlank(providerKey) || llmProviderService == null) {
            return null;
        }
        try {
            LlmProviderService.ResolvedProvider resolved =
                    llmProviderService.resolveProvider(providerKey, config.getJudgeModel());
            AppConfig.LlmConfig llmConfig = copyLlmConfig(appConfig.getLlm());
            llmConfig.setProvider(StrUtil.nullToEmpty(resolved.getProviderKey()).trim());
            llmConfig.setDialect(StrUtil.nullToEmpty(resolved.getDialect()).trim());
            llmConfig.setApiUrl(StrUtil.nullToEmpty(resolved.getApiUrl()).trim());
            llmConfig.setApiKey(resolved.getApiKey());
            llmConfig.setModel(StrUtil.nullToEmpty(resolved.getModel()).trim());
            // 裁决器为非流式调用；maxTokens 来自 goal 配置
            llmConfig.setStream(false);
            llmConfig.setMaxTokens(config.getJudgeMaxTokens());
            return llmConfig;
        } catch (IllegalStateException e) {
            // provider 键未配置：fail-open 继续走 chat 兜底，避免坏的裁决器配置中断 goal 循环
            log.warn(
                    "goal judge provider '{}' not resolved, fail-open to default chat: {}",
                    providerKey,
                    e.getMessage());
            return null;
        }
    }

    /**
     * 复制大模型基线配置，避免污染全局 appConfig.getLlm()。
     *
     * @param source 来源大模型配置。
     * @return 返回复制后的大模型配置。
     */
    private AppConfig.LlmConfig copyLlmConfig(AppConfig.LlmConfig source) {
        AppConfig.LlmConfig copy = new AppConfig.LlmConfig();
        if (source == null) {
            return copy;
        }
        copy.setProvider(source.getProvider());
        copy.setDialect(source.getDialect());
        copy.setApiUrl(source.getApiUrl());
        copy.setApiKey(source.getApiKey());
        copy.setModel(source.getModel());
        copy.setStream(source.isStream());
        copy.setReasoningEffort(source.getReasoningEffort());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setContextWindowTokens(source.getContextWindowTokens());
        return copy;
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
        if (StrUtil.isNotBlank(result.getRawResponse())) {
            return result.getRawResponse();
        }
        if (result.getAssistantMessage() != null) {
            // 优先 resultContent（reasoning/output_text），回退 content
            if (StrUtil.isNotBlank(result.getAssistantMessage().getResultContent())) {
                return result.getAssistantMessage().getResultContent();
            }
            return StrUtil.nullToEmpty(result.getAssistantMessage().getContent());
        }
        return "";
    }

    /**
     * 解析 judge 的 JSON 裁决，剥离 markdown ```json fence。
     *
     * <p>verdict 取值 done/continue/wait；未知 verdict 一律视为 continue。空响应或非法 JSON 抛 {@link
     * GoalJudgeUnparseableException}。
     *
     * @param raw 模型原始响应。
     * @return 解析后的裁决结果。
     */
    @SuppressWarnings("unchecked")
    private GoalJudgeResult parseJudgeJson(String raw) {
        String text = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(text)) {
            throw new GoalJudgeUnparseableException("empty judge response");
        }
        // 剥离 ```json ... ``` fence
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        Map<String, Object> data;
        try {
            data = ONode.deserialize(text, LinkedHashMap.class);
        } catch (Exception e) {
            throw new GoalJudgeUnparseableException(
                    "judge response is not valid JSON: " + abbreviate(text));
        }
        String verdict = String.valueOf(data.getOrDefault("verdict", "")).toLowerCase();
        String reason = data.get("reason") == null ? "" : String.valueOf(data.get("reason"));
        if (GoalVerdict.DONE.equals(verdict)) {
            return GoalJudgeResult.done(reason);
        }
        if (GoalVerdict.WAIT.equals(verdict)) {
            Object pid = data.get("wait_on_pid");
            Object sec = data.get("wait_for_seconds");
            if (pid != null) {
                try {
                    return GoalJudgeResult.waitPid(Integer.parseInt(String.valueOf(pid)), reason);
                } catch (Exception ignored) {
                    // pid 非法，回退尝试秒数屏障
                }
            }
            if (sec != null) {
                try {
                    return GoalJudgeResult.waitSeconds(Long.parseLong(String.valueOf(sec)), reason);
                } catch (Exception ignored) {
                    // 秒数非法，回退 continue
                }
            }
            return GoalJudgeResult.continueGoal("wait verdict without valid barrier, fail-open");
        }
        // continue 或未知 verdict 一律视为 continue
        return GoalJudgeResult.continueGoal(reason);
    }

    /**
     * 截断文本用于错误日志。
     *
     * @param text 原始文本。
     * @return 截断后的文本。
     */
    private String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
