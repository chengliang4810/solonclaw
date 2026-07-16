package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;

/** 拒绝渠道内 pairing 管理，主人绑定只能在可信本机或 Dashboard 完成。 */
final class DefaultPairingCommandHandler {
    /** 网关授权服务，用于返回固定的渠道内认领拒绝提示。 */
    private final GatewayAuthorizationService gatewayAuthorizationService;

    /**
     * 创建配对命令处理器。
     *
     * @param gatewayAuthorizationService 网关授权服务。
     */
    DefaultPairingCommandHandler(GatewayAuthorizationService gatewayAuthorizationService) {
        this.gatewayAuthorizationService = gatewayAuthorizationService;
    }

    /** 执行pairing相关命令相关逻辑。 */
    GatewayReply handle(GatewayMessage message, String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length == 0 || StrUtil.isBlank(parts[0])) {
            return GatewayReply.error(usage());
        }
        String action = parts[0].trim().toLowerCase();

        if (GatewayCommandConstants.ACTION_CLAIM_ADMIN.equals(action)) {
            return gatewayAuthorizationService.claimAdmin(message);
        }

        return GatewayReply.error(usage());
    }

    /** 返回 pairing 命令用法文本。 */
    private String usage() {
        return "渠道内不支持 pairing 管理，请在 Dashboard 或本机终端绑定主人。";
    }
}
