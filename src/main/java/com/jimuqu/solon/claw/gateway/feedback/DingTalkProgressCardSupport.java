package com.jimuqu.solon.claw.gateway.feedback;

import org.noear.snack4.ONode;

/** 钉钉长任务进度卡数据辅助。 */
public final class DingTalkProgressCardSupport {
    /** 工具类不允许实例化。 */
    private DingTalkProgressCardSupport() {}

    /**
     * 构建钉钉 AI 卡片需要的 cardData JSON。
     *
     * @param title 卡片标题。
     * @param status 当前进度状态。
     * @param summary 本轮工具调用摘要。
     * @param detail 当前步骤详情或参数预览。
     * @param updatedAt 更新时间文本。
     * @return 可直接写入渠道扩展字段的 JSON 字符串。
     */
    public static String buildCardData(
            String title, String status, String summary, String detail, String updatedAt) {
        ONode cardParams =
                new ONode()
                        .set("title", title)
                        .set("status", status)
                        .set("summary", summary)
                        .set("detail", detail)
                        .set("updatedAt", updatedAt);
        return new ONode()
                .set("cardParamMap", cardParams.toData())
                .set("cardMediaIdParamMap", new java.util.LinkedHashMap<String, Object>())
                .toJson();
    }
}
