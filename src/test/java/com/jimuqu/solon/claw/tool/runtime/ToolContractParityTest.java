package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.SessionSearchEntry;
import com.jimuqu.solon.claw.core.model.SessionSearchQuery;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.talents.web.WebfetchTalent;
import org.noear.solon.annotation.Param;

/** 验证模型可见工具参数与外部对标仓库的 P1 契约保持一致。 */
class ToolContractParityTest {
    /** read_file 必须在 schema 中声明 path 必填。 */
    @Test
    void shouldRequireReadFilePathInSchema() throws Exception {
        Method method =
                SolonClawFileReadWriteSkill.class.getMethod(
                        "readFile", String.class, Integer.class, Integer.class);
        Param path = method.getParameters()[0].getAnnotation(Param.class);

        assertThat(path).isNotNull();
        assertThat(path.required()).isTrue();
    }

    /** write_file 与 patch 必须公开 cross_profile，并默认保持软保护开启。 */
    @Test
    void shouldExposeCrossProfileFlags() throws Exception {
        Method writeFile =
                SolonClawFileReadWriteSkill.class.getMethod(
                        "writeFile", String.class, String.class, Boolean.class);
        Param writeFlag = writeFile.getParameters()[2].getAnnotation(Param.class);
        Method patch =
                SolonClawPatchTools.class.getMethod(
                        "patch",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Boolean.class,
                        String.class,
                        Boolean.class);
        Param patchFlag = patch.getParameters()[6].getAnnotation(Param.class);

        assertThat(writeFlag.name()).isEqualTo("cross_profile");
        assertThat(writeFlag.required()).isFalse();
        assertThat(writeFlag.defaultValue()).isEqualTo("false");
        assertThat(patchFlag.name()).isEqualTo("cross_profile");
        assertThat(patchFlag.required()).isFalse();
        assertThat(patchFlag.defaultValue()).isEqualTo("false");
    }

    /** 跨 Profile 写入含波浪号路径时默认拒绝，显式 true 后才允许 write_file 与 patch。 */
    @Test
    void shouldSoftBlockTildeCrossProfileWritesUntilExplicitlyAllowed() throws Exception {
        Path home = Files.createTempDirectory("tool-contract-profile-home");
        Path profileRoot = home.resolve(".solonclaw");
        Path target = profileRoot.resolve("profiles/work/skills/note.txt");
        Files.createDirectories(target.getParent());
        String previousHome = System.getProperty("user.home");
        String previousRoot = System.getProperty("solonclaw.profile.root");
        String previousProfile = System.getProperty("solonclaw.profile.name");
        System.setProperty("user.home", home.toString());
        System.setProperty("solonclaw.profile.root", profileRoot.toString());
        System.setProperty("solonclaw.profile.name", "default");
        try {
            String tildePath = "~/.solonclaw/profiles/work/skills/note.txt";
            SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());
            SolonClawFileReadWriteSkill fileTools =
                    new SolonClawFileReadWriteSkill(profileRoot.toString(), policy);

            ONode blockedWrite = ONode.ofJson(fileTools.writeFile(tildePath, "before", null));
            assertThat(blockedWrite.get("status").getString()).isEqualTo("error");
            assertThat(blockedWrite.get("error").getString()).contains("cross_profile=true");
            assertThat(target).doesNotExist();

            assertThatThrownBy(() -> fileTools.writeFile(tildePath, "before", Boolean.TRUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("APPROVAL_REQUIRED");
            SecurityPolicyService.approveFilePolicyForCurrentThread(
                    "workspace_outside_write", tildePath);
            ONode allowedWrite =
                    ONode.ofJson(fileTools.writeFile(tildePath, "before", Boolean.TRUE));
            assertThat(allowedWrite.get("status").getString()).isEqualTo("success");
            assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8))
                    .isEqualTo("before");

            SolonClawPatchTools patchTools =
                    new SolonClawPatchTools(profileRoot.toString(), policy);
            ONode blockedPatch =
                    ONode.ofJson(
                            patchTools.patch(
                                    "replace",
                                    tildePath,
                                    "before",
                                    "after",
                                    Boolean.FALSE,
                                    null,
                                    null));
            assertThat(blockedPatch.get("status").getString()).isEqualTo("error");
            assertThat(blockedPatch.get("error").getString()).contains("cross_profile=true");

            ONode pendingPatch =
                    ONode.ofJson(
                            patchTools.patch(
                                    "replace",
                                    tildePath,
                                    "before",
                                    "after",
                                    Boolean.FALSE,
                                    null,
                                    Boolean.TRUE));
            assertThat(pendingPatch.get("status").getString()).isEqualTo("error");
            assertThat(pendingPatch.get("error").getString()).contains("APPROVAL_REQUIRED");

            SecurityPolicyService.approveFilePolicyForCurrentThread(
                    "workspace_outside_write", tildePath);
            ONode allowedPatch =
                    ONode.ofJson(
                            patchTools.patch(
                                    "replace",
                                    tildePath,
                                    "before",
                                    "after",
                                    Boolean.FALSE,
                                    null,
                                    Boolean.TRUE));
            assertThat(allowedPatch.get("status").getString()).isEqualTo("success");
            assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8))
                    .isEqualTo("after");
        } finally {
            SecurityPolicyService.clearCurrentThreadPolicyApprovals();
            restoreProperty("user.home", previousHome);
            restoreProperty("solonclaw.profile.root", previousRoot);
            restoreProperty("solonclaw.profile.name", previousProfile);
        }
    }

    /** cross_profile=true 仅解除兄弟 Profile 软保护，不能绕过凭据文件硬策略。 */
    @Test
    void shouldKeepCredentialPolicyWhenCrossProfileIsExplicitlyAllowed() throws Exception {
        Path home = Files.createTempDirectory("tool-contract-profile-policy-home");
        Path profileRoot = home.resolve(".solonclaw");
        Path skillsRoot = profileRoot.resolve("profiles/work/skills");
        Path credentialTarget = skillsRoot.resolve("credentials.json");
        Path safeTarget = skillsRoot.resolve("note.txt");
        Files.createDirectories(skillsRoot);
        Files.write(credentialTarget, Collections.singletonList("before"), StandardCharsets.UTF_8);
        String previousHome = System.getProperty("user.home");
        String previousRoot = System.getProperty("solonclaw.profile.root");
        String previousProfile = System.getProperty("solonclaw.profile.name");
        System.setProperty("user.home", home.toString());
        System.setProperty("solonclaw.profile.root", profileRoot.toString());
        System.setProperty("solonclaw.profile.name", "default");
        try {
            AppConfig appConfig = new AppConfig();
            appConfig.getRuntime().setHome(profileRoot.toString());
            SecurityPolicyService policy = new SecurityPolicyService(appConfig);
            SolonClawFileReadWriteSkill fileTools =
                    new SolonClawFileReadWriteSkill(profileRoot.toString(), policy);
            SolonClawPatchTools patchTools =
                    new SolonClawPatchTools(profileRoot.toString(), policy);
            String safePath = "~/.solonclaw/profiles/work/skills/note.txt";
            String credentialPath = "~/.solonclaw/profiles/work/skills/credentials.json";

            SecurityPolicyService.approveFilePolicyForCurrentThread(
                    "workspace_outside_write", safePath);
            ONode safeWrite = ONode.ofJson(fileTools.writeFile(safePath, "allowed", Boolean.TRUE));
            assertThat(safeWrite.get("status").getString()).isEqualTo("success");
            assertThat(new String(Files.readAllBytes(safeTarget), StandardCharsets.UTF_8))
                    .isEqualTo("allowed");

            assertThatThrownBy(() -> fileTools.writeFile(credentialPath, "after", Boolean.TRUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BLOCKED")
                    .hasMessageContaining("凭据")
                    .hasMessageNotContaining("credentials.json");

            ONode blockedPatch =
                    ONode.ofJson(
                            patchTools.patch(
                                    "replace",
                                    credentialPath,
                                    "before",
                                    "after",
                                    Boolean.FALSE,
                                    null,
                                    Boolean.TRUE));
            assertThat(blockedPatch.get("status").getString()).isEqualTo("error");
            assertThat(blockedPatch.get("error").getString())
                    .contains("BLOCKED")
                    .contains("凭据")
                    .doesNotContain("credentials.json");
            assertThat(new String(Files.readAllBytes(credentialTarget), StandardCharsets.UTF_8))
                    .contains("before")
                    .doesNotContain("after");
        } finally {
            SecurityPolicyService.clearCurrentThreadPolicyApprovals();
            restoreProperty("user.home", previousHome);
            restoreProperty("solonclaw.profile.root", previousRoot);
            restoreProperty("solonclaw.profile.name", previousProfile);
        }
    }

    /** 文件写入和 patch 都必须把 approval_required verdict 转成可消费错误。 */
    @Test
    void shouldHonorApprovalRequiredFileVerdicts() throws Exception {
        Path root = Files.createTempDirectory("tool-contract-policy-approval");
        Path target = root.resolve("note.txt");
        Files.write(target, Collections.singletonList("before"), StandardCharsets.UTF_8);
        SecurityPolicyService approvalPolicy =
                new SecurityPolicyService(new AppConfig()) {
                    /** 文件工具统一返回需要审批判定。 */
                    @Override
                    public FileVerdict checkFileToolArgs(
                            String toolName, Map<String, Object> args) {
                        return FileVerdict.approvalRequired("note.txt", "test-policy", "测试路径需要审批");
                    }

                    /** patch 路径统一返回需要审批判定。 */
                    @Override
                    public FileVerdict checkPath(String rawPath, boolean writeLike) {
                        return FileVerdict.approvalRequired("note.txt", "test-policy", "测试路径需要审批");
                    }
                };
        SolonClawFileReadWriteSkill fileTools =
                new SolonClawFileReadWriteSkill(root.toString(), approvalPolicy);
        SolonClawPatchTools patchTools = new SolonClawPatchTools(root.toString(), approvalPolicy);

        assertThatThrownBy(() -> fileTools.writeFile("note.txt", "after", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVAL_REQUIRED")
                .hasMessageContaining("测试路径需要审批");
        ONode patchResult =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "note.txt",
                                "before",
                                "after",
                                Boolean.FALSE,
                                null,
                                null));
        assertThat(patchResult.get("status").getString()).isEqualTo("error");
        assertThat(patchResult.get("error").getString())
                .contains("APPROVAL_REQUIRED")
                .contains("测试路径需要审批");
        assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8))
                .contains("before")
                .doesNotContain("after");
    }

    /** search_files 应使用真实 glob，并让 files_only/count 返回各自的稳定结构。 */
    @Test
    void shouldApplyRealGlobAndOutputModes() throws Exception {
        Path root = Files.createTempDirectory("tool-contract-search");
        Files.createDirectories(root.resolve("src"));
        Files.write(
                root.resolve("src/Main.java"),
                Arrays.asList("needle", "middle", "needle"),
                StandardCharsets.UTF_8);
        Files.write(
                root.resolve("src/Main.kt"),
                Collections.singletonList("needle"),
                StandardCharsets.UTF_8);

        SolonClawFileReadWriteSkill globSkill =
                new SolonClawFileReadWriteSkill(root.toString(), null);
        ONode glob =
                ONode.ofJson(
                        globSkill.searchFiles("*.java", "files", ".", null, 20, 0, "content", 0));
        SolonClawFileReadWriteSkill filesOnlySkill =
                new SolonClawFileReadWriteSkill(root.toString(), null);
        ONode filesOnly =
                ONode.ofJson(
                        filesOnlySkill.searchFiles(
                                "needle", "content", ".", "*.java", 20, 0, "files_only", 0));
        SolonClawFileReadWriteSkill countSkill =
                new SolonClawFileReadWriteSkill(root.toString(), null);
        ONode count =
                ONode.ofJson(
                        countSkill.searchFiles(
                                "needle", "content", ".", "*.java", 20, 0, "count", 0));

        assertThat(glob.get("matches").size()).isEqualTo(1);
        assertThat(glob.get("matches").get(0).get("path").getString()).endsWith("Main.java");
        assertThat(filesOnly.get("matches").size()).isEqualTo(1);
        assertThat(filesOnly.get("matches").get(0).hasKey("text")).isFalse();
        assertThat(count.get("matches").size()).isEqualTo(1);
        assertThat(count.get("matches").get(0).get("count").getInt()).isEqualTo(2);
        assertThat(count.get("match_count").getInt()).isEqualTo(2);
    }

    /** web_extract 应接受字符串或搜索结果对象，保持原顺序并逐项返回错误。 */
    @Test
    void shouldExtractUrlsInOrderWithPerItemErrors() throws Exception {
        String longContent = new String(new char[2600]).replace('\0', 'x');
        SolonClawWebTools.SafeWebExtractTool tool =
                new SolonClawWebTools.SafeWebExtractTool(
                        null,
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return url.endsWith("empty") ? "" : longContent;
                            }
                        });
        Map<String, Object> href = new LinkedHashMap<String, Object>();
        href.put("href", "https://example.com/empty");
        List<Object> urls = new ArrayList<Object>();
        urls.add("https://example.com/long");
        urls.add(href);
        urls.add(Collections.singletonMap("title", "missing url"));

        ONode result = ONode.ofJson(tool.webExtract(urls, "markdown", Integer.valueOf(2000)));

        assertThat(result.get("results").size()).isEqualTo(3);
        assertThat(result.get("results").get(0).get("url").getString())
                .isEqualTo("https://example.com/long");
        assertThat(result.get("results").get(0).get("content").getString())
                .contains("[content truncated]");
        assertThat(result.get("results").get(1).get("url").getString())
                .isEqualTo("https://example.com/empty");
        assertThat(result.get("results").get(1).get("error").getString()).isNotBlank();
        assertThat(result.get("results").get(2).get("error").getString()).contains("url");
    }

    /** web_extract 截断正文时必须保存完整正文，供模型用文件工具继续分段读取。 */
    @Test
    void shouldPersistFullWebExtractContentWhenPreviewIsTruncated() throws Exception {
        Path workspace = Files.createTempDirectory("web-extract-workspace");
        String middle = "MIDDLE-CONTENT-MUST-REMAIN-READABLE";
        String longContent =
                new String(new char[1200]).replace('\0', 'a')
                        + middle
                        + new String(new char[1200]).replace('\0', 'z');
        SolonClawWebTools.SafeWebExtractTool tool =
                new SolonClawWebTools.SafeWebExtractTool(
                        null,
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return longContent;
                            }
                        },
                        workspace.toString());

        ONode result =
                ONode.ofJson(
                        tool.webExtract(
                                Collections.<Object>singletonList("https://example.com/long"),
                                "markdown",
                                Integer.valueOf(2000)));
        ONode item = result.get("results").get(0);
        String contentPath = item.get("content_path").getString();

        assertThat(item.get("content").getString()).contains("content truncated", contentPath);
        assertThat(item.get("hint").getString()).contains("read_file", contentPath);
        assertThat(item.get("content_truncated").getBoolean()).isTrue();
        assertThat(Files.readString(workspace.resolve(contentPath), StandardCharsets.UTF_8))
                .isEqualTo(longContent)
                .contains(middle);
        ONode reread =
                ONode.ofJson(
                        new SolonClawFileReadWriteSkill(workspace.toString(), null)
                                .readFile(contentPath, Integer.valueOf(1), Integer.valueOf(1)));
        assertThat(reread.get("content").getString()).contains(middle);
    }

    /** 网页正文中的失效、畸形或私网引用链接不能让已抓取的正文整体失败。 */
    @Test
    void shouldKeepFetchedContentWhenBodyContainsReferenceLinks() throws Exception {
        SolonClawWebTools.SafeWebfetchTool tool =
                new SolonClawWebTools.SafeWebfetchTool(
                        new SecurityPolicyService(new AppConfig()),
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return "正文引用 https://without-a-comma.example.com/path、"
                                        + "https://www.iana.org/assignments/http-fields]. 和"
                                        + " http://169.254.169.254/latest/meta-data 仍应可读";
                            }
                        });

        assertThat(tool.webfetch("https://example.com", "text", null).getContent())
                .contains("仍应可读");
    }

    /** web_extract 超出五项时必须整体拒绝，不能静默丢弃尾部输入。 */
    @Test
    void shouldRejectWebExtractInputBeyondFiveItems() {
        SolonClawWebTools.SafeWebExtractTool tool =
                new SolonClawWebTools.SafeWebExtractTool(null, new WebfetchTalent());
        List<Object> urls =
                Arrays.<Object>asList(
                        "https://example.com/1",
                        "https://example.com/2",
                        "https://example.com/3",
                        "https://example.com/4",
                        "https://example.com/5",
                        "https://example.com/6");

        ONode result = ONode.ofJson(tool.webExtract(urls, "markdown", Integer.valueOf(2000)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("at most 5");
        assertThat(result.hasKey("results")).isFalse();
    }

    /** web_extract 的 char_limit 小于 2000 时必须明确拒绝，不能静默改写模型参数。 */
    @Test
    void shouldRejectWebExtractCharacterLimitBelowMinimum() {
        SolonClawWebTools.SafeWebExtractTool tool =
                new SolonClawWebTools.SafeWebExtractTool(null, new WebfetchTalent());

        ONode result =
                ONode.ofJson(
                        tool.webExtract(
                                Collections.<Object>singletonList("https://example.com"),
                                "markdown",
                                Integer.valueOf(1999)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("at least 2000");
    }

    /** session_search 应把排序、窗口、角色和 Profile 参数原样下沉到查询对象。 */
    @Test
    void shouldPassSessionSearchContractFields() throws Exception {
        RecordingSessionSearchService service = new RecordingSessionSearchService();
        SessionSearchTools tools = new SessionSearchTools(service, "MEMORY:room:user");

        tools.sessionSearch(
                "topic",
                Integer.valueOf(7),
                "oldest",
                "session-1",
                "message-2",
                Integer.valueOf(12),
                "user,tool",
                "work");

        assertThat(service.query.getLimit()).isEqualTo(7);
        assertThat(service.query.getSort()).isEqualTo("oldest");
        assertThat(service.query.getSessionId()).isEqualTo("session-1");
        assertThat(service.query.getAroundMessageId()).isEqualTo("message-2");
        assertThat(service.query.getWindow()).isEqualTo(12);
        assertThat(service.query.getRoleFilter()).isEqualTo("user,tool");
        assertThat(service.query.getProfile()).isEqualTo("work");
    }

    /** delegate_task 应直接接收结构化 tasks，并传播顶层或逐项模型。 */
    @Test
    void shouldDelegateStructuredTasksAndModels() throws Exception {
        RecordingDelegationService service = new RecordingDelegationService();
        DelegateTools tools = new DelegateTools(service, "MEMORY:room:user");
        DelegateTools.DelegateTaskInput first = new DelegateTools.DelegateTaskInput();
        first.setGoal("first");
        first.setContext("ctx-1");
        first.setModel("model-a");
        first.setAllowedTools(java.util.Collections.<String>emptyList());
        DelegateTools.DelegateTaskInput second = new DelegateTools.DelegateTaskInput();
        second.setGoal("second");

        tools.delegateTask(
                null,
                "shared",
                Arrays.asList(first, second),
                "model-main",
                null,
                java.util.Collections.<String>emptyList(),
                Boolean.TRUE);

        assertThat(service.batchTasks).hasSize(2);
        assertThat(service.batchTasks.get(0).getPrompt()).isEqualTo("first");
        assertThat(service.batchTasks.get(0).getContext()).isEqualTo("ctx-1");
        assertThat(service.batchTasks.get(0).getModel()).isEqualTo("model-a");
        assertThat(service.batchTasks.get(1).getContext()).isEqualTo("shared");
        assertThat(service.batchTasks.get(1).getModel()).isEqualTo("model-main");
    }

    /** call_tool 的 tool_args 缺失或非对象时必须在包装边界拒绝，不能执行目标工具。 */
    @Test
    void shouldRejectNonObjectGatewayToolArgsBeforeExecution() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        FunctionToolDesc raw = new FunctionToolDesc("call_tool");
        raw.inputSchema(
                "{\"type\":\"object\",\"properties\":{\"tool_name\":{\"type\":\"string\"},\"tool_args\":{\"type\":\"object\"}}}");
        raw.doHandle(
                args -> {
                    calls.incrementAndGet();
                    return "executed";
                });
        FunctionTool wrapped = SanitizedFunctionTool.wrap(raw);
        List<Object> invalidValues =
                Arrays.<Object>asList(
                        null, "not-an-object", Arrays.asList("array"), Integer.valueOf(7));

        for (Object invalidValue : invalidValues) {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("tool_name", "terminal");
            if (invalidValue != null) {
                args.put("tool_args", invalidValue);
            }
            ToolResult callResult = wrapped.call(args);
            Object handleResult = wrapped.handle(args);

            assertThat(callResult.isError()).isTrue();
            assertThat(callResult.getContent()).contains("tool_args").contains("JSON 对象");
            assertThat(handleResult).isInstanceOf(ToolResult.class);
            assertThat(((ToolResult) handleResult).isError()).isTrue();
        }
        assertThat(calls.get()).isZero();

        Map<String, Object> valid = new LinkedHashMap<String, Object>();
        valid.put("tool_name", "terminal");
        valid.put("tool_args", Collections.singletonMap("command", "pwd"));
        assertThat(wrapped.call(valid).isError()).isFalse();
        assertThat(wrapped.handle(valid)).isEqualTo("executed");
        assertThat(calls.get()).isEqualTo(2);
    }

    /** 动态工具首次占名后，后续大小写等价冲突不得覆盖先注册实现。 */
    @Test
    void shouldKeepFirstDynamicToolOnNameConflict() {
        FunctionToolDesc first = new FunctionToolDesc("mcp_docs_read");
        first.doHandle(args -> "first");
        FunctionToolDesc duplicate = new FunctionToolDesc("MCP_DOCS_READ");
        duplicate.doHandle(args -> "duplicate");
        List<Object> target = new ArrayList<Object>();
        LinkedHashSet<String> occupied = new LinkedHashSet<String>();

        DefaultToolRegistry.addUniqueDynamicTools(
                target, Arrays.<FunctionTool>asList(first, duplicate), occupied);

        assertThat(target).containsExactly(first);
        assertThat(occupied).containsExactly("mcp_docs_read");
    }

    /** 动态工具不得占用内置实际函数名或渐进披露网关保留名。 */
    @Test
    void shouldRejectDynamicToolsThatConflictWithReservedNames() {
        FunctionToolDesc builtinConflict = new FunctionToolDesc("READ_FILE");
        builtinConflict.doHandle(args -> "builtin-conflict");
        FunctionToolDesc gatewayConflict = new FunctionToolDesc("CALL_TOOL");
        gatewayConflict.doHandle(args -> "gateway-conflict");
        FunctionToolDesc accepted = new FunctionToolDesc("mcp_docs_search");
        accepted.doHandle(args -> "accepted");
        List<Object> target = new ArrayList<Object>();
        LinkedHashSet<String> occupied =
                new LinkedHashSet<String>(Arrays.asList("read_file", "call_tool"));

        DefaultToolRegistry.addUniqueDynamicTools(
                target,
                Arrays.<FunctionTool>asList(builtinConflict, gatewayConflict, accepted),
                occupied);

        assertThat(target).containsExactly(accepted);
        assertThat(occupied).containsExactly("read_file", "call_tool", "mcp_docs_search");
    }

    /** 多函数 Bean 必须只暴露选择器明确启用的函数，不能把同 Bean 的其他函数一并带出。 */
    @Test
    void shouldFilterMultiFunctionBeansAtSelectorGranularity() throws Exception {
        Path root = Files.createTempDirectory("tool-contract-bean-filter");
        Talent fileTalent =
                DefaultToolRegistry.filteredTalent(
                        new SolonClawFileReadWriteSkill(root.toString(), null),
                        Collections.singleton("read_file"));
        Talent shellTalent =
                DefaultToolRegistry.filteredTalent(
                        new SolonClawShellSkill(root.toString(), null),
                        Collections.singleton("terminal"));
        ToolProvider mediaProvider =
                DefaultToolRegistry.filteredMethodToolProvider(
                        new MediaSpeechTools(null, null), Collections.singleton("image_generate"));
        ToolProvider configProvider =
                DefaultToolRegistry.filteredMethodToolProvider(
                        new ConfigTools.ConfigGetTool(null), Collections.singleton("config_get"));

        assertThat(toolNames(fileTalent.getTools(Prompt.of("")))).containsExactly("read_file");
        assertThat(toolNames(shellTalent.getTools(Prompt.of("")))).containsExactly("terminal");
        assertThat(toolNames(mediaProvider.getTools())).containsExactly("image_generate");
        assertThat(toolNames(configProvider.getTools()))
                .containsExactly("config_get")
                .doesNotContain("config_env_probe");
    }

    /**
     * 提取函数工具名，便于验证过滤后模型可见的实际契约。
     *
     * @param tools 函数工具集合。
     * @return 保持提供器声明顺序的函数名列表。
     */
    private static List<String> toolNames(Collection<FunctionTool> tools) {
        List<String> names = new ArrayList<String>();
        if (tools == null) {
            return names;
        }
        for (FunctionTool tool : tools) {
            if (tool != null) {
                names.add(tool.name());
            }
        }
        return names;
    }

    /** 记录 session_search 下沉后的完整查询对象。 */
    private static final class RecordingSessionSearchService implements SessionSearchService {
        /** 最近一次查询。 */
        private SessionSearchQuery query;

        /** 旧接口不参与本测试。 */
        @Override
        public List<SessionSearchEntry> search(String sourceKey, String query, int limit) {
            return Collections.emptyList();
        }

        /** 保存结构化查询，验证工具层参数没有丢失。 */
        @Override
        public List<SessionSearchEntry> search(SessionSearchQuery query) {
            this.query = query;
            return Collections.emptyList();
        }
    }

    /** 恢复测试前的系统属性值。 */
    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    /** 记录 delegate_task 转换后的领域任务。 */
    private static final class RecordingDelegationService implements DelegationService {
        /** 最近一次批量任务。 */
        private List<DelegationTask> batchTasks = Collections.emptyList();

        /** 旧单任务接口不参与本测试。 */
        @Override
        public DelegationResult delegateSingle(String sourceKey, String prompt, String context) {
            return new DelegationResult();
        }

        /** 记录结构化批量任务。 */
        @Override
        public List<DelegationResult> delegateBatch(String sourceKey, List<DelegationTask> tasks) {
            this.batchTasks = tasks;
            return Collections.emptyList();
        }
    }
}
