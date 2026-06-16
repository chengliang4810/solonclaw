package com.jimuqu.solon.claw.core.model;

import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 主动协作单次 tick 的上下文快照，供各类采集器读取。 */
@Getter
@Setter
@NoArgsConstructor
public class ProactiveTickContext {
    /** 当前 tick ID。 */
    private String tickId;

    /** 当前 tick 的统一时间戳，单位毫秒。 */
    private long nowMillis;

    /** 当前配置快照。 */
    private AppConfig config;

    /** 当前已配置的 home channel 列表。 */
    private List<HomeChannelRecord> homeChannels = new ArrayList<HomeChannelRecord>();

    /** 最近若干条决策摘要，供采集器评估近期主动触达情况。 */
    private List<ProactiveDecisionRecord> lastDecisionSummaries =
            new ArrayList<ProactiveDecisionRecord>();
}
