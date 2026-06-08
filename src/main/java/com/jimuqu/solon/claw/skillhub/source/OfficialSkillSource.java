package com.jimuqu.solon.claw.skillhub.source;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.support.SkillFrontmatterSupport;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 仓库内 optional-skills 来源。 */
public class OfficialSkillSource implements SkillSource {
    /** 记录官方技能来源中的repo根用户。 */
    private final File repoRoot;

    /**
     * 创建官方技能来源实例，并注入运行所需依赖。
     *
     * @param repoRoot repoRoot 参数。
     */
    public OfficialSkillSource(File repoRoot) {
        this.repoRoot = repoRoot;
    }

    /**
     * 执行搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    @Override
    public List<SkillMeta> search(String query, int limit) {
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        File root = FileUtil.file(repoRoot, "optional-skills");
        if (!root.exists()) {
            return results;
        }
        String normalized = StrUtil.nullToEmpty(query).toLowerCase();
        for (File categoryDir : root.listFiles()) {
            if (categoryDir == null || !categoryDir.isDirectory()) {
                continue;
            }
            for (File skillDir : categoryDir.listFiles()) {
                if (skillDir == null || !skillDir.isDirectory()) {
                    continue;
                }
                File skillFile = FileUtil.file(skillDir, "SKILL.md");
                if (!skillFile.exists()) {
                    continue;
                }
                String content = FileUtil.readUtf8String(skillFile);
                Map<String, Object> frontmatter = SkillFrontmatterSupport.parseFrontmatter(content);
                SkillMeta meta = new SkillMeta();
                meta.setName(SkillFrontmatterSupport.resolveName(frontmatter, skillDir.getName()));
                meta.setDescription(SkillFrontmatterSupport.resolveDescription(frontmatter, ""));
                meta.setSource(sourceId());
                meta.setIdentifier("official/" + categoryDir.getName() + "/" + skillDir.getName());
                meta.setTrustLevel("builtin");
                meta.setPath(categoryDir.getName() + "/" + skillDir.getName());
                meta.setTags(
                        new ArrayList<String>(SkillFrontmatterSupport.resolveTags(frontmatter)));
                if ((meta.getName() + " " + meta.getDescription())
                        .toLowerCase()
                        .contains(normalized)) {
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
    public SkillBundle fetch(String identifier) {
        String normalized = normalizeIdentifier(identifier);
        File skillDir = FileUtil.file(repoRoot, "optional-skills", normalized);
        if (!skillDir.exists()) {
            return null;
        }
        SkillBundle bundle = new SkillBundle();
        bundle.setName(skillDir.getName());
        bundle.setSource(sourceId());
        bundle.setIdentifier(identifier);
        bundle.setTrustLevel("builtin");
        for (File file : FileUtil.loopFiles(skillDir)) {
            if (!file.isDirectory()) {
                String relative =
                        file.getAbsolutePath()
                                .substring(skillDir.getAbsolutePath().length() + 1)
                                .replace(File.separatorChar, '/');
                bundle.getFiles().put(relative, FileUtil.readUtf8String(file));
            }
        }
        return bundle.getFiles().containsKey("SKILL.md") ? bundle : null;
    }

    /**
     * 执行inspect相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回inspect结果。
     */
    @Override
    public SkillMeta inspect(String identifier) {
        SkillBundle bundle = fetch(identifier);
        if (bundle == null) {
            return null;
        }
        SkillMeta meta = new SkillMeta();
        Map<String, Object> frontmatter =
                SkillFrontmatterSupport.parseFrontmatter(bundle.getFiles().get("SKILL.md"));
        meta.setName(SkillFrontmatterSupport.resolveName(frontmatter, bundle.getName()));
        meta.setDescription(SkillFrontmatterSupport.resolveDescription(frontmatter, ""));
        meta.setSource(sourceId());
        meta.setIdentifier(identifier);
        meta.setTrustLevel("builtin");
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
        return "official";
    }

    /**
     * 执行trust级别For相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回trust级别For结果。
     */
    @Override
    public String trustLevelFor(String identifier) {
        return "builtin";
    }

    /**
     * 规范化Identifier。
     *
     * @param identifier identifier标识或键值。
     * @return 返回Identifier结果。
     */
    private String normalizeIdentifier(String identifier) {
        if (identifier.startsWith("official/")) {
            return identifier.substring("official/".length());
        }
        return identifier;
    }
}
