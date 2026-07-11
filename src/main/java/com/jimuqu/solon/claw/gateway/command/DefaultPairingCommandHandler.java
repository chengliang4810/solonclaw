package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;

/** 处理网关配对命令，隔离授权配对的参数解析和用法文本。 */
final class DefaultPairingCommandHandler {
    /** 网关授权服务，用于执行配对列表、审批和撤销。 */
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

        PlatformType targetPlatform = message.getPlatform();
        if (parts.length >= 2) {
            targetPlatform = PlatformType.fromName(parts[1]);
        }

        if (GatewayCommandConstants.ACTION_LIST.equals(action)) {
            return gatewayAuthorizationService.pairingList(message, targetPlatform);
        }
        if (GatewayCommandConstants.ACTION_PENDING.equals(action)) {
            return gatewayAuthorizationService.pairingPending(message, targetPlatform);
        }
        if (GatewayCommandConstants.ACTION_APPROVED.equals(action)) {
            return gatewayAuthorizationService.pairingApproved(message, targetPlatform);
        }
        if (GatewayCommandConstants.ACTION_APPROVE.equals(action)) {
            if (parts.length < 3) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_PAIRING
                                + " approve <platform> <code>");
            }
            return gatewayAuthorizationService.pairingApprove(message, targetPlatform, parts[2]);
        }
        if (GatewayCommandConstants.ACTION_REVOKE.equals(action)) {
            if (parts.length < 3) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_PAIRING
                                + " revoke <platform> <userId>");
            }
            return gatewayAuthorizationService.pairingRevoke(message, targetPlatform, parts[2]);
        }
        if ("clear-pending".equals(action) || "clear_pending".equals(action)) {
            return gatewayAuthorizationService.pairingClearPending(message, targetPlatform);
        }

        return GatewayReply.error(usage());
    }

    /** 返回 pairing 命令用法文本。 */
    private String usage() {
        return "用法："
                + GatewayCommandConstants.SLASH_PAIRING
                + " [list|pending|approve|revoke|approved|clear-pending] ...";
    }
}
