package com.jimuqu.solon.claw.profile;

import org.noear.solon.Solon;
import org.noear.solon.core.AppContext;

/** 按当前 Profile 作用域解析 Bean，避免命名 Profile 的延迟工具回退到 default 容器。 */
public final class ProfileBeanResolver {
    /** 工具类不保存实例状态。 */
    private ProfileBeanResolver() {}

    /** 返回当前 Profile 子容器；仅未进入 Profile 作用域时回退主 Solon 容器。 */
    public static AppContext currentContext() {
        ProfileRuntimeScope.Context scoped = ProfileRuntimeScope.current();
        if (scoped != null) {
            return scoped.getAppContext();
        }
        return Solon.context();
    }

    /**
     * 从当前逻辑运行时解析指定 Bean。
     *
     * @param type Bean 类型。
     * @param <T> Bean 类型参数。
     * @return 当前 Profile 的 Bean；容器或 Bean 不存在时返回 null。
     */
    public static <T> T getBean(Class<T> type) {
        if (type == null) {
            return null;
        }
        try {
            AppContext context = currentContext();
            return context == null ? null : context.getBean(type);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
