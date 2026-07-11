package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.proactive.ProactiveMessageComposer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 主动协作文案生成服务测试。 */
public class ProactiveMessageComposerTest {
    @Test
    void shouldBuildFallbackMessageThatAsksBeforeAction() throws Exception {
        ProactiveTickContext context = context(false);
        ProactiveDecision decision =
                decision(candidate("project_update_opportunity", "仓库更新", "仓库有新提交"));

        String message = new ProactiveMessageComposer().compose(context, decision);

        assertThat(message).startsWith("主动协作：");
        assertThat(message).contains("仓库更新").contains("仓库有新提交");
        assertThat(message).contains("要不要");
        assertThat(message).doesNotContain("我已经");
        assertThat(message).doesNotContain("我会执行");
    }

    @Test
    void shouldPolishWithLlmButKeepPermissionAndSanitizeUnsafeText() throws Exception {
        ProactiveTickContext context = context(true);
        ProactiveDecision decision =
                decision(
                        candidate(
                                "knowledge_followup",
                                "项目跟进",
                                "<memory-context>内部记忆 token=ghp_shouldhide12345</memory-context>需要看看新进展"));
        FixedLlmGateway llmGateway =
                new FixedLlmGateway("我已经帮你检查了 token=ghp_modelsecret12345，要不要继续让我执行？");

        String message =
                new ProactiveMessageComposer(
                                new ProactiveMessageComposer.GatewayLlmPolishClient(llmGateway))
                        .compose(context, decision);

        assertThat(llmGateway.calls).isEqualTo(1);
        assertThat(llmGateway.lastSystemPrompt).contains("不能声称已经执行");
        assertThat(message).startsWith("主动协作：");
        assertThat(message).contains("要不要");
        assertThat(message).doesNotContain("我已经");
        assertThat(message).doesNotContain("ghp_modelsecret12345");
        assertThat(message).doesNotContain("memory-context");
    }

    @Test
    void shouldScrubSecretsAndMemoryContextFromFallback() throws Exception {
        ProactiveTickContext context = context(false);
        ProactiveCandidateRecord candidate =
                candidate(
                        "work_continuation",
                        "<memory-context>隐藏上下文</memory-context>继续验证",
                        "错误里有 api_key=sk-test-proactive12345");
        candidate.setActionOffer("帮你直接运行 git push");

        String message = new ProactiveMessageComposer().compose(context, decision(candidate));

        assertThat(message).contains("继续验证");
        assertThat(message).contains("要不要");
        assertThat(message).doesNotContain("memory-context");
        assertThat(message).doesNotContain("sk-test-proactive12345");
        assertThat(message).doesNotContain("直接运行");
    }

    /** 构造测试上下文。 */
    private static ProactiveTickContext context(boolean llmPolishEnabled) {
        AppConfig config = new AppConfig();
        config.getProactive().setDeliveryPreviewPrefix("主动协作");
        config.getProactive().setLlmPolishEnabled(llmPolishEnabled);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setTickId("tick-compose");
        context.setNowMillis(1_800_000_000_000L);
        context.setConfig(config);
        context.setHomeChannels(Collections.emptyList());
        return context;
    }

    /** 构造发送决策。 */
    private static ProactiveDecision decision(ProactiveCandidateRecord candidate) {
        ProactiveDecision decision = new ProactiveDecision();
        decision.setDecisionId("decision-compose");
        decision.setTickId("tick-compose");
        decision.setCandidateId(candidate.getCandidateId());
        decision.setSourceKey(candidate.getSourceKey());
        decision.setDecision("SEND");
        decision.setReason("deterministic_allow");
        decision.setMessageIntent("询问用户是否需要继续");
        decision.setSensitivity("normal");
        decision.setCandidate(candidate);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("test", Boolean.TRUE);
        decision.setMetadata(metadata);
        decision.setCreatedAt(1_800_000_000_000L);
        return decision;
    }

    /** 构造候选记录。 */
    private static ProactiveCandidateRecord candidate(String topic, String title, String summary) {
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId("candidate-compose");
        candidate.setSourceType("session");
        candidate.setSourceRef("session-a");
        candidate.setSourceKey("WEIXIN:room:user");
        candidate.setSubjectType("session");
        candidate.setSubjectRef("session-a");
        candidate.setTopic(topic);
        candidate.setTitle(title);
        candidate.setSummary(summary);
        candidate.setReason(summary);
        candidate.setActionOffer("帮你看一下差异并给出处理建议");
        candidate.setConfidence(0.9D);
        candidate.setPriority(90);
        candidate.setDedupKey("dedup-compose");
        candidate.setStateHash("state-compose");
        candidate.setCreatedAt(1_800_000_000_000L);
        candidate.setExpiresAt(1_800_010_000_000L);
        candidate.setStatus("APPROVED");
        candidate.setUpdatedAt(candidate.getCreatedAt());
        candidate.setEvidence(new LinkedHashMap<String, Object>());
        return candidate;
    }

    /** 返回固定内容的大模型网关。 */
    private static final class FixedLlmGateway implements LlmGateway {
        /** 调用次数。 */
        private int calls;

        /** 最近一次系统提示词。 */
        private String lastSystemPrompt;

        /** 固定返回文本。 */
        private final String text;

        /** 创建固定返回网关。 */
        private FixedLlmGateway(String text) {
            this.text = text;
        }

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            calls++;
            lastSystemPrompt = systemPrompt;
            LlmResult result = new LlmResult();
            result.setAssistantMessage(ChatMessage.ofAssistant(text));
            result.setNdjson("");
            result.setRawResponse(text);
            return result;
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            return chat(session, systemPrompt, "", toolObjects);
        }
    }
}
