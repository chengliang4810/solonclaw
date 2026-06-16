package com.jimuqu.solon.claw.proactive;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import java.util.List;

/** 主动协作观测采集器 SPI，用于按来源收集可持久化的观测信号。 */
public interface ProactiveObservationCollector {
    /** 返回采集器稳定名称，用于持久化和排障。 */
    String name();

    /** 根据当前配置判断采集器是否启用。 */
    boolean enabled(AppConfig config);

    /**
     * 采集主动协作观测结果。
     *
     * @param context 当前 tick 的上下文快照。
     * @return 返回采集到的观测列表。
     * @throws Exception 采集失败时抛出异常，由上层统一兜底记录失败观测。
     */
    List<ProactiveObservation> collect(ProactiveTickContext context) throws Exception;
}
