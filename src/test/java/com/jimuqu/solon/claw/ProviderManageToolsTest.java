package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.tool.runtime.ProviderManageTools;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

/** 验证 provider 管理工具的自然语言动作分发。 */
public class ProviderManageToolsTest {
    /** provider_models 应复用远程模型列表能力，而不是落回 provider 配置列表。 */
    @Test
    void shouldRouteProviderModelsAliasToRemoteModelList() {
        RecordingProviderService service = new RecordingProviderService();
        ProviderManageTools tools = new ProviderManageTools(service);

        String json =
                tools.providerManage(
                        "provider_models",
                        null,
                        null,
                        "{\"baseUrl\":\"https://api.example.test\",\"dialect\":\"openai\"}");

        Map<?, ?> result = result(json);
        assertThat(service.remoteModelCalls).isEqualTo(1);
        assertThat(result.get("models")).asList().containsExactly("provider-remote-model");
    }

    /** models_health 应对齐 Dashboard 的 /api/models/health 页面动作。 */
    @Test
    void shouldRouteModelsHealthAliasToProviderHealth() {
        RecordingProviderService service = new RecordingProviderService();
        ProviderManageTools tools = new ProviderManageTools(service);

        String json = tools.providerManage("models_health", null, null, null);

        Map<?, ?> result = result(json);
        assertThat(service.healthCalls).isEqualTo(1);
        assertThat(result.get("providers")).asList().containsExactly("provider-health-ok");
    }

    /** set_default_model 应复用 Dashboard 的默认模型保存能力。 */
    @Test
    void shouldRouteSetDefaultModelAliasToUpdateDefaultModel() {
        RecordingProviderService service = new RecordingProviderService();
        ProviderManageTools tools = new ProviderManageTools(service);

        String json = tools.providerManage("set_default_model", "openai-direct", "mimo-v2.5", null);

        Map<?, ?> result = result(json);
        assertThat(service.defaultModelCalls).isEqualTo(1);
        assertThat(service.defaultProviderKey).isEqualTo("openai-direct");
        assertThat(service.defaultModel).isEqualTo("mimo-v2.5");
        assertThat(result.get("model")).isEqualTo("mimo-v2.5");
    }

    /** 记录 provider service 调用，避免测试访问真实模型接口。 */
    private static class RecordingProviderService extends DashboardProviderService {
        /** 远程模型列表调用次数。 */
        private int remoteModelCalls;

        /** 模型健康检查调用次数。 */
        private int healthCalls;

        /** 默认模型保存调用次数。 */
        private int defaultModelCalls;

        /** 最近一次默认模型保存的 provider 标识。 */
        private String defaultProviderKey;

        /** 最近一次默认模型保存的模型名称。 */
        private String defaultModel;

        /** 创建记录型 provider service。 */
        RecordingProviderService() {
            super(new AppConfig(), null, new LlmProviderService(new AppConfig()));
        }

        /** 返回固定远程模型列表。 */
        @Override
        public Map<String, Object> listRemoteModels(Map<String, Object> data) {
            remoteModelCalls++;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("models", Collections.singletonList("provider-remote-model"));
            return result;
        }

        /** 返回固定模型健康状态。 */
        @Override
        public Map<String, Object> health() {
            healthCalls++;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("providers", Collections.singletonList("provider-health-ok"));
            return result;
        }

        /** 返回固定默认模型保存结果。 */
        @Override
        public Map<String, Object> updateDefaultModel(String providerKey, String model) {
            defaultModelCalls++;
            defaultProviderKey = providerKey;
            defaultModel = model;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("provider", providerKey);
            result.put("model", model);
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
