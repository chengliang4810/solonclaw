package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import org.junit.jupiter.api.Test;

/** 验证代码执行工具中文件和 URL 预检模式的配置行为。 */
class SolonClawCodeExecutionGuardrailModeTest {
    @Test
    void shouldBypassFileAndUrlPreflightByDefault() {
        AppConfig config = new AppConfig();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(config);

        assertThat(SolonClawCodeExecutionSkills.isFileGuardrailEnabled(config)).isFalse();
        assertThat(SolonClawCodeExecutionSkills.isUrlGuardrailEnabled(config)).isFalse();
        SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                "open('/root/.ssh/id_rsa').read()\n"
                        + "import requests\n"
                        + "requests.get('http://127.0.0.1:8080/health')",
                securityPolicyService);
    }

    @Test
    void shouldBlockSensitiveFileAndMetadataUrlWhenStrictPreflightConfigured() {
        AppConfig config = new AppConfig();
        config.getSecurity().setFileGuardrailMode("strict");
        config.getSecurity().setUrlGuardrailMode("strict");
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(config);

        assertThatThrownBy(
                        () ->
                                SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                                        "open('/root/.ssh/id_rsa').read()", securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略");

        assertThatThrownBy(
                        () ->
                                SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                                        "import requests\nrequests.get('http://169.254.169.254/latest')",
                                        securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略");
    }

    @Test
    void shouldBypassConfiguredFileAndUrlPreflightForCodeExecution() {
        AppConfig config = new AppConfig();
        config.getSecurity().setFileGuardrailMode("bypass");
        config.getSecurity().setUrlGuardrailMode("bypass");
        config.getSecurity().setGuardrailMode("bypass");
        config.getSecurity().setHardlineAllowlist(java.util.Collections.singletonList("*"));
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(config);

        SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                "open('/root/.ssh/id_rsa').read()\n"
                        + "import requests\n"
                        + "requests.get('http://169.254.169.254/latest')",
                securityPolicyService);
    }

    @Test
    void shouldBypassSoftDangerousRulesForCodeExecutionWhenGuardrailModeBypass() {
        AppConfig config = new AppConfig();
        config.getSecurity().setUrlGuardrailMode("bypass");
        config.getSecurity().setGuardrailMode("bypass");
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(config);

        SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                "import requests\n"
                        + "requests.get('https://api.example.invalid/accounts', "
                        + "headers={'x-api-key': 'demo-key'})",
                securityPolicyService);
    }
}
