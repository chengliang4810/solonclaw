package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGatewayPolicyRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证每个 Profile 只持久化一个主要通知渠道。 */
class PrimaryHomeChannelPersistenceTest {
    /** 测试使用的独立 Profile 目录。 */
    @TempDir Path tempDir;

    /** 第一条 home channel 自动成为主渠道，显式切换后旧记录仍保留但不再是主渠道。 */
    @Test
    void shouldPersistSinglePrimaryHomeChannel() throws Exception {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(tempDir.toString());
        config.getRuntime().setStateDb(tempDir.resolve("data/state.db").toString());

        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            repository.savePlatformAdmin(admin(PlatformType.FEISHU, "feishu-admin"));
            repository.savePlatformAdmin(admin(PlatformType.DINGTALK, "dingtalk-admin"));
            repository.saveHomeChannel(home(PlatformType.FEISHU, "feishu-home", false, 1L));
            repository.saveHomeChannel(home(PlatformType.DINGTALK, "dingtalk-home", false, 2L));

            assertThat(repository.getPrimaryHomeChannel().getPlatform())
                    .isEqualTo(PlatformType.FEISHU);

            repository.setPrimaryHomeChannel(PlatformType.DINGTALK);

            assertThat(repository.getPrimaryHomeChannel().getPlatform())
                    .isEqualTo(PlatformType.DINGTALK);
            assertThat(repository.getHomeChannel(PlatformType.FEISHU).isPrimary()).isFalse();
            assertThat(repository.getHomeChannel(PlatformType.DINGTALK).isPrimary()).isTrue();
            assertThat(repository.listHomeChannels()).hasSize(2);
            assertThatThrownBy(() -> repository.setPrimaryHomeChannel(PlatformType.WECOM))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(repository.getPrimaryHomeChannel().getPlatform())
                    .isEqualTo(PlatformType.DINGTALK);
        } finally {
            database.shutdown();
        }

        SqliteDatabase reopened = new SqliteDatabase(config);
        try {
            assertThat(
                            new SqliteGatewayPolicyRepository(reopened)
                                    .getPrimaryHomeChannel()
                                    .getPlatform())
                    .isEqualTo(PlatformType.DINGTALK);
        } finally {
            reopened.shutdown();
        }
    }

    /** 首次主人绑定自动成为主渠道，后续平台绑定只新增 home channel。 */
    @Test
    void shouldMakeOnlyFirstOwnerClaimPrimary() throws Exception {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(tempDir.toString());
        config.getRuntime().setStateDb(tempDir.resolve("data/claim-state.db").toString());
        SqliteDatabase database = new SqliteDatabase(config);
        try {
            SqliteGatewayPolicyRepository repository = new SqliteGatewayPolicyRepository(database);
            GatewayAuthorizationService authorization =
                    new GatewayAuthorizationService(repository, config);
            repository.savePairingRequest(
                    pairing(PlatformType.WEIXIN, "WXCODE23", "wx-user", "wx-chat"));
            authorization.claimPairingOwner(PlatformType.WEIXIN, "WXCODE23");
            repository.savePairingRequest(
                    pairing(PlatformType.FEISHU, "FSCODE23", "fs-user", "fs-chat"));
            authorization.claimPairingOwner(PlatformType.FEISHU, "FSCODE23");

            assertThat(repository.getPrimaryHomeChannel().getPlatform())
                    .isEqualTo(PlatformType.WEIXIN);
            assertThat(repository.getHomeChannel(PlatformType.WEIXIN).isPrimary()).isTrue();
            assertThat(repository.getHomeChannel(PlatformType.FEISHU).isPrimary()).isFalse();
        } finally {
            database.shutdown();
        }
    }

    /** 创建测试 home channel。 */
    private HomeChannelRecord home(
            PlatformType platform, String chatId, boolean primary, long updatedAt) {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(platform);
        record.setChatId(chatId);
        record.setChatName(chatId);
        record.setPrimary(primary);
        record.setUpdatedAt(updatedAt);
        return record;
    }

    /** 创建测试平台管理员。 */
    private PlatformAdminRecord admin(PlatformType platform, String userId) {
        PlatformAdminRecord record = new PlatformAdminRecord();
        record.setPlatform(platform);
        record.setUserId(userId);
        record.setUserName(userId);
        record.setChatId(userId + "-chat");
        record.setCreatedAt(System.currentTimeMillis());
        return record;
    }

    /** 创建待可信控制面认领的首次私聊请求。 */
    private PairingRequestRecord pairing(
            PlatformType platform, String code, String userId, String chatId) {
        PairingRequestRecord record = new PairingRequestRecord();
        record.setPlatform(platform);
        record.setCode(code);
        record.setUserId(userId);
        record.setUserName(userId);
        record.setChatId(chatId);
        record.setCreatedAt(System.currentTimeMillis());
        record.setExpiresAt(System.currentTimeMillis() + 60_000L);
        return record;
    }
}
