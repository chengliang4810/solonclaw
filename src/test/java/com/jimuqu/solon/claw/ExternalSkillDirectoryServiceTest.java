package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.ExternalSkillDirectoryService;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class ExternalSkillDirectoryServiceTest {
    @Test
    void shouldScanExternalSkillDirectories() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-test").toFile();
        File extDir = new File(tempDir, "external-skills");
        extDir.mkdirs();

        // Create a skill directory with SKILL.md
        File skillDir = new File(extDir, "my-skill");
        skillDir.mkdirs();
        Files.write(
                new File(skillDir, "SKILL.md").toPath(),
                "# My Skill\nA test skill".getBytes(StandardCharsets.UTF_8));

        // Create a standalone skill file
        Files.write(
                new File(extDir, "standalone.md").toPath(),
                "# Standalone\nAnother skill".getBytes(StandardCharsets.UTF_8));

        Props props = new Props();
        props.put("solonclaw.workspace", tempDir.getAbsolutePath());
        props.put("solonclaw.skills.externalDirs", extDir.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        List<SkillDescriptor> skills = service.scanExternalSkills();

        assertThat(skills).hasSize(2);
        assertThat(skills.get(0).getName()).isEqualTo("my-skill");
        assertThat(skills.get(0).getTrustLevel()).isEqualTo("external");
        assertThat(skills.get(1).getName()).isEqualTo("standalone");
    }

    @Test
    void shouldReturnEmptyWhenDirNotExists() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-empty").toFile();
        Props props = new Props();
        props.put("solonclaw.workspace", tempDir.getAbsolutePath());
        props.put("solonclaw.skills.externalDirs", "/nonexistent/path");
        AppConfig config = AppConfig.load(props);

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        List<SkillDescriptor> skills = service.scanExternalSkills();

        assertThat(skills).isEmpty();
    }

    @Test
    void shouldNormalizeAndDedupeExternalSkillRoots() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-roots").toFile();
        File localDir = new File(tempDir, "skills");
        File extDir = new File(tempDir, "external-skills");
        new File(localDir, "local-only").mkdirs();
        extDir.mkdirs();
        Files.write(
                new File(localDir, "local-only/SKILL.md").toPath(),
                "# Local".getBytes(StandardCharsets.UTF_8));
        File skillDir = new File(extDir, "external-only");
        skillDir.mkdirs();
        Files.write(
                new File(skillDir, "SKILL.md").toPath(),
                "# External".getBytes(StandardCharsets.UTF_8));

        Props props = new Props();
        props.put("solonclaw.workspace", tempDir.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        config.getSkills()
                .setExternalDirs(
                        Arrays.asList(
                                config.getRuntime().getSkillsDir(),
                                extDir.getAbsolutePath(),
                                new File(tempDir, "external-skills/../external-skills").getPath()));

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        List<SkillDescriptor> skills = service.scanExternalSkills();

        assertThat(service.getExternalDirs()).hasSize(1);
        assertThat(service.getExternalDirs().get(0).getCanonicalFile())
                .isEqualTo(extDir.getCanonicalFile());
        assertThat(skills).extracting(SkillDescriptor::getName).containsExactly("external-only");
    }

    @Test
    void shouldReportStatus() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-status").toFile();
        File extDir = new File(tempDir, "skills-ext");
        extDir.mkdirs();
        File skillDir = new File(extDir, "summary-skill");
        skillDir.mkdirs();
        Files.write(
                new File(skillDir, "SKILL.md").toPath(),
                "# Summary".getBytes(StandardCharsets.UTF_8));

        Props props = new Props();
        props.put("solonclaw.workspace", tempDir.getAbsolutePath());
        props.put("solonclaw.skills.externalDirs", extDir.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        Map<String, Object> status = service.status();

        assertThat(status).containsKey("directories");
        assertThat(status).containsKey("totalDirs");
        assertThat(status).containsKey("configuredDirs");
        assertThat(status).containsKey("normalizedDirs");
        assertThat(status).containsKey("duplicateDirs");
        assertThat(status).containsKey("cacheHit");
        assertThat(((Number) status.get("totalDirs")).intValue()).isEqualTo(1);
        assertThat(((Number) status.get("configuredDirs")).intValue()).isEqualTo(1);
        assertThat(((Number) status.get("normalizedDirs")).intValue()).isEqualTo(1);
        assertThat(((Number) status.get("duplicateDirs")).intValue()).isEqualTo(0);
        assertThat(status.get("cacheHit")).isEqualTo(Boolean.TRUE);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> directories =
                (List<Map<String, Object>>) status.get("directories");
        assertThat(directories).hasSize(1);
        assertThat(directories.get(0).get("path"))
                .isEqualTo(directories.get(0).get("normalizedPath"));
        assertThat(directories.get(0).get("configuredPath")).isEqualTo(extDir.getAbsolutePath());
        assertThat(directories.get(0).get("normalizedPath"))
                .isEqualTo(service.getExternalDirs().get(0).getAbsolutePath());
        assertThat(directories.get(0).get("included")).isEqualTo(Boolean.TRUE);
        assertThat(directories.get(0).get("duplicate")).isEqualTo(Boolean.FALSE);
        assertThat(directories.get(0).get("local")).isEqualTo(Boolean.FALSE);
        assertThat(directories.get(0).get("skillCount")).isEqualTo(Integer.valueOf(1));
    }

    @Test
    void shouldExposeNormalizedSummaryForDuplicateAndLocalRoots() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-status-summary").toFile();
        File localDir = new File(tempDir, "skills");
        File extDir = new File(tempDir, "skills-ext");
        localDir.mkdirs();
        extDir.mkdirs();

        Props props = new Props();
        props.put("solonclaw.workspace", tempDir.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        config.getSkills()
                .setExternalDirs(
                        Arrays.asList(
                                config.getRuntime().getSkillsDir(),
                                extDir.getAbsolutePath(),
                                new File(tempDir, "skills-ext/../skills-ext").getPath()));

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        Map<String, Object> status = service.status();

        assertThat(((Number) status.get("totalDirs")).intValue()).isEqualTo(1);
        assertThat(((Number) status.get("configuredDirs")).intValue()).isEqualTo(3);
        assertThat(((Number) status.get("normalizedDirs")).intValue()).isEqualTo(1);
        assertThat(((Number) status.get("duplicateDirs")).intValue()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> directories =
                (List<Map<String, Object>>) status.get("directories");
        assertThat(directories).hasSize(3);
        assertThat(directories.get(0).get("local")).isEqualTo(Boolean.TRUE);
        assertThat(directories.get(0).get("included")).isEqualTo(Boolean.FALSE);
        assertThat(directories.get(1).get("duplicate")).isEqualTo(Boolean.FALSE);
        assertThat(directories.get(1).get("included")).isEqualTo(Boolean.TRUE);
        assertThat(directories.get(2).get("duplicate")).isEqualTo(Boolean.TRUE);
        assertThat(directories.get(2).get("included")).isEqualTo(Boolean.FALSE);
        assertThat(directories.get(1).get("normalizedPath"))
                .isEqualTo(directories.get(2).get("normalizedPath"));
        assertThat(directories.get(1).get("normalizedPath"))
                .isEqualTo(service.getExternalDirs().get(0).getAbsolutePath());
    }

    @Test
    void shouldReuseCachedScanResultWhenDirectoryStateUnchanged() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-cache").toFile();
        File extDir = new File(tempDir, "external-skills");
        extDir.mkdirs();

        File skillDir = new File(extDir, "cached-skill");
        skillDir.mkdirs();
        Files.write(
                new File(skillDir, "SKILL.md").toPath(),
                "# Cached Skill".getBytes(StandardCharsets.UTF_8));

        Props props = new Props();
        props.put("solonclaw.workspace", tempDir.getAbsolutePath());
        props.put("solonclaw.skills.externalDirs", extDir.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        List<SkillDescriptor> firstScan = service.scanExternalSkills();
        List<SkillDescriptor> secondScan = service.scanExternalSkills();

        assertThat(firstScan).hasSize(1);
        assertThat(secondScan).hasSize(1);
        assertThat(firstScan).isNotSameAs(secondScan);
        assertThat(firstScan.get(0)).isSameAs(secondScan.get(0));

        Map<String, Object> firstStatus = service.status();
        Map<String, Object> secondStatus = service.status();

        assertThat(firstStatus.get("cacheHit")).isEqualTo(Boolean.TRUE);
        assertThat(secondStatus.get("cacheHit")).isEqualTo(Boolean.TRUE);
        assertThat(firstStatus.get("directories")).isEqualTo(secondStatus.get("directories"));
    }

    @Test
    void shouldNotScanReadmeFiles() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-readme").toFile();
        File extDir = new File(tempDir, "ext");
        extDir.mkdirs();
        Files.write(
                new File(extDir, "README.md").toPath(),
                "# README".getBytes(StandardCharsets.UTF_8));

        Props props = new Props();
        props.put("solonclaw.workspace", tempDir.getAbsolutePath());
        props.put("solonclaw.skills.externalDirs", extDir.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        assertThat(service.scanExternalSkills()).isEmpty();
    }
}
