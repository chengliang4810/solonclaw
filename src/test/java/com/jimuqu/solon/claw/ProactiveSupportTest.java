package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.proactive.ProactiveSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 主动协作共享辅助测试，覆盖免打扰小时夹紧和跨午夜边界。 */
public class ProactiveSupportTest {
    @ParameterizedTest
    @CsvSource({
        "23,8,23,true",
        "23,8,7,true",
        "23,8,8,false",
        "9,18,12,true",
        "9,18,18,false",
        "9,9,9,false",
        "-1,24,0,true",
        "24,-1,23,true",
        "24,-1,0,false"
    })
    void shouldDetectQuietHourWithClampedBoundaries(
            int startHour, int endHour, int hour, boolean expected) {
        assertThat(ProactiveSupport.isQuietHour(startHour, endHour, hour)).isEqualTo(expected);
    }
}
