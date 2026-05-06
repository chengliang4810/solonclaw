package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tool.runtime.SolonClawToolSchemaSanitizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class SolonClawToolSchemaSanitizerTest {
    @Test
    void shouldInjectPropertiesIntoBareObjectSchemas() {
        ONode root =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
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
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
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
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
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

        SolonClawToolSchemaSanitizer.StripResult result =
                SolonClawToolSchemaSanitizer.stripPatternAndFormat(root);
        ONode stripped = ONode.ofJson(ONode.serialize(result.getSchema()));

        assertThat(result.getStrippedCount()).isEqualTo(2);
        assertThat(stripped.get("properties").hasKey("pattern")).isTrue();
        assertThat(stripped.get("properties").get("date").hasKey("pattern")).isFalse();
        assertThat(stripped.get("properties").get("date").hasKey("format")).isFalse();
    }

    @Test
    void shouldSanitizeItemsAndAdditionalProperties() {
        ONode root =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
                                "{"
                                        + "\"type\":\"object\","
                                        + "\"properties\":{"
                                        + "\"bag\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},"
                                        + "\"payload\":{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true},"
                                        + "\"dictField\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"object\"}}"
                                        + "}"
                                        + "}"));

        ONode bagItems = root.get("properties").get("bag").get("items");
        ONode payload = root.get("properties").get("payload");
        ONode dictAdditionalProperties =
                root.get("properties").get("dictField").get("additionalProperties");

        assertThat(bagItems.get("type").getString()).isEqualTo("object");
        assertThat(bagItems.get("properties").isObject()).isTrue();
        assertThat(payload.get("additionalProperties").getBoolean()).isTrue();
        assertThat(dictAdditionalProperties.get("type").getString()).isEqualTo("object");
        assertThat(dictAdditionalProperties.get("properties").isObject()).isTrue();
    }

    @Test
    void shouldDefaultInvalidTopLevelSchemaAndDropAllMissingRequiredFields() {
        ONode invalid = ONode.ofJson(SolonClawToolSchemaSanitizer.sanitizeSchemaJson("object"));
        assertThat(invalid.get("type").getString()).isEqualTo("object");
        assertThat(invalid.get("properties").isObject()).isTrue();

        ONode required =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
                                "{\"type\":\"object\",\"properties\":{},\"required\":[\"x\",\"y\"]}"));
        assertThat(required.hasKey("required")).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldNotMutateInputSchemaObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        root.put("properties", properties);
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("type", "object");
        properties.put("x", nested);

        Object sanitizedObject = SolonClawToolSchemaSanitizer.sanitizeSchemaObject(root);
        ONode sanitized = ONode.ofJson(ONode.serialize(sanitizedObject));

        assertThat(sanitized.get("properties").get("x").get("properties").isObject()).isTrue();
        Map<String, Object> originalNested = (Map<String, Object>) properties.get("x");
        assertThat(originalNested.containsKey("properties")).isFalse();
    }

    @Test
    void shouldStripPatternAndFormatRecursivelyAndRemainIdempotent() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        root.put("properties", properties);
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        properties.put("value", value);
        java.util.List<Object> variants = new java.util.ArrayList<Object>();
        value.put("anyOf", variants);
        Map<String, Object> variant = new LinkedHashMap<String, Object>();
        variant.put("type", "string");
        variant.put("pattern", "[A-Z]+");
        variant.put("format", "uuid");
        variants.add(variant);
        Map<String, Object> integerVariant = new LinkedHashMap<String, Object>();
        integerVariant.put("type", "integer");
        variants.add(integerVariant);

        SolonClawToolSchemaSanitizer.StripResult first =
                SolonClawToolSchemaSanitizer.stripPatternAndFormat(root);
        SolonClawToolSchemaSanitizer.StripResult second =
                SolonClawToolSchemaSanitizer.stripPatternAndFormat(first.getSchema());
        ONode stripped = ONode.ofJson(ONode.serialize(first.getSchema()));
        ONode strippedVariant =
                stripped.get("properties").get("value").get("anyOf").get(0);

        assertThat(first.getStrippedCount()).isEqualTo(2);
        assertThat(second.getStrippedCount()).isZero();
        assertThat(strippedVariant.hasKey("pattern")).isFalse();
        assertThat(strippedVariant.hasKey("format")).isFalse();
        assertThat(strippedVariant.get("type").getString()).isEqualTo("string");
    }
}
