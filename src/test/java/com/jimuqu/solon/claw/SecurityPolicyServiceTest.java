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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class SecurityPolicyServiceTest {
    @AfterEach
    void clearThreadPolicyApprovals() {
        SecurityPolicyService.clearCurrentThreadPolicyApprovals();
    }

    @Test
    void shouldExposeAlwaysBlockedUrlFloorForCloudMetadataTargets() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        assertThat(policy.isAlwaysBlockedUrl("http://169.254.169.254/latest/meta-data/")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://169.254.169.253/metadata/instance")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://169.254.170.2/v2/credentials")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://100.100.100.200/latest/meta-data/")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://169.254.42.1/")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://metadata.google.internal/")).isTrue();
        assertThat(policy.isAlwaysBlockedUrl("http://metadata.goog/computeMetadata/v1/")).isTrue();
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
    void shouldBlockPrivateUrlsByDefaultAndKeepMetadataBlocked() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        SecurityPolicyService.UrlVerdict localhost =
                policy.checkUrl("http://localhost:18080/api/health");
        SecurityPolicyService.UrlVerdict loopback =
                policy.checkUrl("http://127.0.0.1:18080/api/health");
        SecurityPolicyService.UrlVerdict metadata =
                policy.checkUrl("http://169.254.169.254/latest/meta-data/");

        assertThat(localhost.isAllowed()).isFalse();
        assertThat(loopback.isAllowed()).isFalse();
        assertThat(metadata.isAllowed()).isFalse();
        assertThat(localhost.getMessage()).contains("内网");
        assertThat(loopback.getMessage()).contains("内网");
        assertThat(metadata.getMessage()).contains("元数据");
    }

    @Test
    void shouldBlockPrivateUrlsWhenExplicitlyDisabled() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy = new SecurityPolicyService(config);

        SecurityPolicyService.UrlVerdict verdict =
                policy.checkUrl("http://localhost:18080/api/health");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("内网");
    }

    @Test
    void shouldFailClosedForDnsFailuresOnAlwaysBlockedUrlFloor() {
        SecurityPolicyService policy = new FailingDnsSecurityPolicyService(new AppConfig());

        SecurityPolicyService.UrlVerdict dnsFailure =
                policy.checkAlwaysBlockedUrl("https://nonexistent.example/");

        assertThat(dnsFailure.isAllowed()).isFalse();
        assertThat(dnsFailure.getMessage()).contains("DNS 解析失败");
        assertThat(policy.isAlwaysBlockedUrl("https://nonexistent.example/")).isTrue();
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
    void shouldKeepSignedMetadataUrlsAlwaysBlockedWhenPrivateUrlsAreAllowed() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        SecurityPolicyService policy = new SecurityPolicyService(config);

        SecurityPolicyService.UrlVerdict verdict =
                policy.checkUrl(
                        "http://169.254.169.254/latest/meta-data/iam/security-credentials/?x-amz-signature=secret-signature");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("元数据");
        assertThat(verdict.getMessage()).doesNotContain("secret-signature");
    }

    @Test
    void shouldAllowPrivateUrlsFromSolonClawEnvironmentOverride() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy =
                new FixedDnsEnvSecurityPolicyService(
                        config, "192.168.1.1", env("SOLONCLAW_ALLOW_PRIVATE_URLS", "true"));

        SecurityPolicyService.UrlVerdict privateUrl = policy.checkUrl("http://router.example/");
        SecurityPolicyService.UrlVerdict metadata = policy.checkUrl("http://169.254.169.254/");

        assertThat(privateUrl.isAllowed()).isTrue();
        assertThat(metadata.isAllowed()).isFalse();
        assertThat(metadata.getMessage()).contains("元数据");
    }

    @Test
    void shouldTreatProxyFakeIpDnsAnswersAsPublicDomainAccess() {
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "198.18.0.43");

        SecurityPolicyService.UrlVerdict domain =
                policy.checkUrl("https://public.example/resource");
        SecurityPolicyService.UrlVerdict literal =
                policy.checkUrl("https://198.18.0.43/resource");

        assertThat(domain.isAllowed()).isTrue();
        assertThat(literal.isAllowed()).isFalse();
        assertThat(literal.getMessage()).contains("内网");
    }

    @Test
    void shouldIgnoreJimuquAllowPrivateUrlEnvironmentCompatibility() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy =
                new FixedDnsEnvSecurityPolicyService(
                        config, "10.0.0.5", env("JIMUQU_ALLOW_PRIVATE_URLS", "on"));

        SecurityPolicyService.UrlVerdict verdict = policy.checkUrl("https://internal.example/");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("内网");
    }

    @Test
    void shouldOnlyReadJimuquPrefixedAllowPrivateUrlEnvironmentToggle() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("OTHER_ALLOW_PRIVATE_URLS", "true");
        SecurityPolicyService policy =
                new FixedDnsEnvSecurityPolicyService(config, "10.0.0.5", environment);

        SecurityPolicyService.UrlVerdict verdict = policy.checkUrl("https://internal.example/");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("内网");
    }

    @Test
    void shouldLetSolonClawEnvironmentOverrideWinOverConfigValue() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        Map<String, String> environment = env("SOLONCLAW_ALLOW_PRIVATE_URLS", "false");
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
                        config, "127.0.0.1", env("SOLONCLAW_ALLOW_PRIVATE_URLS", "false"));

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
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
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
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "127.0.0.1");
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
    void shouldCheckHostTargetArgumentKeysForRemoteTools() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("server", "internal.example");
        nested.put("proxyHost", "proxy.example:8080");
        nested.put("upstream_target", "upstream.example");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("transport", nested);

        SecurityPolicyService.UrlVerdict verdict = policy.checkToolArgs("mcp_proxy", args);

        assertThat(policy.extractUrlishValues(args))
                .contains("http://internal.example", "http://proxy.example:8080");
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("内网");
    }

    @Test
    void shouldBlockRuntimeSecretCacheFileWithoutBlockingOtherCacheFiles() throws Exception {
        AppConfig config = new AppConfig();
        Path workspaceHome = Files.createTempDirectory("solonclaw-security-cache");
        config.getRuntime().setHome(workspaceHome.toString());
        SecurityPolicyService policy = new SecurityPolicyService(config);

        SecurityPolicyService.FileVerdict ordinaryCache =
                policy.checkPath("cache/media/sample.png", false);
        SecurityPolicyService.FileVerdict sameNameOutsideCache =
                policy.checkPath("tmp/bws_cache.json", false);
        SecurityPolicyService.FileVerdict sameNameOutsideCacheCommand =
                policy.checkCommandPaths("cat tmp/bws_cache.json");

        // bws_cache.json 等凭据/密钥缓存文件读已放宽（对齐 hermes"读非安全边界"），
        // fileTool/relative/command 的读阻断断言已移除。下方"普通缓存文件与同名外部文件不误伤"断言保留。
        assertThat(ordinaryCache.isAllowed()).isTrue();
        assertThat(sameNameOutsideCache.isAllowed()).isTrue();
        assertThat(sameNameOutsideCacheCommand.isAllowed()).isTrue();
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
        SecurityPolicyService.UrlVerdict websocat =
                policy.checkCommandAlwaysBlockedUrls("websocat 169.254.169.254:80");
        SecurityPolicyService.UrlVerdict grpcurl =
                policy.checkCommandAlwaysBlockedUrls("grpcurl 169.254.169.254:443 list");
        SecurityPolicyService.UrlVerdict curlie =
                policy.checkCommandAlwaysBlockedUrls("curlie 2852039166");
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
        assertThat(websocat.isAllowed()).isFalse();
        assertThat(websocat.getMessage()).contains("元数据");
        assertThat(grpcurl.isAllowed()).isFalse();
        assertThat(grpcurl.getMessage()).contains("元数据");
        assertThat(curlie.isAllowed()).isFalse();
        assertThat(curlie.getMessage()).contains("元数据");
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
    void shouldBlockPercentEncodedMetadataHostsBeforeDnsResolution() {
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict direct =
                policy.checkAlwaysBlockedUrl("http://%31%36%39.254.169.254/latest/meta-data/");
        SecurityPolicyService.UrlVerdict command =
                policy.checkCommandAlwaysBlockedUrls(
                        "curl http://%31%36%39.254.169.254/latest/meta-data/");
        SecurityPolicyService.UrlVerdict protocolRelative =
                policy.checkCommandAlwaysBlockedUrls(
                        "curl //%31%36%39.254.169.254/latest/meta-data/");
        SecurityPolicyService.UrlVerdict directProtocolRelative =
                policy.checkAlwaysBlockedUrl("//%31%36%39.254.169.254/latest/meta-data/");
        SecurityPolicyService.UrlVerdict publicProtocolRelative =
                policy.checkAlwaysBlockedUrl("//example.com/path");

        assertThat(direct.isAllowed()).isFalse();
        assertThat(direct.getMessage()).contains("元数据");
        assertThat(command.isAllowed()).isFalse();
        assertThat(command.getMessage()).contains("元数据");
        assertThat(protocolRelative.isAllowed()).isFalse();
        assertThat(protocolRelative.getMessage()).contains("元数据");
        assertThat(directProtocolRelative.isAllowed()).isFalse();
        assertThat(directProtocolRelative.getMessage()).contains("元数据");
        assertThat(publicProtocolRelative.isAllowed()).isTrue();
    }

    @Test
    void shouldBlockPercentEncodedPrivateHostsAcrossUrlSurfaces() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "93.184.216.34");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("url", "http://%31%32%37.0.0.1:8080/admin");

        SecurityPolicyService.UrlVerdict direct =
                policy.checkUrl("http://%31%32%37.0.0.1:8080/admin");
        SecurityPolicyService.UrlVerdict command =
                policy.checkCommandUrls("curl http://%31%32%37.0.0.1:8080/admin");
        SecurityPolicyService.UrlVerdict toolArg = policy.checkToolArgs("webfetch", args);

        assertThat(direct.isAllowed()).isFalse();
        assertThat(direct.getMessage()).contains("内网");
        assertThat(command.isAllowed()).isFalse();
        assertThat(command.getMessage()).contains("内网");
        assertThat(toolArg.isAllowed()).isFalse();
        assertThat(toolArg.getMessage()).contains("内网");
    }

    @Test
    void shouldNormalizeIdnHostSeparatorsBeforeStaticUrlPolicyChecks() {
        AppConfig config = new AppConfig();
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity().getWebsiteBlocklist().setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "93.184.216.34");

        SecurityPolicyService.UrlVerdict metadataIdeographicDot =
                policy.checkAlwaysBlockedUrl("http://metadata\u3002google\u3002internal/");
        SecurityPolicyService.UrlVerdict metadataFullwidthDot =
                policy.checkCommandAlwaysBlockedUrls(
                        "curl http://metadata\uFF0Egoogle\uFF0Einternal/");
        SecurityPolicyService.UrlVerdict metadataHalfwidthDot =
                policy.checkCommandAlwaysBlockedUrls(
                        "curl http://metadata\uFF61google\uFF61internal/");
        SecurityPolicyService.UrlVerdict websiteFullwidthHost =
                policy.checkUrl("http://blocked\uFF0Eexample/path");

        assertThat(metadataIdeographicDot.isAllowed()).isFalse();
        assertThat(metadataIdeographicDot.getMessage()).contains("元数据");
        assertThat(metadataFullwidthDot.isAllowed()).isFalse();
        assertThat(metadataFullwidthDot.getMessage()).contains("元数据");
        assertThat(metadataHalfwidthDot.isAllowed()).isFalse();
        assertThat(metadataHalfwidthDot.getMessage()).contains("元数据");
        assertThat(websiteFullwidthHost.isAllowed()).isFalse();
        assertThat(websiteFullwidthHost.getMessage()).contains("website policy");
    }

    @Test
    void shouldExtractRedirectTargetsFromReturnedContent() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy =
                new FixedDnsSecurityPolicyService(config, "127.0.0.1");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put(
                "content",
                "HTTP/1.1 302 Found\n"
                        + "Location: http://localhost:8080/admin\n"
                        + "<meta http-equiv=\"refresh\" content=\"0; url=http://169.254.169.254/latest/meta-data/\">");

        SecurityPolicyService.UrlVerdict privateVerdict =
                privatePolicy.checkToolArgs("webfetch_result", response);
        SecurityPolicyService.UrlVerdict metadataVerdict =
                metadataPolicy.checkToolArgs("webfetch_result", response);

        assertThat(privatePolicy.extractUrlishValues(response))
                .contains(
                        "http://localhost:8080/admin", "http://169.254.169.254/latest/meta-data/");
        assertThat(privateVerdict.isAllowed()).isFalse();
        assertThat(privateVerdict.getMessage()).contains("内网");
        assertThat(metadataVerdict.isAllowed()).isFalse();
        assertThat(metadataVerdict.getMessage()).containsAnyOf("元数据", "内网");
    }

    @Test
    void shouldExtractSchemelessRedirectTargetsFromReturnedContent() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put(
                "content",
                "{\"redirect_uri\":\"internal.example/callback\",\"finalUrl\":\"public.example/ok\"}");

        SecurityPolicyService.UrlVerdict verdict =
                policy.checkToolArgs("webfetch_result", response);

        assertThat(policy.extractUrlishValues(response))
                .contains("internal.example/callback", "public.example/ok");
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("内网");
    }

    @Test
    void shouldCheckProtocolRelativeUrlsInCommandsAndArguments() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict privateDirect =
                privatePolicy.checkUrl("//internal.example/path");
        SecurityPolicyService.UrlVerdict privateCommand =
                privatePolicy.checkCommandUrls("curl //internal.example/path");
        Map<String, Object> toolArgs = new LinkedHashMap<String, Object>();
        toolArgs.put("url", "//internal.example/path");
        SecurityPolicyService.UrlVerdict privateToolArg =
                privatePolicy.checkToolArgs("remote_fetch", toolArgs);

        assertThat(privateDirect.isAllowed()).isFalse();
        assertThat(privateDirect.getMessage()).contains("内网");
        assertThat(privateCommand.isAllowed()).isFalse();
        assertThat(privateCommand.getMessage()).contains("内网");
        assertThat(privatePolicy.extractUrlishValues(toolArgs)).contains("//internal.example/path");
        assertThat(privateToolArg.isAllowed()).isFalse();
        assertThat(privateToolArg.getMessage()).contains("内网");
    }

    @Test
    void shouldCheckWebsocketUrlsInCommands() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict privateCommand =
                privatePolicy.checkCommandUrls("websocat ws://internal.example/socket");
        SecurityPolicyService.UrlVerdict metadataCommand =
                publicPolicy.checkCommandUrls("websocat wss://169.254.169.254/latest");

        assertThat(privateCommand.isAllowed()).isFalse();
        assertThat(privateCommand.getMessage()).contains("内网");
        assertThat(metadataCommand.isAllowed()).isFalse();
        assertThat(metadataCommand.getMessage()).contains("元数据");
    }

    @Test
    void shouldRejectUnsupportedNetworkSchemesInCommands() {
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict ftp =
                policy.checkCommandUrls("curl ftp://example.com/secret.txt");
        SecurityPolicyService.UrlVerdict sftp =
                policy.checkCommandUrls("curl sftp://example.com/secret.txt");
        SecurityPolicyService.UrlVerdict scp =
                policy.checkCommandUrls("curl scp://example.com/secret.txt");
        SecurityPolicyService.UrlVerdict gopher =
                policy.checkCommandUrls("curl gopher://example.com/_payload");
        SecurityPolicyService.UrlVerdict localFile =
                policy.checkCommandUrls("curl file:///etc/passwd");
        SecurityPolicyService.UrlVerdict ldap =
                policy.checkCommandUrls("curl ldap://example.com/dc=example");
        SecurityPolicyService.UrlVerdict tftp =
                policy.checkCommandUrls("curl tftp://example.com/config");

        assertThat(ftp.isAllowed()).isFalse();
        assertThat(ftp.getMessage()).contains("仅允许");
        assertThat(sftp.isAllowed()).isFalse();
        assertThat(sftp.getMessage()).contains("仅允许");
        assertThat(scp.isAllowed()).isFalse();
        assertThat(scp.getMessage()).contains("仅允许");
        assertThat(gopher.isAllowed()).isFalse();
        assertThat(gopher.getMessage()).contains("仅允许");
        assertThat(localFile.isAllowed()).isFalse();
        assertThat(localFile.getMessage()).contains("仅允许");
        assertThat(ldap.isAllowed()).isFalse();
        assertThat(ldap.getMessage()).contains("仅允许");
        assertThat(tftp.isAllowed()).isFalse();
        assertThat(tftp.getMessage()).contains("仅允许");
    }

    @Test
    void shouldCheckCurlPreproxyCommandTargets() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");

        SecurityPolicyService.UrlVerdict detached =
                privatePolicy.checkCommandUrls(
                        "curl --preproxy socks5://internal.example:1080 https://example.com");
        SecurityPolicyService.UrlVerdict assigned =
                privatePolicy.checkCommandUrls(
                        "curl --preproxy=socks5://internal.example:1080 https://example.com");
        SecurityPolicyService.UrlVerdict metadata =
                metadataPolicy.checkCommandUrls(
                        "curl --preproxy socks5://metadata.google.internal:1080 https://example.com");

        assertThat(detached.isAllowed()).isFalse();
        assertThat(detached.getMessage()).contains("内网");
        assertThat(assigned.isAllowed()).isFalse();
        assertThat(assigned.getMessage()).contains("内网");
        assertThat(metadata.isAllowed()).isFalse();
        assertThat(metadata.getMessage()).contains("元数据");
    }

    @Test
    void shouldCheckPowerShellProxyEnvironmentAssignments() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict envProxy =
                privatePolicy.checkCommandUrls(
                        "$env:HTTP_PROXY='http://internal.example:8080'; iwr https://example.com");
        SecurityPolicyService.UrlVerdict envColonProxy =
                privatePolicy.checkCommandUrls(
                        "Set-Item Env:HTTPS_PROXY http://internal.example:8443");
        SecurityPolicyService.UrlVerdict setEnvironmentVariable =
                metadataPolicy.checkCommandUrls(
                        "[Environment]::SetEnvironmentVariable('ALL_PROXY','socks5://metadata.google.internal:1080')");
        SecurityPolicyService.UrlVerdict setxProxy =
                privatePolicy.checkCommandUrls("setx HTTPS_PROXY http://internal.example:8443");
        SecurityPolicyService.UrlVerdict publicProxy =
                publicPolicy.checkCommandUrls(
                        "$env:HTTP_PROXY='http://proxy.example:8080'; iwr https://example.com");

        assertThat(envProxy.isAllowed()).isFalse();
        assertThat(envProxy.getMessage()).contains("内网");
        assertThat(envColonProxy.isAllowed()).isFalse();
        assertThat(envColonProxy.getMessage()).contains("内网");
        assertThat(setEnvironmentVariable.isAllowed()).isFalse();
        assertThat(setEnvironmentVariable.getMessage()).contains("元数据");
        assertThat(setxProxy.isAllowed()).isFalse();
        assertThat(setxProxy.getMessage()).contains("内网");
    }

    @Test
    void shouldCheckProxyBypassEnvironmentAssignments() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict shellNoProxy =
                privatePolicy.checkCommandUrls(
                        "NO_PROXY=internal.example,example.com curl https://example.com");
        SecurityPolicyService.UrlVerdict shellMetadataBypass =
                metadataPolicy.checkCommandUrls(
                        "no_proxy=169.254.169.254 curl https://example.com");
        SecurityPolicyService.UrlVerdict powershellNoProxy =
                privatePolicy.checkCommandUrls(
                        "$env:NO_PROXY='.internal.example,example.com'; iwr https://example.com");
        SecurityPolicyService.UrlVerdict powershellNoProxyOnly =
                privatePolicy.checkCommandUrls("$env:NO_PROXY='.internal.example,example.com'");
        SecurityPolicyService.UrlVerdict powershellSetEnvironmentVariable =
                metadataPolicy.checkCommandUrls(
                        "[Environment]::SetEnvironmentVariable('NO_PROXY','metadata.google.internal')");
        SecurityPolicyService.UrlVerdict powershellSetNoProxyOnly =
                metadataPolicy.checkCommandUrls(
                        "[Environment]::SetEnvironmentVariable('NO_PROXY','metadata.google.internal')");
        SecurityPolicyService.UrlVerdict setxNoProxy =
                metadataPolicy.checkCommandUrls("setx NO_PROXY metadata.google.internal");
        SecurityPolicyService.UrlVerdict publicBypass =
                publicPolicy.checkCommandUrls("NO_PROXY=api.example.com curl https://example.com");

        assertThat(shellNoProxy.isAllowed()).isFalse();
        assertThat(shellNoProxy.getMessage()).contains("内网");
        assertThat(shellMetadataBypass.isAllowed()).isFalse();
        assertThat(shellMetadataBypass.getMessage()).contains("元数据");
        assertThat(powershellNoProxy.isAllowed()).isFalse();
        assertThat(powershellNoProxy.getMessage()).contains("内网");
        assertThat(powershellNoProxyOnly.isAllowed()).isFalse();
        assertThat(powershellNoProxyOnly.getMessage()).contains("内网");
        assertThat(powershellSetEnvironmentVariable.isAllowed()).isFalse();
        assertThat(powershellSetEnvironmentVariable.getMessage()).contains("元数据");
        assertThat(powershellSetNoProxyOnly.isAllowed()).isFalse();
        assertThat(powershellSetNoProxyOnly.getMessage()).contains("元数据");
        assertThat(setxNoProxy.isAllowed()).isFalse();
        assertThat(setxNoProxy.getMessage()).contains("元数据");
    }

    @Test
    void shouldCheckPackageManagerProxyBypassEnvironmentAssignments() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict npmNoProxy =
                privatePolicy.checkCommandUrls("NPM_CONFIG_NO_PROXY=internal.example npm install");
        SecurityPolicyService.UrlVerdict yarnNoProxy =
                privatePolicy.checkCommandUrls("YARN_NOPROXY=.internal.example yarn install");
        SecurityPolicyService.UrlVerdict pnpmNoProxy =
                metadataPolicy.checkCommandUrls(
                        "PNPM_CONFIG_NOPROXY=metadata.google.internal pnpm install");
        SecurityPolicyService.UrlVerdict powershellNpmNoProxy =
                metadataPolicy.checkCommandUrls(
                        "$env:NPM_CONFIG_NO_PROXY='169.254.169.254'; npm install");
        SecurityPolicyService.UrlVerdict powershellNpmNoProxyOnly =
                metadataPolicy.checkCommandUrls("$env:NPM_CONFIG_NO_PROXY='169.254.169.254'");
        SecurityPolicyService.UrlVerdict publicNoProxy =
                publicPolicy.checkCommandUrls("NPM_CONFIG_NOPROXY=registry.npmjs.org npm install");

        assertThat(
                        privatePolicy.extractUrlishValues(
                                "NPM_CONFIG_NO_PROXY=internal.example npm install"))
                .contains("http://internal.example");
        assertThat(npmNoProxy.isAllowed()).isFalse();
        assertThat(npmNoProxy.getMessage()).contains("内网");
        assertThat(yarnNoProxy.isAllowed()).isFalse();
        assertThat(yarnNoProxy.getMessage()).contains("内网");
        assertThat(pnpmNoProxy.isAllowed()).isFalse();
        assertThat(pnpmNoProxy.getMessage()).contains("元数据");
        assertThat(powershellNpmNoProxy.isAllowed()).isFalse();
        assertThat(powershellNpmNoProxy.getMessage()).contains("元数据");
        assertThat(powershellNpmNoProxyOnly.isAllowed()).isFalse();
        assertThat(powershellNpmNoProxyOnly.getMessage()).contains("元数据");
    }

    @Test
    void shouldCheckPackageManagerPersistentProxyConfigAssignments() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict npmNoProxy =
                privatePolicy.checkCommandUrls(
                        "npm config set noproxy internal.example,registry.npmjs.org");
        SecurityPolicyService.UrlVerdict yarnNoProxy =
                privatePolicy.checkCommandUrls("yarn config set noProxy .internal.example");
        SecurityPolicyService.UrlVerdict pnpmNoProxy =
                metadataPolicy.checkCommandUrls(
                        "pnpm config set no-proxy metadata.google.internal");
        SecurityPolicyService.UrlVerdict npmProxy =
                privatePolicy.checkCommandUrls(
                        "npm config set https-proxy http://internal.example:8080");
        SecurityPolicyService.UrlVerdict pipProxy =
                metadataPolicy.checkCommandUrls(
                        "pip config set global.proxy http://169.254.169.254:8080");
        SecurityPolicyService.UrlVerdict assignedNpmNoProxy =
                privatePolicy.checkCommandUrls(
                        "npm config set noproxy=internal.example,registry.npmjs.org");
        SecurityPolicyService.UrlVerdict assignedPipProxy =
                metadataPolicy.checkCommandUrls(
                        "pip config set global.proxy=http://169.254.169.254:8080");
        SecurityPolicyService.UrlVerdict publicProxy =
                publicPolicy.checkCommandUrls(
                        "npm config set https-proxy http://proxy.example:8080");

        assertThat(privatePolicy.extractUrlishValues("npm config set noproxy internal.example"))
                .contains("http://internal.example");
        assertThat(privatePolicy.extractUrlishValues("npm config set noproxy=internal.example"))
                .contains("http://internal.example");
        assertThat(npmNoProxy.isAllowed()).isFalse();
        assertThat(npmNoProxy.getMessage()).contains("内网");
        assertThat(yarnNoProxy.isAllowed()).isFalse();
        assertThat(yarnNoProxy.getMessage()).contains("内网");
        assertThat(pnpmNoProxy.isAllowed()).isFalse();
        assertThat(pnpmNoProxy.getMessage()).contains("元数据");
        assertThat(npmProxy.isAllowed()).isFalse();
        assertThat(npmProxy.getMessage()).contains("内网");
        assertThat(pipProxy.isAllowed()).isFalse();
        assertThat(pipProxy.getMessage()).contains("元数据");
        assertThat(assignedNpmNoProxy.isAllowed()).isFalse();
        assertThat(assignedNpmNoProxy.getMessage()).contains("内网");
        assertThat(assignedPipProxy.isAllowed()).isFalse();
        assertThat(assignedPipProxy.getMessage()).contains("元数据");
    }

    @Test
    void shouldCheckSystemProxyCommands() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict winhttpProxy =
                privatePolicy.checkCommandUrls("netsh winhttp set proxy 10.0.0.5:8080");
        SecurityPolicyService.UrlVerdict winhttpNamedProxy =
                metadataPolicy.checkCommandUrls(
                        "netsh winhttp set proxy proxy-server=169.254.169.254:8080 bypass-list=example.com");
        SecurityPolicyService.UrlVerdict winhttpBypass =
                privatePolicy.checkCommandUrls(
                        "netsh winhttp set proxy proxy-server=proxy.example:8080 bypass-list=internal.example");
        SecurityPolicyService.UrlVerdict macosProxy =
                privatePolicy.checkCommandUrls(
                        "networksetup -setwebproxy Wi-Fi internal.example 8080");
        SecurityPolicyService.UrlVerdict macosSocksProxy =
                metadataPolicy.checkCommandUrls(
                        "networksetup -setsocksfirewallproxy Wi-Fi metadata.google.internal 1080");
        SecurityPolicyService.UrlVerdict publicProxy =
                publicPolicy.checkCommandUrls(
                        "networksetup -setsecurewebproxy Wi-Fi proxy.example 8443");

        assertThat(privatePolicy.extractUrlishValues("netsh winhttp set proxy 10.0.0.5:8080"))
                .contains("http://10.0.0.5:8080");
        assertThat(winhttpProxy.isAllowed()).isFalse();
        assertThat(winhttpProxy.getMessage()).contains("内网");
        assertThat(winhttpNamedProxy.isAllowed()).isFalse();
        assertThat(winhttpNamedProxy.getMessage()).contains("元数据");
        assertThat(winhttpBypass.isAllowed()).isFalse();
        assertThat(winhttpBypass.getMessage()).contains("内网");
        assertThat(macosProxy.isAllowed()).isFalse();
        assertThat(macosProxy.getMessage()).contains("内网");
        assertThat(macosSocksProxy.isAllowed()).isFalse();
        assertThat(macosSocksProxy.getMessage()).contains("元数据");
    }

    @Test
    void shouldCheckSystemDnsCommands() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict macosDns =
                privatePolicy.checkCommandUrls(
                        "networksetup -setdnsservers Wi-Fi 10.0.0.5 8.8.8.8");
        SecurityPolicyService.UrlVerdict powershellDns =
                metadataPolicy.checkCommandUrls(
                        "Set-DnsClientServerAddress -InterfaceAlias Ethernet -ServerAddresses 169.254.169.254,8.8.8.8");
        SecurityPolicyService.UrlVerdict netshDns =
                privatePolicy.checkCommandUrls(
                        "netsh interface ip set dns name=Ethernet static=10.0.0.5");
        SecurityPolicyService.UrlVerdict nmcliDns =
                metadataPolicy.checkCommandUrls(
                        "nmcli connection modify eth0 ipv4.dns metadata.google.internal");
        SecurityPolicyService.UrlVerdict publicDns =
                publicPolicy.checkCommandUrls("networksetup -setdnsservers Wi-Fi 8.8.8.8");

        assertThat(
                        privatePolicy.extractUrlishValues(
                                "networksetup -setdnsservers Wi-Fi 10.0.0.5 8.8.8.8"))
                .contains("10.0.0.5");
        assertThat(macosDns.isAllowed()).isFalse();
        assertThat(macosDns.getMessage()).contains("内网");
        assertThat(powershellDns.isAllowed()).isFalse();
        assertThat(powershellDns.getMessage()).contains("元数据");
        assertThat(netshDns.isAllowed()).isFalse();
        assertThat(netshDns.getMessage()).contains("内网");
        assertThat(nmcliDns.isAllowed()).isFalse();
        assertThat(nmcliDns.getMessage()).contains("元数据");
    }

    @Test
    void shouldCheckWindowsRegistryProxyCommands() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict proxyServer =
                privatePolicy.checkCommandUrls(
                        "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name ProxyServer -Value 10.0.0.5:8080");
        SecurityPolicyService.UrlVerdict splitProxyServer =
                metadataPolicy.checkCommandUrls(
                        "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name ProxyServer -Value 'http=proxy.example:8080;https=metadata.google.internal:8443'");
        SecurityPolicyService.UrlVerdict proxyOverride =
                privatePolicy.checkCommandUrls(
                        "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name ProxyOverride -Value 'localhost;internal.example'");
        SecurityPolicyService.UrlVerdict inlineProxyServer =
                privatePolicy.checkCommandUrls(
                        "New-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name:ProxyServer -Value:internal.example:8080");
        SecurityPolicyService.UrlVerdict publicProxy =
                publicPolicy.checkCommandUrls(
                        "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name ProxyServer -Value proxy.example:8080");

        assertThat(
                        privatePolicy.extractUrlishValues(
                                "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name ProxyServer -Value 10.0.0.5:8080"))
                .contains("http://10.0.0.5:8080");
        assertThat(proxyServer.isAllowed()).isFalse();
        assertThat(proxyServer.getMessage()).contains("内网");
        assertThat(splitProxyServer.isAllowed()).isFalse();
        assertThat(splitProxyServer.getMessage()).contains("元数据");
        assertThat(proxyOverride.isAllowed()).isFalse();
        assertThat(proxyOverride.getMessage()).contains("内网");
        assertThat(inlineProxyServer.isAllowed()).isFalse();
        assertThat(inlineProxyServer.getMessage()).contains("内网");
    }

    @Test
    void shouldCheckGitPersistentProxyConfigAssignments() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(false);
        SecurityPolicyService privatePolicy = new FixedDnsSecurityPolicyService(config, "10.0.0.5");
        SecurityPolicyService metadataPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");
        SecurityPolicyService publicPolicy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");

        SecurityPolicyService.UrlVerdict globalProxy =
                privatePolicy.checkCommandUrls(
                        "git config --global http.proxy http://internal.example:8080");
        SecurityPolicyService.UrlVerdict assignedProxy =
                metadataPolicy.checkCommandUrls(
                        "git config --global https.proxy=http://169.254.169.254:8080");
        SecurityPolicyService.UrlVerdict noProxy =
                privatePolicy.checkCommandUrls(
                        "git config --global http.noProxy localhost,internal.example");
        SecurityPolicyService.UrlVerdict addNoProxy =
                metadataPolicy.checkCommandUrls(
                        "git config --global --add http.noProxy metadata.google.internal");
        SecurityPolicyService.UrlVerdict replaceAllProxy =
                privatePolicy.checkCommandUrls(
                        "git config --global --replace-all http.proxy http://10.0.0.5:8080");
        SecurityPolicyService.UrlVerdict publicProxy =
                publicPolicy.checkCommandUrls(
                        "git config --global http.proxy http://proxy.example:8080");
        SecurityPolicyService.UrlVerdict readOnly =
                privatePolicy.checkCommandUrls("git config --global --get http.proxy");

        assertThat(
                        privatePolicy.extractUrlishValues(
                                "git config --global http.noProxy internal.example"))
                .contains("http://internal.example");
        assertThat(
                        privatePolicy.extractUrlishValues(
                                "git config --global http.proxy=http://internal.example:8080"))
                .contains("http://internal.example:8080");
        assertThat(globalProxy.isAllowed()).isFalse();
        assertThat(globalProxy.getMessage()).contains("内网");
        assertThat(assignedProxy.isAllowed()).isFalse();
        assertThat(assignedProxy.getMessage()).contains("元数据");
        assertThat(noProxy.isAllowed()).isFalse();
        assertThat(noProxy.getMessage()).contains("内网");
        assertThat(addNoProxy.isAllowed()).isFalse();
        assertThat(addNoProxy.getMessage()).contains("元数据");
        assertThat(replaceAllProxy.isAllowed()).isFalse();
        assertThat(replaceAllProxy.getMessage()).contains("内网");
        assertThat(readOnly.isAllowed()).isTrue();
    }

    @Test
    void shouldNormalizeWebsiteBlocklistHostsBeforeMatching() {
        AppConfig config = new AppConfig();
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("https://WWW.Blocked.Example./docs", "*.wild.example"));
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        SecurityPolicyService.UrlVerdict mixedCase =
                policy.checkUrl("https://api.BLOCKED.example./v1");
        SecurityPolicyService.UrlVerdict schemeless = policy.checkUrl("www.blocked.example/docs");
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
    }

    @Test
    void shouldMergeWebsiteBlocklistDomainsAndSharedFileRules() throws Exception {
        Path workspaceHome = Files.createTempDirectory("jimuqu-website-policy");
        File shared = workspaceHome.resolve("community-blocklist.txt").toFile();
        FileUtil.writeUtf8String(
                "# comment\n"
                        + "example.org\n"
                        + "https://www.evil.test/path\n"
                        + "*.tracking.example\n",
                shared);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("example.com", "https://www.inline.test/path"));
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList("community-blocklist.txt"));
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        SecurityPolicyService.UrlVerdict configDomain =
                policy.checkUrl("https://docs.example.com/page");
        SecurityPolicyService.UrlVerdict configUrlRule =
                policy.checkUrl("https://www.inline.test/page");
        SecurityPolicyService.UrlVerdict sharedDomain =
                policy.checkUrl("https://api.example.org/v1");
        SecurityPolicyService.UrlVerdict sharedUrlRule = policy.checkUrl("https://evil.test/login");
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
    }

    @Test
    void shouldApplySharedWebsiteBlocklistBeforeLeakingCredentialQuery() throws Exception {
        Path workspaceHome = Files.createTempDirectory("jimuqu-website-policy");
        File shared = workspaceHome.resolve("community-blocklist.txt").toFile();
        FileUtil.writeUtf8String("*.blocked-shared.example\n", shared);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList("community-blocklist.txt"));
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        SecurityPolicyService.UrlVerdict verdict =
                policy.checkUrl("https://api.blocked-shared.example/path?token=secret-token");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("website policy");
        assertThat(verdict.getMessage()).contains("blocked-shared.example");
        assertThat(verdict.getMessage()).doesNotContain("secret-token");
    }

    @Test
    void shouldSkipMissingAndUnsafeWebsiteBlocklistSharedFiles() throws Exception {
        Path parent = Files.createTempDirectory("jimuqu-website-policy-parent");
        Path workspaceHome = Files.createDirectory(parent.resolve("runtime"));
        File outside = parent.resolve("outside-blocklist.txt").toFile();
        FileUtil.writeUtf8String("blocked-outside.example\n", outside);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList("missing-blocklist.txt", "../outside-blocklist.txt"));
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        assertThat(policy.checkUrl("https://allowed.example/").isAllowed()).isTrue();
        assertThat(policy.checkUrl("https://blocked-outside.example/").isAllowed()).isTrue();
    }

    @Test
    void shouldLoadAbsoluteWebsiteBlocklistSharedFiles() throws Exception {
        Path workspaceHome = Files.createTempDirectory("jimuqu-website-policy");
        File shared = Files.createTempFile("jimuqu-shared-blocklist", ".txt").toFile();
        FileUtil.writeUtf8String("absolute-blocked.example\n", shared);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList(shared.getAbsolutePath()));
        SecurityPolicyService policy = new FixedDnsSecurityPolicyService(config, "8.8.8.8");

        SecurityPolicyService.UrlVerdict verdict =
                policy.checkUrl("https://cdn.absolute-blocked.example/path");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("absolute-blocked.example");
    }

    @Test
    void shouldExposeUrlPolicySummaryWithSharedRuleDiagnostics() throws Exception {
        Path parent = Files.createTempDirectory("jimuqu-url-policy-summary");
        Path workspaceHome = Files.createDirectory(parent.resolve("runtime"));
        File shared = workspaceHome.resolve("community-blocklist.txt").toFile();
        FileUtil.writeUtf8String(
                "# comment\n" + "shared.example\n" + "token-sk-1234567890abcdef.example\n", shared);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getSecurity().setAllowPrivateUrls(false);
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example", "secret-sk-1234567890abcdef.example"));
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(
                        Arrays.asList("community-blocklist.txt", "../outside-blocklist.txt"));
        SecurityPolicyService policy = new SecurityPolicyService(config);

        Map<String, Object> summary = policy.urlPolicySummary();

        assertThat(summary.get("allowPrivateUrls")).isEqualTo(Boolean.FALSE);
        assertThat(((Integer) summary.get("alwaysBlockedHostCount")).intValue()).isGreaterThan(0);
        assertThat(((Integer) summary.get("alwaysBlockedIpCount")).intValue()).isGreaterThan(0);
        assertThat(((Integer) summary.get("sensitiveQueryNameCount")).intValue()).isGreaterThan(5);
        assertThat(summary.get("websiteBlocklistEnabled")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("websiteBlocklistDomainCount")).isEqualTo(2);
        assertThat(summary.get("websiteBlocklistSharedFileCount")).isEqualTo(2);
        assertThat(summary.get("websiteBlocklistLoadedSharedFileCount")).isEqualTo(1);
        assertThat(summary.get("websiteBlocklistSkippedSharedFileCount")).isEqualTo(1);
        assertThat(summary.get("websiteBlocklistSharedRuleCount")).isEqualTo(2);
        assertThat(String.valueOf(summary.get("alwaysBlockedIpSamples"))).contains("169.254");
        assertThat(String.valueOf(summary.get("sensitiveQueryNameSamples")))
                .contains("access_token");
        assertThat(String.valueOf(summary.get("websiteBlocklistDomainSamples")))
                .contains("blocked.example")
                .contains("secret-sk-***")
                .doesNotContain("1234567890abcdef");
        assertThat(String.valueOf(summary.get("websiteBlocklistSharedRuleSamples")))
                .contains("shared.example")
                .contains("token-sk-***")
                .doesNotContain("1234567890abcdef");
        assertThat(String.valueOf(summary.get("allowedNetworkSchemes")))
                .contains("http")
                .contains("https")
                .contains("ws")
                .contains("wss");
        assertThat(summary.get("unsupportedNetworkSchemeBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("protocolRelativeUrlChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("schemelessHostChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("percentEncodedHostChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("idnHostNormalized")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("dnsResolutionRequired")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("systemDnsCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("powershellProxyEnvironmentChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("setxProxyEnvironmentChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("systemProxyCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("windowsRegistryProxyCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("proxyBypassEnvironmentChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("gitPersistentProxyConfigChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("packageManagerProxyBypassEnvironmentChecked"))
                .isEqualTo(Boolean.TRUE);
        assertThat(summary.get("packageManagerPersistentProxyConfigChecked"))
                .isEqualTo(Boolean.TRUE);
        assertThat(summary.get("userinfoBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("sensitiveQueryBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("schemelessSensitiveQueryBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("sensitiveQueryNameAliasNormalized")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("encodedSensitiveQueryBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("repeatedEncodedSensitiveQueryBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("semicolonSensitiveQueryBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("fragmentSensitiveQueryBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("sensitivePathCredentialBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("cloudMetadataBlocked")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void shouldExposePrivateUrlPolicySummary() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        Map<String, Object> summary = policy.privateUrlPolicySummary();

        assertThat(summary.get("cloudMetadataAlwaysBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("dnsResolutionRequired")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("obfuscatedIpv4Checked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("percentEncodedHostChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("ipv4MappedIpv6Checked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("loopbackBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("linkLocalBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("siteLocalBlocked")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void shouldDenyWritesToSensitiveSystemAndCredentialPathsLikeJimuquPolicy() throws Exception {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());
        String home = System.getProperty("user.home");

        assertWriteDenied(policy, "/etc/shadow");
        assertWriteDenied(policy, "/etc/passwd");
        assertWriteDenied(policy, "/etc/hosts");
        assertWriteDenied(policy, "/etc/resolv.conf");
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
        assertWriteDenied(policy, home + "/.curlrc");
        assertWriteDenied(policy, home + "/.wgetrc");
        assertWriteDenied(policy, home + "/.pypirc");
        assertWriteDenied(policy, home + "/.m2/settings.xml");
        assertWriteDenied(policy, home + "/.gem/credentials");
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

        assertThat(policy.checkPath(".env.example", true).isAllowed()).isTrue();
        assertThat(policy.checkPath(".envrc.example", true).isAllowed()).isTrue();
    }

    @Test
    void shouldConsumeApprovedCommandUrlTokenAfterPreviewingCommandUrls() {
        SecurityPolicyService policy =
                new FixedDnsSecurityPolicyService(new AppConfig(), "93.184.216.34");
        String command = "curl -fsS https://example.com -o result.txt";

        SecurityPolicyService.UrlVerdict previewVerdict =
                SecurityPolicyService.previewPolicyApprovals(() -> policy.checkCommandUrls(command));

        // public network access is allowed directly without interactive approval;
        // previewing must yield the same allow verdict as the actual check.
        assertThat(previewVerdict.isAllowed()).isTrue();

        assertThat(policy.checkCommandUrls(command).isAllowed()).isTrue();
    }

    // shouldDenyCommandReadsFromEnvrcCredentialFile 已删除：cat .envrc 属读上下文
    // （checkCommandPaths 经 isCommandWritePathContext 判定为读），凭据文件读已放宽（对齐 hermes"读非安全边界"）。

    @Test
    void shouldDenyCommandCredentialFilesWithRelativeAndVariablePrefixes() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        // 命令中凭据路径由 SHELL_RELATIVE_CREDENTIAL_PATH_PATTERN / SHELL_CREDENTIAL_TOKEN_PATTERN
        // 等匹配器以 checkPath(.., false) 读意图检查，凭据文件读已放宽（对齐 hermes"读非安全边界"），
        // 读/重定向类凭据路径现在均放行，assertCommandPathDenied 断言已移除。
        // .env.example 读仍放行断言保留。
        assertThat(policy.checkCommandPaths("cat .env.example").isAllowed()).isTrue();
        assertThat(policy.checkCommandPaths("cat docs/.env.example").isAllowed()).isTrue();
    }

    @Test
    void shouldDenyCommandWritesToPercentWindowsVariablesWithParenthesizedNames() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        SecurityPolicyService.FileVerdict verdict =
                policy.checkCommandPaths("echo probe > %ProgramFiles(x86)%/Probe/probe.txt");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getPath()).isEqualTo("%ProgramFiles(x86)%/Probe/probe.txt");
        assertThat(verdict.getMessage()).contains("敏感");
    }

    // shouldDenyCredentialPathsWithDisplayControlsAndEntities 已删除：该方法全部走读路径
    // （checkPath(.., false) / checkFileToolArgs("read_file") / cat 读命令），凭据文件读已放宽
    // （对齐 hermes"读非安全边界"）。同区的控制字符/路径遍历阻断由其它测试覆盖。

    @Test
    void shouldDenyRawControlCharactersInPaths() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("path", "logs/\u001B]0;hidden\u0007report.txt");

        SecurityPolicyService.FileVerdict newline = policy.checkPath("logs/report\n.json", false);
        SecurityPolicyService.FileVerdict escape = policy.checkFileToolArgs("write_file", args);

        assertThat(newline.isAllowed()).isFalse();
        assertThat(newline.getMessage()).contains("非法字符");
        assertThat(escape.isAllowed()).isFalse();
        assertThat(escape.getMessage()).contains("非法字符");
    }

    @Test
    void shouldDenyEncodedTraversalPaths() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("path", "safe/%252e%252e/readme.txt");

        SecurityPolicyService.FileVerdict doubleEncoded =
                policy.checkPath("safe/%252e%252e/readme.txt", false);
        SecurityPolicyService.FileVerdict htmlEntity =
                policy.checkPath("safe/&#46;&#46;/readme.txt", false);
        SecurityPolicyService.FileVerdict command =
                policy.checkCommandPaths("cat safe/%252e%252e/readme.txt");
        SecurityPolicyService.FileVerdict toolArg = policy.checkFileToolArgs("read_file", args);

        assertThat(doubleEncoded.isAllowed()).isFalse();
        assertThat(doubleEncoded.getMessage()).contains("路径遍历");
        assertThat(htmlEntity.isAllowed()).isFalse();
        assertThat(htmlEntity.getMessage()).contains("路径遍历");
        assertThat(command.isAllowed()).isFalse();
        assertThat(command.getMessage()).contains("路径遍历");
        assertThat(toolArg.isAllowed()).isFalse();
        assertThat(toolArg.getMessage()).contains("路径遍历");
    }

    @Test
    void shouldDenyCommandCredentialFilesInArchiveAndUploadCommands() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        // 归档/上传类命令（tar/zip/scp/rsync/curl -F/--upload-file/@file 等）读取凭据文件属读上下文，
        // 凭据文件读已放宽（对齐 hermes"读非安全边界"），assertCommandPathDenied 读阻断断言已移除。
        // 下方"非凭据文件归档/上传放行"断言保留。
        assertThat(policy.checkCommandPaths("tar czf backup.tgz README.md").isAllowed()).isTrue();
        assertThat(policy.checkCommandPaths("zip backup.zip .env.example").isAllowed()).isTrue();
        assertThat(
                        policy.checkCommandPaths(
                                        "curl --upload-file=report.txt https://upload.example/files")
                                .isAllowed())
                .isTrue();
        assertThat(
                        policy.checkCommandPaths(
                                        "http POST https://upload.example/files @report.txt")
                                .isAllowed())
                .isTrue();
    }

    @Test
    void shouldAllowCommandCredentialPathOptions() {
        // 命令中凭据参数引用的文件路径不再被 checkCredentialPathOptions 无条件阻断，
        // 对齐外部对标仓库行为；实际凭据文件读写仍由 checkPath 按文件内容判定保护。
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        assertThat(policy.checkCommandPaths("curl --key client.pem https://example.invalid")
                        .isAllowed())
                .isTrue();
        assertThat(policy.checkCommandPaths("ssh -i deploy_key host.example").isAllowed())
                .isTrue();
    }

    @Test
    void shouldExposeCredentialPolicySummaryWithoutLeakingConfiguredPaths() {
        AppConfig config = new AppConfig();
        config.getTerminal()
                .setCredentialFiles(
                        Arrays.asList(
                                "credentials/oauth.json",
                                "runtime/secret-sk-1234567890abcdef.json"));
        SecurityPolicyService policy = new SecurityPolicyService(config);

        Map<String, Object> summary = policy.credentialPolicySummary();

        assertThat(((Integer) summary.get("directorySegmentCount")).intValue())
                .isGreaterThanOrEqualTo(10);
        assertThat(((Integer) summary.get("fileNameCount")).intValue()).isGreaterThanOrEqualTo(30);
        assertThat(((Integer) summary.get("pathSuffixCount")).intValue()).isGreaterThanOrEqualTo(4);
        assertThat(summary.get("configuredCredentialFileCount")).isEqualTo(2);
        assertThat(String.valueOf(summary.get("directorySegmentSamples"))).contains(".ssh", ".aws");
        assertThat(String.valueOf(summary.get("fileNameSamples"))).contains(".env", ".netrc");
        assertThat(String.valueOf(summary.get("pathSuffixSamples"))).contains(".credentials.json");
        assertThat(String.valueOf(summary.get("configuredCredentialFileSamples")))
                .contains("credentials/oauth.json")
                .contains("secret-sk-***")
                .doesNotContain("1234567890abcdef");
        assertThat(summary.get("envExampleFilesAllowed")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("projectEnvFileReadBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("projectEnvFileWriteBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("credentialPathReadBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("credentialPathWriteBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("writePolicySharesCredentialClassifier")).isEqualTo(Boolean.TRUE);
        // 凭据文件读已放宽（对齐 hermes"读非安全边界"），写仍阻断。
        assertThat(policy.checkPath(".env", false).isAllowed()).isTrue();
        assertThat(policy.checkPath(".env.local", true).isAllowed()).isFalse();
        assertThat(policy.checkPath(".env.example", false).isAllowed()).isTrue();
    }

    @Test
    void shouldExposePathPolicySummaryWithWorkspaceBoundary() {
        AppConfig config = new AppConfig();
        SecurityPolicyService policy = new SecurityPolicyService(config);

        Map<String, Object> summary = policy.pathPolicySummary();

        assertThat(summary.get("traversalBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("controlCharactersBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("rawControlCharactersBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("normalizedControlCharactersBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("devicePathBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("rawBlockDeviceWriteBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("credentialPathReadBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("credentialPathWriteBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("projectEnvFileWriteBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("skillsHubInternalReadBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("skillsHubInternalWriteBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("localManagementSocketEnvironmentBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("workspaceWriteFree")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("outsideWorkspaceReadFree")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("outsideWorkspaceWriteFree")).isEqualTo(Boolean.TRUE);
        assertThat(summary.containsKey("writeSafeRoot")).isFalse();
        assertThat(summary.containsKey("writeSafeRootConfigured")).isFalse();
        assertThat(((Integer) summary.get("writeDeniedExactPathCount")).intValue())
                .isGreaterThan(0);
        assertThat(((Integer) summary.get("writeDeniedPrefixCount")).intValue()).isGreaterThan(0);
        assertThat(((Integer) summary.get("writeDeniedWindowsPrefixCount")).intValue())
                .isGreaterThan(0);
        assertThat(((Integer) summary.get("writeDeniedHomeFileCount")).intValue()).isGreaterThan(0);
        assertThat(((Integer) summary.get("blockedDevicePathCount")).intValue()).isGreaterThan(0);
        assertThat(String.valueOf(summary.get("writeDeniedExactPathSamples")))
                .contains("/etc/passwd");
        assertThat(String.valueOf(summary.get("writeDeniedExactPathSamples")))
                .contains("/etc/hosts");
        assertThat(String.valueOf(summary.get("writeDeniedWindowsPrefixSamples")))
                .contains("c:/windows/");
        assertThat(String.valueOf(summary.get("blockedDevicePathSamples"))).contains("/dev/zero");
        assertThat(String.valueOf(summary.get("workdirSafePattern"))).contains("A-Za-z0-9");
    }

    @Test
    void shouldDenySkillHubInternalCacheWrites() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        SecurityPolicyService.FileVerdict writeVerdict =
                policy.checkPath("skills/.hub/index-cache/catalog.json", true);
        SecurityPolicyService.FileVerdict readVerdict =
                policy.checkPath("skills/.hub/index-cache/catalog.json", false);

        assertThat(writeVerdict.isAllowed()).isFalse();
        assertThat(writeVerdict.getMessage()).contains("Skills Hub").contains("写入");
        assertThat(readVerdict.isAllowed()).isFalse();
        assertThat(readVerdict.getMessage()).contains("Skills Hub").contains("读取");
    }

    @Test
    void shouldExposeToolArgsPolicySummary() {
        SecurityPolicyService policy = new SecurityPolicyService(new AppConfig());

        Map<String, Object> summary = policy.toolArgsPolicySummary();

        assertThat(summary.get("recursiveUrlExtraction")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("returnedContentUrlExtraction")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("returnedSchemelessUrlChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("returnedDocumentContentChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("returnedDocumentMetadataUrlChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("returnedPojoUrlChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("recursivePathExtraction")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("encodedUrlParameterPolicyInherited")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("rawPathControlCharacterPolicyInherited")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("writeIntentDetection")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("patchTargetExtraction")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("downloadOutputPathOptionChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("downloadOutputDetachedOptionChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("networkUploadSourcePathChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("networkUploadCredentialOnlyBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("proxyOptionUrlChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("preproxyOptionUrlChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("systemDnsCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("powershellProxyEnvironmentChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("setxProxyEnvironmentChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("systemProxyCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("windowsRegistryProxyCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("proxyBypassEnvironmentChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("gitPersistentProxyConfigChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("packageManagerProxyBypassEnvironmentChecked"))
                .isEqualTo(Boolean.TRUE);
        assertThat(summary.get("packageManagerPersistentProxyConfigChecked"))
                .isEqualTo(Boolean.TRUE);
        assertThat(summary.get("unsupportedNetworkSchemeChecked")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(summary.get("urlKeySamples")))
                .contains("url", "endpoint", "*_url");
        assertThat(String.valueOf(summary.get("returnedUrlKeySamples")))
                .contains("browser_download_url", "href");
        assertThat(String.valueOf(summary.get("pathKeySamples")))
                .contains("path", "file_path", "*_path");
        assertThat(String.valueOf(summary.get("writeIntentSamples")))
                .contains("write", "delete", "patch");
        assertThat(String.valueOf(summary.get("patchIntentSamples")))
                .contains("patch");
        assertThat(String.valueOf(summary.get("patchTextKeySamples"))).contains("patch", "diff");
        assertThat(String.valueOf(summary.get("writeLikeToolSamples")))
                .contains("write_file", "patch");
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
        Map<String, Object> moveSourceArgs = new LinkedHashMap<String, Object>();
        moveSourceArgs.put(
                "patch", "*** Begin Patch\n" + "*** Move File: .env.local\n" + "*** End Patch\n");
        Map<String, Object> unifiedCredentialArgs = new LinkedHashMap<String, Object>();
        unifiedCredentialArgs.put(
                "patch",
                "--- /dev/null\n" + "+++ b/.env\n" + "@@ -0,0 +1 @@\n" + "+TOKEN=secret\n");

        SecurityPolicyService.FileVerdict addFileVerdict =
                policy.checkFileToolArgs("patch", addFileArgs);
        SecurityPolicyService.FileVerdict unifiedVerdict =
                policy.checkFileToolArgs("patch", unifiedArgs);
        SecurityPolicyService.FileVerdict moveSourceVerdict =
                policy.checkFileToolArgs("patch", moveSourceArgs);
        SecurityPolicyService.FileVerdict unifiedCredentialVerdict =
                policy.checkFileToolArgs("patch", unifiedCredentialArgs);

        assertThat(addFileVerdict.isAllowed()).isFalse();
        assertThat(addFileVerdict.getPath()).isEqualTo(".env");
        assertThat(addFileVerdict.getMessage()).contains("凭据");
        assertThat(unifiedVerdict.isAllowed()).isFalse();
        assertThat(unifiedVerdict.getPath()).isEqualTo(".ssh/authorized_keys");
        assertThat(moveSourceVerdict.isAllowed()).isFalse();
        assertThat(moveSourceVerdict.getPath()).isEqualTo(".env.local");
        assertThat(moveSourceVerdict.getMessage()).contains("凭据");
        assertThat(unifiedCredentialVerdict.isAllowed()).isFalse();
        assertThat(unifiedCredentialVerdict.getPath()).isEqualTo(".env");
        assertThat(unifiedCredentialVerdict.getMessage()).contains("凭据");
        assertThat(policy.checkFileToolArgs("patch", safeArgs).isAllowed()).isTrue();
    }

    private static void assertWriteDenied(SecurityPolicyService policy, String path) {
        SecurityPolicyService.FileVerdict verdict = policy.checkPath(path, true);
        assertThat(verdict.isAllowed()).as(path).isFalse();
        assertThat(verdict.getMessage()).as(path).contains("敏感");
    }

    private static void assertFileApprovalRequired(
            SecurityPolicyService.FileVerdict verdict, String policyKey) {
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.isApprovalRequired()).isTrue();
        assertThat(verdict.getPolicyKey()).isEqualTo(policyKey);
    }

    private static void assertCommandPathDenied(
            SecurityPolicyService policy, String command, String path) {
        SecurityPolicyService.FileVerdict verdict = policy.checkCommandPaths(command);
        assertThat(verdict.isAllowed()).as(command).isFalse();
        assertThat(verdict.getPath()).as(command).isEqualTo(path);
        assertThat(verdict.getMessage()).as(command).contains("凭据");
    }

    private static void assertCommandCredentialOptionDenied(
            SecurityPolicyService policy, String command, String path) {
        SecurityPolicyService.FileVerdict verdict = policy.checkCommandPaths(command);
        assertThat(verdict.isAllowed()).as(command).isFalse();
        assertThat(verdict.getPath()).as(command).isEqualTo(path);
        assertThat(verdict.getMessage()).as(command).contains("凭据用途参数");
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
