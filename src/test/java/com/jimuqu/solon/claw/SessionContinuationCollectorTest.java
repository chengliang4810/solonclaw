package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.goal.GoalState;
import com.jimuqu.solon.claw.proactive.collector.SessionContinuationCollector;
import com.jimuqu.solon.claw.support.FixedSessionRepository;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 会话续接观测采集器测试，覆盖主动目标、待确认与坏消息解析边界。 */
public class SessionContinuationCollectorTest {
    /** 测试固定当前时间，避免回看窗口判断随系统时间漂移。 */
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void shouldSkipCompletedSessionWithFinalSignal() throws Exception {
        SessionRecord completed =
                session(
                        "done-session",
                        "home:done",
                        "main",
                        "发布任务",
                        NOW - 1_000L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("请完成发布"),
                                        ChatMessage.ofAssistant("已完成，已提交并已推送。"))));
        GoalState goal = new GoalState();
        goal.setStatus(GoalState.STATUS_DONE);
        completed.setGoalStateJson(goal.toJson());

        List<ProactiveObservation> observations =
                collector(completed).collect(context(7));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldSkipOrdinaryAnsweredSessionWithoutContinuationSignal() throws Exception {
        SessionRecord answered =
                session(
                        "answered-session",
                        "home:answered",
                        "main",
                        "普通咨询",
                        NOW - 1_500L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("帮我解释一下这个配置项的作用"),
                                        ChatMessage.ofAssistant("这个配置项用于控制默认输出长度。"))));

        List<ProactiveObservation> observations =
                collector(answered).collect(context(7));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldSkipOrdinaryExplanationEvenWhenItMentionsReviewTerms() throws Exception {
        SessionRecord answered =
                session(
                        "review-explanation-session",
                        "home:review-help",
                        "main",
                        "概念解释",
                        NOW - 1_500L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("什么是 code review 和 pull request？"),
                                        ChatMessage.ofAssistant("code review 是代码评审，pull request 是协作提交入口。"))));

        List<ProactiveObservation> observations =
                collector(answered).collect(context(7));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldSkipDoneGoalWithStaleBlockedReason() throws Exception {
        SessionRecord completedGoal =
                session(
                        "done-blocked-session",
                        "home:done-blocked",
                        "main",
                        "已收尾目标",
                        NOW - 1_000L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("处理这个阻塞的任务"),
                                        ChatMessage.ofAssistant("已完成，验证通过。"))));
        GoalState goal = new GoalState();
        goal.setStatus(GoalState.STATUS_DONE);
        goal.setLastReason("之前 blocked，后来已经处理");
        completedGoal.setGoalStateJson(goal.toJson());

        List<ProactiveObservation> observations =
                collector(completedGoal).collect(context(7));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldCreateObservationForContinuationSessionWithStructuredPayload() throws Exception {
        SessionRecord waiting =
                session(
                        "waiting-session",
                        "home:waiting",
                        "work/proactive-collaboration",
                        "上线前 review",
                        NOW - 2_000L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("请 review 后 push"),
                                        ChatMessage.ofAssistant("我已经完成检查，是否继续 merge 到 dev？"))));

        List<ProactiveObservation> observations =
                collector(waiting).collect(context(7));

        assertThat(observations).hasSize(1);
        ProactiveObservation observation = observations.get(0);
        assertThat(observation.getCollector()).isEqualTo("session_continuation");
        assertThat(observation.getSourceKey()).isEqualTo("home:waiting");
        assertThat(observation.getSummary()).contains("session_continuation");
        Map<String, Object> payload = observation.getPayload();
        assertThat(payload)
                .containsEntry("type", "session_continuation")
                .containsEntry("sessionId", "waiting-session")
                .containsEntry("title", "上线前 review")
                .containsEntry("branchName", "work/proactive-collaboration")
                .containsEntry("sourceKey", "home:waiting")
                .containsEntry("updatedAt", Long.valueOf(NOW - 2_000L));
        assertThat(String.valueOf(payload.get("finalReplyPreview"))).contains("是否继续");
        assertThat((List<?>) payload.get("reasons")).isNotEmpty();
    }

    @Test
    void shouldCreateObservationForActiveGoal() throws Exception {
        SessionRecord active =
                session(
                        "active-goal-session",
                        "home:goal",
                        "work/goal",
                        "持续测试目标",
                        NOW - 3_000L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("继续执行目标"),
                                        ChatMessage.ofAssistant("我会继续处理剩余检查。"))));
        GoalState goal = new GoalState();
        goal.setStatus(GoalState.STATUS_ACTIVE);
        goal.setGoal("完成剩余验证");
        active.setGoalStateJson(goal.toJson());

        List<ProactiveObservation> observations =
                collector(active).collect(context(7));

        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getPayload().get("reasons").toString())
                .contains("goal_active");
    }

    @Test
    void shouldIgnoreSessionsOlderThanLookbackWindow() throws Exception {
        SessionRecord old =
                session(
                        "old-session",
                        "home:old",
                        "work/old",
                        "等待确认",
                        NOW - 3L * 24L * 60L * 60L * 1000L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("请继续"),
                                        ChatMessage.ofAssistant("需要你确认是否继续。"))));

        List<ProactiveObservation> observations =
                collector(old).collect(context(1));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldSkipBadNdjsonAndContinueOtherSessions() throws Exception {
        SessionRecord broken =
                session("broken-session", "home:broken", "work/bad", "坏消息", NOW - 1_000L, "{bad");
        SessionRecord valid =
                session(
                        "valid-session",
                        "home:valid",
                        "work/valid",
                        "等待 follow-up",
                        NOW - 1_000L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("请 follow-up"),
                                        ChatMessage.ofAssistant("等待你确认后我继续。"))));

        List<ProactiveObservation> observations =
                collector(broken, valid).collect(context(7));

        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getPayload()).containsEntry("sessionId", "valid-session");
    }

    @Test
    void shouldTreatNullRecentSessionListAsEmpty() throws Exception {
        List<ProactiveObservation> observations =
                new SessionContinuationCollector(new FixedSessionRepository(null, true)).collect(context(7));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldClampExtremeLookbackDaysWithoutOverflow() throws Exception {
        SessionRecord waiting =
                session(
                        "extreme-lookback-session",
                        "home:extreme",
                        "work/extreme",
                        "等待确认",
                        NOW - 1_000L,
                        MessageSupport.toNdjson(
                                Arrays.asList(
                                        ChatMessage.ofUser("请继续验证"),
                                        ChatMessage.ofAssistant("需要你确认后我继续。"))));

        List<ProactiveObservation> observations =
                collector(waiting).collect(context(Integer.MAX_VALUE));

        assertThat(observations).hasSize(1);
    }

    /** 创建待测采集器并注入内存会话仓储。 */
    private static SessionContinuationCollector collector(SessionRecord... sessions) {
        return new SessionContinuationCollector(new FixedSessionRepository(Arrays.asList(sessions)));
    }

    /** 构造主动协作 tick 上下文并开启指定天数的会话回看窗口。 */
    private static ProactiveTickContext context(int lookbackDays) {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(true);
        config.getProactive().setSessionLookbackDays(lookbackDays);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setNowMillis(NOW);
        context.setConfig(config);
        return context;
    }

    /** 构造测试会话记录，保留采集器需要读取的关键字段。 */
    private static SessionRecord session(
            String sessionId,
            String sourceKey,
            String branchName,
            String title,
            long updatedAt,
            String ndjson) {
        SessionRecord record = new SessionRecord();
        record.setSessionId(sessionId);
        record.setSourceKey(sourceKey);
        record.setBranchName(branchName);
        record.setTitle(title);
        record.setUpdatedAt(updatedAt);
        record.setNdjson(ndjson);
        return record;
    }

}
