package com.jimuqu.solon.claw.web;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** 验证扫码 setup 服务复用 ticket 基础字段投影，避免两套服务重复拼装生命周期字段。 */
class QrSetupTicketMapReuseTest {
    /** 微信、飞书、钉钉扫码 setup 服务不应各自铺写平台无关 ticket 字段。 */
    @Test
    void setupServicesShouldReuseQrTicketBaseMap() throws Exception {
        String[] paths =
                new String[] {
                    "src/main/java/com/jimuqu/solon/claw/web/WeixinQrSetupService.java",
                    "src/main/java/com/jimuqu/solon/claw/web/DomesticQrSetupService.java",
                };
        String[] duplicatedFields =
                new String[] {
                    "ticket",
                    "status",
                    "message",
                    "error_code",
                    "error_message",
                    "created_at",
                    "updated_at",
                    "expires_at",
                };
        for (String path : paths) {
            String source =
                    new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            for (String field : duplicatedFields) {
                assertFalse(source.contains("result.put(\"" + field + "\""), path + " " + field);
            }
        }
    }
}
