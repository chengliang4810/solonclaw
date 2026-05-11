package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class SecretRedactorTest {
    @Test
    void shouldKeepLowercaseCodeAssignmentsLikeJimuqu() {
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
    void shouldRedactShellEnvJsonAndAuthSecretsLikeJimuqu() {
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
    void shouldRedactUrlsPrivateKeysAndDatabasePasswordsLikeJimuqu() {
        String result =
                SecretRedactor.redact(
                        "https://user:supersecretpw@host.example.com/path?code=oauth-code&state=ok\n"
                                + "postgres://admin:dbpass@db.internal:5432/app\n"
                                + "-----BEGIN RSA PRIVATE KEY-----\n"
                                + "abcdef\n"
                                + "-----END RSA PRIVATE KEY-----");

        assertThat(result)
                .contains("[REDACTED_PATH]")
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

        String encoded =
                SecretRedactor.maskUrl(
                        "https://user%3Aencoded-password@example.com/path?ok=value");

        assertThat(encoded)
                .isEqualTo("https://user%3A***@example.com/path?ok=value")
                .doesNotContain("encoded-password");

        String repeatedlyEncoded =
                SecretRedactor.maskUrl(
                        "https://user%253Aencoded-password@example.com/path?ok=value");

        assertThat(repeatedlyEncoded)
                .isEqualTo("https://user%253A***@example.com/path?ok=value")
                .doesNotContain("encoded-password");
    }

    @Test
    void shouldMaskEncodedSensitiveUrlQueryNames() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/callback?api%255Fkey=secret-value-123&client%5Fsecret=client-secret&ok=value");

        assertThat(result)
                .isEqualTo(
                        "https://example.com/callback?api%255Fkey=***&client%5Fsecret=***&ok=value")
                .doesNotContain("secret-value-123")
                .doesNotContain("client-secret");
    }

    @Test
    void shouldMaskRepeatedlyEncodedSensitiveUrlQueryNames() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/callback?api%25255Fkey=deep-secret&client%25255Fsecret=client-deep-secret&ok=value");

        assertThat(result)
                .isEqualTo(
                        "https://example.com/callback?api%25255Fkey=***&client%25255Fsecret=***&ok=value")
                .doesNotContain("deep-secret")
                .doesNotContain("client-deep-secret");
    }

    @Test
    void shouldRedactEncodedSensitiveUrlQueryNamesInGeneralText() {
        String result =
                SecretRedactor.redact(
                        "blocked URL https://example.com/callback?api%255Fkey=secret-value-123&client%5Fsecret=client-secret&ok=value");

        assertThat(result)
                .contains("api%255Fkey=***")
                .contains("client%5Fsecret=***")
                .contains("ok=value")
                .doesNotContain("secret-value-123")
                .doesNotContain("client-secret");
    }

    @Test
    void shouldRedactEncodedUrlParametersAfterPlainSensitiveParameters() {
        String result =
                SecretRedactor.redact(
                        "access_token=ghp_oautherror12345&callback=http://localhost/cb?api%255Fkey=oauth-encoded-secret&token=secret-oauth-error");

        assertThat(result)
                .contains("access_token=***")
                .contains("api%255Fkey=***")
                .contains("token=***")
                .doesNotContain("ghp_oautherror12345")
                .doesNotContain("oauth-encoded-secret")
                .doesNotContain("secret-oauth-error");
    }

    @Test
    void shouldRedactEncodedSensitiveUrlQueryNamesInCommandText() {
        String result =
                SecretRedactor.redact(
                        "printf api_key=sk-history-secret && curl https://example.test/callback?api%255Fkey=history-encoded-secret");

        assertThat(result)
                .contains("api_key=***")
                .contains("api%255Fkey=***")
                .doesNotContain("sk-history-secret")
                .doesNotContain("history-encoded-secret");
    }

    @Test
    void shouldRedactSemicolonSeparatedSensitiveUrlParameters() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/callback;token=plain-secret;api%255Fkey=secret-value-123;ok=value");

        assertThat(result)
                .isEqualTo("https://example.com/callback;token=***;api%255Fkey=***;ok=value")
                .doesNotContain("plain-secret")
                .doesNotContain("secret-value-123");
    }

    @Test
    void shouldRedactFragmentSensitiveUrlParameters() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/callback#token=plain-fragment-secret&access%255Ftoken=fragment-secret&ok=value");

        assertThat(result)
                .isEqualTo(
                        "https://example.com/callback#token=***&access%255Ftoken=***&ok=value")
                .doesNotContain("plain-fragment-secret")
                .doesNotContain("fragment-secret");
    }

    @Test
    void shouldNotMaskNonSensitiveNamesContainingSensitiveSuffixes() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/list?monkey=banana&keystone=door&ok=value");

        assertThat(result)
                .isEqualTo("https://example.com/list?monkey=banana&keystone=door&ok=value");
    }

    @Test
    void shouldMaskEncodedSensitiveUrlQueryNamesWithLocaleIndependentCaseFolding() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));

            String result =
                    SecretRedactor.maskUrl(
                            "https://example.com/callback?ID%255FTOKEN=id-secret&ok=value");

            assertThat(result)
                    .isEqualTo("https://example.com/callback?ID%255FTOKEN=***&ok=value")
                    .doesNotContain("id-secret");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void shouldRedactSensitivePathFragments() {
        String result =
                SecretRedactor.redact(
                        "blocked path C:\\Users\\dev\\.ssh\\id_ed25519 and ./credentials/oauth.json plus .env and credentials.json and credentials");

        assertThat(result)
                .contains("[REDACTED_PATH]")
                .doesNotContain(".ssh")
                .doesNotContain("id_ed25519")
                .doesNotContain("credentials/oauth.json")
                .doesNotContain("credentials.json")
                .doesNotContain("credentials")
                .doesNotContain(".env");
    }

    @Test
    void shouldRedactSkillsHubInternalCachePaths() {
        String result =
                SecretRedactor.redact(
                        "blocked internal path skills/.hub/index-cache/catalog.json and docs/skills/.hub/readme.md");

        assertThat(result)
                .contains("[REDACTED_PATH]")
                .doesNotContain("skills/.hub")
                .doesNotContain("index-cache")
                .doesNotContain("catalog.json")
                .doesNotContain("readme.md");
    }

    @Test
    void shouldRedactEmbeddedTokenFragmentsInNamesAndPaths() {
        String result =
                SecretRedactor.redact(
                        "Tool 'secret_tool_ghp_1234567890abcdef' failed at missing-token-ghp_1234567890abcdef and npm-package-npm_1234567890abcdef.");

        assertThat(result)
                .contains("secret_tool_ghp_***")
                .contains("missing-token-ghp_***")
                .contains("npm-package-npm_***")
                .doesNotContain("1234567890abcdef");
    }

    @Test
    void shouldStripDisplayControlsBeforeReturningRedactedText() {
        String result =
                SecretRedactor.redact(
                        "approve\u202Ecod.exe\u2069 OPENAI_API_KEY=sk-proj-abc123def456ghi789jkl012\rhidden");
        String maskedUrl =
                SecretRedactor.maskUrl(
                        "https://example.com/path?token=opaque\u202Etoken123456789");

        assertThat(result)
                .contains("approvecod.exe")
                .contains("OPENAI_API_KEY=***")
                .doesNotContain("\u202E")
                .doesNotContain("\u2069")
                .doesNotContain("\r")
                .doesNotContain("abc123def456");
        assertThat(maskedUrl)
                .isEqualTo("https://example.com/path?token=***")
                .doesNotContain("\u202E")
                .doesNotContain("opaque");
    }
}
