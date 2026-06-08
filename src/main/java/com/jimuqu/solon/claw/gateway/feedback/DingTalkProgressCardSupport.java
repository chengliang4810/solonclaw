package com.jimuqu.solon.claw.gateway.feedback;

import org.noear.snack4.ONode;

/** 钉钉长任务进度卡数据辅助。 */
public final class DingTalkProgressCardSupport {
    /** 创建Ding Talk Progress Card辅助实例。 */
    private DingTalkProgressCardSupport() {}

    /**
     * 构建Card Data。
     *
     * @param title title 参数。
     * @param status 状态参数。
     * @param summary 摘要参数。
     * @param detail 详情参数。
     * @param updatedAt updatedAt 参数。
     * @return 返回创建好的Card Data。
     */
    public static String buildCardData(
            String title, String status, String summary, String detail, String updatedAt) {
        return new ONode()
                .set("title", title)
                .set("status", status)
                .set("summary", summary)
                .set("detail", detail)
                .set("updatedAt", updatedAt)
                .toJson();
    }
}
