// src/test/java/com/jimuqu/solon/claw/goal/GoalContractTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalContractTest {
    @Test
    void emptyContractRendersBlankAndIsEmpty() {
        GoalContract c = new GoalContract();
        assertThat(c.isEmpty()).isTrue();
        assertThat(c.renderBlock()).isEmpty();
    }

    @Test
    void rendersNonEmptyFieldsWithLabels() {
        GoalContract c = new GoalContract();
        c.setOutcome("测试通过");
        c.setVerification("运行 mvn test 全绿");
        c.setStopWhen("遇到阻塞");
        assertThat(c.isEmpty()).isFalse();
        assertThat(c.renderBlock())
                .contains("- Outcome: 测试通过")
                .contains("- Verification: 运行 mvn test 全绿")
                .contains("- Stop when blocked: 遇到阻塞")
                .doesNotContain("Constraints")
                .doesNotContain("Boundaries");
    }

    @Test
    void roundTripsThroughJson() {
        GoalContract c = new GoalContract();
        c.setOutcome("完成 A");
        c.setConstraints("不改 B");
        String json = c.toJson();
        GoalContract back = GoalContract.fromJson(json);
        assertThat(back.getOutcome()).isEqualTo("完成 A");
        assertThat(back.getConstraints()).isEqualTo("不改 B");
        assertThat(back.getVerification()).isEqualTo("");
    }

    @Test
    void fromJsonHandlesNullAndBlank() {
        assertThat(GoalContract.fromJson(null)).isNull();
        assertThat(GoalContract.fromJson("")).isNull();
    }
}
