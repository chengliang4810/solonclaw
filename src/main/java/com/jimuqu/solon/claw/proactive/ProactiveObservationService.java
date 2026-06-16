package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** 主动协作观测服务，负责执行采集器并将观测结果持久化。 */
public class ProactiveObservationService {
    /** 观测摘要最大保留长度。 */
    private static final int SUMMARY_MAX_LENGTH = 240;

    /** 错误摘要最大保留长度。 */
    private static final int ERROR_MAX_LENGTH = 320;

    /** 单个字符串载荷最大保留长度。 */
    private static final int PAYLOAD_TEXT_MAX_LENGTH = 128;

    /** 顶层载荷最多保留的键数量。 */
    private static final int PAYLOAD_MAX_ENTRIES = 8;

    /** 列表载荷最多保留的元素数量。 */
    private static final int PAYLOAD_MAX_ITEMS = 8;

    /** 递归处理载荷时的最大深度。 */
    private static final int PAYLOAD_MAX_DEPTH = 3;

    /** 主动协作仓储。 */
    private final ProactiveRepository repository;

    /** 当前已注册的观测采集器。 */
    private final List<ProactiveObservationCollector> collectors;

    /**
     * 创建主动协作观测服务。
     *
     * @param repository 主动协作仓储。
     * @param collectors 观测采集器列表，按传入顺序执行。
     */
    public ProactiveObservationService(
            ProactiveRepository repository, List<ProactiveObservationCollector> collectors) {
        this.repository = repository;
        this.collectors =
                collectors == null
                        ? Collections.<ProactiveObservationCollector>emptyList()
                        : new ArrayList<ProactiveObservationCollector>(collectors);
    }

    /**
     * 执行全部启用的采集器并保存观测结果。
     *
     * @param context 当前 tick 上下文。
     * @return 返回已保存的观测记录，顺序与采集顺序一致。
     * @throws Exception 仓储写入失败时抛出异常。
     */
    public List<ProactiveObservationRecord> collectAll(ProactiveTickContext context) throws Exception {
        List<ProactiveObservationRecord> saved = new ArrayList<ProactiveObservationRecord>();
        if (context == null) {
            return saved;
        }
        AppConfig config = context.getConfig();
        for (ProactiveObservationCollector collector : collectors) {
            if (collector == null) {
                continue;
            }
            if (!collector.enabled(config)) {
                continue;
            }
            List<ProactiveObservation> observations;
            try {
                observations = collector.collect(context);
            } catch (Exception ex) {
                ProactiveObservationRecord failed = buildFailureRecord(context, collector, ex);
                repository.saveObservation(failed);
                saved.add(failed);
                continue;
            }
            if (observations == null || observations.isEmpty()) {
                continue;
            }
            for (ProactiveObservation observation : observations) {
                ProactiveObservationRecord record = toRecord(context, collector, observation);
                repository.saveObservation(record);
                saved.add(record);
            }
        }
        return saved;
    }

    /**
     * 将采集结果转换为落库记录。
     *
     * @param context 当前 tick 上下文。
     * @param collector 采集器定义。
     * @param observation 原始观测。
     * @return 返回可直接保存的记录对象。
     */
    private ProactiveObservationRecord toRecord(
            ProactiveTickContext context,
            ProactiveObservationCollector collector,
            ProactiveObservation observation) {
        ProactiveObservationRecord record = new ProactiveObservationRecord();
        ProactiveObservation value = observation == null ? new ProactiveObservation() : observation;
        record.setObservationId(defaultObservationId(value.getObservationId()));
        record.setTickId(context.getTickId());
        record.setCollector(resolveCollectorName(collector, value.getCollector()));
        record.setSourceKey(resolveSourceKey(value.getSourceKey(), collector));
        record.setSummary(SecretRedactor.redact(value.getSummary(), SUMMARY_MAX_LENGTH));
        record.setPayload(sanitizePayload(value.getPayload()));
        record.setStatus(defaultStatus(value.getStatus()));
        record.setError(SecretRedactor.redact(value.getError(), ERROR_MAX_LENGTH));
        record.setCreatedAt(context.getNowMillis());
        return record;
    }

    /**
     * 生成采集失败时的失败观测记录。
     *
     * @param context 当前 tick 上下文。
     * @param collector 失败的采集器。
     * @param ex 采集异常。
     * @return 返回失败记录。
     */
    private ProactiveObservationRecord buildFailureRecord(
            ProactiveTickContext context, ProactiveObservationCollector collector, Exception ex) {
        ProactiveObservationRecord record = new ProactiveObservationRecord();
        record.setObservationId(IdSupport.newId());
        record.setTickId(context.getTickId());
        record.setCollector(resolveCollectorName(collector, null));
        record.setSourceKey(resolveCollectorName(collector, null));
        record.setSummary("采集器执行失败");
        record.setPayload(new LinkedHashMap<String, Object>());
        record.setStatus("FAILED");
        record.setError(SecretRedactor.redact(formatException(ex), ERROR_MAX_LENGTH));
        record.setCreatedAt(context.getNowMillis());
        return record;
    }

    /**
     * 递归裁剪并脱敏观测载荷。
     *
     * @param payload 原始载荷。
     * @return 返回适合落库的小尺寸载荷。
     */
    private Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
        if (payload == null || payload.isEmpty()) {
            return sanitized;
        }
        int index = 0;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (index >= PAYLOAD_MAX_ENTRIES) {
                sanitized.put(
                        "_truncatedEntries", Integer.valueOf(payload.size() - PAYLOAD_MAX_ENTRIES));
                break;
            }
            sanitized.put(entry.getKey(), sanitizePayloadValue(entry.getValue(), 0));
            index++;
        }
        return sanitized;
    }

    /**
     * 递归处理载荷值，优先保留可读结构，必要时退化为脱敏后的文本预览。
     *
     * @param value 原始值。
     * @param depth 当前递归深度。
     * @return 返回可安全落库的值。
     */
    private Object sanitizePayloadValue(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence) {
            return SecretRedactor.redact(String.valueOf(value), PAYLOAD_TEXT_MAX_LENGTH);
        }
        if (depth >= PAYLOAD_MAX_DEPTH) {
            return SecretRedactor.redact(safeJson(value), PAYLOAD_TEXT_MAX_LENGTH);
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> nested = new LinkedHashMap<String, Object>();
            Map<?, ?> source = (Map<?, ?>) value;
            int index = 0;
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (index >= PAYLOAD_MAX_ENTRIES) {
                    nested.put(
                            "_truncatedEntries",
                            Integer.valueOf(source.size() - PAYLOAD_MAX_ENTRIES));
                    break;
                }
                nested.put(
                        String.valueOf(entry.getKey()),
                        sanitizePayloadValue(entry.getValue(), depth + 1));
                index++;
            }
            return nested;
        }
        if (value instanceof Iterable<?>) {
            return sanitizeIterable((Iterable<?>) value, depth + 1);
        }
        if (value.getClass().isArray()) {
            return sanitizeArray(value, depth + 1);
        }
        return SecretRedactor.redact(safeJson(value), PAYLOAD_TEXT_MAX_LENGTH);
    }

    /**
     * 裁剪可迭代载荷，避免大型列表直接入库。
     *
     * @param iterable 原始迭代值。
     * @param depth 当前递归深度。
     * @return 返回裁剪后的列表。
     */
    private List<Object> sanitizeIterable(Iterable<?> iterable, int depth) {
        List<Object> items = new ArrayList<Object>();
        int index = 0;
        for (Object item : iterable) {
            if (index >= PAYLOAD_MAX_ITEMS) {
                items.add("[truncated items]");
                break;
            }
            items.add(sanitizePayloadValue(item, depth));
            index++;
        }
        return items;
    }

    /**
     * 裁剪数组载荷，避免大型数组直接入库。
     *
     * @param value 原始数组对象。
     * @param depth 当前递归深度。
     * @return 返回裁剪后的列表。
     */
    private List<Object> sanitizeArray(Object value, int depth) {
        List<Object> items = new ArrayList<Object>();
        int length = Array.getLength(value);
        int limit = Math.min(length, PAYLOAD_MAX_ITEMS);
        for (int i = 0; i < limit; i++) {
            items.add(sanitizePayloadValue(Array.get(value, i), depth));
        }
        if (length > limit) {
            items.add("[truncated items]");
        }
        return items;
    }

    /**
     * 将异常格式化为适合保存的短文本。
     *
     * @param ex 原始异常。
     * @return 返回包含异常类型和消息的字符串。
     */
    private String formatException(Exception ex) {
        if (ex == null) {
            return null;
        }
        String message = ex.getMessage();
        if (StrUtil.isBlank(message)) {
            return ex.getClass().getSimpleName();
        }
        return ex.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 将任意对象序列化为 JSON 文本，失败时回退到字符串形式。
     *
     * @param value 任意对象。
     * @return 返回可脱敏的文本。
     */
    private String safeJson(Object value) {
        try {
            return ONode.serialize(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    /**
     * 为空的 observationId 自动补齐。
     *
     * @param observationId 原始 observationId。
     * @return 返回最终 observationId。
     */
    private String defaultObservationId(String observationId) {
        return StrUtil.isBlank(observationId) ? IdSupport.newId() : observationId;
    }

    /**
     * 统一 collector 字段，优先使用观测自带值，其次使用采集器名称。
     *
     * @param collector 采集器定义。
     * @param observationCollector 观测声明的采集器名称。
     * @return 返回最终 collector 名称。
     */
    private String resolveCollectorName(
            ProactiveObservationCollector collector, String observationCollector) {
        if (StrUtil.isNotBlank(observationCollector)) {
            return SecretRedactor.redact(observationCollector, PAYLOAD_TEXT_MAX_LENGTH);
        }
        String name = collector == null ? null : collector.name();
        if (StrUtil.isBlank(name)) {
            return "unknown";
        }
        return SecretRedactor.redact(name, PAYLOAD_TEXT_MAX_LENGTH);
    }

    /**
     * 统一来源键，缺失时回退到采集器名称，避免失败记录没有来源定位线索。
     *
     * @param sourceKey 原始来源键。
     * @param collector 对应采集器。
     * @return 返回最终来源键。
     */
    private String resolveSourceKey(String sourceKey, ProactiveObservationCollector collector) {
        if (StrUtil.isNotBlank(sourceKey)) {
            return SecretRedactor.redact(sourceKey, PAYLOAD_TEXT_MAX_LENGTH);
        }
        return resolveCollectorName(collector, null);
    }

    /**
     * 为缺失状态补默认值。
     *
     * @param status 原始状态。
     * @return 返回最终状态。
     */
    private String defaultStatus(String status) {
        return StrUtil.isBlank(status) ? "COLLECTED" : status;
    }
}
