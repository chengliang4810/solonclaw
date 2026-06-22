package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import java.util.List;
import java.util.function.Supplier;

/** 平台适配器注册参数。 */
public class PlatformRegistration {
    /** 平台唯一名称，需与本项目确认保留的国内渠道标识一致。 */
    private final String name;

    /** 展示给 dashboard 的平台名称。 */
    private final String label;

    /** 平台适配器工厂，调用时创建实际渠道适配器实例。 */
    private final Supplier<ChannelAdapter> adapterFactory;

    /** 平台运行所需环境变量名，保持清单顺序用于 setup/doctor 展示。 */
    private List<String> requiredEnv;

    /** 平台安装或配置提示，供 dashboard-first setup 展示。 */
    private String installHint = "";

    /**
     * 创建平台适配器注册信息。
     *
     * @param name 平台唯一名称。
     * @param label 平台展示名称。
     * @param adapterFactory 渠道适配器工厂。
     */
    public PlatformRegistration(
            String name, String label, Supplier<ChannelAdapter> adapterFactory) {
        this.name = name;
        this.label = label;
        this.adapterFactory = adapterFactory;
    }

    /**
     * 读取平台唯一名称。
     *
     * @return 平台唯一名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 读取平台展示名称。
     *
     * @return dashboard 可展示的名称。
     */
    public String getLabel() {
        return label;
    }

    /**
     * 读取渠道适配器工厂。
     *
     * @return 用于创建 ChannelAdapter 的工厂。
     */
    public Supplier<ChannelAdapter> getAdapterFactory() {
        return adapterFactory;
    }

    /**
     * 读取平台必需环境变量名。
     *
     * @return setup/doctor 可展示的环境变量名列表。
     */
    public List<String> getRequiredEnv() {
        return requiredEnv;
    }

    /**
     * 读取安装或配置提示。
     *
     * @return dashboard-first setup 使用的提示文案。
     */
    public String getInstallHint() {
        return installHint;
    }

    /**
     * 设置平台运行所需环境变量名。
     *
     * @param requiredEnv required环境变量参数。
     * @return 当前注册对象，便于链式设置。
     */
    public PlatformRegistration requiredEnv(List<String> requiredEnv) {
        this.requiredEnv = requiredEnv;
        return this;
    }

    /**
     * 设置平台安装或配置提示。
     *
     * @param installHint installHint 参数。
     * @return 当前注册对象，便于链式设置。
     */
    public PlatformRegistration installHint(String installHint) {
        this.installHint = installHint;
        return this;
    }
}
