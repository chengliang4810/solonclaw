package com.jimuqu.solon.claw.skillhub.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkillBundlePathSupportTest {
    @Test
    void shouldRedactUnsafeBundlePathMessages() {
        String unsafe = "C:/Users/chengliang/.ssh/token=ghp_skillbundle12345/secret.txt";

        assertThatThrownBy(() -> SkillBundlePathSupport.normalizeBundlePath(unsafe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe bundle path")
                .hasMessageContaining("secret.txt")
                .hasMessageNotContaining("C:/Users/chengliang")
                .hasMessageNotContaining(".ssh")
                .hasMessageNotContaining("ghp_skillbundle12345");
    }

    @Test
    void shouldRedactUnsafeSkillNameMessages() {
        String unsafe = "../private-token=ghp_skillname12345";

        assertThatThrownBy(() -> SkillBundlePathSupport.normalizeSkillName(unsafe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe skill name")
                .hasMessageNotContaining("..")
                .hasMessageNotContaining("ghp_skillname12345");
    }
}
