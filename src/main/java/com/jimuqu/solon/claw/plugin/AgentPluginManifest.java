package com.jimuqu.solon.claw.plugin;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** plugin.yaml 解析后的清单模型。 */
public class AgentPluginManifest {
    /** 记录Agent插件Manifest中的名称。 */
    private String name;

    /** 记录Agent插件Manifest中的版本。 */
    private String version;

    /** 记录Agent插件Manifest中的描述。 */
    private String description;

    /** 记录Agent插件Manifest中的author。 */
    private String author;

    /** 记录Agent插件Manifest中的kind。 */
    private String kind = "standalone";

    /** 记录Agent插件Manifest中的entry。 */
    private String entry;

    /** 记录Agent插件Manifest中的来源。 */
    private String source;

    /** 记录Agent插件Manifest中的目录。 */
    private Path directory;

    /** 保存requires环境变量集合，维持调用顺序或去重语义。 */
    private List<EnvRequirement> requiresEnv = Collections.emptyList();

    /** 保存provides工具集合，维持调用顺序或去重语义。 */
    private List<String> providesTools = Collections.emptyList();

    /** 标记该配置项或记录是否处于启用状态。 */
    private boolean enabled;

    /**
     * 读取名称。
     *
     * @return 返回读取到的名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 写入名称。
     *
     * @param name 名称参数。
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 读取版本。
     *
     * @return 返回读取到的版本。
     */
    public String getVersion() {
        return version;
    }

    /**
     * 写入版本。
     *
     * @param version 版本参数。
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * 读取Description。
     *
     * @return 返回读取到的Description。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 写入Description。
     *
     * @param description 描述参数。
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 读取Author。
     *
     * @return 返回读取到的Author。
     */
    public String getAuthor() {
        return author;
    }

    /**
     * 写入Author。
     *
     * @param author author 参数。
     */
    public void setAuthor(String author) {
        this.author = author;
    }

    /**
     * 读取Kind。
     *
     * @return 返回读取到的Kind。
     */
    public String getKind() {
        return kind;
    }

    /**
     * 写入Kind。
     *
     * @param kind kind 参数。
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * 读取Entry。
     *
     * @return 返回读取到的Entry。
     */
    public String getEntry() {
        return entry;
    }

    /**
     * 写入Entry。
     *
     * @param entry entry 参数。
     */
    public void setEntry(String entry) {
        this.entry = entry;
    }

    /**
     * 读取来源。
     *
     * @return 返回读取到的来源。
     */
    public String getSource() {
        return source;
    }

    /**
     * 写入来源。
     *
     * @param source 来源参数。
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 读取Directory。
     *
     * @return 返回读取到的Directory。
     */
    public Path getDirectory() {
        return directory;
    }

    /**
     * 写入Directory。
     *
     * @param directory 文件或目录路径参数。
     */
    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    /**
     * 读取Requires Env。
     *
     * @return 返回读取到的Requires Env。
     */
    public List<EnvRequirement> getRequiresEnv() {
        return requiresEnv;
    }

    /**
     * 写入Requires Env。
     *
     * @param requiresEnv requires环境变量参数。
     */
    public void setRequiresEnv(List<EnvRequirement> requiresEnv) {
        this.requiresEnv = requiresEnv;
    }

    /**
     * 读取Provides工具。
     *
     * @return 返回读取到的Provides工具。
     */
    public List<String> getProvidesTools() {
        return providesTools;
    }

    /**
     * 写入Provides工具。
     *
     * @param providesTools providesTools标识或键值。
     */
    public void setProvidesTools(List<String> providesTools) {
        this.providesTools = providesTools;
    }

    /**
     * 判断是否启用。
     *
     * @return 如果启用满足条件则返回 true，否则返回 false。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 写入启用。
     *
     * @param enabled 启用状态开关值。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 是否自动加载（backend/platform 类型自动加载）。 */
    public boolean isAutoLoad() {
        return "backend".equals(kind) || "platform".equals(kind);
    }

    /** 承载环境变量Requirement相关状态和辅助逻辑。 */
    public static class EnvRequirement {
        /** 记录环境变量Requirement中的名称。 */
        private String name;

        /** 记录环境变量Requirement中的描述。 */
        private String description;

        /** 是否启用密钥。 */
        private boolean secret;

        /**
         * 读取名称。
         *
         * @return 返回读取到的名称。
         */
        public String getName() {
            return name;
        }

        /**
         * 写入名称。
         *
         * @param name 名称参数。
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * 读取Description。
         *
         * @return 返回读取到的Description。
         */
        public String getDescription() {
            return description;
        }

        /**
         * 写入Description。
         *
         * @param description 描述参数。
         */
        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * 判断是否密钥。
         *
         * @return 如果密钥满足条件则返回 true，否则返回 false。
         */
        public boolean isSecret() {
            return secret;
        }

        /**
         * 写入密钥。
         *
         * @param secret 签名使用的共享密钥。
         */
        public void setSecret(boolean secret) {
            this.secret = secret;
        }
    }
}
