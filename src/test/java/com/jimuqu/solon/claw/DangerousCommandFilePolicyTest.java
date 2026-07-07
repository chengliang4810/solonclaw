package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.DangerousCommandApprovalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalDecision;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalJudge;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import java.io.File;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

public class DangerousCommandFilePolicyTest {
    @AfterEach
    void clearThreadPolicyApprovals() {
        DangerousCommandApprovalTestSupport.clearThreadPolicyApprovals();
    }

    // shouldBlockCredentialFilePathsForFileTools 已删除：file_read 读凭据文件已放宽
    // （对齐 hermes"读非安全边界"），读 .ssh/id_ed25519 现在放行。写阻断由 assertWriteDenied 系列覆盖。

    @Test
    void shouldBlockJimuquCliCredentialFilePathsForFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        // assertFileReadDenied（file_read 读凭据）断言已移除：读已放宽（对齐 hermes"读非安全边界"）。
        // 下方"非凭据说明文件读放行"断言保留。
        Map<String, Object> authNotes = new LinkedHashMap<String, Object>();
        authNotes.put("fileName", "docs/auth.md");
        Map<String, Object> tokenNotes = new LinkedHashMap<String, Object>();
        tokenNotes.put("fileName", "docs/token-notes.md");
        Map<String, Object> configExample = new LinkedHashMap<String, Object>();
        configExample.put("fileName", "config.example.yml");

        assertThat(securityPolicyService.checkFileToolArgs("file_read", authNotes).isAllowed())
                .isTrue();
        assertThat(securityPolicyService.checkFileToolArgs("file_read", tokenNotes).isAllowed())
                .isTrue();
        assertThat(securityPolicyService.checkFileToolArgs("file_read", configExample).isAllowed())
                .isTrue();
    }

    @Test
    void shouldBlockConfiguredTerminalCredentialFilesForFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setCredentialFiles(Arrays.asList("credentials/oauth.json"));
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> absoluteArgs = new LinkedHashMap<String, Object>();
        absoluteArgs.put(
                "fileName",
                new File(env.appConfig.getRuntime().getHome(), "credentials/oauth.json")
                        .getAbsolutePath());
        Map<String, Object> siblingArgs = new LinkedHashMap<String, Object>();
        siblingArgs.put("fileName", "credentials/oauth-notes.md");

        // file_read 读凭据文件（relative/nested）已放宽（对齐 hermes"读非安全边界"），断言已移除。
        // file_write 写凭据文件仍阻断（absolute），sibling 非凭据读放行，均保留。
        SecurityPolicyService.FileVerdict absolute =
                securityPolicyService.checkFileToolArgs("file_write", absoluteArgs);
        SecurityPolicyService.FileVerdict sibling =
                securityPolicyService.checkFileToolArgs("file_read", siblingArgs);

        assertThat(absolute.isAllowed()).isFalse();
        assertThat(absolute.getMessage()).contains("凭据");
        assertThat(sibling.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockJimuquDevicePathsThatCanHangFileReads() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> zeroArgs = new LinkedHashMap<String, Object>();
        zeroArgs.put("fileName", "/dev/zero");
        Map<String, Object> procFdArgs = new LinkedHashMap<String, Object>();
        procFdArgs.put("path", "/proc/self/fd/0");
        Map<String, Object> projectArgs = new LinkedHashMap<String, Object>();
        projectArgs.put("fileName", "docs/dev/zero.txt");

        SecurityPolicyService.FileVerdict zero =
                securityPolicyService.checkFileToolArgs("file_read", zeroArgs);
        SecurityPolicyService.FileVerdict procFd =
                securityPolicyService.checkFileToolArgs("mcp_remote_tool", procFdArgs);
        SecurityPolicyService.FileVerdict project =
                securityPolicyService.checkFileToolArgs("file_read", projectArgs);

        assertThat(zero.isAllowed()).isFalse();
        assertThat(zero.getMessage()).contains("设备文件");
        assertThat(zero.getPath()).isEqualTo("/dev/zero");
        assertThat(procFd.isAllowed()).isFalse();
        assertThat(procFd.getPath()).isEqualTo("/proc/self/fd/0");
        assertThat(project.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockFilePathsContainingControlCharactersWithCanonicalConfigPathSecurity()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> newlineArgs = new LinkedHashMap<String, Object>();
        newlineArgs.put("fileName", "credentials/token\n.json");
        Map<String, Object> escapeArgs = new LinkedHashMap<String, Object>();
        escapeArgs.put("path", "logs/\u001B]0;hidden\u0007report.txt");
        Map<String, Object> normalArgs = new LinkedHashMap<String, Object>();
        normalArgs.put("fileName", "docs/report.txt");

        SecurityPolicyService.FileVerdict newline =
                securityPolicyService.checkFileToolArgs("file_read", newlineArgs);
        SecurityPolicyService.FileVerdict escape =
                securityPolicyService.checkFileToolArgs("file_write", escapeArgs);
        SecurityPolicyService.FileVerdict normal =
                securityPolicyService.checkFileToolArgs("file_read", normalArgs);

        assertThat(newline.isAllowed()).isFalse();
        assertThat(newline.getMessage()).contains("非法字符");
        assertThat(escape.isAllowed()).isFalse();
        assertThat(escape.getMessage()).contains("非法字符");
        assertThat(normal.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockSkillsHubInternalCacheReadsWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> relativeHub = new LinkedHashMap<String, Object>();
        relativeHub.put("fileName", "skills/.hub/index-cache/catalog.json");
        Map<String, Object> absoluteHub = new LinkedHashMap<String, Object>();
        absoluteHub.put(
                "fileName",
                new File(env.appConfig.getRuntime().getSkillsDir(), ".hub/tap.json")
                        .getAbsolutePath());
        Map<String, Object> skillFile = new LinkedHashMap<String, Object>();
        skillFile.put("fileName", "skills/demo/SKILL.md");
        Map<String, Object> projectNotes = new LinkedHashMap<String, Object>();
        projectNotes.put("fileName", "docs/skills/.hub-notes.md");
        Map<String, Object> projectHub = new LinkedHashMap<String, Object>();
        projectHub.put("fileName", "docs/skills/.hub/readme.md");

        SecurityPolicyService.FileVerdict relative =
                securityPolicyService.checkFileToolArgs("file_read", relativeHub);
        SecurityPolicyService.FileVerdict absolute =
                securityPolicyService.checkFileToolArgs("file_read", absoluteHub);
        SecurityPolicyService.FileVerdict skill =
                securityPolicyService.checkFileToolArgs("file_read", skillFile);
        SecurityPolicyService.FileVerdict notes =
                securityPolicyService.checkFileToolArgs("file_read", projectNotes);
        SecurityPolicyService.FileVerdict hubNotes =
                securityPolicyService.checkFileToolArgs("file_read", projectHub);

        assertThat(relative.isAllowed()).isFalse();
        assertThat(relative.getMessage()).contains("Skills Hub");
        assertThat(absolute.isAllowed()).isFalse();
        assertThat(absolute.getMessage()).contains("Skills Hub");
        assertThat(skill.isAllowed()).isTrue();
        assertThat(notes.isAllowed()).isTrue();
        assertThat(hubNotes.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockJimuquWriteDeniedSystemPathsForFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        assertWriteDenied(securityPolicyService, "/etc/shadow");
        assertWriteDenied(securityPolicyService, "/etc/passwd");
        assertWriteDenied(securityPolicyService, "/etc/hosts");
        assertWriteDenied(securityPolicyService, "/etc/resolv.conf");
        assertWriteDenied(securityPolicyService, "/etc/sudoers");
        assertWriteDenied(securityPolicyService, "/etc/sudoers.d/custom");
        assertWriteDenied(securityPolicyService, "/etc/systemd/system/evil.service");
        assertWriteDenied(securityPolicyService, "/boot/grub/grub.cfg");
        assertWriteDenied(securityPolicyService, "/bin/payload");
        assertWriteDenied(securityPolicyService, "/usr/bin/payload");
        assertWriteDenied(securityPolicyService, "/usr/local/bin/payload");
        assertWriteDenied(securityPolicyService, "/usr/local/sbin/payload");
        assertWriteDenied(securityPolicyService, "/usr/lib/systemd/system/evil.service");
        assertWriteDenied(securityPolicyService, "/private/etc/hosts");
        assertWriteDenied(securityPolicyService, "/private/var/root-owned");
        assertWriteDenied(securityPolicyService, "/var/run/docker.sock");
        assertWriteDenied(securityPolicyService, "/run/docker.sock");
        assertWriteDenied(securityPolicyService, "/run/containerd/containerd.sock");
        assertWriteDenied(securityPolicyService, "/run/podman/podman.sock");
        assertWriteDenied(securityPolicyService, "/var/run/cri-dockerd.sock");
        assertWriteDenied(securityPolicyService, "/var/run/crio/crio.sock");
        assertWriteDenied(securityPolicyService, "//./pipe/docker_engine");
        assertWriteDenied(securityPolicyService, "\\\\.\\pipe\\docker_engine");
        assertWriteDenied(securityPolicyService, "npipe:////./pipe/docker_engine");
        assertWriteDenied(securityPolicyService, "npipe://./pipe/docker_engine");
    }

    // shouldBlockLocalManagementEndpointReadsForFileTools 已删除：管理套接字/命名管道读已放宽
    // （对齐 hermes"读非安全边界"，isLocalManagementSocket/isLocalManagementPipe 仅在写时阻断）。
    // 写阻断由 shouldBlockJimuquWriteDeniedSystemPathsForFileTools 覆盖（assertWriteDenied）。

    @Test
    void shouldBlockRawBlockDeviceWritesForAllFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        assertWriteDenied(securityPolicyService, "/dev/sda");
        assertWriteDenied(securityPolicyService, "/dev/sda1");
        assertWriteDenied(securityPolicyService, "/dev/nvme0n1");
        assertWriteDenied(securityPolicyService, "/dev/nvme0n1p1");
        assertWriteDenied(securityPolicyService, "/dev/mmcblk0p1");

        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", "/dev/sda-notes.txt");
        SecurityPolicyService.FileVerdict safe =
                securityPolicyService.checkFileToolArgs("file_write", args);

        assertThat(safe.getMessage()).doesNotContain("裸块设备");
        assertThat(safe.isAllowed()).isTrue();
    }

    @Test
    void shouldExposeLocalManagementEndpointPathPolicySummary() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        Map<String, Object> summary = securityPolicyService.pathPolicySummary();

        assertThat(summary.get("localManagementSocketReadBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("localManagementSocketWriteBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("localManagementSocketAccessBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("localManagementPipeReadBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("localManagementPipeWriteBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("localManagementPipeAccessBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(((Integer) summary.get("localManagementSocketPathCount")).intValue())
                .isGreaterThan(0);
        assertThat(((Integer) summary.get("localManagementPipePathCount")).intValue())
                .isGreaterThan(0);
        assertThat(((Integer) summary.get("writeDeniedWindowsPrefixCount")).intValue())
                .isGreaterThan(0);
        assertThat(String.valueOf(summary.get("localManagementSocketPathSamples")))
                .contains("docker.sock");
        assertThat(String.valueOf(summary.get("localManagementPipePathSamples")))
                .contains("docker_engine");
        assertThat(String.valueOf(summary.get("writeDeniedWindowsPrefixSamples")))
                .contains("c:/windows/");
        assertThat(String.valueOf(summary.get("description")))
                .contains("local management endpoints");
    }

    @Test
    void shouldBlockJimuquWriteDeniedHomeFilesForFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        assertWriteDenied(securityPolicyService, "~/.bashrc");
        assertWriteDenied(securityPolicyService, "~/.zshrc");
        assertWriteDenied(securityPolicyService, "~/.profile");
        assertWriteDenied(securityPolicyService, "~/.bash_profile");
        assertWriteDenied(securityPolicyService, "~/.zprofile");
        assertWriteDenied(securityPolicyService, "$HOME/.npmrc");
        assertWriteDenied(securityPolicyService, "$HOME/.pypirc");
        assertWriteDenied(securityPolicyService, "$HOME/.pgpass");
    }

    @Test
    void shouldAllowOrdinaryProjectWritesDespiteWriteDenyList() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> projectArgs = new LinkedHashMap<String, Object>();
        projectArgs.put("fileName", "src/main.py");
        Map<String, Object> configArgs = new LinkedHashMap<String, Object>();
        configArgs.put("fileName", ".jimuqu/config.yml");
        Map<String, Object> projectProfileArgs = new LinkedHashMap<String, Object>();
        projectProfileArgs.put("fileName", "fixtures/.bashrc");

        SecurityPolicyService.FileVerdict project =
                securityPolicyService.checkFileToolArgs("file_write", projectArgs);
        SecurityPolicyService.FileVerdict config =
                securityPolicyService.checkFileToolArgs("file_write", configArgs);
        SecurityPolicyService.FileVerdict projectProfile =
                securityPolicyService.checkFileToolArgs("file_write", projectProfileArgs);

        assertThat(project.isAllowed()).isTrue();
        assertThat(config.isAllowed()).isTrue();
        assertThat(projectProfile.isAllowed()).isTrue();
    }

    @Test
    void shouldApplyWritePolicyToFileToolAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File workspaceRootDir = workspaceBoundaryParent("file-tool-aliases");
        File workspace = new File(workspaceRootDir, "workspace").getCanonicalFile();
        File outsideFile = new File(workspaceRootDir, "outside-workspace/file.txt").getCanonicalFile();
        FileUtil.mkdir(workspace);
        FileUtil.mkdir(outsideFile.getParentFile());
        env.appConfig.getWorkspace().setDir(workspace.getAbsolutePath());
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> outsideArgs = new LinkedHashMap<String, Object>();
        outsideArgs.put("fileName", outsideFile.getAbsolutePath());
        Map<String, Object> credentialArgs = new LinkedHashMap<String, Object>();
        credentialArgs.put("fileName", ".env.local");

        for (String toolName :
                Arrays.asList(
                        "write_file", "delete_file", "file_remove", "remove_file", "unlink_file")) {
            SecurityPolicyService.FileVerdict outside =
                    securityPolicyService.checkFileToolArgs(toolName, outsideArgs);
            SecurityPolicyService.FileVerdict credential =
                    securityPolicyService.checkFileToolArgs(toolName, credentialArgs);

            assertThat(outside.isAllowed()).as(toolName).isTrue();
            assertThat(credential.isAllowed()).as(toolName).isFalse();
            assertThat(credential.getMessage()).as(toolName).contains("凭据");
            assertThat(credential.getPath()).as(toolName).isEqualTo(".env.local");
        }
    }

    @Test
    void shouldApplyWritePolicyWhenGenericToolArgsDeclareWriteIntent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File workspaceRootDir = workspaceBoundaryParent("generic-write-intent");
        File workspace = new File(workspaceRootDir, "workspace").getCanonicalFile();
        File outsideDir = new File(workspaceRootDir, "outside-workspace").getCanonicalFile();
        FileUtil.mkdir(workspace);
        FileUtil.mkdir(outsideDir);
        env.appConfig.getWorkspace().setDir(workspace.getAbsolutePath());
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        File writeFile = new File(outsideDir, "file.txt").getCanonicalFile();
        File toolNameWriteFile = new File(outsideDir, "tool-name-write.txt").getCanonicalFile();
        File outputFilePath = new File(outsideDir, "output.txt").getCanonicalFile();
        Map<String, Object> genericWrite = new LinkedHashMap<String, Object>();
        genericWrite.put("action", "write");
        genericWrite.put("file_path", writeFile.getAbsolutePath());
        Map<String, Object> nestedPatch = new LinkedHashMap<String, Object>();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("operation", "patch");
        payload.put(
                "paths",
                Arrays.asList(
                        new File(workspace, "app.txt").getAbsolutePath(),
                        "/etc/systemd/evil.service"));
        nestedPatch.put("payload", payload);
        Map<String, Object> genericRead = new LinkedHashMap<String, Object>();
        genericRead.put("action", "read");
        genericRead.put("file_path", writeFile.getAbsolutePath());
        Map<String, Object> nestedWriteTool = new LinkedHashMap<String, Object>();
        nestedWriteTool.put("tool_name", "write_file");
        Map<String, Object> nestedWriteArgs = new LinkedHashMap<String, Object>();
        nestedWriteArgs.put("path", toolNameWriteFile.getAbsolutePath());
        nestedWriteTool.put("tool_args", nestedWriteArgs);
        Map<String, Object> outputFileWrite = new LinkedHashMap<String, Object>();
        outputFileWrite.put("action", "save");
        outputFileWrite.put("output_file", outputFilePath.getAbsolutePath());
        Map<String, Object> destinationWrite = new LinkedHashMap<String, Object>();
        destinationWrite.put("operation", "write");
        destinationWrite.put("destination", ".env.local");

        SecurityPolicyService.FileVerdict write =
                securityPolicyService.checkFileToolArgs("mcp_remote_tool", genericWrite);
        SecurityPolicyService.FileVerdict patch =
                securityPolicyService.checkFileToolArgs("tool_gateway", nestedPatch);
        SecurityPolicyService.FileVerdict read =
                securityPolicyService.checkFileToolArgs("mcp_remote_tool", genericRead);
        SecurityPolicyService.FileVerdict toolNameWrite =
                securityPolicyService.checkFileToolArgs("tool_gateway", nestedWriteTool);
        SecurityPolicyService.FileVerdict outputFile =
                securityPolicyService.checkFileToolArgs("mcp_remote_tool", outputFileWrite);
        SecurityPolicyService.FileVerdict destination =
                securityPolicyService.checkFileToolArgs("mcp_remote_tool", destinationWrite);

        assertThat(write.isAllowed()).isTrue();
        assertThat(patch.isAllowed()).isFalse();
        assertThat(patch.getMessage()).contains("敏感系统文件");
        assertThat(patch.getPath()).isEqualTo("/etc/systemd/evil.service");
        assertThat(read.isAllowed()).isTrue();
        assertThat(toolNameWrite.isAllowed()).isTrue();
        assertThat(outputFile.isAllowed()).isTrue();
        assertThat(destination.isAllowed()).isFalse();
        assertThat(destination.getMessage()).contains("凭据");
        assertThat(destination.getPath()).isEqualTo(".env.local");
    }

    @Test
    void shouldRequireApprovalForWorkspaceSymlinkEscapeWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File safeRoot = workspaceBoundaryWorkspace("symlink-escape");
        File outside = new File(safeRoot.getParentFile(), "workspace-outside").getCanonicalFile();
        FileUtil.mkdir(outside);
        File outsideFile = new File(outside, "secret.txt");
        FileUtil.writeUtf8String("secret\n", outsideFile);
        File link = new File(safeRoot, "linked-outside");
        boolean symlinkCreated = false;
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath());
            symlinkCreated = true;
        } catch (UnsupportedOperationException ignored) {
            // Windows test environments may disallow symlink creation.
        } catch (java.io.IOException ignored) {
            // Windows without Developer Mode/Admin often rejects symlink creation.
        }
        if (!symlinkCreated) {
            return;
        }
        env.appConfig.getWorkspace().setDir(safeRoot.getAbsolutePath());
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", new File(link, outsideFile.getName()).getAbsolutePath());

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("file_write", args);

        assertFileApprovalRequired(verdict, "workspace_outside_write");
    }

    @Test
    void shouldBlockPathTraversalForFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", "../workspace/config.yml");

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("file_write", args);

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("路径遍历");
    }

    @Test
    void shouldInspectNestedToolArgumentsForUnsafePaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        // mcp_remote_tool 为非 write-like 工具，嵌套/数组读凭据路径（.env.local、~/.ssh/id_ed25519）
        // 已放宽（对齐 hermes"读非安全边界"），nested/array 读阻断断言已移除。
        // 下方嵌套 patch 写凭据文件（.env）仍阻断，保留。
        Map<String, Object> nestedPatchCall = new LinkedHashMap<String, Object>();
        nestedPatchCall.put("tool_name", "patch");
        Map<String, Object> nestedPatchArgs = new LinkedHashMap<String, Object>();
        nestedPatchArgs.put(
                "patch",
                "*** Begin Patch\n"
                        + "*** Add File: .env\n"
                        + "+TOKEN=secret\n"
                        + "*** End Patch\n");
        nestedPatchCall.put("tool_args", nestedPatchArgs);

        SecurityPolicyService.FileVerdict nestedPatchVerdict =
                securityPolicyService.checkFileToolArgs("tool_gateway", nestedPatchCall);

        assertThat(nestedPatchVerdict.isAllowed()).isFalse();
        assertThat(nestedPatchVerdict.getPath()).isEqualTo(".env");
        assertThat(nestedPatchVerdict.getMessage()).contains("凭据");
    }

    @Test
    void shouldInspectJimuquPatchPathsForCredentialFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("mode", "patch");
        args.put(
                "patch",
                "*** Begin Patch\n"
                        + "*** Update File: .env.production\n"
                        + "@@ token @@\n"
                        + "-OLD\n"
                        + "+NEW\n"
                        + "*** End Patch");

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("patch", args);

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("敏感");
        assertThat(verdict.getPath()).isEqualTo(".env.production");
    }

    @Test
    void shouldInspectPatchTargetsForCommonCredentialJsonFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("mode", "patch");
        args.put(
                "patch",
                "*** Begin Patch\n"
                        + "*** Add File: config/secrets.json\n"
                        + "+{\"token\":\"secret\"}\n"
                        + "*** End Patch");

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("patch", args);

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("凭据");
        assertThat(verdict.getPath()).isEqualTo("config/secrets.json");
    }

    @Test
    void shouldInspectGitRenamePatchTargetsForCredentialFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("operation", "patch");
        args.put(
                "diff",
                "diff --git a/example.env b/.env\n"
                        + "similarity index 100%\n"
                        + "rename from example.env\n"
                        + "rename to .env\n");

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("tool_gateway", args);

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("凭据");
        assertThat(verdict.getPath()).isEqualTo(".env");
    }

    @Test
    void shouldInspectGitCopyPatchTargetsForCredentialFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("operation", "patch");
        args.put(
                "diff",
                "diff --git a/template.env b/.env.local\n"
                        + "similarity index 100%\n"
                        + "copy from template.env\n"
                        + "copy to .env.local\n");

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("tool_gateway", args);

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("凭据");
        assertThat(verdict.getPath()).isEqualTo(".env.local");
    }

    // shouldBlockCredentialPathsInsideShellCommands 已删除：命令中凭据路径经读意图匹配器
    // （checkPath(.., false)）判定，读已放宽（对齐 hermes"读非安全边界"），cat ~/.aws/credentials 现在放行。

    @Test
    void shouldBlockJimuquCliCredentialPathsInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        // 命令中凭据路径读已放宽（对齐 hermes"读非安全边界"），cat/type/Get-Content 读凭据的阻断
        // 断言已移除。下方"非凭据说明文件读放行"断言保留。
        SecurityPolicyService.FileVerdict safeAuthDoc =
                securityPolicyService.checkCommandPaths("cat docs/auth.md");
        SecurityPolicyService.FileVerdict safeTokenDoc =
                securityPolicyService.checkCommandPaths("cat docs/token-notes.md");

        assertThat(safeAuthDoc.isAllowed()).isTrue();
        assertThat(safeTokenDoc.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockBareCredentialFileNamesInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        // 命令中凭据文件名读已放宽（对齐 hermes"读非安全边界"），cat/type/grep/openssl -in 等读凭据的
        // 阻断断言已移除。下方"非凭据文件读放行"断言保留。
        SecurityPolicyService.FileVerdict safe =
                securityPolicyService.checkCommandPaths("cat config.example.yml > backup.yml");
        SecurityPolicyService.FileVerdict safeCertificate =
                securityPolicyService.checkCommandPaths("openssl x509 -in public-cert.pem -text");

        assertThat(safe.isAllowed()).isTrue();
        assertThat(safeCertificate.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockConfiguredCredentialFilesInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setCredentialFiles(Arrays.asList("credentials/oauth.json"));
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        // 命令中配置的凭据文件读已放宽（对齐 hermes"读非安全边界"），cat/type/curl --upload-file/
        // @file/--identity-file/--ssh-key-file 等读凭据的阻断断言已移除。下方"凭据文件 .example 读放行"保留。
        SecurityPolicyService.FileVerdict safe =
                securityPolicyService.checkCommandPaths("cat docs/credentials/oauth.json.example");

        assertThat(safe.isAllowed()).isTrue();
    }

    // shouldBlockWindowsCredentialPathsInsideShellCommands 已删除：命令中 Windows 凭据路径经读意图
    // 匹配器判定，读已放宽（对齐 hermes"读非安全边界"），type 读 ssh key/hosts.yml 现在放行。

    @Test
    void shouldBlockRelativeCredentialDirectoryPathsInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        // 命令中相对凭据目录读已放宽（对齐 hermes"读非安全边界"），cat/type/Get-Content 读
        // .ssh/.aws/.docker/gcloud 等的阻断断言已移除。下方"非凭据 .config 说明读放行"保留。
        SecurityPolicyService.FileVerdict ghNotes =
                securityPolicyService.checkCommandPaths("cat docs/.config/gh-notes.md");

        assertThat(ghNotes.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockJimuquWriteDeniedPathsInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.FileVerdict shadow =
                securityPolicyService.checkCommandPaths("echo bad > /etc/shadow");
        SecurityPolicyService.FileVerdict hosts =
                securityPolicyService.checkCommandPaths("echo bad >> /etc/hosts");
        SecurityPolicyService.FileVerdict resolv =
                securityPolicyService.checkCommandPaths("printf nameserver | tee /etc/resolv.conf");
        SecurityPolicyService.FileVerdict profile =
                securityPolicyService.checkCommandPaths("Set-Content ~/.bashrc bad");
        SecurityPolicyService.FileVerdict envHomeProfile =
                securityPolicyService.checkCommandPaths("Set-Content $env:HOME/.bash_profile bad");
        SecurityPolicyService.FileVerdict systemd =
                securityPolicyService.checkCommandPaths(
                        "cat service > /etc/systemd/system/evil.service");
        SecurityPolicyService.FileVerdict localBin =
                securityPolicyService.checkCommandPaths(
                        "curl https://example.invalid/payload -o /usr/local/bin/payload");
        SecurityPolicyService.FileVerdict windowsSystem32 =
                securityPolicyService.checkCommandPaths(
                        "Set-Content C:\\Windows\\System32\\drivers\\etc\\hosts bad");
        SecurityPolicyService.FileVerdict windirSystem32 =
                securityPolicyService.checkCommandPaths(
                        "Add-Content $env:windir\\System32\\drivers\\etc\\hosts bad");
        SecurityPolicyService.FileVerdict programFiles =
                securityPolicyService.checkCommandPaths(
                        "Invoke-WebRequest https://example.invalid/app.exe -OutFile \"C:\\Program Files\\App\\app.exe\"");
        SecurityPolicyService.FileVerdict localDownload =
                securityPolicyService.checkCommandPaths(
                        "curl https://example.invalid/payload -o payload");

        assertThat(shadow.isAllowed()).isFalse();
        assertThat(shadow.getMessage()).contains("系统文件");
        assertThat(hosts.isAllowed()).isFalse();
        assertThat(hosts.getMessage()).contains("系统文件");
        assertThat(resolv.isAllowed()).isFalse();
        assertThat(resolv.getMessage()).contains("系统文件");
        assertThat(profile.isAllowed()).isFalse();
        assertThat(envHomeProfile.isAllowed()).isFalse();
        assertThat(envHomeProfile.getPath()).isEqualTo("$env:HOME/.bash_profile");
        assertThat(systemd.isAllowed()).isFalse();
        assertThat(localBin.isAllowed()).isFalse();
        assertThat(localBin.getPath()).isEqualTo("/usr/local/bin/payload");
        assertThat(windowsSystem32.isAllowed()).isFalse();
        assertThat(windowsSystem32.getMessage()).contains("系统文件");
        assertThat(windirSystem32.isAllowed()).isFalse();
        assertThat(windirSystem32.getPath())
                .isEqualTo("$env:windir\\System32\\drivers\\etc\\hosts");
        assertThat(programFiles.isAllowed()).isFalse();
        assertThat(programFiles.getPath()).contains("Program Files");
        assertThat(localDownload.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockUnsafeUrlsInsideShellAndScriptCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));

        SecurityPolicyService.UrlVerdict metadata =
                securityPolicyService.checkCommandUrls(
                        "curl http://169.254.169.254/latest/meta-data/?token=secret123");
        SecurityPolicyService.UrlVerdict connectToMetadata =
                securityPolicyService.checkCommandUrls(
                        "curl --connect-to safe.example:443:169.254.169.254:80 https://safe.example/");
        SecurityPolicyService.UrlVerdict resolveMetadata =
                securityPolicyService.checkCommandUrls(
                        "curl --resolve safe.example:443:169.254.169.254 https://safe.example/");
        SecurityPolicyService.UrlVerdict cloudCidr =
                securityPolicyService.checkCommandUrls(
                        "gcloud compute firewall-rules create open-ssh --allow tcp:22 --source-ranges 0.0.0.0/0");
        SecurityPolicyService.UrlVerdict cloudIpv6Cidr =
                securityPolicyService.checkCommandUrls(
                        "gcloud compute firewall-rules create open-v6 --allow tcp:443 --source-ranges ::/0");
        SecurityPolicyService.UrlVerdict bracketedIpv6Cidr =
                securityPolicyService.checkCommandUrls(
                        "az network nsg rule create --source-address-prefixes [::]/0 --destination-port-ranges 443");
        SecurityPolicyService.UrlVerdict ipv6Metadata =
                securityPolicyService.checkCommandUrls(
                        "curl http://[fd00:ec2::254]/latest/meta-data/");
        SecurityPolicyService.UrlVerdict python =
                securityPolicyService.checkCommandUrls(
                        "requests.get('https://blocked.example/api?token=secret123');");

        assertThat(metadata.isAllowed()).isFalse();
        assertThat(metadata.getMessage()).contains("元数据");
        assertThat(metadata.getUrl()).contains("token=secret123");
        assertThat(connectToMetadata.isAllowed()).isFalse();
        assertThat(connectToMetadata.getMessage()).contains("元数据");
        assertThat(resolveMetadata.isAllowed()).isFalse();
        assertThat(resolveMetadata.getMessage()).contains("元数据");
        assertThat(cloudCidr.isAllowed()).isTrue();
        assertThat(cloudIpv6Cidr.isAllowed()).isTrue();
        assertThat(bracketedIpv6Cidr.isAllowed()).isTrue();
        assertThat(ipv6Metadata.isAllowed()).isFalse();
        assertThat(ipv6Metadata.getMessage()).contains("元数据");
        assertThat(com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(metadata.getUrl()))
                .doesNotContain("secret123");
        assertThat(
                        com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(
                                "https://user:pass@example.com/path?token=secret123"))
                .doesNotContain("user:pass")
                .doesNotContain("secret123");
        assertThat(
                        com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(
                                "https://oauth.example/callback?access_token=access-secret&client_secret=client-secret&code=oauth-code&x-amz-signature=aws-signature&ok=value"))
                .contains("access_token=***")
                .contains("client_secret=***")
                .contains("code=***")
                .contains("x-amz-signature=***")
                .contains("ok=value")
                .doesNotContain("access-secret")
                .doesNotContain("client-secret")
                .doesNotContain("oauth-code")
                .doesNotContain("aws-signature");
        assertThat(python.isAllowed()).isFalse();
        assertThat(python.getMessage()).contains("blocked.example");
    }

    @Test
    void shouldInspectNestedToolArgumentsForUnsafeUrls() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(
                "callback",
                Arrays.asList("https://blocked.example/hook", "https://example.com/status"));
        nested.put("metadata", metadata);

        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkToolArgs("mcp_remote_tool", nested);

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("blocked.example");
    }

    @Test
    void shouldBuildNativeApprovalCardExtrasAndParseCardAction() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setToolName("execute_shell");
        pending.setPatternKey("recursive_delete");
        pending.setDescription("recursive delete");
        pending.setCommand("rm -rf workspace/cache");
        pending.setApprovalId("approval-123");

        Map<String, Object> extras =
                env.dangerousCommandApprovalService.buildDeliveryExtras(
                        PlatformType.FEISHU, pending);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(
                DangerousCommandApprovalService.CARD_ACTION_KEY,
                DangerousCommandApprovalService.CARD_ACTION_APPROVE);
        payload.put(DangerousCommandApprovalService.CARD_SCOPE_KEY, "always");
        payload.put(DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY, "approval\u202E-123");

        assertThat(extras.get("approvalId")).isEqualTo("approval-123");
        assertThat(extras.get("mode"))
                .isEqualTo(DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);
        assertThat(extras.get("approvalAllowAlways")).isEqualTo(Boolean.TRUE);
        assertThat(extras.get("approvalCommand")).isEqualTo("rm -rf workspace/cache");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload))
                .isEqualTo("/approve approval-123 always");
        Map<String, Object> qqbotExtras =
                env.dangerousCommandApprovalService.buildDeliveryExtras(
                        PlatformType.QQBOT, pending);
        assertThat(qqbotExtras.get("mode"))
                .isEqualTo(DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);
        assertThat(qqbotExtras.get("approvalId")).isEqualTo("approval-123");

        payload.put(
                DangerousCommandApprovalService.CARD_ACTION_KEY,
                DangerousCommandApprovalService.CARD_ACTION_DENY);
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload))
                .isEqualTo("/deny approval-123");

        payload.put(
                DangerousCommandApprovalService.CARD_ACTION_KEY,
                "  " + DangerousCommandApprovalService.CARD_ACTION_APPROVE + "\u001B[0m ");
        payload.put(DangerousCommandApprovalService.CARD_SCOPE_KEY, " SESSION\u202E ");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload))
                .isEqualTo("/approve approval-123 session");

        payload.put(DangerousCommandApprovalService.CARD_ACTION_KEY, "dangerous_approve_all");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload)).isNull();

        payload.put(
                DangerousCommandApprovalService.CARD_ACTION_KEY,
                DangerousCommandApprovalService.CARD_ACTION_APPROVE);
        payload.put(DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY, "approval-123 always");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload)).isNull();
        payload.put(DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY, "approval-123;always");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload)).isNull();
        payload.put(DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY, "approval-123|always");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload)).isNull();
        payload.put(DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY, "approval:123");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload)).isNull();
        payload.put(
                DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY,
                "approval-ghp_cardsecret123456");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload)).isNull();

        String jsonPayload =
                "{\"solonclaw_action\":\"dangerous_approve\",\"scope\":\"session\",\"approvalId\":\"approval-json\"}";
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(jsonPayload))
                .isEqualTo("/approve approval-json session");
        assertThat(
                        DangerousCommandApprovalService.commandFromCardActionPayload(
                                "[\"dangerous_approve\"]"))
                .isNull();
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload("{bad json"))
                .isNull();
        String injectedJsonPayload =
                "{\"solonclaw_action\":\"dangerous_approve\",\"scope\":\"always\",\"approvalId\":\"approval-json always\"}";
        assertThat(
                        DangerousCommandApprovalService.commandFromCardActionPayload(
                                injectedJsonPayload))
                .isNull();
    }

    @Test
    void shouldSanitizeApprovalCardActionPayloadBeforeCommandGeneration() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(
                DangerousCommandApprovalService.CARD_ACTION_KEY,
                "\u001B[32m" + DangerousCommandApprovalService.CARD_ACTION_APPROVE + "\u001B[0m");
        payload.put(
                DangerousCommandApprovalService.CARD_SCOPE_KEY,
                "\u001B]0;hidden\u0007session\u202E");
        payload.put(
                DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY,
                "approval\u001B[31m-ansi\u202E");

        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload))
                .isEqualTo("/approve approval-ansi session");

        payload.put(
                DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY,
                "approval\u001B[31m-ansi\nalways");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload)).isNull();
    }

    @Test
    void shouldSanitizeOutboundApprovalCardSelectorAndFallbackToKeySelector() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setToolName("execute_shell");
        pending.setPatternKey("recursive_delete");
        pending.setDescription("recursive delete");
        pending.setCommand("rm -rf workspace/cache");
        pending.setCommandHash("hash-card-selector");
        pending.setApprovalKey("execute_shell:recursive_delete:hash-card-selector");
        pending.setApprovalId("approval-unsafe always");
        pending.setCreatedAt(System.currentTimeMillis());
        pending.setExpiresAt(System.currentTimeMillis() + 60000L);

        Map<String, Object> extras =
                env.dangerousCommandApprovalService.buildDeliveryExtras(
                        PlatformType.FEISHU, pending);
        String outboundSelector = String.valueOf(extras.get("approvalId"));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(
                DangerousCommandApprovalService.CARD_ACTION_KEY,
                DangerousCommandApprovalService.CARD_ACTION_APPROVE);
        payload.put(DangerousCommandApprovalService.CARD_SCOPE_KEY, "session");
        payload.put(DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY, outboundSelector);

        assertThat(outboundSelector).startsWith("key_");
        assertThat(outboundSelector).doesNotContain("always");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload))
                .isEqualTo("/approve " + outboundSelector + " session");

        TestTrace trace = new TestTrace();
        Map<String, Object> pendingMap = new LinkedHashMap<String, Object>();
        pendingMap.put("approvalId", pending.getApprovalId());
        pendingMap.put("toolName", pending.getToolName());
        pendingMap.put("patternKey", pending.getPatternKey());
        pendingMap.put("patternKeys", Collections.singletonList(pending.getPatternKey()));
        pendingMap.put("description", pending.getDescription());
        pendingMap.put("command", pending.getCommand());
        pendingMap.put("commandHash", pending.getCommandHash());
        pendingMap.put("approvalKey", pending.getApprovalKey());
        pendingMap.put("createdAt", pending.getCreatedAt());
        pendingMap.put("expiresAt", pending.getExpiresAt());
        trace.session
                .getContext()
                .put("_dangerous_command_pending_queue_", Collections.singletonList(pendingMap));

        assertThat(
                        env.dangerousCommandApprovalService.approve(
                                trace.session,
                                outboundSelector,
                                DangerousCommandApprovalService.ApprovalScope.SESSION,
                                "card"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                trace.session, "recursive_delete"))
                .isTrue();
    }

    @Test
    void shouldRedactSecretsFromFeishuApprovalCardExtrasWithoutChangingPendingCommand()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setToolName("execute_shell");
        pending.setPatternKey("shell_command_flag");
        pending.setDescription("remote call with Authorization: Bearer ghp_abcdefghijklmnop");
        pending.setCommand(
                "OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz curl "
                        + "'https://api.example.test/run?access_token=sk-proj-abcdefghijklmnopqrstuvwxyz"
                        + "&accessToken=camel-access-secret"
                        + "&api%255Fkey=encoded-card-secret"
                        + ";client_secret=semicolon-card-secret#token=fragment-card-secret' "
                        + "clientSecret=assignment-card-secret");
        pending.setApprovalId("approval-secret");

        Map<String, Object> extras =
                env.dangerousCommandApprovalService.buildDeliveryExtras(
                        PlatformType.FEISHU, pending);

        assertThat(extras.get("approvalCommand").toString()).doesNotContain("sk-proj-abc");
        assertThat(extras.get("approvalCommand").toString()).contains("OPENAI_API_KEY=***");
        assertThat(extras.get("approvalCommand").toString()).contains("access_token=***");
        assertThat(extras.get("approvalCommand").toString()).contains("accessToken=***");
        assertThat(extras.get("approvalCommand").toString()).contains("api%255Fkey=***");
        assertThat(extras.get("approvalCommand").toString()).contains("client_secret=***");
        assertThat(extras.get("approvalCommand").toString()).contains("clientSecret=***");
        assertThat(extras.get("approvalCommand").toString()).contains("token=***");
        assertThat(extras.get("approvalCommand").toString()).doesNotContain("camel-access-secret");
        assertThat(extras.get("approvalCommand").toString()).doesNotContain("encoded-card-secret");
        assertThat(extras.get("approvalCommand").toString())
                .doesNotContain("semicolon-card-secret");
        assertThat(extras.get("approvalCommand").toString()).doesNotContain("fragment-card-secret");
        assertThat(extras.get("approvalCommand").toString())
                .doesNotContain("assignment-card-secret");
        assertThat(extras.get("approvalDescription").toString())
                .doesNotContain("ghp_abcdefghijklmnop");
        assertThat(pending.getCommand()).contains("sk-proj-abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    void shouldStripTerminalControlsFromApprovalCardExtras() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setToolName("execute_shell");
        pending.setPatternKey("shell_command_flag");
        pending.setDescription(
                "remote call\u001b]8;;https://evil.example\u0007link\u001b]8;;\u0007");
        pending.setCommand("echo safe\u001b[31m red\u001b[0m \u202Etxt");
        pending.setApprovalId("approval-controls");

        Map<String, Object> extras =
                env.dangerousCommandApprovalService.buildDeliveryExtras(
                        PlatformType.FEISHU, pending);

        assertThat(extras.get("approvalCommand").toString())
                .doesNotContain("\u001b")
                .doesNotContain("\u202E")
                .contains("echo safe red txt");
        assertThat(extras.get("approvalDescription").toString())
                .doesNotContain("\u001b")
                .doesNotContain("https://evil.example");
    }

    @Test
    void shouldExpirePendingApprovalWithCanonicalConfigGatewayTimeout() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getApprovals().setGatewayTimeoutSeconds(1);
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        null);
        TestTrace trace = new TestTrace();
        service.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);
        assertThat(pending).isNotNull();
        assertThat(pending.getExpiresAt()).isGreaterThan(pending.getCreatedAt());

        Map<String, Object> expired = new LinkedHashMap<String, Object>();
        expired.put("toolName", "execute_shell");
        expired.put("patternKey", "recursive_delete");
        expired.put("patternKeys", Collections.singletonList("recursive_delete"));
        expired.put("description", "recursive delete");
        expired.put("command", "rm -rf workspace/cache");
        expired.put("commandHash", "hash");
        expired.put("approvalKey", "execute_shell:recursive_delete:hash");
        expired.put("createdAt", System.currentTimeMillis() - 10_000L);
        expired.put("expiresAt", System.currentTimeMillis() - 1_000L);
        trace.session
                .getContext()
                .put("_dangerous_command_pending_queue_", Collections.singletonList(expired));

        assertThat(service.getPendingApproval(trace.session)).isNull();
        assertThat(
                        service.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isFalse();
    }

    @Test
    void shouldStripDisplayControlsFromPendingApprovalIdentityFields() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TestTrace trace = new TestTrace();
        Map<String, Object> pending = new LinkedHashMap<String, Object>();
        pending.put("approvalId", "approval\u202E-control");
        pending.put("toolName", "execute\u202E_shell");
        pending.put("patternKey", "recursive\u202E_delete");
        pending.put("patternKeys", Arrays.asList("recursive\u202E_delete", "recursive_delete"));
        pending.put("description", "recursive delete");
        pending.put("command", "rm -rf workspace/cache");
        pending.put("commandHash", "hash\u202E-control");
        pending.put("approvalKey", "execute_shell:recursive\u202E_delete:hash-control");
        pending.put("createdAt", System.currentTimeMillis());
        pending.put("expiresAt", System.currentTimeMillis() + 60000L);
        trace.session
                .getContext()
                .put("_dangerous_command_pending_queue_", Collections.singletonList(pending));

        DangerousCommandApprovalService.PendingApproval restored =
                env.dangerousCommandApprovalService.getPendingApproval(trace.session);

        assertThat(restored).isNotNull();
        assertThat(restored.getApprovalId()).isEqualTo("approval-control");
        assertThat(restored.getToolName()).isEqualTo("execute_shell");
        assertThat(restored.getPatternKey()).isEqualTo("recursive_delete");
        assertThat(restored.getPatternKeys()).containsExactly("recursive_delete");
        assertThat(restored.getApprovalKey())
                .isEqualTo("execute_shell:recursive_delete:hash-control");
        assertThat(restored.approvalKey()).isEqualTo("execute_shell:recursive_delete:hash-control");
        assertThat(
                        env.dangerousCommandApprovalService.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.SESSION,
                                "test"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                trace.session, "recursive_delete"))
                .isTrue();
    }

    @Test
    void shouldNotifyApprovalObserversWhenPendingApprovalTimesOut() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        final List<String> choices = new java.util.ArrayList<String>();
        final List<String> outcomes = new java.util.ArrayList<String>();
        final List<String> statuses = new java.util.ArrayList<String>();
        final List<Boolean> approved = new java.util.ArrayList<Boolean>();
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {}

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {
                        choices.add(event.getChoice() + ":" + event.getPrimaryPatternKey());
                        outcomes.add(event.getOutcome());
                        statuses.add(event.getStatus());
                        approved.add(Boolean.valueOf(event.isApproved()));
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> expired = new LinkedHashMap<String, Object>();
        expired.put("toolName", "execute_shell");
        expired.put("patternKey", "recursive_delete");
        expired.put("patternKeys", Collections.singletonList("recursive_delete"));
        expired.put("description", "recursive delete");
        expired.put("command", "rm -rf workspace/cache");
        expired.put("commandHash", "hash");
        expired.put("approvalKey", "execute_shell:recursive_delete:hash");
        expired.put("createdAt", System.currentTimeMillis() - 10_000L);
        expired.put("expiresAt", System.currentTimeMillis() - 1_000L);
        trace.session
                .getContext()
                .put("_dangerous_command_pending_queue_", Collections.singletonList(expired));

        assertThat(env.dangerousCommandApprovalService.getPendingApproval(trace.session)).isNull();

        assertThat(choices).containsExactly("timeout:recursive_delete");
        assertThat(outcomes)
                .containsExactly(
                        DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_TIMED_OUT);
        assertThat(statuses).containsExactly("timed_out");
        assertThat(approved).containsExactly(Boolean.FALSE);
    }

    @Test
    void shouldRedactTimeoutApprovalObserverMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        final List<String> observed = new java.util.ArrayList<String>();
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {}

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {
                        DangerousCommandApprovalService.PendingApproval pending =
                                event.getPendingApproval();
                        observed.add(event.getChoice());
                        observed.add(event.getApprover());
                        observed.add(pending.getCommand());
                        observed.add(pending.getPatternKey());
                        observed.add(String.valueOf(pending.getPatternKeys()));
                        observed.add(pending.getDescription());
                        observed.add(pending.getApprovalKey());
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> expired = new LinkedHashMap<String, Object>();
        expired.put("toolName", "execute_shell");
        expired.put("patternKey", "url_policy?api%255Fkey=timeout-secret");
        expired.put(
                "patternKeys", Collections.singletonList("url_policy?api%255Fkey=timeout-secret"));
        expired.put(
                "description",
                "encoded timeout https://example.test/callback?api%255Fkey=timeout-secret");
        expired.put("command", "curl https://example.test/callback?api%255Fkey=timeout-secret");
        expired.put("commandHash", "hash-timeout");
        expired.put(
                "approvalKey", "execute_shell:url_policy?api%255Fkey=timeout-secret:hash-timeout");
        expired.put("createdAt", System.currentTimeMillis() - 10_000L);
        expired.put("expiresAt", System.currentTimeMillis() - 1_000L);
        trace.session
                .getContext()
                .put("_dangerous_command_pending_queue_", Collections.singletonList(expired));

        assertThat(env.dangerousCommandApprovalService.getPendingApproval(trace.session)).isNull();

        assertThat(observed).hasSize(7);
        assertThat(observed.get(0)).isEqualTo("timeout");
        assertThat(observed.get(1)).isEmpty();
        for (int i = 2; i < observed.size(); i++) {
            assertThat(observed.get(i))
                    .contains("api%255Fkey=***")
                    .doesNotContain("timeout-secret");
        }
    }

    @Test
    void shouldKeepMultiplePendingApprovalsWithCanonicalConfigGatewayQueue() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TestTrace trace = new TestTrace();

        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        List<DangerousCommandApprovalService.PendingApproval> pending =
                env.dangerousCommandApprovalService.listPendingApprovals(trace.session);

        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getPatternKey()).isEqualTo("recursive_delete");
        assertThat(pending.get(1).getPatternKey()).isEqualTo("git_reset_hard");
        assertThat(pending.get(0).getApprovalId()).isNotBlank();

        assertThat(
                        env.dangerousCommandApprovalService.approve(
                                trace.session,
                                "#2",
                                DangerousCommandApprovalService.ApprovalScope.SESSION,
                                "test"))
                .isTrue();

        List<DangerousCommandApprovalService.PendingApproval> afterApprove =
                env.dangerousCommandApprovalService.listPendingApprovals(trace.session);
        assertThat(afterApprove).hasSize(1);
        assertThat(afterApprove.get(0).getPatternKey()).isEqualTo("recursive_delete");
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                trace.session, "git_reset_hard"))
                .isTrue();
    }

    @Test
    void shouldKeepFindDeleteAndFindExecApprovalsSeparateWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TestTrace trace = new TestTrace();

        DangerousCommandApprovalService.DetectionResult findExec =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "find . -exec rm {} \\;");
        DangerousCommandApprovalService.DetectionResult findDelete =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "find . -name '*.tmp' -delete");

        assertThat(findExec).isNotNull();
        assertThat(findExec.getPatternKey()).isEqualTo("find_exec_rm");
        assertThat(findDelete).isNotNull();
        assertThat(findDelete.getPatternKey()).isEqualTo("find_delete");
        assertThat(findExec.getPatternKey()).isNotEqualTo(findDelete.getPatternKey());

        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                findExec.getPatternKey(),
                findExec.getDescription(),
                "find . -exec rm {} \\;");

        assertThat(
                        env.dangerousCommandApprovalService.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.SESSION,
                                "test"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                trace.session, "find_exec_rm"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                trace.session, "find_delete"))
                .isFalse();
    }

    @Test
    void shouldStripDisplayControlsWhenRevokingAlwaysApproval() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.globalSettingRepository.set(
                com.jimuqu.solon.claw.support.constants.AgentSettingConstants
                        .DANGEROUS_COMMAND_ALWAYS_PATTERNS,
                ONode.serialize(Collections.singletonList("execute_shell:recursive_delete")));

        assertThat(
                        env.dangerousCommandApprovalService.revokeAlwaysApproval(
                                "execute_shell:recursive\u202E_delete"))
                .isTrue();

        assertThat(env.dangerousCommandApprovalService.listAlwaysApprovals()).isEmpty();
    }

    @Test
    void shouldNotifyApprovalObserversForRequestAndResponseWithCanonicalConfigHooks()
            throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        null);
        final List<String> events = new java.util.ArrayList<String>();
        final List<String> outcomes = new java.util.ArrayList<String>();
        final List<String> statuses = new java.util.ArrayList<String>();
        final List<Boolean> approved = new java.util.ArrayList<Boolean>();
        service.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {
                        events.add(
                                "request:"
                                        + event.getSessionId()
                                        + ":"
                                        + event.getToolName()
                                        + ":"
                                        + event.getPrimaryPatternKey()
                                        + ":"
                                        + event.getCommand());
                    }

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {
                        events.add(
                                "response:"
                                        + event.getChoice()
                                        + ":"
                                        + event.getApprover()
                                        + ":"
                                        + event.getPrimaryPatternKey());
                        outcomes.add(event.getOutcome());
                        statuses.add(event.getStatus());
                        approved.add(Boolean.valueOf(event.isApproved()));
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "rm -rf workspace/cache");

        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));
        assertThat(
                        service.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "tester"))
                .isTrue();

        assertThat(events)
                .containsExactly(
                        "request:tirith-test:execute_shell:recursive_delete:rm -rf workspace/cache",
                        "response:once:tester:recursive_delete");
        assertThat(outcomes)
                .containsExactly(
                        DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_APPROVED);
        assertThat(statuses).containsExactly("approved");
        assertThat(approved).containsExactly(Boolean.TRUE);
    }

    @Test
    void shouldRedactApproverBeforeNotifyingApprovalObservers() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        final List<String> approvers = new java.util.ArrayList<String>();
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {}

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {
                        approvers.add(event.getApprover());
                    }
                });
        TestTrace trace = new TestTrace();
        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        assertThat(
                        env.dangerousCommandApprovalService.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "ops token=ghp_approver123"))
                .isTrue();

        assertThat(approvers).hasSize(1);
        assertThat(approvers.get(0)).contains("token=***").doesNotContain("ghp_approver123");
    }

    @Test
    void shouldRedactApprovalRequestEventCommandAndDescriptionForObservers() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        final List<String> observed = new java.util.ArrayList<String>();
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {
                        observed.add(event.getCommand());
                        observed.add(event.getDescription());
                        observed.add(event.getPendingApproval().getCommand());
                        observed.add(event.getPendingApproval().getDescription());
                    }

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {}
                });
        TestTrace trace = new TestTrace();

        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "delete with token=ghp_requestdescription123 and password=request-password",
                "rm -rf workspace/cache --token ghp_requestcommand123");

        assertThat(observed).hasSize(4);
        for (String value : observed) {
            assertThat(value)
                    .doesNotContain("ghp_requestdescription123")
                    .doesNotContain("request-password")
                    .doesNotContain("ghp_requestcommand123");
        }
        assertThat(observed.get(0)).contains("***");
        assertThat(observed.get(1)).contains("token=***").contains("password=***");
        assertThat(observed.get(2)).contains("***");
        assertThat(observed.get(3)).contains("token=***").contains("password=***");
        assertThat(
                        env.dangerousCommandApprovalService
                                .getPendingApproval(trace.session)
                                .getCommand())
                .contains("ghp_requestcommand123");
    }

    @Test
    void shouldRedactEncodedApprovalObserverMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        final List<String> observed = new java.util.ArrayList<String>();
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {
                        DangerousCommandApprovalService.PendingApproval pending =
                                event.getPendingApproval();
                        observed.add(pending.getCommand());
                        observed.add(pending.getPatternKey());
                        observed.add(String.valueOf(pending.getPatternKeys()));
                        observed.add(pending.getApprovalKey());
                    }

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {}
                });
        TestTrace trace = new TestTrace();

        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "url_policy?api%255Fkey=observer-secret",
                "encoded observer metadata",
                "curl https://example.test/callback?api%255Fkey=observer-secret");

        assertThat(observed).hasSize(4);
        for (String value : observed) {
            assertThat(value).contains("api%255Fkey=***").doesNotContain("observer-secret");
        }
        assertThat(
                        env.dangerousCommandApprovalService
                                .getPendingApproval(trace.session)
                                .getCommand())
                .contains("observer-secret");
    }

    @Test
    void shouldNotifyApprovalObserversForDenyResponse() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        final List<String> choices = new java.util.ArrayList<String>();
        final List<String> outcomes = new java.util.ArrayList<String>();
        final List<String> statuses = new java.util.ArrayList<String>();
        final List<Boolean> approved = new java.util.ArrayList<Boolean>();
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {
                        choices.add("request");
                    }

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {
                        choices.add(event.getChoice());
                        outcomes.add(event.getOutcome());
                        statuses.add(event.getStatus());
                        approved.add(Boolean.valueOf(event.isApproved()));
                    }
                });
        TestTrace trace = new TestTrace();
        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        assertThat(env.dangerousCommandApprovalService.reject(trace.session, "tester")).isTrue();

        assertThat(choices).containsExactly("request", "deny");
        assertThat(outcomes)
                .containsExactly(
                        DangerousCommandApprovalService.ApprovalResponseEvent.OUTCOME_DENIED);
        assertThat(statuses).containsExactly("denied");
        assertThat(approved).containsExactly(Boolean.FALSE);
    }

    @Test
    void shouldRedactApprovalResponseObserverMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        final List<String> observed = new java.util.ArrayList<String>();
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {}

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {
                        observed.add(event.getChoice());
                        observed.add(event.getApprover());
                        observed.add(event.getPendingApproval().getCommand());
                    }
                });
        TestTrace trace = new TestTrace();
        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "url_policy?api%255Fkey=response-secret",
                "encoded response metadata",
                "curl https://example.test/callback?api%255Fkey=response-secret");

        assertThat(
                        env.dangerousCommandApprovalService.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "dashboard-user ghp_responseapprover123"))
                .isTrue();

        assertThat(observed).hasSize(3);
        assertThat(observed.get(0)).isEqualTo("once");
        assertThat(observed.get(1))
                .contains("dashboard-user ***")
                .doesNotContain("ghp_responseapprover123");
        assertThat(observed.get(2)).contains("api%255Fkey=***").doesNotContain("response-secret");
    }

    @Test
    void shouldRedactApproverInApprovalSessionDecisionComment() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TestTrace trace = new TestTrace();
        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        assertThat(
                        env.dangerousCommandApprovalService.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "dashboard-user ghp_1234567890abcdef"))
                .isTrue();

        HITLDecision decision = HITL.getDecision(trace.session, "execute_shell");
        assertThat(decision).isNotNull();
        assertThat(decision.getComment())
                .contains("审批人：dashboard-user ***")
                .doesNotContain("1234567890abcdef");
        assertThat(ONode.serialize(trace.session.getSnapshot())).doesNotContain("1234567890abcdef");
    }

    @Test
    void shouldRedactApproverInRejectSessionDecisionComment() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TestTrace trace = new TestTrace();
        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        assertThat(
                        env.dangerousCommandApprovalService.reject(
                                trace.session, "dashboard-user ghp_1234567890abcdef"))
                .isTrue();

        HITLDecision decision = HITL.getDecision(trace.session, "execute_shell");
        assertThat(decision).isNotNull();
        assertThat(decision.getComment())
                .contains("审批人：dashboard-user ***")
                .doesNotContain("1234567890abcdef");
        assertThat(ONode.serialize(trace.session.getSnapshot())).doesNotContain("1234567890abcdef");
    }

    @Test
    void shouldIgnoreApprovalObserverFailures() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        final List<String> observed = new java.util.ArrayList<String>();
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {
                        throw new IllegalStateException("observer failed");
                    }

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {
                        throw new IllegalStateException("observer failed");
                    }
                });
        env.dangerousCommandApprovalService.addApprovalObserver(
                new DangerousCommandApprovalService.ApprovalObserver() {
                    @Override
                    public void onApprovalRequest(
                            DangerousCommandApprovalService.ApprovalRequestEvent event) {
                        observed.add(event.getCommand());
                        observed.add(event.getDescription());
                    }

                    @Override
                    public void onApprovalResponse(
                            DangerousCommandApprovalService.ApprovalResponseEvent event) {
                        observed.add(event.getApprover());
                        observed.add(event.getPendingApproval().getCommand());
                    }
                });
        TestTrace trace = new TestTrace();

        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "recursive delete with token=ghp_observerfailuredescription123",
                "rm -rf workspace/cache --token ghp_observerfailurecommand123");

        assertThat(
                        env.dangerousCommandApprovalService.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.SESSION,
                                "tester ghp_observerfailureapprover123"))
                .isTrue();
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(trace.session)).isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                trace.session, "recursive_delete"))
                .isTrue();
        assertThat(observed).hasSize(4);
        for (String value : observed) {
            assertThat(value)
                    .doesNotContain("ghp_observerfailuredescription123")
                    .doesNotContain("ghp_observerfailurecommand123")
                    .doesNotContain("ghp_observerfailureapprover123");
        }
        assertThat(observed.get(0)).contains("***");
        assertThat(observed.get(1)).contains("token=***");
        assertThat(observed.get(2)).contains("tester ***");
        assertThat(observed.get(3)).contains("***");
    }

    @Test
    void shouldAllowWhenTirithScanIsDisabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setTirithEnabled(false);
        TirithSecurityService.ScanResult result =
                new TirithSecurityService(env.appConfig).checkCommandSecurity("echo hello");

        assertThat(result.getAction()).isEqualTo("allow");
        assertThat(result.requiresApproval()).isFalse();
    }

    @Test
    void shouldFailOpenOrFailClosedWhenTirithUnavailable() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setTirithPath("__missing_tirith_binary__");
        env.appConfig.getSecurity().setTirithFailOpen(true);
        TirithSecurityService service = new TirithSecurityService(env.appConfig);

        TirithSecurityService.ScanResult open = service.checkCommandSecurity("echo hello");
        env.appConfig.getSecurity().setTirithFailOpen(false);
        TirithSecurityService.ScanResult closed = service.checkCommandSecurity("echo hello");

        assertThat(open.getAction()).isEqualTo("allow");
        assertThat(open.getSummary()).contains("tirith unavailable");
        assertThat(closed.getAction()).isEqualTo("block");
        assertThat(closed.getSummary()).contains("fail-closed");
    }

    @Test
    void shouldCombineTirithWarningWithDangerousCommandApproval() throws Exception {
        TestEnvironment env = approvalEnvironment();
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding(
                                                "homograph_url",
                                                "HIGH",
                                                "Homograph URL",
                                                "Suspicious unicode URL")),
                                "homograph URL"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        HITLInterceptor interceptor = service.buildInterceptor();
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "rm -rf workspace/cache");

        interceptor.onAction(trace, exchange("execute_shell", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);

        assertThat(trace.getFinalAnswer()).contains("Security scan").contains("recursive delete");
        assertThat(trace.getFinalAnswer()).contains("不能永久记住");
        assertThat(trace.getFinalAnswer()).doesNotContain("/approve always");
        assertThat(pending).isNotNull();
        assertThat(pending.getPatternKeys())
                .containsExactly("tirith:homograph_url", "recursive_delete");
    }

    @Test
    void shouldPromptForProcessStartDangerousCommands() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("action", "start");
        args.put("command", "rm -rf workspace/cache");

        service.buildInterceptor().onAction(trace, exchange("process", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);

        assertThat(trace.getFinalAnswer()).contains("需要审批").contains("recursive delete");
        assertThat(pending).isNotNull();
        assertThat(pending.getToolName()).isEqualTo("process");
        assertThat(pending.getCommand()).isEqualTo("rm -rf workspace/cache");
        assertThat(pending.getPatternKeys()).containsExactly("recursive_delete");
    }

    @Test
    void shouldPromptForExecuteCodeDangerousScripts() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "import shutil\nshutil.rmtree('workspace/cache')\n");

        service.buildInterceptor().onAction(trace, exchange("execute_code", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);

        assertThat(trace.getFinalAnswer()).contains("需要审批").contains("Python recursive delete");
        assertThat(pending).isNotNull();
        assertThat(pending.getToolName()).isEqualTo("execute_code");
        assertThat(pending.getCommand())
                .isEqualTo("import shutil\nshutil.rmtree('workspace/cache')\n");
        assertThat(pending.getPatternKeys()).containsExactly("python_rmtree");
    }

    @Test
    void shouldHardBlockExecuteCodeShellHardlineTextWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "import os\nos.system('rm -rf /')\n");

        service.buildInterceptor().onAction(trace, exchange("execute_code", args));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer())
                .contains("BLOCKED (hardline)")
                .contains("root filesystem");
        assertThat(service.getPendingApproval(trace.session)).isNull();
    }

    @Test
    void shouldHardBlockExecuteCodeSubprocessArgvHardlineWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "import subprocess\nsubprocess.run(['rm', '-rf', '/'])\n");

        service.buildInterceptor().onAction(trace, exchange("execute_code", args));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer())
                .contains("BLOCKED (hardline)")
                .contains("root filesystem");
        assertThat(service.getPendingApproval(trace.session)).isNull();
    }

    @Test
    void shouldHardBlockExecuteJsChildProcessHardlineWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "require('child_process').execSync('rm -rf /')\nconsole.log('after')\n");

        service.buildInterceptor().onAction(trace, exchange("execute_js", args));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer())
                .contains("BLOCKED (hardline)")
                .contains("root filesystem");
        assertThat(service.getPendingApproval(trace.session)).isNull();
    }

    @Test
    void shouldNotHardBlockExecuteCodePlainStringMentions() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult result =
                env.dangerousCommandApprovalService.detectHardline(
                        "execute_python", "print('sudo reboot')");

        assertThat(result).isNull();
    }

    @Test
    void shouldPromptForTerminalDangerousCommands() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("command", "rm -rf workspace/cache");

        service.buildInterceptor().onAction(trace, exchange("terminal", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);

        assertThat(trace.getFinalAnswer()).contains("需要审批").contains("recursive delete");
        assertThat(pending).isNotNull();
        assertThat(pending.getToolName()).isEqualTo("terminal");
        assertThat(pending.getCommand()).isEqualTo("rm -rf workspace/cache");
        assertThat(pending.getPatternKeys()).containsExactly("recursive_delete");
    }

    @Test
    void shouldPromptForCurrentShellAndTerminalTools() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));

        Map<String, Object> shellArgs = new LinkedHashMap<String, Object>();
        shellArgs.put("command", "rm -rf workspace/cache");
        TestTrace shellTrace = new TestTrace();

        service.buildInterceptor().onAction(shellTrace, exchange("execute_shell", shellArgs));

        DangerousCommandApprovalService.PendingApproval shellPending =
                service.getPendingApproval(shellTrace.session);
        assertThat(shellTrace.getFinalAnswer()).contains("需要审批").contains("recursive delete");
        assertThat(shellPending).isNotNull();
        assertThat(shellPending.getToolName()).isEqualTo("execute_shell");
        assertThat(shellPending.getPatternKeys()).containsExactly("recursive_delete");

        Map<String, Object> camelShellArgs = new LinkedHashMap<String, Object>();
        camelShellArgs.put("cmd", "git reset --hard");
        TestTrace camelShellTrace = new TestTrace();

        service.buildInterceptor().onAction(camelShellTrace, exchange("execute_shell", camelShellArgs));

        DangerousCommandApprovalService.PendingApproval camelShellPending =
                service.getPendingApproval(camelShellTrace.session);
        assertThat(camelShellTrace.getFinalAnswer()).contains("需要审批").contains("git reset --hard");
        assertThat(camelShellPending).isNotNull();
        assertThat(camelShellPending.getToolName()).isEqualTo("execute_shell");
        assertThat(camelShellPending.getPatternKeys()).containsExactly("git_reset_hard");

        Map<String, Object> terminalArgs = new LinkedHashMap<String, Object>();
        terminalArgs.put("command", "rm -rf workspace/cache");
        TestTrace terminalTrace = new TestTrace();

        service.buildInterceptor().onAction(terminalTrace, exchange("terminal", terminalArgs));

        DangerousCommandApprovalService.PendingApproval terminalPending =
                service.getPendingApproval(terminalTrace.session);
        assertThat(terminalTrace.getFinalAnswer()).contains("需要审批").contains("recursive delete");
        assertThat(terminalPending).isNotNull();
        assertThat(terminalPending.getToolName()).isEqualTo("terminal");
        assertThat(terminalPending.getPatternKeys()).containsExactly("recursive_delete");
    }

    @Test
    void shouldExposeCurrentThreadApprovalForApprovedProcessCommand() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("action", "start");
        args.put("command", "rm -rf workspace/cache");

        service.buildInterceptor().onAction(trace, exchange("process", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);
        assertThat(pending).isNotNull();
        assertThat(
                        service.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();

        TestTrace resumed = new TestTrace(trace.session);
        service.buildInterceptor().onAction(resumed, exchange("process", args));

        assertThat(resumed.getFinalAnswer()).isNull();
        assertThat(
                        DangerousCommandApprovalService.consumeCurrentThreadApproval(
                                "process", "rm -rf workspace/cache"))
                .isTrue();
        assertThat(
                        DangerousCommandApprovalService.consumeCurrentThreadApproval(
                                "process", "rm -rf workspace/cache"))
                .isFalse();
    }

    @Test
    void shouldNotReuseStaleHitlApprovalForDifferentDangerousCommand() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace first = new TestTrace();
        Map<String, Object> firstArgs = new LinkedHashMap<String, Object>();
        firstArgs.put("action", "start");
        firstArgs.put("command", "rm -rf workspace/cache");
        service.buildInterceptor().onAction(first, exchange("process", firstArgs));
        assertThat(service.getPendingApproval(first.session)).isNotNull();
        assertThat(
                        service.approve(
                                first.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();

        TestTrace second = new TestTrace(first.session);
        Map<String, Object> secondArgs = new LinkedHashMap<String, Object>();
        secondArgs.put("action", "start");
        secondArgs.put("command", "git reset --hard origin/main");
        service.buildInterceptor().onAction(second, exchange("process", secondArgs));

        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(second.session);
        assertThat(second.getFinalAnswer()).contains("需要审批").contains("git reset --hard");
        assertThat(pending).isNotNull();
        assertThat(pending.getCommand()).isEqualTo("git reset --hard origin/main");
        assertThat(pending.getPatternKeys()).containsExactly("git_reset_hard");
        assertThat(
                        DangerousCommandApprovalService.consumeCurrentThreadApproval(
                                "process", "git reset --hard origin/main"))
                .isFalse();
    }

    @Test
    void shouldLetApprovedProcessCommandPassToolFallbackOnce() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("action", "start");
        args.put("command", "rm -rf workspace/cache");
        service.buildInterceptor().onAction(trace, exchange("process", args));
        assertThat(
                        service.approve(
                                trace.session,
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();

        TestTrace resumed = new TestTrace(trace.session);
        service.buildInterceptor().onAction(resumed, exchange("process", args));
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "rm -rf workspace/cache",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(started);
        assertThat(started.get("session_id").getString()).isNotBlank();
        env.processRegistry.stop(started.get("session_id").getString());

        ONode blocked =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "rm -rf workspace/cache",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolError(blocked);
        assertThat(blocked.get("error").getString()).contains("危险命令安全规则");
    }

    @Test
    void shouldPromptForGatewayTerminalCommandApproval() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        TestTrace trace = new TestTrace();
        Map<String, Object> toolArgs = new LinkedHashMap<String, Object>();
        toolArgs.put("command", "rm -rf workspace/cache");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("tool_name", "terminal");
        args.put("tool_args", toolArgs);

        service.buildInterceptor().onAction(trace, exchange("call_tool", args));
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);

        assertThat(trace.getFinalAnswer()).contains("需要审批").contains("recursive delete");
        assertThat(pending).isNotNull();
        assertThat(pending.getToolName()).isEqualTo("terminal");
        assertThat(pending.getCommand()).isEqualTo("rm -rf workspace/cache");
        assertThat(pending.getPatternKeys()).containsExactly("recursive_delete");
    }

    @Test
    void shouldPromptForGatewayInfrastructureCommandApproval() throws Exception {
        TestEnvironment env = approvalEnvironment();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));

        assertGatewayCommandApproval(
                service,
                "kubectl proxy --address=0.0.0.0 --accept-hosts=.*",
                "kubectl_network_exposure");
        assertGatewayCommandApproval(
                service, "kubectl proxy --address=::", "kubectl_network_exposure");
        assertGatewayCommandApproval(
                service,
                "kubectl port-forward --address [::] svc/app 8080:80",
                "kubectl_network_exposure");
        assertGatewayCommandApproval(
                service, "terraform state pull", "terraform_state_sensitive_read");
        assertGatewayCommandApproval(
                service,
                "gcloud compute firewall-rules create open-ssh --allow tcp:22 --source-ranges 0.0.0.0/0",
                "cloud_network_exposure_change");
    }

    @Test
    void shouldDetectLocalServiceBroadListenAddress() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        List<String> commands =
                Arrays.asList(
                        "python -m http.server 8000 --bind 0.0.0.0",
                        "vite --host 0.0.0.0",
                        "npm run dev -- --host 0.0.0.0",
                        "pnpm dev --host ::",
                        "yarn serve --host *");
        for (String command : commands) {
            DangerousCommandApprovalService.DetectionResult result =
                    env.dangerousCommandApprovalService.detect("execute_shell", command);
            assertThat(result).as(command).isNotNull();
            assertThat(result.getPatternKey())
                    .as(command)
                    .isEqualTo("local_service_network_exposure");
        }

        assertThat(
                        env.dangerousCommandApprovalService.detect(
                                "execute_shell", "python -m http.server 8000 --bind 127.0.0.1"))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.detect(
                                "execute_shell", "vite --host localhost"))
                .isNull();
    }

    @Test
    void shouldHardBlockGatewayShellMetadataUrlsBeforeApproval() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));
        Map<String, Object> shellArgs = new LinkedHashMap<String, Object>();
        shellArgs.put(
                "command",
                "curl http://169.254.169.254/latest/meta-data/?api%255Fkey=hardline-secret");
        Map<String, Object> gatewayShell = new LinkedHashMap<String, Object>();
        gatewayShell.put("tool_name", "execute_shell");
        gatewayShell.put("tool_args", shellArgs);
        TestTrace trace = new TestTrace();

        service.buildInterceptor().onAction(trace, exchange("call_tool", gatewayShell));

        assertThat(trace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(trace.getFinalAnswer())
                .contains("BLOCKED (hardline)")
                .contains("元数据")
                .contains("api%255Fkey=***")
                .doesNotContain("api%255Fkey=hardline-secret")
                .doesNotContain("hardline-secret");
        assertThat(service.getPendingApproval(trace.session)).isNull();
    }

    @Test
    void shouldBlockGatewayWebfetchWebsitePolicyBeforeApproval() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig));

        Map<String, Object> webfetchArgs = new LinkedHashMap<String, Object>();
        webfetchArgs.put("url", "https://docs.blocked.example/page");
        Map<String, Object> gatewayWebfetch = new LinkedHashMap<String, Object>();
        gatewayWebfetch.put("tool_name", "webfetch");
        gatewayWebfetch.put("tool_args", webfetchArgs);
        TestTrace webfetchTrace = new TestTrace();

        service.buildInterceptor().onAction(webfetchTrace, exchange("call_tool", gatewayWebfetch));

        assertThat(webfetchTrace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(webfetchTrace.getFinalAnswer()).contains("URL 安全策略").contains("blocked.example");
        assertThat(service.getPendingApproval(webfetchTrace.session)).isNull();

        Map<String, Object> httpArgs = new LinkedHashMap<String, Object>();
        httpArgs.put("url", "https://blocked.example/status");
        Map<String, Object> gatewayHttp = new LinkedHashMap<String, Object>();
        gatewayHttp.put("tool_name", "webfetch");
        gatewayHttp.put("tool_args", httpArgs);
        TestTrace httpTrace = new TestTrace();

        service.buildInterceptor().onAction(httpTrace, exchange("call_tool", gatewayHttp));

        assertThat(httpTrace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(httpTrace.getFinalAnswer()).contains("URL 安全策略").contains("blocked.example");
        assertThat(service.getPendingApproval(httpTrace.session)).isNull();

        Map<String, Object> websearchArgs = new LinkedHashMap<String, Object>();
        websearchArgs.put("query", "read https://blocked.example/search?token=secret789");
        Map<String, Object> gatewayWebsearch = new LinkedHashMap<String, Object>();
        gatewayWebsearch.put("tool_name", "websearch");
        gatewayWebsearch.put("tool_args", websearchArgs);
        TestTrace websearchTrace = new TestTrace();

        service.buildInterceptor().onAction(websearchTrace, exchange("call_tool", gatewayWebsearch));

        assertThat(websearchTrace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(websearchTrace.getFinalAnswer())
                .contains("URL 安全策略")
                .contains("blocked.example")
                .doesNotContain("secret789");
        assertThat(service.getPendingApproval(websearchTrace.session)).isNull();

        Map<String, Object> codeSearchArgs = new LinkedHashMap<String, Object>();
        codeSearchArgs.put("query", "inspect https://blocked.example/source?token=secret123");
        Map<String, Object> gatewayCodeSearch = new LinkedHashMap<String, Object>();
        gatewayCodeSearch.put("tool_name", "codesearch");
        gatewayCodeSearch.put("tool_args", codeSearchArgs);
        TestTrace codeSearchTrace = new TestTrace();

        service.buildInterceptor().onAction(codeSearchTrace, exchange("call_tool", gatewayCodeSearch));

        assertThat(codeSearchTrace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(codeSearchTrace.getFinalAnswer())
                .contains("URL 安全策略")
                .contains("blocked.example")
                .doesNotContain("secret123");
        assertThat(service.getPendingApproval(codeSearchTrace.session)).isNull();

        Map<String, Object> exactCodeSearchArgs = new LinkedHashMap<String, Object>();
        exactCodeSearchArgs.put(
                "query", "inspect https://docs.blocked.example/source?token=secret456");
        Map<String, Object> gatewayExactCodeSearch = new LinkedHashMap<String, Object>();
        gatewayExactCodeSearch.put("tool_name", "codesearch");
        gatewayExactCodeSearch.put("tool_args", exactCodeSearchArgs);
        TestTrace exactCodeSearchTrace = new TestTrace();

        service.buildInterceptor()
                .onAction(exactCodeSearchTrace, exchange("call_tool", gatewayExactCodeSearch));

        assertThat(exactCodeSearchTrace.getRoute()).isEqualTo(Agent.ID_END);
        assertThat(exactCodeSearchTrace.getFinalAnswer())
                .contains("URL 安全策略")
                .contains("blocked.example")
                .doesNotContain("secret456");
        assertThat(service.getPendingApproval(exactCodeSearchTrace.session)).isNull();
    }

}
