package com.jimuqu.solon.claw.skillhub.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Hub 审计日志条目。 */
@Getter
@Setter
@NoArgsConstructor
public class HubAuditEntry {
    /** 记录中心审计Entry中的时间戳。 */
    private String timestamp;

    /** 记录中心审计Entry中的action。 */
    private String action;

    /** 记录中心审计Entry中的技能名称。 */
    private String skillName;

    /** 记录中心审计Entry中的来源。 */
    private String source;

    /** 记录中心审计Entry中的trust级别。 */
    private String trustLevel;

    /** 记录中心审计Entry中的判定。 */
    private String verdict;

    /** 记录中心审计Entry中的extra。 */
    private String extra;
}
