package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.Map;

/** 消息渠道审批卡片投递参数解析工具。 */
public final class GatewayApprovalCardSupport {
    /** 工具类不允许创建实例。 */
    private GatewayApprovalCardSupport() {}

    /**
     * 判断投递请求是否为危险命令审批卡片。
     *
     * @param request 投递请求。
     * @return 请求扩展参数声明审批卡片模式时返回 true。
     */
    public static boolean isApprovalCardRequest(DeliveryRequest request) {
        Map<String, Object> extras = request == null ? null : request.getChannelExtras();
        Object mode = extras == null ? null : extras.get("mode");
        return DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD.equalsIgnoreCase(
                stringValue(mode));
    }

    /**
     * 解析审批卡片是否允许永久授权，字段缺失时保持默认允许。
     *
     * @param extras 渠道扩展参数。
     * @return 允许展示永久授权动作时返回 true。
     */
    public static boolean approvalCardAllowAlways(Map<String, Object> extras) {
        Object value = extras == null ? null : extras.get("approvalAllowAlways");
        return value == null || Boolean.parseBoolean(stringValue(value));
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 原始值。
     * @return 规范化后的字符串。
     */
    private static String stringValue(Object value) {
        return StrUtil.nullToEmpty(value == null ? null : String.valueOf(value)).trim();
    }
}
