package com.jimuqu.solon.claw.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证复用网关单 PID 状态只在启用时公开承载的 Profile。 */
class GatewayMultiplexRuntimeStatusTest {
    /** 临时 Profile 根目录。 */
    @TempDir Path tempDir;

    /** 复用状态写入 served_profiles，便于 CLI 与 Dashboard 判断单进程拓扑。 */
    @Test
    void writesServedProfilesForMultiplexGateway() {
        GatewayRuntimeStatusService service = service(tempDir);
        service.setServedProfiles(Arrays.asList("default", "coder"));

        service.writeState("running", "ready");

        assertThat(service.readState().get("served_profiles"))
                .isEqualTo(Arrays.asList("default", "coder"));
    }

    /** 独立网关模式不写空 served_profiles。 */
    @Test
    void omitsServedProfilesForIndependentGateway() {
        GatewayRuntimeStatusService service = service(tempDir);

        service.writeState("running", "ready");

        Map<String, Object> state = service.readState();
        assertThat(state).doesNotContainKey("served_profiles");
    }

    /** 默认构造器必须从 Profile 工作区推导身份，不能把命名 Profile 误写成 default。 */
    @Test
    void derivesNamedProfileFromRuntimeHome() {
        String previousRoot = System.getProperty("solonclaw.profile.root");
        Path worker = tempDir.resolve("profiles/worker");
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(worker.toString());
        config.getDashboard().setBindPort(8080);
        System.setProperty("solonclaw.profile.root", tempDir.toString());
        try {
            GatewayRuntimeStatusService service = new GatewayRuntimeStatusService(config);
            service.writeState("running", "ready");

            assertThat(service.readState().get("profile")).isEqualTo("worker");
            assertThat(service.readState().get("workspace"))
                    .isEqualTo(worker.toAbsolutePath().normalize().toString());
        } finally {
            if (previousRoot == null) {
                System.clearProperty("solonclaw.profile.root");
            } else {
                System.setProperty("solonclaw.profile.root", previousRoot);
            }
        }
    }

    /** 创建测试状态服务。 */
    private GatewayRuntimeStatusService service(Path home) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getDashboard().setBindPort(8080);
        return new GatewayRuntimeStatusService(config, "default");
    }
}
