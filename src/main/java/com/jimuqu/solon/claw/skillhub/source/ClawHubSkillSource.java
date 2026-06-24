package com.jimuqu.solon.claw.skillhub.source;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.support.SkillBundlePathSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.noear.snack4.ONode;

/** 承载项目中心技能来源相关状态和辅助逻辑。 */
public class ClawHubSkillSource implements SkillSource {
    /** 公开基础URL的统一常量值。 */
    public static final String PUBLIC_BASE_URL = "https://clawhub.ai/api/v1";

    /** CN搜索URL的统一常量值。 */
    public static final String CN_SEARCH_URL = "https://lightmake.site/api/v1/search";

    /** CNPRIMARYDOWNLOADURL的统一常量值。 */
    public static final String CN_PRIMARY_DOWNLOAD_URL =
            "https://lightmake.site/api/v1/download?slug=%s";

    /** CN索引URL的统一常量值。 */
    public static final String CN_INDEX_URL =
            "https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com/skills.json";

    /** CN静态资源DOWNLOADURL的统一常量值。 */
    public static final String CN_STATIC_DOWNLOAD_URL =
            "https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com/skills/%s.zip";

    /** 索引缓存键的统一常量值。 */
    private static final String INDEX_CACHE_KEY = "clawhub_cn_index";

    /** 记录项目中心技能来源中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /** 记录项目中心技能来源中的状态Store。 */
    private final SkillHubStateStore stateStore;

    /**
     * 创建项目中心技能来源实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     * @param stateStore 状态Store参数。
     */
    public ClawHubSkillSource(SkillHubHttpClient httpClient, SkillHubStateStore stateStore) {
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
        String cacheKey = "clawhub_search_" + Integer.toHexString((query + "|" + limit).hashCode());
        String cached = stateStore.readCachedIndex(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return SkillMetaDeserialize.deserializeList(cached);
        }

        int safeLimit = Math.max(1, limit);
        String safeQuery = StrUtil.trimToEmpty(query);
        List<SkillMeta> results =
                StrUtil.isBlank(safeQuery)
                        ? loadDomesticIndex(safeLimit)
                        : searchDomestic(safeQuery, safeLimit);
        if (results.isEmpty()) {
            results = searchPublic(safeQuery, safeLimit);
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
        String slug =
                identifier.contains("/")
                        ? identifier.substring(identifier.lastIndexOf('/') + 1)
                        : identifier;
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.putAll(tryDownloadZip(urlWithSlug(CN_PRIMARY_DOWNLOAD_URL, slug)));
        if (!files.containsKey("SKILL.md")) {
            files.putAll(tryDownloadZip(urlWithSlug(CN_STATIC_DOWNLOAD_URL, slug)));
        }
        if (!files.containsKey("SKILL.md")) {
            files.putAll(fetchFromPublicRegistry(slug));
        }
        if (!files.containsKey("SKILL.md")) {
            return null;
        }

        SkillBundle bundle = new SkillBundle();
        bundle.setName(SkillBundlePathSupport.normalizeSkillName(slug));
        bundle.setFiles(files);
        bundle.setSource(sourceId());
        bundle.setIdentifier(slug);
        bundle.setTrustLevel("community");
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
        String slug =
                identifier.contains("/")
                        ? identifier.substring(identifier.lastIndexOf('/') + 1)
                        : identifier;
        SkillMeta domestic = findExactBySlug(searchDomestic(slug, 20), slug);
        if (domestic != null) {
            return domestic;
        }
        domestic = findExactBySlug(loadDomesticIndex(200), slug);
        if (domestic != null) {
            return domestic;
        }

        ONode payload = coerceSkillPayload(tryGetJson(PUBLIC_BASE_URL + "/skills/" + slug));
        if (payload == null) {
            return null;
        }
        return toSkillMeta(payload, slug);
    }

    /**
     * 执行来源标识相关逻辑。
     *
     * @return 返回来源标识。
     */
    @Override
    public String sourceId() {
        return "clawhub";
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
     * 提取Files。
     *
     * @param payload 待签名或解析的载荷内容。
     * @param httpClient HTTPClient参数。
     * @return 返回Files结果。
     */
    public static Map<String, String> extractFiles(ONode payload, SkillHubHttpClient httpClient)
            throws Exception {
        Map<String, String> files = new LinkedHashMap<String, String>();
        if (payload == null || payload.isNull()) {
            return files;
        }
        ONode fileList = payload.get("files");
        if (fileList.isObject()) {
            Map<String, Object> map = ONode.deserialize(fileList.toJson(), LinkedHashMap.class);
            if (map != null) {
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    if (entry.getValue() != null) {
                        files.put(
                                SkillBundlePathSupport.normalizeBundlePath(entry.getKey()),
                                String.valueOf(entry.getValue()));
                    }
                }
            }
            return files;
        }
        if (!fileList.isArray()) {
            return files;
        }
        for (int i = 0; i < fileList.size(); i++) {
            ONode fileNode = fileList.get(i);
            String fileName =
                    StrUtil.blankToDefault(
                            fileNode.get("path").getString(), fileNode.get("name").getString());
            if (StrUtil.isBlank(fileName)) {
                continue;
            }
            String safeName = SkillBundlePathSupport.normalizeBundlePath(fileName);
            String inlineContent = fileNode.get("content").getString();
            if (StrUtil.isNotBlank(inlineContent)) {
                files.put(safeName, inlineContent);
                continue;
            }
            String rawUrl =
                    StrUtil.firstNonBlank(
                            fileNode.get("rawUrl").getString(),
                            fileNode.get("downloadUrl").getString(),
                            fileNode.get("url").getString());
            if (StrUtil.isNotBlank(rawUrl) && rawUrl.startsWith("http")) {
                files.put(safeName, httpClient == null ? null : httpClient.getText(rawUrl, null));
            }
        }
        return files;
    }

    /**
     * 搜索国内。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回国内结果。
     */
    private List<SkillMeta> searchDomestic(String query, int limit) throws Exception {
        if (StrUtil.isBlank(query)) {
            return Collections.emptyList();
        }
        ONode payload =
                tryGetJson(
                        CN_SEARCH_URL
                                + "?q="
                                + URLEncoder.encode(query, "UTF-8")
                                + "&limit="
                                + Math.max(1, limit));
        if (payload == null) {
            return Collections.emptyList();
        }
        return limitResults(mapSkillMetas(payload.get("results"), null), limit);
    }

    /**
     * 加载国内Index。
     *
     * @param limit 最大返回数量。
     * @return 返回国内Index结果。
     */
    private List<SkillMeta> loadDomesticIndex(int limit) throws Exception {
        String cached = stateStore.readCachedIndex(INDEX_CACHE_KEY);
        if (StrUtil.isNotBlank(cached)) {
            return limitResults(SkillMetaDeserialize.deserializeList(cached), limit);
        }
        ONode payload = tryGetJson(CN_INDEX_URL);
        if (payload == null) {
            return Collections.emptyList();
        }
        List<SkillMeta> results = mapSkillMetas(payload.get("skills"), null);
        stateStore.writeCachedIndex(INDEX_CACHE_KEY, ONode.serialize(results));
        return limitResults(results, limit);
    }

    /**
     * 搜索公开。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回公开结果。
     */
    private List<SkillMeta> searchPublic(String query, int limit) throws Exception {
        ONode payload =
                tryGetJson(
                        PUBLIC_BASE_URL
                                + "/skills?search="
                                + URLEncoder.encode(StrUtil.nullToEmpty(query), "UTF-8")
                                + "&limit="
                                + Math.max(1, limit));
        if (payload == null) {
            return Collections.emptyList();
        }
        ONode items = payload.get("items").isArray() ? payload.get("items") : payload;
        return limitResults(mapSkillMetas(items, null), limit);
    }

    /**
     * 拉取From公开注册表。
     *
     * @param slug slug 参数。
     * @return 返回fetch From公开注册表结果。
     */
    private Map<String, String> fetchFromPublicRegistry(String slug) throws Exception {
        Map<String, String> files = new LinkedHashMap<String, String>();
        ONode rootPayload = tryGetJson(PUBLIC_BASE_URL + "/skills/" + slug);
        ONode skillData = coerceSkillPayload(rootPayload);
        if (skillData == null) {
            return files;
        }
        String version = resolveLatestVersion(slug, rootPayload, skillData);
        if (StrUtil.isBlank(version)) {
            return files;
        }

        files.putAll(
                tryDownloadZip(
                        PUBLIC_BASE_URL
                                + "/download?slug="
                                + URLEncoder.encode(slug, "UTF-8")
                                + "&version="
                                + URLEncoder.encode(version, "UTF-8")));
        if (!files.containsKey("SKILL.md")) {
            ONode versionPayload =
                    tryGetJson(PUBLIC_BASE_URL + "/skills/" + slug + "/versions/" + version);
            files.putAll(extractFiles(versionPayload, httpClient));
            files.putAll(
                    extractFiles(
                            versionPayload == null ? null : versionPayload.get("version"),
                            httpClient));
        }
        return files;
    }

    /**
     * 执行coerce技能载荷相关逻辑。
     *
     * @param node 节点参数。
     * @return 返回coerce技能Payload结果。
     */
    private ONode coerceSkillPayload(ONode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        if (node.get("skill").isObject()) {
            return node.get("skill");
        }
        return node;
    }

    /**
     * 解析Latest版本。
     *
     * @param slug slug 参数。
     * @param rootPayload root载荷请求载荷。
     * @param payload 待签名或解析的载荷内容。
     * @return 返回解析后的Latest版本。
     */
    private String resolveLatestVersion(String slug, ONode rootPayload, ONode payload)
            throws Exception {
        String latestVersion = payload.get("latestVersion").get("version").getString();
        if (StrUtil.isNotBlank(latestVersion)) {
            return latestVersion;
        }
        latestVersion = rootPayload.get("latestVersion").get("version").getString();
        if (StrUtil.isNotBlank(latestVersion)) {
            return latestVersion;
        }
        latestVersion = payload.get("tags").get("latest").getString();
        if (StrUtil.isNotBlank(latestVersion)) {
            return latestVersion;
        }
        ONode versions = tryGetJson(PUBLIC_BASE_URL + "/skills/" + slug + "/versions");
        if (versions.isArray() && versions.size() > 0) {
            return versions.get(0).get("version").getString();
        }
        return null;
    }

    /**
     * 执行tryDownloadZip相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回try Download Zip结果。
     */
    private Map<String, String> tryDownloadZip(String url) throws Exception {
        Map<String, String> files = new LinkedHashMap<String, String>();
        byte[] bytes;
        try {
            bytes = httpClient.getBytes(url, null);
        } catch (Exception e) {
            return files;
        }
        if (bytes == null || bytes.length == 0) {
            return files;
        }
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String safeName = SkillBundlePathSupport.normalizeBundlePath(entry.getName());
                if (entry.getSize() > 500_000L) {
                    continue;
                }
                byte[] raw = readZipEntry(zip);
                files.put(safeName, new String(raw, StandardCharsets.UTF_8));
            }
        } finally {
            zip.close();
        }
        return files;
    }

    /**
     * 执行tryGetJSON相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回try Get JSON结果。
     */
    private ONode tryGetJson(String url) throws Exception {
        try {
            return ONode.ofJson(httpClient.getText(url, null));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取Zip Entry。
     *
     * @param zip zip 参数。
     * @return 返回读取到的Zip Entry。
     */
    private byte[] readZipEntry(ZipInputStream zip) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * 规范化Tags。
     *
     * @param tagsNode tags节点参数。
     * @return 返回Tags结果。
     */
    private List<String> normalizeTags(ONode tagsNode) {
        List<String> tags = new ArrayList<String>();
        if (tagsNode.isArray()) {
            for (int i = 0; i < tagsNode.size(); i++) {
                String tag = tagsNode.get(i).getString();
                if (StrUtil.isNotBlank(tag) && !tags.contains(tag)) {
                    tags.add(tag);
                }
            }
        } else if (tagsNode.isObject()) {
            Map<String, Object> map = ONode.deserialize(tagsNode.toJson(), LinkedHashMap.class);
            if (map != null) {
                for (String key : map.keySet()) {
                    if (!"latest".equals(key) && StrUtil.isNotBlank(key) && !tags.contains(key)) {
                        tags.add(key);
                    }
                }
            }
        }
        return tags;
    }

    /**
     * 执行map技能Metas相关逻辑。
     *
     * @param items items 参数。
     * @param fallbackSlug 兜底Slug参数。
     * @return 返回map技能Metas结果。
     */
    private List<SkillMeta> mapSkillMetas(ONode items, String fallbackSlug) {
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        if (items == null || !items.isArray()) {
            return results;
        }
        for (int i = 0; i < items.size(); i++) {
            SkillMeta meta = toSkillMeta(items.get(i), fallbackSlug);
            if (meta != null) {
                results.add(meta);
            }
        }
        return results;
    }

    /**
     * 转换为技能Meta。
     *
     * @param item item 参数。
     * @param fallbackSlug 兜底Slug参数。
     * @return 返回转换后的技能Meta。
     */
    private SkillMeta toSkillMeta(ONode item, String fallbackSlug) {
        if (item == null || item.isNull()) {
            return null;
        }
        String slug = StrUtil.firstNonBlank(item.get("slug").getString(), fallbackSlug);
        if (StrUtil.isBlank(slug)) {
            return null;
        }
        SkillMeta meta = new SkillMeta();
        meta.setName(
                StrUtil.firstNonBlank(
                        item.get("displayName").getString(), item.get("name").getString(), slug));
        meta.setDescription(
                StrUtil.firstNonBlank(
                        item.get("summary").getString(),
                        item.get("description").getString(),
                        item.get("description_zh").getString()));
        meta.setSource(sourceId());
        meta.setIdentifier(slug);
        meta.setTrustLevel("community");
        List<String> tags = normalizeTags(item.get("tags"));
        List<String> categories = normalizeTags(item.get("categories"));
        for (String category : categories) {
            if (!tags.contains(category)) {
                tags.add(category);
            }
        }
        meta.setTags(tags);
        putIfNotBlank(meta, "homepage", item.get("homepage").getString());
        putIfNotBlank(meta, "version", item.get("version").getString());
        putIfNotBlank(meta, "owner", item.get("owner_name").getString());
        return meta;
    }

    /**
     * 根据Slug查找对应数据。
     *
     * @param metas metas 参数。
     * @param slug slug 参数。
     * @return 返回按Slug查找得到的结果。
     */
    private SkillMeta findExactBySlug(List<SkillMeta> metas, String slug) {
        for (SkillMeta meta : metas) {
            if (slug.equals(meta.getIdentifier())) {
                return meta;
            }
        }
        return null;
    }

    /**
     * 执行限制Results相关逻辑。
     *
     * @param metas metas 参数。
     * @param limit 最大返回数量。
     * @return 返回限制Results结果。
     */
    private List<SkillMeta> limitResults(List<SkillMeta> metas, int limit) {
        if (metas == null || metas.isEmpty()) {
            return Collections.emptyList();
        }
        if (metas.size() <= limit) {
            return metas;
        }
        return new ArrayList<SkillMeta>(metas.subList(0, limit));
    }

    /**
     * 写入If Not Blank。
     *
     * @param meta meta 参数。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private void putIfNotBlank(SkillMeta meta, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            meta.getExtra().put(key, value);
        }
    }

    /**
     * 执行URLWithSlug相关逻辑。
     *
     * @param template template 参数。
     * @param slug slug 参数。
     * @return 返回URL With Slug结果。
     */
    private String urlWithSlug(String template, String slug) throws Exception {
        return String.format(template, URLEncoder.encode(slug, "UTF-8"));
    }

}
