package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import org.junit.jupiter.api.Test;

/** Unit tests for MCP advanced capabilities. */
public class McpAdvancedCapabilitiesTest {

    // ---- AppConfig.McpOAuth ----

    @Test
    void appConfig_mcpOauth_defaultsAreNull() {
        AppConfig.McpOAuth oauth = new AppConfig.McpOAuth();
        assertThat(oauth.getClientId()).isNull();
        assertThat(oauth.getClientSecret()).isNull();
        assertThat(oauth.getTokenUrl()).isNull();
        assertThat(oauth.getScope()).isNull();
    }

    @Test
    void appConfig_mcpConfig_hasOauthField() {
        AppConfig.McpConfig mcp = new AppConfig.McpConfig();
        assertThat(mcp.getOauth()).isNotNull();
        mcp.getOauth().setClientId("my-client");
        mcp.getOauth().setTokenUrl("https://auth.example.com/token");
        assertThat(mcp.getOauth().getClientId()).isEqualTo("my-client");
        assertThat(mcp.getOauth().getTokenUrl()).isEqualTo("https://auth.example.com/token");
    }
}
