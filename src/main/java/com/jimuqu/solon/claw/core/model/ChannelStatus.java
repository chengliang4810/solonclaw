package com.jimuqu.solon.claw.core.model;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 渠道运行状态快照。 */
@Getter
@Setter
@NoArgsConstructor
public class ChannelStatus {
    /** 所属平台。 */
    private PlatformType platform;

    /** 是否已启用。 */
    private boolean enabled;

    /** 是否已经建立连接。 */
    private boolean connected;

    /** 连接详情说明。 */
    private String detail;

    /** 渠道配置与接入准备状态。 */
    private String setupState;

    /** 当前渠道连接模式。 */
    private String connectionMode;

    /** 缺失的关键配置路径。 */
    private List<String> missingConfig = new ArrayList<String>();

    /** 当前渠道已实现的能力标签。 */
    private List<String> features = new ArrayList<String>();

    /** 最近一次错误码。 */
    private String lastErrorCode;

    /** 最近一次错误消息。 */
    private String lastErrorMessage;

    /** 是否正在等待自动重连。 */
    private boolean reconnecting;

    /** 当前重连尝试次数。 */
    private int reconnectAttempt;

    /** 最近一次重连调度时间戳。 */
    private long lastReconnectAt;

    /** 下一次重连计划时间戳。 */
    private long nextReconnectAt;

    /** 最近一次重连错误。 */
    private String lastReconnectError;

    /** 当前是否处于连续失败后的重连熔断冷却期。 */
    private boolean reconnectCircuitOpen;

    /** 当前连续连接失败次数。 */
    private int reconnectFailureCount;

    /** 最近一次打开重连熔断的时间戳。 */
    private long reconnectCircuitOpenedAt;

    /** 当前重连熔断冷却截止时间戳。 */
    private long reconnectCircuitOpenUntil;

    /** 最近一次由连接 watchdog 补回重连任务的时间戳。 */
    private long reconnectWatchdogRecoveredAt;

    /**
     * 创建渠道状态实例，并注入运行所需依赖。
     *
     * @param platform 平台参数。
     * @param enabled 启用状态开关值。
     * @param connected connected 参数。
     * @param detail 详情参数。
     */
    public ChannelStatus(PlatformType platform, boolean enabled, boolean connected, String detail) {
        this.platform = platform;
        this.enabled = enabled;
        this.connected = connected;
        this.detail = detail;
    }
}
