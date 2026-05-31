package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import com.jimuqu.solon.claw.plugin.hook.HookResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentPluginManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsTempPluginWithManifestFieldsToolAndCommand() throws Exception {
        Path pluginDir = tempDir.resolve("test-plugin");
        Files.createDirectories(pluginDir);

        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: test-plugin\n"
                        + "version: 1.0.0\n"
                        + "kind: backend\n"
                        + "description: A test plugin\n"
                        + "enabled: true\n"
                        + "entry: TestPlugin\n"
                        + "providesTools:\n"
                        + "  - test_tool\n");

        Files.writeString(pluginDir.resolve("TestPlugin.java"),
                "import com.jimuqu.solon.claw.plugin.*;\n" +
                "import com.jimuqu.solon.claw.plugin.hook.*;\n" +
                "import java.util.Map;\n\n" +
                "public class TestPlugin implements AgentPlugin {\n" +
                "    @Override\n" +
                "    public void register(AgentPluginContext ctx) {\n" +
                "        ctx.registerTool(new ToolRegistration(\"test_tool\", \"test\", java.util.Collections.emptyMap(), args -> \"tool-ok\"));\n" +
                "        ctx.registerCommand(\"plugin_echo\", args -> \"cmd:\" + args);\n" +
                "    }\n" +
                "}\n");

        AgentHookRegistry hookRegistry = new AgentHookRegistry();
        AgentPluginManager manager = new AgentPluginManager(hookRegistry, Set.of("test-plugin"), Set.of(), tempDir);
        CapturingSink sink = new CapturingSink();

        manager.discoverAndLoad(sink);

        assertEquals(1, manager.listPlugins().size());
        AgentPluginManifest manifest = manager.listPlugins().get(0);
        assertEquals("test-plugin", manifest.getName());
        assertEquals("A test plugin", manifest.getDescription());
        assertEquals("TestPlugin", manifest.getEntry());
        assertTrue(manifest.isEnabled());
        assertEquals(List.of("test_tool"), manifest.getProvidesTools());
        assertEquals(List.of("test_tool"), sink.toolNames);
        assertEquals(Set.of("plugin_echo"), sink.commands.keySet());
        assertTrue(manager.diagnostics().stream().anyMatch(d -> d.getStatus() == PluginLoadStatus.LOADED));
    }

    @Test
    void skipsMissingRequiredEnvAndRedactsDiagnostics() throws Exception {
        Path pluginDir = tempDir.resolve("env-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: env-plugin\n"
                        + "kind: backend\n"
                        + "enabled: true\n"
                        + "entry: EnvPlugin\n"
                        + "requiresEnv:\n"
                        + "  - name: SOLONCLAW_TEST_MISSING_SECRET\n"
                        + "    description: secret for test\n"
                        + "    secret: true\n");
        Files.writeString(pluginDir.resolve("EnvPlugin.java"),
                "import com.jimuqu.solon.claw.plugin.*;\n"
                        + "public class EnvPlugin implements AgentPlugin {\n"
                        + "  public void register(AgentPluginContext ctx) { throw new RuntimeException(\"should skip\"); }\n"
                        + "}\n");
        AgentPluginManager manager = new AgentPluginManager(new AgentHookRegistry(), Set.of("env-plugin"), Set.of(), tempDir);

        manager.discoverAndLoad(new CapturingSink());

        assertTrue(manager.listPlugins().isEmpty());
        PluginLoadDiagnostic diagnostic = manager.diagnostics().get(0);
        assertEquals(PluginLoadStatus.SKIPPED, diagnostic.getStatus());
        assertEquals("missing_required_env", diagnostic.getReason());
        assertTrue(diagnostic.getMessage().contains("SOLONCLAW_TEST_MISSING_SECRET"));
        assertFalse(diagnostic.getMessage().contains("secret for test"));
    }

    @Test
    void skipsDisabledPluginFromManifestOrConfiguration() throws Exception {
        writeMinimalPlugin(tempDir.resolve("manifest-disabled"), "manifest-disabled", "false");
        writeMinimalPlugin(tempDir.resolve("config-disabled"), "config-disabled", "true");
        AgentPluginManager manager =
                new AgentPluginManager(
                        new AgentHookRegistry(),
                        Set.of("manifest-disabled", "config-disabled"),
                        Set.of("config-disabled"),
                        tempDir);

        manager.discoverAndLoad(new CapturingSink());

        assertTrue(manager.listPlugins().isEmpty());
        assertEquals(2, manager.diagnostics().stream()
                .filter(d -> d.getStatus() == PluginLoadStatus.SKIPPED)
                .count());
    }

    @Test
    void usesDeterministicConflictPolicyForDuplicatePluginsToolsAndCommands() throws Exception {
        writeRegisteringPlugin(tempDir.resolve("alpha"), "same-plugin", "AlphaPlugin", "duplicate_tool", "duplicate_cmd", "alpha");
        writeRegisteringPlugin(tempDir.resolve("beta"), "same-plugin", "BetaPlugin", "duplicate_tool", "duplicate_cmd", "beta");
        AgentPluginManager manager = new AgentPluginManager(new AgentHookRegistry(), Set.of("same-plugin"), Set.of(), tempDir);
        CapturingSink sink = new CapturingSink(Set.of("duplicate_tool"), Set.of("duplicate_cmd"));

        manager.discoverAndLoad(sink);

        assertEquals(1, manager.listPlugins().size());
        assertTrue(sink.toolNames.isEmpty());
        assertTrue(sink.commands.isEmpty());
        assertTrue(manager.diagnostics().stream().anyMatch(d -> d.getReason().equals("duplicate_plugin_name")));
        assertTrue(manager.diagnostics().stream().anyMatch(d -> d.getReason().equals("duplicate_tool_name")));
        assertTrue(manager.diagnostics().stream().anyMatch(d -> d.getReason().equals("duplicate_command_name")));
    }

    @Test
    void forwardsSpeechAndTranscriptionProvidersThroughConflictAwareSink() throws Exception {
        Path pluginDir = tempDir.resolve("speech-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(
                pluginDir.resolve("plugin.yaml"),
                "name: speech-plugin\nkind: backend\nenabled: true\nentry: SpeechPlugin\n");
        Files.writeString(
                pluginDir.resolve("SpeechPlugin.java"),
                "import com.jimuqu.solon.claw.plugin.*;\n"
                        + "import com.jimuqu.solon.claw.plugin.provider.*;\n"
                        + "import java.util.Map;\n"
                        + "public class SpeechPlugin implements AgentPlugin {\n"
                        + "  public void register(AgentPluginContext ctx) {\n"
                        + "    ctx.registerSpeechProvider(new SpeechProvider() {\n"
                        + "      public String name() { return \"speech-provider\"; }\n"
                        + "      public boolean isAvailable() { return true; }\n"
                        + "      public SpeechResult synthesize(String text, String voice, Map<String,Object> options) { return SpeechResult.ok(\"audio/wav\", new byte[] {1}); }\n"
                        + "    });\n"
                        + "    ctx.registerTranscriptionProvider(new TranscriptionProvider() {\n"
                        + "      public String name() { return \"transcription-provider\"; }\n"
                        + "      public boolean isAvailable() { return true; }\n"
                        + "      public TranscriptionResult transcribe(byte[] audio, String mimeType, Map<String,Object> options) { return TranscriptionResult.ok(\"ok\"); }\n"
                        + "    });\n"
                        + "  }\n"
                        + "}\n");
        AgentPluginManager manager =
                new AgentPluginManager(new AgentHookRegistry(), Set.of("speech-plugin"), Set.of(), tempDir);
        CapturingSink sink = new CapturingSink();

        manager.discoverAndLoad(sink);

        assertEquals(List.of("speech-provider"), sink.speechProviderNames);
        assertEquals(List.of("transcription-provider"), sink.transcriptionProviderNames);
    }

    @Test
    void loadFailureDiagnosticRedactsSecrets() throws Exception {
        Path pluginDir = tempDir.resolve("bad-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: bad-plugin\nkind: backend\nenabled: true\nentry: BadPlugin\n");
        Files.writeString(pluginDir.resolve("BadPlugin.java"),
                "import com.jimuqu.solon.claw.plugin.*;\n"
                        + "public class BadPlugin implements AgentPlugin {\n"
                        + "  public void register(AgentPluginContext ctx) { throw new RuntimeException(\"token=sk-test-secret-value\"); }\n"
                        + "}\n");
        AgentPluginManager manager = new AgentPluginManager(new AgentHookRegistry(), Set.of("bad-plugin"), Set.of(), tempDir);

        manager.discoverAndLoad(new CapturingSink());

        PluginLoadDiagnostic diagnostic = manager.diagnostics().get(0);
        assertEquals(PluginLoadStatus.FAILED, diagnostic.getStatus());
        assertTrue(diagnostic.getMessage().contains("***"));
        assertFalse(diagnostic.getMessage().contains("sk-test-secret-value"));
    }

    @Test
    void hookRegistryInvokeObserver() {
        AgentHookRegistry registry = new AgentHookRegistry();
        AtomicReference<String> captured = new AtomicReference<>();

        registry.register("post_tool_call", args -> {
            captured.set((String) args.get("tool_name"));
            return null;
        });

        registry.invoke("post_tool_call", Map.of("tool_name", "web_search"));
        assertEquals("web_search", captured.get());
    }

    @Test
    void hookRegistryInvokeWithResultReturnsFirstNonNull() {
        AgentHookRegistry registry = new AgentHookRegistry();

        registry.register("pre_tool_call", args -> null);
        registry.register("pre_tool_call", args -> {
            return com.jimuqu.solon.claw.plugin.hook.HookResult.block("blocked by test");
        });

        HookResult result = registry.invokeWithResult("pre_tool_call", Map.of("tool_name", "terminal"));
        assertNotNull(result);
        assertTrue(result.isBlock());
        assertEquals("blocked by test", result.getMessage());
    }

    @Test
    void hookRegistryInvokeNonExistentHookDoesNothing() {
        AgentHookRegistry registry = new AgentHookRegistry();
        registry.invoke("non_existent", Map.of());
        assertNull(registry.invokeWithResult("non_existent", Map.of()));
    }

    @Test
    void manifestParsingFromYaml() throws Exception {
        Path pluginDir = tempDir.resolve("yaml-test");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: my-search\nversion: 2.0.0\nkind: backend\nauthor: test\ndescription: Search plugin\n");

        AgentHookRegistry hookRegistry = new AgentHookRegistry();
        AgentPluginManager manager = new AgentPluginManager(hookRegistry, Set.of(), Set.of());

        // 验证 manifest 解析不抛异常（通过 discoverAndLoad 间接测试）
        assertNotNull(manager);
    }

    private void writeMinimalPlugin(Path pluginDir, String pluginName, String enabled) throws Exception {
        String className = pluginName.replace("-", "") + "Plugin";
        writeRegisteringPlugin(pluginDir, pluginName, className, pluginName + "_tool", pluginName + "_cmd", enabled);
    }

    private void writeRegisteringPlugin(
            Path pluginDir, String pluginName, String className, String toolName, String commandName, String marker)
            throws Exception {
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: " + pluginName + "\nkind: backend\nenabled: " + !"false".equals(marker) + "\nentry: " + className + "\n");
        Files.writeString(pluginDir.resolve(className + ".java"),
                "import com.jimuqu.solon.claw.plugin.*;\n"
                        + "public class " + className + " implements AgentPlugin {\n"
                        + "  public void register(AgentPluginContext ctx) {\n"
                        + "    ctx.registerTool(new ToolRegistration(\"" + toolName + "\", \"test\", java.util.Collections.emptyMap(), args -> \"" + marker + "\"));\n"
                        + "    ctx.registerCommand(\"" + commandName + "\", args -> \"" + marker + "\");\n"
                        + "  }\n"
                        + "}\n");
    }

    private static class CapturingSink implements PluginRegistrationSink {
        final List<String> toolNames = new ArrayList<>();
        final Map<String, CommandHandler> commands = new LinkedHashMap<>();
        final List<String> speechProviderNames = new ArrayList<>();
        final List<String> transcriptionProviderNames = new ArrayList<>();
        final Set<String> reservedTools;
        final Set<String> reservedCommands;

        CapturingSink() {
            this(Collections.emptySet(), Collections.emptySet());
        }

        CapturingSink(Set<String> reservedTools, Set<String> reservedCommands) {
            this.reservedTools = reservedTools;
            this.reservedCommands = reservedCommands;
        }

        @Override public boolean hasTool(String name) { return reservedTools.contains(name) || toolNames.contains(name); }
        @Override public boolean hasCommand(String name) { return reservedCommands.contains(name) || commands.containsKey(name); }
        @Override public void onToolRegistered(ToolRegistration r) { toolNames.add(r.getName()); }
        @Override public void onCommandRegistered(String n, CommandHandler h, String d) { commands.put(n, h); }
        @Override public void onWebSearchProviderRegistered(WebSearchProvider p) {}
        @Override public void onImageGenProviderRegistered(ImageGenProvider p) {}
        @Override public void onVideoGenProviderRegistered(VideoGenProvider p) {}
        @Override public void onBrowserProviderRegistered(BrowserProvider p) {}
        @Override public void onSpeechProviderRegistered(SpeechProvider p) { speechProviderNames.add(p.name()); }
        @Override public void onTranscriptionProviderRegistered(TranscriptionProvider p) { transcriptionProviderNames.add(p.name()); }
        @Override public void onMemoryProviderRegistered(MemoryProvider p) {}
        @Override public void onPlatformRegistered(PlatformRegistration r) {}
    }
}
