package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Param;

/** 验证直接代码执行入口在启动解释器前应用不可绕过的安全边界。 */
class SolonClawCodeExecutionGuardrailModeTest {
    @Test
    void shouldBlockDangerousScriptWithoutDirectHandlerApproval() {
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(new AppConfig());

        assertThatThrownBy(
                        () ->
                                SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                                        "import os\nos.system('git reset --hard')",
                                        securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("危险命令");
    }

    @Test
    void shouldBlockSensitiveFileBeforeCodeExecution() {
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(new AppConfig());

        assertThatThrownBy(
                        () ->
                                SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                                        "open('/root/.ssh/id_rsa').read()", securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略");
    }

    @Test
    void shouldBlockMetadataUrlBeforeCodeExecution() {
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(new AppConfig());

        assertThatThrownBy(
                        () ->
                                SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                                        "import requests\n"
                                                + "requests.get('http://169.254.169.254/latest/meta-data/')",
                                        securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略");
    }

    @Test
    void shouldConsumeWholeScriptApprovalAndAllowOrdinaryCode() {
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(new AppConfig());
        String dangerous = "import os\nos.system('git reset --hard')";

        DangerousCommandApprovalService.grantCurrentThreadApproval(
                ToolNameConstants.EXECUTE_CODE, dangerous);

        assertThatCode(
                        () ->
                                SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                                        dangerous, securityPolicyService))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                                        "print('safe')", securityPolicyService))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotAllowWholeScriptApprovalToBypassEmbeddedHardline() {
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(new AppConfig());
        String hardline = "import os\nos.system('rm -rf /')";

        DangerousCommandApprovalService.grantCurrentThreadApproval(
                ToolNameConstants.EXECUTE_CODE, hardline);

        assertThatThrownBy(
                        () ->
                                SolonClawCodeExecutionSkills.assertSafeExecuteCodeScript(
                                        hardline, securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hardline");
    }

    @Test
    void shouldDetectPythonArgvAndEveryNodeChildProcessHardline() {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));

        assertThat(
                        approvalService.detectHardline(
                                ToolNameConstants.EXECUTE_PYTHON,
                                "import subprocess\nsubprocess.run(['rm', '-rf', '/'])"))
                .isNotNull();
        assertThat(
                        approvalService.detectHardline(
                                ToolNameConstants.EXECUTE_JS,
                                "require('child_process').execSync('echo safe')\n"
                                        + "require('child_process').execSync('rm -rf /')"))
                .isNotNull();
    }

    @Test
    void shouldRejectAtHandlerBoundaryBeforeStartingInterpreter() {
        AppConfig config = new AppConfig();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool tool =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        ".", "missing-python-command", new SecurityPolicyService(config), config);

        assertHandlerBlockedBeforeInterpreter(
                tool, "import os\nos.system('git reset --hard')", "危险命令");
        assertHandlerBlockedBeforeInterpreter(tool, "open('/root/.ssh/id_rsa').read()", "文件安全策略");
        assertHandlerBlockedBeforeInterpreter(
                tool,
                "import requests\n" + "requests.get('http://169.254.169.254/latest/meta-data/')",
                "URL 安全策略");
    }

    @Test
    void shouldExposePythonAndJsTimeoutInSecondsAndConvertForTalent() throws Exception {
        assertTimeoutParamUsesSeconds(SolonClawCodeExecutionSkills.SafePythonSkill.class);
        assertTimeoutParamUsesSeconds(SolonClawCodeExecutionSkills.SafeNodejsSkill.class);

        Method converter =
                SolonClawCodeExecutionSkills.class.getDeclaredMethod(
                        "scriptTimeoutMillis", Integer.class);
        converter.setAccessible(true);
        assertThat(converter.invoke(null, Integer.valueOf(1))).isEqualTo(Integer.valueOf(1000));
        assertThat(converter.invoke(null, new Object[] {null})).isEqualTo(Integer.valueOf(120000));
    }

    /** 验证模型可见 timeout 参数统一使用秒。 */
    private void assertTimeoutParamUsesSeconds(Class<?> skillType) throws Exception {
        Method execute = skillType.getMethod("execute", String.class, Integer.class);
        Param timeout = execute.getParameters()[1].getAnnotation(Param.class);

        assertThat(timeout).isNotNull();
        assertThat(timeout.defaultValue()).isEqualTo("120");
        assertThat(timeout.description()).contains("秒").doesNotContain("毫秒");
    }

    /** 验证 handler 在解释器启动前返回指定安全策略错误。 */
    private void assertHandlerBlockedBeforeInterpreter(
            SolonClawCodeExecutionSkills.SafeExecuteCodeTool tool,
            String code,
            String expectedError) {
        ONode result = ONode.ofJson(tool.executeCode(code, Integer.valueOf(1)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString())
                .contains(expectedError)
                .doesNotContain("missing-python-command");
    }
}
