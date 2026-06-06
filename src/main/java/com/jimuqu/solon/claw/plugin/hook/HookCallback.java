package com.jimuqu.solon.claw.plugin.hook;

import java.util.Map;

/** 钩子回调接口。 */
@FunctionalInterface
public interface HookCallback {
    /**
     * 响应钩子事件。
     *
     * @param args 工具或命令参数。
     * @return 返回on钩子结果。
     */
    HookResult onHook(Map<String, Object> args);
}
