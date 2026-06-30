package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import org.junit.jupiter.api.Test;

/** 验证安全策略测试支撑类保留本地回环放行和危险目标阻断语义。 */
public class SecurityPolicyTestSupportTest {
    /** 本地测试服务器应可访问，但云元数据地址仍必须被阻断。 */
    @Test
    void shouldAllowLoopbackTestServersWhileStillBlockingMetadataUrls() {
        SecurityPolicyService policy =
                new SecurityPolicyTestSupport.AllowLocalButBlockMetadataSecurityPolicyService(
                        new AppConfig());

        SecurityPolicyService.UrlVerdict local =
                policy.checkUrlBlockingPrivate("http://127.0.0.1:18080/status");
        SecurityPolicyService.UrlVerdict metadata =
                policy.checkUrlBlockingPrivate("http://169.254.169.254/latest/meta-data/");

        assertThat(local.isAllowed()).isTrue();
        assertThat(metadata.isAllowed()).isFalse();
        assertThat(metadata.getMessage()).contains("169.254.169.254");
    }

    /** 固定 DNS 测试桩应将任意主机解析为指定 IP，便于覆盖解析后阻断路径。 */
    @Test
    void shouldResolveAnyHostToFixedIpForBoundaryTests() {
        SecurityPolicyService policy =
                new SecurityPolicyTestSupport.FixedDnsSecurityPolicyService(
                        new AppConfig(), "169.254.169.254");

        SecurityPolicyService.UrlVerdict verdict =
                policy.checkUrl("https://cdn.example.test/archive.zip");

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("169.254.169.254");
    }
}
