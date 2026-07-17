package com.jimuqu.solon.claw.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 上下文预算整数边界测试。 */
public class DefaultContextBudgetServiceOverflowTest {
    /** 多部分估值超过 int 上限时必须饱和，而不是溢出为负数。 */
    @Test
    void shouldSaturateEstimatedTokensInsteadOfOverflowing() {
        int estimated =
                DefaultContextBudgetService.saturatingTokenSum(Integer.MAX_VALUE - 10, 6, 6, 100);

        assertThat(estimated).isEqualTo(Integer.MAX_VALUE);
    }

    /** 空值和负数估值不得污染累计结果。 */
    @Test
    void shouldIgnoreNegativeTokenEstimates() {
        assertThat(DefaultContextBudgetService.saturatingTokenSum(null)).isZero();
        assertThat(DefaultContextBudgetService.saturatingTokenSum(-5, 3, -1)).isEqualTo(3);
    }
}
