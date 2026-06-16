package com.jimuqu.solon.claw.core.model;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 主动协作来源快照记录。 */
@Getter
@Setter
@NoArgsConstructor
public class ProactiveSourceSnapshotRecord {
    /** 来源类型。 */
    private String sourceType;

    /** 来源引用。 */
    private String sourceRef;

    /** 当前状态哈希。 */
    private String stateHash;

    /** 快照载荷。 */
    private Map<String, Object> payload;

    /** 最近检查时间。 */
    private long checkedAt;
}
