package com.jimuqu.solon.claw.support;

/** 静态上下文提示词字符预算的统一下限约束。 */
public final class BootstrapPromptBudgetSupport {
    /** 单个静态上下文块允许配置的最小字符数，确保截断标记可完整保留。 */
    public static final int MIN_FILE_CHAR_LIMIT = 256;

    /** 完整静态上下文提示词允许配置的最小字符预算，确保各类截断标记可完整保留。 */
    public static final int MIN_TOTAL_CHAR_BUDGET = 1024;

    /** 禁止实例化仅承载静态预算约束的工具类。 */
    private BootstrapPromptBudgetSupport() {}

    /**
     * 将单文件字符上限钳制到安全下限。
     *
     * @param value 原始单文件字符上限。
     * @return 不低于最小值的单文件字符上限。
     */
    public static int normalizeFileCharLimit(int value) {
        return Math.max(MIN_FILE_CHAR_LIMIT, value);
    }

    /**
     * 将总字符预算钳制到安全下限。
     *
     * @param value 原始总字符预算。
     * @return 不低于最小值的总字符预算。
     */
    public static int normalizeTotalCharBudget(int value) {
        return Math.max(MIN_TOTAL_CHAR_BUDGET, value);
    }
}
