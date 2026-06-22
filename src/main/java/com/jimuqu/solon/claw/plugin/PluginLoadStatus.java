package com.jimuqu.solon.claw.plugin;

/** 插件加载诊断状态。 */
public enum PluginLoadStatus {
    /** 插件完成编译、实例化和注册。 */
    LOADED,

    /** 插件因配置、清单或冲突原因被跳过。 */
    SKIPPED,

    /** 插件加载过程中出现异常，相关错误已写入诊断消息。 */
    FAILED
}
