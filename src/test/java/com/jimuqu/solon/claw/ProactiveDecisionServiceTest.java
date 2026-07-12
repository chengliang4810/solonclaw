package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.proactive.ProactiveDecisionService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 主动协作决策服务测试。 */
public class ProactiveDecisionServiceTest {
    @Test
    void shouldSkipWhenProactiveDisabled() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveTickContext context = contextAt(10, 0);
        context.getConfig().getProactive().setEnabled(false);

        List<ProactiveDecision> decisions =
                new ProactiveDecisionService(repository)
                        .decide(
                                context,
                                Arrays.asList(candidate("candidate-a", 90, 0.9D, "source-a")),
                                Arrays.asList(gate(false, "source-a")));

        assertThat(decisions).extracting(ProactiveDecision::getDecision).containsExactly("SKIP");
        assertThat(decisions.get(0).getReason()).isEqualTo("proactive_disabled");
        assertThat(repository.savedDecisions)
                .extracting(ProactiveDecisionRecord::getReason)
                .containsExactly("proactive_disabled");
        assertThat(repository.savedDecisions.get(0).getMetadata())
                .containsEntry("candidateStatus", "PENDING");
    }

    @Test
    void shouldApplyHomeQuietDailyCooldownExpiredConfidenceAndActiveRunGates() throws Exception {
        assertGateReason(
                noHomeContext(),
                candidate("candidate-home", 90, 0.9D, "source-a"),
                "no_home_channel",
                new ArrayList<ProactiveObservationRecord>());
        assertGateReason(
                quietContext(),
                candidate("candidate-quiet", 90, 0.9D, "source-a"),
                "quiet_hours",
                Arrays.asList(gate(true, "source-a")));

        InMemoryProactiveRepository dailyRepository = new InMemoryProactiveRepository();
        ProactiveTickContext dailyContext = contextAt(10, 0);
        dailyContext.getConfig().getProactive().setDailyMaxContacts(1);
        dailyRepository.sentSince = 1;
        ProactiveDecision dailyDecision =
                new ProactiveDecisionService(dailyRepository)
                        .decide(
                                dailyContext,
                                Arrays.asList(candidate("candidate-daily", 90, 0.9D, "source-a")),
                                Arrays.asList(gate(false, "source-a")))
                        .get(0);
        assertThat(dailyDecision.getReason()).isEqualTo("daily_limit_reached");
        assertThat(dailyRepository.savedDecisions.get(0).getMetadata())
                .containsEntry("candidateStatus", "PENDING");

        InMemoryProactiveRepository cooldownRepository = new InMemoryProactiveRepository();
        ProactiveTickContext cooldownContext = contextAt(10, 0);
        cooldownContext.getConfig().getProactive().setCooldownMinutes(120);
        cooldownRepository.lastSentAt = cooldownContext.getNowMillis() - 30L * 60L * 1000L;
        ProactiveDecision cooldownDecision =
                new ProactiveDecisionService(cooldownRepository)
                        .decide(
                                cooldownContext,
                                Arrays.asList(
                                        candidate("candidate-cooldown", 90, 0.9D, "source-a")),
                                Arrays.asList(gate(false, "source-a")))
                        .get(0);
        assertThat(cooldownDecision.getReason()).isEqualTo("cooldown_active");
        assertThat(cooldownRepository.savedDecisions.get(0).getMetadata())
                .containsEntry("candidateStatus", "PENDING");

        ProactiveCandidateRecord expired = candidate("candidate-expired", 90, 0.9D, "source-a");
        expired.setExpiresAt(contextAt(10, 0).getNowMillis() - 1L);
        assertGateReason(contextAt(10, 0), expired, "candidate_expired");

        ProactiveCandidateRecord lowConfidence = candidate("candidate-low", 90, 0.2D, "source-a");
        assertGateReason(contextAt(10, 0), lowConfidence, "confidence_below_threshold");

        InMemoryProactiveRepository activeRunRepository = new InMemoryProactiveRepository();
        ProactiveDecision activeRunDecision =
                new ProactiveDecisionService(activeRunRepository)
                        .decide(
                                contextAt(10, 0),
                                Arrays.asList(candidate("candidate-active", 80, 0.9D, "source-a")),
                                Arrays.asList(gate(false, "source-a")))
                        .get(0);
        assertThat(activeRunDecision.getReason()).isEqualTo("active_run_for_source");
        assertThat(activeRunRepository.savedDecisions.get(0).getMetadata())
                .containsEntry("candidateStatus", "PENDING");
    }

    @Test
    void shouldAllowRunRecoveryForActiveSource() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveCandidateRecord candidate = candidate("candidate-run", 90, 0.9D, "source-a");
        candidate.setSourceType("run");
        candidate.setReason("运行处于可恢复状态，适合询问用户是否继续");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "run_recoverable");
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("payload", payload);
        candidate.setEvidence(evidence);

        List<ProactiveDecision> decisions =
                new ProactiveDecisionService(repository)
                        .decide(
                                contextAt(10, 0),
                                Arrays.asList(candidate),
                                Arrays.asList(gate(false, "source-a")));

        assertThat(decisions).extracting(ProactiveDecision::getDecision).containsExactly("SEND");
        assertThat(repository.savedDecisions)
                .extracting(ProactiveDecisionRecord::getDecision)
                .containsExactly("SEND");
    }

    @Test
    void shouldRankCandidatesAndRespectMaxContactsPerTick() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveTickContext context = contextAt(10, 0);
        context.getConfig().getProactive().setMaxContactsPerTick(1);
        ProactiveCandidateRecord low = candidate("candidate-low", 50, 0.95D, "source-low");
        ProactiveCandidateRecord best = candidate("candidate-best", 90, 0.70D, "source-best");
        ProactiveCandidateRecord second = candidate("candidate-second", 90, 0.66D, "source-second");

        List<ProactiveDecision> decisions =
                new ProactiveDecisionService(repository)
                        .decide(
                                context,
                                Arrays.asList(low, second, best),
                                Arrays.asList(gate(false)));

        assertThat(decisions)
                .extracting(ProactiveDecision::getCandidateId)
                .containsExactly("candidate-best", "candidate-second", "candidate-low");
        assertThat(decisions)
                .extracting(ProactiveDecision::getDecision)
                .containsExactly("SEND", "SKIP", "SKIP");
        assertThat(decisions)
                .extracting(ProactiveDecision::getReason)
                .containsExactly(
                        "deterministic_allow", "contact_limit_reached", "contact_limit_reached");
    }

    /** 单 tick 上限只暂缓其余候选，下一 tick 仍可继续发送。 */
    @Test
    void shouldRetryContactLimitedCandidateOnNextTick() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveDecisionService service = new ProactiveDecisionService(repository);
        ProactiveTickContext firstTick = contextAt(10, 0);
        firstTick.getConfig().getProactive().setMaxContactsPerTick(1);
        ProactiveCandidateRecord first = candidate("candidate-first", 90, 0.9D, "source-first");
        ProactiveCandidateRecord deferred =
                candidate("candidate-deferred", 80, 0.9D, "source-deferred");

        service.decide(firstTick, Arrays.asList(first, deferred), Arrays.asList(gate(false)));

        assertThat(repository.savedDecisions)
                .extracting(decision -> decision.getMetadata().get("candidateStatus"))
                .containsExactly("APPROVED", "PENDING");

        ProactiveTickContext secondTick = contextAt(10, 30);
        secondTick.setTickId("tick-decision-next");
        List<ProactiveDecision> retried =
                service.decide(secondTick, Arrays.asList(deferred), Arrays.asList(gate(false)));

        assertThat(retried).extracting(ProactiveDecision::getDecision).containsExactly("SEND");
        assertThat(repository.savedDecisions.get(2).getMetadata())
                .containsEntry("candidateStatus", "APPROVED");
    }

    @Test
    void shouldUseLlmDecisionWhenEnabledButNeverOverrideHardGates() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveTickContext context = contextAt(10, 0);
        context.getConfig().getProactive().setLlmDecisionEnabled(true);
        FakeLlmDecisionClient llmClient =
                new FakeLlmDecisionClient(
                        new ProactiveDecisionService.LlmDecisionResult(
                                false, "模型认为暂不打扰", "稍后再问", "normal"));

        List<ProactiveDecision> llmDecisions =
                new ProactiveDecisionService(repository, llmClient)
                        .decide(
                                context,
                                Arrays.asList(candidate("candidate-llm", 90, 0.9D, "source-a")),
                                Arrays.asList(gate(false)));

        assertThat(llmClient.calls).isEqualTo(1);
        assertThat(llmDecisions).extracting(ProactiveDecision::getDecision).containsExactly("SKIP");
        assertThat(llmDecisions.get(0).getReason()).isEqualTo("llm_skip: 模型认为暂不打扰");
        assertThat(llmDecisions.get(0).getMessageIntent()).isEqualTo("稍后再问");

        FakeLlmDecisionClient overridingClient =
                new FakeLlmDecisionClient(
                        new ProactiveDecisionService.LlmDecisionResult(
                                true, "模型想发送", "立即发送", "low"));
        ProactiveTickContext noHomeContext = noHomeContext();
        List<ProactiveDecision> hardGateDecisions =
                new ProactiveDecisionService(new InMemoryProactiveRepository(), overridingClient)
                        .decide(
                                noHomeContext,
                                Arrays.asList(candidate("candidate-hard", 90, 0.9D, "source-a")),
                                new ArrayList<ProactiveObservationRecord>());

        assertThat(overridingClient.calls).isEqualTo(0);
        assertThat(hardGateDecisions.get(0).getReason()).isEqualTo("no_home_channel");
    }

    @Test
    void shouldParseFirstCompleteJsonBlockFromLlmDecisionText() throws Exception {
        ProactiveDecisionService.GatewayLlmDecisionClient client =
                new ProactiveDecisionService.GatewayLlmDecisionClient(
                        new FixedTextLlmGateway(
                                "先不要解析这个示例：{\"send\":false,\"reason\":\"示例\",\"message_intent\":\"\",\"sensitivity\":\"high\"}\n"
                                        + "```json\n"
                                        + "{\"send\":true,\"reason\":\"值得跟进\",\"message_intent\":\"询问是否继续验证\",\"sensitivity\":\"low\"}\n"
                                        + "```\n补充说明 {\"ignored\":true}"));

        ProactiveDecisionService.LlmDecisionResult result =
                client.decide(contextAt(10, 0), candidate("candidate-json", 90, 0.9D, "source-a"));

        assertThat(result.isSend()).isFalse();
        assertThat(result.getReason()).isEqualTo("示例");
        assertThat(result.getSensitivity()).isEqualTo("high");
    }

    @Test
    void shouldKeepLlmDecisionSystemPromptWithinSafeBoundary() throws Exception {
        FixedTextLlmGateway gateway =
                new FixedTextLlmGateway(
                        "{\"send\":true,\"reason\":\"值得跟进\",\"message_intent\":\"询问是否继续验证\",\"sensitivity\":\"low\"}");
        ProactiveDecisionService.GatewayLlmDecisionClient client =
                new ProactiveDecisionService.GatewayLlmDecisionClient(gateway);

        client.decide(contextAt(10, 0), candidate("candidate-prompt", 90, 0.9D, "source-a"));

        assertThat(gateway.systemPrompt)
                .contains("不要输出任何用户数据")
                .contains("会话内容")
                .contains("文件路径")
                .contains("凭据信息")
                .contains("危险操作");
    }

    @Test
    void shouldIncludeSanitizedEvidenceInLlmDecisionPrompt() throws Exception {
        FixedTextLlmGateway gateway =
                new FixedTextLlmGateway(
                        "{\"send\":true,\"reason\":\"证据显示值得跟进\",\"message_intent\":\"询问是否继续验证\",\"sensitivity\":\"normal\"}");
        ProactiveDecisionService.GatewayLlmDecisionClient client =
                new ProactiveDecisionService.GatewayLlmDecisionClient(gateway);
        ProactiveCandidateRecord candidate = candidate("candidate-evidence", 90, 0.9D, "source-a");
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("lastMessage", "用户说阶段 4.3 需要结合真实聊天内容继续推进");
        evidence.put("secret", "token=ghp_modelsecret12345");
        candidate.setEvidence(evidence);

        client.decide(contextAt(10, 0), candidate);

        assertThat(gateway.userMessage)
                .contains("用户说阶段 4.3 需要结合真实聊天内容继续推进")
                .doesNotContain("ghp_modelsecret12345");
    }

    /**
     * 断言单个候选会被指定门控原因拦截。
     *
     * @param context tick 上下文。
     * @param candidate 待决策候选。
     * @param reason 期望原因。
     */
    private static void assertGateReason(
            ProactiveTickContext context, ProactiveCandidateRecord candidate, String reason)
            throws Exception {
        assertGateReason(context, candidate, reason, Arrays.asList(gate(false)));
    }

    /**
     * 断言单个候选会被指定门控原因拦截。
     *
     * @param context tick 上下文。
     * @param candidate 待决策候选。
     * @param reason 期望原因。
     * @param observations 门控观测列表。
     */
    private static void assertGateReason(
            ProactiveTickContext context,
            ProactiveCandidateRecord candidate,
            String reason,
            List<ProactiveObservationRecord> observations)
            throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        List<ProactiveDecision> decisions =
                new ProactiveDecisionService(repository)
                        .decide(context, Arrays.asList(candidate), observations);
        assertThat(decisions).extracting(ProactiveDecision::getReason).containsExactly(reason);
        assertThat(repository.savedDecisions)
                .extracting(ProactiveDecisionRecord::getReason)
                .containsExactly(reason);
        String expectedStatus =
                "candidate_expired".equals(reason) || "confidence_below_threshold".equals(reason)
                        ? "SKIPPED"
                        : "PENDING";
        assertThat(repository.savedDecisions.get(0).getMetadata())
                .containsEntry("candidateStatus", expectedStatus);
    }

    /** 构造工作时间上下文。 */
    private static ProactiveTickContext contextAt(int hour, int minute) {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(true);
        config.getProactive().setQuietStartHour(23);
        config.getProactive().setQuietEndHour(8);
        config.getProactive().setDailyMaxContacts(3);
        config.getProactive().setCooldownMinutes(120);
        config.getProactive().setMinConfidenceToContact(0.65D);
        config.getProactive().setLlmDecisionEnabled(false);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setTickId("tick-decision");
        context.setConfig(config);
        context.setNowMillis(
                LocalDateTime.of(2026, 6, 16, hour, minute)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli());
        context.setHomeChannels(Arrays.asList(home(PlatformType.WEIXIN, "chat-1")));
        return context;
    }

    /** 构造缺少 home channel 的上下文。 */
    private static ProactiveTickContext noHomeContext() {
        ProactiveTickContext context = contextAt(10, 0);
        context.setHomeChannels(new ArrayList<HomeChannelRecord>());
        return context;
    }

    /** 构造静默时段上下文。 */
    private static ProactiveTickContext quietContext() {
        return contextAt(23, 30);
    }

    /** 构造测试 home channel。 */
    private static HomeChannelRecord home(PlatformType platform, String chatId) {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(platform);
        record.setChatId(chatId);
        record.setThreadId("thread-" + chatId);
        record.setChatName("主动协作测试");
        record.setUpdatedAt(1_800_000_000_000L);
        return record;
    }

    /** 构造候选记录。 */
    private static ProactiveCandidateRecord candidate(
            String candidateId, int priority, double confidence, String sourceKey) {
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId(candidateId);
        candidate.setSourceType("session");
        candidate.setSourceRef(candidateId);
        candidate.setSourceKey(sourceKey);
        candidate.setSubjectType("session");
        candidate.setSubjectRef(candidateId);
        candidate.setTopic("work_continuation");
        candidate.setTitle("候选 " + candidateId);
        candidate.setSummary("需要决策");
        candidate.setReason("测试原因");
        candidate.setActionOffer("询问用户是否继续");
        candidate.setConfidence(confidence);
        candidate.setPriority(priority);
        candidate.setDedupKey("dedup:" + candidateId);
        candidate.setStateHash("state:" + candidateId);
        candidate.setCreatedAt(1_797_000_000_000L + priority);
        candidate.setExpiresAt(1_800_000_000_000L);
        candidate.setStatus("PENDING");
        candidate.setUpdatedAt(candidate.getCreatedAt());
        candidate.setEvidence(new LinkedHashMap<String, Object>());
        return candidate;
    }

    /**
     * 构造主动协作门控观测。
     *
     * @param quietHour 是否静默时段。
     * @param activeSources 活跃运行来源列表。
     * @return 返回门控观测记录。
     */
    private static ProactiveObservationRecord gate(boolean quietHour, String... activeSources) {
        ProactiveObservationRecord observation = new ProactiveObservationRecord();
        observation.setObservationId("obs-gate");
        observation.setTickId("tick-decision");
        observation.setCollector("quiet_context");
        observation.setSourceKey("proactive_context:global");
        observation.setStatus("COLLECTED");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "proactive_context");
        payload.put("gateOnly", Boolean.TRUE);
        payload.put("homeChannelReady", Boolean.TRUE);
        payload.put("quietHour", Boolean.valueOf(quietHour));
        List<Map<String, Object>> activeRuns = new ArrayList<Map<String, Object>>();
        for (String activeSource : activeSources) {
            Map<String, Object> run = new LinkedHashMap<String, Object>();
            run.put("runId", "run-" + activeSource);
            run.put("sourceKey", activeSource);
            run.put("status", "running");
            activeRuns.add(run);
        }
        payload.put("activeRuns", activeRuns);
        observation.setPayload(payload);
        observation.setCreatedAt(1_797_000_000_000L);
        return observation;
    }

    /** 测试用 LLM 决策客户端。 */
    private static final class FakeLlmDecisionClient
            implements ProactiveDecisionService.LlmDecisionClient {
        /** 调用次数。 */
        private int calls;

        /** 固定返回结果。 */
        private final ProactiveDecisionService.LlmDecisionResult result;

        /**
         * 创建测试客户端。
         *
         * @param result 固定返回结果。
         */
        private FakeLlmDecisionClient(ProactiveDecisionService.LlmDecisionResult result) {
            this.result = result;
        }

        @Override
        public ProactiveDecisionService.LlmDecisionResult decide(
                ProactiveTickContext context, ProactiveCandidateRecord candidate) {
            calls++;
            return result;
        }
    }

    /** 固定返回文本的大模型网关，用于覆盖主动协作 JSON 解析和提示词边界。 */
    private static final class FixedTextLlmGateway implements LlmGateway {
        /** 模型固定输出。 */
        private final String text;

        /** 最近一次系统提示词。 */
        private String systemPrompt;

        /** 最近一次用户提示词。 */
        private String userMessage;

        /** 创建固定文本网关。 */
        private FixedTextLlmGateway(String text) {
            this.text = text;
        }

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            this.systemPrompt = systemPrompt;
            this.userMessage = userMessage;
            LlmResult result = new LlmResult();
            result.setAssistantMessage(ChatMessage.ofAssistant(text));
            return result;
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException("主动协作决策测试不需要恢复会话");
        }
    }

    /** 决策服务测试用内存仓储。 */
    private static class InMemoryProactiveRepository implements ProactiveRepository {
        /** 保存的决策记录。 */
        private final List<ProactiveDecisionRecord> savedDecisions =
                new ArrayList<ProactiveDecisionRecord>();

        /** 全局发送窗口计数。 */
        private int sentSince;

        /** 最近发送时间。 */
        private Long lastSentAt;

        @Override
        public void saveObservation(ProactiveObservationRecord observation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveCandidate(ProactiveCandidateRecord candidate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProactiveCandidateRecord findRecentCandidateByDedup(
                String dedupKey, String stateHash, long nowMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProactiveCandidateRecord> listPendingCandidates(long nowMillis, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markCandidateStatus(
                String candidateId, String status, String decisionId, long updatedAt) {
            for (ProactiveDecisionRecord decision : savedDecisions) {
                if (decision.getDecisionId().equals(decisionId)) {
                    Map<String, Object> metadata =
                            decision.getMetadata() == null
                                    ? new LinkedHashMap<String, Object>()
                                    : decision.getMetadata();
                    metadata.put("candidateStatus", status);
                    decision.setMetadata(metadata);
                }
            }
        }

        @Override
        public void saveDecision(ProactiveDecisionRecord decision) {
            savedDecisions.add(decision);
        }

        @Override
        public int countSentSince(String sourceKey, long sinceMillis) {
            return sentSince;
        }

        @Override
        public Long findLastSentAt(String sourceKey) {
            return lastSentAt;
        }

        @Override
        public ProactiveSourceSnapshotRecord findSnapshot(String sourceType, String sourceRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveSnapshot(ProactiveSourceSnapshotRecord snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProactiveDecisionRecord> listRecentDecisions(int limit) {
            return new ArrayList<ProactiveDecisionRecord>();
        }
    }
}
