package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 长任务 Todo 一等状态记录。 */
@Getter
@Setter
@NoArgsConstructor
public class TodoRecord {
    /** 记录Todo中的todo标识。 */
    private String todoId;

    /** 记录Todo中的运行标识。 */
    private String runId;

    /** 记录Todo中的会话标识。 */
    private String sessionId;

    /** 记录Todo中的来源键。 */
    private String sourceKey;

    /** 记录Todo中的content。 */
    private String content;

    /** 记录Todo中的状态。 */
    private String status;

    /** 记录Todo中的sortOrder。 */
    private int sortOrder;

    /** 记录Todo中的创建时间。 */
    private long createdAt;

    /** 记录Todo中的更新时间。 */
    private long updatedAt;
}
