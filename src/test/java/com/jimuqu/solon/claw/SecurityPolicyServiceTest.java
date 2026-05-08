package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SecurityPolicyServiceTest {
    @Test
    void shouldExposeAlwaysBlockedUrlFloorForCloudMetadataTargets() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        assertThat(policy.isAlwaysBlockedUrl("http://169.254.169.254/latest/meta-data/"))
                .isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://169.254.169.253/metadata/instance"))
                .isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://169.254.170.2/v2/credentials"))
                .isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://100.100.100.200/latest/meta-data/"))
                .isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://169.254.42.1/")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://metadata.google.internal/")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://metadata.goog/computeMetadata/v1/"))
                .isTrue();
    }

    @Test
    void shouldResolveHostnameBeforeApplyingAlwaysBlockedFloor() {
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");

        SecurityPolicyService.UrlVerdict verdict =
                policy.checkAlwaysBlockedUrl("https://attacker.example/resource");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("元数据");
    }

    @Test
    void shouldTreatIpv4MappedMetadataAddressAsAlwaysBlocked() {
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "::ffff:169.254.169.254");

        assertThat(policy.isAlwaysBlockedUrl("https://attacker.example/resource")).isTrue();
    }

    @Test
    void shouldKeepAlwaysBlockedFloorNarrowerThanFullSsrfPolicy() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        assertThat(policy.isAlwaysBlockedUrl("http://127.0.0.1:8080/")).isFalse();
        assertThat(policy.isAlwaysBlockedUrl("http://10.0.0.5/")).isFalse();
        assertThat(policy.isAlwaysBlockedUrl("http://172.16.0.1/")).isFalse();
        assertThat(policy.isAlwaysBlockedUrl("http://192.168.1.1/")).isFalse();
        assertThat(policy.isAlwaysBlockedUrl("http://100.64.0.1/")).isFalse();
    }

    @Test
    void shouldNotClaimDnsFailuresOrMalformedInputAreAlwaysBlocked() {
        SecurityPolicyService policy = new FailingDnsSecurityPolicyService(new AppConfig());

        assertThat(policy.isAlwaysBlockedUrl("https://nonexistent.example/")).isFalse();
        assertThat(policy.isAlwaysBlockedUrl("")).isFalse();
        assertThat(policy.isAlwaysBlockedUrl("not a url at all")).isFalse();
        assertThat(policy.isAlwaysBlockedUrl("ftp://169.254.169.254/file")).isTrue();
    }

    @Test
    void shouldIgnoreAllowPrivateUrlToggleForAlwaysBlockedFloor() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        SecurityPolicyService policy = new SecurityPolicyService(config);

        assertThat(policy.isAlwaysBlockedUrl("http://169.254.169.254/")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://127.0.0.1:8080/")).isFalse();
    }

    @Test
    void shouldAllowPrivateUrlsFromJimuquEnvironmentOverride() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy =
                new FixedDnsEnvSecurityPolicyService(
                        config, "192.168.1.1", env("JIMUQU_ALLOW_PRIVATE_URLS", "true"));

        SecurityPolicyService.UrlVerdict privateUrl = policy.checkUrl("http://router.example/");
        SecurityPolicyService.UrlVerdict metadata = policy.checkUrl("http://169.254.169.254/");

        assertThat(privateUrl.isAllowed()).isTrue();
        assertThat(metadata.isAllowed()).isFalse();
        assertThat(metadata.getMessage()).contains("元数据");
    }

    @Test
    void shouldSupportJimuquAllowPrivateUrlEnvironmentCompatibility() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy =
                new FixedDnsEnvSecurityPolicyService(
                        config, "10.0.0.5", env("JIMUQU_ALLOW_PRIVATE_URLS", "on"));

        SecurityPolicyService.UrlVerdict verdict = policy.checkUrl("https://internal.example/");

        assertThat(verdict.isAllowed()).isTrue();
    }

    @Test
    void shouldLetJimuquEnvironmentOverrideWinOverJimuquCompatibilityValue() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        Map<String, String> environment = env("JIMUQU_ALLOW_PRIVATE_URLS", "true");
        environment.put("JIMUQU_ALLOW_PRIVATE_URLS", "false");
        SecurityPolicyService policy =
                new FixedDnsEnvSecurityPolicyService(config, "172.16.0.5", environment);

        SecurityPolicyService.UrlVerdict verdict = policy.checkUrl("https://private.example/");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("内网");
    }

    @Test
    void shouldNotLetEnvironmentDisableExplicitPrivateUrlAllowance() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy =
                new FixedDnsEnvSecurityPolicyService(
                        config, "127.0.0.1", env("JIMUQU_ALLOW_PRIVATE_URLS", "false"));

        SecurityPolicyService.UrlVerdict defaultVerdict = policy.checkUrl("http://localhost:8080/");
        SecurityPolicyService.UrlVerdict explicitVerdict =
                policy.checkUrlAllowingPrivate("http://localhost:8080/");

        assertThat(defaultVerdict.isAllowed()).isFalse();
        assertThat(explicitVerdict.isAllowed()).isTrue();
    }

    @Test
    void shouldNormalizeWebsiteBlocklistHostsBeforeMatching() {
        AppConfig config = new AppConfig();
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("https://WWW.Blocked.Example./docs", "*.wild.example"));
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        SecurityPolicyService.UrlVerdict mixedCase =
                policy.checkUrl("https://api.BLOCKED.example./v1");
        SecurityPolicyService.UrlVerdict schemeless =
                policy.checkUrl("www.blocked.example/docs");
        SecurityPolicyService.UrlVerdict wildcard =
                policy.checkUrl("https://child.wild.example/path");
        SecurityPolicyService.UrlVerdict bareWildcard =
                policy.checkUrl("https://wild.example/path");

        assertThat(mixedCase.isAllowed()).isFalse();
        assertThat(mixedCase.getMessage()).contains("blocked.example");
        assertThat(schemeless.isAllowed()).isFalse();
        assertThat(schemeless.getMessage()).contains("blocked.example");
        assertThat(wildcard.isAllowed()).isFalse();
        assertThat(wildcard.getMessage()).contains("wild.example");
        assertThat(bareWildcard.isAllowed()).isTrue();
    }

    private static class FixedDnsSecurityPolicyService extends SecurityPolicyService {
        private final String ip;

        protected FixedDnsSecurityPolicyService(AppConfig appConfig, String ip) {
            super(appConfig);
            this.ip = ip;
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            return new InetAddress[] {InetAddress.getByName(ip)};
        }
    }

    private static class FixedDnsEnvSecurityPolicyService extends FixedDnsSecurityPolicyService {
        private final Map<String, String> environment;

        private FixedDnsEnvSecurityPolicyService(
                AppConfig appConfig, String ip, Map<String, String> environment) {
            super(appConfig, ip);
            this.environment = environment;
        }

        @Override
        protected String readEnvironment(String name) {
            return environment.get(name);
        }
    }

    private static class FailingDnsSecurityPolicyService extends SecurityPolicyService {
        private FailingDnsSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            throw new java.net.UnknownHostException(host);
        }
    }

    private static Map<String, String> env(String key, String value) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(key, value);
        return values;
    }
}
