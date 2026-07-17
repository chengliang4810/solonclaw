package com.jimuqu.solon.claw.gateway.delivery;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 基于渠道适配器集合实现的投递服务。 */
public class AdapterBackedDeliveryService implements DeliveryService {
    /** 后台消息会话回写失败时使用的日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AdapterBackedDeliveryService.class);

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 平台到适配器的映射。 */
    private final Map<PlatformType, ChannelAdapter> adapters;

    /** 网关授权策略仓储，用于解析 home channel。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /** 会话仓储，用于让 Agent 记住成功发送的后台消息。 */
    private final SessionRepository sessionRepository;

    /** 创建不启用会话回写的投递服务，供隔离测试或精简运行时使用。 */
    public AdapterBackedDeliveryService(
            AppConfig appConfig,
            Map<PlatformType, ChannelAdapter> adapters,
            GatewayPolicyRepository gatewayPolicyRepository) {
        this(appConfig, adapters, gatewayPolicyRepository, null);
    }

    /** 创建渠道投递服务，并注入可选的会话回写仓储。 */
    public AdapterBackedDeliveryService(
            AppConfig appConfig,
            Map<PlatformType, ChannelAdapter> adapters,
            GatewayPolicyRepository gatewayPolicyRepository,
            SessionRepository sessionRepository) {
        this.appConfig = appConfig;
        this.adapters = adapters;
        this.gatewayPolicyRepository = gatewayPolicyRepository;
        this.sessionRepository = sessionRepository;
    }

    /** 发送消息；若请求未指定 chatId，则回退到平台 home channel。 */
    @Override
    public void deliver(DeliveryRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("Delivery request is required.");
        }
        applyProfileBoundary(request);
        if (shouldFilterSilenceNarration(request)) {
            return;
        }

        if (StrUtil.isBlank(request.getChatId())) {
            HomeChannelRecord home =
                    request.getPlatform() == null
                            ? gatewayPolicyRepository.getPrimaryHomeChannel()
                            : gatewayPolicyRepository.getHomeChannel(request.getPlatform());
            if (home == null) {
                throw new IllegalStateException(
                        "No home channel configured for platform: " + request.getPlatform());
            }
            request.setPlatform(home.getPlatform());
            request.setChatId(home.getChatId());
            request.setThreadId(StrUtil.blankToDefault(request.getThreadId(), home.getThreadId()));
        }

        ChannelAdapter adapter = adapters.get(request.getPlatform());
        if (adapter == null) {
            throw new IllegalStateException("No adapter for platform: " + request.getPlatform());
        }

        adapter.send(request);
        recordDeliveredMessage(request);
    }

    /** 成功投递后台消息后，将正文回写到唯一匹配的普通会话。 */
    private void recordDeliveredMessage(DeliveryRequest request) {
        if (sessionRepository == null
                || request == null
                || !request.isRecordInConversation()
                || StrUtil.isBlank(request.getText())) {
            return;
        }
        try {
            sessionRepository.appendBoundOriginAssistantMessage(
                    request.getPlatform(),
                    request.getChatId(),
                    request.getThreadId(),
                    request.getUserId(),
                    request.getText());
        } catch (Exception e) {
            log.warn(
                    "Delivered message conversation write-back failed: platform={}, chatId={}, error={}",
                    request.getPlatform(),
                    request.getChatId(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 把空 Profile 补为当前运行时，并拒绝把命名 Profile 请求误投到另一组渠道凭据。
     *
     * @param request 待投递请求。
     */
    private void applyProfileBoundary(DeliveryRequest request) {
        String current = ProfileRuntimeIdentity.resolve(appConfig);
        String requested =
                StrUtil.nullToEmpty(request.getProfile()).trim().toLowerCase(Locale.ROOT);
        if (requested.length() == 0) {
            request.setProfile(current);
            return;
        }
        if (!current.equals(requested)) {
            throw new IllegalArgumentException(
                    "Delivery Profile '"
                            + requested
                            + "' does not match runtime Profile '"
                            + current
                            + "'.");
        }
        request.setProfile(requested);
    }

    /**
     * 判断是否需要丢弃模型生成的静默占位回复。
     *
     * @param request 待投递到国内消息渠道的请求。
     * @return 文本是静默占位且没有附件时返回 true。
     */
    private boolean shouldFilterSilenceNarration(DeliveryRequest request) {
        return appConfig != null
                && appConfig.getGateway().isFilterSilenceNarration()
                && !hasAttachments(request)
                && isSilenceNarration(request == null ? null : request.getText());
    }

    /**
     * 判断请求是否携带需要保留的附件。
     *
     * @param request 待投递请求。
     * @return 附件列表非空时返回 true。
     */
    private boolean hasAttachments(DeliveryRequest request) {
        return request != null && CollUtil.isNotEmpty(request.getAttachments());
    }

    /**
     * 判断短文本是否为“无需回复”的模型静默占位。
     *
     * @param content 模型输出或渠道待发送文本。
     * @return 命中静默占位词、单独省略号或静音符号时返回 true。
     */
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

    /**
     * 剥离 Markdown 或括号包裹，便于识别包在格式符内的静默占位词。
     *
     * @param value 待识别的短文本。
     * @return 去掉两端装饰符后的文本。
     */
    private static String stripSilenceDecorators(String value) {
        String result = StrUtil.nullToEmpty(value).trim();
        while (result.length() > 1 && isSilenceDecorator(result.charAt(0))) {
            result = result.substring(1).trim();
        }
        while (result.length() > 1 && isSilenceDecorator(result.charAt(result.length() - 1))) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    /**
     * 判断字符是否属于静默占位常见包裹符。
     *
     * @param value 待检测字符。
     * @return 是 Markdown、引号或括号装饰符时返回 true。
     */
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
