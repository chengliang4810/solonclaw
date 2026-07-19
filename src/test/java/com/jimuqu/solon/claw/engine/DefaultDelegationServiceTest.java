package com.jimuqu.solon.claw.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.ConversationOrchestratorHolder;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证委托子任务时会把父侧工具边界收敛到交集。 */
public class DefaultDelegationServiceTest {
    /** 验证父工具快照、临时白名单和显式请求会同时生效。 */
    @Test
    void shouldKeepOnlyIntersectionOfParentSnapshotAndRequestedTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqlitePreferenceStore preferenceStore = new SqlitePreferenceStore(env.sqliteDatabase);
        DefaultDelegationService service =
                new DefaultDelegationService(
                        new ConversationOrchestratorHolder(),
                        preferenceStore,
                        env.sessionRepository);
        String parentSourceKey = "MEMORY:chat:parent:user";
        String childSourceKey = "MEMORY:chat:child:user";
        preferenceStore.setToolEnabledGlobal("file_read", true);
        preferenceStore.setToolEnabledGlobal("file_write", true);

        AgentRunContext parentContext =
                new AgentRunContext(null, "run-1", "session-1", parentSourceKey);
        parentContext.setEnabledToolNames(Arrays.asList("file_read", "file_write"));
        parentContext.setToolPolicy(Collections.singletonList("file_read"), 3);
        AgentRunContext previous = AgentRunContext.current();
        AgentRunContext.setCurrent(parentContext);
        try {
            Method method =
                    DefaultDelegationService.class.getDeclaredMethod(
                            "applyAllowedTools",
                            String.class,
                            String.class,
                            List.class,
                            List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> effective =
                    (List<String>)
                            method.invoke(
                                    service,
                                    parentSourceKey,
                                    childSourceKey,
                                    Arrays.asList("file_read", "file_write"),
                                    Collections.emptyList());

            assertThat(effective).containsExactly("file_read");
            assertThat(preferenceStore.isToolEnabled(childSourceKey, "file_read")).isTrue();
            assertThat(preferenceStore.isToolEnabled(childSourceKey, "file_write")).isFalse();
        } finally {
            AgentRunContext.setCurrent(previous);
        }
    }

    /** 缺少父运行上下文时必须拒绝全部工具，但仍允许创建无工具子任务。 */
    @Test
    void shouldFailClosedWithoutParentRunContext() throws Exception {
        TestFixture fixture = fixture();

        List<String> effective =
                applyAllowedTools(
                        fixture.service,
                        fixture.parentSourceKey,
                        fixture.childSourceKey,
                        Collections.singletonList("file_read"));

        assertThat(effective).isEmpty();
        assertThat(fixture.preferenceStore.isToolEnabled(fixture.childSourceKey, "file_read"))
                .isFalse();
    }

    /** 父运行上下文来源键错配时不得借用其他会话的工具权限。 */
    @Test
    void shouldFailClosedForMismatchedParentRunContext() throws Exception {
        TestFixture fixture = fixture();
        AgentRunContext mismatched =
                new AgentRunContext(null, "run-mismatch", "session-mismatch", "MEMORY:other:user");
        mismatched.setEnabledToolNames(Collections.singletonList("file_read"));

        List<String> effective =
                withCurrentContext(
                        mismatched,
                        () ->
                                applyAllowedTools(
                                        fixture.service,
                                        fixture.parentSourceKey,
                                        fixture.childSourceKey,
                                        Collections.singletonList("file_read")));

        assertThat(effective).isEmpty();
    }

    /** 未提供父运行实际工具快照时不得仅凭持久化偏好放大能力。 */
    @Test
    void shouldFailClosedWithoutParentToolSnapshot() throws Exception {
        TestFixture fixture = fixture();
        AgentRunContext parent =
                new AgentRunContext(
                        null, "run-no-snapshot", "session-no-snapshot", fixture.parentSourceKey);

        List<String> effective =
                withCurrentContext(
                        parent,
                        () ->
                                applyAllowedTools(
                                        fixture.service,
                                        fixture.parentSourceKey,
                                        fixture.childSourceKey,
                                        Collections.singletonList("file_read")));

        assertThat(effective).isEmpty();
    }

    /** 默认关闭的工具网关必须在父偏好未显式开启时保持关闭。 */
    @Test
    void shouldKeepToolGatewayDisabledByDefault() throws Exception {
        TestFixture fixture = fixture();
        AgentRunContext parent =
                new AgentRunContext(
                        null, "run-tool-gateway", "session-tool-gateway", fixture.parentSourceKey);
        parent.setEnabledToolNames(Collections.singletonList("tool_gateway"));

        List<String> effective =
                withCurrentContext(
                        parent,
                        () ->
                                applyAllowedTools(
                                        fixture.service,
                                        fixture.parentSourceKey,
                                        fixture.childSourceKey,
                                        Collections.singletonList("tool_gateway")));

        assertThat(effective).isEmpty();
    }

    /** 子 Agent 固定黑名单即使同时出现在父快照和请求中也不得启用。 */
    @Test
    void shouldAlwaysRejectFixedBlockedTools() throws Exception {
        TestFixture fixture = fixture();
        AgentRunContext parent =
                new AgentRunContext(
                        null, "run-blocked", "session-blocked", fixture.parentSourceKey);
        parent.setEnabledToolNames(Arrays.asList("memory", "delegate_task", "file_read"));

        List<String> effective =
                withCurrentContext(
                        parent,
                        () ->
                                applyAllowedTools(
                                        fixture.service,
                                        fixture.parentSourceKey,
                                        fixture.childSourceKey,
                                        Arrays.asList("memory", "delegate_task", "file_read")));

        assertThat(effective).containsExactly("file_read");
        assertThat(fixture.preferenceStore.isToolEnabled(fixture.childSourceKey, "memory"))
                .isFalse();
        assertThat(fixture.preferenceStore.isToolEnabled(fixture.childSourceKey, "delegate_task"))
                .isFalse();
    }

    /** 创建反射测试使用的最小委派服务环境。 */
    private TestFixture fixture() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqlitePreferenceStore preferenceStore = new SqlitePreferenceStore(env.sqliteDatabase);
        DefaultDelegationService service =
                new DefaultDelegationService(
                        new ConversationOrchestratorHolder(),
                        preferenceStore,
                        env.sessionRepository);
        return new TestFixture(
                service, preferenceStore, "MEMORY:chat:parent:user", "MEMORY:chat:child:user");
    }

    /** 调用私有工具交集方法并返回有效工具。 */
    @SuppressWarnings("unchecked")
    private List<String> applyAllowedTools(
            DefaultDelegationService service,
            String parentSourceKey,
            String childSourceKey,
            List<String> requested)
            throws Exception {
        Method method =
                DefaultDelegationService.class.getDeclaredMethod(
                        "applyAllowedTools", String.class, String.class, List.class, List.class);
        method.setAccessible(true);
        return (List<String>)
                method.invoke(
                        service,
                        parentSourceKey,
                        childSourceKey,
                        requested,
                        Collections.emptyList());
    }

    /** 在指定父运行上下文中执行测试动作，并恢复调用线程原上下文。 */
    private <T> T withCurrentContext(AgentRunContext context, CheckedAction<T> action)
            throws Exception {
        AgentRunContext previous = AgentRunContext.current();
        AgentRunContext.setCurrent(context);
        try {
            return action.run();
        } finally {
            AgentRunContext.setCurrent(previous);
        }
    }

    /** 可抛受检异常的测试动作。 */
    private interface CheckedAction<T> {
        /** 执行动作。 */
        T run() throws Exception;
    }

    /** 委派工具边界测试夹具。 */
    private static final class TestFixture {
        /** 待测委派服务。 */
        private final DefaultDelegationService service;

        /** 当前 Profile 的工具偏好仓储。 */
        private final SqlitePreferenceStore preferenceStore;

        /** 父来源键。 */
        private final String parentSourceKey;

        /** 子来源键。 */
        private final String childSourceKey;

        /** 创建测试夹具。 */
        private TestFixture(
                DefaultDelegationService service,
                SqlitePreferenceStore preferenceStore,
                String parentSourceKey,
                String childSourceKey) {
            this.service = service;
            this.preferenceStore = preferenceStore;
            this.parentSourceKey = parentSourceKey;
            this.childSourceKey = childSourceKey;
        }
    }
}
