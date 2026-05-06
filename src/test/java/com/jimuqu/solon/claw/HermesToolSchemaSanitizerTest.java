package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tool.runtime.HermesToolSchemaSanitizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class HermesToolSchemaSanitizerTest {
    @Test
    void shouldInjectPropertiesIntoBareObjectSchemasLikeHermes() {
        ONode root =
                ONode.ofJson(
                        HermesToolSchemaSanitizer.sanitizeSchemaJson(
                                "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"object\"}},\"required\":[\"payload\",\"missing\"]}"));

        ONode payload = root.get("properties").get("payload");
        assertThat(payload.get("type").getString()).isEqualTo("object");
        assertThat(payload.get("properties").isObject()).isTrue();
        assertThat(root.get("required").size()).isEqualTo(1);
        assertThat(root.get("required").get(0).getString()).isEqualTo("payload");
    }

    @Test
    void shouldReplaceBareStringSchemaValuesAndNullableTypeArrays() {
        ONode root =
                ONode.ofJson(
                        HermesToolSchemaSanitizer.sanitizeSchemaJson(
                                "{\"type\":\"object\",\"properties\":{\"payload\":\"object\",\"name\":{\"type\":[\"string\",\"null\"]}}}"));

        ONode payload = root.get("properties").get("payload");
        ONode name = root.get("properties").get("name");
        assertThat(payload.get("type").getString()).isEqualTo("object");
        assertThat(payload.get("properties").isObject()).isTrue();
        assertThat(name.get("type").getString()).isEqualTo("string");
        assertThat(name.get("nullable").getBoolean()).isTrue();
    }

    @Test
    void shouldCollapseNullableAnyOfAndKeepMetadata() {
        ONode root =
                ONode.ofJson(
                        HermesToolSchemaSanitizer.sanitizeSchemaJson(
                                "{\"type\":\"object\",\"properties\":{\"topic\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}],\"description\":\"Optional topic\"}}}"));

        ONode topic = root.get("properties").get("topic");
        assertThat(topic.get("type").getString()).isEqualTo("string");
        assertThat(topic.get("nullable").getBoolean()).isTrue();
        assertThat(topic.get("description").getString()).isEqualTo("Optional topic");
        assertThat(topic.hasKey("anyOf")).isFalse();
    }

    @Test
    void shouldStripPatternAndFormatOnlyFromSchemaNodes() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        root.put("properties", properties);
        Map<String, Object> patternProperty = new LinkedHashMap<String, Object>();
        patternProperty.put("type", "string");
        patternProperty.put("description", "Search regex");
        properties.put("pattern", patternProperty);
        Map<String, Object> dateProperty = new LinkedHashMap<String, Object>();
        dateProperty.put("type", "string");
        dateProperty.put("pattern", "\\d+");
        dateProperty.put("format", "date-time");
        properties.put("date", dateProperty);

        HermesToolSchemaSanitizer.StripResult result =
                HermesToolSchemaSanitizer.stripPatternAndFormat(root);
        ONode stripped = ONode.ofJson(ONode.serialize(result.getSchema()));

        assertThat(result.getStrippedCount()).isEqualTo(2);
        assertThat(stripped.get("properties").hasKey("pattern")).isTrue();
        assertThat(stripped.get("properties").get("date").hasKey("pattern")).isFalse();
        assertThat(stripped.get("properties").get("date").hasKey("format")).isFalse();
    }
}
