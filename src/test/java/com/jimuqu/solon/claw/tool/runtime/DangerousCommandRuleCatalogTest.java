package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证危险命令规则目录的摘要采样逻辑，避免诊断输出展示已不存在的规则名。 */
class DangerousCommandRuleCatalogTest {
    @Test
    void preferredRuleSamplesSkipsMissingRuleKeys() {
        List<String> samples =
                DangerousCommandRuleCatalog.preferredRuleSamples(
                        DangerousCommandRuleCatalog.RULES,
                        2,
                        "missing_rule_key",
                        "domestic_object_storage_recursive_remove",
                        "remote_credential_file_transfer",
                        "another_missing_rule_key");

        assertThat(samples)
                .containsExactly(
                        "domestic_object_storage_recursive_remove",
                        "remote_credential_file_transfer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvalPolicySummaryUsesSharedTerminalGuardrailCatalog() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        Map<String, Object> summary = env.dangerousCommandApprovalService.approvalPolicySummary();
        List<String> guardrails = (List<String>) summary.get("terminalGuardrails");

        assertThat(guardrails)
                .contains(
                        "shell_level_background",
                        "detached_terminal_session",
                        "powershell_background_job",
                        "inline_background_ampersand",
                        "long_lived_foreground");
        assertThat(summary.get("terminalGuardrailCount")).isEqualTo(guardrails.size());
    }
}
