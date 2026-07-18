package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.DangerousCommandApprovalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.ReActToolObservationSupport;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalDecision;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalJudge;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;

public class DangerousCommandGatewayApprovalTest {
    @AfterEach
    void clearThreadPolicyApprovals() {
        DangerousCommandApprovalTestSupport.clearThreadPolicyApprovals();
    }

    @Test
    void shouldRequestWholeScriptApprovalForSafeExecuteCode() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "print('hello')");
        TestTrace trace = new TestTrace();

        service.buildInterceptor().onAction(trace, exchange("execute_code", args));

        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);
        assertThat(trace.getFinalAnswer()).contains("需要审批");
        assertThat(pending).isNotNull();
        assertThat(pending.getToolName()).isEqualTo("execute_code");
        assertThat(pending.getPatternKey()).isEqualTo("execute_code");
    }

    @Test
    void shouldHardBlockExecuteCodeContentBeforeWholeScriptApproval() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put(
                "code",
                "import os\n"
                        + "os.system('rm -rf /')\n"
                        + "print('http://169.254.169.254/latest/meta-data/')");
        TestTrace trace = new TestTrace();

        service.buildInterceptor().onAction(trace, exchange("execute_code", args));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer())
                .contains("BLOCKED (hardline)")
                .contains("169.254.169.254");
        assertThat(service.getPendingApproval(trace.session)).isNull();
    }

    @Test
    void shouldInspectNestedGatewayCommandArguments() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));

        Map<String, Object> nestedTerminalPayload = new LinkedHashMap<String, Object>();
        nestedTerminalPayload.put("command", "git reset --hard");
        Map<String, Object> nestedTerminalArgs = new LinkedHashMap<String, Object>();
        nestedTerminalArgs.put("payload", nestedTerminalPayload);
        Map<String, Object> nestedTerminalCall = new LinkedHashMap<String, Object>();
        nestedTerminalCall.put("tool_name", "terminal");
        nestedTerminalCall.put("tool_args", nestedTerminalArgs);
        TestTrace nestedTerminalTrace = new TestTrace();

        service.buildInterceptor()
                .onAction(nestedTerminalTrace, exchange("call_tool", nestedTerminalCall));

        DangerousCommandApprovalService.PendingApproval terminalPending =
                service.getPendingApproval(nestedTerminalTrace.session);
        assertThat(terminalPending).isNotNull();
        assertThat(terminalPending.getToolName()).isEqualTo("terminal");
        assertThat(terminalPending.getPatternKey()).isEqualTo("git_reset_hard");

        Map<String, Object> nestedShellInput = new LinkedHashMap<String, Object>();
        nestedShellInput.put("code", "docker system prune -af");
        Map<String, Object> nestedShellArgs = new LinkedHashMap<String, Object>();
        nestedShellArgs.put("input", nestedShellInput);
        Map<String, Object> nestedShellCall = new LinkedHashMap<String, Object>();
        nestedShellCall.put("tool_name", "execute_shell");
        nestedShellCall.put("tool_args", nestedShellArgs);
        TestTrace nestedShellTrace = new TestTrace();

        service.buildInterceptor()
                .onAction(nestedShellTrace, exchange("call_tool", nestedShellCall));

        DangerousCommandApprovalService.PendingApproval shellPending =
                service.getPendingApproval(nestedShellTrace.session);
        assertThat(shellPending).isNotNull();
        assertThat(shellPending.getToolName()).isEqualTo("execute_shell");
        assertThat(shellPending.getPatternKey()).isEqualTo("docker_destructive_prune");

        Map<String, Object> commandArrayArgs = new LinkedHashMap<String, Object>();
        commandArrayArgs.put(
                "commands", Arrays.asList("echo ready", "terraform destroy -auto-approve"));
        Map<String, Object> commandArrayCall = new LinkedHashMap<String, Object>();
        commandArrayCall.put("tool_name", "execute_shell");
        commandArrayCall.put("tool_args", commandArrayArgs);
        TestTrace commandArrayTrace = new TestTrace();

        service.buildInterceptor()
                .onAction(commandArrayTrace, exchange("call_tool", commandArrayCall));

        DangerousCommandApprovalService.PendingApproval commandArrayPending =
                service.getPendingApproval(commandArrayTrace.session);
        assertThat(commandArrayPending).isNotNull();
        assertThat(commandArrayPending.getToolName()).isEqualTo("execute_shell");
        assertThat(commandArrayPending.getPatternKey()).isEqualTo("terraform_destroy");

        Map<String, Object> nestedArrayItem = new LinkedHashMap<String, Object>();
        nestedArrayItem.put("cmd", "docker system prune -af");
        Map<String, Object> nestedCommandArrayArgs = new LinkedHashMap<String, Object>();
        nestedCommandArrayArgs.put("commands", new Object[] {"echo ready", nestedArrayItem});
        Map<String, Object> nestedCommandArrayCall = new LinkedHashMap<String, Object>();
        nestedCommandArrayCall.put("tool_name", "execute_shell");
        nestedCommandArrayCall.put("tool_args", nestedCommandArrayArgs);
        TestTrace nestedCommandArrayTrace = new TestTrace();

        service.buildInterceptor()
                .onAction(nestedCommandArrayTrace, exchange("call_tool", nestedCommandArrayCall));

        DangerousCommandApprovalService.PendingApproval nestedCommandArrayPending =
                service.getPendingApproval(nestedCommandArrayTrace.session);
        assertThat(nestedCommandArrayPending).isNotNull();
        assertThat(nestedCommandArrayPending.getToolName()).isEqualTo("execute_shell");
        assertThat(nestedCommandArrayPending.getPatternKey()).isEqualTo("docker_destructive_prune");

        Map<String, Object> safeNestedArgs = new LinkedHashMap<String, Object>();
        safeNestedArgs.put("note", "git reset --hard appears in docs, not as a command key");
        Map<String, Object> safeNestedCall = new LinkedHashMap<String, Object>();
        safeNestedCall.put("tool_name", "terminal");
        safeNestedCall.put("tool_args", safeNestedArgs);
        TestTrace safeTrace = new TestTrace();

        service.buildInterceptor().onAction(safeTrace, exchange("call_tool", safeNestedCall));

        assertThat(service.getPendingApproval(safeTrace.session)).isNull();
        assertThat(safeTrace.getFinalAnswer()).isNull();
    }

    @Test
    void shouldRejectMalformedGatewayToolArgsAtApprovalBoundary() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        Map<String, Object> malformedArgs = new LinkedHashMap<String, Object>();
        malformedArgs.put("tool_name", "webfetch");
        malformedArgs.put(
                "tool_args",
                "{\"url\":\"http://169.254.169.254/latest/meta-data/?api%255Fkey=secret123\"");
        TestTrace malformedTrace = new TestTrace();

        org.noear.solon.ai.agent.react.task.ToolExchanger malformedExchange =
                exchange("call_tool", malformedArgs);
        service.buildInterceptor().onAction(malformedTrace, malformedExchange);

        assertThat(malformedTrace.getRoute()).isNull();
        assertThat(malformedTrace.getFinalAnswer()).isNull();
        assertThat(service.getPendingApproval(malformedTrace.session)).isNull();
        assertThat(ReActToolObservationSupport.get(malformedTrace, malformedExchange))
                .contains("call_tool.tool_args")
                .contains("JSON 对象");

        Map<String, Object> arrayArgs = new LinkedHashMap<String, Object>();
        arrayArgs.put("tool_name", "terminal");
        arrayArgs.put("tool_args", "[]");
        TestTrace arrayTrace = new TestTrace();

        org.noear.solon.ai.agent.react.task.ToolExchanger arrayExchange =
                exchange("call_tool", arrayArgs);
        service.buildInterceptor().onAction(arrayTrace, arrayExchange);

        assertThat(arrayTrace.getRoute()).isNull();
        assertThat(arrayTrace.getFinalAnswer()).isNull();
        assertThat(service.getPendingApproval(arrayTrace.session)).isNull();
        assertThat(ReActToolObservationSupport.get(arrayTrace, arrayExchange))
                .contains("call_tool.tool_args")
                .contains("JSON 对象");

        Map<String, Object> missingArgs = new LinkedHashMap<String, Object>();
        missingArgs.put("tool_name", "terminal");
        TestTrace missingTrace = new TestTrace();
        org.noear.solon.ai.agent.react.task.ToolExchanger missingExchange =
                exchange("call_tool", missingArgs);

        service.buildInterceptor().onAction(missingTrace, missingExchange);

        assertThat(ReActToolObservationSupport.get(missingTrace, missingExchange))
                .contains("call_tool.tool_args")
                .contains("JSON 对象");
        assertThat(service.getPendingApproval(missingTrace.session)).isNull();
    }

    @Test
    void shouldResumeApprovedGatewayTerminalCommandWithoutDirectPreflightRescan() throws Exception {
        TestEnvironment env = approvalEnvironment();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository, env.appConfig, policy);
        SolonClawShellSkill shell =
                new SolonClawShellSkill(
                        env.appConfig.getRuntime().getHome(), env.appConfig, policy);
        Map<String, Object> toolArgs = new LinkedHashMap<String, Object>();
        toolArgs.put("command", "git reset --hard");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("tool_name", "terminal");
        args.put("tool_args", toolArgs);

        TestTrace trace = new TestTrace();
        service.buildInterceptor().onAction(trace, exchange("call_tool", args));
        assertThat(service.getPendingApproval(trace.session).getToolName()).isEqualTo("terminal");
        assertThat(
                        service.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();

        TestTrace resumed = new TestTrace(trace.session);
        service.buildInterceptor().onAction(resumed, exchange("call_tool", args));

        assertThat(resumed.getFinalAnswer()).isNull();
        Object lastIntervened =
                resumed.getContext()
                        .getAs(org.noear.solon.ai.agent.react.intercept.HITL.LAST_INTERVENED);
        assertThat(lastIntervened).isNull();
        ONode allowed =
                ONode.ofJson(
                        shell.terminal(
                                "git reset --hard",
                                Boolean.FALSE,
                                Integer.valueOf(1),
                                null,
                                Boolean.FALSE));
        assertThat(allowed.toJson()).doesNotContain("危险命令安全规则");
    }

    @Test
    void shouldResumeApprovedGatewayManagedProcessWithoutDirectPreflightRescan() throws Exception {
        TestEnvironment env = approvalEnvironment();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository, env.appConfig, policy);
        ProcessRegistry registry = new ProcessRegistry(env.appConfig);
        SolonClawShellSkill shell =
                new SolonClawShellSkill(
                        env.appConfig.getRuntime().getHome(), env.appConfig, policy, registry);
        Map<String, Object> toolArgs = new LinkedHashMap<String, Object>();
        toolArgs.put("command", "git reset --hard");
        toolArgs.put("background", Boolean.TRUE);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("tool_name", "terminal");
        args.put("tool_args", toolArgs);

        TestTrace trace = new TestTrace();
        service.buildInterceptor().onAction(trace, exchange("call_tool", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);
        assertThat(pending).isNotNull();
        assertThat(pending.getToolName()).isEqualTo("terminal");
        assertThat(
                        service.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();

        TestTrace resumed = new TestTrace(trace.session);
        service.buildInterceptor().onAction(resumed, exchange("call_tool", args));

        assertThat(resumed.getFinalAnswer()).isNull();
        Object lastIntervened =
                resumed.getContext()
                        .getAs(org.noear.solon.ai.agent.react.intercept.HITL.LAST_INTERVENED);
        assertThat(lastIntervened).isNull();
        ONode allowed =
                ONode.ofJson(
                        shell.terminal(
                                "git reset --hard",
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                null,
                                Boolean.FALSE));
        assertThat(allowed.toJson()).doesNotContain("危险命令安全规则");
        assertToolSuccess(allowed);
        assertThat(allowed.get("background").getBoolean()).isTrue();
        String sessionId = allowed.get("session_id").getString();
        assertThat(sessionId).isNotBlank();
        registry.stop(sessionId);
    }

    @Test
    void shouldPromptForTirithWarningEvenWhenFindingsAreEmptyWithCanonicalConfig()
            throws Exception {
        TestEnvironment env = approvalEnvironment();
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.<TirithSecurityService.Finding>emptyList(),
                                "generic warning"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "echo hello");

        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);

        assertThat(trace.getFinalAnswer()).contains("Security scan").contains("generic warning");
        assertThat(pending).isNotNull();
        assertThat(pending.getPatternKey()).isEqualTo("tirith:security_scan");
        assertThat(pending.getPatternKeys()).containsExactly("tirith:security_scan");
        assertThat(pending.isPermanentApprovalAllowed()).isFalse();
    }

    @Test
    void shouldHidePermanentApprovalCardChoiceForTirithFindings() throws Exception {
        TestEnvironment env = approvalEnvironment();
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding("shortened_url", "MEDIUM", "Short URL", "")),
                                "shortened URL"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "echo hello");

        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);
        Map<String, Object> extras = service.buildDeliveryExtras(PlatformType.FEISHU, pending);
        Map<String, Object> dingtalkExtras =
                service.buildDeliveryExtras(PlatformType.DINGTALK, pending);

        assertThat(pending).isNotNull();
        assertThat(pending.isPermanentApprovalAllowed()).isFalse();
        assertThat(extras.get("approvalAllowAlways")).isEqualTo(Boolean.FALSE);
        assertThat(dingtalkExtras).containsAllEntriesOf(extras);
        assertThat(trace.getFinalAnswer()).contains("不能永久记住");
        assertThat(trace.getFinalAnswer()).contains("/approve session");
        assertThat(trace.getFinalAnswer()).doesNotContain("/approve always");
    }

    @Test
    void shouldTreatAlwaysApprovalForTirithAsSessionOnly() throws Exception {
        TestEnvironment env = approvalEnvironment();
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding("shortened_url", "MEDIUM", "Short URL", "")),
                                "shortened URL"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "echo hello");
        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));

        boolean approved =
                service.approve(
                        trace.session,
                        DangerousCommandApprovalService.ApprovalScope.ALWAYS,
                        "test");

        assertThat(approved).isTrue();
        assertThat(service.isSessionApproved(trace.session, "tirith:shortened_url")).isTrue();
        assertThat(service.isAlwaysApproved("tirith:shortened_url")).isFalse();
    }

    @Test
    void shouldEscalateSmartApprovalWhenJudgeDoesNotApprove() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("smart");
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        null);
        service.setSmartApprovalJudge(
                new SmartApprovalJudge() {
                    @Override
                    public SmartApprovalDecision judge(
                            String toolName, String command, String description) {
                        return SmartApprovalDecision.escalate(
                                "needs user token=smart-escalate-secret");
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "rm -rf workspace/cache");

        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));

        assertThat(service.getPendingApproval(trace.session)).isNotNull();
        assertThat(trace.getFinalAnswer())
                .contains("危险命令需要审批")
                .doesNotContain("smart-escalate-secret");
        assertThat(service.isSessionApproved(trace.session, "recursive_delete")).isFalse();
    }

    @Test
    void shouldBlockWhenSmartApprovalDeniesWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("smart");
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        null);
        service.setSmartApprovalJudge(
                new SmartApprovalJudge() {
                    @Override
                    public SmartApprovalDecision judge(
                            String toolName, String command, String description) {
                        return SmartApprovalDecision.deny(
                                "destructive cleanup token=smart-deny-secret");
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "rm -rf workspace/cache");

        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer()).contains("BLOCKED").doesNotContain("smart-deny-secret");
        assertThat(service.getPendingApproval(trace.session)).isNull();
        assertThat(service.isSessionApproved(trace.session, "recursive_delete")).isFalse();
    }

    @Test
    void shouldBypassNonHardlineDangerousCommandWhenSessionAutoApprovalIsEnabled()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "rm -rf workspace/cache");

        boolean enabled =
                env.dangerousCommandApprovalService.enableSessionAutoApproval(trace.session);
        env.dangerousCommandApprovalService
                .buildInterceptor()
                .onAction(trace, exchange("execute_shell", args));

        assertThat(enabled).isTrue();
        assertThat(env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(trace.session))
                .isTrue();
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(trace.session)).isNull();
        assertThat(trace.getFinalAnswer()).isNull();
    }

    @Test
    void shouldBlockHardlineThroughInterceptorWhenSessionAutoApprovalIsEnabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "sudo reboot");

        boolean enabled =
                env.dangerousCommandApprovalService.enableSessionAutoApproval(trace.session);
        env.dangerousCommandApprovalService
                .buildInterceptor()
                .onAction(trace, exchange("execute_shell", args));

        assertThat(enabled).isTrue();
        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer()).contains("BLOCKED (hardline)").contains("shutdown");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(trace.session)).isNull();
    }

    @Test
    void shouldSmartApproveTirithFindingsLikeCombinedSafetyJudge() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("smart");
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding(
                                                "terminal_injection",
                                                "HIGH",
                                                "Terminal injection",
                                                "")),
                                "terminal injection"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        service.setSmartApprovalJudge(
                new SmartApprovalJudge() {
                    @Override
                    public SmartApprovalDecision judge(
                            String toolName, String command, String description) {
                        return SmartApprovalDecision.approve("low risk");
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "echo hello");

        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));

        assertThat(service.getPendingApproval(trace.session)).isNull();
        assertThat(trace.getFinalAnswer()).isNull();
        assertThat(service.isSessionApproved(trace.session, "tirith:terminal_injection")).isTrue();
        assertThat(service.isAlwaysApproved("tirith:terminal_injection")).isFalse();
        assertThat(
                        DangerousCommandApprovalService.consumeCurrentThreadApproval(
                                "execute_shell", "echo hello"))
                .isTrue();
    }

    @Test
    void shouldKeepHardlineBlockedWhenGuardrailModeIsBypassAndTirithWarns() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("bypass");
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding(
                                                "terminal_injection",
                                                "HIGH",
                                                "Terminal injection",
                                                "")),
                                "terminal injection"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        DangerousCommandApprovalService.DetectionResult hardline =
                service.detectHardline("execute_shell", "sudo reboot");

        assertThat(service.guardrailMode()).isEqualTo("bypass");
        assertThat(hardline).isNotNull();
        assertThat(hardline.isHardline()).isTrue();
        assertThat(hardline.getDescription()).contains("shutdown");
    }

    @Test
    void shouldSkipTirithScanWhenGuardrailModeIsBypass() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("bypass");
        CountingTirithSecurityService tirith =
                new CountingTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding(
                                                "terminal_injection",
                                                "HIGH",
                                                "Terminal injection",
                                                "")),
                                "terminal injection"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "rm -rf workspace/cache");

        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));

        assertThat(service.guardrailMode()).isEqualTo("bypass");
        assertThat(tirith.getCalls()).isEqualTo(0);
        assertThat(service.getPendingApproval(trace.session)).isNull();
        assertThat(trace.getFinalAnswer()).isNull();
    }

    @Test
    void shouldBlockHardlineThroughInterceptorWhenGuardrailModeIsBypass() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("bypass");
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "sudo reboot");

        env.dangerousCommandApprovalService
                .buildInterceptor()
                .onAction(trace, exchange("execute_shell", args));

        assertThat(env.dangerousCommandApprovalService.guardrailMode()).isEqualTo("bypass");
        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer()).contains("BLOCKED (hardline)").contains("shutdown");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(trace.session)).isNull();
    }

    @Test
    void shouldBlockUpstreamHardlineSamplesBeforeApprovalBypasses() throws Exception {
        TestEnvironment bypassEnv = TestEnvironment.withFakeLlm();
        bypassEnv.appConfig.getSecurity().setGuardrailMode("bypass");
        assertHardlineBlocked(bypassEnv.dangerousCommandApprovalService, "shutdown -r now");

        TestEnvironment sessionAutoApprovalEnv = TestEnvironment.withFakeLlm();
        TestTrace sessionAutoApprovalTrace = new TestTrace();
        assertThat(
                        sessionAutoApprovalEnv.dangerousCommandApprovalService
                                .enableSessionAutoApproval(sessionAutoApprovalTrace.session))
                .isTrue();
        assertHardlineBlocked(
                sessionAutoApprovalEnv.dangerousCommandApprovalService,
                sessionAutoApprovalTrace,
                "sudo reboot");
    }

    @Test
    void shouldBlockJimuquHardlineCommandSamples() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String[] commands =
                withCommonHardlineShutdownCommands(
                        "rm -rf /",
                        "rm -rf /*",
                        "rm -rf /home",
                        "rm -rf /home/*",
                        "rm -rf /etc",
                        "rm -rf /usr",
                        "rm -rf /var",
                        "rm -rf /boot",
                        "rm -rf /bin",
                        "rm --recursive --force /",
                        "rm -fr /",
                        "sudo rm -rf /",
                        "sudo -E rm -rf /etc",
                        "env FOO=1 rm -rf /usr",
                        "exec rm -rf /var",
                        "nohup rm -rf /boot",
                        "setsid rm -rf /bin",
                        "time rm -rf /sbin",
                        "rm -rf ~",
                        "rm -rf ~/",
                        "rm -rf ~/*",
                        "rm -rf $HOME",
                        "mkfs.ext4 /dev/sda1",
                        "mkfs /dev/sdb",
                        "mkfs.xfs /dev/nvme0n1",
                        "dd if=/dev/zero of=/dev/sda bs=1M",
                        "dd if=/dev/urandom of=/dev/nvme0n1",
                        "dd if=anything of=/dev/hda",
                        "echo bad > /dev/sda",
                        "cat /dev/urandom > /dev/sdb");

        for (String command : commands) {
            DangerousCommandApprovalService.DetectionResult result =
                    env.dangerousCommandApprovalService.detectHardline("execute_shell", command);

            assertThat(result)
                    .withFailMessage("expected hardline block for command: %s", command)
                    .isNotNull();
            assertThat(result.isHardline()).isTrue();
            assertThat(result.getDescription()).isNotBlank();
        }
    }

    @Test
    void shouldAllowJimuquHardlineNegativeSamples() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String[] commands =
                new String[] {
                    "rm -rf /tmp/foo",
                    "rm -rf /tmp/*",
                    "rm -rf ./build",
                    "rm -rf node_modules",
                    "rm -rf /home/user/scratch",
                    "rm -rf ~/Downloads/old",
                    "rm -rf $HOME/tmp",
                    "rm foo.txt",
                    "rm -rf some/path",
                    "dd if=/dev/zero of=./image.bin",
                    "dd if=./data of=./backup.bin",
                    "wipefs -n /dev/sda",
                    "sgdisk --print /dev/sda",
                    "parted /dev/sda print",
                    "echo done > /tmp/flag",
                    "echo test > /dev/null",
                    "ls /dev/sda",
                    "cat /dev/urandom | head -c 10",
                    "grep 'shutdown' logs.txt",
                    "echo reboot",
                    "echo '# init 0 in comment'",
                    "cat rebooting.log",
                    "echo 'halt and catch fire'",
                    "python3 -c 'print(\"shutdown\")'",
                    "find . -name '*reboot*'",
                    "mkfs_helper --version",
                    "systemctl status nginx",
                    "systemctl restart nginx",
                    "systemctl stop nginx",
                    "systemctl start nginx",
                    "kill -9 12345",
                    "kill -HUP 1234",
                    "pkill python",
                    "git status",
                    "npm run build",
                    "sudo apt update",
                    "curl https://example.com | head"
                };

        for (String command : commands) {
            DangerousCommandApprovalService.DetectionResult result =
                    env.dangerousCommandApprovalService.detectHardline("execute_shell", command);

            assertThat(result)
                    .withFailMessage("expected hardline allow for command: %s", command)
                    .isNull();
        }
    }
}
