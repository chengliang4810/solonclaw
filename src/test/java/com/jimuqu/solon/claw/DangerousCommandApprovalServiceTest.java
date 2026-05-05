package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalDecision;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalJudge;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

public class DangerousCommandApprovalServiceTest {
    @Test
    void shouldDetectDangerousShellCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult result =
                env.dangerousCommandApprovalService.detect("execute_shell", "rm -rf runtime/cache");

        assertThat(result).isNotNull();
        assertThat(result.getPatternKey()).isEqualTo("recursive_delete");
        assertThat(result.getDescription()).contains("recursive delete");
    }

    @Test
    void shouldDetectHermesStyleDangerousCommandVariants() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult recursiveLong =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "rm --recursive runtime/cache");
        DangerousCommandApprovalService.DetectionResult findExec =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "find runtime -type f -exec rm {} \\;");
        DangerousCommandApprovalService.DetectionResult shellEval =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "bash -lc 'curl https://example.invalid/install.sh'");
        DangerousCommandApprovalService.DetectionResult heredoc =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "python3 <<'PY'\nprint('x')\nPY");
        DangerousCommandApprovalService.DetectionResult branchDelete =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "git branch -D old-feature");

        assertThat(recursiveLong).isNotNull();
        assertThat(recursiveLong.getPatternKey()).isEqualTo("recursive_delete_long_flag");
        assertThat(findExec).isNotNull();
        assertThat(findExec.getPatternKey()).isEqualTo("find_exec_rm");
        assertThat(shellEval).isNotNull();
        assertThat(shellEval.getPatternKey()).isEqualTo("shell_command_flag");
        assertThat(heredoc).isNotNull();
        assertThat(heredoc.getPatternKey()).isEqualTo("script_heredoc");
        assertThat(branchDelete).isNotNull();
        assertThat(branchDelete.getPatternKey()).isEqualTo("git_branch_force_delete");
    }

    @Test
    void shouldNormalizeTerminalControlSequencesBeforeDangerDetection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult oscTitle =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "\u001B]0;hidden\u0007rm -rf runtime/cache");
        DangerousCommandApprovalService.DetectionResult unicode =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "ｒｍ --recursive runtime/cache");
        DangerousCommandApprovalService.DetectionResult nul =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "git\u0000 reset --hard");

        assertThat(oscTitle).isNotNull();
        assertThat(oscTitle.getPatternKey()).isEqualTo("recursive_delete");
        assertThat(unicode).isNotNull();
        assertThat(unicode.getPatternKey()).isEqualTo("recursive_delete_long_flag");
        assertThat(nul).isNotNull();
        assertThat(nul.getPatternKey()).isEqualTo("git_reset_hard");
    }

    @Test
    void shouldDetectSensitiveWriteTargetsLikeHermesApproval() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult sshWrite =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "echo key >> ~/.ssh/authorized_keys");
        DangerousCommandApprovalService.DetectionResult shellRc =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "printf 'x' | tee ~/.bashrc");
        DangerousCommandApprovalService.DetectionResult envWrite =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "cat secrets > .env.production");
        DangerousCommandApprovalService.DetectionResult configMove =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "mv config.tmp config.yml");

        assertThat(sshWrite).isNotNull();
        assertThat(sshWrite.getPatternKey()).isEqualTo("sensitive_redirection");
        assertThat(shellRc).isNotNull();
        assertThat(shellRc.getPatternKey()).isEqualTo("sensitive_tee");
        assertThat(envWrite).isNotNull();
        assertThat(envWrite.getPatternKey()).isEqualTo("project_sensitive_redirection");
        assertThat(configMove).isNotNull();
        assertThat(configMove.getPatternKey()).isEqualTo("copy_into_project_sensitive");
    }

    @Test
    void shouldProtectGatewayLifecycleAndSelfTerminationCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult gatewayStop =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "jimuqu-agent gateway restart");
        DangerousCommandApprovalService.DetectionResult gatewayDetached =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "nohup jimuqu-agent gateway run > gateway.log 2>&1 &");
        DangerousCommandApprovalService.DetectionResult killByName =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "pkill -f jimuqu-agent");
        DangerousCommandApprovalService.DetectionResult killByPgrep =
                env.dangerousCommandApprovalService.detect(
                        "execute_shell", "kill -9 $(pgrep -f jimuqu-agent)");

        assertThat(gatewayStop).isNotNull();
        assertThat(gatewayStop.getPatternKey()).isEqualTo("gateway_stop_restart");
        assertThat(gatewayDetached).isNotNull();
        assertThat(gatewayDetached.getPatternKey()).isEqualTo("gateway_run_detached");
        assertThat(killByName).isNotNull();
        assertThat(killByName.getPatternKey()).isEqualTo("kill_agent_process");
        assertThat(killByPgrep).isNotNull();
        assertThat(killByPgrep.getPatternKey()).isEqualTo("kill_pgrep_expansion");
    }

    @Test
    void shouldWarnForForegroundBackgroundShellPatterns() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        String nohup =
                env.dangerousCommandApprovalService.foregroundBackgroundGuidance(
                        "execute_shell", "nohup npm run dev > app.log 2>&1");
        String amp =
                env.dangerousCommandApprovalService.foregroundBackgroundGuidance(
                        "execute_shell", "npm run dev &");
        String server =
                env.dangerousCommandApprovalService.foregroundBackgroundGuidance(
                        "execute_shell", "python -m http.server 8000");
        String help =
                env.dangerousCommandApprovalService.foregroundBackgroundGuidance(
                        "execute_shell", "npm run dev --help");

        assertThat(nohup).contains("nohup");
        assertThat(amp).contains("&");
        assertThat(server).contains("长驻服务");
        assertThat(help).isNull();
    }

    @Test
    void shouldIgnoreSafeShellCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult result =
                env.dangerousCommandApprovalService.detect("execute_shell", "git status");

        assertThat(result).isNull();
    }

    @Test
    void shouldDetectHardlineCommandSeparatelyFromApprovableDanger() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult result =
                env.dangerousCommandApprovalService.detectHardline("execute_shell", "sudo reboot");

        assertThat(result).isNotNull();
        assertThat(result.isHardline()).isTrue();
        assertThat(result.getPatternKey()).isEqualTo("hardline_shutdown");
        assertThat(result.getDescription()).contains("shutdown");
    }

    @Test
    void shouldExposeHermesApprovalModeConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        env.appConfig.getApprovals().setMode("off");
        env.appConfig.getApprovals().setCronMode("approve");
        env.appConfig.getApprovals().setTimeoutSeconds(45);
        env.appConfig.getApprovals().setGatewayTimeoutSeconds(120);

        assertThat(env.dangerousCommandApprovalService.approvalMode()).isEqualTo("off");
        assertThat(env.dangerousCommandApprovalService.cronApprovalMode()).isEqualTo("approve");
        assertThat(env.dangerousCommandApprovalService.approvalTimeoutSeconds()).isEqualTo(45);
        assertThat(env.dangerousCommandApprovalService.approvalGatewayTimeoutSeconds()).isEqualTo(120);
        assertThat(env.dangerousCommandApprovalService.detectHardline("execute_shell", "sudo reboot"))
                .isNotNull();
        assertThat(env.dangerousCommandApprovalService.detect("execute_shell", "rm -rf runtime/cache"))
                .isNotNull();
    }

    @Test
    void shouldTreatWindowsTerminalGuardrailsAsHardline() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        DangerousCommandApprovalService.DetectionResult format =
                env.dangerousCommandApprovalService.detectHardline("execute_shell", "format C:");
        DangerousCommandApprovalService.DetectionResult profileDelete =
                env.dangerousCommandApprovalService.detectHardline(
                        "execute_shell", "Remove-Item -Recurse -Force $env:USERPROFILE");
        DangerousCommandApprovalService.DetectionResult shutdown =
                env.dangerousCommandApprovalService.detectHardline("execute_shell", "shutdown /r /t 0");

        assertThat(format).isNotNull();
        assertThat(format.getPatternKey()).isEqualTo("hardline_windows_format");
        assertThat(profileDelete).isNotNull();
        assertThat(profileDelete.getPatternKey()).isEqualTo("hardline_windows_delete_profile");
        assertThat(shutdown).isNotNull();
        assertThat(shutdown.getPatternKey()).isEqualTo("hardline_windows_shutdown");
    }

    @Test
    void shouldMatchHermesHardlineBlocklistExamples() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String[] blocked =
                new String[] {
                    "rm -rf /",
                    "rm -rf /*",
                    "rm -rf /home",
                    "rm -rf /home/*",
                    "rm -rf /etc",
                    "rm -rf /usr",
                    "rm -rf /var",
                    "rm -rf /boot",
                    "rm -rf /bin",
                    "rm --recursive --force /",
                    "rm -fr /",
                    "sudo rm -rf /",
                    "rm -rf ~",
                    "rm -rf ~/",
                    "rm -rf ~/*",
                    "rm -rf $HOME",
                    "mkfs.ext4 /dev/sda1",
                    "mkfs /dev/sdb",
                    "mkfs.xfs /dev/nvme0n1",
                    "dd if=/dev/zero of=/dev/sda bs=1M",
                    "dd if=/dev/urandom of=/dev/nvme0n1",
                    "dd if=anything of=/dev/hda",
                    "dd if=/dev/zero of=/dev/mmcblk0",
                    "dd if=/dev/zero of=/dev/vda",
                    "dd if=/dev/zero of=/dev/xvda",
                    "echo bad > /dev/sda",
                    "cat /dev/urandom > /dev/sdb",
                    "cat image.bin > /dev/nvme0n1",
                    "cat image.bin > /dev/mmcblk0",
                    ":(){ :|:& };:",
                    "kill -9 -1",
                    "kill -1",
                    "shutdown -h now",
                    "shutdown -r now",
                    "sudo shutdown now",
                    "reboot",
                    "sudo reboot",
                    "halt",
                    "poweroff",
                    "init 0",
                    "init 6",
                    "telinit 0",
                    "systemctl poweroff",
                    "systemctl reboot",
                    "systemctl halt",
                    "ls; reboot",
                    "echo done && shutdown -h now",
                    "false || halt",
                    "$(reboot)",
                    "`shutdown now`",
                    "sudo -E shutdown now",
                    "env FOO=1 reboot",
                    "exec shutdown",
                    "nohup reboot",
                    "setsid poweroff"
                };

        for (String command : blocked) {
            DangerousCommandApprovalService.DetectionResult result =
                    env.dangerousCommandApprovalService.detectHardline("execute_shell", command);
            assertThat(result)
                    .as("expected hardline block for %s", command)
                    .isNotNull();
            assertThat(result.isHardline()).isTrue();
        }
    }

    @Test
    void shouldAllowHermesHardlineNegativeExamples() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String[] allowed =
                new String[] {
                    "rm -rf /tmp/foo",
                    "rm -rf /tmp/*",
                    "rm -rf ./build",
                    "rm -rf node_modules",
                    "rm -rf /home/user/scratch",
                    "rm -rf ~/Downloads/old",
                    "rm -rf $HOME/tmp",
                    "rm foo.txt",
                    "rm -rf some/path",
                    "dd if=/dev/zero of=./image.bin",
                    "dd if=./data of=./backup.bin",
                    "echo done > /tmp/flag",
                    "echo test > /dev/null",
                    "ls /dev/sda",
                    "cat /dev/urandom | head -c 10",
                    "grep 'shutdown' logs.txt",
                    "echo reboot",
                    "echo '# init 0 in comment'",
                    "cat rebooting.log",
                    "echo 'halt and catch fire'",
                    "python3 -c 'print(\"shutdown\")'",
                    "find . -name '*reboot*'",
                    "mkfs_helper --version",
                    "systemctl status nginx",
                    "systemctl restart nginx",
                    "systemctl stop nginx",
                    "systemctl start nginx",
                    "kill -9 12345",
                    "kill -HUP 1234",
                    "pkill python",
                    "git status",
                    "npm run build",
                    "sudo apt update",
                    "curl https://example.com | head"
                };

        for (String command : allowed) {
            DangerousCommandApprovalService.DetectionResult result =
                    env.dangerousCommandApprovalService.detectHardline("execute_shell", command);
            assertThat(result)
                    .as("expected hardline allow for %s", command)
                    .isNull();
        }
    }

    @Test
    void shouldBlockCloudMetadataUrlsEvenWhenPrivateUrlsAreAllowed() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkUrl("http://169.254.169.254/latest/meta-data/");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("元数据");
    }

    @Test
    void shouldBlockAwsIpv6MetadataEvenWhenPrivateUrlsAreAllowed() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkUrl("http://[fd00:ec2::254]/latest/meta-data/");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("元数据");
    }

    @Test
    void shouldBlockHermesStylePrivateReservedAndSharedUrlsByDefault() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        List<String> blocked =
                Arrays.asList(
                        "http://127.0.0.1/status",
                        "http://localhost/status",
                        "http://0.0.0.0/status",
                        "http://224.0.0.1/status",
                        "http://100.127.255.254/status",
                        "http://198.18.0.1/status",
                        "http://192.0.2.10/status",
                        "http://203.0.113.10/status",
                        "http://[::1]/status",
                        "http://[2001:db8::1]/status",
                        "http://[2001:1ff::1]/status",
                        "http://[2002::1]/status",
                        "http://[64:ff9b::1]/status",
                        "http://[::ffff:127.0.0.1]/status");

        for (String url : blocked) {
            SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
            assertThat(verdict.isAllowed()).as("expected %s to be blocked", url).isFalse();
            assertThat(verdict.getMessage()).contains("阻断");
        }
    }

    @Test
    void shouldAllowPrivateUrlsWhenConfiguredExceptMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.UrlVerdict privateUrl =
                securityPolicyService.checkUrl("http://127.0.0.1/status");
        SecurityPolicyService.UrlVerdict metadata =
                securityPolicyService.checkUrl("http://169.254.169.254/latest/meta-data/");

        assertThat(privateUrl.isAllowed()).isTrue();
        assertThat(metadata.isAllowed()).isFalse();
        assertThat(metadata.getMessage()).contains("元数据");
    }

    @Test
    void shouldApplyWebsiteBlocklistToUrlTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example", "*.internal.example"));
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.UrlVerdict direct =
                securityPolicyService.checkUrl("https://docs.blocked.example/page?token=secret");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("query", "read https://api.internal.example/docs");
        SecurityPolicyService.UrlVerdict query =
                securityPolicyService.checkToolArgs("websearch", args);
        Map<String, Object> schemelessArgs = new LinkedHashMap<String, Object>();
        schemelessArgs.put("query", "read www.blocked.example/docs");
        SecurityPolicyService.UrlVerdict schemeless =
                securityPolicyService.checkToolArgs("websearch", schemelessArgs);
        Map<String, Object> wildcardBareArgs = new LinkedHashMap<String, Object>();
        wildcardBareArgs.put("query", "read internal.example/docs");
        SecurityPolicyService.UrlVerdict wildcardBare =
                securityPolicyService.checkToolArgs("websearch", wildcardBareArgs);

        assertThat(direct.isAllowed()).isFalse();
        assertThat(direct.getMessage()).contains("blocked.example");
        assertThat(query.isAllowed()).isFalse();
        assertThat(query.getMessage()).contains("*.internal.example");
        assertThat(schemeless.isAllowed()).isFalse();
        assertThat(schemeless.getMessage()).contains("blocked.example");
        assertThat(wildcardBare.isAllowed()).isTrue();
    }

    @Test
    void shouldApplySharedWebsiteBlocklistFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File shared = new File(env.appConfig.getRuntime().getHome(), "blocked-sites.txt");
        FileUtil.writeUtf8String("# shared rules\nshared.example\n*.team.internal\n", shared);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList("blocked-sites.txt"));
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.UrlVerdict exact =
                securityPolicyService.checkUrl("https://shared.example/docs");
        SecurityPolicyService.UrlVerdict wildcard =
                securityPolicyService.checkUrl("https://api.team.internal/v1");

        assertThat(exact.isAllowed()).isFalse();
        assertThat(exact.getMessage()).contains("shared.example");
        assertThat(wildcard.isAllowed()).isFalse();
        assertThat(wildcard.getMessage()).contains("*.team.internal");
    }

    @Test
    void shouldBlockCredentialFilePathsForFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", ".ssh/id_ed25519");

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("file_read", args);

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("凭据");
        assertThat(verdict.getPath()).isEqualTo(".ssh/id_ed25519");
    }

    @Test
    void shouldBlockHermesWriteDeniedSystemPathsForFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        assertWriteDenied(securityPolicyService, "/etc/shadow");
        assertWriteDenied(securityPolicyService, "/etc/passwd");
        assertWriteDenied(securityPolicyService, "/etc/sudoers");
        assertWriteDenied(securityPolicyService, "/etc/sudoers.d/custom");
        assertWriteDenied(securityPolicyService, "/etc/systemd/system/evil.service");
    }

    @Test
    void shouldBlockHermesWriteDeniedHomeFilesForFileTools() throws Exception {
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
    void shouldBlockWritesOutsideConfiguredHermesSafeRoot() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setWriteSafeRoot("D:/workspace/safe-root");
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> rootArgs = new LinkedHashMap<String, Object>();
        rootArgs.put("fileName", "D:/workspace/safe-root");
        Map<String, Object> insideArgs = new LinkedHashMap<String, Object>();
        insideArgs.put("fileName", "D:/workspace/safe-root/src/main.java");
        Map<String, Object> outsideArgs = new LinkedHashMap<String, Object>();
        outsideArgs.put("fileName", "D:/workspace/other/file.txt");
        Map<String, Object> prefixArgs = new LinkedHashMap<String, Object>();
        prefixArgs.put("fileName", "D:/workspace/safe-root-other/file.txt");

        SecurityPolicyService.FileVerdict root =
                securityPolicyService.checkFileToolArgs("file_write", rootArgs);
        SecurityPolicyService.FileVerdict inside =
                securityPolicyService.checkFileToolArgs("file_write", insideArgs);
        SecurityPolicyService.FileVerdict outside =
                securityPolicyService.checkFileToolArgs("file_write", outsideArgs);
        SecurityPolicyService.FileVerdict prefix =
                securityPolicyService.checkFileToolArgs("file_write", prefixArgs);

        assertThat(root.isAllowed()).isTrue();
        assertThat(inside.isAllowed()).isTrue();
        assertThat(outside.isAllowed()).isFalse();
        assertThat(outside.getMessage()).contains("安全写入根");
        assertThat(prefix.isAllowed()).isFalse();
    }

    @Test
    void shouldApplyConfiguredSafeRootToShellCommandPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setWriteSafeRoot("D:/workspace/safe-root");
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.FileVerdict inside =
                securityPolicyService.checkCommandPaths(
                        "Set-Content D:/workspace/safe-root/output.txt ok");
        SecurityPolicyService.FileVerdict outside =
                securityPolicyService.checkCommandPaths("echo bad > D:/workspace/other/output.txt");

        assertThat(inside.isAllowed()).isTrue();
        assertThat(outside.isAllowed()).isFalse();
        assertThat(outside.getPath()).isEqualTo("D:/workspace/other/output.txt");
    }

    @Test
    void shouldBlockPathTraversalForFileTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", "../runtime/config.yml");

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("file_write", args);

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("路径遍历");
    }

    @Test
    void shouldInspectNestedToolArgumentsForUnsafePaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("file_path", ".env.local");
        args.put("metadata", metadata);

        SecurityPolicyService.FileVerdict nested =
                securityPolicyService.checkFileToolArgs("mcp_remote_tool", args);

        Map<String, Object> batch = new LinkedHashMap<String, Object>();
        batch.put("paths", Arrays.asList("README.md", "~/.ssh/id_ed25519"));
        SecurityPolicyService.FileVerdict array =
                securityPolicyService.checkFileToolArgs("mcp_remote_tool", batch);

        assertThat(nested.isAllowed()).isFalse();
        assertThat(nested.getPath()).isEqualTo(".env.local");
        assertThat(array.isAllowed()).isFalse();
        assertThat(array.getPath()).isEqualTo("~/.ssh/id_ed25519");
    }

    @Test
    void shouldInspectHermesPatchPathsForCredentialFiles() throws Exception {
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
    void shouldBlockCredentialPathsInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkCommandPaths("cat ~/.aws/credentials");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("凭据");
        assertThat(verdict.getPath()).isEqualTo("~/.aws/credentials");
    }

    @Test
    void shouldBlockBareCredentialFileNamesInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.FileVerdict dotenv =
                securityPolicyService.checkCommandPaths("cat .env > backup.txt");
        SecurityPolicyService.FileVerdict netrc =
                securityPolicyService.checkCommandPaths("Get-Content .netrc");
        SecurityPolicyService.FileVerdict safe =
                securityPolicyService.checkCommandPaths("cat config.example.yml > backup.yml");

        assertThat(dotenv.isAllowed()).isFalse();
        assertThat(dotenv.getMessage()).contains("凭据");
        assertThat(dotenv.getPath()).isEqualTo(".env");
        assertThat(netrc.isAllowed()).isFalse();
        assertThat(netrc.getPath()).isEqualTo(".netrc");
        assertThat(safe.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockWindowsCredentialPathsInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.FileVerdict powershell =
                securityPolicyService.checkCommandPaths("type $env:USERPROFILE\\.ssh\\id_rsa");
        SecurityPolicyService.FileVerdict cmd =
                securityPolicyService.checkCommandPaths("type %APPDATA%\\gh\\hosts.yml");

        assertThat(powershell.isAllowed()).isFalse();
        assertThat(powershell.getMessage()).contains("凭据");
        assertThat(cmd.isAllowed()).isFalse();
        assertThat(cmd.getMessage()).contains("凭据");
    }

    @Test
    void shouldBlockHermesWriteDeniedPathsInsideShellCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);

        SecurityPolicyService.FileVerdict shadow =
                securityPolicyService.checkCommandPaths("echo bad > /etc/shadow");
        SecurityPolicyService.FileVerdict profile =
                securityPolicyService.checkCommandPaths("Set-Content ~/.bashrc bad");
        SecurityPolicyService.FileVerdict systemd =
                securityPolicyService.checkCommandPaths("cat service > /etc/systemd/system/evil.service");

        assertThat(shadow.isAllowed()).isFalse();
        assertThat(shadow.getMessage()).contains("系统文件");
        assertThat(profile.isAllowed()).isFalse();
        assertThat(systemd.isAllowed()).isFalse();
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
        SecurityPolicyService.UrlVerdict python =
                securityPolicyService.checkCommandUrls(
                        "requests.get('https://blocked.example/api?token=secret123');");

        assertThat(metadata.isAllowed()).isFalse();
        assertThat(metadata.getMessage()).contains("元数据");
        assertThat(metadata.getUrl()).contains("token=secret123");
        assertThat(
                        com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(
                                metadata.getUrl()))
                .doesNotContain("secret123");
        assertThat(
                        com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(
                                "https://user:pass@example.com/path?token=secret123"))
                .doesNotContain("user:pass")
                .doesNotContain("secret123");
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
    void shouldBuildFeishuApprovalCardExtrasAndParseCardAction() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DangerousCommandApprovalService.PendingApproval pending =
                new DangerousCommandApprovalService.PendingApproval();
        pending.setToolName("execute_shell");
        pending.setPatternKey("recursive_delete");
        pending.setDescription("recursive delete");
        pending.setCommand("rm -rf runtime/cache");
        pending.setApprovalId("approval-123");

        Map<String, Object> extras =
                env.dangerousCommandApprovalService.buildDeliveryExtras(
                        PlatformType.FEISHU, pending);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(
                DangerousCommandApprovalService.CARD_ACTION_KEY,
                DangerousCommandApprovalService.CARD_ACTION_APPROVE);
        payload.put(DangerousCommandApprovalService.CARD_SCOPE_KEY, "always");
        payload.put(DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY, "approval-123");

        assertThat(extras.get("approvalId")).isEqualTo("approval-123");
        assertThat(extras.get("mode"))
                .isEqualTo(DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);
        assertThat(extras.get("approvalCommand")).isEqualTo("rm -rf runtime/cache");
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload))
                .isEqualTo("/approve approval-123 always");

        payload.put(
                DangerousCommandApprovalService.CARD_ACTION_KEY,
                DangerousCommandApprovalService.CARD_ACTION_DENY);
        assertThat(DangerousCommandApprovalService.commandFromCardActionPayload(payload))
                .isEqualTo("/deny approval-123");
    }

    @Test
    void shouldExpirePendingApprovalLikeHermesGatewayTimeout() throws Exception {
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
                "rm -rf runtime/cache");
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);
        assertThat(pending).isNotNull();
        assertThat(pending.getExpiresAt()).isGreaterThan(pending.getCreatedAt());

        Map<String, Object> expired = new LinkedHashMap<String, Object>();
        expired.put("toolName", "execute_shell");
        expired.put("patternKey", "recursive_delete");
        expired.put("patternKeys", Collections.singletonList("recursive_delete"));
        expired.put("description", "recursive delete");
        expired.put("command", "rm -rf runtime/cache");
        expired.put("commandHash", "hash");
        expired.put("approvalKey", "execute_shell:recursive_delete:hash");
        expired.put("createdAt", System.currentTimeMillis() - 10_000L);
        expired.put("expiresAt", System.currentTimeMillis() - 1_000L);
        trace.session.getContext().remove("_dangerous_command_pending_queue_");
        trace.session.getContext().put("_dangerous_command_pending_", expired);

        assertThat(service.getPendingApproval(trace.session)).isNull();
        assertThat(service.approve(trace.session, DangerousCommandApprovalService.ApprovalScope.ONCE, "test"))
                .isFalse();
    }

    @Test
    void shouldKeepMultiplePendingApprovalsLikeHermesGatewayQueue() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TestTrace trace = new TestTrace();

        env.dangerousCommandApprovalService.storePendingApproval(
                trace.session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
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
        assertThat(env.dangerousCommandApprovalService.isSessionApproved(trace.session, "git_reset_hard"))
                .isTrue();
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
        TestEnvironment env = TestEnvironment.withFakeLlm();
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
        args.put("code", "rm -rf runtime/cache");

        interceptor.onAction(trace, "execute_shell", args);
        DangerousCommandApprovalService.PendingApproval pending =
                service.getPendingApproval(trace.session);

        assertThat(trace.getFinalAnswer()).contains("Security scan").contains("recursive delete");
        assertThat(pending).isNotNull();
        assertThat(pending.getPatternKeys())
                .containsExactly("tirith:homograph_url", "recursive_delete");
    }

    @Test
    void shouldTreatAlwaysApprovalForTirithAsSessionOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding("shortened_url", "MEDIUM", "Short URL", "")),
                                "shortened URL"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "echo hello");
        service.buildInterceptor().onAction(trace, "execute_shell", args);

        boolean approved = service.approve(trace.session, DangerousCommandApprovalService.ApprovalScope.ALWAYS, "test");

        assertThat(approved).isTrue();
        assertThat(service.isSessionApproved(trace.session, "tirith:shortened_url")).isTrue();
        assertThat(service.isAlwaysApproved("tirith:shortened_url")).isFalse();
    }

    @Test
    void shouldAutoApproveLowRiskDangerousCommandInSmartMode() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getApprovals().setMode("smart");
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        null);
        service.setSmartApprovalJudge(
                new SmartApprovalJudge() {
                    @Override
                    public SmartApprovalDecision judge(
                            String toolName, String command, String description) {
                        return SmartApprovalDecision.approve("low risk cleanup");
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "rm -rf runtime/cache");

        service.buildInterceptor().onAction(trace, "execute_shell", args);

        assertThat(service.getPendingApproval(trace.session)).isNull();
        assertThat(trace.getFinalAnswer()).isNull();
        assertThat(service.isSessionApproved(trace.session, "recursive_delete")).isTrue();
        assertThat(service.isAlwaysApproved("recursive_delete")).isFalse();
    }

    @Test
    void shouldEscalateSmartApprovalWhenJudgeDoesNotApprove() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getApprovals().setMode("smart");
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        null);
        service.setSmartApprovalJudge(
                new SmartApprovalJudge() {
                    @Override
                    public SmartApprovalDecision judge(
                            String toolName, String command, String description) {
                        return SmartApprovalDecision.escalate("needs user");
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "rm -rf runtime/cache");

        service.buildInterceptor().onAction(trace, "execute_shell", args);

        assertThat(service.getPendingApproval(trace.session)).isNotNull();
        assertThat(trace.getFinalAnswer()).contains("危险命令需要审批");
        assertThat(service.isSessionApproved(trace.session, "recursive_delete")).isFalse();
    }

    @Test
    void shouldNotSmartApproveTirithFindings() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getApprovals().setMode("smart");
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding("terminal_injection", "HIGH", "Terminal injection", "")),
                                "terminal injection"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        service.setSmartApprovalJudge(
                new SmartApprovalJudge() {
                    @Override
                    public SmartApprovalDecision judge(
                            String toolName, String command, String description) {
                        return SmartApprovalDecision.approve("low risk");
                    }
                });
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("code", "echo hello");

        service.buildInterceptor().onAction(trace, "execute_shell", args);

        assertThat(service.getPendingApproval(trace.session)).isNotNull();
        assertThat(trace.getFinalAnswer()).contains("Security scan");
        assertThat(service.isSessionApproved(trace.session, "tirith:terminal_injection")).isFalse();
    }

    @Test
    void shouldKeepHardlineBlockedWhenApprovalModeIsOffAndTirithWarns() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getApprovals().setMode("off");
        FakeTirithSecurityService tirith =
                new FakeTirithSecurityService(
                        scanResult(
                                "warn",
                                Collections.singletonList(
                                        finding("terminal_injection", "HIGH", "Terminal injection", "")),
                                "terminal injection"));
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository,
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        tirith);
        DangerousCommandApprovalService.DetectionResult hardline =
                service.detectHardline("execute_shell", "sudo reboot");

        assertThat(service.approvalMode()).isEqualTo("off");
        assertThat(hardline).isNotNull();
        assertThat(hardline.isHardline()).isTrue();
        assertThat(hardline.getDescription()).contains("shutdown");
    }

    private static TirithSecurityService.ScanResult scanResult(
            String action, List<TirithSecurityService.Finding> findings, String summary)
            throws Exception {
        java.lang.reflect.Constructor<TirithSecurityService.ScanResult> constructor =
                TirithSecurityService.ScanResult.class.getDeclaredConstructor(
                        String.class, List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(action, findings, summary);
    }

    private static TirithSecurityService.Finding finding(
            String ruleId, String severity, String title, String description) throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("rule_id", ruleId);
        values.put("severity", severity);
        values.put("title", title);
        values.put("description", description);
        return TirithSecurityService.Finding.from(values);
    }

    private static void assertWriteDenied(SecurityPolicyService securityPolicyService, String path) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("fileName", path);
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs("file_write", args);
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getPath()).isEqualTo(path);
    }

    private static class FakeTirithSecurityService extends TirithSecurityService {
        private final TirithSecurityService.ScanResult result;

        private FakeTirithSecurityService(TirithSecurityService.ScanResult result) {
            super(null);
            this.result = result;
        }

        @Override
        public TirithSecurityService.ScanResult checkCommandSecurityForTool(
                String toolName, String command) {
            return result;
        }
    }

    private static class TestTrace extends org.noear.solon.ai.agent.react.ReActTrace {
        private final InMemoryAgentSession session = new InMemoryAgentSession("tirith-test");

        @Override
        public InMemoryAgentSession getSession() {
            return session;
        }

        @Override
        public org.noear.solon.flow.FlowContext getContext() {
            return session.getContext();
        }
    }
}
