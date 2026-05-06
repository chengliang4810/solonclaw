package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {
    @Test
    void shouldKeepLowercaseCodeAssignmentsLikeHermes() {
        assertThat(SecretRedactor.redact("before_tokens = response.usage.prompt_tokens"))
                .isEqualTo("before_tokens = response.usage.prompt_tokens");
        assertThat(SecretRedactor.redact("api_key = config.get('api_key')"))
                .isEqualTo("api_key = config.get('api_key')");
        assertThat(SecretRedactor.redact("const token = await getToken();"))
                .isEqualTo("const token = await getToken();");
        assertThat(SecretRedactor.redact("const secret = await fetchSecret();"))
                .isEqualTo("const secret = await fetchSecret();");
    }

    @Test
    void shouldRedactShellEnvJsonAndAuthSecretsLikeHermes() {
        String result =
                SecretRedactor.redact(
                        "export OPENAI_API_KEY=sk-proj-abc123def456ghi789jkl012\n"
                                + "api_key=sk-test-secret token=secret123\n"
                                + "{\"access_token\": \"eyJhbGciOiJIUzI1NiJ9.payload.signature\"}\n"
                                + "Authorization: Bearer ghp_abcdefghijklmnop\n");

        assertThat(result)
                .contains("OPENAI_API_KEY=***")
                .contains("api_key=***")
                .contains("token=***")
                .contains("\"access_token\": \"***\"")
                .contains("Authorization: Bearer ***")
                .doesNotContain("abc123def456")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123")
                .doesNotContain("abcdefghijklmnop")
                .doesNotContain("payload.signature");
    }

    @Test
    void shouldRedactUrlsPrivateKeysAndDatabasePasswordsLikeHermes() {
        String result =
                SecretRedactor.redact(
                        "https://user:supersecretpw@host.example.com/path?code=oauth-code&state=ok\n"
                                + "postgres://admin:dbpass@db.internal:5432/app\n"
                                + "-----BEGIN RSA PRIVATE KEY-----\n"
                                + "abcdef\n"
                                + "-----END RSA PRIVATE KEY-----");

        assertThat(result)
                .contains("https://user:***@host.example.com/path?code=***&state=ok")
                .contains("postgres://admin:***@db.internal:5432/app")
                .contains("[REDACTED PRIVATE KEY]")
                .doesNotContain("supersecretpw")
                .doesNotContain("oauth-code")
                .doesNotContain("dbpass")
                .doesNotContain("abcdef");
    }

    @Test
    void shouldMaskUrlWithoutLeakingUserinfoOrSensitiveQueryValues() {
        String result =
                SecretRedactor.maskUrl(
                        "wss://user:ws-secret@example.com/ws?token=opaqueWsToken123&ok=value");

        assertThat(result)
                .isEqualTo("wss://user:***@example.com/ws?token=***&ok=value")
                .doesNotContain("ws-secret")
                .doesNotContain("opaqueWsToken123");
    }
}
