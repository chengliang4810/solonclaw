package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.GatewayInjectionAuthService;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** 校验外部消息网关注入认证的 nonce 防重放缓存边界。 */
public class GatewayInjectionAuthServiceTest {
    /** 缓存满但 nonce 仍在重放窗口内时，应拒绝新 nonce 而不是淘汰旧 nonce。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectNewNonceWhenLiveNonceCacheIsFull() throws Exception {
        GatewayInjectionAuthService service = new GatewayInjectionAuthService(new AppConfig());
        long now = 1_000_000L;
        long window = 3600L;
        int maxNonces = maxNonces();
        Map<String, Long> seenNonces = (Map<String, Long>) seenNoncesField().get(service);
        synchronized (seenNonces) {
            for (int i = 0; i < maxNonces; i++) {
                seenNonces.put("nonce-" + i, Long.valueOf(now));
            }
        }

        assertThat(markNonce(service, "nonce-extra", now, window)).isFalse();
        assertThat(markNonce(service, "nonce-0", now, window)).isFalse();
        assertThat(seenNonces).containsKey("nonce-0").hasSize(maxNonces);
    }

    /** 调用私有 markNonce，避免构造完整 HTTP 上下文影响防重放边界断言。 */
    private static boolean markNonce(GatewayInjectionAuthService service, String nonce, long now, long window)
            throws Exception {
        Method method =
                GatewayInjectionAuthService.class.getDeclaredMethod("markNonce", String.class, long.class, long.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(service, nonce, Long.valueOf(now), Long.valueOf(window))).booleanValue();
    }

    /** 读取 nonce 缓存上限，测试跟随生产常量变化。 */
    private static int maxNonces() throws Exception {
        Field field = GatewayInjectionAuthService.class.getDeclaredField("MAX_NONCES");
        field.setAccessible(true);
        return field.getInt(null);
    }

    /** 读取已使用 nonce 缓存，用于构造满容量窗口。 */
    private static Field seenNoncesField() throws Exception {
        Field field = GatewayInjectionAuthService.class.getDeclaredField("seenNonces");
        field.setAccessible(true);
        return field;
    }
}
