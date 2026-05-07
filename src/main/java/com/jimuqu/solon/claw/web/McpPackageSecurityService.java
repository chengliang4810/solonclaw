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

/** Hermes-style OSV malware checks for MCP stdio package launchers. */
public class McpPackageSecurityService {
    private static final Logger log = LoggerFactory.getLogger(McpPackageSecurityService.class);
    private static final String DEFAULT_OSV_ENDPOINT = "https://api.osv.dev/v1/query";

    private final SkillHubHttpClient httpClient;
    private final String endpoint;
    private final SecurityPolicyService securityPolicyService;

    public McpPackageSecurityService(SkillHubHttpClient httpClient) {
        this(httpClient, System.getenv("OSV_ENDPOINT"), null);
    }

    public McpPackageSecurityService(SkillHubHttpClient httpClient, String endpoint) {
        this(httpClient, endpoint, null);
    }

    public McpPackageSecurityService(
            SkillHubHttpClient httpClient, SecurityPolicyService securityPolicyService) {
        this(httpClient, System.getenv("OSV_ENDPOINT"), securityPolicyService);
    }

    public McpPackageSecurityService(
            SkillHubHttpClient httpClient,
            String endpoint,
            SecurityPolicyService securityPolicyService) {
        this.httpClient = httpClient;
        this.endpoint = StrUtil.blankToDefault(endpoint, DEFAULT_OSV_ENDPOINT);
        this.securityPolicyService = securityPolicyService;
    }

    public SecurityVerdict check(String command, Object argsValue) {
        String ecosystem = inferEcosystem(command);
        if (StrUtil.isBlank(ecosystem)) {
            return SecurityVerdict.allow();
        }
        PackageRef packageRef = parsePackage(argsValue, ecosystem);
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

    private String inferEcosystem(String command) {
        String base = StrUtil.nullToEmpty(command).trim();
        if (StrUtil.isBlank(base)) {
            return null;
        }
        base = FileUtil.getName(base).toLowerCase(Locale.ROOT);
        if ("npx".equals(base) || "npx.cmd".equals(base)) {
            return "npm";
        }
        if ("uvx".equals(base) || "uvx.cmd".equals(base) || "pipx".equals(base)) {
            return "PyPI";
        }
        return null;
    }

    private PackageRef parsePackage(Object argsValue, String ecosystem) {
        List<String> args = normalizeArgs(argsValue);
        String token = null;
        for (String arg : args) {
            if (StrUtil.isBlank(arg) || arg.startsWith("-")) {
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

    private PackageRef parseNpm(String token) {
        if (token.startsWith("@")) {
            int at = token.indexOf('@', 1);
            if (at > 0) {
                return new PackageRef(token.substring(0, at), versionOrNull(token.substring(at + 1)));
            }
            return new PackageRef(token, null);
        }
        int at = token.lastIndexOf('@');
        if (at > 0) {
            return new PackageRef(token.substring(0, at), versionOrNull(token.substring(at + 1)));
        }
        return new PackageRef(token, null);
    }

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

    private String versionOrNull(String version) {
        String value = StrUtil.nullToEmpty(version).trim();
        if (StrUtil.isBlank(value) || "latest".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

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

    private static class PackageRef {
        private final String name;
        private final String version;

        private PackageRef(String name, String version) {
            this.name = StrUtil.nullToEmpty(name).trim();
            this.version = version;
        }
    }

    public static class SecurityVerdict {
        private final boolean allowed;
        private final String message;

        private SecurityVerdict(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }

        public static SecurityVerdict allow() {
            return new SecurityVerdict(true, "");
        }

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
                String id = StrUtil.nullToEmpty(String.valueOf(vuln.get("id")));
                ids.append(id);
                summaries.append(
                        StrUtil.maxLength(
                                StrUtil.blankToDefault(
                                        String.valueOf(vuln.get("summary")), id),
                                100));
            }
            return new SecurityVerdict(
                    false,
                    "BLOCKED: Package '"
                            + packageName
                            + "' ("
                            + ecosystem
                            + ") has known malware advisories: "
                            + ids
                            + ". Details: "
                            + summaries);
        }

        public static SecurityVerdict blockEndpoint(String endpoint, String reason) {
            return new SecurityVerdict(
                    false,
                    "BLOCKED: OSV endpoint is unsafe: "
                            + SecretRedactor.maskUrl(endpoint)
                            + " ("
                            + reason
                            + ")");
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getMessage() {
            return message;
        }
    }
}
