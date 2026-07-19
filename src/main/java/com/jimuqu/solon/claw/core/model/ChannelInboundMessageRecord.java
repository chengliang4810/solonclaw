package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 真实渠道入站消息的持久化总账记录。 */
@Getter
@Setter
@NoArgsConstructor
public class ChannelInboundMessageRecord {
    /** 已同步准入但尚未被异步队列或文本批次领取。 */
    public static final String STATUS_PENDING = "pending";

    /** 正在执行命令或 Agent 处理。 */
    public static final String STATUS_PROCESSING = "processing";

    /** 已保存回复，等待或重试渠道投递。 */
    public static final String STATUS_PROCESSED = "processed";

    /** 已开始调用外部渠道，结果在终态落库前不得自动重放。 */
    public static final String STATUS_DELIVERING = "delivering";

    /** 回复已成功投递或无需投递。 */
    public static final String STATUS_COMPLETED = "completed";

    /** 处理状态不确定且不能安全自动重放。 */
    public static final String STATUS_FAILED = "failed";

    /** 入站总账唯一标识。 */
    private String ingressId;

    /** SQLite 分配的稳定插入序号，仅用于恢复水位与原始接收顺序。 */
    private long sequence;

    /** 平台消息 ID 或稳定内容指纹生成的幂等键。 */
    private String messageKey;

    /** 消息归属 Profile。 */
    private String profile;

    /** 消息所属平台名称。 */
    private String platform;

    /** 已完成群聊与 Profile 路由后的来源键。 */
    private String sourceKey;

    /** 完整统一入站消息 JSON。 */
    private String messageJson;

    /** 当前处理状态。 */
    private String status;

    /** 进入业务处理的尝试次数。 */
    private int attempts;

    /** 业务处理完成后保存的回复 JSON。 */
    private String replyJson;

    /** 最近一次脱敏错误摘要。 */
    private String lastError;

    /** 首次落库时间戳。 */
    private long createdAt;

    /** 最近一次状态更新时间戳。 */
    private long updatedAt;

    /** 回复完成持久化的时间戳。 */
    private long processedAt;

    /** 回复完成渠道投递的时间戳。 */
    private long completedAt;
}
