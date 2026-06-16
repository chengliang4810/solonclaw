package com.jimuqu.solon.claw.core.model;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 主动协作观测记录。 */
@Getter
@Setter
@NoArgsConstructor
public class ProactiveObservationRecord {
    /** 观测记录 ID。 */
    private String observationId;

    /** 所属 tick ID。 */
    private String tickId;

    /** 采集器名称。 */
    private String collector;

    /** 关联来源键。 */
    private String sourceKey;

    /** 观测摘要。 */
    private String summary;

    /** 采集器输出的结构化载荷。 */
    private Map<String, Object> payload;

    /** 当前处理状态。 */
    private String status;

    /** 采集失败时的错误摘要。 */
    private String error;

    /** 记录创建时间。 */
    private long createdAt;
}
