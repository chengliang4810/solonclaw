package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
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

    @Test
    void hardlineSummaryContainsOnlyCommandRules() {
        assertThat(DangerousCommandRuleCatalog.hardlineRuleCount())
                .isEqualTo(DangerousCommandRuleCatalog.HARDLINE_RULES.size());
        assertThat(DangerousCommandRuleCatalog.hardlineRuleSamples(99))
                .containsExactly(
                        "hardline_delete_root",
                        "hardline_delete_system_dir",
                        "hardline_delete_home",
                        "hardline_mkfs",
                        "hardline_dd_device",
                        "hardline_redirect_device",
                        "hardline_shutdown",
                        "hardline_kill_all",
                        "hardline_fork_bomb");
        assertThat(DangerousCommandRuleCatalog.hardlineBlockedCategories())
                .containsExactly(
                        "root_or_system_recursive_delete",
                        "filesystem_format_or_raw_device_write",
                        "system_shutdown_or_reboot",
                        "kill_all_or_fork_bomb");
        assertThat(DangerousCommandRuleCatalog.hardlineCoveredTools())
                .containsExactly(
                        ToolNameConstants.EXECUTE_SHELL,
                        ToolNameConstants.TERMINAL,
                        ToolNameConstants.EXECUTE_CODE,
                        ToolNameConstants.EXECUTE_PYTHON,
                        ToolNameConstants.EXECUTE_JS);
    }
}
