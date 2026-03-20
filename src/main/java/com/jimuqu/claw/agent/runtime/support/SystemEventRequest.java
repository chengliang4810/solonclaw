package com.jimuqu.claw.agent.runtime.support;

import com.jimuqu.claw.agent.model.enums.RuntimeSourceKind;
import com.jimuqu.claw.agent.model.enums.SystemEventPolicy;
import com.jimuqu.claw.agent.model.route.ReplyTarget;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 描述一次系统事件执行请求。
 */
@Data
@NoArgsConstructor
public class SystemEventRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private RuntimeSourceKind sourceKind;
    private SystemEventPolicy policy = SystemEventPolicy.INTERNAL_ONLY;
    private String sessionKey;
    private ReplyTarget replyTarget;
    private String content;
    private long sourceUserVersion;
    private String relatedRunId;
    private boolean allowNotifyUser;
    private boolean wakeImmediately = true;
}
