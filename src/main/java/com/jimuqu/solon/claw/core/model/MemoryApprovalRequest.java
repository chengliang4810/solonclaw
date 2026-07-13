package com.jimuqu.solon.claw.core.model;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 对外展示的待审批记忆变更，仅包含脱敏且限长的内容预览。 */
@Getter
@AllArgsConstructor
public class MemoryApprovalRequest {
    /** 待审批变更标识。 */
    private final String id;

    /** 产生该变更的核心子系统。 */
    private final String subsystem;

    /** 记忆写入动作。 */
    private final String action;

    /** 脱敏且限长的变更摘要。 */
    private final String summary;

    /** 变更来源边界。 */
    private final String origin;

    /** 变更暂存时间，使用 Unix epoch 秒。 */
    private final long createdAt;

    /** 脱敏且限长的动作参数。 */
    private final Map<String, String> payload;
}
