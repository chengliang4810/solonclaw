package com.jimuqu.solon.claw.proactive;

import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import org.noear.snack4.ONode;

/** 统一读写主动提醒轻量状态，供调度器、命令和 Dashboard 共用。 */
public class ProactiveReminderStateStore {
    /** 主动提醒运行状态在全局设置表中的键。 */
    public static final String STATE_KEY = "proactive.reminder.state";

    /** 全局设置仓储。 */
    private final GlobalSettingRepository globalSettingRepository;

    /**
     * 创建主动提醒状态仓储。
     *
     * @param globalSettingRepository 全局设置仓储。
     */
    public ProactiveReminderStateStore(GlobalSettingRepository globalSettingRepository) {
        this.globalSettingRepository = globalSettingRepository;
    }

    /** 读取状态；缺失、损坏或仓储不可用时返回默认值。 */
    public ProactiveReminderState load() {
        if (globalSettingRepository == null) {
            return new ProactiveReminderState();
        }
        try {
            ProactiveReminderState state =
                    ONode.deserialize(
                            globalSettingRepository.get(STATE_KEY), ProactiveReminderState.class);
            return state == null ? new ProactiveReminderState() : state;
        } catch (Exception e) {
            return new ProactiveReminderState();
        }
    }

    /**
     * 保存状态；测试或降级环境没有仓储时保持无操作。
     *
     * @param state 要保存的主动提醒状态。
     * @throws Exception 持久化失败时抛出异常。
     */
    public void save(ProactiveReminderState state) throws Exception {
        if (globalSettingRepository != null && state != null) {
            globalSettingRepository.set(STATE_KEY, ONode.serialize(state));
        }
    }
}
