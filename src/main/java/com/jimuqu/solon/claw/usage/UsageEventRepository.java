package com.jimuqu.solon.claw.usage;

import java.util.List;

/** Repository for durable usage events. */
public interface UsageEventRepository {
    boolean insertIfAbsent(UsageEventRecord record) throws Exception;

    UsageEventRecord findByEventId(String eventId) throws Exception;

    List<UsageEventRecord> listRecent(int limit) throws Exception;

    List<UsageEventRecord> listBetween(long fromInclusive, long toInclusive, int limit)
            throws Exception;
}
