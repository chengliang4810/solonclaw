package com.jimuqu.solon.claw.gateway.platform.base;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证控制命令路由的基类能力：{@code /stop}、{@code /cancel} 必须能被识别并投递到与各渠道串行入站执行器
 * 相互独立的并发执行器，否则控制命令会排在运行中的任务之后，导致取消逻辑来不及触发。
 */
public class AbstractConfigurableChannelAdapterTest {

    /** 控制命令识别：首个空白分隔的 token 精确匹配（大小写不敏感）。 */
    @Test
    void shouldDetectControlCommandsByFirstToken() {
        TestAdapter adapter = newAdapter();

        // 正例：精确命令、带参数、大小写混写、带前后空白
        assertThat(adapter.isControlCommand("/stop")).isTrue();
        assertThat(adapter.isControlCommand("/stop now")).isTrue();
        assertThat(adapter.isControlCommand("  /stop   now  ")).isTrue();
        assertThat(adapter.isControlCommand("/STOP")).isTrue();
        assertThat(adapter.isControlCommand("/Cancel")).isTrue();
        assertThat(adapter.isControlCommand("/cancel 123")).isTrue();

        // 负例：以命令前缀开头的其它词、其它斜杠命令、普通文本、空白
        assertThat(adapter.isControlCommand("/stopwatch")).isFalse();
        assertThat(adapter.isControlCommand("/canceled")).isFalse();
        assertThat(adapter.isControlCommand("/help")).isFalse();
        assertThat(adapter.isControlCommand("/goal x")).isFalse();
        assertThat(adapter.isControlCommand("hello")).isFalse();
        assertThat(adapter.isControlCommand("")).isFalse();
        assertThat(adapter.isControlCommand(null)).isFalse();
        assertThat(adapter.isControlCommand("请帮我总结 /stop 这段话")).isFalse();
    }

    /** 控制执行器必须与串行入站执行器是不同实例，否则控制命令仍会被串行化。 */
    @Test
    void shouldExposeSeparateControlExecutor() {
        TestAdapter adapter = newAdapter();

        ExecutorService controlExecutor = adapter.controlExecutor();
        ExecutorService inboundExecutor = adapter.newInboundExecutor();

        try {
            assertThat(controlExecutor).isNotNull();
            assertThat(inboundExecutor).isNotNull();
            assertThat(controlExecutor).isNotSameAs(inboundExecutor);
            assertThat(controlExecutor.isShutdown()).isFalse();
        } finally {
            inboundExecutor.shutdownNow();
        }
    }

    /** 控制命令投递不能被持有串行入站执行器的长任务阻塞：模拟一个独占串行线程的任务，再投递控制命令， 控制命令的处理线程必须不同于串行线程，且能在长任务结束前完成。 */
    @Test
    void shouldDispatchControlCommandWithoutWaitingForRunningTask() throws Exception {
        final TestAdapter adapter = newAdapter();
        final ExecutorService inboundExecutor = adapter.newInboundExecutor();
        adapter.bindInboundExecutor(inboundExecutor);

        try {
            // 1) 先提交一个独占串行线程的长任务，使串行队列后续任务必须排队
            final CountDownLatch longTaskRunning = new CountDownLatch(1);
            final CountDownLatch releaseLongTask = new CountDownLatch(1);
            inboundExecutor.submit(
                    new Runnable() {
                        @Override
                        public void run() {
                            longTaskRunning.countDown();
                            try {
                                // 模拟多轮 ReAct 循环独占线程
                                releaseLongTask.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
            assertThat(longTaskRunning.await(5, TimeUnit.SECONDS)).isTrue();

            // 2) 此时投递一条控制命令，验证它能在长任务仍在运行时被处理
            final CountDownLatch controlHandled = new CountDownLatch(1);
            final AtomicReference<String> controlThread = new AtomicReference<String>();
            adapter.setInboundMessageHandler(
                    new InboundMessageHandler() {
                        @Override
                        public void handle(GatewayMessage message) throws Exception {
                            controlThread.set(Thread.currentThread().getName());
                            controlHandled.countDown();
                        }
                    });

            adapter.dispatchInboundControl(
                    new GatewayMessage(PlatformType.FEISHU, "chat-1", "user-1", "/stop"));

            // 控制命令应在串行长任务释放前完成（2 秒足够）
            assertThat(controlHandled.await(5, TimeUnit.SECONDS))
                    .as("控制命令必须被并发处理，不能等待串行长任务结束")
                    .isTrue();
            // 控制命令的处理线程名应包含控制执行器前缀，且与串行线程不同
            assertThat(controlThread.get()).startsWith("channel-control-");

            // 3) 再向串行队列追加一条普通消息，验证它仍被排队（保持原串行语义）
            final AtomicInteger normalHandled = new AtomicInteger(0);
            adapter.setInboundMessageHandler(
                    new InboundMessageHandler() {
                        @Override
                        public void handle(GatewayMessage message) throws Exception {
                            normalHandled.incrementAndGet();
                        }
                    });
            inboundExecutor.submit(
                    new Runnable() {
                        @Override
                        public void run() {
                            adapter.inboundHandle(
                                    new GatewayMessage(PlatformType.FEISHU, "c", "u", "hi"));
                        }
                    });
            // 普通消息不应在长任务释放前被处理
            assertThat(normalHandled.get()).isZero();

            // 释放长任务，串行队列中的普通消息随后处理
            releaseLongTask.countDown();
            adapter.assertNormalHandledEventually(normalHandled);
        } finally {
            inboundExecutor.shutdownNow();
            adapter.shutdownControlExecutor();
        }
    }

    private TestAdapter newAdapter() {
        AppConfig.ChannelConfig config = new AppConfig().getChannels().getFeishu();
        return new TestAdapter(PlatformType.FEISHU, config);
    }

    /** 最小可实例化子类，暴露基类受保护方法，并托管一个串行入站执行器用于对比测试。 */
    private static final class TestAdapter extends AbstractConfigurableChannelAdapter {
        private ExecutorService inboundExecutor;

        private TestAdapter(PlatformType platformType, AppConfig.ChannelConfig config) {
            super(platformType, config);
        }

        @Override
        public boolean isControlCommand(String text) {
            return super.isControlCommand(text);
        }

        @Override
        public ExecutorService controlExecutor() {
            return super.controlExecutor();
        }

        @Override
        public void dispatchInboundControl(GatewayMessage message) {
            super.dispatchInboundControl(message);
        }

        @Override
        public void shutdownControlExecutor() {
            super.shutdownControlExecutor();
        }

        /** 创建一个与各渠道一致的单线程串行入站执行器，用于对比。 */
        private ExecutorService newInboundExecutor() {
            return java.util.concurrent.Executors.newSingleThreadExecutor();
        }

        private void bindInboundExecutor(ExecutorService executor) {
            this.inboundExecutor = executor;
        }

        /** 模拟串行入站执行器中对普通消息的处理器调用。 */
        private void inboundHandle(GatewayMessage message) {
            com.jimuqu.solon.claw.core.service.InboundMessageHandler handler =
                    inboundMessageHandler();
            if (handler != null) {
                try {
                    handler.handle(message);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        /** 等待串行队列中的普通消息最终被处理，最长 5 秒。 */
        private void assertNormalHandledEventually(final AtomicInteger normalHandled)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000L;
            while (normalHandled.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(normalHandled.get()).as("普通消息在长任务释放后应被串行处理").isEqualTo(1);
        }
    }
}
