package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.ReActToolObservationSupport;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.core.Props;

public class ToolCallLoopGuardrailServiceTest {
    @TempDir File tempDir;

    @Test
    void shouldWarnRepeatedExactFailureWithoutBlockingByDefault() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopExactFailureWarnAfter(2);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();
        Map<String, Object> args = args("query", "same");

        runFailedCall(interceptor, trace, "websearch", args);
        runFailedCall(interceptor, trace, "websearch", args);

        assertThat(observation(trace)).contains("工具循环提醒");
        assertThat(observation(trace)).contains("repeated_exact_failure_warning");
        assertThat(trace.getRoute()).isNotEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer()).isNull();
    }

    @Test
    void shouldHardStopRepeatedExactFailureBeforeExecutingNextCallWhenEnabled() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopHardStopEnabled(true);
        config.getReact().setToolLoopExactFailureWarnAfter(2);
        config.getReact().setToolLoopExactFailureBlockAfter(2);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();
        Map<String, Object> args = args("query", "same");

        runFailedCall(interceptor, trace, "websearch", args);
        runFailedCall(interceptor, trace, "websearch", args);
        interceptor.onAction(trace, exchange("websearch", args));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer()).contains("已停止重复工具调用");
        assertThat(observation(trace)).contains("repeated_exact_failure_block");
        Object haltDecision = trace.getExtra(ToolCallLoopGuardrailService.HALT_DECISION_EXTRA_KEY);
        assertThat(haltDecision).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> haltMetadata = (Map<String, Object>) haltDecision;
        assertThat(haltMetadata)
                .containsEntry("action", "block")
                .containsEntry("code", "repeated_exact_failure_block")
                .containsEntry("tool_name", "websearch")
                .containsKey("message");
        assertThat(String.valueOf(haltMetadata.get("message"))).contains("相同参数");
    }

    @Test
    void shouldWarnSameToolVaryingArgsFailuresWithoutBlockingByDefault() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopExactFailureWarnAfter(10);
        config.getReact().setToolLoopSameToolFailureWarnAfter(3);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();

        runFailedCall(interceptor, trace, "terminal", args("command", "git status --short"));
        runFailedCall(interceptor, trace, "terminal", args("command", "git status --branch"));
        runFailedCall(interceptor, trace, "terminal", args("command", "git status --porcelain"));

        assertThat(observation(trace)).contains("工具循环提醒");
        assertThat(observation(trace)).contains("same_tool_failure_warning");
        assertThat(trace.getRoute()).isNotEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer()).isNull();
    }

    @Test
    void shouldHaltSameToolVaryingArgsFailuresWhenHardStopEnabled() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopHardStopEnabled(true);
        config.getReact().setToolLoopExactFailureWarnAfter(10);
        config.getReact().setToolLoopExactFailureBlockAfter(10);
        config.getReact().setToolLoopSameToolFailureWarnAfter(10);
        config.getReact().setToolLoopSameToolFailureHaltAfter(3);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();

        runFailedCall(interceptor, trace, "terminal", args("command", "git status --short"));
        runFailedCall(interceptor, trace, "terminal", args("command", "git status --branch"));
        runFailedCall(interceptor, trace, "terminal", args("command", "git status --porcelain"));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer()).contains("已停止重复工具调用");
        assertThat(observation(trace)).contains("same_tool_failure_halt");
        Object haltDecision = trace.getExtra(ToolCallLoopGuardrailService.HALT_DECISION_EXTRA_KEY);
        assertThat(haltDecision).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> haltMetadata = (Map<String, Object>) haltDecision;
        assertThat(haltMetadata)
                .containsEntry("action", "halt")
                .containsEntry("code", "same_tool_failure_halt")
                .containsEntry("tool_name", "terminal")
                .containsEntry("count", Integer.valueOf(3));
    }

    @Test
    void shouldHashCanonicalNestedArgsWithoutLeakingRawArgsInGuardrailMetadata() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopHardStopEnabled(true);
        config.getReact().setToolLoopExactFailureWarnAfter(2);
        config.getReact().setToolLoopExactFailureBlockAfter(2);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();
        Map<String, Object> firstArgs = nestedArgs(false);
        Map<String, Object> reorderedArgs = nestedArgs(true);

        runFailedCall(interceptor, trace, "websearch", firstArgs);
        runFailedCall(interceptor, trace, "websearch", reorderedArgs);
        interceptor.onAction(trace, exchange("websearch", reorderedArgs));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(observation(trace))
                .contains("args_hash")
                .contains("repeated_exact_failure_block");
        assertThat(observation(trace)).contains("\"message\"");
        assertThat(observation(trace))
                .doesNotContain("secret-token-value")
                .doesNotContain("☤")
                .doesNotContain("β");
        assertThat(trace.getFinalAnswer())
                .doesNotContain("secret-token-value")
                .doesNotContain("☤")
                .doesNotContain("β");
    }

    @Test
    void shouldWarnIdempotentNoProgressForSameReadOnlyResult() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopNoProgressWarnAfter(2);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();
        Map<String, Object> args = args("path", "README.md");

        runSuccessfulCall(
                interceptor,
                trace,
                "read_file",
                args,
                "{\"status\":\"success\",\"content\":\"same\"}");
        runSuccessfulCall(
                interceptor,
                trace,
                "read_file",
                args,
                "{\"content\":\"same\",\"status\":\"success\"}");

        assertThat(observation(trace)).contains("工具循环提醒");
        assertThat(observation(trace)).contains("idempotent_no_progress_warning");
    }

    @Test
    void shouldBlockIdempotentNoProgressBeforeNextCallWhenHardStopEnabled() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopHardStopEnabled(true);
        config.getReact().setToolLoopNoProgressWarnAfter(2);
        config.getReact().setToolLoopNoProgressBlockAfter(2);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();
        Map<String, Object> args = args("path", "README.md");

        runSuccessfulCall(
                interceptor,
                trace,
                "read_file",
                args,
                "{\"status\":\"success\",\"content\":\"same\"}");
        runSuccessfulCall(
                interceptor,
                trace,
                "read_file",
                args,
                "{\"content\":\"same\",\"status\":\"success\"}");
        interceptor.onAction(trace, exchange("read_file", args));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer()).contains("已停止重复工具调用");
        assertThat(observation(trace)).contains("idempotent_no_progress_block");
        Object haltDecision = trace.getExtra(ToolCallLoopGuardrailService.HALT_DECISION_EXTRA_KEY);
        assertThat(haltDecision).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> haltMetadata = (Map<String, Object>) haltDecision;
        assertThat(haltMetadata)
                .containsEntry("action", "block")
                .containsEntry("code", "idempotent_no_progress_block")
                .containsEntry("tool_name", "read_file")
                .containsEntry("count", Integer.valueOf(2));
    }

    @Test
    void shouldNotTreatMutatingSuccessAsNoProgressLoop() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopNoProgressWarnAfter(2);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();
        Map<String, Object> args = args("path", "out.txt");

        runSuccessfulCall(interceptor, trace, "write_file", args, "{\"status\":\"success\"}");
        runSuccessfulCall(interceptor, trace, "write_file", args, "{\"status\":\"success\"}");

        assertThat(observation(trace)).doesNotContain("工具循环提醒");
    }

    @Test
    void shouldClassifyExternalBrowserToolNamesLikeJimuquGuardrail() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopNoProgressWarnAfter(2);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();

        runSuccessfulCall(
                interceptor,
                trace,
                "browser_snapshot",
                args("ref", "page"),
                "{\"status\":\"success\",\"snapshot\":\"same\"}");
        runSuccessfulCall(
                interceptor,
                trace,
                "browser_snapshot",
                args("ref", "page"),
                "{\"snapshot\":\"same\",\"status\":\"success\"}");
        assertThat(observation(trace)).contains("idempotent_no_progress_warning");

        ReActTrace mutatingTrace = newTrace();
        runSuccessfulCall(
                interceptor,
                mutatingTrace,
                "browser_click",
                args("ref", "button"),
                "{\"status\":\"success\"}");
        runSuccessfulCall(
                interceptor,
                mutatingTrace,
                "browser_click",
                args("ref", "button"),
                "{\"status\":\"success\"}");
        assertThat(observation(mutatingTrace)).doesNotContain("工具循环提醒");
    }

    @Test
    void shouldResetExactFailureCountAfterSuccessfulCall() {
        AppConfig config = new AppConfig();
        config.getReact().setToolLoopExactFailureWarnAfter(2);
        ReActInterceptor interceptor = new ToolCallLoopGuardrailService(config).buildInterceptor();
        ReActTrace trace = newTrace();
        Map<String, Object> args = args("query", "same");

        runFailedCall(interceptor, trace, "websearch", args);
        runSuccessfulCall(interceptor, trace, "websearch", args, "{\"status\":\"success\"}");
        runFailedCall(interceptor, trace, "websearch", args);

        assertThat(observation(trace)).doesNotContain("repeated_exact_failure_warning");
    }

    @Test
    void shouldClearOtherToolCallEpochWhenAgentEnds() {
        ReActInterceptor interceptor =
                new ToolCallLoopGuardrailService(new AppConfig()).buildInterceptor();

        ToolCallLoopGuardrailService.notifyFileReadDedupIfOtherTool("terminal");
        assertThat(ToolCallLoopGuardrailService.otherToolCallEpoch()).isGreaterThan(0);

        interceptor.onAgentEnd(newTrace());

        assertThat(ToolCallLoopGuardrailService.otherToolCallEpoch()).isZero();
    }

    @Test
    void shouldLoadSolonClawReactToolLoopGuardrailConfigKeys() {
        File workspaceHome = FileUtil.file(tempDir, "runtime");
        FileUtil.mkdir(workspaceHome);
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  react:\n"
                        + "    toolLoopWarningsEnabled: false\n"
                        + "    toolLoopHardStopEnabled: true\n"
                        + "    toolLoopExactFailureWarnAfter: 4\n"
                        + "    toolLoopSameToolFailureWarnAfter: 5\n"
                        + "    toolLoopNoProgressWarnAfter: 6\n"
                        + "    toolLoopExactFailureBlockAfter: 7\n"
                        + "    toolLoopSameToolFailureHaltAfter: 8\n"
                        + "    toolLoopNoProgressBlockAfter: 9\n",
                FileUtil.file(workspaceHome, "config.yml"));
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getReact().isToolLoopWarningsEnabled()).isFalse();
        assertThat(config.getReact().isToolLoopHardStopEnabled()).isTrue();
        assertThat(config.getReact().getToolLoopExactFailureWarnAfter()).isEqualTo(4);
        assertThat(config.getReact().getToolLoopSameToolFailureWarnAfter()).isEqualTo(5);
        assertThat(config.getReact().getToolLoopNoProgressWarnAfter()).isEqualTo(6);
        assertThat(config.getReact().getToolLoopExactFailureBlockAfter()).isEqualTo(7);
        assertThat(config.getReact().getToolLoopSameToolFailureHaltAfter()).isEqualTo(8);
        assertThat(config.getReact().getToolLoopNoProgressBlockAfter()).isEqualTo(9);
    }

    private void runFailedCall(
            ReActInterceptor interceptor,
            ReActTrace trace,
            String toolName,
            Map<String, Object> args) {
        runSuccessfulCall(
                interceptor, trace, toolName, args, "{\"status\":\"error\",\"error\":\"boom\"}");
    }

    private void runSuccessfulCall(
            ReActInterceptor interceptor,
            ReActTrace trace,
            String toolName,
            Map<String, Object> args,
            String result) {
        ToolExchanger exchanger = exchange(toolName, args);
        interceptor.onAction(trace, exchanger);
        ReActToolObservationSupport.set(trace, exchanger, result);
        interceptor.onObservation(trace, exchanger, null, null, 3L);
    }

    private Map<String, Object> args(String key, Object value) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put(key, value);
        return args;
    }

    private Map<String, Object> nestedArgs(boolean reordered) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        if (reordered) {
            inner.put("x", "secret-token-value");
            inner.put("y", Integer.valueOf(2));
            item.put("a", Integer.valueOf(1));
            item.put("β", "☤");
            root.put("a", inner);
            root.put("z", Arrays.<Object>asList(item));
        } else {
            item.put("β", "☤");
            item.put("a", Integer.valueOf(1));
            inner.put("y", Integer.valueOf(2));
            inner.put("x", "secret-token-value");
            root.put("z", Arrays.<Object>asList(item));
            root.put("a", inner);
        }
        return root;
    }

    private ReActTrace newTrace() {
        return new ReActTrace() {
            @Override
            public void setRoute(String route) {
                getExtras().put("test.route", route);
            }

            @Override
            public String getRoute() {
                Object route = getExtras().get("test.route");
                return route == null ? null : String.valueOf(route);
            }
        };
    }

    private ToolExchanger exchange(String toolName, Map<String, Object> args) {
        return new ToolExchanger(toolName, args);
    }

    private String observation(ReActTrace trace) {
        return ReActToolObservationSupport.get(trace, null);
    }
}
