package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 校验打包运行时使用的 Jackson 组件能够完成 ObjectMapper 初始化和基础 JSON 解析。 */
class JacksonRuntimeInitializationTest {

    /** 防止 annotations、core 与 databind 的传递依赖版本错配导致 ObjectMapper 初始化失败。 */
    @Test
    void shouldInitializeObjectMapper() throws Exception {
        assertThat(new ObjectMapper().readTree("{\"ready\":true}").get("ready").asBoolean())
                .isTrue();
    }
}
