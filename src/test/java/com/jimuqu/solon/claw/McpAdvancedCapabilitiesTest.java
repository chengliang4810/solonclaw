package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpImageSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for MCP advanced capabilities. */
public class McpAdvancedCapabilitiesTest {

    // ---- McpImageSupport ----

    @Test
    void mcpImageSupport_extractsBase64ImageBlock() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "iVBORw0KGgo="); // minimal base64

        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "image");
        block.put("source", source);

        List<Map<String, Object>> images = McpImageSupport.extractImages(block);
        assertThat(images).hasSize(1);
        assertThat(images.get(0).get("mime_type")).isEqualTo("image/png");
        assertThat(images.get(0).get("data")).isNotNull();
    }

    @Test
    void mcpImageSupport_hasImages_returnsTrueWhenImagePresent() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("media_type", "image/jpeg");
        source.put("data", "iVBORw0KGgo=");

        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "image");
        block.put("source", source);

        assertThat(McpImageSupport.hasImages(block)).isTrue();
    }

    @Test
    void mcpImageSupport_hasImages_returnsFalseForTextBlock() {
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", "hello");

        assertThat(McpImageSupport.hasImages(block)).isFalse();
    }

    @Test
    void mcpImageSupport_extractsFromContentList() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("media_type", "image/png");
        source.put("data", "iVBORw0KGgo=");

        Map<String, Object> imageBlock = new LinkedHashMap<String, Object>();
        imageBlock.put("type", "image");
        imageBlock.put("source", source);

        List<Object> content = new ArrayList<Object>();
        content.add(imageBlock);

        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("content", content);

        List<Map<String, Object>> images = McpImageSupport.extractImages(envelope);
        assertThat(images).hasSize(1);
    }

    @Test
    void mcpImageSupport_toDataUri_producesCorrectPrefix() {
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("mime_type", "image/png");
        descriptor.put("data", "abc123");

        String uri = McpImageSupport.toDataUri(descriptor);
        assertThat(uri).startsWith("data:image/png;base64,abc123");
    }

    @Test
    void mcpImageSupport_rejectsNonImageMimeType() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("media_type", "text/plain");
        source.put("data", "aGVsbG8=");

        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "image");
        block.put("source", source);

        List<Map<String, Object>> images = McpImageSupport.extractImages(block);
        assertThat(images).isEmpty();
    }

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
