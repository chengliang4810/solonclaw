package com.jimuqu.solon.claw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jimuqu.solon.claw.tool.runtime.MemoryApprovalCoordinator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证前台记忆审批的连接归属、选择范围和故障保留语义。 */
class MemoryApprovalCoordinatorTest {
    /** 同一连接可批准一次，跨连接响应与永久批准均被拒绝。 */
    @Test
    void shouldAcceptOnlyScopedOneShotDecision() throws Exception {
        MemoryApprovalCoordinator coordinator = new MemoryApprovalCoordinator(2_000L);
        Object owner = new Object();
        AtomicReference<MemoryApprovalCoordinator.ApprovalRequest> emitted =
                new AtomicReference<MemoryApprovalCoordinator.ApprovalRequest>();
        coordinator.bindSession("s1", owner, emitted::set);

        CompletableFuture<MemoryApprovalCoordinator.Decision> decision =
                CompletableFuture.supplyAsync(
                        () -> coordinator.request("s1", "12345678", "add", "value"));
        waitFor(emitted);
        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.respondIfPending("s1", "memory:12345678", "always", owner));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        coordinator.respondIfPending(
                                "s1", "memory:12345678", "once", new Object()));
        assertTrue(coordinator.respondIfPending("s1", "memory:12345678", "once", owner));
        assertEquals(MemoryApprovalCoordinator.Decision.APPROVE, decision.get(2, TimeUnit.SECONDS));
        assertFalse(coordinator.respondIfPending("s1", "shell:1", "once", owner));
    }

    /** 发送异常或连接断开必须返回不可用，交由持久化 pending 后续处理。 */
    @Test
    void shouldKeepPendingDecisionUnavailableOnTransportFailure() throws Exception {
        MemoryApprovalCoordinator broken = new MemoryApprovalCoordinator(50L);
        broken.bindSession("s1", new Object(), request -> { throw new IllegalStateException("closed"); });
        assertEquals(
                MemoryApprovalCoordinator.Decision.UNAVAILABLE,
                broken.request("s1", "12345678", "add", "value"));

        MemoryApprovalCoordinator disconnected = new MemoryApprovalCoordinator(2_000L);
        Object owner = new Object();
        AtomicReference<MemoryApprovalCoordinator.ApprovalRequest> emitted = new AtomicReference<>();
        disconnected.bindSession("s2", owner, emitted::set);
        CompletableFuture<MemoryApprovalCoordinator.Decision> decision =
                CompletableFuture.supplyAsync(
                        () -> disconnected.request("s2", "87654321", "remove", "value"));
        waitFor(emitted);
        disconnected.clearOwner(owner);
        assertEquals(MemoryApprovalCoordinator.Decision.UNAVAILABLE, decision.get(2, TimeUnit.SECONDS));
        assertFalse(disconnected.canRequest("s2"));
    }

    /** 等待异步请求完成事件发送。 */
    private void waitFor(AtomicReference<MemoryApprovalCoordinator.ApprovalRequest> request)
            throws Exception {
        long deadline = System.currentTimeMillis() + 1_000L;
        while (request.get() == null && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertTrue(request.get() != null);
    }
}
