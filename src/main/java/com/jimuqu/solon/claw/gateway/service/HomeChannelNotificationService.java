package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Home channel notification service.
 * Sends system notifications (process lifecycle, cron results, errors) to configured home channels.
 */
public class HomeChannelNotificationService {
    private final AppConfig appConfig;
    private final DeliveryService deliveryService;
    private final List<HomeChannelRecord> homeChannels = new CopyOnWriteArrayList<HomeChannelRecord>();

    public HomeChannelNotificationService(AppConfig appConfig, DeliveryService deliveryService) {
        this.appConfig = appConfig;
        this.deliveryService = deliveryService;
    }

    public void registerHomeChannel(PlatformType platform, String chatId, String threadId, String chatName) {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(platform);
        record.setChatId(chatId);
        record.setThreadId(threadId);
        record.setChatName(StrUtil.blankToDefault(chatName, chatId));
        record.setUpdatedAt(System.currentTimeMillis());
        for (int i = 0; i < homeChannels.size(); i++) {
            HomeChannelRecord existing = homeChannels.get(i);
            if (existing.getPlatform() == platform && chatId.equals(existing.getChatId())) {
                homeChannels.set(i, record);
                return;
            }
        }
        homeChannels.add(record);
    }

    public void unregisterHomeChannel(PlatformType platform, String chatId) {
        homeChannels.removeIf(r -> r.getPlatform() == platform && chatId.equals(r.getChatId()));
    }

    public List<HomeChannelRecord> listHomeChannels() {
        return new ArrayList<HomeChannelRecord>(homeChannels);
    }

    public void notifyProcessStarted(String processName, String command) {
        notify("process_started", processName + " 已启动: " + truncate(command, 100));
    }

    public void notifyProcessCompleted(String processName, int exitCode) {
        String status = exitCode == 0 ? "成功" : "失败(exit=" + exitCode + ")";
        notify("process_completed", processName + " 已完成: " + status);
    }

    public void notifyProcessFailed(String processName, String error) {
        notify("process_failed", processName + " 执行失败: " + truncate(error, 200));
    }

    public void notifyCronResult(String jobName, String status, String summary) {
        notify("cron_result", "定时任务 [" + jobName + "] " + status + ": " + truncate(summary, 150));
    }

    public void notifySystemError(String component, String error) {
        notify("system_error", "系统错误 [" + component + "]: " + truncate(error, 200));
    }

    public void notifyCustom(String eventType, String message) {
        notify(eventType, message);
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("registeredChannels", Integer.valueOf(homeChannels.size()));
        List<Map<String, Object>> channels = new ArrayList<Map<String, Object>>();
        for (HomeChannelRecord record : homeChannels) {
            Map<String, Object> ch = new LinkedHashMap<String, Object>();
            ch.put("platform", record.getPlatform().name());
            ch.put("chatId", record.getChatId());
            ch.put("chatName", record.getChatName());
            channels.add(ch);
        }
        result.put("channels", channels);
        return result;
    }

    private void notify(String eventType, String message) {
        if (homeChannels.isEmpty() || deliveryService == null) {
            return;
        }
        String formatted = "[" + eventType + "] " + message;
        for (HomeChannelRecord channel : homeChannels) {
            try {
                DeliveryRequest request = new DeliveryRequest();
                request.setPlatform(channel.getPlatform());
                request.setChatId(channel.getChatId());
                request.setThreadId(channel.getThreadId());
                request.setText(formatted);
                deliveryService.deliver(request);
            } catch (Exception ignored) {
            }
        }
    }

    private String truncate(String text, int maxLen) {
        String value = StrUtil.nullToEmpty(text).trim();
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }
}
