package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void shouldCheckSchemelessUrlValuesFromEndpointLikeArgumentKeys() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("base_url", "internal.example/v1");
        args.put("apiEndpoint", "public.example/v1");

        SecurityPolicyService.UrlVerdict verdict = policy.checkToolArgs("remote_fetch", args);

        assertThat(policy.extractUrlishValues(args)).contains("http://internal.example/v1");
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("内网");
    }

    @Test
    void shouldCheckEndpointLikeArgumentKeysInsideNestedContainers() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(config, "127.0.0.1");
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("api_url", "localhost:8080/admin");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("config", nested);

        SecurityPolicyService.UrlVerdict verdict = policy.checkToolArgs("mcp_proxy", args);

        assertThat(policy.extractUrlishValues(args)).contains("http://localhost:8080/admin");
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("内网");
    }

    @Test
    void shouldBlockBarePackedIpv4MetadataCommandTargets() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        SecurityPolicyService.UrlVerdict decimal =
                policy.checkCommandAlwaysBlockedUrls("curl 2852039166");
        SecurityPolicyService.UrlVerdict hex =
                policy.checkCommandAlwaysBlockedUrls("curl 0xa9fea9fe");
        SecurityPolicyService.UrlVerdict bits =
                policy.checkCommandAlwaysBlockedUrls(
                        "Start-BitsTransfer -Source 0xa9fea9fe -Destination out.txt");
        SecurityPolicyService.UrlVerdict certutil =
                policy.checkCommandAlwaysBlockedUrls(
                        "certutil -urlcache -split -f 2852039166 payload.bin");
        SecurityPolicyService.UrlVerdict aria =
                policy.checkCommandAlwaysBlockedUrls("aria2c 0xa9fea9fe");
        SecurityPolicyService.UrlVerdict httpie =
                policy.checkCommandAlwaysBlockedUrls("http 2852039166");
        SecurityPolicyService.UrlVerdict nc =
                policy.checkCommandAlwaysBlockedUrls("nc 2852039166 80");
        SecurityPolicyService.UrlVerdict ncat =
                policy.checkCommandAlwaysBlockedUrls("ncat 0xa9fea9fe 80");
        SecurityPolicyService.UrlVerdict telnet =
                policy.checkCommandAlwaysBlockedUrls("telnet 169.254.169.254 80");
        SecurityPolicyService.UrlVerdict socat =
                policy.checkCommandAlwaysBlockedUrls("socat - TCP:169.254.169.254:80");
        SecurityPolicyService.UrlVerdict openssl =
                policy.checkCommandAlwaysBlockedUrls(
                        "openssl s_client -connect 169.254.169.254:443");
        SecurityPolicyService.UrlVerdict safeNumber =
                policy.checkCommandAlwaysBlockedUrls("printf 12345");

        assertThat(decimal.isAllowed()).isFalse();
        assertThat(decimal.getMessage()).contains("元数据");
        assertThat(hex.isAllowed()).isFalse();
        assertThat(hex.getMessage()).contains("元数据");
        assertThat(bits.isAllowed()).isFalse();
        assertThat(bits.getMessage()).contains("元数据");
        assertThat(certutil.isAllowed()).isFalse();
        assertThat(certutil.getMessage()).contains("元数据");
        assertThat(aria.isAllowed()).isFalse();
        assertThat(aria.getMessage()).contains("元数据");
        assertThat(httpie.isAllowed()).isFalse();
        assertThat(httpie.getMessage()).contains("元数据");
        assertThat(nc.isAllowed()).isFalse();
        assertThat(nc.getMessage()).contains("元数据");
        assertThat(ncat.isAllowed()).isFalse();
        assertThat(ncat.getMessage()).contains("元数据");
        assertThat(telnet.isAllowed()).isFalse();
        assertThat(telnet.getMessage()).contains("元数据");
        assertThat(socat.isAllowed()).isFalse();
        assertThat(socat.getMessage()).contains("元数据");
        assertThat(openssl.isAllowed()).isFalse();
        assertThat(openssl.getMessage()).contains("元数据");
        assertThat(safeNumber.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockBareIpv6MetadataCommandTargets() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        SecurityPolicyService.UrlVerdict mapped =
                policy.checkCommandAlwaysBlockedUrls("curl [::ffff:169.254.169.254]");
        SecurityPolicyService.UrlVerdict expanded =
                policy.checkCommandAlwaysBlockedUrls("curl [0:0:0:0:0:ffff:a9fe:a9fe]");
        SecurityPolicyService.UrlVerdict bitsAdmin =
                policy.checkCommandAlwaysBlockedUrls(
                        "bitsadmin /transfer job /download [::ffff:169.254.169.254] out.txt");
        SecurityPolicyService.UrlVerdict mshta =
                policy.checkCommandAlwaysBlockedUrls("mshta [::ffff:169.254.169.254]");
        SecurityPolicyService.UrlVerdict socat =
                policy.checkCommandAlwaysBlockedUrls("socat - TCP:[::ffff:169.254.169.254]:80");

        assertThat(mapped.isAllowed()).isFalse();
        assertThat(mapped.getMessage()).contains("元数据");
        assertThat(expanded.isAllowed()).isFalse();
        assertThat(expanded.getMessage()).contains("元数据");
        assertThat(bitsAdmin.isAllowed()).isFalse();
        assertThat(bitsAdmin.getMessage()).contains("元数据");
        assertThat(mshta.isAllowed()).isFalse();
        assertThat(mshta.getMessage()).contains("元数据");
        assertThat(socat.isAllowed()).isFalse();
        assertThat(socat.getMessage()).contains("元数据");
    }

    @Test
    void shouldBlockSchemelessUserInfoUrlsInCommandsAndArguments() {
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict direct =
                policy.checkUrl("alice:secret@example.com/path");
        SecurityPolicyService.UrlVerdict command =
                policy.checkCommandUrls("curl alice:secret@example.com/path");
        SecurityPolicyService.UrlVerdict safe =
                policy.checkCommandUrls("curl example.com/path");

        assertThat(direct.isAllowed()).isFalse();
        assertThat(direct.getMessage()).contains("userinfo");
        assertThat(command.isAllowed()).isFalse();
        assertThat(command.getMessage()).contains("userinfo");
        assertThat(safe.isAllowed()).isTrue();
    }

    @Test
    void shouldCheckProtocolRelativeUrlsInCommandsAndArguments() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy =
                new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict privateDirect =
                privatePolicy.checkUrl("//internal.example/path");
        SecurityPolicyService.UrlVerdict privateCommand =
                privatePolicy.checkCommandUrls("curl //internal.example/path");
        SecurityPolicyService.UrlVerdict userInfoCommand =
                publicPolicy.checkCommandUrls("curl //alice:secret@example.com/path");
        SecurityPolicyService.UrlVerdict publicCommand =
                publicPolicy.checkCommandUrls("curl //example.com/path");

        assertThat(privateDirect.isAllowed()).isFalse();
        assertThat(privateDirect.getMessage()).contains("内网");
        assertThat(privateCommand.isAllowed()).isFalse();
        assertThat(privateCommand.getMessage()).contains("内网");
        assertThat(userInfoCommand.isAllowed()).isFalse();
        assertThat(userInfoCommand.getMessage()).contains("userinfo");
        assertThat(publicCommand.isAllowed()).isTrue();
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

    @Test
    void shouldMergeWebsiteBlocklistDomainsAndSharedFileRules() throws Exception {
        Path runtimeHome = Files.createTempDirectory("jimuqu-website-policy");
        File shared = runtimeHome.resolve("community-blocklist.txt").toFile();
        FileUtil.writeUtf8String(
                "# comment\n"
                        + "example.org\n"
                        + "https://www.evil.test/path\n"
                        + "*.tracking.example\n",
                shared);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("example.com", "https://www.inline.test/path"));
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList("community-blocklist.txt"));
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        SecurityPolicyService.UrlVerdict configDomain =
                policy.checkUrl("https://docs.example.com/page");
        SecurityPolicyService.UrlVerdict configUrlRule =
                policy.checkUrl("https://www.inline.test/page");
        SecurityPolicyService.UrlVerdict sharedDomain =
                policy.checkUrl("https://api.example.org/v1");
        SecurityPolicyService.UrlVerdict sharedUrlRule =
                policy.checkUrl("https://evil.test/login");
        SecurityPolicyService.UrlVerdict wildcardChild =
                policy.checkUrl("https://a.tracking.example/pixel");
        SecurityPolicyService.UrlVerdict wildcardBare =
                policy.checkUrl("https://tracking.example/pixel");

        assertThat(configDomain.isAllowed()).isFalse();
        assertThat(configDomain.getMessage()).contains("example.com");
        assertThat(configUrlRule.isAllowed()).isFalse();
        assertThat(configUrlRule.getMessage()).contains("inline.test");
        assertThat(sharedDomain.isAllowed()).isFalse();
        assertThat(sharedDomain.getMessage()).contains("example.org");
        assertThat(sharedUrlRule.isAllowed()).isFalse();
        assertThat(sharedUrlRule.getMessage()).contains("evil.test");
        assertThat(wildcardChild.isAllowed()).isFalse();
        assertThat(wildcardChild.getMessage()).contains("tracking.example");
        assertThat(wildcardBare.isAllowed()).isTrue();
    }

    @Test
    void shouldSkipMissingAndUnsafeWebsiteBlocklistSharedFiles() throws Exception {
        Path parent = Files.createTempDirectory("jimuqu-website-policy-parent");
        Path runtimeHome = Files.createDirectory(parent.resolve("runtime"));
        File outside = parent.resolve("outside-blocklist.txt").toFile();
        FileUtil.writeUtf8String("blocked-outside.example\n", outside);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList("missing-blocklist.txt", "../outside-blocklist.txt"));
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        assertThat(policy.checkUrl("https://allowed.example/").isAllowed()).isTrue();
        assertThat(policy.checkUrl("https://blocked-outside.example/").isAllowed()).isTrue();
    }

    @Test
    void shouldLoadAbsoluteWebsiteBlocklistSharedFiles() throws Exception {
        Path runtimeHome = Files.createTempDirectory("jimuqu-website-policy");
        File shared = Files.createTempFile("jimuqu-shared-blocklist", ".txt").toFile();
        FileUtil.writeUtf8String("absolute-blocked.example\n", shared);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList(shared.getAbsolutePath()));
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        SecurityPolicyService.UrlVerdict verdict =
                policy.checkUrl("https://cdn.absolute-blocked.example/path");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("absolute-blocked.example");
    }

    @Test
    void shouldDenyWritesToSensitiveSystemAndCredentialPathsLikeJimuquPolicy() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());
        String home = System.getProperty("user.home");

        assertWriteDenied(policy, "/etc/shadow");
        assertWriteDenied(policy, "/etc/passwd");
        assertWriteDenied(policy, "/etc/sudoers");
        assertWriteDenied(policy, "/etc/sudoers.d/custom");
        assertWriteDenied(policy, "/etc/systemd/system/evil.service");
        assertWriteDenied(policy, "~/.ssh/authorized_keys");
        assertWriteDenied(policy, home + "/.ssh/id_rsa");
        assertWriteDenied(policy, home + "/.ssh/id_ed25519");
        assertWriteDenied(policy, home + "/.aws/credentials");
        assertWriteDenied(policy, home + "/.gnupg/secring.gpg");
        assertWriteDenied(policy, home + "/.kube/config");
        assertWriteDenied(policy, home + "/.netrc");
        assertWriteDenied(policy, home + "/.npmrc");
        assertWriteDenied(policy, home + "/.pypirc");
        assertWriteDenied(policy, home + "/.pgpass");
        assertWriteDenied(policy, home + "/.bashrc");
        assertWriteDenied(policy, home + "/.zshrc");
        assertWriteDenied(policy, home + "/.profile");
        assertWriteDenied(policy, home + "/.bash_profile");
        assertWriteDenied(policy, home + "/.zprofile");
        assertWriteDenied(policy, ".env");
        assertWriteDenied(policy, ".envrc");
        assertWriteDenied(policy, ".env.local");
        assertWriteDenied(policy, "credentials.json");
        assertWriteDenied(policy, "service-account.json");
        assertWriteDenied(policy, "private-api-key.pem");

        assertThat(policy.checkPath("/tmp/safe_file.txt", true).isAllowed()).isTrue();
        assertThat(policy.checkPath("/home/user/project/main.py", true).isAllowed()).isTrue();
        assertThat(policy.checkPath(home + "/.jimuqu/config.yml", true).isAllowed()).isTrue();
        assertThat(policy.checkPath(".env.example", true).isAllowed()).isTrue();
        assertThat(policy.checkPath(".envrc.example", true).isAllowed()).isTrue();
    }

    @Test
    void shouldDenyCommandReadsFromEnvrcCredentialFile() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        SecurityPolicyService.FileVerdict verdict = policy.checkCommandPaths("cat .envrc");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getPath()).isEqualTo(".envrc");
        assertThat(verdict.getMessage()).contains("凭据");
    }

    @Test
    void shouldDenyCommandCredentialFilesWithRelativeAndVariablePrefixes() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        assertCommandPathDenied(policy, "printf token > ./.env", "./.env");
        assertCommandPathDenied(policy, "cat $PWD/.env", "$PWD/.env");
        assertCommandPathDenied(policy, "cat ${PWD}/.env.local", "${PWD}/.env.local");
        assertCommandPathDenied(policy, "Get-Content $env:USERPROFILE\\.npmrc", "$env:USERPROFILE\\.npmrc");
        assertCommandPathDenied(policy, "type %USERPROFILE%\\.netrc", "%USERPROFILE%\\.netrc");
        assertCommandPathDenied(policy, "cat ~/.aws/credentials", "~/.aws/credentials");

        assertThat(policy.checkCommandPaths("cat .env.example").isAllowed()).isTrue();
    }

    @Test
    void shouldDenyPatchDiffsTargetingSensitiveCredentialPaths() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());
        Map<String, Object> addFileArgs = new LinkedHashMap<String, Object>();
        addFileArgs.put(
                "patch",
                "*** Begin Patch\n"
                        + "*** Add File: .env\n"
                        + "+TOKEN=secret\n"
                        + "*** End Patch\n");
        Map<String, Object> unifiedArgs = new LinkedHashMap<String, Object>();
        unifiedArgs.put(
                "diff",
                "diff --git a/src/Main.java b/.ssh/authorized_keys\n"
                        + "--- a/src/Main.java\n"
                        + "+++ b/.ssh/authorized_keys\n"
                        + "@@ -0,0 +1 @@\n"
                        + "+ssh-rsa AAA\n");
        Map<String, Object> safeArgs = new LinkedHashMap<String, Object>();
        safeArgs.put(
                "content",
                "diff --git a/src/Main.java b/src/Main.java\n"
                        + "--- a/src/Main.java\n"
                        + "+++ b/src/Main.java\n"
                        + "@@ -1 +1 @@\n"
                        + "-old\n"
                        + "+new\n");

        SecurityPolicyService.FileVerdict addFileVerdict =
                policy.checkFileToolArgs("patch", addFileArgs);
        SecurityPolicyService.FileVerdict unifiedVerdict =
                policy.checkFileToolArgs("patch", unifiedArgs);

        assertThat(addFileVerdict.isAllowed()).isFalse();
        assertThat(addFileVerdict.getPath()).isEqualTo(".env");
        assertThat(addFileVerdict.getMessage()).contains("凭据");
        assertThat(unifiedVerdict.isAllowed()).isFalse();
        assertThat(unifiedVerdict.getPath()).isEqualTo(".ssh/authorized_keys");
        assertThat(policy.checkFileToolArgs("patch", safeArgs).isAllowed()).isTrue();
    }

    private static void assertWriteDenied(SecurityPolicyService policy, String path) {
        SecurityPolicyService.FileVerdict verdict = policy.checkPath(path, true);
        assertThat(verdict.isAllowed()).as(path).isFalse();
        assertThat(verdict.getMessage()).as(path).contains("敏感");
    }

    private static void assertCommandPathDenied(
            SecurityPolicyService policy, String command, String path) {
        SecurityPolicyService.FileVerdict verdict = policy.checkCommandPaths(command);
        assertThat(verdict.isAllowed()).as(command).isFalse();
        assertThat(verdict.getPath()).as(command).isEqualTo(path);
        assertThat(verdict.getMessage()).as(command).contains("凭据");
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
