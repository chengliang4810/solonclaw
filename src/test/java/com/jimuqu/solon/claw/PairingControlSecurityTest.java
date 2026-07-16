package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRateLimitRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestAdmissionResult;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGatewayPolicyRepository;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.PairingConstants;
import com.jimuqu.solon.claw.web.DashboardPairingService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 验证可信 pairing 控制面不会把临时凭据明文落库。 */
public class PairingControlSecurityTest {
    /** pairing code 必须盐化存储，并只能由正确明文绑定唯一主人。 */
    @Test
    void shouldHashPairingCodeAndApproveThroughTrustedControl() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-control"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);
            PairingRequestRecord request = request("ABCD2345");
            repository.savePairingRequest(request);

            assertThat(storedCode(database))
                    .startsWith("pbkdf2-sha256$")
                    .doesNotContain("ABCD2345");
            assertThat(repository.getPairingRequest(PlatformType.WEIXIN, "WRONG234")).isNull();

            assertThat(authorization.claimPairingOwner(PlatformType.WEIXIN, "ABCD2345").getUserId())
                    .isEqualTo("wx-user");
            assertThat(repository.listPairingRequests(PlatformType.WEIXIN)).isEmpty();
            assertThat(repository.getPlatformAdmin(PlatformType.WEIXIN).getUserId())
                    .isEqualTo("wx-user");
        } finally {
            database.shutdown();
        }
    }

    /** 首次可信绑定必须原子创建主人、默认私聊并消费 pairing 请求。 */
    @Test
    void shouldClaimFirstPairingUserAsOwnerAndHomeChannel() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-owner"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);
            repository.savePairingRequest(request("OWNER234"));

            assertThat(authorization.claimPairingOwner(PlatformType.WEIXIN, "OWNER234").getUserId())
                    .isEqualTo("wx-user");
            assertThat(repository.getPlatformAdmin(PlatformType.WEIXIN).getChatId())
                    .isEqualTo("wx-chat");
            assertThat(repository.getHomeChannel(PlatformType.WEIXIN).getChatId())
                    .isEqualTo("wx-chat");
            assertThat(repository.getHomeChannel(PlatformType.WEIXIN).isPrimary()).isTrue();
            assertThat(repository.listPairingRequests(PlatformType.WEIXIN)).isEmpty();
            assertThat(repository.getApprovedUser(PlatformType.WEIXIN, "wx-user")).isNull();
        } finally {
            database.shutdown();
        }
    }

    /** 清除主人时必须删除旧私聊，且不得自动改选其他通知渠道。 */
    @Test
    void shouldRemoveOwnerHomeWithoutPromotingAnotherChannel() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-owner-clear"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);
            repository.savePairingRequest(request("WEIXIN23"));
            repository.savePairingRequest(
                    request(PlatformType.FEISHU, "FEISHU23", "fs-user", "fs-chat"));
            authorization.claimPairingOwner(PlatformType.WEIXIN, "WEIXIN23");
            authorization.claimPairingOwner(PlatformType.FEISHU, "FEISHU23");

            authorization.clearPlatformAdmin(PlatformType.WEIXIN);

            assertThat(repository.getPlatformAdmin(PlatformType.WEIXIN)).isNull();
            assertThat(repository.getHomeChannel(PlatformType.WEIXIN)).isNull();
            assertThat(repository.getPrimaryHomeChannel()).isNull();
            assertThat(repository.getHomeChannel(PlatformType.FEISHU).isPrimary()).isFalse();
        } finally {
            database.shutdown();
        }
    }

    /** 未绑定平台管理员的孤立渠道不得成为任何通知策略的投递目标。 */
    @Test
    void shouldHideHomeChannelWithoutPlatformAdmin() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-orphan-home"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            HomeChannelRecord orphan = new HomeChannelRecord();
            orphan.setPlatform(PlatformType.FEISHU);
            orphan.setChatId("orphan-chat");
            orphan.setPrimary(true);
            orphan.setUpdatedAt(System.currentTimeMillis());
            repository.saveHomeChannel(orphan);

            assertThat(repository.getHomeChannel(PlatformType.FEISHU)).isNull();
            assertThat(repository.getPrimaryHomeChannel()).isNull();
            assertThat(repository.listHomeChannels()).isEmpty();
        } finally {
            database.shutdown();
        }
    }

    /** pairing 请求并不存在时必须回滚同一事务内已尝试写入的主人和默认私聊。 */
    @Test
    void shouldRollbackOwnerAndHomeWhenPairingConsumptionFails() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-owner-rollback"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            PairingRequestRecord missing = request("MISSING2");
            PlatformAdminRecord admin = new PlatformAdminRecord();
            admin.setPlatform(PlatformType.WEIXIN);
            admin.setUserId(missing.getUserId());
            admin.setChatId(missing.getChatId());
            admin.setCreatedAt(System.currentTimeMillis());
            HomeChannelRecord home = new HomeChannelRecord();
            home.setPlatform(PlatformType.WEIXIN);
            home.setChatId(missing.getChatId());
            home.setUpdatedAt(System.currentTimeMillis());

            assertThatThrownBy(() -> repository.claimPlatformAdminIfAbsent(missing, admin, home))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已被消费");
            assertThat(repository.getPlatformAdmin(PlatformType.WEIXIN)).isNull();
            assertThat(repository.getHomeChannel(PlatformType.WEIXIN)).isNull();
        } finally {
            database.shutdown();
        }
    }

    /** 已有主人时可信控制面也不得抢占，原主人与默认私聊必须保持不变。 */
    @Test
    void shouldRejectOwnerTakeoverWhenPlatformAlreadyClaimed() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-owner-takeover"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);
            repository.savePairingRequest(request("FIRST234", "first-owner"));
            authorization.claimPairingOwner(PlatformType.WEIXIN, "FIRST234");
            repository.savePairingRequest(request("SECOND23", "second-owner"));

            assertThatThrownBy(
                            () -> authorization.claimPairingOwner(PlatformType.WEIXIN, "SECOND23"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已绑定主人");
            assertThat(repository.getPlatformAdmin(PlatformType.WEIXIN).getUserId())
                    .isEqualTo("first-owner");
            assertThat(repository.getHomeChannel(PlatformType.WEIXIN).getChatId())
                    .isEqualTo("wx-chat");
            assertThat(repository.getPairingRequest(PlatformType.WEIXIN, "SECOND23")).isNotNull();
        } finally {
            database.shutdown();
        }
    }

    /** Dashboard 首次绑定成功后必须立即使用当前 Profile 的渠道投递欢迎语。 */
    @Test
    void shouldDeliverWelcomeAfterDashboardOwnerClaim() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-owner-welcome"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);
            repository.savePairingRequest(request("HELLO234"));
            AtomicReference<DeliveryRequest> delivered = new AtomicReference<DeliveryRequest>();
            DashboardPairingService service =
                    new DashboardPairingService(
                            authorization,
                            new DashboardProfileContext(ProfileManager.current(), config),
                            new DeliveryService() {
                                /** 记录欢迎消息投递请求。 */
                                @Override
                                public void deliver(DeliveryRequest request) {
                                    delivered.set(request);
                                }

                                /** 测试投递器不连接真实渠道。 */
                                @Override
                                public List<ChannelStatus> statuses() {
                                    return Collections.emptyList();
                                }
                            },
                            null);

            Map<String, Object> result = service.claimOwner(null, "weixin", "HELLO234");

            assertThat(delivered.get().getPlatform()).isEqualTo(PlatformType.WEIXIN);
            assertThat(delivered.get().getChatId()).isEqualTo("wx-chat");
            assertThat(delivered.get().getChatType())
                    .isEqualTo(GatewayBehaviorConstants.CHAT_TYPE_DM);
            assertThat(delivered.get().getText()).contains("准备好了");
            assertThat(welcome(result).get("status")).isEqualTo("sent");
        } finally {
            database.shutdown();
        }
    }

    /** 首次欢迎语失败后只能按已绑定主人记录重发，不能信任客户端投递目标。 */
    @Test
    void shouldReportWelcomeFailureAndRetryBoundOwnerDm() throws Exception {
        AppConfig config =
                config(Files.createTempDirectory("solonclaw-pairing-owner-welcome-retry"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);
            repository.savePairingRequest(request("RETRY234"));
            AtomicInteger attempts = new AtomicInteger();
            AtomicReference<DeliveryRequest> delivered = new AtomicReference<DeliveryRequest>();
            DashboardPairingService service =
                    new DashboardPairingService(
                            authorization,
                            new DashboardProfileContext(ProfileManager.current(), config),
                            new DeliveryService() {
                                /** 首次模拟渠道失败，第二次记录服务端构造的欢迎语请求。 */
                                @Override
                                public void deliver(DeliveryRequest request) throws Exception {
                                    if (attempts.getAndIncrement() == 0) {
                                        throw new IllegalStateException("channel unavailable");
                                    }
                                    delivered.set(request);
                                }

                                /** 测试投递器不连接真实渠道。 */
                                @Override
                                public List<ChannelStatus> statuses() {
                                    return Collections.emptyList();
                                }
                            },
                            null);

            Map<String, Object> claim = service.claimOwner(null, "weixin", "RETRY234");
            Map<String, Object> retried = service.retryWelcome(null, "weixin");

            assertThat(welcome(claim).get("status")).isEqualTo("failed");
            assertThat(welcome(retried).get("status")).isEqualTo("sent");
            assertThat(delivered.get().getChatId()).isEqualTo("wx-chat");
            assertThat(delivered.get().getUserId()).isEqualTo("wx-user");
            assertThat(delivered.get().getChatType())
                    .isEqualTo(GatewayBehaviorConstants.CHAT_TYPE_DM);
        } finally {
            database.shutdown();
        }
    }

    /** 可信控制面只能把已经绑定主人的平台设为主要通知渠道。 */
    @Test
    void shouldRequireBoundOwnerBeforeSettingPrimaryChannel() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-primary-owner"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);

            assertThatThrownBy(() -> authorization.setPrimaryHomeChannel(PlatformType.WEIXIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("尚未绑定主人");

            repository.savePairingRequest(request("PRIMARY2"));
            authorization.claimPairingOwner(PlatformType.WEIXIN, "PRIMARY2");
            assertThat(authorization.setPrimaryHomeChannel(PlatformType.WEIXIN).isPrimary())
                    .isTrue();
        } finally {
            database.shutdown();
        }
    }

    /** 主人绑定失败必须共享平台级失败锁定，锁定期间正确 code 也不能通过。 */
    @Test
    void shouldEnforcePlatformApprovalLockoutAcrossTrustedControls() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-lockout"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);
            repository.savePairingRequest(request("LOCK2345"));

            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(
                                () ->
                                        authorization.claimPairingOwner(
                                                PlatformType.WEIXIN, "WRONG234"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("无效或已过期");
            }

            assertThatThrownBy(
                            () -> authorization.claimPairingOwner(PlatformType.WEIXIN, "LOCK2345"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("失败次数过多");
            assertThat(repository.getPlatformAdmin(PlatformType.WEIXIN)).isNull();
            assertThat(repository.getPairingRequest(PlatformType.WEIXIN, "LOCK2345")).isNotNull();
        } finally {
            database.shutdown();
        }
    }

    /** 多个 SQLite 实例并发记录错误审批时不得丢失失败次数或绕过平台锁定。 */
    @Test
    void shouldAtomicallyLockPairingApprovalAcrossSqliteInstances() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-concurrent-lock"));
        List<SqliteDatabase> databases = databases(config, 5);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (SqliteDatabase database : databases) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    new SqliteGatewayPolicyRepository(database)
                                            .recordPairingApprovalFailure(
                                                    PlatformType.WEIXIN,
                                                    "solonclaw:pairing-platform-approval",
                                                    System.currentTimeMillis(),
                                                    5,
                                                    60_000L);
                                    return null;
                                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }

            assertThat(
                            new SqliteGatewayPolicyRepository(databases.get(0))
                                    .getPairingRateLimit(
                                            PlatformType.WEIXIN,
                                            "solonclaw:pairing-platform-approval")
                                    .getLockoutUntil())
                    .isGreaterThan(System.currentTimeMillis());
        } finally {
            executor.shutdownNow();
            shutdown(databases);
        }
    }

    /** 正确 code 审批不得清除其执行期间由另一 SQLite 实例建立的平台锁。 */
    @Test
    void shouldKeepConcurrentPlatformLockBeforeApprovingCorrectCode() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-final-lock-gate"));
        List<SqliteDatabase> databases = databases(config, 2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch requestLoaded = new CountDownLatch(1);
        CountDownLatch continueApproval = new CountDownLatch(1);
        try {
            SqliteGatewayPolicyRepository approvalRepository =
                    new SqliteGatewayPolicyRepository(databases.get(0)) {
                        /** 在正确请求读取后暂停，稳定复现另一实例并发建立平台锁的时序。 */
                        @Override
                        public PairingRequestRecord getPairingRequest(
                                PlatformType platform, String code) throws Exception {
                            PairingRequestRecord record = super.getPairingRequest(platform, code);
                            requestLoaded.countDown();
                            continueApproval.await();
                            return record;
                        }
                    };
            approvalRepository.savePairingRequest(request("RACE2345"));
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(approvalRepository, config);
            Future<String> approval =
                    executor.submit(
                            () -> {
                                try {
                                    authorization.claimPairingOwner(
                                            PlatformType.WEIXIN, "RACE2345");
                                    return "claimed";
                                } catch (IllegalArgumentException e) {
                                    return e.getMessage();
                                }
                            });

            requestLoaded.await();
            SqliteGatewayPolicyRepository concurrentRepository =
                    new SqliteGatewayPolicyRepository(databases.get(1));
            for (int i = 0; i < 5; i++) {
                concurrentRepository.recordPairingApprovalFailure(
                        PlatformType.WEIXIN,
                        "solonclaw:pairing-platform-approval",
                        System.currentTimeMillis(),
                        5,
                        60_000L);
            }
            continueApproval.countDown();

            assertThat(approval.get()).contains("失败次数过多");
            assertThat(
                            concurrentRepository
                                    .getPairingRateLimit(
                                            PlatformType.WEIXIN,
                                            "solonclaw:pairing-platform-approval")
                                    .getLockoutUntil())
                    .isGreaterThan(System.currentTimeMillis());
            assertThat(concurrentRepository.getPlatformAdmin(PlatformType.WEIXIN)).isNull();
            assertThat(concurrentRepository.getPairingRequest(PlatformType.WEIXIN, "RACE2345"))
                    .isNotNull();
        } finally {
            continueApproval.countDown();
            executor.shutdownNow();
            shutdown(databases);
        }
    }

    /** 多个 SQLite 实例并发创建请求时，单平台待处理数量必须严格受容量上限约束。 */
    @Test
    void shouldAtomicallyLimitPendingPairingsAcrossSqliteInstances() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-concurrent-limit"));
        List<SqliteDatabase> databases = databases(config, 8);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < databases.size(); i++) {
                final int userIndex = i;
                final SqliteDatabase database = databases.get(i);
                futures.add(
                        executor.submit(
                                () -> {
                                    PairingRequestRecord candidate =
                                            request(
                                                    "CODE" + (2345 + userIndex),
                                                    "wx-user-" + userIndex);
                                    start.await();
                                    return new SqliteGatewayPolicyRepository(database)
                                            .trySavePairingRequest(
                                                    candidate, System.currentTimeMillis(), 3);
                                }));
            }

            start.countDown();
            int saved = 0;
            for (Future<Boolean> future : futures) {
                if (future.get().booleanValue()) {
                    saved++;
                }
            }

            SqliteGatewayPolicyRepository repository =
                    new SqliteGatewayPolicyRepository(databases.get(0));
            assertThat(saved).isEqualTo(3);
            assertThat(repository.listPairingRequests(PlatformType.WEIXIN)).hasSize(3);
        } finally {
            executor.shutdownNow();
            shutdown(databases);
        }
    }

    /** 同一用户并发申请时只能返回一个仍然有效的 pairing code，其余请求必须命中冷却。 */
    @Test
    void shouldAtomicallyAdmitOnePairingRequestPerUserAcrossSqliteInstances() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-concurrent-user"));
        List<SqliteDatabase> databases = databases(config, 8);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<GatewayReply>> futures = new ArrayList<Future<GatewayReply>>();
            for (SqliteDatabase database : databases) {
                futures.add(
                        executor.submit(
                                () -> {
                                    GatewayAuthorizationService authorization =
                                            new GatewayAuthorizationService(
                                                    new SqliteGatewayPolicyRepository(database),
                                                    config);
                                    GatewayMessage message =
                                            new GatewayMessage(
                                                    PlatformType.WEIXIN,
                                                    "same-user-chat",
                                                    "same-user",
                                                    "hello");
                                    start.await();
                                    return authorization.preAuthorize(message);
                                }));
            }

            start.countDown();
            List<GatewayReply> replies = new ArrayList<GatewayReply>();
            for (Future<GatewayReply> future : futures) {
                replies.add(future.get());
            }

            List<GatewayReply> created = new ArrayList<GatewayReply>();
            for (GatewayReply reply : replies) {
                if (reply.getContent().contains("pairing code")) {
                    created.add(reply);
                } else {
                    assertThat(reply.getContent()).contains("请求过于频繁");
                }
            }
            assertThat(created).hasSize(1);
            String code = pairingCode(created.get(0).getContent());
            SqliteGatewayPolicyRepository repository =
                    new SqliteGatewayPolicyRepository(databases.get(0));
            assertThat(repository.listPairingRequests(PlatformType.WEIXIN)).hasSize(1);
            assertThat(repository.getPairingRequest(PlatformType.WEIXIN, code)).isNotNull();
            assertThat(repository.getPairingRateLimit(PlatformType.WEIXIN, "same-user"))
                    .extracting("requestedAt")
                    .isNotEqualTo(0L);
        } finally {
            executor.shutdownNow();
            shutdown(databases);
        }
    }

    /** 容量已满时原子准入必须保留当前用户旧 code，并清理无状态限流占位。 */
    @Test
    void shouldKeepExistingPairingWhenAtomicAdmissionHitsCapacity() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-admit-capacity"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            long now = System.currentTimeMillis();
            repository.savePairingRequest(request("OTHER234", "other-1"));
            repository.savePairingRequest(request("OTHER235", "other-2"));
            repository.savePairingRequest(request("OTHER236", "other-3"));
            repository.savePairingRequest(request("CURRENT2", "current-user"));

            PairingRequestAdmissionResult result =
                    repository.admitPairingRequest(
                            request("REPLACE2", "current-user"),
                            now,
                            PairingConstants.MAX_PENDING_PER_PLATFORM,
                            PairingConstants.RATE_LIMIT_MILLIS);

            assertThat(result.getStatus())
                    .isEqualTo(PairingRequestAdmissionResult.Status.CAPACITY_REACHED);
            assertThat(repository.getPairingRequest(PlatformType.WEIXIN, "CURRENT2")).isNotNull();
            assertThat(repository.getPairingRequest(PlatformType.WEIXIN, "REPLACE2")).isNull();
            assertThat(repository.getPairingRateLimit(PlatformType.WEIXIN, "current-user"))
                    .isNull();
        } finally {
            database.shutdown();
        }
    }

    /** 原子准入发生 code 摘要冲突时必须同时回滚请求替换和限流占位。 */
    @Test
    void shouldRollbackAtomicAdmissionWhenPairingCodeHashCollides() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-admit-collision"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            repository.savePairingRequest(request("OWNER234", "first-user"));
            String storedHash =
                    repository.listPairingRequests(PlatformType.WEIXIN).get(0).getCode();

            assertThatThrownBy(
                            () ->
                                    repository.admitPairingRequest(
                                            request(storedHash, "second-user"),
                                            System.currentTimeMillis(),
                                            PairingConstants.MAX_PENDING_PER_PLATFORM,
                                            PairingConstants.RATE_LIMIT_MILLIS))
                    .isInstanceOf(java.sql.SQLException.class);

            assertThat(repository.listPairingRequests(PlatformType.WEIXIN))
                    .singleElement()
                    .extracting(PairingRequestRecord::getUserId)
                    .isEqualTo("first-user");
            assertThat(repository.getPairingRateLimit(PlatformType.WEIXIN, "second-user")).isNull();
        } finally {
            database.shutdown();
        }
    }

    /** 原子准入更新请求时间时必须保留已有失败计数和锁定时间。 */
    @Test
    void shouldPreservePairingFailureStateDuringAtomicAdmission() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-admit-state"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            long now = System.currentTimeMillis();
            PairingRateLimitRecord existing = new PairingRateLimitRecord();
            existing.setPlatform(PlatformType.WEIXIN);
            existing.setUserId("state-user");
            existing.setRequestedAt(now - PairingConstants.RATE_LIMIT_MILLIS - 1L);
            existing.setFailedAttempts(3);
            existing.setLockoutUntil(now + 30_000L);
            repository.savePairingRateLimit(existing);

            PairingRequestAdmissionResult result =
                    repository.admitPairingRequest(
                            request("STATE234", "state-user"),
                            now,
                            PairingConstants.MAX_PENDING_PER_PLATFORM,
                            PairingConstants.RATE_LIMIT_MILLIS);

            PairingRateLimitRecord updated =
                    repository.getPairingRateLimit(PlatformType.WEIXIN, "state-user");
            assertThat(result.getStatus()).isEqualTo(PairingRequestAdmissionResult.Status.CREATED);
            assertThat(updated.getRequestedAt()).isEqualTo(now);
            assertThat(updated.getFailedAttempts()).isEqualTo(3);
            assertThat(updated.getLockoutUntil()).isEqualTo(now + 30_000L);
        } finally {
            database.shutdown();
        }
    }

    /** 同一用户再次申请时只保留最新请求。 */
    @Test
    void shouldReplaceExistingPairingRequestForSameUser() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-replace"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            long now = System.currentTimeMillis();

            assertThat(repository.trySavePairingRequest(request("FIRST234"), now, 3)).isTrue();
            assertThat(repository.trySavePairingRequest(request("SECOND23"), now, 3)).isTrue();

            assertThat(repository.listPairingRequests(PlatformType.WEIXIN)).hasSize(1);
            assertThat(repository.getPairingRequest(PlatformType.WEIXIN, "FIRST234")).isNull();
            assertThat(repository.getPairingRequest(PlatformType.WEIXIN, "SECOND23")).isNotNull();
        } finally {
            database.shutdown();
        }
    }

    /** 已存在的摘要冲突不得被另一用户静默覆盖。 */
    @Test
    void shouldNotOverwriteAnotherUserWhenPairingCodeHashCollides() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-code-collision"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            PairingRequestRecord owner = request("OWNER234", "first-user");
            repository.savePairingRequest(owner);
            String storedHash =
                    repository.listPairingRequests(PlatformType.WEIXIN).get(0).getCode();
            PairingRequestRecord collision = request(storedHash, "second-user");

            assertThatThrownBy(
                            () ->
                                    repository.trySavePairingRequest(
                                            collision, System.currentTimeMillis(), 3))
                    .isInstanceOf(java.sql.SQLException.class);

            assertThat(repository.listPairingRequests(PlatformType.WEIXIN))
                    .singleElement()
                    .extracting(PairingRequestRecord::getUserId)
                    .isEqualTo("first-user");
        } finally {
            database.shutdown();
        }
    }

    /** 管理员设置和清除只能走可信控制服务，且 SQLite 主文件权限必须 owner-only。 */
    @Test
    void shouldManageAdminAndRestrictDatabasePermissions() throws Exception {
        AppConfig config = config(Files.createTempDirectory("solonclaw-pairing-admin"));
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(
                            new SqliteGatewayPolicyRepository(database), config);
            authorization.setPlatformAdmin(PlatformType.WEIXIN, "wx-admin", "管理员", "wx-admin-chat");
            assertThat(authorization.platformAdmin(PlatformType.WEIXIN).getUserId())
                    .isEqualTo("wx-admin");
            authorization.clearPlatformAdmin(PlatformType.WEIXIN);
            assertThat(authorization.platformAdmin(PlatformType.WEIXIN)).isNull();

            Path stateDb = Paths.get(config.getRuntime().getStateDb());
            if (Files.getFileAttributeView(stateDb, PosixFileAttributeView.class) != null) {
                assertThat(Files.getPosixFilePermissions(stateDb))
                        .isEqualTo(
                                EnumSet.of(
                                        PosixFilePermission.OWNER_READ,
                                        PosixFilePermission.OWNER_WRITE));
            }
        } finally {
            database.shutdown();
        }
    }

    /** 创建隔离 SQLite 配置。 */
    private AppConfig config(Path home) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        return config;
    }

    /** 创建一条待审批微信 pairing 请求。 */
    private PairingRequestRecord request(String code) {
        return request(code, "wx-user");
    }

    /** 创建指定用户的待审批微信 pairing 请求。 */
    private PairingRequestRecord request(String code, String userId) {
        return request(PlatformType.WEIXIN, code, userId, "wx-chat");
    }

    /** 创建指定平台和私聊的待绑定请求。 */
    private PairingRequestRecord request(
            PlatformType platform, String code, String userId, String chatId) {
        PairingRequestRecord request = new PairingRequestRecord();
        request.setPlatform(platform);
        request.setCode(code);
        request.setUserId(userId);
        request.setUserName("微信用户");
        request.setChatId(chatId);
        request.setCreatedAt(System.currentTimeMillis());
        request.setExpiresAt(System.currentTimeMillis() + 60_000L);
        return request;
    }

    /** 从用户提示中提取本次返回的八位 pairing code。 */
    private String pairingCode(String prompt) {
        Matcher matcher = Pattern.compile("`([A-Z2-9]{8})`").matcher(prompt);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** 读取 Dashboard 返回的欢迎投递状态。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> welcome(Map<String, Object> result) {
        return (Map<String, Object>) result.get("welcome_delivery");
    }

    /** 创建多个独立数据库组件实例，共同指向同一状态库文件。 */
    private List<SqliteDatabase> databases(AppConfig config, int count) throws Exception {
        List<SqliteDatabase> databases = new ArrayList<SqliteDatabase>();
        for (int i = 0; i < count; i++) {
            databases.add(new SqliteDatabase(config));
        }
        return databases;
    }

    /** 关闭并发测试创建的全部数据库组件实例。 */
    private void shutdown(List<SqliteDatabase> databases) {
        for (SqliteDatabase database : databases) {
            database.shutdown();
        }
    }

    /** 直接读取数据库中的摘要文本，证明明文未落库。 */
    private String storedCode(SqliteDatabase database) throws Exception {
        Connection connection = database.openConnection();
        try {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("select code from pairing_requests");
            try {
                return resultSet.next() ? resultSet.getString(1) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }
}
