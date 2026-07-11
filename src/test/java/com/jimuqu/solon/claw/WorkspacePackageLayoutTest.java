package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

public class WorkspacePackageLayoutTest {
    @Test
    void shouldBuildTerminalUiAndCopyWorkspaceLauncherLayoutFromMavenPackage() throws Exception {
        String pom = new String(Files.readAllBytes(Paths.get("pom.xml")), StandardCharsets.UTF_8);

        assertThat(pom)
                .contains("<id>terminal-ui-npm-install</id>")
                .contains("<id>terminal-ui-npm-build</id>")
                .contains("<workingDirectory>${project.basedir}/terminal-ui</workingDirectory>")
                .contains("${project.build.directory}/solonclaw-workspace/bin")
                .contains("${project.build.directory}/solonclaw-workspace/terminal-ui/dist")
                .contains("${project.build.directory}/solonclaw-workspace")
                .contains("${project.build.finalName}.jar");
    }

    @Test
    void shouldLetLauncherRunFromWorkspacePackageLayoutAndDevelopmentTree() throws Exception {
        String launcher =
                new String(Files.readAllBytes(Paths.get("bin/solonclaw")), StandardCharsets.UTF_8);

        assertThat(launcher)
                .contains("SOLONCLAW_WORKSPACE")
                .contains("-Dsolonclaw.workspace")
                .contains("solonclaw-0.0.1.jar")
                .contains("DEV_JAR=\"$ROOT/target/$DEFAULT_JAR_NAME\"")
                .contains("terminal-ui/dist/entry.js");
    }
}
