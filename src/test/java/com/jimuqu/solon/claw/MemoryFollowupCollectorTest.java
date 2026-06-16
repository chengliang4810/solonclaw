package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.proactive.collector.MemoryFollowupCollector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 记忆跟进观测采集器测试，覆盖普通偏好过滤和可跟进知识线索识别。 */
public class MemoryFollowupCollectorTest {
    @Test
    void shouldIgnoreGenericUserPreferenceMemory() throws Exception {
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setUserText("- 用户喜欢简洁回答\n- 用户偏好中文说明\n- 用户偏好项目通知使用中文");
        memoryService.snapshot.setMemoryText("- 用户希望先给结论再解释");
        memoryService.snapshot.setDailyMemoryText("- 今天用户要求回复直接一点");

        List<ProactiveObservation> observations =
                new MemoryFollowupCollector(memoryService).collect(context(true));

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldEmitProjectWatchFollowupFromMemoryLine() throws Exception {
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setMemoryText(
                "# 项目记忆\n"
                        + "- 持续关注 /Users/chengliang/code-projects/solon-claw-demo 的更新并主动提醒 token=secret-token-1234567890\n"
                        + "- 用户喜欢简洁回答");

        List<ProactiveObservation> observations =
                new MemoryFollowupCollector(memoryService).collect(context(true));

        ProactiveObservation observation = observationOfType(observations, "knowledge_followup");
        assertThat(observation.getCollector()).isEqualTo("memory_followup");
        assertThat(observation.getStatus()).isEqualTo("COLLECTED");
        assertThat(observation.getSourceKey()).startsWith("memory_followup:memory:");
        assertThat(observation.getSummary()).contains("solon-claw-demo");
        assertThat(observation.getSummary()).contains("token=***");
        assertThat(observation.getSummary()).doesNotContain("secret-token-1234567890");
        assertThat(observation.getPayload()).containsEntry("type", "knowledge_followup");
        assertThat(observation.getPayload()).containsEntry("section", "memory");
        assertThat(observation.getPayload()).containsEntry("sourceRef", "MEMORY.md");
        assertThat(observation.getPayload()).containsEntry("priority", "low");
        assertThat(observation.getPayload()).containsEntry("confidenceHint", "high");
        assertThat(labels(observation)).contains("repo_watch", "preferred_followup");
        assertThat(String.valueOf(evidence(observation).get("lines"))).contains("token=***");
        assertThat(String.valueOf(observation.getPayload())).doesNotContain("secret-token-1234567890");
    }

    @Test
    void shouldEmitFollowupFromDailyMemoryLine() throws Exception {
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setDailyMemoryText(
                "- 今天需要继续跟进 bzaqweb 风险审批工作台，下次主动询问是否要协作处理");

        List<ProactiveObservation> observations =
                new MemoryFollowupCollector(memoryService).collect(context(true));

        ProactiveObservation observation = observationOfType(observations, "knowledge_followup");
        assertThat(observation.getPayload()).containsEntry("section", "today");
        assertThat(observation.getPayload()).containsEntry("sourceRef", "TODAY_MEMORY");
        assertThat(labels(observation)).contains("ongoing_work", "preferred_followup");
        assertThat(String.valueOf(evidence(observation).get("lines"))).contains("bzaqweb");
    }

    @Test
    void shouldEmitPreferredFollowupWithoutProjectKeyword() throws Exception {
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setUserText("- 用户希望你隔天主动询问是否继续处理");

        List<ProactiveObservation> observations =
                new MemoryFollowupCollector(memoryService).collect(context(true));

        ProactiveObservation observation = observationOfType(observations, "knowledge_followup");
        assertThat(observation.getPayload()).containsEntry("confidenceHint", "medium");
        assertThat(labels(observation)).contains("preferred_followup", "explicit_followup");
        assertThat(String.valueOf(evidence(observation).get("lines"))).contains("隔天主动询问");
    }

    @Test
    void shouldEmitRecurringResponsibilityWithoutProjectKeyword() throws Exception {
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setMemoryText("- 用户每周负责周报整理");

        List<ProactiveObservation> observations =
                new MemoryFollowupCollector(memoryService).collect(context(true));

        ProactiveObservation observation = observationOfType(observations, "knowledge_followup");
        assertThat(observation.getPayload()).containsEntry("confidenceHint", "medium");
        assertThat(labels(observation)).contains("recurring_responsibility", "work_responsibility");
        assertThat(String.valueOf(evidence(observation).get("lines"))).contains("周报整理");
    }

    @Test
    void shouldNotCollectWhenDisabledOrSnapshotMissing() throws Exception {
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setMemoryText("- 每周跟进 solon-claw 插件系统建设");

        assertThat(new MemoryFollowupCollector(memoryService).collect(context(false))).isEmpty();

        memoryService.snapshot = null;
        assertThat(new MemoryFollowupCollector(memoryService).collect(context(true))).isEmpty();
    }

    @Test
    void shouldNotCollectWhenContextOrDependencyMissing() throws Exception {
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setMemoryText("- 每周跟进 solon-claw 插件系统建设");

        assertThat(new MemoryFollowupCollector(memoryService).collect(null)).isEmpty();
        assertThat(new MemoryFollowupCollector(null).collect(context(true))).isEmpty();

        memoryService.snapshot.setMemoryText(" \n# 只有标题\n-   ");
        memoryService.snapshot.setUserText(null);
        memoryService.snapshot.setDailyMemoryText("");
        assertThat(new MemoryFollowupCollector(memoryService).collect(context(true))).isEmpty();
    }

    /** 从观测列表中查找指定类型，缺失时让断言输出包含实际类型，便于定位。 */
    private static ProactiveObservation observationOfType(
            List<ProactiveObservation> observations, String type) {
        for (ProactiveObservation observation : observations) {
            if (type.equals(observation.getPayload().get("type"))) {
                return observation;
            }
        }
        assertThat(observations).extracting(item -> item.getPayload().get("type")).contains(type);
        return null;
    }

    /** 读取观测中的 evidence 子载荷，便于验证记忆证据不会被顶层 payload 数量裁剪丢失。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(ProactiveObservation observation) {
        Object value = observation.getPayload().get("evidence");
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** 读取观测中的原因标签列表，便于验证不同记忆线索命中的规则。 */
    @SuppressWarnings("unchecked")
    private static List<String> labels(ProactiveObservation observation) {
        Object value = observation.getPayload().get("reasonLabels");
        assertThat(value).isInstanceOf(List.class);
        return (List<String>) value;
    }

    /** 构造启用或关闭主动协作的测试 tick 上下文。 */
    private static ProactiveTickContext context(boolean enabled) {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(enabled);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setConfig(config);
        context.setNowMillis(1_800_000_000_000L);
        context.setTickId("tick-memory-followup");
        return context;
    }

    /** 测试用内存记忆服务，只实现采集器读取快照所需的接口。 */
    private static final class InMemoryMemoryService implements MemoryService {
        /** 采集器读取的测试快照。 */
        private MemorySnapshot snapshot = new MemorySnapshot();

        @Override
        public MemorySnapshot loadSnapshot() {
            return snapshot;
        }

        @Override
        public String read(String target) {
            throw new UnsupportedOperationException("测试记忆服务不支持读取单个目标");
        }

        @Override
        public String add(String target, String content) {
            throw new UnsupportedOperationException("测试记忆服务不支持追加记忆");
        }

        @Override
        public String replace(String target, String oldText, String newContent) {
            throw new UnsupportedOperationException("测试记忆服务不支持替换记忆");
        }

        @Override
        public String remove(String target, String matchText) {
            throw new UnsupportedOperationException("测试记忆服务不支持删除记忆");
        }
    }
}
