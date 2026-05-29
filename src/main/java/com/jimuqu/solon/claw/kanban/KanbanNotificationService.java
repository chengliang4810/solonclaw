package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Delivers subscribed Kanban task events through the configured channels. */
public class KanbanNotificationService {
    private static final List<String> NOTIFY_KINDS =
            Arrays.asList("completed", "blocked", "gave_up", "crashed", "timed_out");
    private static final List<String> REMOVE_AFTER_KINDS =
            Arrays.asList("completed");

    private final KanbanRepository repository;
    private final DeliveryService deliveryService;

    public KanbanNotificationService(KanbanRepository repository, DeliveryService deliveryService) {
        this.repository = repository;
        this.deliveryService = deliveryService;
    }

    public KanbanNotificationResult deliverPending() throws Exception {
        List<KanbanNotifySubscriptionRecord> subscriptions = repository.listNotifySubscriptions(null);
        KanbanNotificationResult result = new KanbanNotificationResult();
        result.setSubscriptions(subscriptions.size());
        for (KanbanNotifySubscriptionRecord subscription : subscriptions) {
            deliverSubscription(subscription, result);
        }
        return result;
    }

    private void deliverSubscription(
            KanbanNotifySubscriptionRecord subscription, KanbanNotificationResult result)
            throws Exception {
        KanbanNotifyClaim claim =
                repository.claimNotifyEvents(
                        subscription.getTaskId(),
                        subscription.getPlatform(),
                        subscription.getChatId(),
                        subscription.getThreadId(),
                        NOTIFY_KINDS);
        result.addClaimedEvents(claim.getEvents().size());
        if (claim.getEvents().isEmpty()) {
            return;
        }
        try {
            for (KanbanEventRecord event : claim.getEvents()) {
                deliverEvent(subscription, event);
                result.addDeliveredEvent();
                if (REMOVE_AFTER_KINDS.contains(event.getKind())) {
                    if (repository.removeNotifySubscription(
                            subscription.getTaskId(),
                            subscription.getPlatform(),
                            subscription.getChatId(),
                            subscription.getThreadId())) {
                        result.addRemovedSubscription();
                    }
                }
            }
        } catch (Exception e) {
            result.addFailedEvent();
            result.addError(subscription.getTaskId() + ": " + safeError(e));
            repository.rewindNotifyCursor(
                    subscription.getTaskId(),
                    subscription.getPlatform(),
                    subscription.getChatId(),
                    subscription.getThreadId(),
                    claim.getNewCursor(),
                    claim.getOldCursor());
        }
    }

    private void deliverEvent(KanbanNotifySubscriptionRecord subscription, KanbanEventRecord event)
            throws Exception {
        PlatformType platform = PlatformType.fromName(subscription.getPlatform());
        if (platform == null) {
            throw new IllegalArgumentException("unsupported platform: " + subscription.getPlatform());
        }
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(platform);
        request.setChatId(subscription.getChatId());
        request.setUserId(subscription.getUserId());
        request.setThreadId(StrUtil.blankToDefault(subscription.getThreadId(), null));
        request.setText(formatEvent(subscription, event));
        deliveryService.deliver(request);
    }

    @SuppressWarnings("unchecked")
    private String formatEvent(KanbanNotifySubscriptionRecord subscription, KanbanEventRecord event) {
        Map<String, Object> payload = null;
        try {
            Object data = StrUtil.isBlank(event.getPayloadJson()) ? null : ONode.ofJson(event.getPayloadJson()).toData();
            if (data instanceof Map<?, ?>) {
                payload = (Map<String, Object>) data;
            }
        } catch (Exception ignored) {
            payload = null;
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("Kanban task ").append(subscription.getTaskId()).append(" ");
        buffer.append(StrUtil.blankToDefault(event.getKind(), "updated"));
        String summary = payload == null ? null : safeText(payload.get("summary"));
        if (StrUtil.isBlank(summary) && payload != null) {
            summary = safeText(payload.get("reason"));
        }
        if (StrUtil.isBlank(summary) && payload != null) {
            summary = safeText(payload.get("error"));
        }
        if (StrUtil.isNotBlank(summary)) {
            buffer.append(": ").append(summary);
        }
        return buffer.toString();
    }

    private String safeText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        return StrUtil.blankToDefault(message, e.getClass().getSimpleName());
    }
}
