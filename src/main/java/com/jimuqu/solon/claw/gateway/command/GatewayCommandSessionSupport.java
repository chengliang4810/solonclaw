package com.jimuqu.solon.claw.gateway.command;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;

/** slash command 会话绑定辅助逻辑。 */
final class GatewayCommandSessionSupport {
    /** 工具类不允许创建实例。 */
    private GatewayCommandSessionSupport() {}

    /**
     * 获取来源键绑定会话；不存在时立即创建新会话。
     *
     * @param sessionRepository 会话仓储。
     * @param sourceKey 渠道来源键。
     * @return 已存在或新建的会话。
     */
    static SessionRecord requireSession(SessionRepository sessionRepository, String sourceKey)
            throws Exception {
        SessionRecord session = sessionRepository.getBoundSession(sourceKey);
        if (session == null) {
            session = sessionRepository.bindNewSession(sourceKey);
        }
        return session;
    }
}
