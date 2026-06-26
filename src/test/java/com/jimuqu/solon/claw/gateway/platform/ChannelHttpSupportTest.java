package com.jimuqu.solon.claw.gateway.platform;

import static org.assertj.core.api.Assertions.assertThat;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class ChannelHttpSupportTest {
    /** 无响应体时应返回空正文。 */
    @Test
    void shouldReturnEmptyBodyWhenResponseHasNoBody() throws Exception {
        Response response =
                new Response.Builder()
                        .request(new Request.Builder().url("http://127.0.0.1").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(204)
                        .message("No Content")
                        .build();

        assertThat(ChannelHttpSupport.safeBody(response)).isEmpty();
    }

    /** API 域名应使用配置值或默认值并移除末尾斜杠。 */
    @Test
    void shouldNormalizeApiDomain() {
        assertThat(ChannelHttpSupport.apiDomain(" https://api.example.com// ", "https://fallback"))
                .isEqualTo("https://api.example.com");
        assertThat(ChannelHttpSupport.apiDomain(" ", "https://fallback/"))
                .isEqualTo("https://fallback");
    }

    /** 首个非空白文本应裁剪后返回，全部为空时返回空串。 */
    @Test
    void shouldReturnTrimmedFirstNonBlank() {
        assertThat(ChannelHttpSupport.firstNonBlank(" ", "  value  ")).isEqualTo("value");
        assertThat(ChannelHttpSupport.firstNonBlank(" ", null)).isEmpty();
        assertThat(ChannelHttpSupport.firstNonBlank((String[]) null)).isEmpty();
    }
}
