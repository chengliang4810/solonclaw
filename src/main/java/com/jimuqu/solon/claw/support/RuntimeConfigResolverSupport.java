package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;

/** 运行时配置解析器辅助逻辑，统一从 AppConfig 定位 workspace/config.yml。 */
public final class RuntimeConfigResolverSupport {
    /** 工具类不允许创建实例。 */
    private RuntimeConfigResolverSupport() {}

    /**
     * 基于应用配置获取当前工作区配置解析器。
     *
     * @param appConfig 应用运行配置。
     * @return 当前工作区配置解析器。
     */
    public static RuntimeConfigResolver fromAppConfig(AppConfig appConfig) {
        String home =
                appConfig == null || appConfig.getRuntime() == null
                        ? ""
                        : appConfig.getRuntime().getHome();
        return RuntimeConfigResolver.open(home);
    }
}
