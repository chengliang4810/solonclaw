package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.PlatformToolsetsManageTools;
import com.jimuqu.solon.claw.web.DashboardPlatformToolsetsService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

/** 验证平台工具集管理工具的页面动作别名。 */
public class PlatformToolsetsManageToolsTest {
    /** save 和 save_toolsets 应复用 Dashboard 的平台工具集保存能力。 */
    @Test
    void shouldRouteSaveAliasesToPlatformToolsetsUpdate() {
        RecordingPlatformToolsetsService service = new RecordingPlatformToolsetsService();
        PlatformToolsetsManageTools tools = new PlatformToolsetsManageTools(service);

        for (String action : new String[] {"save", "save_toolsets"}) {
            String json =
                    tools.platformToolsetsManage(
                            action,
                            "feishu",
                            "{\"enabledToolsets\":[\"web\"],\"disabledToolsets\":\"terminal\"}");

            Map<?, ?> result = result(json);
            assertThat(result.get("platform")).isEqualTo("feishu");
        }
        assertThat(service.updateCalls).isEqualTo(2);
        assertThat(service.lastBody.get("enabledToolsets")).asList().containsExactly("web");
        assertThat(service.lastBody.get("disabledToolsets")).isEqualTo("terminal");
    }

    /** 记录平台工具集服务调用，避免测试写入真实配置文件。 */
    private static class RecordingPlatformToolsetsService
            extends DashboardPlatformToolsetsService {
        /** 平台工具集保存调用次数。 */
        private int updateCalls;

        /** 最近一次保存的平台。 */
        private String lastPlatform;

        /** 最近一次保存的请求体。 */
        private Map<String, Object> lastBody;

        /** 创建记录型平台工具集服务。 */
        RecordingPlatformToolsetsService() {
            super(new AppConfig(), null);
        }

        /** 返回固定概览结果。 */
        @Override
        public Map<String, Object> overview() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("platforms", new LinkedHashMap<String, Object>());
            return result;
        }

        /** 记录保存请求并返回平台结果。 */
        @Override
        public Map<String, Object> update(String platform, Map<String, Object> body) {
            updateCalls++;
            lastPlatform = platform;
            lastBody = body;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("platform", lastPlatform);
            return result;
        }
    }

    /** 读取工具结果里的 result 数据。 */
    @SuppressWarnings("unchecked")
    private Map<?, ?> result(String json) {
        Map<?, ?> root = ONode.deserialize(json, LinkedHashMap.class);
        assertThat(root.get("status")).isEqualTo("success");
        return (Map<?, ?>) root.get("result");
    }
}
