package com.jimuqu.solon.claw.skillhub.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.service.SkillGuardService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.SkillImportService;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.ScanResult;
import com.jimuqu.solon.claw.skillhub.model.SkillBrowseResult;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.model.TapRecord;
import com.jimuqu.solon.claw.skillhub.source.ClaudeMarketplaceSkillSource;
import com.jimuqu.solon.claw.skillhub.source.ClawHubSkillSource;
import com.jimuqu.solon.claw.skillhub.source.GitHubSkillSource;
import com.jimuqu.solon.claw.skillhub.source.LobeHubSkillSource;
import com.jimuqu.solon.claw.skillhub.source.OfficialSkillSource;
import com.jimuqu.solon.claw.skillhub.source.SkillSource;
import com.jimuqu.solon.claw.skillhub.source.SkillsShSkillSource;
import com.jimuqu.solon.claw.skillhub.source.SolonClawIndexSource;
import com.jimuqu.solon.claw.skillhub.source.WellKnownSkillSource;
import com.jimuqu.solon.claw.skillhub.support.GitHubAuth;
import com.jimuqu.solon.claw.skillhub.support.SkillBundlePathSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillHubContentSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认 Skills Hub 服务。 */
public class DefaultSkillHubService implements SkillHubService {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultSkillHubService.class);

    /** 记录默认技能中心中的repo根用户。 */
    private final File repoRoot;

    /** 记录默认技能中心中的技能目录。 */
    private final File skillsDir;

    /** 注入技能导入服务，用于调用对应业务能力。 */
    private final SkillImportService skillImportService;

    /** 注入技能保护服务，用于调用对应业务能力。 */
    private final SkillGuardService skillGuardService;

    /** 记录默认技能中心中的状态Store。 */
    private final SkillHubStateStore stateStore;

    /** 记录默认技能中心中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /** 记录默认技能中心中的git中心认证。 */
    private final GitHubAuth gitHubAuth;

    /** 记录默认技能中心中的git中心技能来源。 */
    private final GitHubSkillSource gitHubSkillSource;

    /**
     * 创建默认技能中心服务实例，并注入运行所需依赖。
     *
     * @param repoRoot repoRoot 参数。
     * @param skillsDir 文件或目录路径参数。
     * @param skillImportService 技能Import服务依赖。
     * @param skillGuardService 技能防护服务依赖。
     * @param stateStore 状态Store参数。
     * @param httpClient HTTPClient参数。
     * @param gitHubAuth gitHub鉴权参数。
     * @param gitHubSkillSource gitHub技能来源参数。
     */
    public DefaultSkillHubService(
            File repoRoot,
            File skillsDir,
            SkillImportService skillImportService,
            SkillGuardService skillGuardService,
            SkillHubStateStore stateStore,
            SkillHubHttpClient httpClient,
            GitHubAuth gitHubAuth,
            GitHubSkillSource gitHubSkillSource) {
        this.repoRoot = repoRoot;
        this.skillsDir = skillsDir;
        this.skillImportService = skillImportService;
        this.skillGuardService = skillGuardService;
        this.stateStore = stateStore;
        this.httpClient = httpClient;
        this.gitHubAuth = gitHubAuth;
        this.gitHubSkillSource = gitHubSkillSource;
    }

    /**
     * 执行browse相关逻辑。
     *
     * @param sourceFilter 来源过滤器参数。
     * @param page page 参数。
     * @param pageSize pageSize 参数。
     * @return 返回browse结果。
     */
    @Override
    public SkillBrowseResult browse(String sourceFilter, int page, int pageSize) throws Exception {
        SourceCollectResult collected =
                collectFromSources(
                        "", sourceFilter, Math.max(pageSize * Math.max(page, 1), pageSize));
        List<SkillMeta> all = collected.items;
        SkillBrowseResult result = new SkillBrowseResult();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int start = (safePage - 1) * safePageSize;
        int end = Math.min(start + safePageSize, all.size());
        result.setTotal(all.size());
        result.setPage(safePage);
        result.setPageSize(safePageSize);
        if (start < all.size()) {
            result.setItems(new ArrayList<SkillMeta>(all.subList(start, end)));
        }
        result.setTimedOutSources(collected.failedSources);
        return result;
    }

    /**
     * 执行搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param sourceFilter 来源过滤器参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    @Override
    public SkillBrowseResult search(String query, String sourceFilter, int limit) throws Exception {
        SourceCollectResult collected = collectFromSources(query, sourceFilter, limit);
        SkillBrowseResult result = new SkillBrowseResult();
        result.setItems(collected.items);
        result.setTotal(result.getItems().size());
        result.setPage(1);
        result.setPageSize(limit);
        result.setTimedOutSources(collected.failedSources);
        return result;
    }

    /**
     * 执行inspect相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回inspect结果。
     */
    @Override
    public SkillMeta inspect(String identifier) throws Exception {
        for (SkillSource source : sources()) {
            if (!matchesSourceFilter(source, identifier)) {
                continue;
            }
            SkillMeta meta = source.inspect(normalizeIdentifierForSource(source, identifier));
            if (meta != null) {
                return meta;
            }
        }
        return null;
    }

    /**
     * 执行install相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @param category 分类参数。
     * @param force force 参数。
     * @return 返回install结果。
     */
    @Override
    public HubInstallRecord install(String identifier, String category, boolean force)
            throws Exception {
        for (SkillSource source : sources()) {
            if (!matchesSourceFilter(source, identifier)) {
                continue;
            }
            SkillBundle bundle = source.fetch(normalizeIdentifierForSource(source, identifier));
            if (bundle != null) {
                return skillImportService.installBundle(bundle, category, force, null);
            }
        }
        throw new IllegalStateException("Skill not found in any source: " + identifier);
    }

    /**
     * 列出Installed。
     *
     * @return 返回Installed列表。
     */
    @Override
    public List<HubInstallRecord> listInstalled() {
        return stateStore.listInstalled();
    }

    /**
     * 执行check相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回check结果。
     */
    @Override
    public List<HubInstallRecord> check(String name) throws Exception {
        List<HubInstallRecord> results = new ArrayList<HubInstallRecord>();
        for (HubInstallRecord record : stateStore.listInstalled()) {
            if (StrUtil.isNotBlank(name) && !name.equals(record.getName())) {
                continue;
            }
            SkillBundle bundle = fetchFromRecordedSource(record);
            if (bundle == null) {
                continue;
            }
            HubInstallRecord copy = cloneRecord(record);
            String latestHash = SkillHubContentSupport.bundleContentHash(bundle);
            copy.getMetadata()
                    .put(
                            "status",
                            record.getContentHash().equals(latestHash)
                                    ? "up_to_date"
                                    : "update_available");
            copy.getMetadata().put("latestHash", latestHash);
            results.add(copy);
        }
        return results;
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param name 名称参数。
     * @param force force 参数。
     * @return 返回更新结果。
     */
    @Override
    public List<HubInstallRecord> update(String name, boolean force) throws Exception {
        List<HubInstallRecord> updated = new ArrayList<HubInstallRecord>();
        for (HubInstallRecord record : check(name)) {
            if (!"update_available".equals(record.getMetadata().get("status"))) {
                continue;
            }
            HubInstallRecord installed =
                    install(record.getIdentifier(), deriveCategory(record.getInstallPath()), force);
            updated.add(installed);
        }
        return updated;
    }

    /**
     * 执行审计相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回审计结果。
     */
    @Override
    public List<ScanResult> audit(String name) throws Exception {
        List<ScanResult> results = new ArrayList<ScanResult>();
        for (HubInstallRecord record : stateStore.listInstalled()) {
            if (StrUtil.isNotBlank(name) && !name.equals(record.getName())) {
                continue;
            }
            File installDir =
                    SkillBundlePathSupport.resolveUnderRoot(skillsDir, record.getInstallPath());
            ScanResult scanResult = skillGuardService.scanSkill(installDir, record.getSource());
            results.add(scanResult);
        }
        return results;
    }

    /**
     * 执行uninstall相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回uninstall结果。
     */
    @Override
    public String uninstall(String name) {
        HubInstallRecord record = stateStore.getInstalled(name);
        if (record == null) {
            throw new IllegalStateException("Hub-installed skill not found: " + name);
        }
        File installDir =
                SkillBundlePathSupport.resolveUnderRoot(skillsDir, record.getInstallPath());
        stateStore.recordUninstall(name);
        try {
            if (installDir.exists()) {
                FileUtil.del(installDir);
            }
        } catch (RuntimeException e) {
            stateStore.recordInstall(record);
            throw e;
        }
        stateStore.appendAuditLog(
                "UNINSTALL",
                name,
                record.getSource(),
                record.getTrustLevel(),
                "n/a",
                "user_request");
        return "Uninstalled " + name;
    }

    /**
     * 列出Taps。
     *
     * @return 返回Taps列表。
     */
    @Override
    public List<TapRecord> listTaps() {
        return stateStore.listTaps();
    }

    /**
     * 追加来源库。
     *
     * @param repo repo 参数。
     * @param path 文件或目录路径。
     * @return 返回add Tap结果。
     */
    @Override
    public String addTap(String repo, String path) {
        if (StrUtil.isBlank(repo) || !repo.contains("/")) {
            throw new IllegalStateException("Invalid tap repo: " + repo);
        }
        List<TapRecord> taps = new ArrayList<TapRecord>(stateStore.listTaps());
        for (TapRecord existing : taps) {
            if (repo.equals(existing.getRepo())) {
                return "Tap already exists: " + repo;
            }
        }
        TapRecord tap = new TapRecord();
        tap.setRepo(repo);
        tap.setPath(StrUtil.blankToDefault(path, "skills/"));
        taps.add(tap);
        stateStore.saveTaps(taps);
        return "Added tap: " + repo;
    }

    /**
     * 移除Tap。
     *
     * @param repo repo 参数。
     * @return 返回Tap结果。
     */
    @Override
    public String removeTap(String repo) {
        List<TapRecord> taps = new ArrayList<TapRecord>(stateStore.listTaps());
        boolean removed = false;
        for (int i = taps.size() - 1; i >= 0; i--) {
            if (repo.equals(taps.get(i).getRepo())) {
                taps.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            throw new IllegalStateException("Tap not found: " + repo);
        }
        stateStore.saveTaps(taps);
        return "Removed tap: " + repo;
    }

    /**
     * 收集From Sources。
     *
     * @param query 查询参数。
     * @param sourceFilter 来源过滤器参数。
     * @param limit 最大返回数量。
     * @return 返回From Sources结果。
     */
    private SourceCollectResult collectFromSources(String query, String sourceFilter, int limit)
            throws Exception {
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        List<String> failedSources = new ArrayList<String>();
        for (SkillSource source : sources()) {
            if (!"all".equals(sourceFilter)
                    && !source.sourceId().equals(sourceFilter)
                    && !"official".equals(source.sourceId())) {
                continue;
            }
            try {
                results.addAll(source.search(query, limit));
            } catch (Exception e) {
                failedSources.add(source.sourceId());
                log.warn(
                        "Skills Hub source search failed, skipping source: source={}, query={}, sourceFilter={}, limit={}, error={}",
                        source.sourceId(),
                        StrUtil.nullToEmpty(query),
                        StrUtil.nullToEmpty(sourceFilter),
                        limit,
                        ErrorTextSupport.safeError(e));
                log.debug(
                        "Skills Hub source search failure detail: source={}, error={}",
                        source.sourceId(),
                        ErrorTextSupport.safeError(e));
            }
        }
        Map<String, SkillMeta> unique = new LinkedHashMap<String, SkillMeta>();
        for (SkillMeta meta : results) {
            SkillMeta current = unique.get(meta.getName());
            if (current == null
                    || trustRank(meta.getTrustLevel()) > trustRank(current.getTrustLevel())) {
                unique.put(meta.getName(), meta);
            }
        }
        List<SkillMeta> deduped = new ArrayList<SkillMeta>(unique.values());
        deduped.sort(
                new Comparator<SkillMeta>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param left 左侧比较对象。
                     * @param right 右侧比较对象。
                     * @return 返回compare结果。
                     */
                    @Override
                    public int compare(SkillMeta left, SkillMeta right) {
                        int rank =
                                trustRank(right.getTrustLevel()) - trustRank(left.getTrustLevel());
                        if (rank != 0) {
                            return rank;
                        }
                        return left.getName().compareToIgnoreCase(right.getName());
                    }
                });
        if (deduped.size() > limit) {
            return new SourceCollectResult(
                    new ArrayList<SkillMeta>(deduped.subList(0, limit)), failedSources);
        }
        return new SourceCollectResult(deduped, failedSources);
    }

    /**
     * 拉取FromRecorded来源。
     *
     * @param record 记录参数。
     * @return 返回fetch From Recorded来源结果。
     */
    private SkillBundle fetchFromRecordedSource(HubInstallRecord record) throws Exception {
        for (SkillSource source : sources()) {
            if (record.getSource().equals(source.sourceId())) {
                return source.fetch(normalizeIdentifierForSource(source, record.getIdentifier()));
            }
        }
        return null;
    }

    /**
     * 执行sources相关逻辑。
     *
     * @return 返回sources结果。
     */
    protected List<SkillSource> sources() {
        List<SkillSource> sources = new ArrayList<SkillSource>();
        sources.add(new OfficialSkillSource(repoRoot));
        sources.add(new SolonClawIndexSource(httpClient, stateStore, gitHubSkillSource));
        sources.add(new SkillsShSkillSource(httpClient, stateStore, gitHubSkillSource));
        sources.add(new WellKnownSkillSource(httpClient));
        sources.add(gitHubSkillSource);
        sources.add(new ClawHubSkillSource(httpClient, stateStore));
        sources.add(
                new ClaudeMarketplaceSkillSource(
                        httpClient, gitHubAuth, gitHubSkillSource, stateStore));
        sources.add(new LobeHubSkillSource(httpClient, stateStore));
        return sources;
    }

    /**
     * 判断是否匹配来源过滤器。
     *
     * @param source 来源参数。
     * @param identifier identifier标识或键值。
     * @return 返回matches来源Filter结果。
     */
    private boolean matchesSourceFilter(SkillSource source, String identifier) {
        String normalized = StrUtil.nullToEmpty(identifier);
        String sourceId = source.sourceId();
        if ("well-known".equals(sourceId)) {
            return normalized.startsWith("well-known:");
        }
        if ("official".equals(sourceId)) {
            return normalized.startsWith("official/");
        }
        if (normalized.contains(":")) {
            return false;
        }
        if (normalized.startsWith(sourceId + "/")) {
            return true;
        }
        if ("github".equals(sourceId) && slashCount(normalized) >= 2) {
            return true;
        }
        if ("clawhub".equals(sourceId) && slashCount(normalized) == 0) {
            return true;
        }
        return false;
    }

    /**
     * 规范化Identifier For来源。
     *
     * @param source 来源参数。
     * @param identifier identifier标识或键值。
     * @return 返回Identifier For来源结果。
     */
    private String normalizeIdentifierForSource(SkillSource source, String identifier) {
        String normalized = StrUtil.nullToEmpty(identifier);
        if ("well-known".equals(source.sourceId())) {
            return normalized;
        }
        String prefix = source.sourceId() + "/";
        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length());
        }
        return normalized;
    }

    /**
     * 执行斜杠命令次数相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回slash次数结果。
     */
    private int slashCount(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    /**
     * 执行trustRank相关逻辑。
     *
     * @param trustLevel trustLevel 参数。
     * @return 返回trust Rank结果。
     */
    private int trustRank(String trustLevel) {
        if ("builtin".equals(trustLevel)) {
            return 3;
        }
        if ("trusted".equals(trustLevel)) {
            return 2;
        }
        if ("agent-created".equals(trustLevel)) {
            return 1;
        }
        return 0;
    }

    /**
     * 克隆记录。
     *
     * @param record 记录参数。
     * @return 返回clone记录结果。
     */
    private HubInstallRecord cloneRecord(HubInstallRecord record) {
        HubInstallRecord copy = new HubInstallRecord();
        copy.setName(record.getName());
        copy.setSource(record.getSource());
        copy.setIdentifier(record.getIdentifier());
        copy.setTrustLevel(record.getTrustLevel());
        copy.setScanVerdict(record.getScanVerdict());
        copy.setContentHash(record.getContentHash());
        copy.setInstallPath(record.getInstallPath());
        copy.setFiles(new ArrayList<String>(record.getFiles()));
        copy.setMetadata(new LinkedHashMap<String, Object>(record.getMetadata()));
        return copy;
    }

    /**
     * 执行deriveCategory相关逻辑。
     *
     * @param installPath 文件或目录路径参数。
     * @return 返回derive Category结果。
     */
    private String deriveCategory(String installPath) {
        int index = installPath.lastIndexOf('/');
        return index < 0 ? null : installPath.substring(0, index);
    }

    /** 表示来源Collect结果，携带调用方后续判断所需信息。 */
    private static class SourceCollectResult {
        /** 保存items集合，维持调用顺序或去重语义。 */
        private final List<SkillMeta> items;

        /** 保存failedSources集合，维持调用顺序或去重语义。 */
        private final List<String> failedSources;

        /**
         * 创建来源Collect结果实例，并注入运行所需依赖。
         *
         * @param items items 参数。
         * @param failedSources failedSources 参数。
         */
        private SourceCollectResult(List<SkillMeta> items, List<String> failedSources) {
            this.items = items;
            this.failedSources = failedSources;
        }
    }
}
