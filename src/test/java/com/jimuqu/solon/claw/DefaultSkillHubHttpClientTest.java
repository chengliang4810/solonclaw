package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

public class DefaultSkillHubHttpClientTest {
    @Test
    void shouldBlockPrivateSkillHubUrlsBeforeNetworkAccess() {
        DefaultSkillHubHttpClient client =
                new DefaultSkillHubHttpClient(
                        new FixedDnsSecurityPolicyService(new AppConfig(), "127.0.0.1"));

        assertThatThrownBy(
                        () ->
                                client.getText(
                                        "https://skills.example/.well-known/skills/index.json",
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skills Hub HTTP URL blocked")
                .hasMessageContaining("内网");
    }

    @Test
    void shouldBlockUnsafeOsvEndpointBeforePostingJson() {
        DefaultSkillHubHttpClient client =
                new DefaultSkillHubHttpClient(
                        new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254"));

        assertThatThrownBy(() -> client.postJson("https://api.osv.dev/v1/query", null, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skills Hub HTTP URL blocked")
                .hasMessageContaining("169.254.169.254");
    }

    private static class FixedDnsSecurityPolicyService extends SecurityPolicyService {
        private final String ip;

        private FixedDnsSecurityPolicyService(AppConfig appConfig, String ip) {
            super(appConfig);
            this.ip = ip;
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            return new InetAddress[] {InetAddress.getByName(ip)};
        }
    }
}
