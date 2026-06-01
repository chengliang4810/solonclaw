package com.jimuqu.solon.claw.skillhub.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillBundleLoaderTest {
    @Test
    void shouldAutoReloadWhenBundleDirectoryChanges() throws Exception {
        File skillsDir = Files.createTempDirectory("skill-bundle-loader").toFile();
        File bundlesDir = new File(skillsDir, "bundles");
        FileUtil.mkdir(bundlesDir);
        AppConfig config = config(skillsDir);
        SkillBundleLoader loader = new SkillBundleLoader(config);

        writeBundle(new File(bundlesDir, "alpha.json"), "Alpha Bundle", "SKILL.md", "alpha");
        assertThat(loader.listBundles()).extracting(SkillBundle::getName).containsExactly("Alpha Bundle");

        writeBundle(new File(bundlesDir, "beta.json"), "Beta Bundle", "SKILL.md", "beta");

        assertThat(loader.listBundles())
                .extracting(SkillBundle::getName)
                .containsExactly("Alpha Bundle", "Beta Bundle");
    }

    @Test
    void shouldKeepFirstBundleWhenSlugDuplicates() throws Exception {
        File skillsDir = Files.createTempDirectory("skill-bundle-loader-duplicates").toFile();
        File bundlesDir = new File(skillsDir, "bundles");
        FileUtil.mkdir(bundlesDir);
        SkillBundleLoader loader = new SkillBundleLoader(config(skillsDir));

        writeBundle(new File(bundlesDir, "01-first.json"), "Demo Bundle", "SKILL.md", "first");
        writeBundle(new File(bundlesDir, "02-second.json"), "Demo_Bundle", "SKILL.md", "second");

        List<SkillBundle> bundles = loader.reload();

        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).getName()).isEqualTo("Demo Bundle");
        assertThat(bundles.get(0).getFiles()).containsEntry("SKILL.md", "first");
        assertThat(loader.getBundle("demo_bundle").getFiles()).containsEntry("SKILL.md", "first");
    }

    @Test
    void shouldLoadYamlBundleAndFallbackToFileName() throws Exception {
        File skillsDir = Files.createTempDirectory("skill-bundle-loader-yaml").toFile();
        File bundlesDir = new File(skillsDir, "bundles");
        FileUtil.mkdir(bundlesDir);
        SkillBundleLoader loader = new SkillBundleLoader(config(skillsDir));

        File yaml = new File(bundlesDir, "yaml-bundle.yml");
        FileUtil.writeString(
                "files:\n  SKILL.md: yaml-content\nsource: local\nmetadata:\n  channel: test\n",
                yaml,
                StandardCharsets.UTF_8);

        SkillBundle bundle = loader.getBundle("yaml_bundle");

        assertThat(bundle).isNotNull();
        assertThat(bundle.getName()).isEqualTo("yaml-bundle");
        assertThat(bundle.getSource()).isEqualTo("local");
        assertThat(bundle.getFiles()).containsEntry("SKILL.md", "yaml-content");
        assertThat(bundle.getMetadata()).containsEntry("channel", "test");
    }

    private AppConfig config(File skillsDir) {
        AppConfig config = new AppConfig();
        config.getRuntime().setSkillsDir(skillsDir.getAbsolutePath());
        return config;
    }

    private void writeBundle(File file, String name, String path, String content) {
        FileUtil.writeString(
                "{\"name\":\""
                        + name
                        + "\",\"files\":{\""
                        + path
                        + "\":\""
                        + content
                        + "\"}}",
                file,
                StandardCharsets.UTF_8);
    }
}
