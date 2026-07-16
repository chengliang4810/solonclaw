package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.media.VisionAnalysisService;
import com.jimuqu.solon.claw.provider.BrowserProvider;
import com.jimuqu.solon.claw.provider.CdpBrowserProvider;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.BrowserTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

public class BrowserRuntimeServiceTests {
    @TempDir Path tempDir;

    @Test
    void shouldReturnUnavailableWhenNoProviderCanCreateSession() {
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        new AppConfig(),
                        Collections.<BrowserProvider>emptyList(),
                        new SecurityPolicyService(new AppConfig()));

        BrowserRuntimeService.BrowserResult result = service.create("task-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError().getCode()).isEqualTo("browser_unavailable");
    }

    @Test
    void shouldBlockPrivateAndMetadataNavigationTargets() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        config,
                        Collections.<BrowserProvider>singletonList(provider),
                        new FixedDnsSecurityPolicyService(config, "127.0.0.1"));

        BrowserRuntimeService.BrowserResult privateResult =
                service.navigate("missing", "https://internal.example/admin", 1);
        BrowserRuntimeService.BrowserResult metadataResult =
                service.navigate("missing", "http://169.254.169.254/latest/meta-data/", 1);

        assertThat(privateResult.isSuccess()).isFalse();
        assertThat(privateResult.getError().getCode()).isEqualTo("security_blocked");
        assertThat(metadataResult.isSuccess()).isFalse();
        assertThat(metadataResult.getError().getCode()).isEqualTo("security_blocked");
        assertThat(provider.closeCount.get()).isZero();
    }

    @Test
    void shouldReleaseLeaseOnCloseAndShutdown() {
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        new AppConfig(),
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(new AppConfig()),
                        2);

        BrowserRuntimeService.BrowserResult first = service.create("task-1");
        BrowserRuntimeService.BrowserResult second = service.create("task-2");

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(service.activeLeaseCount()).isEqualTo(2);

        BrowserRuntimeService.BrowserResult closeResult = service.close(first.getSessionId());

        assertThat(closeResult.isSuccess()).isTrue();
        assertThat(service.activeLeaseCount()).isEqualTo(1);
        assertThat(provider.closeCount.get()).isEqualTo(1);

        service.shutdown();

        assertThat(service.activeLeaseCount()).isZero();
        assertThat(provider.closeCount.get()).isEqualTo(2);
    }

    @Test
    void shouldDelegateBrowserActionsToProvider() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        config.normalizePaths();
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        config,
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(config),
                        1);
        BrowserRuntimeService.BrowserResult created = service.create("task-1");

        BrowserRuntimeService.BrowserResult navigated =
                service.navigate(created.getSessionId(), "http://127.0.0.1/start", 7);
        BrowserRuntimeService.BrowserResult clicked =
                service.click(created.getSessionId(), "#submit", 8);
        BrowserRuntimeService.BrowserResult typed =
                service.type(created.getSessionId(), "#name", "alice", 9);
        BrowserRuntimeService.BrowserResult screenshot =
                service.screenshot(created.getSessionId(), "page.png", true);
        BrowserRuntimeService.BrowserResult extracted =
                service.extract(created.getSessionId(), "main", "text");

        assertThat(navigated.isSuccess()).isTrue();
        assertThat(clicked.isSuccess()).isTrue();
        assertThat(typed.isSuccess()).isTrue();
        assertThat(screenshot.isSuccess()).isTrue();
        assertThat(extracted.isSuccess()).isTrue();
        assertThat(provider.navigateCount.get()).isEqualTo(1);
        assertThat(provider.clickCount.get()).isEqualTo(1);
        assertThat(provider.typeCount.get()).isEqualTo(1);
        assertThat(provider.screenshotCount.get()).isEqualTo(1);
        assertThat(provider.extractCount.get()).isEqualTo(1);
        assertThat(navigated.getDetails().get("providerAction")).isEqualTo("navigate");
        assertThat(clicked.getDetails().get("selector")).isEqualTo("#submit");
        assertThat(typed.getDetails().toString()).doesNotContain("alice");
        assertThat(screenshot.getDetails().get("path")).isEqualTo("page.png");
        assertThat(extracted.getDetails().get("content")).isEqualTo("provider text");
    }

    @Test
    void shouldCreateNavigateAndSnapshotWhenToolSessionIsOmitted() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        config,
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(config),
                        1);

        BrowserRuntimeService.BrowserResult result =
                new BrowserTools(service).navigate(null, "http://127.0.0.1/start", 7);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSessionId()).isNotBlank();
        assertThat(result.getDetails()).containsEntry("autoCreated", Boolean.TRUE);
        assertThat(result.getDetails().get("snapshot")).isEqualTo("page snapshot");
        assertThat(result.getDetails().get("refs").toString()).contains("@e1");
        assertThat(provider.createCount.get()).isEqualTo(1);
        assertThat(provider.navigateCount.get()).isEqualTo(1);
        assertThat(provider.snapshotCount.get()).isEqualTo(1);
    }

    @Test
    void shouldReturnSnapshotWhenToolNavigatesExistingSession() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        config,
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(config),
                        1);
        String sessionId = service.create("task-1").getSessionId();

        BrowserRuntimeService.BrowserResult result =
                new BrowserTools(service).navigate(sessionId, "http://127.0.0.1/start", 7);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSessionId()).isEqualTo(sessionId);
        assertThat(result.getDetails()).doesNotContainKey("autoCreated");
        assertThat(result.getDetails().get("snapshot")).isEqualTo("page snapshot");
        assertThat(result.getDetails().get("refs").toString()).contains("@e1");
        assertThat(provider.createCount.get()).isEqualTo(1);
        assertThat(provider.navigateCount.get()).isEqualTo(1);
        assertThat(provider.snapshotCount.get()).isEqualTo(1);
        assertThat(service.activeLeaseCount()).isEqualTo(1);
    }

    @Test
    void shouldExecuteProductionBrowserActionsThroughCdp() throws Exception {
        try (FakeCdpServer server = new FakeCdpServer(false)) {
            CdpTestProvider provider = new CdpTestProvider(server.connectUrl());
            AppConfig config = new AppConfig();
            config.normalizePaths();
            BrowserRuntimeService service =
                    new BrowserRuntimeService(
                            config,
                            Collections.<BrowserProvider>singletonList(provider),
                            new SecurityPolicyService(config),
                            1);
            BrowserRuntimeService.BrowserResult created = service.create("task-cdp");
            Path screenshotPath = tempDir.resolve("browser-cdp.png");

            BrowserRuntimeService.BrowserResult navigated =
                    service.navigate(created.getSessionId(), "https://93.184.216.34/start", 3);
            BrowserRuntimeService.BrowserResult clicked =
                    service.click(created.getSessionId(), "#submit", 3);
            BrowserRuntimeService.BrowserResult typed =
                    service.type(created.getSessionId(), "#name", "secret-input-value", 3);
            BrowserRuntimeService.BrowserResult screenshot =
                    service.screenshot(created.getSessionId(), screenshotPath.toString(), true);
            BrowserRuntimeService.BrowserResult extracted =
                    service.extract(created.getSessionId(), "main", "text");

            assertThat(navigated.isSuccess()).isTrue();
            assertThat(clicked.isSuccess()).isTrue();
            assertThat(typed.isSuccess()).isTrue();
            assertThat(screenshot.isSuccess()).isTrue();
            assertThat(extracted.isSuccess()).isTrue();
            assertThat(Files.readAllBytes(screenshotPath)).containsExactly(1, 2, 3, 4);
            assertThat(extracted.getDetails().get("content")).isEqualTo("provider text");
            assertThat(typed.getDetails().toString()).doesNotContain("secret-input-value");
            assertThat(server.methods())
                    .contains(
                            "Target.getTargets",
                            "Target.attachToTarget",
                            "Page.navigate",
                            "Runtime.evaluate",
                            "Page.captureScreenshot");

            BrowserRuntimeService.BrowserResult closed = service.close(created.getSessionId());
            assertThat(closed.isSuccess()).isTrue();
            assertThat(provider.closeCount.get()).isEqualTo(1);
            server.assertHealthy();
        }
    }

    @Test
    void shouldExecuteExtendedBrowserActionsThroughCdp() throws Exception {
        try (FakeCdpServer server = new FakeCdpServer(false)) {
            CdpTestProvider provider = new CdpTestProvider(server.connectUrl());
            AppConfig config = new AppConfig();
            config.normalizePaths();
            BrowserRuntimeService service =
                    new BrowserRuntimeService(
                            config,
                            Collections.<BrowserProvider>singletonList(provider),
                            new SecurityPolicyService(config),
                            1);
            BrowserRuntimeService.BrowserResult created = service.create("task-cdp-extended");
            BrowserRuntimeService.BrowserResult navigated =
                    service.navigate(created.getSessionId(), "https://93.184.216.34/current", 3);

            BrowserRuntimeService.BrowserResult snapshot =
                    service.snapshot(created.getSessionId(), true);
            BrowserRuntimeService.BrowserResult clicked =
                    service.click(created.getSessionId(), "@e1", 3);
            BrowserRuntimeService.BrowserResult typed =
                    service.type(created.getSessionId(), "@e2", "extended-secret-input", 3);
            BrowserRuntimeService.BrowserResult scrolled =
                    service.scroll(created.getSessionId(), "down", 700);
            BrowserRuntimeService.BrowserResult pressed =
                    service.press(created.getSessionId(), "Enter", 3);
            BrowserRuntimeService.BrowserResult images = service.getImages(created.getSessionId());
            BrowserRuntimeService.BrowserResult console =
                    service.console(created.getSessionId(), false, null, 3);
            BrowserRuntimeService.BrowserResult evaluated =
                    service.console(created.getSessionId(), false, "window.__testState", 3);
            BrowserRuntimeService.BrowserResult cdp =
                    service.cdp(
                            created.getSessionId(),
                            "Browser.getVersion",
                            Collections.<String, Object>emptyMap(),
                            null,
                            3);
            BrowserRuntimeService.BrowserResult back = service.back(created.getSessionId(), 3);
            BrowserRuntimeService.BrowserResult dialog =
                    service.dialog(
                            created.getSessionId(), "accept", "dialog-secret-input", "dialog-1", 3);

            assertThat(navigated.isSuccess()).isTrue();
            assertThat(snapshot.isSuccess()).isTrue();
            assertThat(snapshot.getDetails().get("refs").toString())
                    .contains("@e1")
                    .contains("@e2");
            assertThat(String.valueOf(snapshot.getDetails().get("snapshot")))
                    .startsWith("snapshot-start")
                    .contains("[truncated")
                    .doesNotContain("snapshot-secret-token");
            assertThat(snapshot.getDetails().get("pendingDialogs").toString())
                    .contains("dialog-1")
                    .contains("Confirm action");
            assertThat(clicked.isSuccess()).isTrue();
            assertThat(typed.isSuccess()).isTrue();
            assertThat(typed.getDetails().toString()).doesNotContain("extended-secret-input");
            assertThat(scrolled.isSuccess()).isTrue();
            assertThat(scrolled.getDetails().get("scrollY")).isEqualTo(700);
            assertThat(pressed.isSuccess()).isTrue();
            assertThat(images.isSuccess()).isTrue();
            assertThat(images.getDetails().toString())
                    .contains("image.png?token=***")
                    .doesNotContain("image-secret-token")
                    .doesNotContain("sk-imageSecret123456");
            assertThat(console.isSuccess()).isTrue();
            assertThat(console.getDetails().toString())
                    .contains("console-message")
                    .doesNotContain("console-secret-token");
            assertThat(evaluated.isSuccess()).isTrue();
            assertThat(evaluated.getDetails().toString())
                    .contains("authorization=[redacted]")
                    .contains("token=***")
                    .doesNotContain("expression-secret-token");
            assertThat(cdp.isSuccess()).isTrue();
            assertThat(cdp.getDetails().toString())
                    .contains("Chrome/123")
                    .doesNotContain("cdp-secret-token")
                    .doesNotContain("user:secret");
            assertThat(back.isSuccess()).isTrue();
            assertThat(back.getDetails().get("currentUrl"))
                    .isEqualTo("https://93.184.216.34/previous");
            assertThat(dialog.isSuccess()).isTrue();
            assertThat(dialog.getDetails().toString()).doesNotContain("dialog-secret-input");
            assertThat(server.lastDialogParams().get("promptText"))
                    .isEqualTo("dialog-secret-input");
            assertThat(String.join("\n", server.expressions()))
                    .contains("data-solonclaw-ref=\\\"e1\\\"")
                    .contains("data-solonclaw-ref=\\\"e2\\\"");
            assertThat(server.methods())
                    .contains(
                            "Page.getNavigationHistory",
                            "Page.navigateToHistoryEntry",
                            "Input.dispatchKeyEvent",
                            "Browser.getVersion",
                            "Page.handleJavaScriptDialog");

            service.close(created.getSessionId());
            server.assertHealthy();
        }
    }

    @Test
    void shouldAnalyzeCapturedScreenshotWithoutInventingAnnotations() throws Exception {
        try (FakeCdpServer server = new FakeCdpServer(false)) {
            CdpTestProvider provider = new CdpTestProvider(server.connectUrl());
            AppConfig config = new AppConfig();
            config.getRuntime().setCacheDir(tempDir.toString());
            config.normalizePaths();
            AtomicReference<String> analyzedPath = new AtomicReference<String>();
            AtomicReference<String> analyzedQuestion = new AtomicReference<String>();
            BrowserRuntimeService service =
                    new BrowserRuntimeService(
                            config,
                            Collections.<BrowserProvider>singletonList(provider),
                            new SecurityPolicyService(config),
                            (imagePath, question) -> {
                                analyzedPath.set(imagePath);
                                analyzedQuestion.set(question);
                                return VisionAnalysisService.VisionAnalysisOutcome.ok(
                                        "页面布局正常",
                                        "vision-test",
                                        "vision-model",
                                        Collections.<String, Object>singletonMap(
                                                "totalTokens", Long.valueOf(15L)));
                            },
                            1);
            BrowserRuntimeService.BrowserResult created = service.create("task-cdp-vision");
            Path screenshotPath = tempDir.resolve("browser-vision.png");
            BrowserRuntimeService.BrowserResult navigated =
                    service.navigate(created.getSessionId(), "https://93.184.216.34/vision", 3);

            BrowserRuntimeService.BrowserResult result =
                    service.vision(
                            created.getSessionId(), "页面布局是否正常？", true, screenshotPath.toString());

            assertThat(navigated.isSuccess()).isTrue();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo("vision_analyzed");
            assertThat(result.getDetails().get("analysisAvailable")).isEqualTo(true);
            assertThat(result.getDetails().get("capability")).isEqualTo("vision_analysis");
            assertThat(result.getDetails().get("answer")).isEqualTo("页面布局正常");
            assertThat(result.getDetails().get("annotated")).isEqualTo(false);
            assertThat(result.getDetails().get("annotateRequested")).isEqualTo(true);
            assertThat(analyzedPath.get()).isEqualTo(screenshotPath.toString());
            assertThat(analyzedQuestion.get()).isEqualTo("页面布局是否正常？");
            assertThat(Files.readAllBytes(screenshotPath)).containsExactly(1, 2, 3, 4);
            service.close(created.getSessionId());
            server.assertHealthy();
        }
    }

    @Test
    void shouldBlockSensitiveConsoleAndCdpCallsBeforeWebSocketDispatch() throws Exception {
        try (FakeCdpServer server = new FakeCdpServer(false)) {
            CdpTestProvider provider = new CdpTestProvider(server.connectUrl());
            AppConfig config = new AppConfig();
            BrowserRuntimeService service =
                    new BrowserRuntimeService(
                            config,
                            Collections.<BrowserProvider>singletonList(provider),
                            new SecurityPolicyService(config),
                            1);
            BrowserRuntimeService.BrowserResult created = service.create("task-cdp-security");

            BrowserRuntimeService.BrowserResult console =
                    service.console(created.getSessionId(), false, "document.cookie", 3);
            BrowserRuntimeService.BrowserResult cookieCdp =
                    service.cdp(
                            created.getSessionId(),
                            "Network.getAllCookies",
                            Collections.<String, Object>emptyMap(),
                            null,
                            3);
            BrowserRuntimeService.BrowserResult evaluateCdp =
                    service.cdp(
                            created.getSessionId(),
                            "Runtime.evaluate",
                            Collections.<String, Object>singletonMap(
                                    "expression", "localStorage.getItem('token')"),
                            "page",
                            3);

            assertThat(console.isSuccess()).isFalse();
            assertThat(console.getError().getCode()).isEqualTo("security_blocked");
            assertThat(cookieCdp.isSuccess()).isFalse();
            assertThat(cookieCdp.getError().getCode()).isEqualTo("security_blocked");
            assertThat(evaluateCdp.isSuccess()).isFalse();
            assertThat(evaluateCdp.getError().getCode()).isEqualTo("security_blocked");
            assertThat(server.methods()).isEmpty();
            service.close(created.getSessionId());
            server.assertHealthy();
        }
    }

    @Test
    void shouldExposeExtendedBrowserToolContracts() {
        Set<String> mappings = new HashSet<String>();
        Method clickMethod = null;
        Method typeMethod = null;
        for (Method method : BrowserTools.class.getDeclaredMethods()) {
            ToolMapping mapping = method.getAnnotation(ToolMapping.class);
            if (mapping == null) {
                continue;
            }
            mappings.add(mapping.name());
            if ("browser_click".equals(mapping.name())) {
                clickMethod = method;
            } else if ("browser_type".equals(mapping.name())) {
                typeMethod = method;
            }
        }

        assertThat(mappings)
                .contains(
                        "browser_snapshot",
                        "browser_scroll",
                        "browser_back",
                        "browser_press",
                        "browser_get_images",
                        "browser_vision",
                        "browser_console",
                        "browser_cdp",
                        "browser_dialog");
        assertThat(toolParamNames(clickMethod)).contains("ref", "selector");
        assertThat(toolParamNames(typeMethod)).contains("ref", "selector", "text");
    }

    @Test
    void shouldReturnStableCdpFailureWithoutLeakingSessionOrSelector() throws Exception {
        try (FakeCdpServer server = new FakeCdpServer(true)) {
            CdpTestProvider provider = new CdpTestProvider(server.connectUrl());
            AppConfig config = new AppConfig();
            config.normalizePaths();
            BrowserRuntimeService service =
                    new BrowserRuntimeService(
                            config,
                            Collections.<BrowserProvider>singletonList(provider),
                            new SecurityPolicyService(config),
                            1);
            BrowserRuntimeService.BrowserResult created = service.create("task-cdp-failure");

            BrowserRuntimeService.BrowserResult clicked =
                    service.click(created.getSessionId(), "#private-selector", 3);

            assertThat(clicked.isSuccess()).isFalse();
            assertThat(clicked.getError().getCode()).isEqualTo("element_not_found");
            assertThat(clicked.getError().getMessage())
                    .doesNotContain("private-selector")
                    .doesNotContain("connect-secret");
            service.close(created.getSessionId());
            server.assertHealthy();
        }
    }

    @Test
    void shouldApplyScreenshotPathPolicyBeforeProviderWrite() {
        AppConfig config = new AppConfig();
        RecordingProvider provider = new RecordingProvider(true);
        SecurityPolicyService policy =
                new SecurityPolicyService(config) {
                    @Override
                    public FileVerdict checkPath(String rawPath, boolean writeLike) {
                        return FileVerdict.block(rawPath, "blocked screenshot path");
                    }
                };
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        config, Collections.<BrowserProvider>singletonList(provider), policy, 1);
        BrowserRuntimeService.BrowserResult created = service.create("task-path-policy");

        BrowserRuntimeService.BrowserResult screenshot =
                service.screenshot(created.getSessionId(), "blocked.png", false);

        assertThat(screenshot.isSuccess()).isFalse();
        assertThat(screenshot.getError().getCode()).isEqualTo("security_blocked");
        assertThat(provider.screenshotCount.get()).isZero();
    }

    @Test
    void shouldRefreshLeaseAfterSuccessfulBrowserAction() throws Exception {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        config,
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(config),
                        1);
        BrowserRuntimeService.BrowserResult created = service.create("task-1");
        long nearExpiry = System.currentTimeMillis() + 1_000L;
        setLeaseExpiry(service, created.getSessionId(), nearExpiry);

        BrowserRuntimeService.BrowserResult clicked =
                service.click(created.getSessionId(), "#submit", 8);

        assertThat(clicked.isSuccess()).isTrue();
        assertThat(readLeaseExpiry(service, created.getSessionId())).isGreaterThan(nearExpiry);
    }

    @Test
    void shouldRewriteLoopbackNavigationForContainerBrowserProviders() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        config.getSecurity().setRewriteBrowserLoopbackUrls(true);
        config.getSecurity().setBrowserLoopbackHostAlias("host.docker.internal");
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        config,
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(config),
                        1);
        BrowserRuntimeService.BrowserResult created = service.create("task-1");

        BrowserRuntimeService.BrowserResult navigated =
                service.navigate(created.getSessionId(), "http://127.0.0.1:8766/#settings", 7);

        assertThat(navigated.isSuccess()).isTrue();
        assertThat(provider.lastNavigatedUrl)
                .isEqualTo("http://host.docker.internal:8766/#settings");
        assertThat(navigated.getDetails().get("requestedUrl"))
                .isEqualTo("http://127.0.0.1:8766/#settings");
        assertThat(navigated.getDetails().get("url"))
                .isEqualTo("http://host.docker.internal:8766/#settings");
        assertThat(String.valueOf(navigated.getDetails().get("urlRewrite"))).contains("127.0.0.1");
        assertThat(String.valueOf(navigated.getDetails().get("urlRewrite")))
                .contains("host.docker.internal");
    }

    @Test
    void shouldBlockProviderReportedNavigationAfterClickOrType() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        RecordingProvider provider = new RecordingProvider(true);
        provider.nextUrl = "http://169.254.169.254/latest/meta-data/";
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        config,
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(config),
                        1);
        BrowserRuntimeService.BrowserResult created = service.create("task-1");

        BrowserRuntimeService.BrowserResult clicked =
                service.click(created.getSessionId(), "#metadata", 3);

        assertThat(clicked.isSuccess()).isFalse();
        assertThat(clicked.getError().getCode()).isEqualTo("security_blocked");
        assertThat(provider.navigateCount.get()).isEqualTo(1);
        assertThat(provider.lastNavigatedUrl).isEqualTo("about:blank");
        assertThat(service.activeLeaseCount()).isZero();
        assertThat(provider.closeCount.get()).isEqualTo(1);
    }

    @Test
    void shouldAvoidProviderAllocationWhenCapacityIsAlreadyExhausted() {
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        new AppConfig(),
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(new AppConfig()),
                        0);

        BrowserRuntimeService.BrowserResult result = service.create("task-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError().getCode()).isEqualTo("browser_busy");
        assertThat(provider.createCount.get()).isZero();
        assertThat(provider.closeCount.get()).isZero();
        assertThat(service.activeLeaseCount()).isZero();
    }

    @Test
    void shouldEnforceConcurrencyLimit() {
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        new AppConfig(),
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(new AppConfig()),
                        1);

        BrowserRuntimeService.BrowserResult first = service.create("task-1");
        BrowserRuntimeService.BrowserResult second = service.create("task-2");

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isFalse();
        assertThat(second.getError().getCode()).isEqualTo("browser_busy");
        assertThat(provider.createCount.get()).isEqualTo(1);
    }

    @Test
    void shouldReleaseExpiredLeaseBeforeCreatingNextBrowserSession() throws Exception {
        RecordingProvider provider = new RecordingProvider(true);
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        new AppConfig(),
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(new AppConfig()),
                        1);

        BrowserRuntimeService.BrowserResult first = service.create("task-1");
        expireLease(service, first.getSessionId());

        BrowserRuntimeService.BrowserResult second = service.create("task-2");

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(second.getSessionId()).isNotEqualTo(first.getSessionId());
        assertThat(provider.createCount.get()).isEqualTo(2);
        assertThat(provider.closeCount.get()).isEqualTo(1);
        assertThat(service.activeLeaseCount()).isEqualTo(1);
    }

    @Test
    void shouldRedactSessionOutput() {
        RecordingProvider provider =
                new RecordingProvider(
                        true, "ws://user:secret@example.com/devtools?token=sk-secretsecret");
        BrowserRuntimeService service =
                new BrowserRuntimeService(
                        new AppConfig(),
                        Collections.<BrowserProvider>singletonList(provider),
                        new SecurityPolicyService(new AppConfig()));

        BrowserRuntimeService.BrowserResult result = service.create("task-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDetails().toString()).doesNotContain("secretsecret");
        assertThat(result.getDetails().toString()).doesNotContain("user:secret");
        assertThat(result.getDetails().toString()).contains("***");
    }

    private static class CdpTestProvider extends CdpBrowserProvider {
        private final String connectUrl;
        private final AtomicInteger closeCount = new AtomicInteger();

        CdpTestProvider(String connectUrl) {
            this.connectUrl = connectUrl;
        }

        @Override
        public String name() {
            return "cdp-test";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public BrowserSession createSession(String taskId) {
            return registerCdpSession("provider-session", connectUrl);
        }

        @Override
        public void closeSession(String sessionId) {
            releaseCdpSession(sessionId);
            closeCount.incrementAndGet();
        }
    }

    private static class FakeCdpServer implements AutoCloseable {
        private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        private final boolean failClick;
        private final ServerSocket serverSocket;
        private final Thread serverThread;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private final List<String> methods = new CopyOnWriteArrayList<String>();
        private final List<String> expressions = new CopyOnWriteArrayList<String>();
        private final List<String> outboundEvents = new CopyOnWriteArrayList<String>();
        private final AtomicBoolean startupEventsSent = new AtomicBoolean();
        private final AtomicReference<Map<String, Object>> lastDialogParams =
                new AtomicReference<Map<String, Object>>(Collections.<String, Object>emptyMap());
        private volatile Socket clientSocket;
        private volatile String currentUrl = "about:blank";

        FakeCdpServer(boolean failClick) throws Exception {
            this.failClick = failClick;
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            this.serverThread = new Thread(this::serve, "fake-browser-cdp");
            this.serverThread.setDaemon(true);
            this.serverThread.start();
        }

        String connectUrl() {
            return "ws://127.0.0.1:"
                    + serverSocket.getLocalPort()
                    + "/devtools/browser/test?token=connect-secret";
        }

        List<String> methods() {
            return methods;
        }

        List<String> expressions() {
            return expressions;
        }

        Map<String, Object> lastDialogParams() {
            return lastDialogParams.get();
        }

        void assertHealthy() {
            assertThat(failure.get()).isNull();
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                clientSocket = socket;
                socket.setSoTimeout(5000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                completeHandshake(input, output);
                while (running.get()) {
                    Frame frame = readFrame(input);
                    if (frame.opcode == 0x8) {
                        writeFrame(output, 0x8, new byte[0]);
                        return;
                    }
                    if (frame.opcode == 0x9) {
                        writeFrame(output, 0xA, frame.payload);
                        continue;
                    }
                    if (frame.opcode != 0x1) {
                        continue;
                    }
                    String response =
                            handleMessage(new String(frame.payload, StandardCharsets.UTF_8));
                    writeFrame(output, 0x1, response.getBytes(StandardCharsets.UTF_8));
                    for (String event : new ArrayList<String>(outboundEvents)) {
                        if (outboundEvents.remove(event)) {
                            writeFrame(output, 0x1, event.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (SocketException e) {
                if (running.get()) {
                    failure.compareAndSet(null, e);
                }
            } catch (Throwable e) {
                if (running.get()) {
                    failure.compareAndSet(null, e);
                }
            }
        }

        private void completeHandshake(InputStream input, OutputStream output) throws Exception {
            String headers = readHeaders(input);
            String websocketKey = "";
            for (String line : headers.split("\\r?\\n")) {
                if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18)) {
                    websocketKey = line.substring(18).trim();
                    break;
                }
            }
            if (websocketKey.length() == 0) {
                throw new IOException("missing websocket key");
            }
            String accept =
                    Base64.getEncoder()
                            .encodeToString(
                                    MessageDigest.getInstance("SHA-1")
                                            .digest(
                                                    (websocketKey + WEBSOCKET_GUID)
                                                            .getBytes(StandardCharsets.US_ASCII)));
            String response =
                    "HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: "
                            + accept
                            + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @SuppressWarnings("unchecked")
        private String handleMessage(String json) {
            Map<String, Object> command = (Map<String, Object>) ONode.ofJson(json).toData();
            Object id = command.get("id");
            String method = String.valueOf(command.get("method"));
            Map<String, Object> params =
                    command.get("params") instanceof Map
                            ? (Map<String, Object>) command.get("params")
                            : Collections.<String, Object>emptyMap();
            methods.add(method);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            if ("Target.getTargets".equals(method)) {
                Map<String, Object> target =
                        mapOf("targetId", "page-1", "type", "page", "url", currentUrl);
                result.put("targetInfos", Collections.<Object>singletonList(target));
            } else if ("Target.attachToTarget".equals(method)) {
                result.put("sessionId", "target-session-1");
            } else if ("Target.createTarget".equals(method)) {
                result.put("targetId", "page-1");
            } else if ("Page.navigate".equals(method)) {
                currentUrl = String.valueOf(params.get("url"));
                result.put("frameId", "frame-1");
            } else if ("Page.getNavigationHistory".equals(method)) {
                result.put("currentIndex", Integer.valueOf(1));
                result.put(
                        "entries",
                        Arrays.<Object>asList(
                                mapOf(
                                        "id",
                                        Integer.valueOf(10),
                                        "url",
                                        "https://93.184.216.34/previous"),
                                mapOf("id", Integer.valueOf(11), "url", currentUrl)));
            } else if ("Page.navigateToHistoryEntry".equals(method)) {
                currentUrl = "https://93.184.216.34/previous";
            } else if ("Page.captureScreenshot".equals(method)) {
                result.put("data", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4}));
            } else if ("Runtime.enable".equals(method)) {
                enqueueStartupEvents();
            } else if ("Page.handleJavaScriptDialog".equals(method)) {
                lastDialogParams.set(new LinkedHashMap<String, Object>(params));
            } else if ("Browser.getVersion".equals(method)) {
                result.put("product", "Chrome/123");
                result.put(
                        "webSocketDebuggerUrl",
                        "ws://user:secret@example.test/devtools?token=cdp-secret-token-12345");
            } else if ("Runtime.evaluate".equals(method)) {
                String expression = String.valueOf(params.get("expression"));
                expressions.add(expression);
                Object value;
                if (expression.contains("document.readyState")) {
                    value = "complete";
                } else if (expression.contains("window.location.href")) {
                    value = currentUrl;
                } else if (expression.contains("data-solonclaw-ref")
                        && expression.contains("elementCount")) {
                    Map<String, Object> refs = new LinkedHashMap<String, Object>();
                    refs.put("@e1", mapOf("tag", "button", "label", "Submit"));
                    refs.put("@e2", mapOf("tag", "input", "label", "Name"));
                    value =
                            mapOf(
                                    "ok",
                                    Boolean.TRUE,
                                    "snapshot",
                                    "snapshot-start "
                                            + repeat('x', 9000)
                                            + " snapshot-secret-token-12345",
                                    "refs",
                                    refs,
                                    "elementCount",
                                    Integer.valueOf(2));
                } else if (expression.contains("element.click()")) {
                    value =
                            failClick
                                    ? mapOf("ok", Boolean.FALSE, "code", "element_not_found")
                                    : mapOf("ok", Boolean.TRUE, "tag", "button");
                } else if (expression.contains("element.dispatchEvent")) {
                    value = mapOf("ok", Boolean.TRUE, "tag", "input");
                } else if (expression.contains("window.scrollBy")) {
                    value = Integer.valueOf(700);
                } else if (expression.contains("document.images")) {
                    value =
                            Collections.<Object>singletonList(
                                    mapOf(
                                            "src",
                                            "https://cdn.example.test/image.png?token=image-secret-token-12345",
                                            "alt",
                                            "hero sk-imageSecret123456",
                                            "width",
                                            Integer.valueOf(640),
                                            "height",
                                            Integer.valueOf(480)));
                } else if ("window.__testState".equals(expression)) {
                    value =
                            mapOf(
                                    "authorization",
                                    "Bearer expression-secret-token-12345",
                                    "nested",
                                    mapOf(
                                            "url",
                                            "https://example.test/path?token=expression-secret-token-12345"));
                } else if (expression.contains("let content")) {
                    value = mapOf("ok", Boolean.TRUE, "content", "provider text");
                } else {
                    value = null;
                }
                result.put("result", remoteValue(value));
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("id", id);
            response.put("result", result);
            return ONode.serialize(response);
        }

        private void enqueueStartupEvents() {
            if (!startupEventsSent.compareAndSet(false, true)) {
                return;
            }
            Map<String, Object> consoleEvent = new LinkedHashMap<String, Object>();
            consoleEvent.put("method", "Runtime.consoleAPICalled");
            consoleEvent.put("sessionId", "target-session-1");
            consoleEvent.put(
                    "params",
                    mapOf(
                            "type",
                            "error",
                            "timestamp",
                            Double.valueOf(1.0),
                            "args",
                            Collections.<Object>singletonList(
                                    mapOf(
                                            "value",
                                            "console-message token=console-secret-token-12345"))));
            outboundEvents.add(ONode.serialize(consoleEvent));

            Map<String, Object> dialogEvent = new LinkedHashMap<String, Object>();
            dialogEvent.put("method", "Page.javascriptDialogOpening");
            dialogEvent.put("sessionId", "target-session-1");
            dialogEvent.put(
                    "params",
                    mapOf(
                            "type",
                            "prompt",
                            "message",
                            "Confirm action",
                            "defaultPrompt",
                            "default value"));
            outboundEvents.add(ONode.serialize(dialogEvent));
        }

        private String repeat(char value, int count) {
            char[] values = new char[count];
            Arrays.fill(values, value);
            return new String(values);
        }

        private Map<String, Object> remoteValue(Object value) {
            Map<String, Object> remote = new LinkedHashMap<String, Object>();
            remote.put("type", value instanceof Map ? "object" : "string");
            remote.put("value", value);
            return remote;
        }

        private Map<String, Object> mapOf(Object... entries) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (int i = 0; i + 1 < entries.length; i += 2) {
                result.put(String.valueOf(entries[i]), entries[i + 1]);
            }
            return result;
        }

        private String readHeaders(InputStream input) throws Exception {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int matched = 0;
            while (buffer.size() < 16384) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException("websocket handshake ended early");
                }
                buffer.write(value);
                int expected = matched == 0 || matched == 2 ? '\r' : '\n';
                if (value == expected) {
                    matched++;
                    if (matched == 4) {
                        return buffer.toString(StandardCharsets.ISO_8859_1.name());
                    }
                } else {
                    matched = value == '\r' ? 1 : 0;
                }
            }
            throw new IOException("websocket handshake headers too large");
        }

        private Frame readFrame(InputStream input) throws Exception {
            int first = readRequired(input);
            int second = readRequired(input);
            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126L) {
                length = (readRequired(input) << 8) | readRequired(input);
            } else if (length == 127L) {
                length = 0L;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readRequired(input);
                }
            }
            if (length > 1024L * 1024L) {
                throw new IOException("test websocket frame too large");
            }
            byte[] mask = masked ? readFully(input, 4) : new byte[0];
            byte[] payload = readFully(input, (int) length);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            return new Frame(opcode, payload);
        }

        private void writeFrame(OutputStream output, int opcode, byte[] payload) throws Exception {
            output.write(0x80 | opcode);
            if (payload.length < 126) {
                output.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                output.write(126);
                output.write((payload.length >>> 8) & 0xFF);
                output.write(payload.length & 0xFF);
            } else {
                output.write(127);
                long length = payload.length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    output.write((int) ((length >>> shift) & 0xFF));
                }
            }
            output.write(payload);
            output.flush();
        }

        private int readRequired(InputStream input) throws Exception {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("websocket frame ended early");
            }
            return value;
        }

        private byte[] readFully(InputStream input, int length) throws Exception {
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = input.read(bytes, offset, length - offset);
                if (read < 0) {
                    throw new EOFException("websocket payload ended early");
                }
                offset += read;
            }
            return bytes;
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            Socket socket = clientSocket;
            if (socket != null) {
                socket.close();
            }
            serverSocket.close();
            serverThread.join(2000L);
            assertHealthy();
        }

        private static class Frame {
            private final int opcode;
            private final byte[] payload;

            Frame(int opcode, byte[] payload) {
                this.opcode = opcode;
                this.payload = payload;
            }
        }
    }

    private static class RecordingProvider implements BrowserProvider {
        private final boolean available;
        private final String connectUrl;
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger navigateCount = new AtomicInteger();
        private final AtomicInteger clickCount = new AtomicInteger();
        private final AtomicInteger typeCount = new AtomicInteger();
        private final AtomicInteger snapshotCount = new AtomicInteger();
        private final AtomicInteger screenshotCount = new AtomicInteger();
        private final AtomicInteger extractCount = new AtomicInteger();
        private String nextUrl = "http://127.0.0.1/after-action";
        private String lastNavigatedUrl = "";

        RecordingProvider(boolean available) {
            this(available, "wss://browser.example/connect?token=sk-secretsecret");
        }

        RecordingProvider(boolean available, String connectUrl) {
            this.available = available;
            this.connectUrl = connectUrl;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public BrowserSession createSession(String taskId) {
            createCount.incrementAndGet();
            return new BrowserSession("session-" + sequence.incrementAndGet(), connectUrl);
        }

        @Override
        public void closeSession(String sessionId) {
            closeCount.incrementAndGet();
        }

        @Override
        public BrowserActionResult navigate(String sessionId, String url, int timeoutSeconds) {
            navigateCount.incrementAndGet();
            lastNavigatedUrl = url;
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("providerAction", "navigate");
            details.put("url", url);
            details.put("timeoutSeconds", timeoutSeconds);
            return BrowserActionResult.ok("navigated", nextUrl, details);
        }

        @Override
        public BrowserActionResult click(String sessionId, String selector, int timeoutSeconds) {
            clickCount.incrementAndGet();
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("selector", selector);
            details.put("timeoutSeconds", timeoutSeconds);
            return BrowserActionResult.ok("clicked", nextUrl, details);
        }

        @Override
        public BrowserActionResult type(
                String sessionId, String selector, String text, int timeoutSeconds) {
            typeCount.incrementAndGet();
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("selector", selector);
            details.put("text", text);
            details.put("timeoutSeconds", timeoutSeconds);
            return BrowserActionResult.ok("typed", nextUrl, details);
        }

        @Override
        public BrowserActionResult snapshot(String sessionId, boolean full) {
            snapshotCount.incrementAndGet();
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("snapshot", "page snapshot");
            details.put("refs", Collections.singletonMap("@e1", "button"));
            return BrowserActionResult.ok("snapshot", nextUrl, details);
        }

        @Override
        public BrowserActionResult screenshot(String sessionId, String path, boolean fullPage) {
            screenshotCount.incrementAndGet();
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("path", path);
            details.put("fullPage", Boolean.valueOf(fullPage));
            return BrowserActionResult.ok("screenshot", nextUrl, details);
        }

        @Override
        public BrowserActionResult extract(String sessionId, String selector, String format) {
            extractCount.incrementAndGet();
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("selector", selector);
            details.put("format", format);
            details.put("content", "provider text");
            return BrowserActionResult.ok("extracted", nextUrl, details);
        }
    }

    private static class FixedDnsSecurityPolicyService extends SecurityPolicyService {
        private final String ip;

        FixedDnsSecurityPolicyService(AppConfig appConfig, String ip) {
            super(appConfig);
            this.ip = ip;
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            if (Arrays.asList("internal.example").contains(host)) {
                return new InetAddress[] {InetAddress.getByName(ip)};
            }
            return super.resolveHost(host);
        }
    }

    /**
     * 读取工具方法声明的参数名称。
     *
     * @param method 带 ToolMapping 的工具方法。
     * @return Param 注解中的参数名称集合。
     */
    private static Set<String> toolParamNames(Method method) {
        Set<String> names = new HashSet<String>();
        if (method == null) {
            return names;
        }
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof Param) {
                    names.add(((Param) annotation).name());
                }
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static void expireLease(BrowserRuntimeService service, String sessionId)
            throws Exception {
        setLeaseExpiry(service, sessionId, System.currentTimeMillis() - 1000L);
    }

    private static void setLeaseExpiry(
            BrowserRuntimeService service, String sessionId, long expiresAt) throws Exception {
        Field leasesField = BrowserRuntimeService.class.getDeclaredField("leases");
        leasesField.setAccessible(true);
        ConcurrentMap<String, Object> leases =
                (ConcurrentMap<String, Object>) leasesField.get(service);
        Object lease = leases.get(sessionId);
        Field expiresAtField = lease.getClass().getDeclaredField("expiresAtMillis");
        expiresAtField.setAccessible(true);
        expiresAtField.setLong(lease, expiresAt);
    }

    @SuppressWarnings("unchecked")
    private static long readLeaseExpiry(BrowserRuntimeService service, String sessionId)
            throws Exception {
        Field leasesField = BrowserRuntimeService.class.getDeclaredField("leases");
        leasesField.setAccessible(true);
        ConcurrentMap<String, Object> leases =
                (ConcurrentMap<String, Object>) leasesField.get(service);
        Object lease = leases.get(sessionId);
        Field expiresAtField = lease.getClass().getDeclaredField("expiresAtMillis");
        expiresAtField.setAccessible(true);
        return expiresAtField.getLong(lease);
    }
}
