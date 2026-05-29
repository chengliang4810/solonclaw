package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import java.util.List;
import java.util.function.Supplier;

/** 平台适配器注册参数。 */
public class PlatformRegistration {
    private final String name;
    private final String label;
    private final Supplier<ChannelAdapter> adapterFactory;
    private List<String> requiredEnv;
    private String installHint = "";

    public PlatformRegistration(String name, String label, Supplier<ChannelAdapter> adapterFactory) {
        this.name = name;
        this.label = label;
        this.adapterFactory = adapterFactory;
    }

    public String getName() { return name; }
    public String getLabel() { return label; }
    public Supplier<ChannelAdapter> getAdapterFactory() { return adapterFactory; }
    public List<String> getRequiredEnv() { return requiredEnv; }
    public String getInstallHint() { return installHint; }

    public PlatformRegistration requiredEnv(List<String> requiredEnv) {
        this.requiredEnv = requiredEnv;
        return this;
    }

    public PlatformRegistration installHint(String installHint) {
        this.installHint = installHint;
        return this;
    }
}
