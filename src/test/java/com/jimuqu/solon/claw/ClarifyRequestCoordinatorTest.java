package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.tool.runtime.ClarifyRequestCoordinator;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证 clarify request_id 回流、归属校验和生命周期清理。 */
class ClarifyRequestCoordinatorTest {
    @Test
    void responseCompletesOnlyTheOwningSessionAndConnection() throws Exception {
        ClarifyRequestCoordinator coordinator = new ClarifyRequestCoordinator(2_000L);
        Object owner = new Object();
        AtomicReference<ClarifyRequestCoordinator.ClarifyRequest> emitted =
                new AtomicReference<ClarifyRequestCoordinator.ClarifyRequest>();
        coordinator.bindSession("session-a", owner, emitted::set);

        CompletableFuture<String> answer =
                CompletableFuture.supplyAsync(
                        () ->
                                coordinator.request(
                                        "session-a",
                                        "Which target?",
                                        Arrays.asList("dev", "main")));
        ClarifyRequestCoordinator.ClarifyRequest request = waitForRequest(emitted);

        assertThatThrownBy(
                        () ->
                                coordinator.respond(
                                        "session-b", request.getRequestId(), "main", owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another session");
        assertThatThrownBy(
                        () ->
                                coordinator.respond(
                                        "session-a", request.getRequestId(), "main", new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another connection");

        coordinator.respond("session-a", request.getRequestId(), "main", owner);

        assertThat(answer.get(1, TimeUnit.SECONDS)).isEqualTo("main");
        assertThat(coordinator.pendingCount()).isZero();
    }

    @Test
    void clearingOneSessionReleasesItsWaiterWithoutAffectingAnother() throws Exception {
        ClarifyRequestCoordinator coordinator = new ClarifyRequestCoordinator(2_000L);
        Object ownerA = new Object();
        Object ownerB = new Object();
        AtomicReference<ClarifyRequestCoordinator.ClarifyRequest> requestA =
                new AtomicReference<ClarifyRequestCoordinator.ClarifyRequest>();
        AtomicReference<ClarifyRequestCoordinator.ClarifyRequest> requestB =
                new AtomicReference<ClarifyRequestCoordinator.ClarifyRequest>();
        coordinator.bindSession("session-a", ownerA, requestA::set);
        coordinator.bindSession("session-b", ownerB, requestB::set);
        CompletableFuture<String> answerA =
                CompletableFuture.supplyAsync(() -> coordinator.request("session-a", "A?", null));
        CompletableFuture<String> answerB =
                CompletableFuture.supplyAsync(() -> coordinator.request("session-b", "B?", null));
        waitForRequest(requestA);
        ClarifyRequestCoordinator.ClarifyRequest emittedB = waitForRequest(requestB);

        coordinator.clearSession("session-a");

        assertThat(answerA.get(1, TimeUnit.SECONDS)).isEmpty();
        assertThat(answerB).isNotDone();
        coordinator.respond("session-b", emittedB.getRequestId(), "continue", ownerB);
        assertThat(answerB.get(1, TimeUnit.SECONDS)).isEqualTo("continue");
        AtomicReference<ClarifyRequestCoordinator.ClarifyRequest> retry =
                new AtomicReference<ClarifyRequestCoordinator.ClarifyRequest>();
        coordinator.bindSession("session-a", ownerA, retry::set);
        CompletableFuture<String> retryAnswer =
                CompletableFuture.supplyAsync(
                        () -> coordinator.request("session-a", "Retry?", null));
        ClarifyRequestCoordinator.ClarifyRequest retryRequest = waitForRequest(retry);
        coordinator.respond("session-a", retryRequest.getRequestId(), "yes", ownerA);
        assertThat(retryAnswer.get(1, TimeUnit.SECONDS)).isEqualTo("yes");
    }

    @Test
    void timeoutRemovesPendingRequest() {
        ClarifyRequestCoordinator coordinator = new ClarifyRequestCoordinator(20L);
        Object owner = new Object();
        coordinator.bindSession("session-timeout", owner, request -> {});

        assertThat(coordinator.request("session-timeout", "Still there?", null)).isEmpty();
        assertThat(coordinator.pendingCount()).isZero();
    }

    /** 等待异步工具线程发出请求，避免测试依赖固定 sleep。 */
    private ClarifyRequestCoordinator.ClarifyRequest waitForRequest(
            AtomicReference<ClarifyRequestCoordinator.ClarifyRequest> request) throws Exception {
        long deadline = System.currentTimeMillis() + 1_000L;
        while (request.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(request.get()).isNotNull();
        return request.get();
    }
}
