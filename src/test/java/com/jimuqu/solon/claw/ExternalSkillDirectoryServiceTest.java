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
        Files.write(new File(skillDir, "SKILL.md").toPath(),
                "# My Skill\nA test skill".getBytes(StandardCharsets.UTF_8));

        // Create a standalone skill file
        Files.write(new File(extDir, "standalone.md").toPath(),
                "# Standalone\nAnother skill".getBytes(StandardCharsets.UTF_8));

        Props props = new Props();
        props.put("solonclaw.runtime.home", tempDir.getAbsolutePath());
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
        props.put("solonclaw.runtime.home", tempDir.getAbsolutePath());
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
        Files.write(new File(localDir, "local-only/SKILL.md").toPath(),
                "# Local".getBytes(StandardCharsets.UTF_8));
        File skillDir = new File(extDir, "external-only");
        skillDir.mkdirs();
        Files.write(new File(skillDir, "SKILL.md").toPath(),
                "# External".getBytes(StandardCharsets.UTF_8));

        Props props = new Props();
        props.put("solonclaw.runtime.home", tempDir.getAbsolutePath());
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
        assertThat(service.getExternalDirs().get(0).getCanonicalFile()).isEqualTo(extDir.getCanonicalFile());
        assertThat(skills).extracting(SkillDescriptor::getName).containsExactly("external-only");
    }

    @Test
    void shouldReportStatus() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-status").toFile();
        File extDir = new File(tempDir, "skills-ext");
        extDir.mkdirs();

        Props props = new Props();
        props.put("solonclaw.runtime.home", tempDir.getAbsolutePath());
        props.put("solonclaw.skills.externalDirs", extDir.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        Map<String, Object> status = service.status();

        assertThat(status).containsKey("directories");
        assertThat(status).containsKey("totalDirs");
        assertThat(((Number) status.get("totalDirs")).intValue()).isEqualTo(1);
    }

    @Test
    void shouldNotScanReadmeFiles() throws Exception {
        File tempDir = Files.createTempDirectory("ext-skill-readme").toFile();
        File extDir = new File(tempDir, "ext");
        extDir.mkdirs();
        Files.write(new File(extDir, "README.md").toPath(),
                "# README".getBytes(StandardCharsets.UTF_8));

        Props props = new Props();
        props.put("solonclaw.runtime.home", tempDir.getAbsolutePath());
        props.put("solonclaw.skills.externalDirs", extDir.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        ExternalSkillDirectoryService service = new ExternalSkillDirectoryService(config);
        assertThat(service.scanExternalSkills()).isEmpty();
    }
}
