package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.proactive.ProactiveMessageComposer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/** 主动协作安全与命名守卫测试，防止主动触达能力绕过确认或引入写仓库探测。 */
public class ProactiveNamingGuardTest {
    /** 仓库根目录，测试从 Maven 工作目录运行。 */
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();

    /** 主动协作文案生成源码路径。 */
    private static final Path COMPOSER =
            ROOT.resolve("src/main/java/com/jimuqu/solon/claw/proactive/ProactiveMessageComposer.java");

    /** 默认仓库探测源码路径。 */
    private static final Path REPOSITORY_PROBE =
            ROOT.resolve(
                    "src/main/java/com/jimuqu/solon/claw/proactive/DefaultRepositoryProbeService.java");

    /** 现有命名守卫脚本路径。 */
    private static final Path NAMING_SCRIPT = ROOT.resolve("scripts/check-project-naming.py");

    @Test
    void shouldKeepNamingGuardScriptAvailableForProactiveSources() {
        assertThat(Files.isRegularFile(NAMING_SCRIPT)).isTrue();
    }

    @Test
    void shouldKeepComposerPromptBoundedByUserConfirmation() throws Exception {
        String source = read(COMPOSER);

        assertThat(source).contains("不能声称已经执行");
        assertThat(source).contains("不能承诺会执行命令或修改文件");
        assertThat(source).contains("必须询问用户是否需要协作");
        assertThat(source).contains("ensurePermissionQuestion");
    }

    @Test
    void shouldKeepRepositoryProbeReadOnly() throws Exception {
        String source = read(REPOSITORY_PROBE);

        assertThat(source)
                .contains("\"rev-parse\"")
                .contains("\"ls-remote\"")
                .contains("\"remote\"");
        assertThat(source)
                .doesNotContain("\"pull\"")
                .doesNotContain("\"fetch\"")
                .doesNotContain("\"checkout\"")
                .doesNotContain("\"commit\"")
                .doesNotContain("\"push\"")
                .doesNotContain("\"merge\"");
    }

    @Test
    void shouldScrubOutboundMessageAndAskBeforeAction() throws Exception {
        ProactiveCandidateRecord candidate = candidate();
        ProactiveDecision decision = decision(candidate);
        ProactiveTickContext context = context();

        String message = new ProactiveMessageComposer().compose(context, decision);

        assertThat(message).startsWith("主动协作：");
        assertThat(message).contains("要不要");
        assertThat(message).doesNotContain("memory-context");
        assertThat(message).doesNotContain("sk-test-proactive-guard-123456");
        assertThat(message).doesNotContain("直接执行");
        assertThat(message).doesNotContain("自动提交");
        assertThat(message).doesNotContain("自动推送");
    }

    /** 读取 UTF-8 文本文件。 */
    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 构造主动协作 tick 上下文。 */
    private static ProactiveTickContext context() {
        AppConfig appConfig = new AppConfig();
        appConfig.getProactive().setDeliveryPreviewPrefix("主动协作");
        appConfig.getProactive().setLlmPolishEnabled(false);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setTickId("tick-guard");
        context.setNowMillis(1_800_000_000_000L);
        context.setConfig(appConfig);
        return context;
    }

    /** 构造带敏感信息和风险动作的候选。 */
    private static ProactiveCandidateRecord candidate() {
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId("candidate-guard");
        candidate.setSourceType("knowledge_followup");
        candidate.setSourceRef("memory-guard");
        candidate.setSourceKey("WEIXIN:room:user");
        candidate.setSubjectType("memory");
        candidate.setSubjectRef("memory-guard");
        candidate.setTopic("knowledge_followup");
        candidate.setTitle("<memory-context>内部上下文</memory-context>项目跟进");
        candidate.setSummary("发现 token=sk-test-proactive-guard-123456，建议继续看一下。");
        candidate.setReason("用户之前提到过相关项目。");
        candidate.setActionOffer("直接执行验证并自动提交、自动推送");
        candidate.setEvidence(new LinkedHashMap<String, Object>());
        candidate.setConfidence(0.9D);
        candidate.setPriority(80);
        candidate.setDedupKey("guard:memory");
        candidate.setStateHash("state-guard");
        candidate.setCreatedAt(1_800_000_000_000L);
        candidate.setExpiresAt(1_800_010_000_000L);
        candidate.setStatus("APPROVED");
        candidate.setUpdatedAt(candidate.getCreatedAt());
        return candidate;
    }

    /** 构造 SEND 决策。 */
    private static ProactiveDecision decision(ProactiveCandidateRecord candidate) {
        ProactiveDecision decision = new ProactiveDecision();
        decision.setDecisionId("decision-guard");
        decision.setTickId("tick-guard");
        decision.setCandidateId(candidate.getCandidateId());
        decision.setSourceKey(candidate.getSourceKey());
        decision.setDecision("SEND");
        decision.setReason("deterministic_allow");
        decision.setMessageIntent("只询问用户是否需要协作");
        decision.setSensitivity("normal");
        decision.setCandidate(candidate);
        decision.setMetadata(new LinkedHashMap<String, Object>());
        decision.setCreatedAt(1_800_000_000_000L);
        return decision;
    }
}
