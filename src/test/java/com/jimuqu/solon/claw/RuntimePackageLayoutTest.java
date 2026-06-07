package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

public class RuntimePackageLayoutTest {
    @Test
    void shouldBuildTerminalUiAndCopyRuntimeLauncherLayoutFromMavenPackage() throws Exception {
        String pom =
                new String(
                        Files.readAllBytes(Paths.get("pom.xml")),
                        StandardCharsets.UTF_8);

        assertThat(pom)
                .contains("<id>terminal-ui-npm-install</id>")
                .contains("<id>terminal-ui-npm-build</id>")
                .contains("<workingDirectory>${project.basedir}/terminal-ui</workingDirectory>")
                .contains("${project.build.directory}/solon-claw-runtime/bin")
                .contains("${project.build.directory}/solon-claw-runtime/terminal-ui/dist")
                .contains("${project.build.directory}/solon-claw-runtime")
                .contains("${project.build.finalName}.jar");
    }

    @Test
    void shouldLetLauncherRunFromRuntimeHomeLayoutAndDevelopmentTree() throws Exception {
        String launcher =
                new String(
                        Files.readAllBytes(Paths.get("bin/solonclaw")),
                        StandardCharsets.UTF_8);

        assertThat(launcher)
                .contains("SOLONCLAW_RUNTIME_HOME")
                .contains("solon-claw-0.0.1.jar")
                .contains("DEV_JAR=\"$ROOT/target/$DEFAULT_JAR_NAME\"")
                .contains("terminal-ui/dist/entry.js");
    }
}
