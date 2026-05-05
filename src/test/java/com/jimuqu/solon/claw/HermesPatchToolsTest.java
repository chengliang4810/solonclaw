package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.HermesPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class HermesPatchToolsTest {
    @Test
    void shouldReplaceUniqueString() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("src/app.txt");
        Files.createDirectories(file.getParent());
        Files.write(file, "hello\nworld\n".getBytes(StandardCharsets.UTF_8));

        HermesPatchTools tools = new HermesPatchTools(dir.toString());
        String json = tools.patch("replace", "src/app.txt", "hello", "hi", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(result.get("files_modified"))).contains("src/app.txt");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("hi\nworld\n");
    }

    @Test
    void shouldRejectAmbiguousReplaceUnlessReplaceAll() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("dup.txt");
        Files.write(file, "x\nx\n".getBytes(StandardCharsets.UTF_8));

        HermesPatchTools tools = new HermesPatchTools(dir.toString());
        String json = tools.patch("replace", "dup.txt", "x", "y", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error"))).contains("Found 2 matches");
    }

    @Test
    void shouldApplyV4aPatchAtomicallyAfterValidation() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve("app.txt");
        Files.write(file, "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8));

        HermesPatchTools tools = new HermesPatchTools(dir.toString());
        String patch =
                "*** Begin Patch\n"
                        + "*** Update File: app.txt\n"
                        + "@@ beta @@\n"
                        + " alpha\n"
                        + "-beta\n"
                        + "+gamma\n"
                        + "*** Add File: new.txt\n"
                        + "+created\n"
                        + "*** End Patch";
        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("alpha\ngamma\n");
        assertThat(new String(Files.readAllBytes(dir.resolve("new.txt")), StandardCharsets.UTF_8))
                .isEqualTo("created");
    }

    @Test
    void shouldRejectTraversalBeforeWriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        HermesPatchTools tools = new HermesPatchTools(dir.toString());

        String json = tools.patch("replace", "../outside.txt", "a", "b", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error"))).contains("越权");
    }

    @Test
    void shouldBlockCredentialFilesInsidePatchToolItself() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        Path file = dir.resolve(".env.production");
        Files.write(file, "TOKEN=old\n".getBytes(StandardCharsets.UTF_8));
        HermesPatchTools tools =
                new HermesPatchTools(dir.toString(), new SecurityPolicyService(new AppConfig()));

        String json = tools.patch("replace", ".env.production", "old", "new", false, null);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error"))).contains("BLOCKED").contains("敏感");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .isEqualTo("TOKEN=old\n");
    }

    @Test
    void shouldBlockCredentialFilesInV4aPatchBeforeWriting() throws Exception {
        Path dir = Files.createTempDirectory("jimuqu-patch-test");
        HermesPatchTools tools =
                new HermesPatchTools(dir.toString(), new SecurityPolicyService(new AppConfig()));
        String patch =
                "*** Begin Patch\n"
                        + "*** Add File: .env.local\n"
                        + "+TOKEN=new\n"
                        + "*** End Patch";

        String json = tools.patch("patch", null, null, null, null, patch);

        Map<?, ?> result = parse(json);
        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(result.get("error"))).contains("BLOCKED").contains(".env.local");
        assertThat(Files.exists(dir.resolve(".env.local"))).isFalse();
    }

    private Map<?, ?> parse(String json) {
        return ONode.deserialize(json, java.util.LinkedHashMap.class);
    }
}
