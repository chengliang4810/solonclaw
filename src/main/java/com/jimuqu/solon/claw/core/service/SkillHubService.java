package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.ScanResult;
import com.jimuqu.solon.claw.skillhub.model.SkillBrowseResult;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import java.util.List;

/** Skills Hub 服务接口。 */
public interface SkillHubService {
    /**
     * 执行browse相关逻辑。
     *
     * @param sourceFilter 来源过滤器参数。
     * @param page page 参数。
     * @param pageSize pageSize 参数。
     * @return 返回browse结果。
     */
    SkillBrowseResult browse(String sourceFilter, int page, int pageSize) throws Exception;

    /**
     * 执行搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param sourceFilter 来源过滤器参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    SkillBrowseResult search(String query, String sourceFilter, int limit) throws Exception;

    /**
     * 执行inspect相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回inspect结果。
     */
    SkillMeta inspect(String identifier) throws Exception;

    /**
     * 执行install相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @param category 分类参数。
     * @param force force 参数。
     * @return 返回install结果。
     */
    HubInstallRecord install(String identifier, String category, boolean force) throws Exception;

    /**
     * 列出Installed。
     *
     * @return 返回Installed列表。
     */
    List<HubInstallRecord> listInstalled() throws Exception;

    /**
     * 执行check相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回check结果。
     */
    List<HubInstallRecord> check(String name) throws Exception;

    /**
     * 执行更新相关逻辑。
     *
     * @param name 名称参数。
     * @param force force 参数。
     * @return 返回更新结果。
     */
    List<HubInstallRecord> update(String name, boolean force) throws Exception;

    /**
     * 执行审计相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回审计结果。
     */
    List<ScanResult> audit(String name) throws Exception;

    /**
     * 执行uninstall相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回uninstall结果。
     */
    String uninstall(String name) throws Exception;

    /**
     * 列出Taps。
     *
     * @return 返回Taps列表。
     */
    List<com.jimuqu.solon.claw.skillhub.model.TapRecord> listTaps() throws Exception;

    /**
     * 追加来源库。
     *
     * @param repo repo 参数。
     * @param path 文件或目录路径。
     * @return 返回add Tap结果。
     */
    String addTap(String repo, String path) throws Exception;

    /**
     * 移除Tap。
     *
     * @param repo repo 参数。
     * @return 返回Tap结果。
     */
    String removeTap(String repo) throws Exception;
}
