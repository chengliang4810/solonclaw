package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HEARTBEAT.md 固定间隔轮询调度器。 */
@RequiredArgsConstructor
public class HeartbeatScheduler {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    /** 心跳用户的统一常量值。 */
    private static final String HEARTBEAT_USER = "__heartbeat__";

    /** QUIETtoken的统一常量值。 */
    private static final String QUIET_TOKEN = "[SILENT]";

    /** 默认提示词的统一常量值。 */
    private static final String DEFAULT_PROMPT =
            "请阅读 HEARTBEAT.md 并严格执行其中的检查清单。如果没有任何需要关注的内容，只回复 [SILENT]。";

    /** 注入应用配置，用于心跳调度器。 */
    private final AppConfig appConfig;

    /** 保存消息网关策略仓储依赖，用于访问持久化数据。 */
    private final GatewayPolicyRepository gatewayPolicyRepository;

    /** 记录心跳调度器中的对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 注入persona工作区服务，用于调用对应业务能力。 */
    private final PersonaWorkspaceService personaWorkspaceService;

    /** 保存执行器服务执行组件，负责调度异步或定时任务。 */
    private ScheduledExecutorService executorService;

    /** 启动当前组件并准备运行资源。 */
    public void start() {
        int intervalMinutes = appConfig.getAgent().getHeartbeat().getIntervalMinutes();
        if (intervalMinutes <= 0) {
            return;
        }
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleWithFixedDelay(
                this::tickSafe, 30, intervalMinutes * 60L, TimeUnit.SECONDS);
    }

    /** 停止当前组件并释放运行状态。 */
    public void stop() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        stop();
    }

    /** 执行tick安全相关逻辑。 */
    public void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("Heartbeat tick failed: error={}", CronJobSupport.safeError(e));
        }
    }

    /** 执行tick相关逻辑。 */
    public void tick() throws Exception {
        int intervalMinutes = appConfig.getAgent().getHeartbeat().getIntervalMinutes();
        if (intervalMinutes <= 0) {
            log.debug("Heartbeat skipped: disabled");
            return;
        }
        if (!hasHeartbeatTasks()) {
            log.debug("Heartbeat skipped: HEARTBEAT.md has no runnable tasks");
            return;
        }

        log.info("Heartbeat tick started: intervalMinutes={}", intervalMinutes);
        tryRunForPlatform(PlatformType.FEISHU, appConfig.getChannels().getFeishu().isEnabled());
        tryRunForPlatform(PlatformType.DINGTALK, appConfig.getChannels().getDingtalk().isEnabled());
        tryRunForPlatform(PlatformType.WECOM, appConfig.getChannels().getWecom().isEnabled());
        tryRunForPlatform(PlatformType.WEIXIN, appConfig.getChannels().getWeixin().isEnabled());
    }

    /**
     * 执行try运行For平台相关逻辑。
     *
     * @param platform 平台参数。
     * @param enabled 启用状态开关值。
     */
    private void tryRunForPlatform(PlatformType platform, boolean enabled) throws Exception {
        if (!enabled) {
            log.debug("Heartbeat skipped for {}: channel disabled", platform);
            return;
        }
        HomeChannelRecord home = gatewayPolicyRepository.getHomeChannel(platform);
        if (home == null || StrUtil.isBlank(home.getChatId())) {
            log.debug("Heartbeat skipped for {}: home channel missing", platform);
            return;
        }
        runOnce(platform, home);
    }

    /**
     * 运行Once。
     *
     * @param platform 平台参数。
     * @param home 主渠道参数。
     */
    void runOnce(PlatformType platform, HomeChannelRecord home) throws Exception {
        log.info(
                "Heartbeat executing: platform={}, chatId={}, chatName={}",
                platform,
                home.getChatId(),
                StrUtil.blankToDefault(home.getChatName(), home.getChatId()));
        GatewayMessage message =
                new GatewayMessage(platform, home.getChatId(), HEARTBEAT_USER, DEFAULT_PROMPT);
        message.setHeartbeat(true);
        message.setThreadId(home.getThreadId());
        message.setChatName(home.getChatName());
        message.setUserName(HEARTBEAT_USER);
        message.setSourceKeyOverride(heartbeatSourceKey(platform, home));

        GatewayReply reply = conversationOrchestrator.runScheduled(message);
        if (!shouldDeliver(reply)) {
            log.info("Heartbeat quiet: platform={}, chatId={}", platform, home.getChatId());
            return;
        }

        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(platform);
        request.setChatId(home.getChatId());
        request.setThreadId(home.getThreadId());
        request.setText(reply.getContent());
        deliveryService.deliver(request);
        log.info(
                "Heartbeat delivered: platform={}, chatId={}, chars={}",
                platform,
                home.getChatId(),
                reply.getContent() == null ? 0 : reply.getContent().length());
    }

    /**
     * 判断是否需要Deliver。
     *
     * @param reply 回复参数。
     * @return 如果Deliver满足条件则返回 true，否则返回 false。
     */
    private boolean shouldDeliver(GatewayReply reply) {
        if (reply == null || StrUtil.isBlank(reply.getContent())) {
            return false;
        }
        return !QUIET_TOKEN.equalsIgnoreCase(StrUtil.nullToEmpty(reply.getContent()).trim());
    }

    /**
     * 执行心跳来源键相关逻辑。
     *
     * @param platform 平台参数。
     * @param home 主渠道参数。
     * @return 返回心跳来源键结果。
     */
    private String heartbeatSourceKey(PlatformType platform, HomeChannelRecord home) {
        StringBuilder key = new StringBuilder();
        key.append(platform.name()).append(":").append(home.getChatId()).append(":");
        if (StrUtil.isNotBlank(home.getThreadId())) {
            key.append(home.getThreadId().trim()).append(":");
        }
        key.append(HEARTBEAT_USER);
        return key.toString();
    }

    /**
     * 判断是否存在心跳Tasks。
     *
     * @return 如果心跳Tasks满足条件则返回 true，否则返回 false。
     */
    private boolean hasHeartbeatTasks() {
        String content = personaWorkspaceService.read(ContextFileConstants.KEY_HEARTBEAT);
        if (StrUtil.isBlank(content)) {
            return false;
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            String text = StrUtil.nullToEmpty(line).trim();
            if (text.length() == 0) {
                continue;
            }
            if (text.startsWith("#")) {
                continue;
            }
            return true;
        }
        return false;
    }
}
