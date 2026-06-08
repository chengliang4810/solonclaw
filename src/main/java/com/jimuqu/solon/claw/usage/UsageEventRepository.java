package com.jimuqu.solon.claw.usage;

import java.util.List;

/** 定义用量事件的抽象契约，供不同运行时实现保持一致行为。 */
public interface UsageEventRepository {
    /**
     * 写入IfAbsent。
     *
     * @param record 记录参数。
     * @return 返回insert If Absent结果。
     */
    boolean insertIfAbsent(UsageEventRecord record) throws Exception;

    /**
     * 根据事件标识查找对应数据。
     *
     * @param eventId 事件标识。
     * @return 返回按事件标识查找得到的结果。
     */
    UsageEventRecord findByEventId(String eventId) throws Exception;

    /**
     * 列出Recent。
     *
     * @param limit 最大返回数量。
     * @return 返回Recent列表。
     */
    List<UsageEventRecord> listRecent(int limit) throws Exception;

    /**
     * 列出Between。
     *
     * @param fromInclusive fromInclusive 参数。
     * @param toInclusive toInclusive 参数。
     * @param limit 最大返回数量。
     * @return 返回Between列表。
     */
    List<UsageEventRecord> listBetween(long fromInclusive, long toInclusive, int limit)
            throws Exception;

    /**
     * 列出Between。
     *
     * @param fromInclusive fromInclusive 参数。
     * @param toInclusive toInclusive 参数。
     * @return 返回Between列表。
     */
    default List<UsageEventRecord> listBetween(long fromInclusive, long toInclusive)
            throws Exception {
        return listBetween(fromInclusive, toInclusive, Integer.MAX_VALUE);
    }
}
