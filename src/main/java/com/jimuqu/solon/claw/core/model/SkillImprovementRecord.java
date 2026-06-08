package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Self-improvement 产生的技能改进报告。 */
@Getter
@Setter
@NoArgsConstructor
public class SkillImprovementRecord {
    /** 记录技能Improvement中的improvement标识。 */
    private String improvementId;

    /** 记录技能Improvement中的会话标识。 */
    private String sessionId;

    /** 记录技能Improvement中的运行标识。 */
    private String runId;

    /** 记录技能Improvement中的技能名称。 */
    private String skillName;

    /** 记录技能Improvement中的action。 */
    private String action;

    /** 记录技能Improvement中的摘要。 */
    private String summary;

    /** 记录技能Improvement中的changedFilesJSON。 */
    private String changedFilesJson;

    /** 记录技能Improvement中的evidenceJSON。 */
    private String evidenceJson;

    /** 是否启用needsReview。 */
    private boolean needsReview;

    /** 记录技能Improvement中的创建时间。 */
    private long createdAt;
}
