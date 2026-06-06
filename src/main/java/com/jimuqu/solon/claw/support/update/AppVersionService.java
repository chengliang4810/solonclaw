package com.jimuqu.solon.claw.support.update;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.SolonClawApp;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 应用版本与部署形态识别服务。 */
public class AppVersionService {
    /** 默认REPO的统一常量值。 */
    private static final String DEFAULT_REPO = "chengliang4810/solon-claw";

    /** 注入应用配置，用于应用版本。 */
    private final AppConfig appConfig;

    /**
     * 创建App版本服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public AppVersionService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 执行当前版本相关逻辑。
     *
     * @return 返回当前版本结果。
     */
    public String currentVersion() {
        Package pkg = SolonClawApp.class.getPackage();
        if (pkg != null && StrUtil.isNotBlank(pkg.getImplementationVersion())) {
            return pkg.getImplementationVersion().trim();
        }

        String pomVersion = loadPomPropertiesVersion();
        if (StrUtil.isNotBlank(pomVersion)) {
            return pomVersion;
        }

        String projectVersion = readPomXmlVersion();
        return StrUtil.blankToDefault(projectVersion, "0.0.1");
    }

    /**
     * 执行当前Tag相关逻辑。
     *
     * @return 返回当前Tag结果。
     */
    public String currentTag() {
        return "v" + stripLeadingV(currentVersion());
    }

    /**
     * 执行releaseRepo相关逻辑。
     *
     * @return 返回release Repo结果。
     */
    public String releaseRepo() {
        String override = configValue("solonclaw.update.repo");
        return StrUtil.blankToDefault(override, DEFAULT_REPO).trim();
    }

    /**
     * 执行releaseApiURL相关逻辑。
     *
     * @return 返回release Api URL结果。
     */
    public String releaseApiUrl() {
        String override = configValue("solonclaw.update.releaseApiUrl");
        if (StrUtil.isNotBlank(override)) {
            return override.trim();
        }
        return "https://api.github.com/repos/" + releaseRepo() + "/releases/latest";
    }

    /**
     * 执行tagsApiURL相关逻辑。
     *
     * @return 返回tags Api URL结果。
     */
    public String tagsApiUrl() {
        String override = configValue("solonclaw.update.tagsApiUrl");
        if (StrUtil.isNotBlank(override)) {
            return override.trim();
        }
        return "https://api.github.com/repos/" + releaseRepo() + "/tags?per_page=5";
    }

    /**
     * 更新Proxy URL。
     *
     * @return 返回Proxy URL结果。
     */
    public String updateProxyUrl() {
        String override = configValue("solonclaw.update.httpProxy");
        return StrUtil.nullToEmpty(override).trim();
    }

    /**
     * 执行deployment模式相关逻辑。
     *
     * @return 返回deployment模式结果。
     */
    public String deploymentMode() {
        if (isDocker()) {
            return "docker";
        }
        File codeSource = currentCodeSourceFile();
        if (codeSource != null && codeSource.isFile() && codeSource.getName().endsWith(".jar")) {
            return "jar";
        }
        return "dev";
    }

    /**
     * 判断是否Docker。
     *
     * @return 如果Docker满足条件则返回 true，否则返回 false。
     */
    public boolean isDocker() {
        if (new File("/.dockerenv").exists()) {
            return true;
        }
        String cgroup =
                firstNonBlank(
                        readFileQuietly("/proc/1/cgroup"), readFileQuietly("/proc/self/cgroup"));
        return cgroup != null
                && (cgroup.contains("docker")
                        || cgroup.contains("kubepods")
                        || cgroup.contains("containerd"));
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    public boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 执行当前Code来源文件相关逻辑。
     *
     * @return 返回当前Code来源文件结果。
     */
    public File currentCodeSourceFile() {
        try {
            URL location = SolonClawApp.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            String path = URLDecoder.decode(location.getPath(), "UTF-8");
            return new File(path).getAbsoluteFile();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行当前Jar文件相关逻辑。
     *
     * @return 返回当前Jar文件结果。
     */
    public File currentJarFile() {
        File file = currentCodeSourceFile();
        if (file != null && file.isFile() && file.getName().endsWith(".jar")) {
            return file;
        }
        return null;
    }

    /**
     * 执行javaExecutable相关逻辑。
     *
     * @return 返回java Executable结果。
     */
    public String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        String executable = isWindows() ? "java.exe" : "java";
        return new File(new File(javaHome, "bin"), executable).getAbsolutePath();
    }

    /**
     * 读取应用启动参数快照。
     *
     * @return 返回启动参数参数结果。
     */
    public String[] startupArgs() {
        return SolonClawApp.startupArgs();
    }

    /**
     * 执行运行时主渠道相关逻辑。
     *
     * @return 返回运行时主渠道结果。
     */
    public File runtimeHome() {
        return new File(appConfig.getRuntime().getHome()).getAbsoluteFile();
    }

    /**
     * 执行compareVersions相关逻辑。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 返回compare Versions结果。
     */
    public static int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left).split("\\.");
        String[] rightParts = normalizeVersion(right).split("\\.");
        int size = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < size; i++) {
            int l = i < leftParts.length ? parseInt(leftParts[i]) : 0;
            int r = i < rightParts.length ? parseInt(rightParts[i]) : 0;
            if (l != r) {
                return l < r ? -1 : 1;
            }
        }
        return 0;
    }

    /**
     * 规范化版本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回版本结果。
     */
    public static String normalizeVersion(String value) {
        String normalized = stripLeadingV(value);
        int dash = normalized.indexOf('-');
        if (dash >= 0) {
            normalized = normalized.substring(0, dash);
        }
        return normalized.trim();
    }

    /**
     * 剥离LeadingV。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Leading V结果。
     */
    public static String stripLeadingV(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    /**
     * 加载Pom Properties版本。
     *
     * @return 返回Pom Properties版本结果。
     */
    private String loadPomPropertiesVersion() {
        InputStream inputStream = null;
        try {
            inputStream =
                    SolonClawApp.class
                            .getClassLoader()
                            .getResourceAsStream(
                                    "META-INF/maven/com.jimuqu.solon.claw/solon-claw/pom.properties");
            if (inputStream == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties.getProperty("version");
        } catch (Exception e) {
            return null;
        } finally {
            IoUtil.close(inputStream);
        }
    }

    /**
     * 读取Pom Xml版本。
     *
     * @return 返回读取到的Pom Xml版本。
     */
    private String readPomXmlVersion() {
        File pomFile = new File(System.getProperty("user.dir"), "pom.xml");
        if (!pomFile.isFile()) {
            return null;
        }
        String content = cn.hutool.core.io.FileUtil.readUtf8String(pomFile);
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 读取文件Quietly。
     *
     * @param path 文件或目录路径。
     * @return 返回读取到的文件Quietly。
     */
    private String readFileQuietly(String path) {
        try {
            return cn.hutool.core.io.FileUtil.readUtf8String(new File(path));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析Int。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回解析后的Int。
     */
    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 执行firstNon空白值相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回first Non Blank结果。
     */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 执行配置值相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回配置Value结果。
     */
    private String configValue(String key) {
        if (appConfig != null
                && appConfig.getRuntime() != null
                && StrUtil.isNotBlank(appConfig.getRuntime().getHome())) {
            return RuntimeConfigResolver.initialize(appConfig.getRuntime().getHome()).get(key);
        }
        return RuntimeConfigResolver.getValue(key);
    }
}
