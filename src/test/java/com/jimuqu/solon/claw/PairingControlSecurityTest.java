package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGatewayPolicyRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 验证可信 pairing 控制面不会把临时凭据明文落库。 */
public class PairingControlSecurityTest {
    /** pairing code 必须盐化存储，并只能由正确明文完成审批。 */
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

            assertThat(
                            authorization
                                    .approvePairing(PlatformType.WEIXIN, "ABCD2345", "dashboard")
                                    .getUserId())
                    .isEqualTo("wx-user");
            assertThat(repository.listPairingRequests(PlatformType.WEIXIN)).isEmpty();
            assertThat(repository.getApprovedUser(PlatformType.WEIXIN, "wx-user")).isNotNull();
        } finally {
            database.shutdown();
        }
    }

    /** Dashboard、CLI 和渠道审批必须共享平台级失败锁定，锁定期间正确 code 也不能通过。 */
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
                String approvedBy = i % 2 == 0 ? "dashboard" : "local-cli";
                assertThatThrownBy(
                                () ->
                                        authorization.approvePairing(
                                                PlatformType.WEIXIN, "WRONG234", approvedBy))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("无效或已过期");
            }

            assertThatThrownBy(
                            () ->
                                    authorization.approvePairing(
                                            PlatformType.WEIXIN, "LOCK2345", "dashboard"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("失败次数过多");
            assertThat(repository.getApprovedUser(PlatformType.WEIXIN, "wx-user")).isNull();
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
            assertThatThrownBy(() -> authorization.revokePairing(PlatformType.WEIXIN, "wx-admin"))
                    .isInstanceOf(IllegalArgumentException.class);
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
        PairingRequestRecord request = new PairingRequestRecord();
        request.setPlatform(PlatformType.WEIXIN);
        request.setCode(code);
        request.setUserId(userId);
        request.setUserName("微信用户");
        request.setChatId("wx-chat");
        request.setCreatedAt(System.currentTimeMillis());
        request.setExpiresAt(System.currentTimeMillis() + 60_000L);
        return request;
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
