package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelInboundMessageRecord;
import com.jimuqu.solon.claw.storage.repository.SqliteChannelInboundMessageRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SQLite 渠道入站消息仓储测试。 */
class SqliteChannelInboundMessageRepositoryTest {
    /** 临时 SQLite 数据库。 */
    private SqliteDatabase database;

    /** 被测入站消息仓储。 */
    private SqliteChannelInboundMessageRepository repository;

    /** 创建隔离数据库。 */
    @BeforeEach
    void setUp() throws Exception {
        Path home = Files.createTempDirectory("channel-inbound-repository-test");
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        database = new SqliteDatabase(config);
        repository = new SqliteChannelInboundMessageRepository(database);
    }

    /** 关闭临时数据库连接。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 相同消息键只能成功插入一次。 */
    @Test
    void shouldDeduplicateByMessageKey() throws Exception {
        ChannelInboundMessageRecord record = record("ingress-1", "message-key-1");
        assertThat(repository.saveIfAbsent(record)).isTrue();
        assertThat(repository.saveIfAbsent(record("ingress-2", "message-key-1"))).isFalse();
        assertThat(repository.findByMessageKey("message-key-1").getIngressId())
                .isEqualTo("ingress-1");
    }

    /** 首次插入即应原子进入 processing 且尝试次数为一，不依赖第二次状态更新。 */
    @Test
    void shouldInsertFirstAttemptDirectlyAsProcessing() throws Exception {
        repository.saveIfAbsent(record("processing-direct", "message-key-direct"));

        ChannelInboundMessageRecord stored = repository.findByMessageKey("message-key-direct");
        assertThat(stored.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_PROCESSING);
        assertThat(stored.getAttempts()).isEqualTo(1);
    }

    /** 重启只收敛已领取的 processing，尚未进入业务主链的 pending 必须保留恢复。 */
    @Test
    void shouldConvergeInterruptedRecordsToFailed() throws Exception {
        ChannelInboundMessageRecord pending = record("pending-1", "message-key-2");
        pending.setStatus(ChannelInboundMessageRecord.STATUS_PENDING);
        pending.setAttempts(0);
        repository.saveIfAbsent(pending);
        repository.saveIfAbsent(record("processing-1", "message-key-3"));
        ChannelInboundMessageRecord currentProcess =
                record("processing-current", "message-key-current");
        currentProcess.setCreatedAt(40L);
        currentProcess.setUpdatedAt(40L);
        repository.saveIfAbsent(currentProcess);

        assertThat(repository.markInterrupted("default", 30L, "restarted")).isEqualTo(1);

        assertThat(repository.findByMessageKey("message-key-2").getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_PENDING);
        assertThat(repository.findByMessageKey("message-key-3").getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_FAILED);
        assertThat(repository.findByMessageKey("message-key-current").getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_PROCESSING);
    }

    /** 批次领取必须在一个事务内让 leader 开始处理并收敛其余原始 receipt。 */
    @Test
    void shouldStartPendingBatchAtomically() throws Exception {
        repository.saveIfAbsent(pendingRecord("receipt-a", "raw-a", 1L));
        repository.saveIfAbsent(pendingRecord("receipt-b", "raw-b", 2L));
        repository.saveIfAbsent(pendingRecord("receipt-c", "raw-c", 3L));

        assertThat(
                        repository.startBatch(
                                "receipt-a",
                                Arrays.asList("receipt-a", "receipt-b", "receipt-c"),
                                "WEIXIN:routed:user",
                                "{\"text\":\"A\\nB\\nC\"}",
                                10L))
                .isTrue();

        ChannelInboundMessageRecord leader = repository.findByMessageKey("raw-a");
        ChannelInboundMessageRecord second = repository.findByMessageKey("raw-b");
        ChannelInboundMessageRecord third = repository.findByMessageKey("raw-c");
        assertThat(leader.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_PROCESSING);
        assertThat(leader.getAttempts()).isEqualTo(1);
        assertThat(leader.getSourceKey()).isEqualTo("WEIXIN:routed:user");
        assertThat(leader.getMessageJson()).contains("A\\nB\\nC");
        assertThat(second.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(second.getLastError()).contains("receipt-a");
        assertThat(third.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(repository.listPending("default", 10L, 0L, 10)).isEmpty();
    }

    /** 任一批次成员缺失或已被消费时必须整体回滚，不能留下半领取状态。 */
    @Test
    void shouldRollbackBatchWhenAnyReceiptIsUnavailable() throws Exception {
        repository.saveIfAbsent(pendingRecord("receipt-x", "raw-x", 1L));
        repository.saveIfAbsent(pendingRecord("receipt-y", "raw-y", 2L));

        assertThat(
                        repository.startBatch(
                                "receipt-x",
                                Arrays.asList("receipt-x", "missing", "receipt-y"),
                                "WEIXIN:routed:user",
                                "{\"text\":\"X\\nY\"}",
                                10L))
                .isFalse();

        assertThat(repository.findByMessageKey("raw-x").getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_PENDING);
        assertThat(repository.findByMessageKey("raw-y").getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_PENDING);
    }

    /** rowid 水位必须包含同毫秒旧 receipt，并排除水位捕获后插入的新 receipt。 */
    @Test
    void shouldFreezePendingRecoveryByInsertWatermark() throws Exception {
        repository.saveIfAbsent(pendingRecord("receipt-z-old", "raw-old", 100L));
        long watermark = repository.capturePendingWatermark("default");
        repository.saveIfAbsent(pendingRecord("receipt-a-new", "raw-new", 100L));

        List<ChannelInboundMessageRecord> records =
                repository.listPending("default", watermark, 0L, 10);

        assertThat(records)
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("receipt-z-old");
    }

    /** 单渠道重连只能读取该平台的 pending receipt，不能提前消费其他未就绪渠道。 */
    @Test
    void shouldFilterPendingRecoveryByConnectedPlatform() throws Exception {
        repository.saveIfAbsent(pendingRecord("receipt-weixin", "raw-weixin", 100L));
        ChannelInboundMessageRecord wecom = pendingRecord("receipt-wecom", "raw-wecom", 100L);
        wecom.setPlatform("WECOM");
        repository.saveIfAbsent(wecom);
        long watermark = repository.capturePendingWatermark("default");

        List<ChannelInboundMessageRecord> records =
                repository.listPending("default", "WECOM", watermark, 0L, 10);

        assertThat(records)
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("receipt-wecom");
    }

    /** 相同创建时间的 pending receipt 必须按插入顺序跨页且不漏不重。 */
    @Test
    void shouldPagePendingReceiptsByInsertSequence() throws Exception {
        List<String> expected = new java.util.ArrayList<String>();
        for (int index = 0; index < 101; index++) {
            String ingressId = String.format("same-time-%03d", Integer.valueOf(index));
            expected.add(ingressId);
            repository.saveIfAbsent(pendingRecord(ingressId, "same-time-key-" + index, 100L));
        }
        long watermark = repository.capturePendingWatermark("default");
        List<String> actual = new java.util.ArrayList<String>();
        long afterSequence = 0L;
        while (true) {
            List<ChannelInboundMessageRecord> page =
                    repository.listPending("default", watermark, afterSequence, 100);
            if (page.isEmpty()) {
                break;
            }
            for (ChannelInboundMessageRecord record : page) {
                actual.add(record.getIngressId());
            }
            afterSequence = page.get(page.size() - 1).getSequence();
        }

        assertThat(actual).containsExactlyElementsOf(expected);
    }

    /** 启动恢复批次只能包含认领瞬间已经处于 processed 的回复。 */
    @Test
    void shouldFreezeStartupDeliveryRecoveryBatch() throws Exception {
        repository.saveIfAbsent(record("processed-old", "processed-key-old"));
        repository.markProcessed("processed-old", "{\"content\":\"old\"}", 100L);
        assertThat(repository.claimStartupDeliveries("default", "startup-token")).isEqualTo(1);
        repository.saveIfAbsent(record("processed-new", "processed-key-new"));
        repository.markProcessed("processed-new", "{\"content\":\"new\"}", 100L);

        List<ChannelInboundMessageRecord> records =
                repository.listClaimedDeliveries("default", "startup-token", -1L, "", 10);

        assertThat(records)
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("processed-old");
    }

    /** 活跃恢复 owner 不得被后续启动认领覆盖，旧进程 owner 回收后才允许重新认领。 */
    @Test
    void shouldProtectAndReclaimDeliveryOwner() throws Exception {
        repository.saveIfAbsent(record("claimed-old", "claimed-key-old"));
        repository.markProcessed("claimed-old", "{\"content\":\"old\"}", 100L);

        assertThat(repository.claimStartupDeliveries("default", "owner-a")).isEqualTo(1);
        assertThat(repository.claimStartupDeliveries("default", "owner-b")).isZero();
        assertThat(repository.listClaimedDeliveries("default", "owner-a", -1L, "", 10))
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("claimed-old");

        assertThat(
                        repository.convergeInterruptedDeliveries(
                                "default", Long.MAX_VALUE, "interrupted"))
                .isEqualTo(1);
        assertThat(repository.claimStartupDeliveries("default", "owner-b")).isEqualTo(1);
        assertThat(repository.listClaimedDeliveries("default", "owner-b", -1L, "", 10))
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("claimed-old");
    }

    /** 已经开始外发但未落终态的旧记录必须 fail-closed，不能在重启后重复投递。 */
    @Test
    void shouldFailClosedInterruptedDeliveringRecord() throws Exception {
        repository.saveIfAbsent(record("delivering-old", "delivering-key-old"));
        repository.markProcessedForDelivery(
                "delivering-old", "{\"content\":\"reply\"}", "owner-a", 100L);
        assertThat(repository.markClaimedDeliveryStarted("delivering-old", "owner-a", 101L))
                .isTrue();

        assertThat(
                        repository.convergeInterruptedDeliveries(
                                "default", 200L, "delivery result unknown"))
                .isEqualTo(1);

        ChannelInboundMessageRecord stored = repository.findByMessageKey("delivering-key-old");
        assertThat(stored.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_FAILED);
        assertThat(stored.getLastError()).isEqualTo("delivery result unknown");
        assertThat(repository.claimStartupDeliveries("default", "owner-b")).isZero();
    }

    /** 平台重连只能认领该平台已有明确投递错误且尚未被其他批次认领的回复。 */
    @Test
    void shouldClaimOnlyFailedDeliveriesForConnectedPlatform() throws Exception {
        ChannelInboundMessageRecord weixin = record("failed-weixin", "failed-key-weixin");
        repository.saveIfAbsent(weixin);
        repository.markProcessed("failed-weixin", "{\"content\":\"wx\"}", 100L);
        repository.markDeliveryFailed("failed-weixin", "offline", 101L);
        ChannelInboundMessageRecord wecom = record("failed-wecom", "failed-key-wecom");
        wecom.setPlatform("WECOM");
        repository.saveIfAbsent(wecom);
        repository.markProcessed("failed-wecom", "{\"content\":\"wc\"}", 100L);
        repository.markDeliveryFailed("failed-wecom", "offline", 101L);
        repository.saveIfAbsent(record("fresh-weixin", "fresh-key-weixin"));
        repository.markProcessed("fresh-weixin", "{\"content\":\"fresh\"}", 102L);

        assertThat(repository.claimFailedDeliveries("default", "WEIXIN", "reconnect-token"))
                .isEqualTo(1);

        assertThat(repository.listClaimedDeliveries("default", "reconnect-token", -1L, "", 10))
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("failed-weixin");
    }

    /** 迟到的失败收敛不得覆盖另一线程已经确认的 completed 终态。 */
    @Test
    void shouldKeepCompletedStatusWhenLateFailureArrives() throws Exception {
        repository.saveIfAbsent(record("completed-race", "completed-race-key"));
        repository.markProcessed("completed-race", "{\"content\":\"done\"}", 100L);
        repository.markCompleted("completed-race", 101L);

        repository.markFailed("completed-race", "late failure", 102L);

        assertThat(repository.findByMessageKey("completed-race-key").getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
    }

    /** 已处理记录应按完成时间顺序恢复列出。 */
    @Test
    void shouldListProcessedRecordsInOrder() throws Exception {
        repository.saveIfAbsent(record("ingress-1", "message-key-4"));
        repository.markProcessed("ingress-1", "{\"content\":\"first\"}", 100L);

        repository.saveIfAbsent(record("ingress-2", "message-key-5"));
        repository.markProcessed("ingress-2", "{\"content\":\"second\"}", 200L);

        List<ChannelInboundMessageRecord> records = repository.listProcessed("default", 10);
        assertThat(records)
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("ingress-1", "ingress-2");
    }

    /** 稳定游标必须在相同处理时间的多条记录之间继续翻页且不重复。 */
    @Test
    void shouldPageProcessedRecordsWithStableTieBreaker() throws Exception {
        for (String suffix : java.util.Arrays.asList("a", "b", "c")) {
            String ingressId = "ingress-" + suffix;
            repository.saveIfAbsent(record(ingressId, "message-key-page-" + suffix));
            repository.markProcessed(ingressId, "{\"content\":\"page\"}", 100L);
        }

        List<ChannelInboundMessageRecord> first = repository.listProcessed("default", -1L, "", 2);
        ChannelInboundMessageRecord cursor = first.get(first.size() - 1);
        List<ChannelInboundMessageRecord> second =
                repository.listProcessed(
                        "default", cursor.getProcessedAt(), cursor.getIngressId(), 2);

        assertThat(first)
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("ingress-a", "ingress-b");
        assertThat(second)
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly("ingress-c");
    }

    /** 保留期清理只能删除足够旧的 completed/failed，所有非终态和近期终态必须保留。 */
    @Test
    void shouldPruneOnlyExpiredTerminalRecords() throws Exception {
        repository.saveIfAbsent(pendingRecord("pending-old", "pending-old-key", 1L));
        repository.saveIfAbsent(record("processing-old", "processing-old-key"));

        repository.saveIfAbsent(record("processed-old", "processed-old-key"));
        repository.markProcessed("processed-old", "{\"content\":\"reply\"}", 2L);

        repository.saveIfAbsent(record("delivering-old", "delivering-old-key"));
        repository.markProcessedForDelivery(
                "delivering-old", "{\"content\":\"reply\"}", "owner", 2L);
        repository.markClaimedDeliveryStarted("delivering-old", "owner", 3L);

        repository.saveIfAbsent(record("completed-old", "completed-old-key"));
        repository.markProcessed("completed-old", "{\"content\":\"reply\"}", 2L);
        repository.markCompleted("completed-old", 3L);

        repository.saveIfAbsent(record("failed-old", "failed-old-key"));
        repository.markFailed("failed-old", "failed", 3L);

        repository.saveIfAbsent(record("completed-recent", "completed-recent-key"));
        repository.markProcessed("completed-recent", "{\"content\":\"reply\"}", 90L);
        repository.markCompleted("completed-recent", 100L);

        assertThat(repository.pruneTerminal("default", 50L)).isEqualTo(2);

        assertThat(repository.findByMessageKey("completed-old-key")).isNull();
        assertThat(repository.findByMessageKey("failed-old-key")).isNull();
        assertThat(repository.findByMessageKey("pending-old-key")).isNotNull();
        assertThat(repository.findByMessageKey("processing-old-key")).isNotNull();
        assertThat(repository.findByMessageKey("processed-old-key")).isNotNull();
        assertThat(repository.findByMessageKey("delivering-old-key")).isNotNull();
        assertThat(repository.findByMessageKey("completed-recent-key")).isNotNull();
    }

    /**
     * 创建满足 SQLite 约束的入站消息记录。
     *
     * @param ingressId 入站标识。
     * @param messageKey 幂等键。
     * @return 可直接落库的入站记录。
     */
    private ChannelInboundMessageRecord record(String ingressId, String messageKey) {
        ChannelInboundMessageRecord record = new ChannelInboundMessageRecord();
        record.setIngressId(ingressId);
        record.setMessageKey(messageKey);
        record.setProfile("default");
        record.setPlatform("WEIXIN");
        record.setSourceKey("WEIXIN:chat:user");
        record.setMessageJson("{\"text\":\"hello\"}");
        record.setStatus(ChannelInboundMessageRecord.STATUS_PROCESSING);
        record.setAttempts(1);
        record.setCreatedAt(1L);
        record.setUpdatedAt(1L);
        return record;
    }

    /** 创建一条尚未进入业务主链的原始入站 receipt。 */
    private ChannelInboundMessageRecord pendingRecord(
            String ingressId, String messageKey, long createdAt) {
        ChannelInboundMessageRecord record = record(ingressId, messageKey);
        record.setStatus(ChannelInboundMessageRecord.STATUS_PENDING);
        record.setAttempts(0);
        record.setCreatedAt(createdAt);
        record.setUpdatedAt(createdAt);
        return record;
    }
}
