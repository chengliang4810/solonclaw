package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 用于测试运行停止语义的阻塞模型网关。 */
public class BlockingLlmGateway implements LlmGateway {
    /** 标记模型调用已经进入阻塞区。 */
    private final CountDownLatch started = new CountDownLatch(1);

    /** 记录阻塞线程是否收到中断。 */
    private volatile boolean interrupted;

    /**
     * 等待阻塞模型调用开始。
     *
     * @param timeout 等待时长。
     * @param unit 时间单位。
     * @return 调用是否已开始。
     */
    public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return started.await(timeout, unit);
    }

    /**
     * 判断阻塞模型调用是否被中断。
     *
     * @return 已收到中断时返回 true。
     */
    public boolean isInterrupted() {
        return interrupted;
    }

    /**
     * 模拟长时间运行的聊天请求，直到测试发出中断。
     *
     * @param session 当前会话。
     * @param systemPrompt 系统提示词。
     * @param userMessage 用户消息。
     * @param toolObjects 工具对象。
     * @return 本方法正常情况下不会返回。
     */
    @Override
    public LlmResult chat(
            SessionRecord session, String systemPrompt, String userMessage, List<Object> toolObjects)
            throws Exception {
        started.countDown();
        try {
            while (true) {
                Thread.sleep(1000L);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        }
    }

    /**
     * 恢复请求沿用相同阻塞行为，保证停止测试覆盖同一中断路径。
     *
     * @param session 当前会话。
     * @param systemPrompt 系统提示词。
     * @param toolObjects 工具对象。
     * @return 本方法正常情况下不会返回。
     */
    @Override
    public LlmResult resume(SessionRecord session, String systemPrompt, List<Object> toolObjects)
            throws Exception {
        return chat(session, systemPrompt, null, toolObjects);
    }
}
