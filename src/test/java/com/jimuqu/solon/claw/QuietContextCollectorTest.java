package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.proactive.collector.QuietContextCollector;
import com.jimuqu.solon.claw.support.UnsupportedAgentRunRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 静默上下文观测采集器测试，覆盖 home channel、跨午夜静默时间和前台运行门控信号。 */
public class QuietContextCollectorTest {
    @Test
    void shouldEmitDiagnosticObservationWhenHomeChannelMissing() throws Exception {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        ProactiveTickContext context = contextAt(10, 0);

        List<ProactiveObservation> observations =
                new QuietContextCollector(runRepository).collect(context);

        ProactiveObservation observation = observationOfType(observations, "proactive_context");
        assertThat(observation.getCollector()).isEqualTo("quiet_context");
        assertThat(observation.getStatus()).isEqualTo("COLLECTED");
        assertThat(observation.getSourceKey()).isEqualTo("proactive_context:global");
        assertThat(observation.getPayload()).containsEntry("homeChannelReady", Boolean.FALSE);
        assertThat(observation.getPayload()).containsEntry("quietHour", Boolean.FALSE);
        assertThat(observation.getPayload()).containsEntry("missingHomeChannel", Boolean.TRUE);
        assertThat(observation.getPayload()).containsEntry("gateOnly", Boolean.TRUE);
        assertThat(observation.getSummary()).contains("home channel 未配置");
    }

    @Test
    void shouldDetectQuietHourAcrossMidnight() throws Exception {
        ProactiveTickContext lateNight = contextAt(23, 30);
        lateNight.setHomeChannels(Collections.singletonList(home(PlatformType.WEIXIN, "chat-1")));

        ProactiveObservation lateNightObservation =
                observationOfType(
                        new QuietContextCollector(new InMemoryAgentRunRepository()).collect(lateNight),
                        "proactive_context");

        assertThat(lateNightObservation.getPayload()).containsEntry("quietHour", Boolean.TRUE);

        ProactiveTickContext earlyMorning = contextAt(7, 30);
        earlyMorning.setHomeChannels(Collections.singletonList(home(PlatformType.WEIXIN, "chat-1")));

        ProactiveObservation earlyMorningObservation =
                observationOfType(
                        new QuietContextCollector(new InMemoryAgentRunRepository()).collect(earlyMorning),
                        "proactive_context");

        assertThat(earlyMorningObservation.getPayload()).containsEntry("quietHour", Boolean.TRUE);

        ProactiveTickContext daytime = contextAt(9, 0);
        daytime.setHomeChannels(Collections.singletonList(home(PlatformType.WEIXIN, "chat-1")));

        ProactiveObservation daytimeObservation =
                observationOfType(
                        new QuietContextCollector(new InMemoryAgentRunRepository()).collect(daytime),
                        "proactive_context");

        assertThat(daytimeObservation.getPayload()).containsEntry("quietHour", Boolean.FALSE);
    }

    @Test
    void shouldIncludeHomeChannelAndActiveRunGateEvidence() throws Exception {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        runRepository.activeRuns.add(activeRun("run-active", "weixin:chat-1"));
        ProactiveTickContext context = contextAt(10, 0);
        context.setHomeChannels(Collections.singletonList(home(PlatformType.WEIXIN, "chat-1")));

        ProactiveObservation observation =
                observationOfType(new QuietContextCollector(runRepository).collect(context), "proactive_context");

        assertThat(observation.getPayload()).containsEntry("homeChannelReady", Boolean.TRUE);
        assertThat(observation.getPayload()).containsEntry("homeChannelCount", Integer.valueOf(1));
        assertThat(observation.getPayload()).containsEntry("activeRunCount", Integer.valueOf(1));
        assertThat(observation.getPayload()).containsEntry("lastSentAt", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> activeRuns =
                (List<Map<String, Object>>) observation.getPayload().get("activeRuns");
        assertThat(activeRuns).hasSize(1);
        assertThat(activeRuns.get(0)).containsEntry("runId", "run-active");
        assertThat(activeRuns.get(0)).containsEntry("sourceKey", "weixin:chat-1");
    }

    @Test
    void shouldReturnEmptyWhenDisabledOrContextMissing() throws Exception {
        ProactiveTickContext disabled = contextAt(10, 0);
        disabled.getConfig().getProactive().setEnabled(false);

        assertThat(new QuietContextCollector(new InMemoryAgentRunRepository()).collect(disabled))
                .isEmpty();
        assertThat(new QuietContextCollector(new InMemoryAgentRunRepository()).collect(null))
                .isEmpty();
    }

    /** 构造指定本地小时的 tick 上下文。 */
    private static ProactiveTickContext contextAt(int hour, int minute) {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(true);
        config.getProactive().setQuietStartHour(23);
        config.getProactive().setQuietEndHour(8);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setConfig(config);
        context.setTickId("tick-quiet-context");
        context.setNowMillis(
                LocalDateTime.of(2026, 6, 16, hour, minute)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli());
        return context;
    }

    /** 构造测试 home channel。 */
    private static HomeChannelRecord home(PlatformType platform, String chatId) {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(platform);
        record.setChatId(chatId);
        record.setChatName("测试 home");
        record.setUpdatedAt(1_800_000_000_000L);
        return record;
    }

    /** 构造测试活跃运行。 */
    private static AgentRunRecord activeRun(String runId, String sourceKey) {
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId(runId);
        run.setSessionId("session-" + runId);
        run.setSourceKey(sourceKey);
        run.setStatus("running");
        run.setPhase("agent_loop");
        run.setLastActivityAt(1_800_000_000_000L - 1_000L);
        return run;
    }

    /** 从观测列表中查找指定类型。 */
    private static ProactiveObservation observationOfType(
            List<ProactiveObservation> observations, String type) {
        assertThat(observations).extracting(item -> item.getPayload().get("type")).contains(type);
        for (ProactiveObservation observation : observations) {
            if (type.equals(observation.getPayload().get("type"))) {
                return observation;
            }
        }
        throw new AssertionError("missing observation type: " + type);
    }

    /** 面向静默上下文采集器测试的内存运行仓储。 */
    private static final class InMemoryAgentRunRepository extends UnsupportedAgentRunRepository {
        /** searchRuns 返回的活跃运行记录。 */
        private final List<AgentRunRecord> activeRuns = new ArrayList<AgentRunRecord>();

        @Override
        public List<AgentRunRecord> searchRuns(
                String sourceKey,
                String sessionId,
                String runId,
                String query,
                long timeFrom,
                long timeTo,
                int limit) {
            return activeRuns;
        }
    }
}
