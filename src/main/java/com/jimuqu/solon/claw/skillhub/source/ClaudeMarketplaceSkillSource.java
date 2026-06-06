package com.jimuqu.solon.claw.skillhub.source;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.support.GitHubAuth;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import java.util.ArrayList;
import java.util.List;
import org.noear.snack4.ONode;

/** 承载外部技能市场技能市场技能来源相关状态和辅助逻辑。 */
public class ClaudeMarketplaceSkillSource implements SkillSource {
    /** KNOWNMARKETPLACES的统一常量值。 */
    private static final String[] KNOWN_MARKETPLACES =
            new String[] {"anthropics/skills", "aiskillstore/marketplace"};

    /** 记录外部技能市场技能市场技能来源中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /** 记录外部技能市场技能市场技能来源中的认证。 */
    private final GitHubAuth auth;

    /** 记录外部技能市场技能市场技能来源中的GitHub技能来源。 */
    private final GitHubSkillSource githubSkillSource;

    /** 记录外部技能市场技能市场技能来源中的状态Store。 */
    private final SkillHubStateStore stateStore;

    /**
     * 创建外部技能市场技能市场技能来源实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     * @param auth 鉴权参数。
     * @param githubSkillSource GitHub技能来源参数。
     * @param stateStore 状态Store参数。
     */
    public ClaudeMarketplaceSkillSource(
            SkillHubHttpClient httpClient,
            GitHubAuth auth,
            GitHubSkillSource githubSkillSource,
            SkillHubStateStore stateStore) {
        this.httpClient = httpClient;
        this.auth = auth;
        this.githubSkillSource = githubSkillSource;
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
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        String normalized = StrUtil.nullToEmpty(query).toLowerCase();
        for (String repo : KNOWN_MARKETPLACES) {
            ONode plugins = fetchMarketplaceIndex(repo);
            for (int i = 0; i < plugins.size(); i++) {
                ONode item = plugins.get(i);
                String name = item.get("name").getString();
                String description = item.get("description").getString();
                if ((name + " " + description).toLowerCase().contains(normalized)) {
                    String identifier = resolveIdentifier(repo, item.get("source").getString());
                    SkillMeta meta = new SkillMeta();
                    meta.setName(name);
                    meta.setDescription(description);
                    meta.setSource(sourceId());
                    meta.setIdentifier(identifier);
                    meta.setTrustLevel(trustLevelFor(identifier));
                    meta.setRepo(repo);
                    results.add(meta);
                    if (results.size() >= limit) {
                        return results;
                    }
                }
            }
        }
        return results;
    }

    /**
     * 执行fetch相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回fetch结果。
     */
    @Override
    public SkillBundle fetch(String identifier) throws Exception {
        SkillBundle bundle = githubSkillSource.fetch(normalizeIdentifier(identifier));
        if (bundle != null) {
            bundle.setSource(sourceId());
        }
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
        SkillMeta meta = githubSkillSource.inspect(normalizeIdentifier(identifier));
        if (meta != null) {
            meta.setSource(sourceId());
            meta.setTrustLevel(trustLevelFor(identifier));
        }
        return meta;
    }

    /**
     * 执行来源标识相关逻辑。
     *
     * @return 返回来源标识。
     */
    @Override
    public String sourceId() {
        return "claude-marketplace";
    }

    /**
     * 执行trust级别For相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回trust级别For结果。
     */
    @Override
    public String trustLevelFor(String identifier) {
        return githubSkillSource.trustLevelFor(normalizeIdentifier(identifier));
    }

    /**
     * 拉取技能市场索引。
     *
     * @param repo repo 参数。
     * @return 返回fetch技能市场Index结果。
     */
    private ONode fetchMarketplaceIndex(String repo) throws Exception {
        String cacheKey = "claude_marketplace_" + repo.replace("/", "_");
        String cached = stateStore.readCachedIndex(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return ONode.ofJson(cached).get("plugins");
        }
        java.util.Map<String, String> headers = auth.getHeaders();
        headers.put("Accept", "application/vnd.github.v3.raw");
        String text =
                httpClient.getText(
                        "https://api.github.com/repos/"
                                + repo
                                + "/contents/.claude-plugin/marketplace.json",
                        headers);
        ONode node = ONode.ofJson(text);
        stateStore.writeCachedIndex(cacheKey, node.toJson());
        return node.get("plugins");
    }

    /**
     * 解析Identifier。
     *
     * @param repo repo 参数。
     * @param sourcePath 文件或目录路径参数。
     * @return 返回解析后的Identifier。
     */
    private String resolveIdentifier(String repo, String sourcePath) {
        if (StrUtil.isBlank(sourcePath)) {
            return repo;
        }
        if (sourcePath.startsWith("./")) {
            return repo + "/" + sourcePath.substring(2);
        }
        if (sourcePath.contains("/")) {
            return sourcePath;
        }
        return repo + "/" + sourcePath;
    }

    /**
     * 规范化Identifier。
     *
     * @param identifier identifier标识或键值。
     * @return 返回Identifier结果。
     */
    private String normalizeIdentifier(String identifier) {
        String normalized = StrUtil.nullToEmpty(identifier);
        if (normalized.startsWith(sourceId() + "/")) {
            return normalized.substring(sourceId().length() + 1);
        }
        return normalized;
    }
}
