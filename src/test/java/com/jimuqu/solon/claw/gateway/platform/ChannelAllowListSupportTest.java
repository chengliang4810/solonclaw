package com.jimuqu.solon.claw.gateway.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** 验证渠道允许名单的通配符、大小写和空值处理。 */
class ChannelAllowListSupportTest {
    /** 通配符和大小写不敏感匹配应命中允许名单。 */
    @Test
    void shouldMatchWildcardAndIgnoreCase() {
        assertTrue(ChannelAllowListSupport.contains(Collections.singletonList("*"), "user-1"));
        assertTrue(
                ChannelAllowListSupport.contains(Arrays.asList(" group-1 ", "USER-2"), "user-2"));
    }

    /** 空值和未命中目标不得误判为允许。 */
    @Test
    void shouldRejectBlankOrMissingTarget() {
        assertFalse(ChannelAllowListSupport.contains(null, "user-1"));
        assertFalse(ChannelAllowListSupport.contains(Collections.singletonList("user-1"), null));
        assertFalse(
                ChannelAllowListSupport.contains(Collections.singletonList("user-1"), "user-2"));
    }
}
