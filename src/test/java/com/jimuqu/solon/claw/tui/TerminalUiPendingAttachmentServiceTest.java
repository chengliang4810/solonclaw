package com.jimuqu.solon.claw.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.core.model.MessageAttachment;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 验证终端 UI 待提交附件服务的会话隔离、原子消费与边界处理。 */
@DisplayName("TerminalUiPendingAttachmentService 待提交附件服务")
class TerminalUiPendingAttachmentServiceTest {

    private TerminalUiPendingAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new TerminalUiPendingAttachmentService();
    }

    // ── add 操作 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("add - 添加单个附件应返回数量 1")
    void add_singleAttachment_shouldReturnOne() {
        MessageAttachment attachment = createAttachment("image", "/tmp/photo.jpg");

        int count = service.add("session-1", attachment);

        assertThat(count).isEqualTo(1);
        assertThat(service.size("session-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("add - 同一会话添加多个附件应累积计数")
    void add_multipleAttachments_shouldAccumulateCount() {
        MessageAttachment first = createAttachment("image", "/tmp/a.jpg");
        MessageAttachment second = createAttachment("file", "/tmp/b.txt");

        service.add("session-1", first);
        int count = service.add("session-1", second);

        assertThat(count).isEqualTo(2);
        assertThat(service.size("session-1")).isEqualTo(2);
    }

    @Test
    @DisplayName("add - 不同会话应隔离存储")
    void add_differentSessions_shouldIsolateStorage() {
        MessageAttachment attachment1 = createAttachment("image", "/tmp/a.jpg");
        MessageAttachment attachment2 = createAttachment("file", "/tmp/b.txt");

        service.add("session-1", attachment1);
        service.add("session-2", attachment2);

        assertThat(service.size("session-1")).isEqualTo(1);
        assertThat(service.size("session-2")).isEqualTo(1);
    }

    @Test
    @DisplayName("add - sessionId 为 null 应抛出 IllegalArgumentException")
    void add_nullSessionId_shouldThrowException() {
        MessageAttachment attachment = createAttachment("image", "/tmp/a.jpg");

        assertThatThrownBy(() -> service.add(null, attachment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session_id is required");
    }

    @Test
    @DisplayName("add - sessionId 为空白字符串应抛出 IllegalArgumentException")
    void add_blankSessionId_shouldThrowException() {
        MessageAttachment attachment = createAttachment("image", "/tmp/a.jpg");

        assertThatThrownBy(() -> service.add("   ", attachment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session_id is required");
    }

    @Test
    @DisplayName("add - attachment 为 null 应抛出 IllegalArgumentException")
    void add_nullAttachment_shouldThrowException() {
        assertThatThrownBy(() -> service.add("session-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attachment is required");
    }

    // ── drain 操作 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("drain - 原子取出并清空附件")
    void drain_withAttachments_shouldReturnAndClear() {
        MessageAttachment first = createAttachment("image", "/tmp/a.jpg");
        MessageAttachment second = createAttachment("file", "/tmp/b.txt");
        service.add("session-1", first);
        service.add("session-1", second);

        List<MessageAttachment> drained = service.drain("session-1");

        assertThat(drained).hasSize(2);
        assertThat(service.size("session-1")).isZero();
    }

    @Test
    @DisplayName("drain - 无附件时返回空列表")
    void drain_noAttachments_shouldReturnEmptyList() {
        List<MessageAttachment> drained = service.drain("session-1");

        assertThat(drained).isEmpty();
    }

    @Test
    @DisplayName("drain - 返回的列表不可修改")
    void drain_returnedList_shouldBeUnmodifiable() {
        service.add("session-1", createAttachment("image", "/tmp/a.jpg"));

        List<MessageAttachment> drained = service.drain("session-1");

        assertThatThrownBy(() -> drained.add(createAttachment("file", "/tmp/b.txt")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("drain - 多次 drain 应隔离轮次")
    void drain_multipleDrains_shouldIsolateRounds() {
        service.add("session-1", createAttachment("image", "/tmp/a.jpg"));
        List<MessageAttachment> firstDrain = service.drain("session-1");

        service.add("session-1", createAttachment("file", "/tmp/b.txt"));
        List<MessageAttachment> secondDrain = service.drain("session-1");

        assertThat(firstDrain).hasSize(1);
        assertThat(secondDrain).hasSize(1);
        assertThat(firstDrain.get(0).getLocalPath()).isEqualTo("/tmp/a.jpg");
        assertThat(secondDrain.get(0).getLocalPath()).isEqualTo("/tmp/b.txt");
    }

    @Test
    @DisplayName("drain - sessionId 为 null 应抛出 IllegalArgumentException")
    void drain_nullSessionId_shouldThrowException() {
        assertThatThrownBy(() -> service.drain(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session_id is required");
    }

    // ── clear 操作 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("clear - 清理指定会话附件")
    void clear_withSession_shouldRemoveAttachments() {
        service.add("session-1", createAttachment("image", "/tmp/a.jpg"));

        service.clear("session-1");

        assertThat(service.size("session-1")).isZero();
    }

    @Test
    @DisplayName("clear - 不存在的会话不应抛出异常")
    void clear_nonExistentSession_shouldNotThrow() {
        service.clear("non-existent-session");
        // 不应抛出异常
    }

    @Test
    @DisplayName("clear - sessionId 为 null 不应抛出异常")
    void clear_nullSessionId_shouldNotThrow() {
        service.clear(null);
        // 不应抛出异常
    }

    @Test
    @DisplayName("clear - sessionId 为空白字符串不应抛出异常")
    void clear_blankSessionId_shouldNotThrow() {
        service.clear("   ");
        // 不应抛出异常
    }

    // ── size 操作 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("size - 无附件时返回 0")
    void size_noAttachments_shouldReturnZero() {
        assertThat(service.size("session-1")).isZero();
    }

    @Test
    @DisplayName("size - sessionId 为 null 返回 0")
    void size_nullSessionId_shouldReturnZero() {
        assertThat(service.size(null)).isZero();
    }

    @Test
    @DisplayName("size - sessionId 为空白字符串返回 0")
    void size_blankSessionId_shouldReturnZero() {
        assertThat(service.size("   ")).isZero();
    }

    @Test
    @DisplayName("size - sessionId 前后有空格应被规范化")
    void size_sessionIdWithSpaces_shouldNormalize() {
        service.add("session-1", createAttachment("image", "/tmp/a.jpg"));

        assertThat(service.size("  session-1  ")).isEqualTo(1);
    }

    // ── 集成场景 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("集成 - add + drain + size 完整流程")
    void integration_addDrainSize_fullFlow() {
        MessageAttachment attachment1 = createAttachment("image", "/tmp/a.jpg");
        MessageAttachment attachment2 = createAttachment("file", "/tmp/b.txt");

        service.add("session-1", attachment1);
        service.add("session-1", attachment2);
        assertThat(service.size("session-1")).isEqualTo(2);

        List<MessageAttachment> drained = service.drain("session-1");
        assertThat(drained).hasSize(2);
        assertThat(service.size("session-1")).isZero();

        service.add("session-1", createAttachment("video", "/tmp/c.mp4"));
        assertThat(service.size("session-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("集成 - 同会话并发添加与消费不丢附件")
    void integration_concurrentAddAndDrain_shouldNotLoseAttachments() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> add =
                    executor.submit(
                            () -> {
                                start.await();
                                service.add("session-1", createAttachment("image", "/tmp/a.jpg"));
                                return null;
                            });
            Future<List<MessageAttachment>> drain =
                    executor.submit(
                            () -> {
                                start.await();
                                return service.drain("session-1");
                            });

            start.countDown();
            add.get();
            List<MessageAttachment> drained = drain.get();
            assertThat(drained.size() + service.size("session-1")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ── 辅助方法 ────────────────────────────────────────────────────────────────

    private MessageAttachment createAttachment(String kind, String localPath) {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind(kind);
        attachment.setLocalPath(localPath);
        attachment.setOriginalName(localPath.substring(localPath.lastIndexOf('/') + 1));
        return attachment;
    }
}
