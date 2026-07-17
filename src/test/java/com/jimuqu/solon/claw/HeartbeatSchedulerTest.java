package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovedUserRecord;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRateLimitRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestAdmissionResult;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.scheduler.HeartbeatScheduler;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HeartbeatSchedulerTest {
    @TempDir Path tempDir;

    @Test
    void shouldDefaultHeartbeatIntervalToFifteenMinutes() {
        AppConfig config = new AppConfig();

        assertThat(config.getAgent().getHeartbeat().getIntervalMinutes()).isEqualTo(15);
    }

    @Test
    void shouldNotRunWhenDisabled() throws Exception {
        AppConfig config = config();
        config.getAgent().getHeartbeat().setIntervalMinutes(0);
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        new InMemoryGatewayPolicyRepository(),
                        orchestrator,
                        new CapturingDeliveryService(),
                        workspace(config, "- 检查事项"));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(0);
    }

    @Test
    void shouldSkipWhenHeartbeatFileHasOnlyComments() throws Exception {
        AppConfig config = config();
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        repository.saveHomeChannel(home(PlatformType.FEISHU, "chat-1"));
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        new CapturingDeliveryService(),
                        workspace(config, "# 注释一行\n# 再来一行注释"));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(0);
    }

    /** 默认模板只有元数据、标题和注释时，不应发起心跳模型调用。 */
    @Test
    void shouldSkipBundledHeartbeatTemplateWithoutTasks() throws Exception {
        AppConfig config = config();
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        repository.saveHomeChannel(home(PlatformType.FEISHU, "chat-1"));
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        new CapturingDeliveryService(),
                        new PersonaWorkspaceService(config));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(0);
    }

    /** 兼容旧模板的 YAML 元数据和多行 HTML 注释，不把说明内容误判为任务。 */
    @Test
    void shouldSkipLegacyTemplateMetadataAndComments() throws Exception {
        AppConfig config = config();
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        repository.saveHomeChannel(home(PlatformType.FEISHU, "chat-1"));
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        String template =
                "---\nsummary: heartbeat template\n---\n# HEARTBEAT.md\n"
                        + "<!-- 默认没有任务\n需要时再添加 -->";

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        new CapturingDeliveryService(),
                        workspace(config, template));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(0);
    }

    /** 普通段落和编号步骤都是有效心跳任务，不能只识别 Markdown 项目符号。 */
    @Test
    void shouldRunHeartbeatForSectionedNumberedTasks() throws Exception {
        AppConfig config = config();
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        repository.saveHomeChannel(home(PlatformType.FEISHU, "chat-1"));
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        String tasks = "# HEARTBEAT.md\n\n## 日志巡检\n\n每次心跳时执行：\n\n1. 查看日志\n2. 发现异常时报告";

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        new CapturingDeliveryService(),
                        workspace(config, tasks));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(1);
    }

    @Test
    void shouldDeliverNonQuietReplyToHomeChannel() throws Exception {
        AppConfig config = config();
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        repository.saveHomeChannel(home(PlatformType.FEISHU, "chat-1"));
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        orchestrator.reply = GatewayReply.ok("今天需要提醒用户处理一件事");
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        deliveryService,
                        workspace(config, "- 检查今天是否有需要提醒的事项"));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(1);
        assertThat(orchestrator.lastMessage).isNotNull();
        assertThat(orchestrator.lastMessage.isHeartbeat()).isTrue();
        assertThat(orchestrator.lastMessage.sourceKey()).isEqualTo("FEISHU:chat-1:__heartbeat__");
        assertThat(deliveryService.requests).hasSize(1);
        assertThat(deliveryService.requests.get(0).getPlatform()).isEqualTo(PlatformType.FEISHU);
        assertThat(deliveryService.requests.get(0).getChatId()).isEqualTo("chat-1");
        assertThat(deliveryService.requests.get(0).getText()).isEqualTo("今天需要提醒用户处理一件事");
        assertThat(deliveryService.requests.get(0).isRecordInConversation()).isTrue();
    }

    /** 多个平台都有 home channel 时只运行显式选择的主要通知渠道。 */
    @Test
    void shouldRunOnlyPrimaryHomeChannel() throws Exception {
        AppConfig config = config();
        config.getChannels().getDingtalk().setEnabled(true);
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        repository.saveHomeChannel(home(PlatformType.FEISHU, "feishu-home"));
        HomeChannelRecord primary = home(PlatformType.DINGTALK, "dingtalk-primary");
        primary.setPrimary(true);
        repository.saveHomeChannel(primary);
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        orchestrator.reply = GatewayReply.ok("只发送一次");
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        deliveryService,
                        workspace(config, "- 检查今天是否有需要提醒的事项"));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(1);
        assertThat(deliveryService.requests).hasSize(1);
        assertThat(deliveryService.requests.get(0).getPlatform()).isEqualTo(PlatformType.DINGTALK);
        assertThat(deliveryService.requests.get(0).getChatId()).isEqualTo("dingtalk-primary");
    }

    @Test
    void shouldPreserveHomeChannelThreadForSyntheticMessageAndDelivery() throws Exception {
        AppConfig config = config();
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        HomeChannelRecord home = home(PlatformType.FEISHU, "chat-1");
        home.setThreadId("topic-7");
        repository.saveHomeChannel(home);
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        orchestrator.reply = GatewayReply.ok("线程提醒");
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        deliveryService,
                        workspace(config, "- 检查今天是否有需要提醒的事项"));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(1);
        assertThat(orchestrator.lastMessage).isNotNull();
        assertThat(orchestrator.lastMessage.getThreadId()).isEqualTo("topic-7");
        assertThat(orchestrator.lastMessage.sourceKey())
                .isEqualTo("FEISHU:chat-1:topic-7:__heartbeat__");
        assertThat(deliveryService.requests).hasSize(1);
        assertThat(deliveryService.requests.get(0).getThreadId()).isEqualTo("topic-7");
        assertThat(deliveryService.requests.get(0).getText()).isEqualTo("线程提醒");
    }

    @Test
    void shouldSkipDeliveryForQuietToken() throws Exception {
        AppConfig config = config();
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        repository.saveHomeChannel(home(PlatformType.WECOM, "chat-2"));
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        orchestrator.reply = GatewayReply.ok("[SILENT]");
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        deliveryService,
                        workspace(config, "- 检查并在有结果时提醒"));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(1);
        assertThat(deliveryService.requests).isEmpty();
    }

    /** Agent 在内部总结后以静默标记收尾时，整条心跳结果都不投递。 */
    @Test
    void shouldSkipDeliveryWhenLastNonBlankLineIsQuietToken() throws Exception {
        AppConfig config = config();
        InMemoryGatewayPolicyRepository repository = new InMemoryGatewayPolicyRepository();
        repository.saveHomeChannel(home(PlatformType.WECOM, "chat-2"));
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        orchestrator.reply =
                GatewayReply.ok("errors.log 无新增错误，均属已知噪声。技能巡检和自我进化均未触发。系统正常。\n\n[SILENT]\n");
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        repository,
                        orchestrator,
                        deliveryService,
                        workspace(config, "- 检查并在有结果时提醒"));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(1);
        assertThat(deliveryService.requests).isEmpty();
    }

    @Test
    void shouldNotFailWithoutHomeChannel() throws Exception {
        AppConfig config = config();
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();

        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        config,
                        new InMemoryGatewayPolicyRepository(),
                        orchestrator,
                        deliveryService,
                        workspace(config, "- 检查并在有结果时提醒"));

        scheduler.tick();

        assertThat(orchestrator.calls).isEqualTo(0);
        assertThat(deliveryService.requests).isEmpty();
    }

    private AppConfig config() {
        AppConfig config = new AppConfig();
        File runtimeDir = new File(tempDir.toFile(), "runtime");
        config.getRuntime().setHome(runtimeDir.getAbsolutePath());
        config.getRuntime().setContextDir(new File(runtimeDir, "context").getAbsolutePath());
        config.getWorkspace().setDir(new File(tempDir.toFile(), "workspace").getAbsolutePath());
        config.getAgent().getHeartbeat().setIntervalMinutes(30);
        config.getChannels().getFeishu().setEnabled(true);
        config.getChannels().getWecom().setEnabled(true);
        return config;
    }

    private PersonaWorkspaceService workspace(AppConfig config, String heartbeatContent) {
        PersonaWorkspaceService service = new PersonaWorkspaceService(config);
        service.write("heartbeat", heartbeatContent);
        return service;
    }

    private HomeChannelRecord home(PlatformType platform, String chatId) {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(platform);
        record.setChatId(chatId);
        record.setChatName(chatId);
        record.setUpdatedAt(System.currentTimeMillis());
        return record;
    }

    private static class CapturingOrchestrator implements ConversationOrchestrator {
        private int calls;
        private GatewayMessage lastMessage;
        private GatewayReply reply = GatewayReply.ok("[SILENT]");

        @Override
        public GatewayReply handleIncoming(GatewayMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
            calls++;
            lastMessage = syntheticMessage;
            return reply;
        }

        @Override
        public GatewayReply resumePending(String sourceKey) {
            throw new UnsupportedOperationException();
        }
    }

    private static class CapturingDeliveryService implements DeliveryService {
        private final List<DeliveryRequest> requests = new ArrayList<DeliveryRequest>();

        @Override
        public void deliver(DeliveryRequest request) {
            requests.add(request);
        }

        @Override
        public List<ChannelStatus> statuses() {
            return new ArrayList<ChannelStatus>();
        }
    }

    private static class InMemoryGatewayPolicyRepository implements GatewayPolicyRepository {
        private final Map<PlatformType, HomeChannelRecord> homes =
                new LinkedHashMap<PlatformType, HomeChannelRecord>();

        @Override
        public HomeChannelRecord getHomeChannel(PlatformType platform) {
            return homes.get(platform);
        }

        @Override
        public void saveHomeChannel(HomeChannelRecord record) {
            homes.put(record.getPlatform(), record);
        }

        @Override
        public PlatformAdminRecord getPlatformAdmin(PlatformType platform) {
            return null;
        }

        @Override
        public boolean createPlatformAdminIfAbsent(PlatformAdminRecord record) {
            return false;
        }

        @Override
        public ApprovedUserRecord getApprovedUser(PlatformType platform, String userId) {
            return null;
        }

        @Override
        public void saveApprovedUser(ApprovedUserRecord record) {}

        @Override
        public void revokeApprovedUser(PlatformType platform, String userId) {}

        @Override
        public List<ApprovedUserRecord> listApprovedUsers(PlatformType platform) {
            return new ArrayList<ApprovedUserRecord>();
        }

        @Override
        public int countApprovedUsers(PlatformType platform) {
            return 0;
        }

        @Override
        public PairingRequestRecord getPairingRequest(PlatformType platform, String code) {
            return null;
        }

        @Override
        public PairingRequestRecord getLatestUserPairingRequest(
                PlatformType platform, String userId) {
            return null;
        }

        @Override
        public void savePairingRequest(PairingRequestRecord record) {}

        /** 心跳测试仓储不承载 pairing 准入能力，调用时明确失败而不是使用非原子默认实现。 */
        @Override
        public PairingRequestAdmissionResult admitPairingRequest(
                PairingRequestRecord record,
                long nowEpochMillis,
                int maxPending,
                long rateLimitMillis) {
            throw new UnsupportedOperationException("心跳测试仓储不支持 pairing 准入。");
        }

        @Override
        public void deletePairingRequest(PlatformType platform, String code) {}

        @Override
        public void deleteExpiredPairingRequests(PlatformType platform, long nowEpochMillis) {}

        @Override
        public List<PairingRequestRecord> listPairingRequests(PlatformType platform) {
            return new ArrayList<PairingRequestRecord>();
        }

        @Override
        public PairingRateLimitRecord getPairingRateLimit(PlatformType platform, String userId) {
            return null;
        }

        @Override
        public void savePairingRateLimit(PairingRateLimitRecord record) {}
    }
}
