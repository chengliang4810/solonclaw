package com.jimuqu.solon.claw.tui;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 验证 Java 终端模型选择器完整展示 Provider 模型清单。 */
class TerminalModelPickerTest {
    /** 选择器必须同时展示默认模型和额外登记模型。 */
    @Test
    void shouldRenderEveryRegisteredProviderModel() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Custom");
        provider.setDefaultModel("main-model");
        provider.setModels(Arrays.asList("main-model", "extra-model"));
        provider.setDialect("openai");
        config.getProviders().put("custom", provider);
        config.getModel().setProviderKey("custom");
        config.getModel().setDefault("main-model");

        String output = new TerminalModelPicker(config, new LlmProviderService(config)).render();

        assertThat(output).contains("custom:main-model").contains("custom:extra-model");
    }
}
