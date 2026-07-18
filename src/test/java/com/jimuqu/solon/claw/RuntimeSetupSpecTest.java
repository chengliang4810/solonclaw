package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.RuntimeSetupSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证初始化规格中的国内渠道 catalog，为 Dashboard 设置页提供稳定元数据。 */
class RuntimeSetupSpecTest {
    @Test
    void domesticChannelCatalogMatchesConfirmedChannelScope() {
        List<Map<String, Object>> catalog = RuntimeSetupSpec.domesticChannelCatalog();

        assertThat(catalog)
                .extracting(item -> item.get("code"))
                .containsExactly("feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao");
        assertThat(catalog.get(0))
                .containsEntry("displayName", "飞书")
                .containsEntry("iconKey", "feishu")
                .containsEntry("order", 10)
                .containsEntry("enabled", true);
        assertThat(RuntimeSetupSpec.allowedChannelKeys("dingtalk")).contains("mentionPatterns");
    }
}
