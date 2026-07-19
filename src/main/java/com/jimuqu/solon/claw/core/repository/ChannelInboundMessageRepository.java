package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.model.ChannelInboundMessageRecord;
import java.util.List;

/** 渠道入站消息总账仓储。 */
public interface ChannelInboundMessageRepository {
    /**
     * 按唯一消息键插入入站记录。
     *
     * @param record 待插入记录。
     * @return 成功插入返回 true，已存在相同消息键返回 false。
     */
    boolean saveIfAbsent(ChannelInboundMessageRecord record) throws Exception;

    /**
     * 原子消费一个逻辑批次中的原始 pending receipt。
     *
     * <p>leader 进入 processing 并保存合并后的消息，其余成员收敛为 completed；任一成员不存在或已被消费时整个事务不生效。
     *
     * @param leaderIngressId 承担逻辑处理和回复投递的首条入站标识。
     * @param memberIngressIds 按平台接收顺序排列的全部原始入站标识。
     * @param sourceKey 完成 Profile 与群聊路由后的来源键。
     * @param messageJson 合并后的统一消息 JSON。
     * @param startedAt 开始处理时间戳。
     * @return 全部成员成功原子消费时返回 true。
     */
    boolean startBatch(
            String leaderIngressId,
            List<String> memberIngressIds,
            String sourceKey,
            String messageJson,
            long startedAt)
            throws Exception;

    /**
     * 按幂等键读取入站记录。
     *
     * @param messageKey 消息幂等键。
     * @return 对应记录，不存在时返回 null。
     */
    ChannelInboundMessageRecord findByMessageKey(String messageKey) throws Exception;

    /**
     * 在渠道投递前保存完整回复。
     *
     * @param ingressId 入站标识。
     * @param replyJson 回复 JSON，可为空。
     * @param processedAt 处理完成时间戳。
     * @return 仅在 processing 状态成功写入回复时返回 true。
     */
    boolean markProcessed(String ingressId, String replyJson, long processedAt) throws Exception;

    /**
     * 保存业务回复并同时分配正常投递 owner，避免平台 ready 恢复者抢走刚生成的回复。
     *
     * @param ingressId 入站标识。
     * @param replyJson 回复 JSON，可为空。
     * @param deliveryToken 正常投递 owner 令牌。
     * @param processedAt 处理完成时间戳。
     * @return 仅在 processing 状态成功写入回复时返回 true。
     */
    boolean markProcessedForDelivery(
            String ingressId, String replyJson, String deliveryToken, long processedAt)
            throws Exception;

    /**
     * 标记回复已成功投递或无需投递。
     *
     * @param ingressId 入站标识。
     * @param completedAt 投递完成时间戳。
     * @return 仅在 processed 状态成功收敛时返回 true。
     */
    boolean markCompleted(String ingressId, long completedAt) throws Exception;

    /**
     * 保留已处理回复并记录投递失败，供重启恢复重试。
     *
     * @param ingressId 入站标识。
     * @param error 脱敏错误摘要。
     * @param updatedAt 更新时间戳。
     */
    void markDeliveryFailed(String ingressId, String error, long updatedAt) throws Exception;

    /**
     * 标记无法安全自动重放的处理失败。
     *
     * @param ingressId 入站标识。
     * @param error 脱敏错误摘要。
     * @param updatedAt 更新时间戳。
     */
    void markFailed(String ingressId, String error, long updatedAt) throws Exception;

    /**
     * 把本次进程启动前遗留的 processing 记录收敛为失败，避免重复执行副作用；pending 保留给安全恢复。
     *
     * @param profile 当前 Profile。
     * @param before 进程启动时间戳。
     * @param error 固定恢复说明。
     * @return 被收敛的记录数量。
     */
    int markInterrupted(String profile, long before, String error) throws Exception;

    /**
     * 捕获当前 Profile 已落库入站消息的稳定插入序号上界。
     *
     * @param profile 当前 Profile。
     * @return 当前最大插入序号；没有记录时返回 0。
     */
    long capturePendingWatermark(String profile) throws Exception;

    /**
     * 从稳定游标之后列出水位内尚未被消费的原始入站消息。
     *
     * @param profile 当前 Profile。
     * @param maxSequence 仅恢复不晚于该稳定插入序号的记录，避免与连接后的新消息竞争。
     * @param afterSequence 上一页最后一条稳定插入序号；首次查询传 0。
     * @param limit 最大返回数量。
     * @return 按稳定插入序号升序排列的 pending 记录。
     */
    List<ChannelInboundMessageRecord> listPending(
            String profile, long maxSequence, long afterSequence, int limit) throws Exception;

    /**
     * 从稳定游标之后列出指定平台在水位内尚未消费的原始入站消息。
     *
     * @param profile 当前 Profile。
     * @param platform 当前恢复连接的平台名称。
     * @param maxSequence 仅恢复不晚于该稳定插入序号的记录。
     * @param afterSequence 上一页最后一条稳定插入序号；首次查询传 0。
     * @param limit 最大返回数量。
     * @return 按稳定插入序号升序排列的指定平台 pending 记录。
     */
    List<ChannelInboundMessageRecord> listPending(
            String profile, String platform, long maxSequence, long afterSequence, int limit)
            throws Exception;

    /**
     * 在渠道启动前认领当时已有的全部 processed 回复。
     *
     * @param profile 当前 Profile。
     * @param recoveryToken 本次启动恢复批次令牌。
     * @return 被认领的回复数量。
     */
    int claimStartupDeliveries(String profile, String recoveryToken) throws Exception;

    /**
     * 在指定平台真正可用后认领全部尚未完成且未被其他恢复者占用的回复。
     *
     * @param profile 当前 Profile。
     * @param platform 已恢复可用的平台名称。
     * @param recoveryToken 本次恢复批次令牌。
     * @return 被认领的回复数量。
     */
    int claimPlatformDeliveries(String profile, String platform, String recoveryToken)
            throws Exception;

    /**
     * 在单个平台连接成功后认领尚未被其他恢复批处理的投递失败回复。
     *
     * @param profile 当前 Profile。
     * @param platform 已恢复连接的平台名称。
     * @param recoveryToken 本次重连恢复批次令牌。
     * @return 被认领的回复数量。
     */
    int claimFailedDeliveries(String profile, String platform, String recoveryToken)
            throws Exception;

    /**
     * 收敛上一次进程中已开始但结果未知的投递，并释放尚未开始外发的安全认领。
     *
     * @param profile 当前 Profile。
     * @param interruptedBefore 仅处理不晚于本次启动时间的旧记录。
     * @param error 结果未知投递的固定失败说明。
     * @return 被收敛或释放的记录总数。
     */
    int convergeInterruptedDeliveries(String profile, long interruptedBefore, String error)
            throws Exception;

    /**
     * 仅由当前 owner 把已认领回复推进为正在外发；进程在此后崩溃时必须 fail-closed。
     *
     * @param ingressId 入站标识。
     * @param deliveryToken 当前投递 owner 令牌。
     * @param startedAt 外部投递开始时间戳。
     * @return owner 与 processed 状态同时匹配时返回 true。
     */
    boolean markClaimedDeliveryStarted(String ingressId, String deliveryToken, long startedAt)
            throws Exception;

    /**
     * 仅由当前 owner 释放一条尚未开始外发的 processed 认领。
     *
     * @param ingressId 入站标识。
     * @param deliveryToken 当前投递 owner 令牌。
     * @return owner 与 processed 状态同时匹配时返回 true。
     */
    boolean releaseClaimedDelivery(String ingressId, String deliveryToken) throws Exception;

    /**
     * 仅由当前恢复 owner 把已投递回复收敛为完成态。
     *
     * @param ingressId 入站标识。
     * @param recoveryToken 当前恢复 owner 令牌。
     * @param completedAt 投递完成时间戳。
     * @return owner 与 delivering 状态同时匹配时返回 true。
     */
    boolean markClaimedCompleted(String ingressId, String recoveryToken, long completedAt)
            throws Exception;

    /**
     * 仅由当前恢复 owner 把无法安全重放的记录收敛为失败态。
     *
     * @param ingressId 入站标识。
     * @param recoveryToken 当前恢复 owner 令牌。
     * @param error 脱敏错误摘要。
     * @param updatedAt 更新时间戳。
     * @return owner 与 processed 状态同时匹配时返回 true。
     */
    boolean markClaimedFailed(String ingressId, String recoveryToken, String error, long updatedAt)
            throws Exception;

    /**
     * 从稳定游标之后列出指定恢复批次认领的 processed 回复。
     *
     * @param profile 当前 Profile。
     * @param recoveryToken 恢复批次令牌。
     * @param afterProcessedAt 上一页最后一条处理时间；首次查询传负数。
     * @param afterIngressId 上一页最后一条入站标识；首次查询传空字符串。
     * @param limit 最大返回数量。
     * @return 按处理时间和入站标识升序排列的已认领回复。
     */
    List<ChannelInboundMessageRecord> listClaimedDeliveries(
            String profile,
            String recoveryToken,
            long afterProcessedAt,
            String afterIngressId,
            int limit)
            throws Exception;

    /**
     * 释放指定批次仍未完成的恢复认领，允许下一次连接继续尝试。
     *
     * @param profile 当前 Profile。
     * @param recoveryToken 恢复批次令牌。
     */
    void releaseDeliveryClaim(String profile, String recoveryToken) throws Exception;

    /**
     * 列出已保存回复但尚未完成投递的记录。
     *
     * @param profile 当前 Profile。
     * @param limit 最大返回数量。
     * @return 按处理时间升序排列的待恢复记录。
     */
    List<ChannelInboundMessageRecord> listProcessed(String profile, int limit) throws Exception;

    /**
     * 从稳定游标之后列出已保存回复但尚未完成投递的记录。
     *
     * @param profile 当前 Profile。
     * @param afterProcessedAt 上一页最后一条处理时间；首次查询传负数。
     * @param afterIngressId 上一页最后一条入站标识；首次查询传空字符串。
     * @param limit 最大返回数量。
     * @return 按处理时间和入站标识升序排列的待恢复记录。
     */
    List<ChannelInboundMessageRecord> listProcessed(
            String profile, long afterProcessedAt, String afterIngressId, int limit)
            throws Exception;

    /**
     * 删除足够旧的完成态和失败态入站总账；所有可能参与处理或恢复的非终态必须保留。
     *
     * @param profile 当前 Profile。
     * @param updatedBefore 仅删除更新时间早于该时间戳的终态记录。
     * @return 实际删除的记录数量。
     */
    int pruneTerminal(String profile, long updatedBefore) throws Exception;
}
