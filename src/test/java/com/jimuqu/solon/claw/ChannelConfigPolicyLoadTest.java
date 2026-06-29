package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class ChannelConfigPolicyLoadTest {
    @Test
    void shouldLoadChannelPoliciesAndWecomGroupAllowMap() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-config-policies").toFile();
        File overrideFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  channels:\n"
                        + "    feishu:\n"
                        + "      dmPolicy: allowlist\n"
                        + "      groupPolicy: open\n"
                        + "      groupAllowedUsers:\n"
                        + "        - oc_group_a\n"
                        + "      allowedChats:\n"
                        + "        - oc_chat_a\n"
                        + "      requireMention: false\n"
                        + "      freeResponseChats:\n"
                        + "        - oc_free_a\n"
                        + "      botName: solonclaw Bot\n"
                        + "    dingtalk:\n"
                        + "      requireMention: false\n"
                        + "      allowedChats: cidAlpha,cidBeta\n"
                        + "      freeResponseChats: cidFreeA,cidFreeB\n"
                        + "    wecom:\n"
                        + "      groups:\n"
                        + "        room-a:\n"
                        + "          allowFrom:\n"
                        + "            - alice\n"
                        + "            - bob\n"
                        + "        '*':\n"
                        + "          allowFrom:\n"
                        + "            - admin\n",
                overrideFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        props.put("solonclaw.channels.feishu.enabled", "true");

        AppConfig config = AppConfig.load(props);

        assertThat(config.getChannels().getFeishu().getDmPolicy()).isEqualTo("allowlist");
        assertThat(config.getChannels().getFeishu().getGroupPolicy()).isEqualTo("open");
        assertThat(config.getChannels().getFeishu().getGroupAllowedUsers())
                .containsExactly("oc_group_a");
        assertThat(config.getChannels().getFeishu().getAllowedChats()).containsExactly("oc_chat_a");
        assertThat(config.getChannels().getFeishu().isRequireMention()).isFalse();
        assertThat(config.getChannels().getFeishu().getFreeResponseChats())
                .containsExactly("oc_free_a");
        assertThat(config.getChannels().getDingtalk().isRequireMention()).isFalse();
        assertThat(config.getChannels().getDingtalk().getAllowedChats())
                .containsExactly("cidAlpha", "cidBeta");
        assertThat(config.getChannels().getDingtalk().getFreeResponseChats())
                .containsExactly("cidFreeA", "cidFreeB");
        assertThat(config.getChannels().getFeishu().getBotName()).isEqualTo("solonclaw Bot");
        assertThat(config.getChannels().getWecom().getGroupMemberAllowedUsers().get("room-a"))
                .containsExactly("alice", "bob");
        assertThat(config.getChannels().getWecom().getGroupMemberAllowedUsers().get("*"))
                .isEqualTo(Arrays.asList("admin"));
    }

    @Test
    void shouldKeepMentionPolicyDefaultsWhenChannelPolicyFieldsAreAbsent() {
        AppConfig.ChannelConfig channelConfig = AppConfig.load(new Props()).getChannels().getFeishu();

        assertThat(channelConfig.isRequireMention()).isTrue();
        assertThat(channelConfig.getFreeResponseChats()).isEmpty();
    }
}
