package com.jimuqu.claw.config.props;

import com.jimuqu.claw.agent.model.enums.SystemEventPolicy;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 描述系统事件执行默认配置。
 */
@Data
@NoArgsConstructor
public class SystemEventsProperties implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 通用 system event 的默认策略。 */
    private SystemEventPolicy defaultPolicy = SystemEventPolicy.INTERNAL_ONLY;
}
