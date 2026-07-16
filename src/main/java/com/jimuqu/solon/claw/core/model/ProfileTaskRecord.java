package com.jimuqu.solon.claw.core.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 跨 Profile 协作任务持久化记录；任务控制面统一保存在 default Profile 数据库。 */
@Getter
@Setter
@NoArgsConstructor
public class ProfileTaskRecord {
    /** 任务标识。 */
    private String taskId;

    /** 分配任务的 Profile。 */
    private String sourceProfile;

    /** 接收任务的命名 Profile；禁止为 default。 */
    private String targetProfile;

    /** 分配任务的来源会话键，用于结果回流。 */
    private String sourceKey;

    /** 任务标题。 */
    private String title;

    /** 当前版本任务描述。 */
    private String prompt;

    /** PENDING、READY、RUNNING、COMPLETED、FAILED、TIMED_OUT、BLOCKED、CANCELLED 或 INTERRUPTED。 */
    private String status;

    /** AND 前置任务标识。 */
    private List<String> dependencyIds = new ArrayList<String>();

    /** 已经开始的执行次数，包含首次执行。 */
    private int attemptCount;

    /** 最多执行次数，包含首次执行。 */
    private int maxAttempts = 5;

    /** 每次执行超时分钟数。 */
    private int timeoutMinutes = 30;

    /** 当前执行令牌，阻止超时后的迟到结果覆盖新状态。 */
    private String executionToken;

    /** 最终或最近一次结果。 */
    private String result;

    /** 最近一次失败摘要。 */
    private String error;

    /** 创建时间。 */
    private long createdAt;

    /** 更新时间。 */
    private long updatedAt;
}
