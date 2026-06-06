package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import java.util.List;
import java.util.function.Supplier;

/** 平台适配器注册参数。 */
public class PlatformRegistration {
    /** 记录平台Registration中的名称。 */
    private final String name;

    /** 记录平台Registration中的label。 */
    private final String label;

    /** 记录平台Registration中的适配器工厂。 */
    private final Supplier<ChannelAdapter> adapterFactory;

    /** 保存required环境变量集合，维持调用顺序或去重语义。 */
    private List<String> requiredEnv;

    /** 记录平台Registration中的installHint。 */
    private String installHint = "";

    /**
     * 创建平台Registration实例，并注入运行所需依赖。
     *
     * @param name 名称参数。
     * @param label label 参数。
     * @param adapterFactory adapterFactory 参数。
     */
    public PlatformRegistration(
            String name, String label, Supplier<ChannelAdapter> adapterFactory) {
        this.name = name;
        this.label = label;
        this.adapterFactory = adapterFactory;
    }

    /**
     * 读取名称。
     *
     * @return 返回读取到的名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 读取Label。
     *
     * @return 返回读取到的Label。
     */
    public String getLabel() {
        return label;
    }

    /**
     * 读取适配器工厂。
     *
     * @return 返回读取到的适配器工厂。
     */
    public Supplier<ChannelAdapter> getAdapterFactory() {
        return adapterFactory;
    }

    /**
     * 读取Required Env。
     *
     * @return 返回读取到的Required Env。
     */
    public List<String> getRequiredEnv() {
        return requiredEnv;
    }

    /**
     * 读取Install Hint。
     *
     * @return 返回读取到的Install Hint。
     */
    public String getInstallHint() {
        return installHint;
    }

    /**
     * 执行required环境变量相关逻辑。
     *
     * @param requiredEnv required环境变量参数。
     * @return 返回required Env结果。
     */
    public PlatformRegistration requiredEnv(List<String> requiredEnv) {
        this.requiredEnv = requiredEnv;
        return this;
    }

    /**
     * 安装Hint。
     *
     * @param installHint installHint 参数。
     * @return 返回install Hint结果。
     */
    public PlatformRegistration installHint(String installHint) {
        this.installHint = installHint;
        return this;
    }
}
