package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import java.util.List;

/** 渠道轻量状态仓储。 */
public interface ChannelStateRepository {
    /** 读取单条状态值。 */
    String get(PlatformType platform, String scopeKey, String stateKey) throws Exception;

    /** 写入或覆盖单条状态值。 */
    void put(PlatformType platform, String scopeKey, String stateKey, String stateValue)
            throws Exception;

    /** 删除单条状态值。 */
    void delete(PlatformType platform, String scopeKey, String stateKey) throws Exception;

    /** 列出某个 scope 下的全部状态项。 */
    List<StateItem> list(PlatformType platform, String scopeKey) throws Exception;

    /** 状态项。 */
    class StateItem {
        /** 记录状态Item中的状态键。 */
        private final String stateKey;

        /** 记录状态Item中的状态值。 */
        private final String stateValue;

        /** 记录状态Item中的更新时间。 */
        private final long updatedAt;

        /**
         * 创建状态Item实例，并注入运行所需依赖。
         *
         * @param stateKey 状态键标识或键值。
         * @param stateValue 状态值参数。
         * @param updatedAt updatedAt 参数。
         */
        public StateItem(String stateKey, String stateValue, long updatedAt) {
            this.stateKey = stateKey;
            this.stateValue = stateValue;
            this.updatedAt = updatedAt;
        }

        /**
         * 读取状态键。
         *
         * @return 返回读取到的状态键。
         */
        public String getStateKey() {
            return stateKey;
        }

        /**
         * 读取状态Value。
         *
         * @return 返回读取到的状态Value。
         */
        public String getStateValue() {
            return stateValue;
        }

        /**
         * 读取更新时间。
         *
         * @return 返回读取到的更新时间。
         */
        public long getUpdatedAt() {
            return updatedAt;
        }
    }
}
