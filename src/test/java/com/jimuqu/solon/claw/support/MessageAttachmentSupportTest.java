package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.MessageAttachment;
import org.junit.jupiter.api.Test;

public class MessageAttachmentSupportTest {
    @Test
    void shouldRedactSensitiveAttachmentPathInMissingFileMessage() {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setOriginalName("id_rsa-token=ghp_attachmentpath12345");
        attachment.setLocalPath(
                "C:/Users/test/.ssh/id_rsa-token=ghp_attachmentpath12345");

        String message = MessageAttachmentSupport.fileNotFoundMessage("Feishu", attachment);

        assertThat(message)
                .isEqualTo("Feishu attachment file not found: [redacted-sensitive-name] (path=[redacted-sensitive-path])")
                .doesNotContain("ghp_attachmentpath12345")
                .doesNotContain(".ssh")
                .doesNotContain("id_rsa-token");
    }

    @Test
    void shouldKeepOnlySafeAttachmentFileNameInMissingFileMessage() {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setOriginalName("report.pdf");
        attachment.setLocalPath("D:/runtime/cache/media/report.pdf");

        String message = MessageAttachmentSupport.fileNotFoundMessage("WeCom", attachment);

        assertThat(message).isEqualTo("WeCom attachment file not found: report.pdf");
    }

    @Test
    void shouldRedactTokenLikeAttachmentPathInMissingFileMessage() {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setOriginalName("");
        attachment.setLocalPath("D:/runtime/cache/token=ghp_attachmentmissing12345/secret.txt");

        String message = MessageAttachmentSupport.fileNotFoundMessage("QQBot", attachment);

        assertThat(message)
                .contains("QQBot attachment file not found")
                .contains("[redacted-sensitive-path]")
                .doesNotContain("ghp_attachmentmissing12345")
                .doesNotContain("token=")
                .doesNotContain("secret.txt");
    }
}
