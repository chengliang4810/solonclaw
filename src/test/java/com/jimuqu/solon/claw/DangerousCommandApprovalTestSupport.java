package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

/** 复用危险命令审批测试中的通用断言、探针桩和 ReAct trace 桩。 */
final class DangerousCommandApprovalTestSupport {
    private DangerousCommandApprovalTestSupport() {}

    static TirithSecurityService.ScanResult scanResult(
            String action, List<TirithSecurityService.Finding> findings, String summary)
            throws Exception {
        java.lang.reflect.Constructor<TirithSecurityService.ScanResult> constructor =
                TirithSecurityService.ScanResult.class.getDeclaredConstructor(
                        String.class, List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(action, findings, summary);
    }

    static TirithSecurityService.Finding finding(
            String ruleId, String severity, String title, String description) throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("rule_id", ruleId);
        values.put("severity", severity);
        values.put("title", title);
        values.put("description", description);
        return TirithSecurityService.Finding.from(values);
    }

    static void assertHardlineBlocked(
            DangerousCommandApprovalService service, String command) {
        assertHardlineBlocked(service, new TestTrace(), command);
    }

    static void assertDangerPattern(TestEnvironment env, String command, String patternKey) {
        DangerousCommandApprovalService.DetectionResult result =
                env.dangerousCommandApprovalService.detect("execute_shell", command);
        assertThat(result)
                .withFailMessage("expected danger detection for command: %s", command)
                .isNotNull();
        assertThat(result.getPatternKey()).isEqualTo(patternKey);
    }

    static void assertDockerLifecyclePattern(
            TestEnvironment env, String command, String patternKey) {
        DangerousCommandApprovalService.DetectionResult result =
                env.dangerousCommandApprovalService.detect("execute_shell", command);
        assertThat(result)
                .withFailMessage("expected Docker lifecycle detection for command: %s", command)
                .isNotNull();
        assertThat(result.getPatternKey()).isEqualTo(patternKey);
    }

    static void assertHardlineBlocked(
            DangerousCommandApprovalService service, TestTrace trace, String command) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", command);

        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer())
                .contains("BLOCKED (hardline)")
                .contains("Windows shutdown/reboot");
        assertThat(service.getPendingApproval(trace.session)).isNull();
    }

    static void assertGatewayCommandApproval(
            DangerousCommandApprovalService service, String command, String patternKey)
            throws Exception {
        Map<String, Object> toolArgs = new LinkedHashMap<String, Object>();
        toolArgs.put("command", command);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("tool_name", "terminal");
        args.put("tool_args", toolArgs);
        TestTrace trace = new TestTrace();

        service.buildInterceptor().onAction(trace, exchange("call_tool", args));

        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);
        assertThat(trace.getFinalAnswer()).contains("需要审批");
        assertThat(pending).isNotNull();
        assertThat(pending.getToolName()).isEqualTo("terminal");
        assertThat(pending.getCommand()).isEqualTo(command);
        assertThat(pending.getPatternKeys()).containsExactly(patternKey);
    }

    static void assertWriteDenied(SecurityPolicyService securityPolicyService, String path) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", path);
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("file_write", args);
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getPath()).isEqualTo(path);
    }

    static void assertFileApprovalRequired(
            SecurityPolicyService.FileVerdict verdict, String policyKey) {
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.isApprovalRequired()).isTrue();
        assertThat(verdict.getPolicyKey()).isEqualTo(policyKey);
    }

    static void assertUrlApprovalRequired(
            SecurityPolicyService.UrlVerdict verdict, String policyKey) {
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.isApprovalRequired()).isTrue();
        assertThat(verdict.getPolicyKey()).isEqualTo(policyKey);
    }

    static void assertReadDenied(
            SecurityPolicyService securityPolicyService, String path, String message) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", path);
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("file_read", args);
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains(message);
        assertThat(verdict.getPath()).isEqualTo(path);
    }

    static void assertFileReadDenied(SecurityPolicyService securityPolicyService, String path) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", path);
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("file_read", args);
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("凭据");
        assertThat(verdict.getPath()).isEqualTo(path);
    }

    static ToolExchanger exchange(String toolName, Map<String, Object> args) {
        return new ToolExchanger(toolName, args);
    }

    static Map<String, Object> gatewayToolCall(String toolName, Map<String, Object> toolArgs) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("tool_name", toolName);
        args.put("tool_args", toolArgs);
        return args;
    }

    /** 固定返回预设 Tirith 扫描结果的测试桩。 */
    static class FakeTirithSecurityService extends TirithSecurityService {
        private final TirithSecurityService.ScanResult result;

        FakeTirithSecurityService(TirithSecurityService.ScanResult result) {
            super(null);
            this.result = result;
        }

        @Override
        public TirithSecurityService.ScanResult checkCommandSecurityForTool(
                String toolName, String command) {
            return result;
        }
    }

    /** 记录 Tirith 调用次数的测试桩。 */
    static class CountingTirithSecurityService extends FakeTirithSecurityService {
        private int calls;

        CountingTirithSecurityService(TirithSecurityService.ScanResult result) {
            super(result);
        }

        @Override
        public TirithSecurityService.ScanResult checkCommandSecurityForTool(
                String toolName, String command) {
            calls++;
            return super.checkCommandSecurityForTool(toolName, command);
        }

        int getCalls() {
            return calls;
        }
    }

    /** 固定 DNS 解析结果的安全策略测试桩。 */
    static class FixedDnsSecurityPolicyService extends SecurityPolicyService {
        private final String ip;

        FixedDnsSecurityPolicyService(com.jimuqu.solon.claw.config.AppConfig appConfig, String ip) {
            super(appConfig);
            this.ip = ip;
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            return new InetAddress[] {InetAddress.getByName(ip)};
        }
    }

    /** 固定 DNS 解析失败的安全策略测试桩。 */
    static class FailingDnsSecurityPolicyService extends SecurityPolicyService {
        FailingDnsSecurityPolicyService(com.jimuqu.solon.claw.config.AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            throw new java.net.UnknownHostException(host);
        }
    }

    /** 提供审批拦截器测试所需的最小 ReAct trace。 */
    static class TestTrace extends org.noear.solon.ai.agent.react.ReActTrace {
        final InMemoryAgentSession session;
        private String route;

        TestTrace() {
            this(new InMemoryAgentSession("tirith-test"));
        }

        TestTrace(InMemoryAgentSession session) {
            this.session = session;
        }

        @Override
        public InMemoryAgentSession getSession() {
            return session;
        }

        @Override
        public org.noear.solon.flow.FlowContext getContext() {
            return session.getContext();
        }

        @Override
        public void setRoute(String route) {
            this.route = route;
        }

        @Override
        public String getRoute() {
            return route;
        }
    }
}
