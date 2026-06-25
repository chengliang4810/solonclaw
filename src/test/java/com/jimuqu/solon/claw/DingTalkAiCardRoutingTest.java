package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.solon.claw.storage.repository.SqliteChannelStateRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DingTalkAiCardRoutingTest {
    /** 验证当前钉钉 AI Card 只使用显式 robotCode 配置。 */
    @Test
    void shouldRequireExplicitRobotCodeForAiCardRouting() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome = Files.createTempDirectory("solonclaw-dingtalk-robot-code").toFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        config.getChannels().getDingtalk().setEnabled(true);
        config.getChannels().getDingtalk().setClientId("ding-client");
        config.getChannels().getDingtalk().setClientSecret("ding-secret");

        ChannelStateRepository stateRepository =
                new SqliteChannelStateRepository(new SqliteDatabase(config));
        TestableDingTalkChannelAdapter adapter =
                new TestableDingTalkChannelAdapter(
                        config.getChannels().getDingtalk(),
                        stateRepository,
                        new AttachmentCacheService(config));

        assertThat(adapter.exposeEffectiveRobotCode()).isEmpty();
    }

    @Test
    void shouldRouteDeliveryRequestWithCardExtrasToAiCardSend() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome = Files.createTempDirectory("solonclaw-dingtalk-card").toFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        config.getChannels().getDingtalk().setEnabled(true);
        config.getChannels().getDingtalk().setClientId("app-key");
        config.getChannels().getDingtalk().setClientSecret("app-secret");
        config.getChannels().getDingtalk().setRobotCode("robot-code");

        ChannelStateRepository stateRepository =
                new SqliteChannelStateRepository(new SqliteDatabase(config));

        final DeliveryRequest[] captured = new DeliveryRequest[1];
        DingTalkChannelAdapter adapter =
                new DingTalkChannelAdapter(
                        config.getChannels().getDingtalk(),
                        stateRepository,
                        new AttachmentCacheService(config)) {
                    @Override
                    protected synchronized void refreshAccessTokenIfNecessary() {
                        // no-op for unit test
                    }

                    @Override
                    protected void sendAiCard(DeliveryRequest request) {
                        captured[0] = request;
                    }
                };

        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.DINGTALK);
        request.setChatId("cid-group");
        request.setChatType("group");
        Map<String, Object> extras = new LinkedHashMap<String, Object>();
        extras.put("mode", "ai_card");
        extras.put("cardTemplateId", "tpl-001");
        extras.put("cardData", "{\"title\":\"demo\"}");
        request.setChannelExtras(extras);

        adapter.send(request);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getChannelExtras()).containsEntry("cardTemplateId", "tpl-001");
    }

    /** 暴露钉钉适配器内部有效机器人编码，避免单元测试触发真实平台连接。 */
    private static class TestableDingTalkChannelAdapter extends DingTalkChannelAdapter {
        /** 创建测试用钉钉适配器。 */
        private TestableDingTalkChannelAdapter(
                AppConfig.ChannelConfig config,
                ChannelStateRepository channelStateRepository,
                AttachmentCacheService attachmentCacheService) {
            super(config, channelStateRepository, attachmentCacheService);
        }

        /** 返回运行时最终使用的机器人编码。 */
        private String exposeEffectiveRobotCode() {
            return effectiveRobotCode();
        }
    }
}
