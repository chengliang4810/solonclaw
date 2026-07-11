package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

/** 主动协作配置加载测试，覆盖默认值和 workspace/config.yml 覆盖优先级。 */
public class ProactiveConfigTest {
    /** 验证未写入工作区配置时，主动协作配置使用产品默认值。 */
    @Test
    void shouldLoadDefaultProactiveConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-proactive-defaults").toFile();
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig.ProactiveConfig proactive = AppConfig.load(props).getProactive();

        assertThat(proactive.isEnabled()).isTrue();
        assertThat(proactive.getIntervalMinutes()).isEqualTo(30);
        assertThat(proactive.getInitialDelaySeconds()).isEqualTo(60);
        assertThat(proactive.getDailyMaxContacts()).isEqualTo(3);
        assertThat(proactive.getCooldownMinutes()).isEqualTo(120);
        assertThat(proactive.getQuietStartHour()).isEqualTo(23);
        assertThat(proactive.getQuietEndHour()).isEqualTo(8);
        assertThat(proactive.getMinConfidenceToContact()).isEqualTo(0.65D);
        assertThat(proactive.isLlmDecisionEnabled()).isTrue();
        assertThat(proactive.isLlmPolishEnabled()).isTrue();
        assertThat(proactive.getMaxCandidatesPerTick()).isEqualTo(20);
        assertThat(proactive.getMaxContactsPerTick()).isEqualTo(1);
        assertThat(proactive.getCandidateTtlHours()).isEqualTo(72);
        assertThat(proactive.isRepositoryCheckEnabled()).isTrue();
        assertThat(proactive.getRepositoryCheckIntervalMinutes()).isEqualTo(360);
        assertThat(proactive.getSessionLookbackDays()).isEqualTo(30);
        assertThat(proactive.getRunLookbackDays()).isEqualTo(14);
        assertThat(proactive.getCronLookbackDays()).isEqualTo(14);
        assertThat(proactive.isCareCheckinEnabled()).isTrue();
        assertThat(proactive.getCareCheckinAfterIdleHours()).isEqualTo(48);
        assertThat(proactive.getDeliveryPreviewPrefix()).isEqualTo("主动协作");
    }

    /** 验证 workspace/config.yml 中的 solonclaw.proactive 配置会覆盖 Props 中的同名键。 */
    @Test
    void shouldLoadProactiveConfigFromRuntimeConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-proactive-overrides").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  proactive:\n"
                        + "    enabled: false\n"
                        + "    intervalMinutes: 11\n"
                        + "    initialDelaySeconds: 12\n"
                        + "    dailyMaxContacts: 4\n"
                        + "    cooldownMinutes: 55\n"
                        + "    quietStartHour: 21\n"
                        + "    quietEndHour: 7\n"
                        + "    minConfidenceToContact: 0.82\n"
                        + "    llmDecisionEnabled: false\n"
                        + "    llmPolishEnabled: false\n"
                        + "    maxCandidatesPerTick: 9\n"
                        + "    maxContactsPerTick: 2\n"
                        + "    candidateTtlHours: 24\n"
                        + "    repositoryCheckEnabled: false\n"
                        + "    repositoryCheckIntervalMinutes: 180\n"
                        + "    sessionLookbackDays: 12\n"
                        + "    runLookbackDays: 8\n"
                        + "    cronLookbackDays: 6\n"
                        + "    careCheckinEnabled: false\n"
                        + "    careCheckinAfterIdleHours: 36\n"
                        + "    deliveryPreviewPrefix: 协作提醒\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        props.put("solonclaw.proactive.enabled", "true");
        props.put("solonclaw.proactive.intervalMinutes", "99");

        AppConfig.ProactiveConfig proactive = AppConfig.load(props).getProactive();

        assertThat(proactive.isEnabled()).isFalse();
        assertThat(proactive.getIntervalMinutes()).isEqualTo(11);
        assertThat(proactive.getInitialDelaySeconds()).isEqualTo(12);
        assertThat(proactive.getDailyMaxContacts()).isEqualTo(4);
        assertThat(proactive.getCooldownMinutes()).isEqualTo(55);
        assertThat(proactive.getQuietStartHour()).isEqualTo(21);
        assertThat(proactive.getQuietEndHour()).isEqualTo(7);
        assertThat(proactive.getMinConfidenceToContact()).isEqualTo(0.82D);
        assertThat(proactive.isLlmDecisionEnabled()).isFalse();
        assertThat(proactive.isLlmPolishEnabled()).isFalse();
        assertThat(proactive.getMaxCandidatesPerTick()).isEqualTo(9);
        assertThat(proactive.getMaxContactsPerTick()).isEqualTo(2);
        assertThat(proactive.getCandidateTtlHours()).isEqualTo(24);
        assertThat(proactive.isRepositoryCheckEnabled()).isFalse();
        assertThat(proactive.getRepositoryCheckIntervalMinutes()).isEqualTo(180);
        assertThat(proactive.getSessionLookbackDays()).isEqualTo(12);
        assertThat(proactive.getRunLookbackDays()).isEqualTo(8);
        assertThat(proactive.getCronLookbackDays()).isEqualTo(6);
        assertThat(proactive.isCareCheckinEnabled()).isFalse();
        assertThat(proactive.getCareCheckinAfterIdleHours()).isEqualTo(36);
        assertThat(proactive.getDeliveryPreviewPrefix()).isEqualTo("协作提醒");
    }

    /** 验证运行时写入接口接受全部主动协作配置键，并能被应用配置加载使用。 */
    @Test
    void shouldAcceptAllProactiveRuntimeOverrideKeys() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-proactive-runtime-keys").toFile();
        RuntimeConfigResolver resolver =
                RuntimeConfigResolver.initialize(workspaceHome.getAbsolutePath());
        resolver.setFileValue("solonclaw.proactive.enabled", "false");
        resolver.setFileValue("solonclaw.proactive.intervalMinutes", "31");
        resolver.setFileValue("solonclaw.proactive.initialDelaySeconds", "62");
        resolver.setFileValue("solonclaw.proactive.dailyMaxContacts", "5");
        resolver.setFileValue("solonclaw.proactive.cooldownMinutes", "121");
        resolver.setFileValue("solonclaw.proactive.quietStartHour", "22");
        resolver.setFileValue("solonclaw.proactive.quietEndHour", "9");
        resolver.setFileValue("solonclaw.proactive.minConfidenceToContact", "0.72");
        resolver.setFileValue("solonclaw.proactive.llmDecisionEnabled", "false");
        resolver.setFileValue("solonclaw.proactive.llmPolishEnabled", "false");
        resolver.setFileValue("solonclaw.proactive.maxCandidatesPerTick", "17");
        resolver.setFileValue("solonclaw.proactive.maxContactsPerTick", "2");
        resolver.setFileValue("solonclaw.proactive.candidateTtlHours", "49");
        resolver.setFileValue("solonclaw.proactive.repositoryCheckEnabled", "false");
        resolver.setFileValue("solonclaw.proactive.repositoryCheckIntervalMinutes", "181");
        resolver.setFileValue("solonclaw.proactive.sessionLookbackDays", "21");
        resolver.setFileValue("solonclaw.proactive.runLookbackDays", "10");
        resolver.setFileValue("solonclaw.proactive.cronLookbackDays", "11");
        resolver.setFileValue("solonclaw.proactive.careCheckinEnabled", "false");
        resolver.setFileValue("solonclaw.proactive.careCheckinAfterIdleHours", "37");
        resolver.setFileValue("solonclaw.proactive.deliveryPreviewPrefix", "协作观察");

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig.ProactiveConfig proactive = AppConfig.load(props).getProactive();

        assertThat(proactive.isEnabled()).isFalse();
        assertThat(proactive.getIntervalMinutes()).isEqualTo(31);
        assertThat(proactive.getInitialDelaySeconds()).isEqualTo(62);
        assertThat(proactive.getDailyMaxContacts()).isEqualTo(5);
        assertThat(proactive.getCooldownMinutes()).isEqualTo(121);
        assertThat(proactive.getQuietStartHour()).isEqualTo(22);
        assertThat(proactive.getQuietEndHour()).isEqualTo(9);
        assertThat(proactive.getMinConfidenceToContact()).isEqualTo(0.72D);
        assertThat(proactive.isLlmDecisionEnabled()).isFalse();
        assertThat(proactive.isLlmPolishEnabled()).isFalse();
        assertThat(proactive.getMaxCandidatesPerTick()).isEqualTo(17);
        assertThat(proactive.getMaxContactsPerTick()).isEqualTo(2);
        assertThat(proactive.getCandidateTtlHours()).isEqualTo(49);
        assertThat(proactive.isRepositoryCheckEnabled()).isFalse();
        assertThat(proactive.getRepositoryCheckIntervalMinutes()).isEqualTo(181);
        assertThat(proactive.getSessionLookbackDays()).isEqualTo(21);
        assertThat(proactive.getRunLookbackDays()).isEqualTo(10);
        assertThat(proactive.getCronLookbackDays()).isEqualTo(11);
        assertThat(proactive.isCareCheckinEnabled()).isFalse();
        assertThat(proactive.getCareCheckinAfterIdleHours()).isEqualTo(37);
        assertThat(proactive.getDeliveryPreviewPrefix()).isEqualTo("协作观察");
    }
}
