package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

public class BoundedAttachmentIOTest {
    @Test
    void shouldBlockPrivateDownloadUrlBeforeNetworkAccess() {
        SecurityPolicyService securityPolicyService =
                new FixedDnsSecurityPolicyService(new AppConfig(), "127.0.0.1");

        assertThatThrownBy(
                        () ->
                                BoundedAttachmentIO.downloadHutool(
                                        "https://cdn.example.test/file.png",
                                        1000,
                                        BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                        securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attachment download URL blocked")
                .hasMessageContaining("内网");
    }

    @Test
    void shouldBlockMetadataDownloadUrlBeforeNetworkAccess() {
        SecurityPolicyService securityPolicyService =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");

        assertThatThrownBy(
                        () ->
                                BoundedAttachmentIO.downloadHutool(
                                        "https://media.example.test/download?id=1",
                                        1000,
                                        BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                        securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attachment download URL blocked")
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
