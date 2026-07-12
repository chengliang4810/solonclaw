package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.Locale;

/** 主动协作投递服务，负责选择 home channel、发送最终文案并记录投递结果。 */
public class ProactiveDispatchService {
    /** home channel 仓储，用于解析各平台默认投递目标。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /** 统一渠道投递服务。 */
    private final DeliveryService deliveryService;

    /** 主动协作仓储，用于审计投递结果。 */
    private final ProactiveRepository proactiveRepository;

    /**
     * 创建主动协作投递服务。
     *
     * @param gatewayPolicyRepository home channel 仓储。
     * @param deliveryService 统一投递服务。
     * @param proactiveRepository 主动协作仓储。
     */
    public ProactiveDispatchService(
            GatewayPolicyRepository gatewayPolicyRepository,
            DeliveryService deliveryService,
            ProactiveRepository proactiveRepository) {
        this.gatewayPolicyRepository = gatewayPolicyRepository;
        this.deliveryService = deliveryService;
        this.proactiveRepository = proactiveRepository;
    }

    /**
     * 投递已批准的主动协作文案，并保存成功或失败记录。
     *
     * @param decision 已批准的发送决策。
     * @param message 最终外发文本。
     * @return 返回带投递状态的决策记录。
     * @throws Exception 仓储保存失败时抛出异常；渠道投递失败会被记录为 FAILED 不向外抛出。
     */
    public ProactiveDecisionRecord dispatch(ProactiveDecision decision, String message)
            throws Exception {
        ProactiveDecisionRecord record = baseRecord(decision, message);
        if (decision == null
                || !"SEND".equalsIgnoreCase(StrUtil.nullToEmpty(decision.getDecision()))) {
            record.setDeliveryStatus("SKIPPED");
            record.setDeliveryError("not_send_decision_or_empty_message");
            save(record);
            return record;
        }
        if (StrUtil.isBlank(message)) {
            return fail(record, decision, "empty_message");
        }
        HomeChannelRecord home;
        try {
            home = resolveHomeChannel(decision);
        } catch (Exception e) {
            return fail(record, decision, safeError(e));
        }
        if (home == null) {
            return fail(record, decision, "no_home_channel");
        }
        record.setDeliveryPlatform(home.getPlatform().name());
        record.setDeliveryChatId(home.getChatId());
        record.setDeliveryThreadId(home.getThreadId());
        record.setDeliveryStatus("DELIVERY_PENDING");
        record.setDeliveryError(null);
        save(record);
        try {
            DeliveryRequest request = new DeliveryRequest();
            request.setPlatform(home.getPlatform());
            request.setChatId(home.getChatId());
            request.setThreadId(home.getThreadId());
            if (home.getPlatform() == PlatformType.FEISHU
                    && StrUtil.isNotBlank(home.getThreadId())) {
                request.setReplyToMessageId(home.getThreadId());
            }
            request.setText(message);
            deliveryService.deliver(request);
        } catch (Exception e) {
            return fail(record, decision, safeError(e));
        }
        record.setDeliveryStatus("SENT");
        record.setDeliveryError(null);
        save(record);
        markCandidate(decision, "SENT");
        return record;
    }

    /** 记录投递失败，并把候选退回待处理状态。 */
    private ProactiveDecisionRecord fail(
            ProactiveDecisionRecord record, ProactiveDecision decision, String error)
            throws Exception {
        record.setDeliveryStatus("FAILED");
        record.setDeliveryError(StrUtil.nullToEmpty(error));
        save(record);
        markCandidate(decision, "PENDING");
        return record;
    }

    /** 同步候选投递终态或重试状态。 */
    private void markCandidate(ProactiveDecision decision, String status) throws Exception {
        if (proactiveRepository == null
                || decision == null
                || StrUtil.isBlank(decision.getCandidateId())) {
            return;
        }
        proactiveRepository.markCandidateStatus(
                decision.getCandidateId(),
                status,
                decision.getDecisionId(),
                System.currentTimeMillis());
    }

    /**
     * 构造投递审计记录的公共字段。
     *
     * @param decision 主动协作决策。
     * @param message 外发文案。
     * @return 返回决策记录。
     */
    private ProactiveDecisionRecord baseRecord(ProactiveDecision decision, String message) {
        ProactiveDecisionRecord record = new ProactiveDecisionRecord();
        if (decision != null) {
            record.setDecisionId(decision.getDecisionId());
            record.setTickId(decision.getTickId());
            record.setCandidateId(decision.getCandidateId());
            record.setSourceKey(decision.getSourceKey());
            record.setDecision(decision.getDecision());
            record.setReason(decision.getReason());
            record.setMetadata(decision.getMetadata());
            record.setCreatedAt(decision.getCreatedAt());
        }
        record.setMessage(message);
        return record;
    }

    /**
     * 按来源平台优先、国内平台固定顺序回退解析 home channel。
     *
     * @param decision 主动协作决策。
     * @return 找到返回 home channel，否则返回 null。
     * @throws Exception home channel 仓储读取失败时抛出异常。
     */
    private HomeChannelRecord resolveHomeChannel(ProactiveDecision decision) throws Exception {
        PlatformType sourcePlatform =
                sourcePlatform(decision == null ? null : decision.getSourceKey());
        if (sourcePlatform != null && sourcePlatform != PlatformType.MEMORY) {
            HomeChannelRecord home =
                    validHome(gatewayPolicyRepository.getHomeChannel(sourcePlatform));
            if (home != null) {
                return home;
            }
        }
        for (PlatformType platform : PlatformType.DOMESTIC_PLATFORMS) {
            HomeChannelRecord home = validHome(gatewayPolicyRepository.getHomeChannel(platform));
            if (home != null) {
                return home;
            }
        }
        return null;
    }

    /**
     * 从 source key 前缀解析平台。
     *
     * @param sourceKey 来源键。
     * @return 返回平台；无法解析时返回 null。
     */
    private PlatformType sourcePlatform(String sourceKey) {
        if (StrUtil.isBlank(sourceKey)) {
            return null;
        }
        String prefix = sourceKey;
        int index = sourceKey.indexOf(':');
        if (index > 0) {
            prefix = sourceKey.substring(0, index);
        }
        return PlatformType.fromName(prefix.toUpperCase(Locale.ROOT));
    }

    /**
     * 校验 home channel 是否具备投递目标。
     *
     * @param home 原始 home channel。
     * @return 有效时返回原对象，否则返回 null。
     */
    private HomeChannelRecord validHome(HomeChannelRecord home) {
        if (home == null || home.getPlatform() == null || StrUtil.isBlank(home.getChatId())) {
            return null;
        }
        return home;
    }

    /**
     * 保存投递审计记录。
     *
     * @param record 决策记录。
     * @throws Exception 仓储保存失败时抛出异常。
     */
    private void save(ProactiveDecisionRecord record) throws Exception {
        if (proactiveRepository != null) {
            proactiveRepository.saveDecision(record);
        }
    }

    /**
     * 生成脱敏错误摘要，避免渠道异常泄漏 token。
     *
     * @param error 异常。
     * @return 返回安全错误摘要。
     */
    private String safeError(Exception error) {
        if (error == null) {
            return "";
        }
        String message =
                error.getClass().getSimpleName() + ": " + StrUtil.nullToEmpty(error.getMessage());
        return SecretRedactor.redact(message, 500);
    }
}
