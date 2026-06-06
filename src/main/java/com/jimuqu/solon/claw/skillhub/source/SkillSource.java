package com.jimuqu.solon.claw.skillhub.source;

import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import java.util.List;

/** Skills Hub 来源抽象。 */
public interface SkillSource {
    /**
     * 执行搜索相关逻辑。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回搜索结果。
     */
    List<SkillMeta> search(String query, int limit) throws Exception;

    /**
     * 执行fetch相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回fetch结果。
     */
    SkillBundle fetch(String identifier) throws Exception;

    /**
     * 执行inspect相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回inspect结果。
     */
    SkillMeta inspect(String identifier) throws Exception;

    /**
     * 执行来源标识相关逻辑。
     *
     * @return 返回来源标识。
     */
    String sourceId();

    /**
     * 执行trust级别For相关逻辑。
     *
     * @param identifier identifier标识或键值。
     * @return 返回trust级别For结果。
     */
    String trustLevelFor(String identifier);
}
