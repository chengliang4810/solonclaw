package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
}
