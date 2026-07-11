package com.jimuqu.solon.claw.profile;

import com.jimuqu.solon.claw.config.AppConfig;
import java.nio.file.Path;
import org.noear.solon.core.Props;

/** 统一保护命名 Profile 网关，避免与正在运行的 default 复用网关重复消费渠道。 */
public final class ProfileGatewayMultiplexGuard {
    /** 工具类不保存实例状态。 */
    private ProfileGatewayMultiplexGuard() {}

    /**
     * 校验指定 Profile 是否可以启动独立网关。
     *
     * @param manager Profile 管理器。
     * @param profile 待启动 Profile。
     * @param force 是否显式绕过保护。
     * @throws Exception default 状态读取失败或发现重复网关。
     */
    public static void requireIndependentGatewayAllowed(
            ProfileManager manager, String profile, boolean force) throws Exception {
        if (force || "default".equals(profile)) {
            return;
        }
        ProfileGatewayStatus defaultGateway = manager.gatewayStatus("default");
        if (!defaultGateway.isRunning() || !defaultMultiplexEnabled(manager.root())) {
            return;
        }
        throw new IllegalStateException(
                "The default gateway is running as a profile multiplexer and already serves profile '"
                        + profile
                        + "'. Manage the default gateway instead, or explicitly force an independent gateway.");
    }

    /** 复用统一 AppConfig 解析 default Profile 开关，保证环境变量和配置优先级一致。 */
    private static boolean defaultMultiplexEnabled(Path defaultHome) {
        Props props = new Props();
        props.loadAddIfAbsent("app.yml");
        props.put("solonclaw.workspace", defaultHome.toAbsolutePath().normalize().toString());
        return AppConfig.loadDetached(props).getGateway().isMultiplexProfiles();
    }
}
