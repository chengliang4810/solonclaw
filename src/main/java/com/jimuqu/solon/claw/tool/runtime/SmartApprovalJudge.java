package com.jimuqu.solon.claw.tool.runtime;

/** 定义Smart审批Judge的抽象契约，供不同运行时实现保持一致行为。 */
public interface SmartApprovalJudge {
    /**
     * 执行judge相关逻辑。
     *
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     * @param description 描述参数。
     * @return 返回judge结果。
     */
    SmartApprovalDecision judge(String toolName, String command, String description);
}
