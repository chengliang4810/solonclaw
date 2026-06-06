package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Curator 巡检报告索引。 */
@Getter
@Setter
@NoArgsConstructor
public class CuratorReportRecord {
    /** 记录技能维护Report中的report标识。 */
    private String reportId;

    /** 记录技能维护Report中的状态。 */
    private String status;

    /** 记录技能维护Report中的摘要。 */
    private String summary;

    /** 记录技能维护Report中的report路径。 */
    private String reportPath;

    /** 记录技能维护Report中的reportJSON。 */
    private String reportJson;

    /** 记录技能维护Report中的started时间。 */
    private long startedAt;

    /** 记录技能维护Report中的finished时间。 */
    private long finishedAt;
}
