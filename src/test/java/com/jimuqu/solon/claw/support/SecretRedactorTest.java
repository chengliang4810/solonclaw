package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
    void shouldRedactCamelCaseJsonSecrets() {
        String result =
                SecretRedactor.redact(
                        "{\"accessToken\":\"access-secret\",\"clientSecret\":\"client-secret\",\"privateKey\":\"private-secret\"}");

        assertThat(result)
                .contains("\"accessToken\":\"***\"")
                .contains("\"clientSecret\":\"***\"")
                .contains("\"privateKey\":\"***\"")
                .doesNotContain("access-secret")
                .doesNotContain("client-secret")
                .doesNotContain("private-secret");
    }

    @Test
    void shouldRedactCamelCaseShellCredentialAssignments() {
        String result =
                SecretRedactor.redact(
                        "accessToken=access-secret clientSecret=client-secret privateKey=private-secret ok=value");

        assertThat(result)
                .contains("accessToken=***")
                .contains("clientSecret=***")
                .contains("privateKey=***")
                .contains("ok=value")
                .doesNotContain("access-secret")
                .doesNotContain("client-secret")
                .doesNotContain("private-secret");
    }

    @Test
    void shouldRedactYamlStyleCredentialFields() {
        String result =
                SecretRedactor.redact(
                        "providers:\n"
                                + "  default:\n"
                                + "    apiKey: tp-local-provider-secret-1234567890\n"
                                + "    accessToken: \"access-token-secret\"\n"
                                + "    clientSecret: 'client-secret-value'\n"
                                + "    defaultModel: mimo-v2.5-pro\n"
                                + "    baseUrl: https://example.test/v1\n");

        assertThat(result)
                .contains("apiKey: ***")
                .contains("accessToken: \"***\"")
                .contains("clientSecret: '***'")
                .contains("defaultModel: mimo-v2.5-pro")
                .contains("baseUrl: https://example.test/v1")
                .doesNotContain("tp-local-provider-secret")
                .doesNotContain("access-token-secret")
                .doesNotContain("client-secret-value");
    }

    @Test
    void shouldRedactUrlsPrivateKeysAndDatabasePasswordsLikeJimuqu() {
        String result =
                SecretRedactor.redactSensitivePaths(
                        SecretRedactor.redact(
                                "https://user:supersecretpw@host.example.com/path?code=oauth-code&state=ok\n"
                                        + "postgres://admin:dbpass@db.internal:5432/app\n"
                                        + "-----BEGIN RSA PRIVATE KEY-----\n"
                                        + "abcdef\n"
                                        + "-----END RSA PRIVATE KEY-----"));

        assertThat(result)
                .contains("[REDACTED_URL_CREDENTIAL]")
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
                SecretRedactor.maskUrl("https://user%3Aencoded-password@example.com/path?ok=value");

        assertThat(encoded)
                .isEqualTo("https://user%3A***@example.com/path?ok=value")
                .doesNotContain("encoded-password");

        String repeatedlyEncoded =
                SecretRedactor.maskUrl(
                        "https://user%253Aencoded-password@example.com/path?ok=value");

        assertThat(repeatedlyEncoded)
                .isEqualTo("https://user%253A***@example.com/path?ok=value")
                .doesNotContain("encoded-password");

        String schemeless = SecretRedactor.maskUrl("alice:schemeless-password@example.com/private");

        assertThat(schemeless)
                .isEqualTo("alice:***@example.com/private")
                .doesNotContain("schemeless-password");

        assertThat(SecretRedactor.maskUrl("ftp://example.com/file.txt"))
                .isEqualTo("ftp://example.com/file.txt");
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
    void shouldMaskApprovalSensitiveUrlQueryAliases() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/callback?oauth_token=oauth-secret&client_assertion=assertion-secret&proxy_authorization=proxy-secret&access_key=access-key-secret&secret_key=secret-key-secret&session_token=session-secret&ok=value");

        assertThat(result)
                .contains("oauth_token=***")
                .contains("client_assertion=***")
                .contains("proxy_authorization=***")
                .contains("access_key=***")
                .contains("secret_key=***")
                .contains("session_token=***")
                .contains("ok=value")
                .doesNotContain("oauth-secret")
                .doesNotContain("assertion-secret")
                .doesNotContain("proxy-secret")
                .doesNotContain("access-key-secret")
                .doesNotContain("secret-key-secret")
                .doesNotContain("session-secret");
    }

    @Test
    void shouldMaskCamelCaseSensitiveUrlQueryNames() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/callback?accessToken=access-secret&clientSecret=client-secret&privateKey=private-secret&ok=value");

        assertThat(result)
                .isEqualTo(
                        "https://example.com/callback?accessToken=***&clientSecret=***&privateKey=***&ok=value")
                .doesNotContain("access-secret")
                .doesNotContain("client-secret")
                .doesNotContain("private-secret");
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
    void shouldMaskNestedEncodedSignedUrls() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/download?next=https%253A%252F%252Fbucket.example.com%252Ffile%253Fx-amz-signature%253Dnested-signature-secret&ok=value");

        assertThat(result)
                .isEqualTo("https://example.com/download?next=***&ok=value")
                .doesNotContain("nested-signature-secret");
    }

    @Test
    void shouldMaskNestedEncodedSensitiveParametersAfterAmpersand() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/callback?page=1%2526client_secret=nested-client-secret&ok=value");

        assertThat(result)
                .isEqualTo("https://example.com/callback?page=***&ok=value")
                .doesNotContain("nested-client-secret");
    }

    @Test
    void shouldMaskSensitiveUrlPathSegments() {
        String result =
                SecretRedactor.maskUrl("example.com/oauth/client_secret/schemeless-path-secret");

        assertThat(result)
                .isEqualTo("example.com/oauth/[REDACTED_URL_SECRET]")
                .doesNotContain("schemeless-path-secret");

        String fileToken = SecretRedactor.maskUrl("example.com/download/client_secret.json");

        assertThat(fileToken)
                .isEqualTo("example.com/download/[REDACTED_URL_SECRET]")
                .doesNotContain("client_secret.json");
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
    void shouldRedactSensitiveUrlQueryNameAliases() {
        String result =
                SecretRedactor.maskUrl(
                        "https://example.com/callback?api.key=dot-secret&private-key=dash-secret&ok=value");

        assertThat(result)
                .isEqualTo("https://example.com/callback?api.key=***&private-key=***&ok=value")
                .doesNotContain("dot-secret")
                .doesNotContain("dash-secret");
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
                .isEqualTo("https://example.com/callback#token=***&access%255Ftoken=***&ok=value")
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
                SecretRedactor.redactSensitivePaths(
                        SecretRedactor.redact(
                                "blocked path C:\\Users\\dev\\.ssh\\id_ed25519 and ./credentials/oauth.json plus .env and credentials.json and credentials"));

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
    void shouldRedactEncodedSensitiveFileTokens() {
        String result =
                SecretRedactor.redactSensitivePaths(
                        SecretRedactor.redact(
                                "blocked files client&#95;secret.json api%5Fkey.txt access%255Ftoken.cache private-key.pem"));

        assertThat(result)
                .contains("[REDACTED_PATH]")
                .doesNotContain("client&#95;secret.json")
                .doesNotContain("api%5Fkey.txt")
                .doesNotContain("access%255Ftoken.cache")
                .doesNotContain("private-key.pem");
    }

    @Test
    void shouldRedactSkillsHubInternalCachePaths() {
        String result =
                SecretRedactor.redactSensitivePaths(
                        SecretRedactor.redact(
                                "blocked internal path skills/.hub/index-cache/catalog.json and docs/skills/.hub/readme.md"));

        assertThat(result)
                .contains("[REDACTED_PATH]")
                .doesNotContain("skills/.hub")
                .doesNotContain("index-cache")
                .doesNotContain("catalog.json")
                .doesNotContain("readme.md");
    }

    @Test
    void shouldRedactSensitivePathsInStructuredMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("result_ref", "/tmp/output-token=secret123-ghp_1234567890abcdef.txt");
        metadata.put("message", "blocked credentials/oauth.json token=ghp_metadata1234567890");

        String result = StructuredMetadataSupport.serializeRedacted(metadata);

        assertThat(result)
                .contains("\"result_ref\":\"[REDACTED_PATH]\"")
                .contains("\"message\":\"blocked [REDACTED_PATH] token=***\"")
                .doesNotContain("secret123")
                .doesNotContain("ghp_1234567890abcdef")
                .doesNotContain("ghp_metadata1234567890")
                .doesNotContain("credentials/oauth.json");
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
                SecretRedactor.maskUrl("https://example.com/path?token=opaque\u202Etoken123456789");

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
