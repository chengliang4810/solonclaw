package com.jimuqu.solon.claw.skillhub.source;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.model.TapRecord;
import com.jimuqu.solon.claw.skillhub.support.GitHubAuth;
import com.jimuqu.solon.claw.skillhub.support.SkillBundlePathSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillFrontmatterSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 承载Git中心技能来源相关状态和辅助逻辑。 */
public class GitHubSkillSource implements SkillSource {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(GitHubSkillSource.class);

    /** API基础的统一常量值。 */
    private static final String API_BASE = "https://api.github.com/repos/";

    /** TRUSTEDREPOS的统一常量值。 */
    private static final LinkedHashSet<String> TRUSTED_REPOS =
            new LinkedHashSet<String>(
                    java.util.Arrays.asList(
                            "openai/skills",
                            "anthropics/skills",
                            "huggingface/skills",
                            "NVIDIA/skills"));

    /** 记录Git中心技能来源中的认证。 */
    private final GitHubAuth auth;

    /** 记录Git中心技能来源中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /** 记录Git中心技能来源中的状态Store。 */
    private final SkillHubStateStore stateStore;

    /**
     * 创建Git中心技能来源实例，并注入运行所需依赖。
     *
     * @param auth 鉴权参数。
     * @param httpClient HTTPClient参数。
     * @param stateStore 状态Store参数。
     */
    public GitHubSkillSource(
            GitHubAuth auth, SkillHubHttpClient httpClient, SkillHubStateStore stateStore) {
        this.auth = auth;
        this.httpClient = httpClient;
        this.stateStore = stateStore;
    }

    /**
     * 执行搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    @Override
    public List<SkillMeta> search(String query, int limit) throws Exception {
        String normalizedQuery = StrUtil.nullToEmpty(query).trim().toLowerCase(Locale.ROOT);
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        for (TapRecord tap : allTaps()) {
            List<SkillMeta> tapResults;
            try {
                tapResults =
                        listSkillsInRepo(tap.getRepo(), StrUtil.blankToDefault(tap.getPath(), ""));
            } catch (Exception e) {
                log.warn(
                        "GitHub Skills Hub tap search failed, skipping tap: repo={}, path={}, query={}, limit={}, error={}",
                        tap.getRepo(),
                        StrUtil.blankToDefault(tap.getPath(), ""),
                        StrUtil.nullToEmpty(query),
                        limit,
                        ErrorTextSupport.safeError(e));
                log.debug(
                        "GitHub Skills Hub tap search failure detail: repo={}, path={}, error={}",
                        tap.getRepo(),
                        StrUtil.blankToDefault(tap.getPath(), ""),
                        ErrorTextSupport.safeError(e));
                continue;
            }
            for (SkillMeta meta : tapResults) {
                String searchable =
                        (meta.getName()
                                        + " "
                                        + meta.getDescription()
                                        + " "
                                        + String.join(" ", meta.getTags()))
                                .toLowerCase(Locale.ROOT);
                if (normalizedQuery.length() == 0 || searchable.contains(normalizedQuery)) {
                    results.add(meta);
                    if (results.size() >= limit) {
                        return dedupe(results);
                    }
                }
            }
        }
        return dedupe(results);
    }

    /**
     * 执行fetch相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回fetch结果。
     */
    @Override
    public SkillBundle fetch(String identifier) throws Exception {
        String[] parts = splitIdentifier(identifier);
        if (parts == null) {
            return null;
        }
        Map<String, String> files = downloadDirectory(parts[0], parts[1]);
        if (!files.containsKey("SKILL.md")) {
            return null;
        }
        SkillBundle bundle = new SkillBundle();
        bundle.setName(SkillBundlePathSupport.normalizeSkillName(lastPathToken(parts[1])));
        bundle.setFiles(files);
        bundle.setSource(sourceId());
        bundle.setIdentifier(identifier);
        bundle.setTrustLevel(trustLevelFor(identifier));
        return bundle;
    }

    /**
     * 执行inspect相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回inspect结果。
     */
    @Override
    public SkillMeta inspect(String identifier) throws Exception {
        String[] parts = splitIdentifier(identifier);
        if (parts == null) {
            return null;
        }
        String content = fetchFileContent(parts[0], parts[1] + "/SKILL.md");
        if (StrUtil.isBlank(content)) {
            return null;
        }
        Map<String, Object> frontmatter = SkillFrontmatterSupport.parseFrontmatter(content);
        SkillMeta meta = new SkillMeta();
        meta.setName(SkillFrontmatterSupport.resolveName(frontmatter, lastPathToken(parts[1])));
        meta.setDescription(SkillFrontmatterSupport.resolveDescription(frontmatter, ""));
        meta.setSource(sourceId());
        meta.setIdentifier(identifier);
        meta.setTrustLevel(trustLevelFor(identifier));
        meta.setRepo(parts[0]);
        meta.setPath(parts[1]);
        meta.setTags(new ArrayList<String>(SkillFrontmatterSupport.resolveTags(frontmatter)));
        return meta;
    }

    /**
     * 执行来源标识相关逻辑。
     *
     * @return 返回来源标识。
     */
    @Override
    public String sourceId() {
        return "github";
    }

    /**
     * 执行trust级别For相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回trust级别For结果。
     */
    @Override
    public String trustLevelFor(String identifier) {
        String[] parts = splitIdentifier(identifier);
        if (parts == null) {
            return "community";
        }
        return TRUSTED_REPOS.contains(parts[0]) ? "trusted" : "community";
    }

    /**
     * 列出技能In Repo。
     *
     * @param repo repo 参数。
     * @param path 文件或目录路径。
     * @return 返回技能In Repo列表。
     */
    public List<SkillMeta> listSkillsInRepo(String repo, String path) throws Exception {
        String cacheKey = "github_search_" + repo.replace("/", "_") + "_" + path.replace("/", "_");
        String cached = stateStore.readCachedIndex(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return SkillMetaDeserialize.deserializeList(cached);
        }

        String normalizedPath = trimSlashes(path);
        String url = API_BASE + repo + "/contents/" + normalizedPath;
        String response = httpClient.getText(url, auth.getHeaders());
        ONode node = ONode.ofJson(response);
        if (!node.isArray()) {
            return Collections.emptyList();
        }

        List<SkillMeta> results = new ArrayList<SkillMeta>();
        for (int i = 0; i < node.size(); i++) {
            ONode item = node.get(i);
            if (!"dir".equalsIgnoreCase(item.get("type").getString())) {
                continue;
            }
            String dirName = item.get("name").getString();
            if (StrUtil.startWith(dirName, ".") || StrUtil.startWith(dirName, "_")) {
                continue;
            }
            String identifier = repo + "/" + trimSlashes(normalizedPath + "/" + dirName);
            SkillMeta meta = inspect(identifier);
            if (meta != null) {
                results.add(meta);
            }
        }
        stateStore.writeCachedIndex(cacheKey, ONode.serialize(results));
        return results;
    }

    /**
     * 拉取文件Content。
     *
     * @param repo repo 参数。
     * @param repoPath 文件或目录路径参数。
     * @return 返回fetch文件Content结果。
     */
    public String fetchFileContent(String repo, String repoPath) throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>(auth.getHeaders());
        headers.put("Accept", "application/vnd.github.v3.raw");
        return httpClient.getText(API_BASE + repo + "/contents/" + trimSlashes(repoPath), headers);
    }

    /**
     * 执行download目录相关逻辑。
     *
     * @param repo repo 参数。
     * @param path 文件或目录路径。
     * @return 返回download Directory结果。
     */
    private Map<String, String> downloadDirectory(String repo, String path) throws Exception {
        Map<String, String> files = new LinkedHashMap<String, String>();
        downloadDirectoryRecursive(repo, trimSlashes(path), "", files);
        return files;
    }

    /**
     * 执行download目录Recursive相关逻辑。
     *
     * @param repo repo 参数。
     * @param repoPath 文件或目录路径参数。
     * @param relativePrefix relativePrefix 参数。
     * @param sink sink 参数。
     */
    private void downloadDirectoryRecursive(
            String repo, String repoPath, String relativePrefix, Map<String, String> sink)
            throws Exception {
        String response =
                httpClient.getText(API_BASE + repo + "/contents/" + repoPath, auth.getHeaders());
        ONode node = ONode.ofJson(response);
        if (!node.isArray()) {
            return;
        }

        for (int i = 0; i < node.size(); i++) {
            ONode item = node.get(i);
            String type = item.get("type").getString();
            String name = item.get("name").getString();
            if ("file".equalsIgnoreCase(type)) {
                String bundlePath =
                        SkillBundlePathSupport.normalizeBundlePath(relativePrefix + name);
                String downloadUrl = item.get("download_url").getString();
                String fileContent =
                        StrUtil.isNotBlank(downloadUrl)
                                ? httpClient.getText(downloadUrl, null)
                                : fetchFileContent(repo, repoPath + "/" + name);
                sink.put(bundlePath, fileContent);
            } else if ("dir".equalsIgnoreCase(type)) {
                downloadDirectoryRecursive(
                        repo, repoPath + "/" + name, relativePrefix + name + "/", sink);
            }
        }
    }

    /**
     * 执行全部Taps相关逻辑。
     *
     * @return 返回全部Taps结果。
     */
    private List<TapRecord> allTaps() {
        List<TapRecord> taps = new ArrayList<TapRecord>();
        taps.add(defaultTap("openai/skills", "skills/"));
        taps.add(defaultTap("anthropics/skills", "skills/"));
        taps.add(defaultTap("VoltAgent/awesome-agent-skills", "skills/"));
        taps.add(defaultTap("garrytan/gstack", ""));
        taps.addAll(stateStore.listTaps());
        return taps;
    }

    /**
     * 执行默认来源库相关逻辑。
     *
     * @param repo repo 参数。
     * @param path 文件或目录路径。
     * @return 返回默认Tap结果。
     */
    private TapRecord defaultTap(String repo, String path) {
        TapRecord tap = new TapRecord();
        tap.setRepo(repo);
        tap.setPath(path);
        return tap;
    }

    /**
     * 执行dedupe相关逻辑。
     *
     * @param input 输入参数。
     * @return 返回dedupe结果。
     */
    private List<SkillMeta> dedupe(List<SkillMeta> input) {
        Map<String, SkillMeta> unique = new LinkedHashMap<String, SkillMeta>();
        for (SkillMeta meta : input) {
            if (!unique.containsKey(meta.getName())) {
                unique.put(meta.getName(), meta);
            }
        }
        return new ArrayList<SkillMeta>(unique.values());
    }

    /**
     * 拆分Identifier。
     *
     * @param identifier identifier标识或键值。
     * @return 返回Identifier结果。
     */
    private String[] splitIdentifier(String identifier) {
        String normalized = trimSlashes(identifier);
        String[] parts = normalized.split("/", 3);
        if (parts.length < 3) {
            return null;
        }
        return new String[] {parts[0] + "/" + parts[1], parts[2]};
    }

    /**
     * 执行trimSlashes相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回trim Slashes结果。
     */
    private String trimSlashes(String value) {
        String normalized = StrUtil.nullToEmpty(value).replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 执行last路径token相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回last路径token结果。
     */
    private String lastPathToken(String path) {
        String normalized = trimSlashes(path);
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }
}
