package com.jimuqu.solon.claw.plugin.hook;

import java.util.Map;

/** 钩子回调接口。 */
@FunctionalInterface
public interface HookCallback {
    HookResult onHook(Map<String, Object> args);
}
