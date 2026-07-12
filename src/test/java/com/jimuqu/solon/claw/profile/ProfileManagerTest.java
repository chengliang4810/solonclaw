package com.jimuqu.solon.claw.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 Profile 管理命令与独立工作区的完整用户可见行为。 */
class ProfileManagerTest {
    /** 每个测试独占的临时目录。 */
    @TempDir Path tempDir;

    /** 默认 Profile 根目录。 */
    private Path root;

    /** 测试专用命令别名目录。 */
    private Path wrapperDir;

    /** 被测 Profile 管理器。 */
    private ProfileManager manager;

    /** 为每个测试创建独立 Profile 根目录，避免污染真实用户目录。 */
    @BeforeEach
    void setUp() throws Exception {
        root = tempDir.resolve("workspace");
        wrapperDir = tempDir.resolve("bin");
        Files.createDirectories(root);
        manager = new ProfileManager(root, wrapperDir, "solonclaw");
    }

    /** `--clone` 复制配置、凭据、人格和技能，但不继承会话数据库与网关运行文件。 */
    @Test
    void cloneCopiesIdentityWithoutRuntimeHistory() throws Exception {
        write(root.resolve("config.yml"), "providers:\n  default:\n    apiKey: clone-secret\n");
        write(root.resolve(".env"), "TOKEN=clone-secret\n");
        write(root.resolve("SOUL.md"), "# source soul\n");
        write(root.resolve("MEMORY.md"), "# source memory\n");
        write(root.resolve("skills/demo/SKILL.md"), "# demo\n");
        write(root.resolve("data/state.db"), "source-state");
        write(root.resolve("gateway.pid"), "{\"pid\":1}");

        runOk("default", "create", "work", "--clone", "--no-alias");

        Path work = manager.profileHome("work");
        assertThat(work.resolve("config.yml")).hasSameTextualContentAs(root.resolve("config.yml"));
        assertThat(work.resolve(".env")).hasSameTextualContentAs(root.resolve(".env"));
        assertThat(work.resolve("SOUL.md")).exists();
        assertThat(work.resolve("MEMORY.md")).exists();
        assertThat(work.resolve("skills/demo/SKILL.md")).exists();
        assertThat(work.resolve("data/state.db")).doesNotExist();
        assertThat(work.resolve("gateway.pid")).doesNotExist();
    }

    /** `--clone-all` 复制 Profile 用户状态，但仍为新 Profile 清除会话历史和运行时网关状态。 */
    @Test
    void cloneAllKeepsProfileFilesButStartsWithFreshRuntimeState() throws Exception {
        write(root.resolve("config.yml"), "model:\n  default: source-model\n");
        write(root.resolve("memory/2026-07-11.md"), "source memory\n");
        write(root.resolve("logs/gateway.log"), "source log\n");
        write(root.resolve("forensics/shutdown.json"), "runtime history\n");
        write(root.resolve("cache/model-context.json"), "reusable cache\n");
        write(root.resolve("workspace/project-notes.md"), "user workspace\n");
        write(root.resolve("data/state.db"), "source-state");
        write(root.resolve("data/user-index.json"), "user data\n");
        write(root.resolve("sessions/session.json"), "source-session\n");
        write(root.resolve("backups/profile.tar.gz"), "source-backup\n");
        write(root.resolve("state-snapshots/snapshot.json"), "source-snapshot\n");
        write(root.resolve("checkpoints/checkpoint.json"), "source-checkpoint\n");
        write(root.resolve("processes.json"), "[]\n");
        write(root.resolve("gateway_state.json"), "{\"status\":\"running\"}");
        write(root.resolve("skills/demo/draft.tmp"), "incomplete write\n");
        write(root.resolve("profiles/ignored/config.yml"), "ignored\n");
        write(root.resolve(".worktrees/ignored/marker.txt"), "ignored\n");
        write(root.resolve("bin/ignored-tool"), "ignored\n");
        write(root.resolve("node_modules/ignored/package.json"), "{}\n");

        runOk(
                "default",
                "create",
                "backup",
                "--clone-from",
                "default",
                "--clone-all",
                "--no-alias");

        Path backup = manager.profileHome("backup");
        assertThat(backup.resolve("config.yml")).exists();
        assertThat(backup.resolve("memory/2026-07-11.md")).exists();
        assertThat(backup.resolve("logs")).doesNotExist();
        assertThat(backup.resolve("forensics")).doesNotExist();
        assertThat(backup.resolve("cache/model-context.json")).exists();
        assertThat(backup.resolve("workspace/project-notes.md")).exists();
        assertThat(backup.resolve("data/state.db")).doesNotExist();
        assertThat(backup.resolve("data/user-index.json")).exists();
        assertThat(backup.resolve("sessions")).doesNotExist();
        assertThat(backup.resolve("backups")).doesNotExist();
        assertThat(backup.resolve("state-snapshots")).doesNotExist();
        assertThat(backup.resolve("checkpoints")).doesNotExist();
        assertThat(backup.resolve("processes.json")).doesNotExist();
        assertThat(backup.resolve("gateway_state.json")).doesNotExist();
        assertThat(backup.resolve("skills/demo/draft.tmp")).doesNotExist();
        assertThat(backup.resolve("profiles")).doesNotExist();
        assertThat(backup.resolve(".worktrees")).doesNotExist();
        assertThat(backup.resolve("bin")).doesNotExist();
        assertThat(backup.resolve("node_modules")).doesNotExist();
    }

    /** named Profile 的 clone-all 只丢根历史，保留同名用户目录与原样符号链接。 */
    @Test
    void cloneAllPreservesNamedProfileDataAndSymbolicLinks() throws Exception {
        assumeSymbolicLinksSupported();
        runOk("default", "create", "source", "--description", "Source role.", "--no-alias");
        Path source = manager.profileHome("source");
        write(source.resolve("profiles/user-owned.txt"), "keep\n");
        write(source.resolve("bin/user-tool"), "keep\n");
        write(source.resolve("data/state.db"), "history\n");
        write(source.resolve("data/user-index.json"), "keep\n");
        write(source.resolve("workspace/backups/user.txt"), "keep\n");
        write(source.resolve("skills/demo/target.txt"), "target\n");
        Files.delete(source.resolve(".env"));
        Files.delete(source.resolve("SOUL.md"));
        Files.createSymbolicLink(source.resolve(".env"), Paths.get("missing.env"));
        Files.createSymbolicLink(source.resolve("SOUL.md"), Paths.get("missing-soul.md"));
        Files.createSymbolicLink(source.resolve("skills/demo/valid-link"), Paths.get("target.txt"));
        Files.createSymbolicLink(
                source.resolve("skills/demo/broken-link"), Paths.get("missing.txt"));

        runOk("default", "create", "clone", "--clone-from", "source", "--clone-all", "--no-alias");

        Path clone = manager.profileHome("clone");
        assertThat(clone.resolve("profiles/user-owned.txt")).hasContent("keep\n");
        assertThat(clone.resolve("bin/user-tool")).hasContent("keep\n");
        assertThat(clone.resolve("data/state.db")).doesNotExist();
        assertThat(clone.resolve("data/user-index.json")).hasContent("keep\n");
        assertThat(clone.resolve("workspace/backups/user.txt")).hasContent("keep\n");
        assertThat(manager.profileView("clone").getDescription()).isEqualTo("Source role.");
        assertThat(Files.isSymbolicLink(clone.resolve(".env"))).isTrue();
        assertThat(Files.readSymbolicLink(clone.resolve(".env")))
                .isEqualTo(Paths.get("missing.env"));
        assertThat(Files.isSymbolicLink(clone.resolve("SOUL.md"))).isTrue();
        assertThat(Files.isSymbolicLink(clone.resolve("skills/demo/valid-link"))).isTrue();
        assertThat(Files.readSymbolicLink(clone.resolve("skills/demo/valid-link")))
                .isEqualTo(Paths.get("target.txt"));
        assertThat(Files.isSymbolicLink(clone.resolve("skills/demo/broken-link"))).isTrue();
        assertThat(Files.readSymbolicLink(clone.resolve("skills/demo/broken-link")))
                .isEqualTo(Paths.get("missing.txt"));
    }

    /** 基础 clone 跟随单文件链接，但保留 skills 树中的有效与断裂链接。 */
    @Test
    void basicCloneMatchesFileAndTreeSymbolicLinkSemantics() throws Exception {
        assumeSymbolicLinksSupported();
        write(root.resolve("source-config.yml"), "model:\n  default: linked-model\n");
        Files.createSymbolicLink(root.resolve("config.yml"), Paths.get("source-config.yml"));
        write(root.resolve("skills/demo/target.txt"), "target\n");
        Files.createSymbolicLink(root.resolve("skills/demo/link"), Paths.get("target.txt"));
        Files.createSymbolicLink(root.resolve("skills/demo/broken"), Paths.get("missing.txt"));

        runOk("default", "create", "linked", "--clone", "--no-alias");

        Path clone = manager.profileHome("linked");
        assertThat(Files.isSymbolicLink(clone.resolve("config.yml"))).isFalse();
        assertThat(clone.resolve("config.yml")).hasContent("model:\n  default: linked-model\n");
        assertThat(Files.isSymbolicLink(clone.resolve("skills/demo/link"))).isTrue();
        assertThat(Files.isSymbolicLink(clone.resolve("skills/demo/broken"))).isTrue();
    }

    /** clone_from 只以 null 表示未提供；显式空值必须按非法名称拒绝。 */
    @Test
    void rejectsExplicitEmptyCloneSource() {
        assertThatThrownBy(
                        () ->
                                manager.createProfile(
                                        "invalid-clone",
                                        new ProfileCreateOptions().setCloneFrom("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
        assertThat(manager.profileHome("invalid-clone")).doesNotExist();
    }

    /** list/use/show/alias/rename/delete 共同维护活动 Profile、别名和目录状态。 */
    @Test
    void managesStickyProfileAliasRenameAndDelete() throws Exception {
        runOk("default", "create", "work", "--no-alias");
        runOk("default", "use", "work");
        assertThat(manager.activeProfile()).isEqualTo("work");
        assertThat(root.resolve("active_profile")).hasContent("work\n");
        assertThat(root.resolve(".active-profile")).doesNotExist();

        runOk("work", "alias", "work", "--name", "work-agent");
        assertThat(wrapperDir.resolve("work-agent")).exists();
        assertThat(Files.readString(wrapperDir.resolve("work-agent")))
                .contains("solonclaw --profile work");

        CommandResult show = runOk("work", "show", "work");
        assertThat(show.stdout)
                .contains("Profile: work")
                .contains(manager.profileHome("work").toString())
                .contains("Gateway:")
                .contains("Skills:");

        runOk("work", "rename", "work", "team");
        assertThat(manager.activeProfile()).isEqualTo("team");
        assertThat(manager.profileHome("work")).doesNotExist();
        assertThat(manager.profileHome("team")).isDirectory();
        assertThat(Files.readString(wrapperDir.resolve("work-agent")))
                .contains("solonclaw --profile team");

        runOk("team", "delete", "team", "-y");
        assertThat(manager.activeProfile()).isEqualTo("default");
        assertThat(manager.profileHome("team")).doesNotExist();
        assertThat(wrapperDir.resolve("work-agent")).doesNotExist();
    }

    /** profile 管理命令在应用启动前运行，因此允许删除本次命令所选 Profile。 */
    @Test
    void deletesProfileSelectedByCurrentBootstrapCommand() throws Exception {
        runOk("default", "create", "work", "--no-alias");
        String previousName = System.getProperty("solonclaw.profile.name");
        try {
            System.setProperty("solonclaw.profile.name", "work");

            CommandResult result = run("work", "delete", "work", "-y");

            assertThat(result.exitCode).isZero();
            assertThat(result.stderr).isEmpty();
            assertThat(manager.profileHome("work")).doesNotExist();
        } finally {
            if (previousName == null) {
                System.clearProperty("solonclaw.profile.name");
            } else {
                System.setProperty("solonclaw.profile.name", previousName);
            }
        }
    }

    /** 删除会收敛只读目录和文件，不因 clone 或分发保留的权限位留下半删状态。 */
    @Test
    void deletesReadOnlyProfileTree() throws Exception {
        runOk("default", "create", "readonly", "--no-alias");
        Path readonly = manager.profileHome("readonly").resolve("skills/locked");
        Path file = readonly.resolve("SKILL.md");
        write(file, "locked\n");
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ));
            Files.setPosixFilePermissions(
                    readonly,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "POSIX permissions unavailable");
        }

        runOk("default", "delete", "readonly", "-y");

        assertThat(manager.profileHome("readonly")).doesNotExist();
    }

    /** 导出归档保留会话与记忆，但不携带明文凭据；导入后得到新的独立 Profile。 */
    @Test
    void exportImportRedactsCredentialsAndRestoresState() throws Exception {
        runOk("default", "create", "source", "--no-alias");
        Path source = manager.profileHome("source");
        write(
                source.resolve("config.yml"),
                "providers:\n  default:\n    apiKey: Sk-Profile-Export-Secret-12345\n");
        write(source.resolve(".env"), "TOKEN=Sk-Env-Export-Secret-12345\n");
        write(source.resolve(".env.example"), "TOKEN=\n");
        write(source.resolve(".env.local"), "TOKEN=local-secret\n");
        write(source.resolve("MEMORY.md"), "# durable memory\n");
        write(source.resolve("data/state.db"), "session-state");
        Path archive = tempDir.resolve("source.tar.gz");

        runOk("default", "export", "source", "-o", archive.toString());
        runOk("default", "import", archive.toString(), "--name", "restored");

        Path restored = manager.profileHome("restored");
        assertThat(restored.resolve("MEMORY.md")).exists();
        assertThat(restored.resolve("data/state.db")).exists();
        assertThat(wrapperDir.resolve("restored")).exists();
        assertThat(Files.readString(wrapperDir.resolve("restored")))
                .contains("solonclaw --profile restored");
        assertThat(Files.readString(restored.resolve("config.yml")))
                .contains("***")
                .doesNotContain("Sk-Profile-Export-Secret-12345");
        assertThat(Files.readString(restored.resolve(".env")))
                .doesNotContain("Sk-Env-Export-Secret-12345");
        assertThat(restored.resolve(".env.example")).hasContent("TOKEN=\n");
        assertThat(restored.resolve(".env.local")).doesNotExist();
    }

    /** default 导出采用 Profile 工件白名单，并保留调用方指定的精确输出路径。 */
    @Test
    void defaultExportUsesPortableAllowListAndPreservesOutputPath() throws Exception {
        write(root.resolve("config.yml"), "model:\n  default: portable\n");
        write(root.resolve("SOUL.md"), "portable soul\n");
        write(root.resolve("skills/demo/SKILL.md"), "# demo\n");
        write(root.resolve("unrelated-project/private.txt"), "do not export\n");
        write(root.resolve("logs/gateway.log"), "runtime log\n");
        write(root.resolve("data/state.db"), "runtime state\n");

        Path archive = manager.exportProfile("default", tempDir.resolve("portable.tgz"));
        Path caseSensitiveArchive =
                manager.exportProfile("default", tempDir.resolve("portable.TGZ"));

        assertThat(archive.getFileName().toString()).isEqualTo("portable.tgz");
        assertThat(caseSensitiveArchive.getFileName().toString()).isEqualTo("portable.TGZ");
        assertThat(archive).isRegularFile();
        Path extracted = tempDir.resolve("default-export");
        assertThat(ProfileArchive.extract(archive, extracted)).isEqualTo("default");
        Path exported = extracted.resolve("default");
        assertThat(exported.resolve("config.yml")).exists();
        assertThat(exported.resolve("SOUL.md")).exists();
        assertThat(exported.resolve("skills/demo/SKILL.md")).exists();
        assertThat(exported.resolve("unrelated-project")).doesNotExist();
        assertThat(exported.resolve("logs")).doesNotExist();
        assertThat(exported.resolve("data")).doesNotExist();
    }

    /** default 白名单目录中的断裂链接会进入归档，不会在复制阶段被跟随或报错。 */
    @Test
    void defaultExportPreservesBrokenLinksInsideAllowedArtifacts() throws Exception {
        assumeSymbolicLinksSupported();
        write(root.resolve("config.yml"), "model:\n  default: portable\n");
        Files.createDirectories(root.resolve("skills/demo"));
        Files.createSymbolicLink(root.resolve("skills/demo/broken-link"), Paths.get("missing.txt"));
        Path archive = tempDir.resolve("default-links.tar.gz");

        manager.exportProfile("default", archive);

        assertThat(archive).isRegularFile();
        assertThatThrownBy(
                        () ->
                                ProfileArchive.extract(
                                        archive, tempDir.resolve("default-links-extract")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("default/skills/demo/broken-link");
    }

    /** named 导出保留链接条目，安全导入必须拒绝链接而不能跟随到归档外。 */
    @Test
    void exportPreservesLinksWhileImportRejectsThem() throws Exception {
        assumeSymbolicLinksSupported();
        runOk("default", "create", "linked-export", "--no-alias");
        Path source = manager.profileHome("linked-export");
        write(source.resolve("skills/demo/target.txt"), "target\n");
        Files.createSymbolicLink(source.resolve("skills/demo/valid-link"), Paths.get("target.txt"));
        Files.createSymbolicLink(
                source.resolve("skills/demo/broken-link"), Paths.get(repeat('x', 150)));
        Path archive = tempDir.resolve("linked-export.tar.gz");

        manager.exportProfile("linked-export", archive);

        assertThat(archive).isRegularFile();
        assertThatThrownBy(() -> manager.importProfile(archive, "linked-export-restored"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported profile archive member type");
        assertThat(manager.profileHome("linked-export-restored")).doesNotExist();
    }

    /** 归档往返恢复普通文件的可执行权限。 */
    @Test
    void exportImportPreservesExecutableFileMode() throws Exception {
        runOk("default", "create", "executable", "--no-alias");
        Path script = manager.profileHome("executable").resolve("scripts/run.sh");
        write(script, "#!/bin/sh\nexit 0\n");
        try {
            Files.setPosixFilePermissions(
                    script,
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "POSIX permissions unavailable");
        }
        Path archive = tempDir.resolve("executable.tar.gz");

        manager.exportProfile("executable", archive);
        manager.importProfile(archive, "executable-restored");

        assertThat(manager.profileHome("executable-restored").resolve("scripts/run.sh"))
                .isExecutable();
    }

    /** 超出 ustar name/prefix 上限的路径通过 PAX 扩展头完成导出和导入。 */
    @Test
    void exportImportSupportsPaxLongPaths() throws Exception {
        runOk("default", "create", "long-path", "--no-alias");
        Path relative = Paths.get(repeat('a', 120), repeat('b', 120), repeat('c', 80) + ".txt");
        write(manager.profileHome("long-path").resolve(relative), "long path\n");
        Path archive = tempDir.resolve("long-path.tar.gz");

        manager.exportProfile("long-path", archive);
        manager.importProfile(archive, "long-path-restored");

        assertThat(manager.profileHome("long-path-restored").resolve(relative))
                .hasContent("long path\n");
    }

    /** import name 的 null 或空白值都从归档唯一根目录推断名称。 */
    @Test
    void importBlankNameInfersArchiveRoot() throws Exception {
        runOk("default", "create", "inferred", "--no-alias");
        write(manager.profileHome("inferred").resolve("marker.txt"), "inferred\n");
        Path archive = tempDir.resolve("inferred.tar.gz");
        manager.exportProfile("inferred", archive);
        manager.deleteProfile("inferred");

        Path imported = manager.importProfile(archive, "  ");

        assertThat(imported).isEqualTo(manager.profileHome("inferred"));
        assertThat(imported.resolve("marker.txt")).hasContent("inferred\n");
    }

    /** 归档成员必须留在临时解压目录内，拒绝绝对路径和父目录穿越。 */
    @Test
    void rejectsUnsafeArchiveMemberPaths() {
        assertThatThrownBy(() -> ProfileArchive.safeRelativePath("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfileArchive.safeRelativePath("/tmp/outside"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfileArchive.safeRelativePath("C:\\outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** install/info 支持本地分发包；force 覆盖分发文件时保留凭据、记忆和会话数据。 */
    @Test
    void installsDistributionAndPreservesUserStateOnForce() throws Exception {
        Path distribution = tempDir.resolve("distribution");
        write(
                distribution.resolve("distribution.yaml"),
                "name: ops\nversion: 1.0.0\ndescription: ops profile\n");
        write(distribution.resolve("config.yml"), "model:\n  default: first-model\n");
        write(distribution.resolve("skills/ops/SKILL.md"), "# first\n");

        runOk("default", "install", "--alias", "--name=ops", "-y", distribution.toString());
        Path installed = manager.profileHome("ops");
        assertThat(installed.resolve("config.yml")).exists();
        assertThat(wrapperDir.resolve("ops")).exists();
        assertThat(runOk("default", "info", "ops").stdout)
                .contains("Version: 1.0.0")
                .contains(distribution.toString());

        write(installed.resolve(".env"), "TOKEN=user-secret\n");
        write(installed.resolve("MEMORY.md"), "# user memory\n");
        write(installed.resolve("data/state.db"), "user-state");
        write(
                distribution.resolve("distribution.yaml"),
                "name: ops\nversion: 2.0.0\ndescription: ops profile v2\n");
        write(distribution.resolve("config.yml"), "model:\n  default: second-model\n");

        runOk("default", "install", "--force", "--yes", distribution.toString());

        assertThat(Files.readString(installed.resolve("config.yml"))).contains("second-model");
        assertThat(Files.readString(installed.resolve(".env"))).contains("user-secret");
        assertThat(Files.readString(installed.resolve("MEMORY.md"))).contains("user memory");
        assertThat(Files.readString(installed.resolve("data/state.db"))).contains("user-state");
        assertThat(runOk("default", "info", "ops").stdout).contains("Version: 2.0.0");
    }

    /** create/describe/no-skills 写入 Profile 局部元数据，并通过结构化视图公开隔离路径。 */
    @Test
    void exposesDescriptionNoSkillsAndIsolationView() throws Exception {
        runOk(
                "default",
                "create",
                "router",
                "--description",
                "Routes focused tasks.",
                "--no-skills",
                "--no-alias");

        ProfileView view = manager.profileView("router");
        assertThat(view.getDescription()).isEqualTo("Routes focused tasks.");
        assertThat(view.isNoBundledSkills()).isTrue();
        assertThat(view.getSoul()).isRegularFile();
        assertThat(Files.readString(view.getSoul())).contains("# SOUL.md");
        assertThat(view.getHome()).isEqualTo(manager.profileHome("router"));
        assertThat(view.getSessions().toString()).endsWith("data/state.db");
        assertThat(view.getMemoryDir().toString()).endsWith("memory");
        assertThat(view.getSkillsDir().toString()).endsWith("skills");
        assertThat(view.getMcpConfig().toString()).endsWith("config.yml");
        assertThat(view.getChannelsConfig().toString()).endsWith("config.yml");
        assertThat(view.getLogs().toString()).endsWith("logs");
        assertThat(runOk("default", "describe", "router").stdout).contains("Routes focused tasks.");

        runOk("default", "describe", "router", "--text", "Updated role.");
        assertThat(manager.profileView("router").getDescription()).isEqualTo("Updated role.");
    }

    /** 新鲜 Profile 默认触发内置技能同步，--no-skills 与克隆路径都不重复同步。 */
    @Test
    void seedsBundledSkillsOnlyForFreshProfiles() throws Exception {
        AtomicInteger seeds = new AtomicInteger();
        ProfileManager seededManager =
                new ProfileManager(
                        root,
                        wrapperDir,
                        "solonclaw",
                        new ProfileDescriptionService(),
                        profileHome -> {
                            seeds.incrementAndGet();
                            write(profileHome.resolve("skills/builtin/SKILL.md"), "# builtin\n");
                            return Collections.singletonList("builtin");
                        });

        seededManager.createProfile("fresh", new ProfileCreateOptions().setNoAlias(true));
        seededManager.createProfile(
                "empty", new ProfileCreateOptions().setNoAlias(true).setNoSkills(true));
        seededManager.createProfile(
                "clone",
                new ProfileCreateOptions().setNoAlias(true).setClone(true).setCloneFrom("default"));

        assertThat(seeds.get()).isEqualTo(1);
        assertThat(seededManager.profileHome("fresh").resolve("skills/builtin/SKILL.md"))
                .isRegularFile();
        assertThat(seededManager.profileHome("empty").resolve("skills")).isEmptyDirectory();
    }

    /** 裸 profile 命令展示当前选择及非密运行摘要，而不是仅打印帮助。 */
    @Test
    void showsSelectedProfileStatusForBareCommand() throws Exception {
        runOk("default", "create", "work", "--no-alias");

        CommandResult result = runOk("work");

        assertThat(result.stdout)
                .contains("Active profile: work")
                .contains("Path:           " + manager.profileHome("work"))
                .contains("Gateway:        stopped")
                .contains("Skills:         1 installed")
                .doesNotContain("Usage: solonclaw profile");
    }

    /** describe 参数冲突返回 2，不会误报成模型或 Profile 运行错误。 */
    @Test
    void rejectsInvalidDescribeArgumentCombinationsWithExitCodeTwo() {
        assertDescribeUsageError("--all requires --auto", "describe", "--all");
        assertDescribeUsageError(
                "--all is mutually exclusive", "describe", "router", "--all", "--auto");
        assertDescribeUsageError(
                "--all is mutually exclusive", "describe", "--all", "--auto", "--text", "manual");
        assertDescribeUsageError("profile name is required", "describe");
        assertDescribeUsageError("profile name is required", "describe", "--auto");
        assertDescribeUsageError(
                "--text is mutually exclusive", "describe", "router", "--text", "manual", "--auto");
    }

    /** 自动描述保护人工文本，--overwrite 可覆盖，自动文本则无需该开关即可重新生成。 */
    @Test
    void protectsManualDescriptionsAndRegeneratesAutomaticDescriptions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        manager =
                managerWithModel(
                        (config, systemPrompt, userPrompt) ->
                                "{\"description\":\"generated-" + calls.incrementAndGet() + "\"}");
        runOk("default", "create", "router", "--no-alias");
        runOk("default", "describe", "router", "--text", "Curated role.");

        CommandResult protectedResult = run("default", "describe", "router", "--auto");
        assertThat(protectedResult.exitCode).isEqualTo(1);
        assertThat(protectedResult.stderr).contains("user-authored description");
        assertThat(calls).hasValue(0);

        assertThat(runOk("default", "describe", "router", "--auto", "--overwrite").stdout)
                .contains("Described 'router': generated-1");
        assertThat(runOk("default", "describe", "router").stdout).isEqualTo("[auto] generated-1\n");

        assertThat(runOk("default", "describe", "router", "--auto").stdout).contains("generated-2");
        assertThat(calls).hasValue(2);
    }

    /** --all 跳过人工说明，单项失败不中断，至少一项成功时整体成功。 */
    @Test
    void sweepsDescribableProfilesAndContinuesAfterOneFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        manager =
                managerWithModel(
                        (config, systemPrompt, userPrompt) -> {
                            int call = calls.incrementAndGet();
                            if (call == 1) {
                                throw new IOException("simulated failure");
                            }
                            return "{\"description\":\"batch success\"}";
                        });
        runOk("default", "create", "alpha", "--no-alias");
        runOk("default", "create", "manual", "--description", "Curated role.", "--no-alias");

        CommandResult result = run("default", "describe", "--all", "--auto");

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).contains("profile describe default: LLM error: IOException");
        assertThat(result.stdout).contains("Described 'alpha': batch success");
        assertThat(manager.profileView("manual").getDescription()).isEqualTo("Curated role.");
        assertThat(calls).hasValue(2);
    }

    /** 全部 Profile 均有人工说明时批量命令直接成功，不调用模型。 */
    @Test
    void skipsModelWhenAllProfilesHaveManualDescriptions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        manager =
                managerWithModel(
                        (config, systemPrompt, userPrompt) -> {
                            calls.incrementAndGet();
                            return "{\"description\":\"unused\"}";
                        });
        runOk("default", "describe", "default", "--text", "Default role.");
        runOk("default", "create", "manual", "--description", "Manual role.", "--no-alias");

        CommandResult result = runOk("default", "describe", "--all", "--auto");

        assertThat(result.stdout).isEqualTo("All profiles already have descriptions.\n");
        assertThat(calls).hasValue(0);
    }

    /** --all 至少发现一个目标但没有任何成功项时整体返回 1。 */
    @Test
    void returnsFailureWhenEveryBatchDescriptionFails() {
        manager =
                managerWithModel(
                        (config, systemPrompt, userPrompt) -> {
                            throw new IOException("simulated failure");
                        });

        CommandResult result = run("default", "describe", "--all", "--auto");

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr).contains("profile describe default: LLM error: IOException");
    }

    /** 基础 clone 只复制 curated MEMORY.md，不复制 daily memory 目录历史。 */
    @Test
    void basicCloneDoesNotCopyDailyMemoryDirectory() throws Exception {
        write(root.resolve("MEMORY.md"), "# curated\n");
        write(root.resolve("memory/2026-07-11.md"), "daily history\n");

        runOk("default", "create", "clean", "--clone", "--no-alias");

        assertThat(manager.profileHome("clean").resolve("MEMORY.md")).exists();
        assertThat(manager.profileHome("clean").resolve("memory/2026-07-11.md")).doesNotExist();
    }

    /** 分发 update 默认保留本机 config.yml，--force-config 才应用远端配置。 */
    @Test
    void updatesDistributionWithoutOverwritingLocalConfigByDefault() throws Exception {
        Path distribution = tempDir.resolve("updatable-distribution");
        write(distribution.resolve("distribution.yaml"), "name: deploy\nversion: 1.0.0\n");
        write(distribution.resolve("config.yml"), "model:\n  default: first-model\n");
        write(distribution.resolve("skills/deploy/SKILL.md"), "# first\n");
        runOk("default", "install", distribution.toString(), "-y");

        Path installed = manager.profileHome("deploy");
        write(installed.resolve("config.yml"), "model:\n  default: local-model\n");
        write(distribution.resolve("distribution.yaml"), "name: deploy\nversion: 2.0.0\n");
        write(distribution.resolve("config.yml"), "model:\n  default: second-model\n");
        write(distribution.resolve("skills/deploy/SKILL.md"), "# second\n");

        runOk("default", "update", "deploy", "-y");
        assertThat(Files.readString(installed.resolve("config.yml"))).contains("local-model");
        assertThat(Files.readString(installed.resolve("skills/deploy/SKILL.md")))
                .contains("second");

        runOk("default", "update", "--force-config", "-y", "deploy");
        assertThat(Files.readString(installed.resolve("config.yml"))).contains("second-model");
        assertThat(runOk("default", "info", "deploy").stdout)
                .contains("Version: 2.0.0")
                .contains("Installed:")
                .contains("Updated:");
    }

    /** distribution_owned 仅替换声明路径，且即使显式声明也不能导入凭据或会话状态。 */
    @Test
    void appliesOnlyDeclaredDistributionPathsAndRejectsProtectedState() throws Exception {
        Path distribution = tempDir.resolve("owned-distribution");
        write(
                distribution.resolve("distribution.yaml"),
                "name: owned\nversion: 1.0.0\ndistribution_owned:\n  - SOUL.md\n");
        write(distribution.resolve("SOUL.md"), "first soul\n");
        write(distribution.resolve("skills/ignored/SKILL.md"), "# ignored\n");

        runOk("default", "install", distribution.toString(), "-y");
        Path installed = manager.profileHome("owned");
        assertThat(installed.resolve("SOUL.md")).hasContent("first soul\n");
        assertThat(installed.resolve("skills/ignored/SKILL.md")).doesNotExist();

        write(
                distribution.resolve("distribution.yaml"),
                "name: owned\nversion: 2.0.0\ndistribution_owned:\n  - .env\n");
        write(distribution.resolve(".env"), "TOKEN=distribution-secret\n");

        CommandResult result = run("default", "update", "owned", "-y");
        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.stderr).contains("protected user data");
        assertThat(Files.readString(installed.resolve(".env")))
                .doesNotContain("distribution-secret");

        write(
                distribution.resolve("distribution.yaml"),
                "name: owned\nversion: 2.0.0\ndistribution_owned:\n  - processes.json\n");
        write(distribution.resolve("processes.json"), "[{\"pid\":1}]\n");
        CommandResult processResult = run("default", "update", "owned", "-y");
        assertThat(processResult.exitCode).isEqualTo(1);
        assertThat(processResult.stderr).contains("protected user data");
        assertThat(installed.resolve("processes.json")).doesNotExist();
    }

    /** distribution_owned 必须是列表，不能静默把错误格式解释成默认所有权范围。 */
    @Test
    void rejectsMalformedDistributionOwnedDeclaration() throws Exception {
        Path distribution = tempDir.resolve("malformed-distribution");
        write(
                distribution.resolve("distribution.yaml"),
                "name: malformed\nversion: 1.0.0\ndistribution_owned: SOUL.md\n");
        write(distribution.resolve("SOUL.md"), "distribution soul\n");

        CommandResult result = run("default", "install", distribution.toString(), "-y");

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.stderr).contains("distribution_owned must be a list");
        assertThat(manager.profileHome("malformed")).doesNotExist();
    }

    /** env_requires 的错误结构必须在创建目标 Profile 前失败。 */
    @Test
    void rejectsMalformedDistributionEnvironmentRequirementsBeforeInstall() throws Exception {
        Path distribution = tempDir.resolve("malformed-env-distribution");
        write(
                distribution.resolve("distribution.yaml"),
                "name: malformed-env\nversion: 1.0.0\nenv_requires:\n  name: API_TOKEN\n");

        CommandResult result = run("default", "install", distribution.toString(), "-y");

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.stderr).contains("env_requires must be a list");
        assertThat(manager.profileHome("malformed-env")).doesNotExist();
    }

    /** 分发版本约束支持单一比较符与裸最低版本，并在创建目标前拒绝不满足的版本。 */
    @Test
    void enforcesDistributionVersionRequirementBeforeInstall() throws Exception {
        Path distribution = tempDir.resolve("versioned-distribution");
        write(
                distribution.resolve("distribution.yaml"),
                "name: compatible\nversion: 1.0.0\nsolonclaw_requires: '>=0.0.1'\n");

        runOk("default", "install", distribution.toString(), "-y");
        assertThat(runOk("default", "info", "compatible").stdout).contains("Requires: >=0.0.1");

        write(
                distribution.resolve("distribution.yaml"),
                "name: future\nversion: 1.0.0\nsolonclaw_requires: 99.0.0\n");
        CommandResult future = run("default", "install", distribution.toString(), "-y");
        assertThat(future.exitCode).isEqualTo(1);
        assertThat(future.stderr).contains("requires solonclaw >=99.0.0");
        assertThat(manager.profileHome("future")).doesNotExist();

        write(
                distribution.resolve("distribution.yaml"),
                "name: malformed-version\nversion: 1.0.0\nsolonclaw_requires: '>=not-a-version'\n");
        CommandResult malformed = run("default", "install", distribution.toString(), "-y");
        assertThat(malformed.exitCode).isEqualTo(1);
        assertThat(malformed.stderr).contains("Invalid solonclaw_requires");
        assertThat(manager.profileHome("malformed-version")).doesNotExist();
    }

    /** 环境变量示例保留必填/可选/default 语义，作者模板优先于清单生成内容。 */
    @Test
    void installsEnvironmentExampleWithRequirementSemanticsAndTemplatePrecedence()
            throws Exception {
        Path generated = tempDir.resolve("generated-env-distribution");
        write(
                generated.resolve("distribution.yaml"),
                "name: generated-env\n"
                        + "env_requires:\n"
                        + "  - name: REQUIRED_TOKEN\n"
                        + "    description: Required token\n"
                        + "  - name: OPTIONAL_URL\n"
                        + "    required: false\n"
                        + "    default: http://127.0.0.1:8080\n");

        runOk("default", "install", generated.toString(), "-y");
        assertThat(manager.profileHome("generated-env").resolve(".env.example"))
                .hasContent(
                        "# Environment variables required by this solonclaw distribution.\n"
                                + "# Copy to `.env` and fill in your own values before running.\n\n"
                                + "# Required token\n"
                                + "# (required)\n"
                                + "REQUIRED_TOKEN=\n\n"
                                + "# (optional)\n"
                                + "# OPTIONAL_URL=http://127.0.0.1:8080\n\n");

        Path templated = tempDir.resolve("templated-env-distribution");
        write(templated.resolve("distribution.yaml"), "name: templated-env\n");
        write(templated.resolve(".env.template"), "CUSTOM_TOKEN=replace-me\n");
        runOk("default", "install", templated.toString(), "-y");
        assertThat(manager.profileHome("templated-env").resolve(".env.example"))
                .hasContent("CUSTOM_TOKEN=replace-me\n");
        assertThat(manager.profileHome("templated-env").resolve(".env.template")).doesNotExist();
    }

    /** env_requires.required 必须是布尔值，避免字符串 false 被误解释为必填。 */
    @Test
    void rejectsNonBooleanDistributionEnvironmentRequirementFlag() throws Exception {
        Path distribution = tempDir.resolve("invalid-required-distribution");
        write(
                distribution.resolve("distribution.yaml"),
                "name: invalid-required\n"
                        + "env_requires:\n"
                        + "  - name: OPTIONAL_TOKEN\n"
                        + "    required: 'false'\n");

        CommandResult result = run("default", "install", distribution.toString(), "-y");

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.stderr).contains("required must be a boolean");
        assertThat(manager.profileHome("invalid-required")).doesNotExist();
    }

    /** 结构化 Profile API 与 CLI 复用相同的说明、别名和分发安全语义。 */
    @Test
    void exposesStructuredDescriptionAliasAndDistributionOperations() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        manager =
                managerWithModel(
                        (config, systemPrompt, userPrompt) -> {
                            calls.incrementAndGet();
                            return "{\"description\":\"Generated role.\"}";
                        });
        runOk("default", "create", "api", "--no-alias");

        assertThat(manager.setDescription("api", "Manual role.").getDescription())
                .isEqualTo("Manual role.");
        assertThat(manager.describeProfile("api", false).isSuccess()).isFalse();
        assertThat(calls).hasValue(0);
        assertThat(manager.describeProfile("api", true).isSuccess()).isTrue();
        assertThat(manager.profileView("api").getDescription()).isEqualTo("Generated role.");

        assertThat(manager.createProfileAlias("api", "api-worker").getAliases())
                .containsExactly("api-worker");
        assertThat(manager.removeProfileAlias("api", "api-worker").getAliases()).isEmpty();

        Path distribution = tempDir.resolve("structured-distribution");
        write(distribution.resolve("distribution.yaml"), "name: structured\nversion: 1.0.0\n");
        write(distribution.resolve("SOUL.md"), "first soul\n");
        ProfileView installed =
                manager.installDistribution(distribution.toString(), null, false, false);
        assertThat(installed.getName()).isEqualTo("structured");
        assertThat(manager.distributionInfo("structured")).containsEntry("version", "1.0.0");

        write(distribution.resolve("distribution.yaml"), "name: structured\nversion: 2.0.0\n");
        write(distribution.resolve("SOUL.md"), "second soul\n");
        assertThat(manager.updateDistribution("structured", false).getDistribution())
                .containsEntry("version", "2.0.0");
        assertThat(manager.profileHome("structured").resolve("SOUL.md"))
                .hasContent("second soul\n");
    }

    /** 命名 Profile 自动获得互不冲突且持久化的 Dashboard/API 端口。 */
    @Test
    void allocatesStableDistinctGatewayPorts() throws Exception {
        runOk("default", "create", "alpha", "--no-alias");
        runOk("default", "create", "beta", "--no-alias");

        java.util.List<String> alphaArgs =
                manager.gatewayServerArguments("alpha", Arrays.asList("--server.host=127.0.0.1"));
        java.util.List<String> betaArgs =
                manager.gatewayServerArguments("beta", java.util.Collections.<String>emptyList());
        Integer alphaPort = manager.gatewayStatus("alpha").getPort();
        Integer betaPort = manager.gatewayStatus("beta").getPort();

        assertThat(alphaPort).isNotNull().isNotEqualTo(betaPort);
        assertThat(alphaArgs).contains("--server.port=" + alphaPort);
        assertThat(betaArgs).contains("--server.port=" + betaPort);
        assertThat(
                        manager.gatewayServerArguments(
                                "alpha", java.util.Collections.<String>emptyList()))
                .contains("--server.port=" + alphaPort);
    }

    /** 并发分配初始候选端口相同的 Profile 时，工作区文件锁必须保证元数据端口互斥。 */
    @Test
    void serializesConcurrentGatewayPortAllocation() throws Exception {
        String firstProfile = "p1n";
        String secondProfile = "p30";
        assertThat(Math.floorMod(firstProfile.hashCode(), 1000))
                .isEqualTo(Math.floorMod(secondProfile.hashCode(), 1000));
        runOk("default", "create", firstProfile, "--no-alias");
        runOk("default", "create", secondProfile, "--no-alias");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<String>> first =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return manager.gatewayServerArguments(
                                        firstProfile, Collections.<String>emptyList());
                            });
            Future<List<String>> second =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return manager.gatewayServerArguments(
                                        secondProfile, Collections.<String>emptyList());
                            });
            assertThat(ready.await(5L, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10L, TimeUnit.SECONDS))
                    .anyMatch(value -> value.startsWith("--server.port="));
            assertThat(second.get(10L, TimeUnit.SECONDS))
                    .anyMatch(value -> value.startsWith("--server.port="));
            assertThat(manager.gatewayStatus(firstProfile).getPort())
                    .isNotEqualTo(manager.gatewayStatus(secondProfile).getPort());
            assertThat(root.resolve("profiles/.gateway-start.lock")).isRegularFile();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Profile 和别名名称只接受安全的单段标识符。 */
    @Test
    void rejectsUnsafeProfileNames() {
        CommandResult result = run("default", "create", "../escape", "--no-alias");
        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.stderr).contains("Invalid profile name");
        assertThat(tempDir.resolve("escape")).doesNotExist();
    }

    /** 固定参数命令拒绝多余位置参数，避免误切换或误操作 Profile。 */
    @Test
    void rejectsUnexpectedArgumentsForFixedArityCommands() throws Exception {
        runOk("default", "create", "work", "--no-alias");

        assertThat(run("default", "use", "work", "unexpected").exitCode).isEqualTo(2);
        assertThat(manager.activeProfile()).isEqualTo("default");
        assertThat(run("default", "show", "work", "unexpected").exitCode).isEqualTo(2);
        assertThat(run("default", "rename", "work", "team", "unexpected").exitCode).isEqualTo(2);
        assertThat(manager.profileHome("work")).isDirectory();
        assertThat(run("default", "info", "work", "unexpected").exitCode).isEqualTo(2);
    }

    /** 子命令选项可与位置参数交错，并支持带值长选项的等号形式。 */
    @Test
    void supportsIntermixedPositionalsAndLongOptionValues() throws Exception {
        runOk("default", "create", "--description=Intermixed role.", "--no-alias", "work");
        assertThat(manager.profileView("work").getDescription()).isEqualTo("Intermixed role.");

        runOk("default", "create", "--clone-from=work", "--no-alias", "copy");
        assertThat(manager.profileHome("copy")).isDirectory();

        runOk("default", "describe", "--text=Updated role.", "work");
        assertThat(manager.profileView("work").getDescription()).isEqualTo("Updated role.");

        runOk("default", "alias", "--name=work-agent", "work");
        assertThat(wrapperDir.resolve("work-agent")).exists();

        Path archive = tempDir.resolve("work-export.tar.gz");
        runOk("default", "export", "--output=" + archive, "work");
        assertThat(archive).isRegularFile();
        runOk("default", "import", "--name=restored", archive.toString());
        assertThat(manager.profileHome("restored")).isDirectory();

        runOk("default", "delete", "--yes", "copy");
        assertThat(manager.profileHome("copy")).doesNotExist();
    }

    /** token 结构错误统一走 stderr/exit 2，领域和文件错误仍保持 exit 1。 */
    @Test
    void distinguishesUsageErrorsFromRuntimeFailures() throws Exception {
        CommandResult unknownAction = run("default", "missing");
        assertUsageError(unknownAction, "Unknown profile subcommand");
        assertUsageError(run("default", "List"), "Unknown profile subcommand");
        assertUsageError(run("default", "help"), "Unknown profile subcommand");
        assertUsageError(
                run("default", "create", "work", "--unknown"), "Unknown profile create option");
        assertUsageError(
                run("default", "create", "work", "--description"),
                "--description requires a value");
        assertUsageError(
                run("default", "create", "work", "--no-alias=true"), "does not accept a value");

        CommandResult runtime = run("default", "show", "missing-profile");
        assertThat(runtime.exitCode).isEqualTo(1);
        assertThat(runtime.stdout).isEmpty();
        assertThat(runtime.stderr).contains("does not exist");
    }

    /** alias 保留原始大小写校验，删除缺失别名必须明确报告 not found。 */
    @Test
    void validatesRawAliasAndReportsMissingRemoval() throws Exception {
        runOk("default", "create", "work", "--no-alias");

        CommandResult uppercase = run("default", "alias", "work", "--name=Work-Agent");
        assertThat(uppercase.exitCode).isEqualTo(1);
        assertThat(uppercase.stderr).contains("Invalid alias name 'Work-Agent'");
        assertThat(wrapperDir.resolve("work-agent")).doesNotExist();

        CommandResult missing =
                runOk("default", "alias", "--remove", "--name=missing-alias", "work");
        assertThat(missing.stdout)
                .contains("No alias 'missing-alias' found to remove.")
                .doesNotContain("Removed alias");
    }

    /** 执行一个预期成功的 Profile 命令。 */
    private CommandResult runOk(String selectedProfile, String... args) throws Exception {
        CommandResult result = run(selectedProfile, args);
        assertThat(result.stderr).isEmpty();
        assertThat(result.exitCode).isZero();
        return result;
    }

    /** 执行 Profile 命令并捕获标准输出和错误输出。 */
    private CommandResult run(String selectedProfile, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode =
                manager.execute(
                        Arrays.asList(args),
                        selectedProfile,
                        new ByteArrayInputStream(new byte[0]),
                        new PrintStream(stdout, true, StandardCharsets.UTF_8),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CommandResult(
                exitCode,
                new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    /** 断言通用命令行用法错误不污染 stdout，并返回退出码 2。 */
    private void assertUsageError(CommandResult result, String message) {
        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr).contains(message);
    }

    /** 创建使用本地模型替身的管理器，确保测试不会发送真实网络请求。 */
    private ProfileManager managerWithModel(ProfileDescriptionService.ModelClient modelClient) {
        return new ProfileManager(
                root, wrapperDir, "solonclaw", new ProfileDescriptionService(modelClient));
    }

    /** 断言 describe 参数错误使用 stderr 和退出码 2。 */
    private void assertDescribeUsageError(String message, String... args) {
        CommandResult result = run("default", args);
        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr).contains("profile describe: ").contains(message);
    }

    /** 在不支持创建链接的平台跳过链接语义测试。 */
    private void assumeSymbolicLinksSupported() throws Exception {
        Path target = tempDir.resolve("symlink-probe-target");
        Path link = tempDir.resolve("symlink-probe-link");
        Files.write(target, new byte[] {1});
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
        }
    }

    /** 构造长路径测试片段，兼容 Java 8。 */
    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }

    /** 创建文件及其父目录。 */
    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 保存一次命令执行结果。 */
    private static class CommandResult {
        /** 进程语义退出码。 */
        private final int exitCode;

        /** 标准输出文本。 */
        private final String stdout;

        /** 标准错误文本。 */
        private final String stderr;

        /** 创建命令执行结果。 */
        private CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
