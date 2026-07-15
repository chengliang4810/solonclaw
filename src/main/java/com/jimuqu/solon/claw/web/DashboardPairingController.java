package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** 已认证 Dashboard 的 pairing 管理接口。 */
@Controller
public class DashboardPairingController {
    /** pairing 管理服务。 */
    private final DashboardPairingService pairingService;

    /** 创建 pairing 管理控制器。 */
    public DashboardPairingController(DashboardPairingService pairingService) {
        this.pairingService = pairingService;
    }

    /** 列出管理员、待审批申请与已批准用户。 */
    @Mapping(value = "/api/gateway/pairing", method = MethodType.GET)
    public Map<String, Object> list(Context context) {
        return handle(
                context,
                () -> pairingService.list(DashboardProfileContext.requestedProfile(context)));
    }

    /** 可信绑定当前 Profile 的平台主人，并返回欢迎消息投递参数。 */
    @Mapping(value = "/api/gateway/pairing/claim-owner", method = MethodType.POST)
    public Map<String, Object> claimOwner(Context context) {
        return bodyAction(
                context,
                body ->
                        pairingService.claimOwner(
                                DashboardProfileContext.requestedProfile(context, body),
                                text(body, "platform"),
                                text(body, "code")));
    }

    /** 对已绑定主人重发欢迎语，投递目标只从服务端记录读取。 */
    @Mapping(value = "/api/gateway/pairing/welcome/retry", method = MethodType.POST)
    public Map<String, Object> retryWelcome(Context context) {
        return bodyAction(
                context,
                body ->
                        pairingService.retryWelcome(
                                DashboardProfileContext.requestedProfile(context, body),
                                text(body, "platform")));
    }

    /** 将已绑定主人的平台设为当前 Profile 的主要通知渠道。 */
    @Mapping(value = "/api/gateway/pairing/primary", method = MethodType.POST)
    public Map<String, Object> setPrimary(Context context) {
        return bodyAction(
                context,
                body ->
                        pairingService.setPrimary(
                                DashboardProfileContext.requestedProfile(context, body),
                                text(body, "platform")));
    }

    /** 清除当前 Profile 的平台主人绑定。 */
    @Mapping(value = "/api/gateway/pairing/owner", method = MethodType.DELETE)
    public Map<String, Object> clearOwner(Context context) {
        return bodyAction(
                context,
                body ->
                        pairingService.clearOwner(
                                DashboardProfileContext.requestedProfile(context, body),
                                text(body, "platform")));
    }

    /** 解析 JSON 请求体后执行写操作。 */
    private Map<String, Object> bodyAction(Context context, BodyAction action) {
        return handle(context, () -> action.run(DashboardRequestBodies.jsonObjectMap(context)));
    }

    /** 统一映射参数、Profile 和内部异常。 */
    private Map<String, Object> handle(Context context, Action action) {
        try {
            return DashboardResponse.ok(action.run());
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "PAIRING_BAD_REQUEST", e);
        } catch (Exception e) {
            return DashboardResponse.error(context, 500, "PAIRING_OPERATION_FAILED", e);
        }
    }

    /** 从请求体读取可选文本。 */
    private String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    /** 无请求体操作。 */
    private interface Action {
        /** 执行控制器操作。 */
        Map<String, Object> run() throws Exception;
    }

    /** 带请求体操作。 */
    private interface BodyAction {
        /** 执行控制器写操作。 */
        Map<String, Object> run(Map<String, Object> body) throws Exception;
    }
}
