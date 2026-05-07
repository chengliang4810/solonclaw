package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.skillhub.model.InstallDecision;
import com.jimuqu.solon.claw.skillhub.model.ScanResult;
import com.jimuqu.solon.claw.skillhub.service.DefaultSkillGuardService;
import org.junit.jupiter.api.Test;

public class DefaultSkillGuardServiceTest {
    private final DefaultSkillGuardService service = new DefaultSkillGuardService();

    @Test
    void shouldAllowBuiltinDangerousByPolicyLikeHermes() {
        InstallDecision decision = service.shouldAllowInstall(scan("builtin", "dangerous"), false);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getReason()).contains("builtin");
    }

    @Test
    void shouldBlockDangerousTrustedAndCommunityWithoutForceLikeHermes() {
        InstallDecision trusted = service.shouldAllowInstall(scan("trusted", "dangerous"), false);
        InstallDecision community = service.shouldAllowInstall(scan("community", "dangerous"), false);

        assertThat(trusted.isAllowed()).isFalse();
        assertThat(trusted.getReason()).contains("dangerous");
        assertThat(community.isAllowed()).isFalse();
        assertThat(community.getReason()).contains("dangerous");
    }

    @Test
    void shouldLetForceOverrideDangerousTrustedAndCommunityLikeHermes() {
        InstallDecision trusted = service.shouldAllowInstall(scan("trusted", "dangerous"), true);
        InstallDecision community = service.shouldAllowInstall(scan("community", "dangerous"), true);

        assertThat(trusted.isAllowed()).isTrue();
        assertThat(trusted.getReason()).contains("Force").contains("dangerous");
        assertThat(community.isAllowed()).isTrue();
        assertThat(community.getReason()).contains("Force").contains("dangerous");
    }

    @Test
    void shouldKeepCommunityCautionConfirmationUnlessForcedLikeHermes() {
        InstallDecision unforced = service.shouldAllowInstall(scan("community", "caution"), false);
        InstallDecision forced = service.shouldAllowInstall(scan("community", "caution"), true);

        assertThat(unforced.isAllowed()).isFalse();
        assertThat(unforced.isRequiresConfirmation()).isTrue();
        assertThat(forced.isAllowed()).isTrue();
    }

    @Test
    void shouldAllowSafeCommunitySkillsLikeHermes() {
        InstallDecision decision = service.shouldAllowInstall(scan("community", "safe"), false);

        assertThat(decision.isAllowed()).isTrue();
    }

    private ScanResult scan(String trustLevel, String verdict) {
        ScanResult result = new ScanResult();
        result.setTrustLevel(trustLevel);
        result.setVerdict(verdict);
        return result;
    }
}
