package com.jimuqu.solon.claw.context;

import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import org.noear.snack4.ONode;

/** 在当前 Profile 的全局设置表中持久化记忆归档诊断状态。 */
public class MemoryArchiveStateStore {
    /** 记忆归档状态键。 */
    public static final String STATE_KEY = "memory.archive.state";

    /** 全局设置仓储。 */
    private final GlobalSettingRepository globalSettingRepository;

    /**
     * 创建记忆归档状态仓储。
     *
     * @param globalSettingRepository 全局设置仓储。
     */
    public MemoryArchiveStateStore(GlobalSettingRepository globalSettingRepository) {
        this.globalSettingRepository = globalSettingRepository;
    }

    /** 读取状态；状态缺失或损坏时返回默认值，不影响文件系统恢复。 */
    public MemoryArchiveState load() {
        if (globalSettingRepository == null) {
            return new MemoryArchiveState();
        }
        try {
            MemoryArchiveState state =
                    ONode.deserialize(
                            globalSettingRepository.get(STATE_KEY), MemoryArchiveState.class);
            return state == null ? new MemoryArchiveState() : state;
        } catch (Exception e) {
            return new MemoryArchiveState();
        }
    }

    /**
     * 保存低敏诊断状态。
     *
     * @param state 当前状态。
     * @throws Exception 状态持久化失败时抛出异常。
     */
    public void save(MemoryArchiveState state) throws Exception {
        if (globalSettingRepository != null && state != null) {
            globalSettingRepository.set(STATE_KEY, ONode.serialize(state));
        }
    }
}
