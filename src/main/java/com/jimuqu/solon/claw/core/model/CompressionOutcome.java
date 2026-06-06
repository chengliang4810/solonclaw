package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 单次上下文压缩结果。 */
@Getter
@Setter
@NoArgsConstructor
public class CompressionOutcome {
    /** 记录压缩中的会话。 */
    private SessionRecord session;

    /** 是否启用compressed。 */
    private boolean compressed;

    /** 是否启用skipped。 */
    private boolean skipped;

    /** 是否启用failed。 */
    private boolean failed;

    /** 记录压缩中的warning。 */
    private String warning;

    /** 记录压缩中的错误消息。 */
    private String errorMessage;

    /** 记录压缩中的estimatedtoken。 */
    private int estimatedTokens;

    /** 记录压缩中的thresholdtoken。 */
    private int thresholdTokens;

    /**
     * 执行skipped相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回skipped结果。
     */
    public static CompressionOutcome skipped(SessionRecord session) {
        CompressionOutcome outcome = new CompressionOutcome();
        outcome.setSession(session);
        outcome.setSkipped(true);
        return outcome;
    }

    /**
     * 执行success相关逻辑。
     *
     * @param session 会话参数。
     * @param compressed compressed 参数。
     * @return 返回success结果。
     */
    public static CompressionOutcome success(SessionRecord session, boolean compressed) {
        CompressionOutcome outcome = new CompressionOutcome();
        outcome.setSession(session);
        outcome.setCompressed(compressed);
        outcome.setSkipped(!compressed);
        return outcome;
    }

    /**
     * 执行failed相关逻辑。
     *
     * @param session 会话参数。
     * @param error 错误参数。
     * @return 返回failed结果。
     */
    public static CompressionOutcome failed(SessionRecord session, Throwable error) {
        CompressionOutcome outcome = new CompressionOutcome();
        outcome.setSession(session);
        outcome.setFailed(true);
        outcome.setWarning("上下文压缩摘要生成失败，本轮已继续执行；原始上下文已保留，未丢弃未压缩消息。");
        outcome.setErrorMessage(error == null ? "" : error.getMessage());
        return outcome;
    }
}
