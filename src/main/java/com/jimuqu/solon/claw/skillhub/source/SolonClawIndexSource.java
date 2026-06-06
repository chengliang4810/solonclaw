package com.jimuqu.solon.claw.skillhub.source;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import java.util.ArrayList;
import java.util.List;
import org.noear.snack4.ONode;

/** 承载Solon项目索引来源相关状态和辅助逻辑。 */
public class SolonClawIndexSource implements SkillSource {
    /** 索引URL的统一常量值。 */
    private static final String INDEX_URL = "https://solon-claw.local/docs/api/skills-index.json";

    /** 记录Solon项目索引来源中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /** 记录Solon项目索引来源中的状态Store。 */
    private final SkillHubStateStore stateStore;

    /** 记录Solon项目索引来源中的GitHub技能来源。 */
    private final GitHubSkillSource githubSkillSource;

    /**
     * 创建Solon项目Index来源实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     * @param stateStore 状态Store参数。
     * @param githubSkillSource GitHub技能来源参数。
     */
    public SolonClawIndexSource(
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
        ONode index = loadIndex();
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        String normalized = StrUtil.nullToEmpty(query).toLowerCase();
        ONode skills = index.get("skills");
        for (int i = 0; i < skills.size(); i++) {
            ONode item = skills.get(i);
            String searchable =
                    (item.get("name").getString()
                                    + " "
                                    + item.get("description").getString()
                                    + " "
                                    + item.get("tags").toJson())
                            .toLowerCase();
            if (normalized.length() == 0 || searchable.contains(normalized)) {
                SkillMeta meta = toMeta(item);
                results.add(meta);
                if (results.size() >= limit) {
                    break;
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
        ONode entry = findEntry(identifier);
        if (entry == null) {
            return null;
        }
        String resolved = entry.get("resolved_github_id").getString();
        SkillBundle bundle = null;
        if (StrUtil.isNotBlank(resolved)) {
            bundle = githubSkillSource.fetch(resolved);
        }
        if (bundle == null) {
            String repo = entry.get("repo").getString();
            String path = entry.get("path").getString();
            if (StrUtil.isNotBlank(repo) && StrUtil.isNotBlank(path)) {
                bundle = githubSkillSource.fetch(repo + "/" + path);
            }
        }
        if (bundle != null) {
            bundle.setSource(StrUtil.blankToDefault(entry.get("source").getString(), sourceId()));
            bundle.setIdentifier(identifier);
            bundle.setTrustLevel(
                    StrUtil.blankToDefault(entry.get("trust_level").getString(), "community"));
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
        ONode entry = findEntry(identifier);
        return entry == null ? null : toMeta(entry);
    }

    /**
     * 执行来源标识相关逻辑。
     *
     * @return 返回来源标识。
     */
    @Override
    public String sourceId() {
        return "solonclaw-index";
    }

    /**
     * 执行trust级别For相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回trust级别For结果。
     */
    @Override
    public String trustLevelFor(String identifier) {
        try {
            ONode entry = findEntry(identifier);
            return entry == null
                    ? "community"
                    : StrUtil.blankToDefault(entry.get("trust_level").getString(), "community");
        } catch (Exception e) {
            return "community";
        }
    }

    /**
     * 加载Index。
     *
     * @return 返回Index结果。
     */
    private ONode loadIndex() throws Exception {
        String cached = stateStore.readCachedIndex("solonclaw-index");
        if (StrUtil.isNotBlank(cached)) {
            return ONode.ofJson(cached);
        }
        String text = httpClient.getText(INDEX_URL, null);
        stateStore.writeCachedIndex("solonclaw-index", text);
        return ONode.ofJson(text);
    }

    /**
     * 查找Entry。
     *
     * @param identifier identifier标识或键值。
     * @return 返回Entry结果。
     */
    private ONode findEntry(String identifier) throws Exception {
        ONode index = loadIndex();
        String normalized = stripPrefix(identifier);
        ONode skills = index.get("skills");
        for (int i = 0; i < skills.size(); i++) {
            ONode item = skills.get(i);
            String candidate = stripPrefix(item.get("identifier").getString());
            if (identifier.equals(item.get("identifier").getString())
                    || normalized.equals(candidate)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 转换为Meta。
     *
     * @param node 节点参数。
     * @return 返回转换后的Meta。
     */
    private SkillMeta toMeta(ONode node) {
        SkillMeta meta = new SkillMeta();
        meta.setName(node.get("name").getString());
        meta.setDescription(node.get("description").getString());
        meta.setSource(StrUtil.blankToDefault(node.get("source").getString(), sourceId()));
        meta.setIdentifier(node.get("identifier").getString());
        meta.setTrustLevel(
                StrUtil.blankToDefault(node.get("trust_level").getString(), "community"));
        meta.setRepo(node.get("repo").getString());
        meta.setPath(node.get("path").getString());
        List<String> tags = new ArrayList<String>();
        ONode tagNodes = node.get("tags");
        for (int i = 0; i < tagNodes.size(); i++) {
            tags.add(tagNodes.get(i).getString());
        }
        meta.setTags(tags);
        return meta;
    }

    /**
     * 移除签名前缀，得到纯十六进制签名。
     *
     * @param identifier identifier标识或键值。
     * @return 返回strip Prefix结果。
     */
    private String stripPrefix(String identifier) {
        String normalized = StrUtil.nullToEmpty(identifier);
        String[] prefixes =
                new String[] {"skills-sh/", "skills.sh/", "official/", "github/", "clawhub/"};
        for (String prefix : prefixes) {
            if (normalized.startsWith(prefix)) {
                return normalized.substring(prefix.length());
            }
        }
        return normalized;
    }
}
