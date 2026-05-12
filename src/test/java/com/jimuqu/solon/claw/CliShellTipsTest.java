package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliShell;
import com.jimuqu.solon.claw.cli.ConsoleEventSink;
import com.jimuqu.solon.claw.cli.LocalTerminalHelp;
import com.jimuqu.solon.claw.cli.TerminalCommandCatalog;
import com.jimuqu.solon.claw.cli.TerminalSecurityPolicyView;
import java.util.Arrays;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

public class CliShellTipsTest {
    @Test
    void shouldHandleTipsLocallyAndExposeCompletion() throws Exception {
        CliShell shell = new CliShell(null, new CliMode(CliMode.Kind.CLI, null, null));

        assertThat(commandList()).containsExactly(TerminalCommandCatalog.SLASH_COMMANDS);
        assertThat(LocalTerminalHelp.text()).contains("/reload-mcp [now|always]");
        assertThat(commandList())
                .contains(
                        "/security",
                        "/security audit",
                        "/security status",
                        "/security policy",
                        "/security audit-tool",
                        "/security approvals",
                        "/security slash-confirm",
                        "/security approval-card",
                        "/security approval-audit",
                        "/security mcp-reload",
                        "/security hardline",
                        "/security terminal-guardrails",
                        "/security tirith",
                        "/security tirith-approval",
                        "/security cron-approvals",
                        "/security subagent-approvals",
                        "/security smart-approval",
                        "/security urls",
                        "/security private-urls",
                        "/security website",
                        "/security paths",
                        "/security credentials",
                        "/security skill-credentials",
                        "/security tool-args",
                        "/security lifecycle",
                        "/security mcp",
                        "/security mcp-oauth",
                        "/security mcp-package",
                        "/security schema",
                        "/security attachments",
                        "/security terminal-paste",
                        "/security media-cache",
                        "/security tool-results",
                        "/security patch",
                        "/security code-execution",
                        "/security subprocess-env",
                        "/security terminal-output",
                        "/security sudo",
                        "/security process");
        assertThat(shouldHandleInline(shell, "/tips")).isTrue();
        assertThat(shouldHandleInline(shell, "/skin mono")).isTrue();
        assertThat(shouldHandleInline(shell, "/security audit")).isTrue();

        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        int exitCode = sendOnce(shell, writer, "/tips");

        assertThat(exitCode).isEqualTo(0);
        assertThat(buffer.toString()).contains("终端提示").contains("/queue").contains("/steer");

        StringWriter skinBuffer = new StringWriter();
        int skinExitCode = sendOnce(shell, new PrintWriter(skinBuffer), "/skin mono");
        assertThat(skinExitCode).isEqualTo(0);
        assertThat(skinBuffer.toString()).contains("当前皮肤：mono").contains("/skin <名称>");
    }

    @Test
    void shouldRenderSecurityPolicyLocally() throws Exception {
        CliShell shell = new CliShell(null, new CliMode(CliMode.Kind.CLI, null, null));

        StringWriter buffer = new StringWriter();
        int exitCode = sendOnce(shell, new PrintWriter(buffer), "/security approvals");

        assertThat(exitCode).isEqualTo(0);
        assertThat(buffer.toString())
                .contains("审批策略摘要")
                .contains("object_storage_exposure_change")
                .contains("remote_credential_file_transfer")
                .contains("sensitive_file_clipboard_export")
                .contains("剪贴板凭据")
                .contains("python=true")
                .contains("js=true")
                .contains("代码凭据输出")
                .contains("pythonStdout=true")
                .contains("jsLog=true")
                .contains("Secret 存储")
                .contains("secret_store_destroy")
                .contains("终端护栏");
        assertThat(TerminalSecurityPolicyView.render(null, "/security audit"))
                .contains("安全审计摘要")
                .contains("/security status")
                .contains("/security audit-tool")
                .contains("/security mcp")
                .contains("/security terminal-paste")
                .contains("/security media-cache")
                .contains("/security tool-results")
                .contains("/security private-urls")
                .contains("/security hardline")
                .contains("/security lifecycle")
                .contains("/security terminal-guardrails")
                .contains("/security tirith")
                .contains("/security cron-approvals")
                .contains("/security subagent-approvals")
                .contains("/security smart-approval")
                .contains("/security approval-card")
                .contains("/security approval-audit")
                .contains("/security mcp-reload")
                .contains("/security mcp-oauth")
                .contains("/security mcp-package")
                .contains("/security skill-credentials")
                .contains("/security code-execution")
                .contains("/security process");
        assertThat(TerminalSecurityPolicyView.render(null, "/security urls"))
                .contains("URL 安全策略摘要")
                .contains("unsupportedSchemeBlocked")
                .contains("protocolRelative=true")
                .contains("userinfo=true")
                .contains("fragment=true")
                .contains("pathCredential=true")
                .contains("packageProxy=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security private-urls"))
                .contains("私有 URL 安全策略摘要")
                .contains("metadataAlwaysBlocked")
                .contains("loopback")
                .contains("multicast=true")
                .contains("documentation=true")
                .contains("trustedPrivateHosts=");
        assertThat(TerminalSecurityPolicyView.render(null, "/security website"))
                .contains("网站策略摘要")
                .contains("wildcard")
                .contains("pathSafe")
                .contains("domainSamples=")
                .contains("sharedFileSamples=")
                .contains("samples=")
                .contains("schemePathIgnored=true")
                .contains("wwwIgnored=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security paths"))
                .contains("路径安全策略摘要")
                .contains("devicePath")
                .contains("windowsPrefixDenied")
                .contains("rawControl=true")
                .contains("normalizedControl=true")
                .contains("rawBlockWrite=true")
                .contains("socketEnv=true")
                .contains("pipePaths=")
                .contains("homeDenied=");
        assertThat(TerminalSecurityPolicyView.render(null, "/security credentials"))
                .contains("凭据文件策略摘要")
                .contains(".env")
                .contains(".credentials.json")
                .contains("Key 文件")
                .contains("extensions=")
                .contains("markers=")
                .contains("envExamplesAllowed=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security skill-credentials"))
                .contains("技能凭据文件安全策略摘要")
                .contains("relativeOnly=true")
                .contains("traversalRejected=true")
                .contains("required_credential_files");
        assertThat(TerminalSecurityPolicyView.render(null, "/security tool-args"))
                .contains("工具参数安全策略摘要")
                .contains("patchTarget")
                .contains("downloadOutput=true")
                .contains("detachedOutput=true")
                .contains("uploadSource=true")
                .contains("credentialOnlyBlocked=true")
                .contains("setx=true")
                .contains("registry=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security policy"))
                .contains("MCP")
                .contains("oauthReauth")
                .contains("Tool schema")
                .contains("unsupportedKeywordsStripped")
                .contains("私有 URL")
                .contains("网站策略")
                .contains("Slash 确认")
                .contains("审批卡")
                .contains("platforms=[FEISHU, QQBOT]")
                .contains("审批审计")
                .contains("keyRedacted=true")
                .contains("MCP 重载审批")
                .contains("toolNotice=true")
                .contains("MCP OAuth")
                .contains("pkce=true")
                .contains("MCP 包安全")
                .contains("malwareBlocks=true")
                .contains("npxPackageOption=true")
                .contains("pypiSourceOption=true")
                .contains("persistedReason=true")
                .contains("审计工具")
                .contains("executesCommand=false")
                .contains("技能凭据")
                .contains("traversalRejected=true")
                .contains("审批生命周期")
                .contains("tirithDowngrade=true")
                .contains("Cron 审批")
                .contains("scriptChecked=true")
                .contains("子 Agent 审批")
                .contains("humanPromptSuppressed=true")
                .contains("智能审批")
                .contains("escalateHuman=true")
                .contains("硬阻断")
                .contains("终端护栏")
                .contains("附件下载")
                .contains("redirectChecked")
                .contains("代码执行")
                .contains("timeoutKillsProcess")
                .contains("后台进程")
                .contains("managedRequired")
                .contains("工具输出")
                .contains("persistOversize");
        assertThat(TerminalSecurityPolicyView.render(null, "/security slash-confirm"))
                .contains("Slash 确认策略摘要")
                .contains("approveAll")
                .contains("metadataRedacted");
        assertThat(TerminalSecurityPolicyView.render(null, "/security approval-card"))
                .contains("审批卡策略摘要")
                .contains("platforms=[FEISHU, QQBOT]")
                .contains("outboundSanitized=true")
                .contains("rawCommand=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security approval-audit"))
                .contains("审批审计策略摘要")
                .contains("repositoryBacked=true")
                .contains("approvalKey=true")
                .contains("revocationAudited=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security mcp-reload"))
                .contains("MCP 重载审批策略摘要")
                .contains("command=/reload-mcp")
                .contains("slashConfirm=true")
                .contains("oauthUrlSafe=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security lifecycle"))
                .contains("审批生命周期策略摘要")
                .contains("approveAll=true")
                .contains("tirithDowngrade=true")
                .contains("approvalKey=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security hardline"))
                .contains("硬阻断命令策略摘要")
                .contains("metadataUrlBlocked")
                .contains("yolo=false");
        assertThat(TerminalSecurityPolicyView.render(null, "/security terminal-guardrails"))
                .contains("终端护栏策略摘要")
                .contains("managedRequired")
                .contains("PowerShell")
                .contains("Start-Process")
                .contains("requiresWait=true")
                .contains("noNewWindowNotEnough=true")
                .contains("passThruNotEnough=true")
                .contains("shellExtraction=true")
                .contains("execute_python")
                .contains("credentialPath")
                .contains("systemDns")
                .contains("systemProxy")
                .contains("windowsRegistryProxy=true")
                .contains("hostsResolver=true")
                .contains("downloadOutput=true")
                .contains("uploadSource=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security tirith"))
                .contains("Tirith 安全策略摘要")
                .contains("jsonMode")
                .contains("failOpenMode");
        assertThat(TerminalSecurityPolicyView.render(null, "/security tirith-approval"))
                .contains("Tirith 审批策略摘要")
                .contains("permanentAllowed=false")
                .contains("alwaysDowngraded=true")
                .contains("emptyKey=tirith:security_scan")
                .contains("smartDeny=true")
                .contains("cardAlwaysHidden=true")
                .contains("pendingBlocksAlways=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security cron-approvals"))
                .contains("Cron 审批策略摘要")
                .contains("hardlineBlocked=true")
                .contains("file=true")
                .contains("url=true")
                .contains("terminal=true")
                .contains("scriptChecked=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security subagent-approvals"))
                .contains("子 Agent 审批策略摘要")
                .contains("humanPromptSuppressed=true")
                .contains("pendingCreated=false");
        assertThat(TerminalSecurityPolicyView.render(null, "/security smart-approval"))
                .contains("智能审批策略摘要")
                .contains("escalateHuman=true")
                .contains("tirithFindings=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security mcp"))
                .contains("MCP 安全策略摘要")
                .contains("structuredReauth");
        assertThat(TerminalSecurityPolicyView.render(null, "/security mcp-oauth"))
                .contains("MCP OAuth 安全策略摘要")
                .contains("pkceS256=true")
                .contains("accessRedacted=true")
                .contains("handle401=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security mcp-package"))
                .contains("MCP 包安全策略摘要")
                .contains("launchers=[npx, uvx, pipx]")
                .contains("env=JIMUQU_OSV_ENDPOINT,OSV_ENDPOINT")
                .contains("malwarePrefix=MAL-")
                .contains("npxPackageOption=true")
                .contains("pypiSourceOption=true")
                .contains("pipxRunSkipped=true")
                .contains("reasons=[allow, malware_advisory, unsafe_endpoint, blocked]")
                .contains("listReason=true")
                .contains("failOpen=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security audit-tool"))
                .contains("安全审计工具策略摘要")
                .contains("executesCommand=false")
                .contains("writesFile=false")
                .contains("secretRedaction=true");
        assertThat(TerminalSecurityPolicyView.render(null, "/security schema"))
                .contains("工具 schema 安全策略摘要")
                .contains("nullableUnionCollapsed");
        assertThat(TerminalSecurityPolicyView.render(null, "/security attachments"))
                .contains("附件下载安全策略摘要")
                .contains("redirectUrl");
        assertThat(TerminalSecurityPolicyView.render(null, "/security terminal-paste"))
                .contains("终端粘贴附件安全策略摘要")
                .contains("credentialBlocked")
                .contains("percentDecode=true")
                .contains("cacheAfterPolicy=true")
                .contains("dedupe=true")
                .contains("rawPathHidden");
        assertThat(TerminalSecurityPolicyView.render(null, "/security media-cache"))
                .contains("媒体缓存安全策略摘要")
                .contains("traversalBlocked")
                .contains("hostPathHidden");
        assertThat(TerminalSecurityPolicyView.render(null, "/security tool-results"))
                .contains("工具输出安全策略摘要")
                .contains("oversizedPersisted")
                .contains("pinnedRawInline")
                .contains("previewOnlyFallback");
        assertThat(TerminalSecurityPolicyView.render(null, "/security patch"))
                .contains("补丁工具安全策略摘要")
                .contains("atomicValidation")
                .contains("symlinkEscapeBlocked");
        assertThat(TerminalSecurityPolicyView.render(null, "/security code-execution"))
                .contains("代码执行安全策略摘要")
                .contains("pathPolicy")
                .contains("timeoutKillsProcess");
        assertThat(TerminalSecurityPolicyView.render(null, "/security subprocess-env"))
                .contains("子进程环境安全策略摘要")
                .contains("defaultDenyUnknown")
                .contains("_JIMUQU_FORCE_");
        assertThat(TerminalSecurityPolicyView.render(null, "/security terminal-output"))
                .contains("终端输出安全策略摘要")
                .contains("maxInlineChars")
                .contains("osc=true")
                .contains("bidi=true")
                .contains("sudoHint")
                .contains("emptySuccess=执行成功");
        assertThat(TerminalSecurityPolicyView.render(null, "/security sudo"))
                .contains("sudo 改写安全策略摘要")
                .contains("passwordRedacted")
                .contains("ptyDisabled");
        assertThat(TerminalSecurityPolicyView.render(null, "/security process"))
                .contains("后台进程安全策略摘要")
                .contains("dangerousChecked")
                .contains("pathChecked=true")
                .contains("payloadChecked=true")
                .contains("execute_python")
                .contains("wrappers=[env, sudo")
                .contains("managedRequired");
    }

    @Test
    void shouldRenderEventsLocallyLikeTerminalUi() throws Exception {
        CliShell shell = new CliShell(null, new CliMode(CliMode.Kind.CLI, null, null));

        assertThat(commandList()).contains("/events");
        assertThat(shouldHandleInline(shell, "/events")).isTrue();
        assertThat(renderEvents(shell)).isEqualTo("暂无终端事件。");

        setField(shell, "lastEventSnapshot", eventSnapshot(3, 1, 1));
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        int exitCode = sendOnce(shell, writer, "/events");

        assertThat(exitCode).isEqualTo(0);
        assertThat(buffer.toString())
                .contains("最近一次运行事件：total=3 tools=1 failures=1")
                .contains("1. tool.start terminal")
                .contains("2. run.failed session=cli-test");
    }

    @Test
    void shouldIncludeRecentEventsInShutdownSummary() throws Exception {
        CliShell shell = new CliShell(null, new CliMode(CliMode.Kind.CLI, null, null));
        setField(shell, "lastEventSnapshot", eventSnapshot(4, 2, 1));
        setField(shell, "lastReply", "ready");
        StringWriter buffer = new StringWriter();

        renderShutdownSummary(shell, new PrintWriter(buffer), "cli-test");

        assertThat(buffer.toString())
                .contains("终端会话结束：session=cli-test")
                .contains("events=4 tools=2 failures=1")
                .contains("copy=ready");
    }

    private static String[] commandList() throws Exception {
        Field field = CliShell.class.getDeclaredField("COMMANDS");
        field.setAccessible(true);
        return (String[]) field.get(null);
    }

    private static boolean shouldHandleInline(CliShell shell, String input) throws Exception {
        Method method = CliShell.class.getDeclaredMethod("shouldHandleInline", String.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(shell, input)).booleanValue();
    }

    private static int sendOnce(CliShell shell, PrintWriter writer, String input) throws Exception {
        Method method =
                CliShell.class.getDeclaredMethod(
                        "sendOnce",
                        com.jimuqu.solon.claw.cli.LocalTerminalTaskRunner.class,
                        PrintWriter.class,
                        String.class,
                        String.class,
                        boolean.class);
        method.setAccessible(true);
        return ((Integer) method.invoke(shell, null, writer, "cli-test", input, Boolean.TRUE)).intValue();
    }

    private static String renderEvents(CliShell shell) throws Exception {
        Method method = CliShell.class.getDeclaredMethod("renderEvents");
        method.setAccessible(true);
        return (String) method.invoke(shell);
    }

    private static void setField(CliShell shell, String name, Object value) throws Exception {
        Field field = CliShell.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(shell, value);
    }

    private static void renderShutdownSummary(CliShell shell, PrintWriter writer, String sessionId)
            throws Exception {
        Method method =
                CliShell.class.getDeclaredMethod(
                        "renderShutdownSummary",
                        PrintWriter.class,
                        String.class,
                        com.jimuqu.solon.claw.cli.LocalTerminalTaskRunner.class);
        method.setAccessible(true);
        method.invoke(shell, writer, sessionId, null);
    }

    private static Object eventSnapshot(int total, int tools, int failures) throws Exception {
        Constructor<ConsoleEventSink.EventSnapshot> constructor =
                ConsoleEventSink.EventSnapshot.class.getDeclaredConstructor(
                        int.class, int.class, int.class, java.util.List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                Integer.valueOf(total),
                Integer.valueOf(tools),
                Integer.valueOf(failures),
                Arrays.asList("tool.start terminal", "run.failed session=cli-test"));
    }
}
