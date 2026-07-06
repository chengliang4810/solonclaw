package com.jimuqu.solon.claw.goal;

/**
 * judge 模型有返回但 JSON 不可解析时抛出，供上层累计 consecutiveParseFailures 后自动暂停。
 *
 * <p>这是一个 fail-open 与 fail-loud 的分界：网络/超时/异常一律 fail-open 继续目标， 而「模型确实回了内容、但不是合法 JSON」累计达到上限后自动暂停，避免无谓消耗轮次。
 */
public class GoalJudgeUnparseableException extends RuntimeException {
    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 描述不可解析原因的消息。
     */
    public GoalJudgeUnparseableException(String message) {
        super(message);
    }
}
