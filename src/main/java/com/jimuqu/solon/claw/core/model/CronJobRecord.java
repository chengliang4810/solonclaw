package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 定时任务持久化记录。 */
@Getter
@Setter
@NoArgsConstructor
public class CronJobRecord {
    /** 任务 ID。 */
    private String jobId;

    /** 任务名称。 */
    private String name;

    /** cron 表达式。 */
    private String cronExpr;

    /** 触发时发给 Agent 的提示词。 */
    private String prompt;

    /** 会话来源键。 */
    private String sourceKey;

    /** 投递平台。 */
    private String deliverPlatform;

    /** 投递会话 ID。 */
    private String deliverChatId;

    /** 投递线程 ID。 */
    private String deliverThreadId;

    /** 原始来源 JSON。 */
    private String originJson;

    /** 绑定技能 JSON 数组。 */
    private String skillsJson;

    /** 单次任务重复上限；0 表示无限。 */
    private int repeatTimes;

    /** 已完成次数。 */
    private int repeatCompleted;

    /** 任务脚本，相对 runtime/scripts。 */
    private String script;

    /** 脚本工作目录。 */
    private String workdir;

    /** 是否跳过 Agent，直接投递脚本输出。 */
    private boolean noAgent;

    /** 上游任务上下文 JSON 数组。 */
    private String contextFromJson;

    /** 限定启用工具集 JSON 数组。 */
    private String enabledToolsetsJson;

    /** 任务固定模型。 */
    private String model;

    /** 任务固定 provider。 */
    private String provider;

    /** 任务固定 base URL。 */
    private String baseUrl;

    /** 是否包装 Cron 投递结果。 */
    private boolean wrapResponse = true;

    /** 最近一次运行状态。 */
    private String lastStatus;

    /** 最近一次运行错误。 */
    private String lastError;

    /** 最近一次投递错误。 */
    private String lastDeliveryError;

    /** 暂停时间。 */
    private long pausedAt;

    /** 暂停原因。 */
    private String pausedReason;

    /** 最近一次输出摘要。 */
    private String lastOutput;

    /** 任务状态。 */
    private String status;

    /** 下次执行时间。 */
    private long nextRunAt;

    /** 最近一次执行时间。 */
    private long lastRunAt;

    /** 创建时间。 */
    private long createdAt;

    /** 更新时间。 */
    private long updatedAt;
}
