package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.TuiRuntimeManageTools;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Param;

/** 验证 TUI 运行时自然语言工具兼容前端 RPC 方法名。 */
public class TuiRuntimeManageToolsTest {
    /** 前端 model.options RPC 方法名应能返回模型选项。 */
    @Test
    void shouldReadModelOptionsWithRpcMethodName() throws Exception {
        ONode result = call("model.options", null, null);

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("result").get("providers").isArray()).isTrue();
    }

    /** 前端 channel.options RPC 方法名应能返回渠道选项。 */
    @Test
    void shouldReadChannelOptionsWithRpcMethodName() throws Exception {
        ONode result = call("channel.options", null, null);

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("result").get("channels").isArray()).isTrue();
    }

    /** 前端 channel.status RPC 方法名应能返回指定渠道状态。 */
    @Test
    void shouldReadChannelStatusWithRpcMethodName() throws Exception {
        ONode result = call("channel.status", "feishu", null);

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("result").get("channel").getString()).isEqualTo("feishu");
    }

    /** 前端 config.get RPC 方法名应能读取指定配置视图。 */
    @Test
    void shouldReadConfigWithRpcMethodName() throws Exception {
        ONode result = call("config.get", null, "model");

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("result").get("key").getString()).isEqualTo("model");
        assertThat(result.get("result").get("value").isNull()).isFalse();
    }

    /** 工具参数说明应包含前端 TUI RPC 的读方法名，便于模型直接选择正确 action。 */
    @Test
    void shouldDescribeTuiRpcReadMethodAliases() throws Exception {
        Method method =
                TuiRuntimeManageTools.class.getMethod(
                        "tuiRuntimeManage",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Map.class,
                        String.class,
                        String.class);

        assertThat(paramDescription(method, "action"))
                .contains("setup.status")
                .contains("model.options")
                .contains("channel.options")
                .contains("channel.status")
                .contains("config.get");
    }

    /** 调用 TUI 运行时工具并解析统一工具结果。 */
    private ONode call(String action, String channel, String key) throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TuiRuntimeManageTools tools = new TuiRuntimeManageTools(env.appConfig);
        return ONode.ofJson(
                tools.tuiRuntimeManage(action, channel, key, null, null, null, "session-tui"));
    }

    /** 读取指定参数的工具说明文本。 */
    private String paramDescription(Method method, String name) {
        for (Parameter parameter : method.getParameters()) {
            Param annotation = parameter.getAnnotation(Param.class);
            if (annotation != null && name.equals(annotation.name())) {
                return annotation.description();
            }
        }
        throw new IllegalStateException("parameter not found: " + name);
    }
}
