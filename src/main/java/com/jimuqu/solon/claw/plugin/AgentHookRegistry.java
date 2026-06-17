package com.jimuqu.solon.claw.plugin;

import cn.hutool.core.collection.CollUtil;
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
    /** 钩子回调执行失败时使用的日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AgentHookRegistry.class);

    /** 钩子名称到回调列表的线程安全映射，支持插件运行期追加回调。 */
    private final Map<String, List<HookCallback>> hooks = new ConcurrentHashMap<>();

    /**
     * 注册钩子回调。
     *
     * @param hookName 钩子名称。
     * @param callback 钩子触发时执行的回调。
     */
    public void register(String hookName, HookCallback callback) {
        hooks.computeIfAbsent(hookName, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    /** 观察者模式分发，忽略返回值。 */
    public void invoke(String hookName, Map<String, Object> args) {
        List<HookCallback> callbacks = hooks.get(hookName);
        if (CollUtil.isEmpty(callbacks)) {
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
        if (CollUtil.isEmpty(callbacks)) {
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

    /** 清空所有钩子回调，通常在插件管理器关闭时调用。 */
    public void clear() {
        hooks.clear();
    }
}
