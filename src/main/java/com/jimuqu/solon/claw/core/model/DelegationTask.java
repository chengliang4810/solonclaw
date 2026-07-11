package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 子代理委托任务。 */
@Getter
@Setter
@NoArgsConstructor
public class DelegationTask {
    /** 任务名称。 */
    private String name;

    /** 委托目标。 */
    private String prompt;

    /** 可选短上下文。 */
    private String context;

    /** 允许子代理使用的工具名列表。 */
    private java.util.List<String> allowedTools;

    /** 允许子代理使用的工具集选择器，例如 web、terminal、file。 */
    private java.util.List<String> toolsets;

    /** 期望输出格式说明。 */
    private String expectedOutput;

    /** 可写入范围说明。 */
    private String writeScope;

    /** 子代理角色：leaf 或 orchestrator。 */
    private String role;

    /** 可选目标 Profile；为空时由运行时结合 Profile 名称和职责说明选择。 */
    private String profile;
}
