package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.plugin.hook.HookCallback;
import com.jimuqu.solon.claw.plugin.hook.HookResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 钩子注册与分发。线程安全。 */
public class AgentHookRegistry {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(AgentHookRegistry.class);

    /** 保存hooks映射，便于按键快速查询。 */
    private final Map<String, List<HookCallback>> hooks = new ConcurrentHashMap<>();

    /**
     * 执行register相关逻辑。
     *
     * @param hookName 钩子名称参数。
     * @param callback 回调参数。
     */
    public void register(String hookName, HookCallback callback) {
        hooks.computeIfAbsent(hookName, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    /** 观察者模式分发，忽略返回值。 */
    public void invoke(String hookName, Map<String, Object> args) {
        List<HookCallback> callbacks = hooks.get(hookName);
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        for (HookCallback cb : callbacks) {
            try {
                cb.onHook(args);
            } catch (Exception e) {
                log.warn("Hook '{}' callback failed: {}", hookName, e.getMessage());
            }
        }
    }

    /** 拦截模式分发，首个非 null 返回值胜出。 */
    public HookResult invokeWithResult(String hookName, Map<String, Object> args) {
        List<HookCallback> callbacks = hooks.get(hookName);
        if (callbacks == null || callbacks.isEmpty()) {
            return null;
        }
        for (HookCallback cb : callbacks) {
            try {
                HookResult result = cb.onHook(args);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("Hook '{}' callback failed: {}", hookName, e.getMessage());
            }
        }
        return null;
    }

    /** 执行clear相关逻辑。 */
    public void clear() {
        hooks.clear();
    }
}
