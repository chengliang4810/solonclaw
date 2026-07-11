package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.tui.TerminalUiPendingAttachmentService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证终端 UI 待提交附件按会话和提交轮次隔离。 */
class TerminalUiPendingAttachmentServiceTest {
    @Test
    void drainConsumesOnlyTheTargetSessionOnce() {
        TerminalUiPendingAttachmentService service = new TerminalUiPendingAttachmentService();
        MessageAttachment first = attachment("first.png");
        MessageAttachment second = attachment("second.png");
        service.add("session-a", first);
        service.add("session-b", second);

        List<MessageAttachment> drained = service.drain("session-a");

        assertThat(drained).containsExactly(first);
        assertThat(service.drain("session-a")).isEmpty();
        assertThat(service.drain("session-b")).containsExactly(second);
    }

    @Test
    void attachmentAddedAfterDrainBelongsToTheNextTurn() {
        TerminalUiPendingAttachmentService service = new TerminalUiPendingAttachmentService();
        MessageAttachment first = attachment("first.png");
        MessageAttachment next = attachment("next.png");
        service.add("session-a", first);

        List<MessageAttachment> currentTurn = service.drain("session-a");
        service.add("session-a", next);

        assertThat(currentTurn).containsExactly(first);
        assertThat(service.drain("session-a")).containsExactly(next);
    }

    @Test
    void clearRemovesOnlyTheRequestedSession() {
        TerminalUiPendingAttachmentService service = new TerminalUiPendingAttachmentService();
        service.add("session-a", attachment("a.png"));
        service.add("session-b", attachment("b.png"));

        service.clear("session-a");

        assertThat(service.size("session-a")).isZero();
        assertThat(service.size("session-b")).isEqualTo(1);
    }

    /** 构造可按对象身份验证的最小图片附件。 */
    private MessageAttachment attachment(String name) {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind("image");
        attachment.setOriginalName(name);
        return attachment;
    }
}
