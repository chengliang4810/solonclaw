package com.jimuqu.solon.claw.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Describes a registered slash command shared by gateway, CLI, and TUI. */
public final class CommandDescriptor {
    private final String name;
    private final List<String> aliases;
    private final String category;
    private final String description;
    private final List<String> scopes;
    private final boolean enabledByDefault;

    private CommandDescriptor(Builder builder) {
        this.name = builder.name;
        this.aliases = Collections.unmodifiableList(new ArrayList<String>(builder.aliases));
        this.category = builder.category;
        this.description = builder.description;
        this.scopes = Collections.unmodifiableList(new ArrayList<String>(builder.scopes));
        this.enabledByDefault = builder.enabledByDefault;
    }

    public String getName() {
        return name;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    public String slashName() {
        return "/" + name;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final List<String> aliases = new ArrayList<String>();
        private String category;
        private String description;
        private final List<String> scopes = new ArrayList<String>();
        private boolean enabledByDefault = true;

        private Builder(String name) {
            this.name = normalize(name);
        }

        public Builder alias(String alias) {
            this.aliases.add(normalize(alias));
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder scopes(String... scopes) {
            Collections.addAll(this.scopes, scopes);
            return this;
        }

        public Builder enabledByDefault(boolean enabledByDefault) {
            this.enabledByDefault = enabledByDefault;
            return this;
        }

        public CommandDescriptor build() {
            return new CommandDescriptor(this);
        }
    }

    static String normalize(String command) {
        String value = command == null ? "" : command.trim().toLowerCase();
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
