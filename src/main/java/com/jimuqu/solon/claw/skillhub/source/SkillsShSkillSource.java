package com.jimuqu.solon.claw.skillhub.source;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.noear.snack4.ONode;

/** 承载技能Sh技能来源相关状态和辅助逻辑。 */
public class SkillsShSkillSource implements SkillSource {
    /** 基础URL的统一常量值。 */
    private static final String BASE_URL = "https://skills.sh";

    /** 搜索URL的统一常量值。 */
    private static final String SEARCH_URL = BASE_URL + "/api/search";

    /** 记录技能Sh技能来源中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /** 记录技能Sh技能来源中的状态Store。 */
    private final SkillHubStateStore stateStore;

    /** 记录技能Sh技能来源中的GitHub技能来源。 */
    private final GitHubSkillSource githubSkillSource;

    /**
     * 创建技能Sh技能来源实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     * @param stateStore 状态Store参数。
     * @param githubSkillSource GitHub技能来源参数。
     */
    public SkillsShSkillSource(
            SkillHubHttpClient httpClient,
            SkillHubStateStore stateStore,
            GitHubSkillSource githubSkillSource) {
        this.httpClient = httpClient;
        this.stateStore = stateStore;
        this.githubSkillSource = githubSkillSource;
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
        if (StrUtil.isBlank(query)) {
            return new ArrayList<SkillMeta>();
        }
        String cacheKey =
                "skills_sh_search_" + Integer.toHexString((query + "|" + limit).hashCode());
        String cached = stateStore.readCachedIndex(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return SkillMetaDeserialize.deserializeList(cached);
        }

        String url =
                SEARCH_URL + "?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&limit=" + limit;
        ONode node = ONode.ofJson(httpClient.getText(url, null));
        ONode items = node.get("skills").isArray() ? node.get("skills") : node;
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        for (int i = 0; i < items.size(); i++) {
            ONode item = items.get(i);
            SkillMeta meta = metaFromSearchItem(item);
            if (meta != null) {
                results.add(meta);
            }
        }
        stateStore.writeCachedIndex(cacheKey, ONode.serialize(results));
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
        String canonical = normalizeIdentifier(identifier);
        SkillBundle bundle = githubSkillSource.fetch(canonical);
        if (bundle != null) {
            bundle.setSource(sourceId());
            bundle.setIdentifier(wrapIdentifier(canonical));
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
        String canonical = normalizeIdentifier(identifier);
        SkillMeta meta = githubSkillSource.inspect(canonical);
        if (meta != null) {
            meta.setSource(sourceId());
            meta.setIdentifier(wrapIdentifier(canonical));
            meta.setTrustLevel(trustLevelFor(canonical));
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
        return "skills-sh";
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
     * 执行metaFrom搜索Item相关逻辑。
     *
     * @param item item 参数。
     * @return 返回meta From搜索Item结果。
     */
    private SkillMeta metaFromSearchItem(ONode item) {
        String canonical = item.get("id").getString();
        if (StrUtil.isBlank(canonical)) {
            String repo = item.get("source").getString();
            String skillId = item.get("skillId").getString();
            if (StrUtil.hasBlank(repo, skillId)) {
                return null;
            }
            canonical = repo + "/" + skillId;
        }
        String[] parts = canonical.split("/", 3);
        if (parts.length < 3) {
            return null;
        }

        SkillMeta meta = new SkillMeta();
        meta.setName(
                StrUtil.blankToDefault(
                        item.get("name").getString(),
                        parts[2].substring(parts[2].lastIndexOf('/') + 1)));
        int installsCount = item.get("installs").getInt(0);
        String installs = installsCount > 0 ? String.valueOf(installsCount) : "";
        meta.setDescription(
                "Indexed by skills.sh from "
                        + parts[0]
                        + "/"
                        + parts[1]
                        + (StrUtil.isNotBlank(installs) ? " · " + installs + " installs" : ""));
        meta.setSource(sourceId());
        meta.setIdentifier(wrapIdentifier(canonical));
        meta.setTrustLevel(trustLevelFor(canonical));
        meta.setRepo(parts[0] + "/" + parts[1]);
        meta.setPath(parts[2]);
        return meta;
    }

    /**
     * 规范化Identifier。
     *
     * @param identifier identifier标识或键值。
     * @return 返回Identifier结果。
     */
    private String normalizeIdentifier(String identifier) {
        String normalized = StrUtil.nullToEmpty(identifier).trim();
        if (normalized.startsWith("skills-sh/")) {
            normalized = normalized.substring("skills-sh/".length());
        }
        if (normalized.startsWith("skills.sh/")) {
            normalized = normalized.substring("skills.sh/".length());
        }
        return normalized.replace('\\', '/');
    }

    /**
     * 执行wrapIdentifier相关逻辑。
     *
     * @param canonical canonical 参数。
     * @return 返回wrap Identifier结果。
     */
    private String wrapIdentifier(String canonical) {
        return "skills-sh/" + canonical;
    }
}
