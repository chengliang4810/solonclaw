package com.jimuqu.solon.claw.skillhub.source;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.support.SkillBundlePathSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillFrontmatterSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** well-known skill endpoint 适配器。 */
public class WellKnownSkillSource implements SkillSource {
    /** 基础路径的统一常量值。 */
    private static final String BASE_PATH = "/.well-known/skills";

    /** 记录Well已知技能来源中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /**
     * 创建Well Known技能来源实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     */
    public WellKnownSkillSource(SkillHubHttpClient httpClient) {
        this.httpClient = httpClient;
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
        String indexUrl = queryToIndexUrl(query);
        if (StrUtil.isBlank(indexUrl)) {
            return new ArrayList<SkillMeta>();
        }
        ONode node = ONode.ofJson(httpClient.getText(indexUrl, null));
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        ONode skills = node.get("skills");
        for (int i = 0; i < skills.size(); i++) {
            ONode item = skills.get(i);
            if (results.size() >= limit) {
                break;
            }
            String name = item.get("name").getString();
            if (StrUtil.isBlank(name)) {
                continue;
            }
            SkillMeta meta = new SkillMeta();
            meta.setName(name);
            meta.setDescription(item.get("description").getString());
            meta.setSource(sourceId());
            meta.setIdentifier(wrapIdentifier(baseUrl(indexUrl), name));
            meta.setTrustLevel("community");
            meta.setPath(name);
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put("index_url", indexUrl);
            extra.put("base_url", baseUrl(indexUrl));
            meta.setExtra(extra);
            results.add(meta);
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
        ParsedIdentifier parsed = parseIdentifier(identifier);
        if (parsed == null) {
            return null;
        }
        ONode index = ONode.ofJson(httpClient.getText(parsed.indexUrl, null));
        ONode entry = null;
        ONode skills = index.get("skills");
        for (int i = 0; i < skills.size(); i++) {
            ONode item = skills.get(i);
            if (parsed.skillName.equals(item.get("name").getString())) {
                entry = item;
                break;
            }
        }
        if (entry == null) {
            return null;
        }

        List<String> files = new ArrayList<String>();
        if (entry.get("files").isArray()) {
            for (int i = 0; i < entry.get("files").size(); i++) {
                files.add(entry.get("files").get(i).getString());
            }
        }
        if (files.isEmpty()) {
            files.add("SKILL.md");
        }

        SkillBundle bundle = new SkillBundle();
        bundle.setName(SkillBundlePathSupport.normalizeSkillName(parsed.skillName));
        bundle.setSource(sourceId());
        bundle.setIdentifier(identifier);
        bundle.setTrustLevel("community");
        for (String file : files) {
            String safeFile = SkillBundlePathSupport.normalizeBundlePath(file);
            bundle.getFiles()
                    .put(safeFile, httpClient.getText(parsed.skillUrl + "/" + safeFile, null));
        }
        if (!bundle.getFiles().containsKey("SKILL.md")) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("index_url", parsed.indexUrl);
        metadata.put("base_url", parsed.baseUrl);
        metadata.put("endpoint", parsed.skillUrl);
        bundle.setMetadata(metadata);
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
        ParsedIdentifier parsed = parseIdentifier(identifier);
        if (parsed == null) {
            return null;
        }
        String skillMd = httpClient.getText(parsed.skillUrl + "/SKILL.md", null);
        Map<String, Object> frontmatter = SkillFrontmatterSupport.parseFrontmatter(skillMd);
        SkillMeta meta = new SkillMeta();
        meta.setName(SkillFrontmatterSupport.resolveName(frontmatter, parsed.skillName));
        meta.setDescription(SkillFrontmatterSupport.resolveDescription(frontmatter, ""));
        meta.setSource(sourceId());
        meta.setIdentifier(identifier);
        meta.setTrustLevel("community");
        meta.setPath(parsed.skillName);
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
        return "well-known";
    }

    /**
     * 执行trust级别For相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回trust级别For结果。
     */
    @Override
    public String trustLevelFor(String identifier) {
        return "community";
    }

    /**
     * 查询To Index URL。
     *
     * @param query 查询参数。
     * @return 返回To Index URL结果。
     */
    private String queryToIndexUrl(String query) {
        String normalized = StrUtil.nullToEmpty(query).trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return null;
        }
        if (normalized.endsWith("/index.json")) {
            return normalized;
        }
        if (normalized.contains(BASE_PATH + "/")) {
            return normalized.substring(0, normalized.indexOf(BASE_PATH + "/") + BASE_PATH.length())
                    + "/index.json";
        }
        return normalized.replaceAll("/+$", "") + BASE_PATH + "/index.json";
    }

    /**
     * 执行wrapIdentifier相关逻辑。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param skillName 技能名称参数。
     * @return 返回wrap Identifier结果。
     */
    private String wrapIdentifier(String baseUrl, String skillName) {
        return "well-known:" + baseUrl + "#" + skillName;
    }

    /**
     * 解析Identifier。
     *
     * @param identifier identifier标识或键值。
     * @return 返回解析后的Identifier。
     */
    private ParsedIdentifier parseIdentifier(String identifier) {
        if (StrUtil.isBlank(identifier) || !identifier.startsWith("well-known:")) {
            return null;
        }
        String raw = identifier.substring("well-known:".length());
        int idx = raw.lastIndexOf('#');
        if (idx <= 0 || idx >= raw.length() - 1) {
            return null;
        }
        ParsedIdentifier parsed = new ParsedIdentifier();
        parsed.baseUrl = raw.substring(0, idx);
        parsed.skillName = raw.substring(idx + 1);
        parsed.indexUrl = parsed.baseUrl + "/index.json";
        parsed.skillUrl = parsed.baseUrl + "/" + parsed.skillName;
        return parsed;
    }

    /**
     * 执行基础URL相关逻辑。
     *
     * @param indexUrl 待校验或访问的地址参数。
     * @return 返回base URL结果。
     */
    private String baseUrl(String indexUrl) {
        String normalized = indexUrl.replaceAll("/index\\.json$", "");
        return normalized;
    }

    /** 承载ParsedIdentifier相关状态和辅助逻辑。 */
    private static class ParsedIdentifier {
        /** 记录ParsedIdentifier中的基础URL。 */
        private String baseUrl;

        /** 记录ParsedIdentifier中的索引URL。 */
        private String indexUrl;

        /** 记录ParsedIdentifier中的技能名称。 */
        private String skillName;

        /** 记录ParsedIdentifier中的技能URL。 */
        private String skillUrl;
    }
}
