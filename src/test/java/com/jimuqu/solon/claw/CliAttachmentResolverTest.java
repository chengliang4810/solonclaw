package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.cli.CliAttachmentResolver;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class CliAttachmentResolverTest {
    @Test
    void shouldResolveQuotedLocalPathIntoAttachmentAndHideRawPath() throws Exception {
        AppConfig config = testConfig();
        File file = new File(config.getRuntime().getHome(), "report.txt");
        Files.write(file.toPath(), "hello".getBytes("UTF-8"));
        CliAttachmentResolver resolver = resolver(config);

        CliAttachmentResolver.ResolvedInput resolved =
                resolver.resolve("请看 \"" + file.getAbsolutePath() + "\"");

        assertThat(resolved.getText()).contains("[附件: report.txt]");
        assertThat(resolved.getText()).doesNotContain(file.getAbsolutePath());
        List<MessageAttachment> attachments = resolved.getAttachments();
        assertThat(attachments).hasSize(1);
        MessageAttachment attachment = attachments.get(0);
        assertThat(attachment.getOriginalName()).isEqualTo("report.txt");
        assertThat(attachment.getKind()).isEqualTo("file");
        assertThat(attachment.getMimeType()).isEqualTo("text/plain");
        assertThat(new File(attachment.getLocalPath())).exists();
    }

    @Test
    void shouldRedactSecretLikeAttachmentNameAfterResolvingLocalPath() throws Exception {
        AppConfig config = testConfig();
        File file = new File(config.getRuntime().getHome(), "report-ghp_attachmentsecret12345.txt");
        Files.write(file.toPath(), "hello".getBytes("UTF-8"));
        CliAttachmentResolver resolver = resolver(config);

        CliAttachmentResolver.ResolvedInput resolved =
                resolver.resolve("请看 " + file.getAbsolutePath());

        assertThat(resolved.getText())
                .contains("[附件: report-redacted.txt]")
                .doesNotContain("ghp_attachmentsecret12345")
                .doesNotContain(file.getAbsolutePath());
        assertThat(resolved.getAttachments()).hasSize(1);
        MessageAttachment attachment = resolved.getAttachments().get(0);
        assertThat(attachment.getOriginalName())
                .isEqualTo("report-redacted.txt")
                .doesNotContain("ghp_attachmentsecret12345");
        assertThat(new File(attachment.getLocalPath()).getName())
                .doesNotContain("ghp_attachmentsecret12345");
    }

    @Test
    void shouldResolveFileUriIntoAttachment() throws Exception {
        AppConfig config = testConfig();
        File file = new File(config.getRuntime().getHome(), "diagram.png");
        Files.write(
                file.toPath(),
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        CliAttachmentResolver resolver = resolver(config);

        CliAttachmentResolver.ResolvedInput resolved =
                resolver.resolve("分析 " + file.toURI().toString());

        assertThat(resolved.getText()).contains("[附件: diagram.png]");
        assertThat(resolved.getAttachments()).hasSize(1);
        assertThat(resolved.getAttachments().get(0).getKind()).isEqualTo("image");
        assertThat(resolved.getAttachments().get(0).getMimeType()).isEqualTo("image/png");
    }

    @Test
    void shouldDeduplicateRepeatedLocalAttachmentPaths() throws Exception {
        AppConfig config = testConfig();
        File file = new File(config.getRuntime().getHome(), "duplicate.txt");
        Files.write(file.toPath(), "hello".getBytes("UTF-8"));
        CliAttachmentResolver resolver = resolver(config);

        CliAttachmentResolver.ResolvedInput resolved =
                resolver.resolve(file.getAbsolutePath() + " " + file.getAbsolutePath());

        assertThat(resolved.getAttachments()).hasSize(1);
        assertThat(resolved.getText()).doesNotContain(file.getAbsolutePath());
    }

    @Test
    void shouldIgnoreMissingPathLikePlainText() throws Exception {
        AppConfig config = testConfig();
        CliAttachmentResolver resolver = resolver(config);
        String input = "这里提到 /tmp/not-exist-jimuqu-file.txt 但不用附加";

        CliAttachmentResolver.ResolvedInput resolved = resolver.resolve(input);

        assertThat(resolved.getText()).isEqualTo(input);
        assertThat(resolved.getAttachments()).isEmpty();
    }

    @Test
    void shouldBlockCredentialPathBeforeCachingAttachment() throws Exception {
        AppConfig config = testConfig();
        File sshDir = new File(config.getRuntime().getHome(), ".ssh");
        Files.createDirectories(sshDir.toPath());
        File privateKey = new File(sshDir, "id_rsa-token=ghp_attachmentsecret12345");
        Files.write(privateKey.toPath(), "secret".getBytes("UTF-8"));
        CliAttachmentResolver resolver = resolver(config);

        assertThatThrownBy(() -> resolver.resolve("上传 " + privateKey.getAbsolutePath()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全策略阻断")
                .hasMessageNotContaining("ghp_attachmentsecret12345")
                .hasMessageNotContaining(privateKey.getAbsolutePath());
    }

    @Test
    void shouldRedactAttachmentPolicyMessagesBeforeReturningToTerminal() throws Exception {
        AppConfig config = testConfig();
        File file = new File(config.getRuntime().getHome(), "safe.txt");
        Files.write(file.toPath(), "secret".getBytes("UTF-8"));
        CliAttachmentResolver resolver =
                new CliAttachmentResolver(
                        new AttachmentCacheService(config),
                        new SecurityPolicyService(config) {
                            @Override
                            public FileVerdict checkPath(String rawPath, boolean writeLike) {
                                return FileVerdict.block(
                                        rawPath,
                                        "blocked token=ghp_attachmentpolicy12345 path=" + rawPath);
                            }
                        });

        assertThatThrownBy(() -> resolver.resolve("上传 " + file.getAbsolutePath()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token=***")
                .hasMessageNotContaining("ghp_attachmentpolicy12345")
                .hasMessageNotContaining(file.getAbsolutePath());
    }

    @Test
    void shouldPreviewAttachmentPathsWithoutCaching() throws Exception {
        AppConfig config = testConfig();
        File file = new File(config.getRuntime().getHome(), "preview.txt");
        Files.write(file.toPath(), "hello".getBytes("UTF-8"));
        CliAttachmentResolver resolver = resolver(config);

        List<CliAttachmentResolver.AttachmentPreview> previews =
                resolver.preview("预检 " + file.getAbsolutePath());

        assertThat(previews).hasSize(1);
        CliAttachmentResolver.AttachmentPreview preview = previews.get(0);
        assertThat(preview.getStatus()).isEqualTo("allowed");
        assertThat(preview.getName()).isEqualTo("preview.txt");
        assertThat(preview.getKind()).isEqualTo("file");
        assertThat(preview.getMimeType()).isEqualTo("text/plain");
        assertThat(preview.getSizeBytes()).isEqualTo(5L);
        assertThat(resolver.renderPreview(file.getAbsolutePath()))
                .contains("附件预检")
                .contains("allowed")
                .contains("preview.txt")
                .contains("size=5");
        assertThat(new File(config.getRuntime().getCacheDir(), "media")).doesNotExist();
    }

    @Test
    void shouldPreviewBlockedAndMissingAttachmentPaths() throws Exception {
        AppConfig config = testConfig();
        File sshDir = new File(config.getRuntime().getHome(), ".ssh");
        Files.createDirectories(sshDir.toPath());
        File privateKey = new File(sshDir, "id_ed25519-token=ghp_previewsecret12345");
        Files.write(privateKey.toPath(), "secret".getBytes("UTF-8"));
        File missing = new File(config.getRuntime().getHome(), "missing-token=ghp_missingsecret12345.txt");
        CliAttachmentResolver resolver = resolver(config);

        String preview = resolver.renderPreview(privateKey.getAbsolutePath() + " " + missing.getAbsolutePath());

        assertThat(preview)
                .contains("blocked")
                .contains("***")
                .doesNotContain("ghp_previewsecret12345")
                .doesNotContain("ghp_missingsecret12345")
                .doesNotContain(privateKey.getAbsolutePath())
                .contains("missing")
                .contains("token=***");
    }

    private CliAttachmentResolver resolver(AppConfig config) {
        return new CliAttachmentResolver(
                new AttachmentCacheService(config), new SecurityPolicyService(config));
    }

    private AppConfig testConfig() throws Exception {
        AppConfig config = new AppConfig();
        File runtimeHome = Files.createTempDirectory("jimuqu-cli-attachment").toFile();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setCacheDir(new File(runtimeHome, "cache").getAbsolutePath());
        config.getRuntime().setConfigFile(new File(runtimeHome, "config.yml").getAbsolutePath());
        return config;
    }

    @Test
    void shouldExposeTerminalPastePolicyBoundaries() {
        Map<String, Object> summary = CliAttachmentResolver.policySummary();

        assertThat(summary.get("fileUriPercentDecoded")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("tildeHomeExpansion")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("canonicalPathResolvedBeforePolicy")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("duplicatePathDeduplicated")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("cacheWriteAfterPolicyOnly")).isEqualTo(Boolean.TRUE);
    }
}
