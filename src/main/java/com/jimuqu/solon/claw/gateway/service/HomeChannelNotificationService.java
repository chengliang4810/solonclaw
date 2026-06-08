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

/** 提供主渠道渠道Notification相关业务能力，封装调用方不需要感知的运行细节。 */
public class HomeChannelNotificationService {
    /** 注入应用配置，用于主渠道渠道Notification。 */
    private final AppConfig appConfig;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 保存主渠道Channels集合，维持调用顺序或去重语义。 */
    private final List<HomeChannelRecord> homeChannels =
            new CopyOnWriteArrayList<HomeChannelRecord>();

    /**
     * 创建主渠道渠道Notification服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     */
    public HomeChannelNotificationService(AppConfig appConfig, DeliveryService deliveryService) {
        this.appConfig = appConfig;
        this.deliveryService = deliveryService;
    }

    /**
     * 注册主渠道渠道。
     *
     * @param platform 平台参数。
     * @param chatId 聊天标识。
     * @param threadId thread标识。
     * @param chatName 聊天名称参数。
     */
    public void registerHomeChannel(
            PlatformType platform, String chatId, String threadId, String chatName) {
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

    /**
     * 取消注册主渠道渠道。
     *
     * @param platform 平台参数。
     * @param chatId 聊天标识。
     */
    public void unregisterHomeChannel(PlatformType platform, String chatId) {
        homeChannels.removeIf(r -> r.getPlatform() == platform && chatId.equals(r.getChatId()));
    }

    /**
     * 列出主渠道Channels。
     *
     * @return 返回主渠道Channels列表。
     */
    public List<HomeChannelRecord> listHomeChannels() {
        return new ArrayList<HomeChannelRecord>(homeChannels);
    }

    /**
     * 执行notify进程Started相关逻辑。
     *
     * @param processName 进程名称参数。
     * @param command 待执行或解析的命令文本。
     */
    public void notifyProcessStarted(String processName, String command) {
        notify("process_started", processName + " 已启动: " + truncate(command, 100));
    }

    /**
     * 执行notify进程Completed相关逻辑。
     *
     * @param processName 进程名称参数。
     * @param exitCode 命令退出码。
     */
    public void notifyProcessCompleted(String processName, int exitCode) {
        String status = exitCode == 0 ? "成功" : "失败(exit=" + exitCode + ")";
        notify("process_completed", processName + " 已完成: " + status);
    }

    /**
     * 执行notify进程Failed相关逻辑。
     *
     * @param processName 进程名称参数。
     * @param error 错误参数。
     */
    public void notifyProcessFailed(String processName, String error) {
        notify("process_failed", processName + " 执行失败: " + truncate(error, 200));
    }

    /**
     * 执行notify定时任务结果相关逻辑。
     *
     * @param jobName job名称参数。
     * @param status 状态参数。
     * @param summary 摘要参数。
     */
    public void notifyCronResult(String jobName, String status, String summary) {
        notify("cron_result", "定时任务 [" + jobName + "] " + status + ": " + truncate(summary, 150));
    }

    /**
     * 执行notify系统错误相关逻辑。
     *
     * @param component component 参数。
     * @param error 错误参数。
     */
    public void notifySystemError(String component, String error) {
        notify("system_error", "系统错误 [" + component + "]: " + truncate(error, 200));
    }

    /**
     * 执行notifyCustom相关逻辑。
     *
     * @param eventType 事件类型参数。
     * @param message 平台消息或错误消息。
     */
    public void notifyCustom(String eventType, String message) {
        notify(eventType, message);
    }

    /**
     * 执行状态相关逻辑。
     *
     * @return 返回状态。
     */
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

    /**
     * 执行notify相关逻辑。
     *
     * @param eventType 事件类型参数。
     * @param message 平台消息或错误消息。
     */
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

    /**
     * 执行truncate相关逻辑。
     *
     * @param text 待处理文本。
     * @param maxLen 最大保留字符数。
     * @return 返回truncate结果。
     */
    private String truncate(String text, int maxLen) {
        String value = StrUtil.nullToEmpty(text).trim();
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }
}
