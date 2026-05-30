package com.jimuqu.solon.claw.gateway.delivery;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/** 基于渠道适配器集合实现的投递服务。 */
@RequiredArgsConstructor
public class AdapterBackedDeliveryService implements DeliveryService {
    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 平台到适配器的映射。 */
    private final Map<PlatformType, ChannelAdapter> adapters;

    /** 网关授权策略仓储，用于解析 home channel。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /** 发送消息；若请求未指定 chatId，则回退到平台 home channel。 */
    @Override
    public void deliver(DeliveryRequest request) throws Exception {
        if (shouldFilterSilenceNarration(request)) {
            return;
        }

        if (StrUtil.isBlank(request.getChatId())) {
            HomeChannelRecord home = gatewayPolicyRepository.getHomeChannel(request.getPlatform());
            if (home == null) {
                throw new IllegalStateException(
                        "No home channel configured for platform: " + request.getPlatform());
            }
            request.setChatId(home.getChatId());
            request.setThreadId(StrUtil.blankToDefault(request.getThreadId(), home.getThreadId()));
        }

        ChannelAdapter adapter = adapters.get(request.getPlatform());
        if (adapter == null) {
            throw new IllegalStateException("No adapter for platform: " + request.getPlatform());
        }

        adapter.send(request);
    }

    private boolean shouldFilterSilenceNarration(DeliveryRequest request) {
        return appConfig != null
                && appConfig.getGateway().isFilterSilenceNarration()
                && !hasAttachments(request)
                && isSilenceNarration(request == null ? null : request.getText());
    }

    private boolean hasAttachments(DeliveryRequest request) {
        if (request == null) {
            return false;
        }
        List<MessageAttachment> attachments = request.getAttachments();
        return attachments != null && !attachments.isEmpty();
    }

    static boolean isSilenceNarration(String content) {
        if (StrUtil.isBlank(content)) {
            return false;
        }
        String value = content.trim();
        if (value.length() > 64) {
            return false;
        }
        if ("🔇".equals(value)) {
            return true;
        }
        if (value.matches("[.。…]+")) {
            return true;
        }
        String normalized = stripSilenceDecorators(value).toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[.。!！?？]+$", "").trim();
        return "silent".equals(normalized)
                || "silence".equals(normalized)
                || "no response".equals(normalized)
                || "no reply".equals(normalized);
    }

    private static String stripSilenceDecorators(String value) {
        String result = value == null ? "" : value.trim();
        while (result.length() > 1 && isSilenceDecorator(result.charAt(0))) {
            result = result.substring(1).trim();
        }
        while (result.length() > 1 && isSilenceDecorator(result.charAt(result.length() - 1))) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean isSilenceDecorator(char value) {
        return value == '`'
                || value == '*'
                || value == '_'
                || value == '~'
                || value == '('
                || value == ')'
                || value == '['
                || value == ']'
                || value == '{'
                || value == '}'
                || value == '"'
                || value == '\'';
    }

    /** 汇总全部渠道状态。 */
    @Override
    public List<ChannelStatus> statuses() {
        List<ChannelStatus> items = new ArrayList<ChannelStatus>();
        for (ChannelAdapter adapter : adapters.values()) {
            items.add(adapter.statusSnapshot());
        }
        return items;
    }
}
