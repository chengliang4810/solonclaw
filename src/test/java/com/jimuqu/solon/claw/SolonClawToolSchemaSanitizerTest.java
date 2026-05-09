package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.tool.runtime.SolonClawToolSchemaSanitizer;
import com.jimuqu.solon.claw.tool.runtime.SanitizedFunctionTool;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class SolonClawToolSchemaSanitizerTest {
    @Test
    void shouldExposeSchemaSanitizerPolicySummary() {
        Map<String, Object> summary = SolonClawToolSchemaSanitizer.policySummary();

        assertThat(summary.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("inputSchemaSanitized")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("mcpInputSchemaSanitized")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("invalidSchemaDefaultsToObject")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("requiredPrunedToKnownProperties")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(summary))
                .contains("localFunctionTools")
                .contains("toolProviders")
                .contains("mcpRemoteToolImport")
                .contains("pattern")
                .contains("format")
                .contains("$ref")
                .contains("if")
                .contains("snack4");
    }

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
                                "{\"type\":\"object\",\"properties\":{\"payload\":\"object\",\"label\":\"string\",\"name\":{\"type\":[\"string\",\"null\"]}}}"));

        ONode payload = root.get("properties").get("payload");
        ONode label = root.get("properties").get("label");
        ONode name = root.get("properties").get("name");
        assertThat(payload.get("type").getString()).isEqualTo("object");
        assertThat(payload.get("properties").isObject()).isTrue();
        assertThat(label.get("type").getString()).isEqualTo("string");
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
    void shouldStripTopLevelAllOfForStrictBackendCompatibility() {
        ONode root =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
                                "{"
                                        + "\"type\":\"object\","
                                        + "\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"add\",\"replace\"]},\"content\":{\"type\":\"string\"}},"
                                        + "\"required\":[\"action\"],"
                                        + "\"allOf\":[{\"if\":{\"properties\":{\"action\":{\"const\":\"add\"}},\"required\":[\"action\"]},\"then\":{\"required\":[\"content\"]}}]"
                                        + "}"));

        assertThat(root.hasKey("allOf")).isFalse();
        assertThat(root.get("required").get(0).getString()).isEqualTo("action");
        assertThat(root.get("properties").hasKey("content")).isTrue();
        assertThat(root.get("properties").get("action").get("enum").size()).isEqualTo(2);
    }

    @Test
    void shouldStripOtherForbiddenTopLevelCombinators() {
        ONode root =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
                                "{"
                                        + "\"type\":\"object\","
                                        + "\"properties\":{\"x\":{\"type\":\"string\"}},"
                                        + "\"oneOf\":[{\"required\":[\"x\"]}],"
                                        + "\"anyOf\":[{\"required\":[\"x\"]}],"
                                        + "\"enum\":[\"invalid-top-level\"],"
                                        + "\"not\":{\"required\":[\"y\"]}"
                                        + "}"));

        assertThat(root.hasKey("oneOf")).isFalse();
        assertThat(root.hasKey("anyOf")).isFalse();
        assertThat(root.hasKey("enum")).isFalse();
        assertThat(root.hasKey("not")).isFalse();
        assertThat(root.get("properties").hasKey("x")).isTrue();
    }

    @Test
    void shouldStripNestedUnsupportedSchemaKeywordsInsideProperties() {
        ONode root =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
                                "{"
                                        + "\"type\":\"object\","
                                        + "\"properties\":{\"config\":{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\"}},\"allOf\":[{\"required\":[\"mode\"]}],\"if\":{\"required\":[\"mode\"]},\"then\":{\"required\":[\"extra\"]},\"else\":{\"required\":[\"fallback\"]}}}"
                                        + "}"));

        ONode nested = root.get("properties").get("config");
        assertThat(nested.hasKey("allOf")).isFalse();
        assertThat(nested.hasKey("if")).isFalse();
        assertThat(nested.hasKey("then")).isFalse();
        assertThat(nested.hasKey("else")).isFalse();
        assertThat(nested.get("properties").hasKey("mode")).isTrue();
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
    void shouldStripPatternAndFormatDuringSchemaSanitization() {
        ONode root =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
                                "{"
                                        + "\"type\":\"object\","
                                        + "\"properties\":{\"date\":{\"type\":\"string\",\"pattern\":\"\\\\d+\",\"format\":\"date-time\"}}"
                                        + "}"));

        ONode date = root.get("properties").get("date");

        assertThat(date.get("type").getString()).isEqualTo("string");
        assertThat(date.hasKey("pattern")).isFalse();
        assertThat(date.hasKey("format")).isFalse();
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
    void shouldSanitizePatternProperties() {
        ONode root =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
                                "{"
                                        + "\"type\":\"object\","
                                        + "\"patternProperties\":{\"^x-\":{\"type\":\"object\"}}"
                                        + "}"));

        ONode dynamic = root.get("patternProperties").get("^x-");

        assertThat(dynamic.get("type").getString()).isEqualTo("object");
        assertThat(dynamic.get("properties").isObject()).isTrue();
    }

    @Test
    void shouldStripDynamicToolUnsupportedSchemaKeywordsRecursively() {
        ONode root =
                ONode.ofJson(
                        SolonClawToolSchemaSanitizer.sanitizeSchemaJson(
                                "{"
                                        + "\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                                        + "\"$id\":\"urn:test\","
                                        + "\"type\":\"object\","
                                        + "\"$defs\":{\"refValue\":{\"type\":\"string\"}},"
                                        + "\"properties\":{"
                                        + "\"value\":{\"$ref\":\"#/$defs/refValue\"},"
                                        + "\"names\":{\"type\":\"array\",\"contains\":{\"type\":\"string\"},\"minContains\":1,\"maxContains\":3,\"prefixItems\":[{\"type\":\"string\"}],\"items\":{\"type\":\"string\"}},"
                                        + "\"attrs\":{\"type\":\"object\",\"propertyNames\":{\"pattern\":\"^[a-z]+$\"},\"dependentSchemas\":{\"mode\":{\"required\":[\"level\"]}},\"unevaluatedProperties\":false,\"properties\":{\"mode\":{\"type\":\"string\"}},\"required\":[\"mode\",\"level\"]}"
                                        + "},"
                                        + "\"dependentRequired\":{\"value\":[\"names\"]},"
                                        + "\"unevaluatedProperties\":false"
                                        + "}"));

        ONode attrs = root.get("properties").get("attrs");
        ONode names = root.get("properties").get("names");
        ONode value = root.get("properties").get("value");

        assertThat(root.hasKey("$schema")).isFalse();
        assertThat(root.hasKey("$id")).isFalse();
        assertThat(root.hasKey("$defs")).isFalse();
        assertThat(root.hasKey("dependentRequired")).isFalse();
        assertThat(root.hasKey("unevaluatedProperties")).isFalse();
        assertThat(value.hasKey("$ref")).isFalse();
        assertThat(names.hasKey("contains")).isFalse();
        assertThat(names.hasKey("minContains")).isFalse();
        assertThat(names.hasKey("maxContains")).isFalse();
        assertThat(names.hasKey("prefixItems")).isFalse();
        assertThat(names.get("items").get("type").getString()).isEqualTo("string");
        assertThat(attrs.hasKey("propertyNames")).isFalse();
        assertThat(attrs.hasKey("dependentSchemas")).isFalse();
        assertThat(attrs.hasKey("unevaluatedProperties")).isFalse();
        assertThat(attrs.get("required").size()).isEqualTo(1);
        assertThat(attrs.get("required").get(0).getString()).isEqualTo("mode");
    }

    @Test
    void shouldExposeSanitizedSchemasWithoutChangingToolExecution() throws Throwable {
        FunctionTool raw =
                new FunctionTool() {
                    @Override
                    public String name() {
                        return "fixture_tool";
                    }

                    @Override
                    public String title() {
                        return "Fixture";
                    }

                    @Override
                    public String description() {
                        return "Fixture tool";
                    }

                    @Override
                    public boolean returnDirect() {
                        return false;
                    }

                    @Override
                    public String inputSchema() {
                        return "{"
                                + "\"type\":\"object\","
                                + "\"properties\":{\"when\":{\"type\":\"string\",\"format\":\"date-time\",\"default\":\"now\"}},"
                                + "\"if\":{\"required\":[\"when\"]},"
                                + "\"then\":{\"required\":[\"other\"]},"
                                + "\"required\":[\"when\",\"other\"]"
                                + "}";
                    }

                    @Override
                    public java.lang.reflect.Type returnType() {
                        return String.class;
                    }

                    @Override
                    public Object handle(Map<String, Object> args) {
                        return "handled:" + args.get("when");
                    }
                };

        FunctionTool wrapped = SanitizedFunctionTool.wrap(raw);
        ONode schema = ONode.ofJson(wrapped.inputSchema());
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("when", "today");

        assertThat(schema.get("properties").get("when").hasKey("format")).isFalse();
        assertThat(schema.hasKey("if")).isFalse();
        assertThat(schema.hasKey("then")).isFalse();
        assertThat(schema.get("required").size()).isEqualTo(1);
        assertThat(schema.get("required").get(0).getString()).isEqualTo("when");
        assertThat(wrapped.handle(args)).isEqualTo("handled:today");
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
