package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.service.DeliveryService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 维护 home channel 列表，并向已登记的国内消息渠道推送运行通知。 */
public class HomeChannelNotificationService {
    /** 应用配置引用，保留给后续按配置控制通知类型使用。 */
    private final AppConfig appConfig;

    /** 投递服务，用于把运行事件发送到具体渠道适配器。 */
    private final DeliveryService deliveryService;

    /** 已登记的 home channel，使用写时复制列表避免通知遍历时被注册操作打断。 */
    private final List<HomeChannelRecord> homeChannels =
            new CopyOnWriteArrayList<HomeChannelRecord>();

    /**
     * 创建 home channel 通知服务。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     */
    public HomeChannelNotificationService(AppConfig appConfig, DeliveryService deliveryService) {
        this.appConfig = appConfig;
        this.deliveryService = deliveryService;
    }

    /**
     * 注册或更新平台 home channel。
     *
     * @param platform 国内消息平台。
     * @param chatId 平台会话标识。
     * @param threadId 平台线程标识，可为空。
     * @param chatName 展示用会话名称，空白时回退为 chatId。
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
     * 取消已登记的 home channel。
     *
     * @param platform 国内消息平台。
     * @param chatId 平台会话标识。
     */
    public void unregisterHomeChannel(PlatformType platform, String chatId) {
        homeChannels.removeIf(r -> r.getPlatform() == platform && chatId.equals(r.getChatId()));
    }

    /**
     * 列出当前已登记的 home channel 快照。
     *
     * @return 新列表，调用方修改不会影响内部注册表。
     */
    public List<HomeChannelRecord> listHomeChannels() {
        return CollUtil.newArrayList(homeChannels);
    }

    /**
     * 通知 home channel：后台进程已经启动。
     *
     * @param processName 进程名称。
     * @param command 待执行或解析的命令文本。
     */
    public void notifyProcessStarted(String processName, String command) {
        notify("process_started", processName + " 已启动: " + truncate(command, 100));
    }

    /**
     * 通知 home channel：后台进程已经结束。
     *
     * @param processName 进程名称。
     * @param exitCode 命令退出码。
     */
    public void notifyProcessCompleted(String processName, int exitCode) {
        String status = exitCode == 0 ? "成功" : "失败(exit=" + exitCode + ")";
        notify("process_completed", processName + " 已完成: " + status);
    }

    /**
     * 通知 home channel：后台进程执行失败。
     *
     * @param processName 进程名称。
     * @param error 错误摘要。
     */
    public void notifyProcessFailed(String processName, String error) {
        notify("process_failed", processName + " 执行失败: " + truncate(error, 200));
    }

    /**
     * 通知 home channel：定时任务执行结果。
     *
     * @param jobName 定时任务名称。
     * @param status 执行状态。
     * @param summary 结果摘要。
     */
    public void notifyCronResult(String jobName, String status, String summary) {
        notify("cron_result", "定时任务 [" + jobName + "] " + status + ": " + truncate(summary, 150));
    }

    /**
     * 通知 home channel：系统组件出现错误。
     *
     * @param component 组件名称。
     * @param error 错误摘要。
     */
    public void notifySystemError(String component, String error) {
        notify("system_error", "系统错误 [" + component + "]: " + truncate(error, 200));
    }

    /**
     * 发送自定义 home channel 通知。
     *
     * @param eventType 事件类型，会作为通知前缀展示。
     * @param message 通知正文。
     */
    public void notifyCustom(String eventType, String message) {
        notify(eventType, message);
    }

    /**
     * 输出 home channel 注册状态，供 dashboard 诊断展示。
     *
     * @return 已登记数量和渠道列表。
     */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("registeredChannels", Integer.valueOf(homeChannels.size()));
        List<Map<String, Object>> channels = CollUtil.newArrayList();
        for (HomeChannelRecord record : homeChannels) {
            channels.add(channelStatus(record));
        }
        result.put("channels", channels);
        return result;
    }

    /**
     * 向全部 home channel 广播一条事件通知。
     *
     * @param eventType 事件类型，保留在正文前缀中。
     * @param message 通知正文。
     */
    private void notify(String eventType, String message) {
        if (CollUtil.isEmpty(homeChannels) || deliveryService == null) {
            return;
        }
        String formatted = "[" + eventType + "] " + message;
        for (HomeChannelRecord channel : homeChannels) {
            try {
                deliveryService.deliver(notificationRequest(channel, formatted));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 构造单个 home channel 的状态行。
     *
     * @param record home channel 注册记录。
     * @return dashboard 可序列化的状态映射。
     */
    private Map<String, Object> channelStatus(HomeChannelRecord record) {
        Map<String, Object> ch = new LinkedHashMap<String, Object>();
        ch.put("platform", record.getPlatform().name());
        ch.put("chatId", record.getChatId());
        ch.put("chatName", record.getChatName());
        return ch;
    }

    /**
     * 构造投递到指定 home channel 的通知请求。
     *
     * @param channel home channel 注册记录。
     * @param text 已拼好事件前缀的通知文本。
     * @return 可直接交给投递服务的请求。
     */
    private DeliveryRequest notificationRequest(HomeChannelRecord channel, String text) {
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(channel.getPlatform());
        request.setChatId(channel.getChatId());
        request.setThreadId(channel.getThreadId());
        request.setText(text);
        return request;
    }

    /**
     * 截断运行事件摘要，避免通知消息过长。
     *
     * @param text 待处理文本。
     * @param maxLen 最大保留字符数。
     * @return 不超过限制的文本，超出时追加省略号。
     */
    private String truncate(String text, int maxLen) {
        String value = StrUtil.nullToEmpty(text).trim();
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }
}
