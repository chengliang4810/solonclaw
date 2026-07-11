// src/test/java/com/jimuqu/solon/claw/goal/GoalContractParserTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalContractParserTest {
    @Test
    void plainTextBecomesHeadlineEmptyContract() {
        GoalContractParser.ParseResult r = GoalContractParser.parse("补齐项目测试");
        assertThat(r.getHeadline()).isEqualTo("补齐项目测试");
        assertThat(r.getContract().isEmpty()).isTrue();
    }

    @Test
    void extractsOutcomeAndVerification() {
        String text = "补齐测试\noutcome: 全部测试通过\nverification: 运行 mvn test";
        GoalContractParser.ParseResult r = GoalContractParser.parse(text);
        assertThat(r.getHeadline()).isEqualTo("补齐测试");
        assertThat(r.getContract().getOutcome()).isEqualTo("全部测试通过");
        assertThat(r.getContract().getVerification()).isEqualTo("运行 mvn test");
    }

    @Test
    void incidentColonNotMangled() {
        // "Fix bug: the parser" 里的冒号不是已知前缀，整行归入 headline
        GoalContractParser.ParseResult r = GoalContractParser.parse("Fix bug: the parser here");
        assertThat(r.getHeadline()).contains("Fix bug: the parser here");
        assertThat(r.getContract().isEmpty()).isTrue();
    }

    @Test
    void recognizesStopWhenAlias() {
        GoalContractParser.ParseResult r = GoalContractParser.parse("do task\nstop when: 遇到阻塞");
        assertThat(r.getContract().getStopWhen()).isEqualTo("遇到阻塞");
    }

    @Test
    void nullInputReturnsEmpty() {
        GoalContractParser.ParseResult r = GoalContractParser.parse(null);
        assertThat(r.getHeadline()).isEmpty();
        assertThat(r.getContract().isEmpty()).isTrue();
    }
}
