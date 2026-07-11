package com.jimuqu.solon.claw.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

/** 验证 Profile 自动描述的解析、持久化和有界技能采样。 */
class ProfileDescriptionServiceTest {
    /** 每个测试独占的 Profile 工作区。 */
    @TempDir Path tempDir;

    /** 代码围栏和嵌入文本中的 JSON 都能提取 description。 */
    @Test
    void parsesFencedAndEmbeddedJson() {
        assertThat(
                        ProfileDescriptionService.parseDescription(
                                        "```json\n{\"description\":\"fenced result\"}\n```")
                                .getDescription())
                .isEqualTo("fenced result");
        assertThat(
                        ProfileDescriptionService.parseDescription(
                                        "prefix {\"description\":\"embedded result\"} suffix")
                                .getDescription())
                .isEqualTo("embedded result");
    }

    /** 非 JSON 回退首个自然段，并把最终说明截断为 280 字符。 */
    @Test
    void fallsBackToFirstParagraphAndLimitsLength() {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 300; index++) {
            source.append('x');
        }
        String longText = source.append("\n\nignored paragraph").toString();

        ProfileDescriptionService.ParseOutcome outcome =
                ProfileDescriptionService.parseDescription(longText);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getDescription()).hasSize(280).doesNotContain("ignored");
    }

    /** 截断按 Unicode 字符计数，不会切断补充平面的代理对。 */
    @Test
    void limitsDescriptionWithoutSplittingUnicodeCharacters() {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 281; index++) {
            source.appendCodePoint(0x1F680);
        }

        String description =
                ProfileDescriptionService.parseDescription(source.toString()).getDescription();

        assertThat(description.codePointCount(0, description.length())).isEqualTo(280);
        assertThat(description.codePointAt(description.offsetByCodePoints(0, 279)))
                .isEqualTo(0x1F680);
    }

    /** 空响应和合法 JSON 中缺失 description 字段均返回明确失败。 */
    @Test
    void rejectsEmptyResponseAndMissingDescriptionField() {
        ProfileDescriptionService.ParseOutcome empty =
                ProfileDescriptionService.parseDescription("  ");
        ProfileDescriptionService.ParseOutcome missing =
                ProfileDescriptionService.parseDescription("{\"summary\":\"wrong field\"}");

        assertThat(empty.isSuccess()).isFalse();
        assertThat(empty.getReason()).contains("empty response");
        assertThat(missing.isSuccess()).isFalse();
        assertThat(missing.getReason()).contains("missing 'description'");
    }

    /** 自动生成仅把名称、模型、Provider 和技能索引发给模型，并写入自动标记。 */
    @Test
    void writesAutomaticMetadataWithoutReadingMemory() throws Exception {
        write(tempDir.resolve("skills/dev/build/SKILL.md"), "# build\n");
        write(tempDir.resolve("MEMORY.md"), "private-memory-marker\n");
        write(
                tempDir.resolve("config.yml"),
                "providers:\n"
                        + "  default:\n"
                        + "    name: Test\n"
                        + "    baseUrl: https://example.invalid\n"
                        + "    apiKey: ignored\n"
                        + "    defaultModel: test-model\n"
                        + "    dialect: openai-responses\n"
                        + "model:\n"
                        + "  providerKey: default\n");
        AtomicReference<String> prompt = new AtomicReference<String>();
        ProfileDescriptionService service =
                new ProfileDescriptionService(
                        (config, systemPrompt, userPrompt) -> {
                            prompt.set(userPrompt);
                            return "{\"description\":\"Builds Java projects.\"}";
                        });

        ProfileDescriptionService.DescribeOutcome outcome =
                service.describe("builder", tempDir, false);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(prompt.get())
                .contains("Profile name: builder")
                .contains("Default model: test-model")
                .contains("Provider: default")
                .contains("Installed skill count: 1")
                .contains("dev/build")
                .doesNotContain("private-memory-marker");
        Map<?, ?> metadata =
                ONode.deserialize(Files.readString(tempDir.resolve(".profile.json")), Map.class);
        assertThat(metadata.get("description")).isEqualTo("Builds Java projects.");
        assertThat(metadata.get("description_auto")).isEqualTo(Boolean.TRUE);
    }

    /** 超过 60 个技能时使用稳定均匀样本，同时保留未截断总数。 */
    @Test
    void evenlySamplesSixtySkillsAndKeepsTotalCount() throws Exception {
        for (int index = 0; index < 120; index++) {
            write(
                    tempDir.resolve(String.format("skills/group/skill-%03d/SKILL.md", index)),
                    "# skill\n");
        }

        ProfileDescriptionService.SkillInventory inventory =
                ProfileDescriptionService.collectSkillInventory(tempDir);

        assertThat(inventory.getTotalCount()).isEqualTo(120);
        assertThat(inventory.getSampledNames()).hasSize(60);
        assertThat(inventory.getSampledNames().get(0)).isEqualTo("group/skill-000");
        assertThat(inventory.getSampledNames().get(1)).isEqualTo("group/skill-002");
        assertThat(inventory.getSampledNames().get(59)).isEqualTo("group/skill-118");
    }

    /** 创建测试文件及其父目录。 */
    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
