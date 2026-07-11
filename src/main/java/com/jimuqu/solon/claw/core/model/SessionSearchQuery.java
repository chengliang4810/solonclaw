package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Dashboard search 过滤条件。 */
@Getter
@Setter
@NoArgsConstructor
public class SessionSearchQuery {
    /** 记录会话搜索查询中的来源键。 */
    private String sourceKey;

    /** 记录会话搜索查询中的会话标识。 */
    private String sessionId;

    /** 记录会话搜索查询中的运行标识。 */
    private String runId;

    /** 记录会话搜索查询中的工具名称。 */
    private String toolName;

    /** 记录会话搜索查询中的渠道。 */
    private String channel;

    /** 记录会话搜索查询中的查询。 */
    private String query;

    /** 记录会话搜索查询中的around消息标识。 */
    private String aroundMessageId;

    /** discovery 模式的时间排序：newest 或 oldest。 */
    private String sort;

    /** scroll 模式锚点两侧各返回的消息数量。 */
    private int window = 5;

    /** discovery 模式允许命中的消息角色，使用逗号分隔。 */
    private String roleFilter;

    /** 目标 Profile 名称；空值表示当前 Profile。 */
    private String profile;

    /** 记录会话搜索查询中的时间From。 */
    private long timeFrom;

    /** 记录会话搜索查询中的时间To。 */
    private long timeTo;

    /** 是否启用summarize。 */
    private boolean summarize;

    /** 记录会话搜索查询中的限制。 */
    private int limit = 10;
}
