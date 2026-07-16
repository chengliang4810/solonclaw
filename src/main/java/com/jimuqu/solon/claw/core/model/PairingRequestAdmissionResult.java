package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** pairing 请求原子准入结果，区分创建成功、用户冷却和平台容量不足。 */
@Getter
@RequiredArgsConstructor
public final class PairingRequestAdmissionResult {
    /** pairing 请求准入状态。 */
    public enum Status {
        /** 请求已保存且对应 code 可以返回给用户。 */
        CREATED,

        /** 同一用户仍处于请求冷却窗口。 */
        RATE_LIMITED,

        /** 平台待处理请求已达到容量上限。 */
        CAPACITY_REACHED
    }

    /** 本次准入状态。 */
    private final Status status;

    /** 被限流时距离下次允许请求的剩余毫秒数，其他状态为零。 */
    private final long retryAfterMillis;

    /** 创建成功结果。 */
    public static PairingRequestAdmissionResult created() {
        return new PairingRequestAdmissionResult(Status.CREATED, 0L);
    }

    /** 创建用户冷却结果。 */
    public static PairingRequestAdmissionResult rateLimited(long retryAfterMillis) {
        return new PairingRequestAdmissionResult(
                Status.RATE_LIMITED, Math.max(1L, retryAfterMillis));
    }

    /** 创建平台容量不足结果。 */
    public static PairingRequestAdmissionResult capacityReached() {
        return new PairingRequestAdmissionResult(Status.CAPACITY_REACHED, 0L);
    }
}
