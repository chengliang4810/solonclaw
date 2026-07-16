package com.jimuqu.solon.claw.core.model;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 平台 home channel 绑定记录。 */
@Getter
@Setter
@NoArgsConstructor
public class HomeChannelRecord {
    /** 所属平台。 */
    private PlatformType platform;

    /** 目标会话 ID。 */
    private String chatId;

    /** 目标线程或话题 ID。 */
    private String threadId;

    /** 会话名称。 */
    private String chatName;

    /** 是否为当前 Profile 唯一的主要通知渠道。 */
    private boolean primary;

    /** 更新时间。 */
    private long updatedAt;
}
