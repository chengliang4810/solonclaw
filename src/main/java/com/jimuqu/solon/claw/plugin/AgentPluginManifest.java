package com.jimuqu.solon.claw.plugin;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** plugin.yaml 解析后的清单模型。 */
public class AgentPluginManifest {
    private String name;
    private String version;
    private String description;
    private String author;
    private String kind = "standalone";
    private String entry;
    private String source;
    private Path directory;
    private List<EnvRequirement> requiresEnv = Collections.emptyList();
    private List<String> providesTools = Collections.emptyList();
    private boolean enabled;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getEntry() { return entry; }
    public void setEntry(String entry) { this.entry = entry; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Path getDirectory() { return directory; }
    public void setDirectory(Path directory) { this.directory = directory; }

    public List<EnvRequirement> getRequiresEnv() { return requiresEnv; }
    public void setRequiresEnv(List<EnvRequirement> requiresEnv) { this.requiresEnv = requiresEnv; }

    public List<String> getProvidesTools() { return providesTools; }
    public void setProvidesTools(List<String> providesTools) { this.providesTools = providesTools; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** 是否自动加载（backend/platform 类型自动加载）。 */
    public boolean isAutoLoad() {
        return "backend".equals(kind) || "platform".equals(kind);
    }

    public static class EnvRequirement {
        private String name;
        private String description;
        private boolean secret;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isSecret() { return secret; }
        public void setSecret(boolean secret) { this.secret = secret; }
    }
}
