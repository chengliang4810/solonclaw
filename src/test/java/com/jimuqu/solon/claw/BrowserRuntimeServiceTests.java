package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class BrowserRuntimeServiceTests {
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
    void shouldRedactSessionOutput() {
        RecordingProvider provider =
                new RecordingProvider(
                        true,
                        "ws://user:secret@example.com/devtools?token=sk-secretsecret");
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

    private static class RecordingProvider implements BrowserProvider {
        private final boolean available;
        private final String connectUrl;
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();

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
}
