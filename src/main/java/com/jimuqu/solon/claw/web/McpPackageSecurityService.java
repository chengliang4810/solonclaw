package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供MCP Package安全相关业务能力，封装调用方不需要感知的运行细节。 */
public class McpPackageSecurityService {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(McpPackageSecurityService.class);

    /** 默认OSVENDPO整型的统一常量值。 */
    private static final String DEFAULT_OSV_ENDPOINT = "https://api.osv.dev/v1/query";

    /** 记录MCP包安全中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /** 记录MCP包安全中的endpoint。 */
    private final String endpoint;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /**
     * 创建MCP Package安全服务实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     */
    public McpPackageSecurityService(SkillHubHttpClient httpClient) {
        this(httpClient, endpointFromEnvironment(), null);
    }

    /**
     * 创建MCP Package安全服务实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     * @param endpoint endpoint 参数。
     */
    public McpPackageSecurityService(SkillHubHttpClient httpClient, String endpoint) {
        this(httpClient, endpoint, null);
    }

    /**
     * 创建MCP Package安全服务实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public McpPackageSecurityService(
            SkillHubHttpClient httpClient, SecurityPolicyService securityPolicyService) {
        this(httpClient, endpointFromEnvironment(), securityPolicyService);
    }

    /**
     * 创建MCP Package安全服务实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     * @param endpoint endpoint 参数。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public McpPackageSecurityService(
            SkillHubHttpClient httpClient,
            String endpoint,
            SecurityPolicyService securityPolicyService) {
        this.httpClient = httpClient;
        this.endpoint = StrUtil.blankToDefault(endpoint, DEFAULT_OSV_ENDPOINT);
        this.securityPolicyService = securityPolicyService;
    }

    /**
     * 执行check相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param argsValue args值参数。
     * @return 返回check结果。
     */
    public SecurityVerdict check(String command, Object argsValue) {
        String launcher = launcherName(command);
        String ecosystem = inferEcosystem(launcher);
        if (StrUtil.isBlank(ecosystem)) {
            return SecurityVerdict.allow();
        }
        PackageRef packageRef = parsePackage(argsValue, ecosystem, launcher);
        if (packageRef == null || StrUtil.isBlank(packageRef.name)) {
            return SecurityVerdict.allow();
        }
        SecurityVerdict endpointVerdict = checkEndpoint();
        if (!endpointVerdict.isAllowed()) {
            return endpointVerdict;
        }
        try {
            List<Map<String, Object>> malware = queryOsv(packageRef, ecosystem);
            if (malware.isEmpty()) {
                return SecurityVerdict.allow();
            }
            return SecurityVerdict.block(packageRef.name, ecosystem, malware);
        } catch (Exception e) {
            log.debug(
                    "OSV malware check failed for {}/{} (allowing): {}",
                    ecosystem,
                    packageRef.name,
                    e.getMessage());
            return SecurityVerdict.allow();
        }
    }

    /**
     * 构建当前策略配置摘要。
     *
     * @return 返回策略Summary结果。
     */
    public Map<String, Object> policySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("enabledForTransport", "stdio");
        summary.put("checkedLaunchers", java.util.Arrays.asList("npx", "uvx", "pipx"));
        summary.put("supportedEcosystems", java.util.Arrays.asList("npm", "PyPI"));
        summary.put("endpointUrlSafetyChecked", Boolean.valueOf(securityPolicyService != null));
        summary.put("defaultEndpoint", DEFAULT_OSV_ENDPOINT);
        summary.put("endpointOverrideEnvironment", "SOLONCLAW_OSV_ENDPOINT");
        summary.put("projectEndpointOverrideEnvironment", "SOLONCLAW_OSV_ENDPOINT");
        summary.put("malwareAdvisoryPrefix", "MAL-");
        summary.put("nonMalwareVulnerabilitiesIgnored", Boolean.TRUE);
        summary.put("malwareBlocksSaveAndCheck", Boolean.TRUE);
        summary.put("requestFailureFailsOpen", Boolean.TRUE);
        summary.put("unsafeEndpointBlocksBeforeNetwork", Boolean.TRUE);
        summary.put(
                "structuredReasons",
                java.util.Arrays.asList("allow", "malware_advisory", "unsafe_endpoint", "blocked"));
        summary.put("persistedListReasonExposed", Boolean.TRUE);
        summary.put("packageVersionParsed", Boolean.TRUE);
        summary.put("scopedNpmPackageParsed", Boolean.TRUE);
        summary.put("npxPackageOptionParsed", Boolean.TRUE);
        summary.put("pipxRunSubcommandSkipped", Boolean.TRUE);
        summary.put("pypiSourceOptionParsed", Boolean.TRUE);
        summary.put("pypiExtrasIgnored", Boolean.TRUE);
        summary.put("jsonArgsSupported", Boolean.TRUE);
        summary.put("advisoryMessageLimit", Integer.valueOf(3));
        summary.put("messageRedacted", Boolean.TRUE);
        summary.put("endpointRedacted", Boolean.TRUE);
        summary.put(
                "description",
                "MCP stdio package launchers are checked against OSV malware advisories before save/check; unsafe OSV endpoints are blocked before network access and advisory messages are redacted.");
        return summary;
    }

    /**
     * 执行endpointFromEnvironment相关逻辑。
     *
     * @return 返回endpoint From Environment结果。
     */
    private static String endpointFromEnvironment() {
        return System.getenv("SOLONCLAW_OSV_ENDPOINT");
    }

    /**
     * 执行inferEcosystem相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回infer Ecosystem结果。
     */
    private String inferEcosystem(String command) {
        String launcher = StrUtil.nullToEmpty(command).trim().toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(launcher)) {
            return null;
        }
        if ("npx".equals(launcher) || "npx.cmd".equals(launcher)) {
            return "npm";
        }
        if ("uvx".equals(launcher)
                || "uvx.cmd".equals(launcher)
                || "pipx".equals(launcher)
                || "pipx.cmd".equals(launcher)) {
            return "PyPI";
        }
        return null;
    }

    /**
     * 执行launcher名称相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回launcher名称结果。
     */
    private String launcherName(String command) {
        String base = StrUtil.nullToEmpty(command).trim();
        if (StrUtil.isBlank(base)) {
            return "";
        }
        return FileUtil.getName(base).toLowerCase(Locale.ROOT);
    }

    /**
     * 解析Package。
     *
     * @param argsValue args值参数。
     * @param ecosystem ecosystem 参数。
     * @param launcher launcher 参数。
     * @return 返回解析后的Package。
     */
    private PackageRef parsePackage(Object argsValue, String ecosystem, String launcher) {
        List<String> args = normalizeArgs(argsValue);
        String token = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (StrUtil.isBlank(arg) || arg.startsWith("-")) {
                String optionValue = packageOptionValue(args, i, arg, ecosystem);
                if (StrUtil.isNotBlank(optionValue)) {
                    token = optionValue;
                    break;
                }
                continue;
            }
            if (isPipxSubcommand(launcher, arg)) {
                continue;
            }
            token = arg.trim();
            break;
        }
        if (StrUtil.isBlank(token)) {
            return null;
        }
        if ("npm".equals(ecosystem)) {
            return parseNpm(token);
        }
        if ("PyPI".equals(ecosystem)) {
            return parsePypi(token);
        }
        return new PackageRef(token, null);
    }

    /**
     * 执行包选项值相关逻辑。
     *
     * @param args 工具或命令参数。
     * @param index 索引参数。
     * @param arg arg 参数。
     * @param ecosystem ecosystem 参数。
     * @return 返回package Option Value结果。
     */
    private String packageOptionValue(List<String> args, int index, String arg, String ecosystem) {
        String value = StrUtil.nullToEmpty(arg).trim();
        if ("npm".equals(ecosystem)) {
            if ("--package".equals(value) || "-p".equals(value)) {
                return nextArg(args, index);
            }
            if (value.startsWith("--package=")) {
                return value.substring("--package=".length()).trim();
            }
            if (value.startsWith("-p=")) {
                return value.substring("-p=".length()).trim();
            }
        }
        if ("PyPI".equals(ecosystem)) {
            if ("--from".equals(value) || "--spec".equals(value)) {
                return nextArg(args, index);
            }
            if (value.startsWith("--from=")) {
                return value.substring("--from=".length()).trim();
            }
            if (value.startsWith("--spec=")) {
                return value.substring("--spec=".length()).trim();
            }
        }
        return null;
    }

    /**
     * 执行nextArg相关逻辑。
     *
     * @param args 工具或命令参数。
     * @param index 索引参数。
     * @return 返回next Arg结果。
     */
    private String nextArg(List<String> args, int index) {
        for (int i = index + 1; i < args.size(); i++) {
            String next = StrUtil.nullToEmpty(args.get(i)).trim();
            if (StrUtil.isNotBlank(next)) {
                return next;
            }
        }
        return null;
    }

    /**
     * 判断是否Pipx Subcommand。
     *
     * @param launcher launcher 参数。
     * @param arg arg 参数。
     * @return 如果Pipx Subcommand满足条件则返回 true，否则返回 false。
     */
    private boolean isPipxSubcommand(String launcher, String arg) {
        String value = StrUtil.nullToEmpty(arg).trim().toLowerCase(Locale.ROOT);
        return ("pipx".equals(launcher) || "pipx.cmd".equals(launcher))
                && ("run".equals(value) || "runpip".equals(value));
    }

    /**
     * 规范化参数。
     *
     * @param argsValue args值参数。
     * @return 返回参数结果。
     */
    private List<String> normalizeArgs(Object argsValue) {
        List<String> args = new ArrayList<String>();
        if (argsValue instanceof List) {
            for (Object item : (List<?>) argsValue) {
                if (item != null) {
                    args.add(String.valueOf(item));
                }
            }
            return args;
        }
        if (argsValue instanceof String) {
            String text = StrUtil.nullToEmpty((String) argsValue).trim();
            if (text.startsWith("[") && text.endsWith("]")) {
                try {
                    Object parsed = ONode.deserialize(text, Object.class);
                    return normalizeArgs(parsed);
                } catch (Exception ignored) {
                }
            }
            for (String item : text.split("\\s+")) {
                if (StrUtil.isNotBlank(item)) {
                    args.add(item);
                }
            }
        }
        return args;
    }

    /**
     * 解析Npm。
     *
     * @param token token 参数。
     * @return 返回解析后的Npm。
     */
    private PackageRef parseNpm(String token) {
        if (token.startsWith("@")) {
            int at = token.indexOf('@', 1);
            if (at > 0) {
                return new PackageRef(
                        token.substring(0, at), versionOrNull(token.substring(at + 1)));
            }
            return new PackageRef(token, null);
        }
        int at = token.lastIndexOf('@');
        if (at > 0) {
            return new PackageRef(token.substring(0, at), versionOrNull(token.substring(at + 1)));
        }
        return new PackageRef(token, null);
    }

    /**
     * 解析Pypi。
     *
     * @param token token 参数。
     * @return 返回解析后的Pypi。
     */
    private PackageRef parsePypi(String token) {
        String name = token;
        String version = null;
        int extras = name.indexOf('[');
        int equals = name.indexOf("==");
        if (equals >= 0) {
            version = versionOrNull(name.substring(equals + 2));
            name = name.substring(0, equals);
        }
        if (extras >= 0) {
            name = name.substring(0, extras);
        }
        return new PackageRef(name, version);
    }

    /**
     * 检查Endpoint。
     *
     * @return 返回Endpoint结果。
     */
    private SecurityVerdict checkEndpoint() {
        if (securityPolicyService == null) {
            return SecurityVerdict.allow();
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(endpoint);
        if (verdict.isAllowed()) {
            return SecurityVerdict.allow();
        }
        return SecurityVerdict.blockEndpoint(verdict.getUrl(), verdict.getMessage());
    }

    /**
     * 执行版本Or空值相关逻辑。
     *
     * @param version 版本参数。
     * @return 返回版本Or Null结果。
     */
    private String versionOrNull(String version) {
        String value = StrUtil.nullToEmpty(version).trim();
        if (StrUtil.isBlank(value) || "latest".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    /**
     * 查询Osv。
     *
     * @param packageRef 包Ref参数。
     * @param ecosystem ecosystem 参数。
     * @return 返回Osv结果。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryOsv(PackageRef packageRef, String ecosystem)
            throws Exception {
        Map<String, Object> pkg = new LinkedHashMap<String, Object>();
        pkg.put("name", packageRef.name);
        pkg.put("ecosystem", ecosystem);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("package", pkg);
        if (StrUtil.isNotBlank(packageRef.version)) {
            payload.put("version", packageRef.version);
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "jimuqu-agent-osv-check/1.0");
        String body = httpClient.postJson(endpoint, headers, ONode.serialize(payload));
        Object parsed = ONode.deserialize(StrUtil.nullToEmpty(body), Object.class);
        if (!(parsed instanceof Map)) {
            return new ArrayList<Map<String, Object>>();
        }
        Object vulns = ((Map<?, ?>) parsed).get("vulns");
        List<Map<String, Object>> malware = new ArrayList<Map<String, Object>>();
        if (vulns instanceof List) {
            for (Object item : (List<?>) vulns) {
                if (item instanceof Map) {
                    Map<String, Object> vuln = (Map<String, Object>) item;
                    String id = StrUtil.nullToEmpty(String.valueOf(vuln.get("id")));
                    if (id.startsWith("MAL-")) {
                        malware.add(vuln);
                    }
                }
            }
        }
        return malware;
    }

    /** 承载包Ref相关状态和辅助逻辑。 */
    private static class PackageRef {
        /** 记录包Ref中的名称。 */
        private final String name;

        /** 记录包Ref中的版本。 */
        private final String version;

        /**
         * 创建Package Ref实例，并注入运行所需依赖。
         *
         * @param name 名称参数。
         * @param version 版本参数。
         */
        private PackageRef(String name, String version) {
            this.name = StrUtil.nullToEmpty(name).trim();
            this.version = version;
        }
    }

    /** 承载安全判定相关状态和辅助逻辑。 */
    public static class SecurityVerdict {
        /** 是否启用allowed。 */
        private final boolean allowed;

        /** 记录安全判定中的消息。 */
        private final String message;

        /** 记录安全判定中的原因。 */
        private final String reason;

        /**
         * 创建安全Verdict实例，并注入运行所需依赖。
         *
         * @param allowed allowed开关值。
         * @param message 平台消息或错误消息。
         * @param reason 原因参数。
         */
        private SecurityVerdict(boolean allowed, String message, String reason) {
            this.allowed = allowed;
            this.message = message;
            this.reason = StrUtil.blankToDefault(reason, allowed ? "allow" : "blocked");
        }

        /**
         * 执行allow相关逻辑。
         *
         * @return 返回allow结果。
         */
        public static SecurityVerdict allow() {
            return new SecurityVerdict(true, "", "allow");
        }

        /**
         * 执行阻断相关逻辑。
         *
         * @param packageName 包名称参数。
         * @param ecosystem ecosystem 参数。
         * @param malware malware 参数。
         * @return 返回block结果。
         */
        public static SecurityVerdict block(
                String packageName, String ecosystem, List<Map<String, Object>> malware) {
            StringBuilder ids = new StringBuilder();
            StringBuilder summaries = new StringBuilder();
            int limit = Math.min(3, malware.size());
            for (int i = 0; i < limit; i++) {
                Map<String, Object> vuln = malware.get(i);
                if (i > 0) {
                    ids.append(", ");
                    summaries.append("; ");
                }
                String id =
                        SecretRedactor.redact(
                                StrUtil.nullToEmpty(String.valueOf(vuln.get("id"))), 200);
                ids.append(id);
                summaries.append(
                        StrUtil.maxLength(
                                SecretRedactor.redact(
                                        StrUtil.blankToDefault(
                                                String.valueOf(vuln.get("summary")), id),
                                        200),
                                100));
            }
            return new SecurityVerdict(
                    false,
                    "BLOCKED: Package '"
                            + SecretRedactor.redact(packageName, 200)
                            + "' ("
                            + ecosystem
                            + ") has known malware advisories: "
                            + ids
                            + ". Details: "
                            + summaries,
                    "malware_advisory");
        }

        /**
         * 执行阻断Endpoint相关逻辑。
         *
         * @param endpoint endpoint 参数。
         * @param reason 原因参数。
         * @return 返回block Endpoint结果。
         */
        public static SecurityVerdict blockEndpoint(String endpoint, String reason) {
            return new SecurityVerdict(
                    false,
                    "BLOCKED: OSV endpoint is unsafe: "
                            + SecretRedactor.maskUrl(endpoint)
                            + " ("
                            + reason
                            + ")",
                    "unsafe_endpoint");
        }

        /**
         * 判断是否Allowed。
         *
         * @return 如果Allowed满足条件则返回 true，否则返回 false。
         */
        public boolean isAllowed() {
            return allowed;
        }

        /**
         * 读取消息。
         *
         * @return 返回读取到的消息。
         */
        public String getMessage() {
            return message;
        }

        /**
         * 读取Reason。
         *
         * @return 返回读取到的Reason。
         */
        public String getReason() {
            return reason;
        }
    }
}
