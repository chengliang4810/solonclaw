package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayApprovalCardSupportTest {
    /** 请求扩展参数声明审批卡片模式时应识别为审批卡片投递。 */
    @Test
    void shouldDetectApprovalCardRequest() {
        DeliveryRequest request = new DeliveryRequest();
        request.getChannelExtras()
                .put("mode", "  " + DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);

        assertThat(GatewayApprovalCardSupport.isApprovalCardRequest(request)).isTrue();
        assertThat(GatewayApprovalCardSupport.isApprovalCardRequest(new DeliveryRequest()))
                .isFalse();
        assertThat(GatewayApprovalCardSupport.isApprovalCardRequest(null)).isFalse();
    }

    /** 永久授权开关缺失时默认允许，显式 false 时关闭。 */
    @Test
    void shouldParseApprovalAllowAlways() {
        Map<String, Object> extras = new LinkedHashMap<String, Object>();

        assertThat(GatewayApprovalCardSupport.approvalCardAllowAlways(extras)).isTrue();

        extras.put("approvalAllowAlways", "false");

        assertThat(GatewayApprovalCardSupport.approvalCardAllowAlways(extras)).isFalse();
    }
}
