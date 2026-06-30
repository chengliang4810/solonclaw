package com.jimuqu.solon.claw.gateway.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import org.junit.jupiter.api.Test;

/** 验证 slash 帮助文本由命令注册表驱动，避免帮助入口与真实命令目录漂移。 */
class SlashCommandHelpRendererTest {
    @Test
    void renderIncludesEveryGatewayCommandFromRegistry() {
        String help = SlashCommandHelpRenderer.render();

        for (CommandDescriptor descriptor : CommandRegistry.all()) {
            if (descriptor.supportsScope(CommandRegistry.SCOPE_GATEWAY)) {
                assertThat(help)
                        .as(descriptor.slashName())
                        .contains(descriptor.slashName() + " ");
            } else {
                assertThat(help)
                        .as(descriptor.slashName())
                        .doesNotContain(descriptor.slashName() + " - ");
            }
        }
    }
}
