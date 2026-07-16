package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;

/** Dashboard 控制器的 Profile 路由公共基类，提供 resolve / isTarget / client / withoutProfile 等复用方法。 */
public abstract class AbstractDashboardProfileController {

    /** 解析请求指定的 Profile；为空时保持当前运行。 */
    @Inject(required = false)
    protected DashboardProfileContext profileContext;

    /** 解析 query 或非空 body.profile，body 优先。 */
    protected DashboardProfileContext.Scope resolve(Context context, Map<String, Object> body) {
        String requested = DashboardProfileContext.requestedProfile(context, body);
        if (profileContext == null) {
            if (StrUtil.isBlank(requested) || "current".equalsIgnoreCase(requested)) {
                return null;
            }
            throw new IllegalStateException("Dashboard Profile scope is unavailable.");
        }
        return profileContext.resolve(requested);
    }

    /** 判断请求是否需要交给目标 Profile 独立网关。 */
    protected boolean isTarget(DashboardProfileContext.Scope scope) {
        return scope != null && !scope.isCurrent();
    }

    /** 创建绑定目标 Profile 的回环客户端。 */
    protected DashboardProfileGatewayClient client(
            Context context, DashboardProfileContext.Scope scope) {
        return new DashboardProfileGatewayClient(
                profileContext, scope, context == null ? null : context.header("Authorization"));
    }

    /** 复制 JSON 写请求并移除机器级路由字段。 */
    protected Map<String, Object> withoutProfile(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (body != null) {
            result.putAll(body);
        }
        result.remove("profile");
        return result;
    }
}
