package com.jimuqu.solon.claw.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 Profile 内置技能初始化、发行更新和用户修改保护语义。 */
class ProfileBundledSkillSeederTest {
    /** 每个测试使用独立文件树。 */
    @TempDir Path tempDir;

    /** 普通新建会从正式发行目录同步技能、分类说明和来源哈希清单。 */
    @Test
    void seedsFreshProfileAndWritesStableManifest() throws Exception {
        Path bundled = tempDir.resolve("bundled-skills");
        write(
                bundled.resolve("productivity/notes/SKILL.md"),
                "---\nname: official-notes\n---\n# Notes v1\n");
        write(bundled.resolve("productivity/DESCRIPTION.md"), "# Productivity\n");
        ProfileManager manager = manager(ProfileBundledSkillSeeder.fromDirectory(bundled));

        Path profile = manager.createProfile("fresh", new ProfileCreateOptions().setNoAlias(true));

        assertThat(profile.resolve("skills/productivity/notes/SKILL.md"))
                .hasContent("---\nname: official-notes\n---\n# Notes v1\n");
        assertThat(profile.resolve("skills/productivity/DESCRIPTION.md"))
                .hasContent("# Productivity\n");
        assertThat(Files.readString(profile.resolve("skills/.bundled_manifest")))
                .matches("productivity/notes:[0-9a-f]{64}\\n");
    }

    /** 生产构造器会读取显式发行目录属性，而不是退化为空同步器。 */
    @Test
    void productionManagerDiscoversConfiguredDistributionDirectory() throws Exception {
        Path bundled = tempDir.resolve("configured-bundled-skills");
        write(bundled.resolve("core/doctor/SKILL.md"), "# Doctor\n");
        String previous =
                System.getProperty(DefaultProfileBundledSkillSeeder.BUNDLED_SKILLS_PROPERTY);
        try {
            System.setProperty(
                    DefaultProfileBundledSkillSeeder.BUNDLED_SKILLS_PROPERTY, bundled.toString());
            ProfileManager manager =
                    new ProfileManager(
                            tempDir.resolve("production-workspace"),
                            tempDir.resolve("production-bin"),
                            "solonclaw");

            Path profile =
                    manager.createProfile(
                            "configured", new ProfileCreateOptions().setNoAlias(true));

            assertThat(profile.resolve("skills/core/doctor/SKILL.md")).hasContent("# Doctor\n");
        } finally {
            restoreProperty(DefaultProfileBundledSkillSeeder.BUNDLED_SKILLS_PROPERTY, previous);
        }
    }

    /** 正式资源根为空时只保留 Profile 目录，不伪造业务技能或清单。 */
    @Test
    void emptyDistributionDoesNotInventSkills() throws Exception {
        Path bundled = tempDir.resolve("empty-bundled-skills");
        Files.createDirectories(bundled);
        ProfileManager manager = manager(ProfileBundledSkillSeeder.fromDirectory(bundled));

        Path profile =
                manager.createProfile("empty-source", new ProfileCreateOptions().setNoAlias(true));

        assertThat(profile.resolve("skills")).isEmptyDirectory();
        assertThat(profile.resolve("skills/.bundled_manifest")).doesNotExist();
    }

    /** --no-skills 写入永久 opt-out 标记，创建时和后续直接同步都保持空目录。 */
    @Test
    void noSkillsMarkerSkipsCreationAndLaterSync() throws Exception {
        Path bundled = tempDir.resolve("opt-out-bundled-skills");
        write(bundled.resolve("core/builtin/SKILL.md"), "# Builtin\n");
        ProfileBundledSkillSeeder seeder = ProfileBundledSkillSeeder.fromDirectory(bundled);
        ProfileManager manager = manager(seeder);

        Path profile =
                manager.createProfile(
                        "optout", new ProfileCreateOptions().setNoAlias(true).setNoSkills(true));

        assertThat(profile.resolve(".no-bundled-skills")).isRegularFile();
        assertThat(profile.resolve("skills")).isEmptyDirectory();
        assertThat(seeder.seed(profile)).isEmpty();
        assertThat(profile.resolve("skills")).isEmptyDirectory();
    }

    /** clone 只复制来源 Profile 的技能，不额外混入当前发行技能。 */
    @Test
    void cloneDoesNotRunBundledSkillSeederAgain() throws Exception {
        Path bundled = tempDir.resolve("clone-bundled-skills");
        write(bundled.resolve("core/builtin/SKILL.md"), "# Builtin\n");
        ProfileManager manager = manager(ProfileBundledSkillSeeder.fromDirectory(bundled));
        Path root = tempDir.resolve("workspace");
        write(root.resolve("skills/custom/SKILL.md"), "# Custom\n");

        Path profile =
                manager.createProfile(
                        "clone",
                        new ProfileCreateOptions()
                                .setNoAlias(true)
                                .setClone(true)
                                .setCloneFrom("default"));

        assertThat(profile.resolve("skills/custom/SKILL.md")).hasContent("# Custom\n");
        assertThat(profile.resolve("skills/core/builtin/SKILL.md")).doesNotExist();
        assertThat(profile.resolve("skills/.bundled_manifest")).doesNotExist();
    }

    /** 后续同步只更新仍等于旧发行哈希的副本，并保留额外安装技能。 */
    @Test
    void updatesUnmodifiedCopiesAndKeepsAdditionalSkills() throws Exception {
        Path bundled = tempDir.resolve("updated-bundled-skills");
        Path source = bundled.resolve("core/builtin/SKILL.md");
        write(source, "# Builtin v1\n");
        ProfileBundledSkillSeeder seeder = ProfileBundledSkillSeeder.fromDirectory(bundled);
        Path profile = tempDir.resolve("update-profile");

        assertThat(seeder.seed(profile)).containsExactly("builtin");
        write(profile.resolve("skills/custom/SKILL.md"), "# Custom\n");
        write(source, "# Builtin v2\n");

        assertThat(seeder.seed(profile)).containsExactly("builtin");
        assertThat(profile.resolve("skills/core/builtin/SKILL.md")).hasContent("# Builtin v2\n");
        assertThat(profile.resolve("skills/custom/SKILL.md")).hasContent("# Custom\n");
    }

    /** 用户修改后的发行技能不被新版覆盖，用户主动删除的发行技能也不被复活。 */
    @Test
    void preservesModifiedAndDeletedBundledCopies() throws Exception {
        Path bundled = tempDir.resolve("protected-bundled-skills");
        Path source = bundled.resolve("core/builtin/SKILL.md");
        write(source, "# Builtin v1\n");
        ProfileBundledSkillSeeder seeder = ProfileBundledSkillSeeder.fromDirectory(bundled);
        Path profile = tempDir.resolve("protected-profile");
        seeder.seed(profile);
        Path installed = profile.resolve("skills/core/builtin/SKILL.md");

        write(installed, "# User customization\n");
        write(source, "# Builtin v2\n");
        assertThat(seeder.seed(profile)).isEmpty();
        assertThat(installed).hasContent("# User customization\n");

        deleteTree(installed.getParent());
        write(source, "# Builtin v3\n");
        assertThat(seeder.seed(profile)).isEmpty();
        assertThat(installed).doesNotExist();
    }

    /** 首次同步遇到同路径用户技能时不覆盖也不写入发行清单。 */
    @Test
    void keepsPreexistingUserSkillOutsideBundledManifest() throws Exception {
        Path bundled = tempDir.resolve("conflict-bundled-skills");
        Path source = bundled.resolve("core/shared/SKILL.md");
        write(source, "# Official\n");
        ProfileBundledSkillSeeder seeder = ProfileBundledSkillSeeder.fromDirectory(bundled);
        Path profile = tempDir.resolve("conflict-profile");
        Path userSkill = profile.resolve("skills/core/shared/SKILL.md");
        write(userSkill, "# User skill\n");

        assertThat(seeder.seed(profile)).isEmpty();

        assertThat(userSkill).hasContent("# User skill\n");
        assertThat(Files.readString(profile.resolve("skills/.bundled_manifest")))
                .doesNotContain("core/shared:");
    }

    /** 普通 classpath 目录中的正式技能根可以直接同步。 */
    @Test
    void loadsBundledSkillsFromDirectoryClasspath() throws Exception {
        Path classpath = tempDir.resolve("directory-classpath");
        write(
                classpath.resolve("META-INF/solonclaw/skills/core/classpath/SKILL.md"),
                "# Directory classpath\n");
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {classpath.toUri().toURL()}, null)) {
            ProfileBundledSkillSeeder seeder = ProfileBundledSkillSeeder.fromClasspath(loader);
            Path profile = tempDir.resolve("directory-classpath-profile");

            assertThat(seeder.seed(profile)).containsExactly("classpath");
            assertThat(profile.resolve("skills/core/classpath/SKILL.md"))
                    .hasContent("# Directory classpath\n");
        }
    }

    /** 当前 Jar 随附至少一个项目原生技能，fresh Profile 不会退化为空初始化。 */
    @Test
    void loadsPackagedProjectSkill() throws Exception {
        ProfileBundledSkillSeeder seeder =
                ProfileBundledSkillSeeder.fromClasspath(
                        ProfileBundledSkillSeeder.class.getClassLoader());
        Path profile = tempDir.resolve("packaged-profile");

        assertThat(seeder.seed(profile)).contains("plan");
        assertThat(profile.resolve("skills/software-development/plan/SKILL.md")).isRegularFile();
    }

    /** Jar 内 META-INF 正式技能根通过 NIO Jar 文件系统同步。 */
    @Test
    void loadsBundledSkillsFromJarClasspath() throws Exception {
        Path jar = tempDir.resolve("bundled-skills.jar");
        writeJar(
                jar,
                "META-INF/solonclaw/skills/core/jar-skill/SKILL.md",
                "---\nname: jar-official\n---\n# Jar classpath\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            ProfileBundledSkillSeeder seeder = ProfileBundledSkillSeeder.fromClasspath(loader);
            Path profile = tempDir.resolve("jar-classpath-profile");

            assertThat(seeder.seed(profile)).containsExactly("jar-official");
            assertThat(profile.resolve("skills/core/jar-skill/SKILL.md"))
                    .hasContent("---\nname: jar-official\n---\n# Jar classpath\n");
        }
    }

    /** 正式发行源中的链接会使创建事务失败且清除半成品 Profile。 */
    @Test
    void rejectsLinkedDistributionContentAndCleansPartialProfile() throws Exception {
        assumeSymbolicLinksSupported();
        Path bundled = tempDir.resolve("linked-bundled-skills");
        Path skill = bundled.resolve("core/linked");
        write(skill.resolve("SKILL.md"), "# Linked\n");
        write(skill.resolve("target.txt"), "target\n");
        Files.createSymbolicLink(skill.resolve("link.txt"), Paths.get("target.txt"));
        ProfileManager manager = manager(ProfileBundledSkillSeeder.fromDirectory(bundled));

        assertThatThrownBy(
                        () ->
                                manager.createProfile(
                                        "linked", new ProfileCreateOptions().setNoAlias(true)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symbolic links");
        assertThat(manager.profileHome("linked")).doesNotExist();
    }

    /** 用户给发行副本加入链接后按用户修改保留，不读取链接目标也不中断同步。 */
    @Test
    void preservesUserCopyContainingSymbolicLink() throws Exception {
        assumeSymbolicLinksSupported();
        Path bundled = tempDir.resolve("user-link-bundled-skills");
        Path source = bundled.resolve("core/linked/SKILL.md");
        write(source, "# Version 1\n");
        ProfileBundledSkillSeeder seeder = ProfileBundledSkillSeeder.fromDirectory(bundled);
        Path profile = tempDir.resolve("user-link-profile");
        seeder.seed(profile);
        Path installedDirectory = profile.resolve("skills/core/linked");
        write(profile.resolve("outside.txt"), "outside\n");
        Files.createSymbolicLink(
                installedDirectory.resolve("user-link.txt"), Paths.get("../../../outside.txt"));
        write(source, "# Version 2\n");

        assertThat(seeder.seed(profile)).isEmpty();
        assertThat(installedDirectory.resolve("SKILL.md")).hasContent("# Version 1\n");
        assertThat(Files.isSymbolicLink(installedDirectory.resolve("user-link.txt"))).isTrue();
    }

    /** 创建使用固定技能源的被测管理器。 */
    private ProfileManager manager(ProfileBundledSkillSeeder seeder) throws Exception {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        return new ProfileManager(
                root, tempDir.resolve("bin"), "solonclaw", new ProfileDescriptionService(), seeder);
    }

    /** 创建包含显式目录项的 Jar，确保类加载器可枚举正式技能根。 */
    private void writeJar(Path jar, String fileName, String content) throws Exception {
        List<String> directories =
                Arrays.asList(
                        "META-INF/",
                        "META-INF/solonclaw/",
                        "META-INF/solonclaw/skills/",
                        "META-INF/solonclaw/skills/core/",
                        "META-INF/solonclaw/skills/core/jar-skill/");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String directory : directories) {
                output.putNextEntry(new JarEntry(directory));
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry(fileName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    /** 恢复测试前的系统属性。 */
    private void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    /** 在不支持符号链接的平台跳过链接测试。 */
    private void assumeSymbolicLinksSupported() throws Exception {
        Path target = tempDir.resolve("symlink-probe-target");
        Path link = tempDir.resolve("symlink-probe-link");
        Files.write(target, Collections.singletonList("target"), StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
        }
    }

    /** 创建文本文件及其父目录。 */
    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 删除测试目录树。 */
    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] ordered = paths.sorted(Collections.reverseOrder()).toArray(Path[]::new);
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }
}
