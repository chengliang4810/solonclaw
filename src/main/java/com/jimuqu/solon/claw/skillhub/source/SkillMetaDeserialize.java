package com.jimuqu.solon.claw.skillhub.source;

import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.noear.snack4.ONode;

/**
 * SkillMeta 列表反序列化共享工具。
 *
 * <p>各 SkillSource 此前各自复制了一份 SkillMeta[] -> List 的反序列化逻辑，这里集中提供统一实现。
 */
final class SkillMetaDeserialize {

    private SkillMetaDeserialize() {}

    /**
     * 执行deserialize列表相关逻辑。
     *
     * @param json JSON参数。
     * @return 返回deserialize List结果。
     */
    static List<SkillMeta> deserializeList(String json) {
        SkillMeta[] array = ONode.deserialize(json, SkillMeta[].class);
        List<SkillMeta> results = new ArrayList<SkillMeta>();
        if (array != null) {
            Collections.addAll(results, array);
        }
        return results;
    }
}
