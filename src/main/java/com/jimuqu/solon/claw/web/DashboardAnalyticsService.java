package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.usage.UsageEventRecord;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Dashboard 分析服务。 */
public class DashboardAnalyticsService {
    private final SessionRepository sessionRepository;
    private final UsageEventRepository usageEventRepository;

    public DashboardAnalyticsService(SessionRepository sessionRepository) {
        this(sessionRepository, null);
    }

    public DashboardAnalyticsService(
            SessionRepository sessionRepository, UsageEventRepository usageEventRepository) {
        this.sessionRepository = sessionRepository;
        this.usageEventRepository = usageEventRepository;
    }

    public Map<String, Object> getUsage(int days) throws Exception {
        int safeDays = days <= 0 ? 30 : Math.min(days, 365);
        List<Map<String, Object>> daily = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> byModel = new ArrayList<Map<String, Object>>();
        Map<String, Object> totals = createTotals(0);
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate end =
                Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zoneId).toLocalDate();
        LocalDate start = end.minusDays(safeDays - 1L);

        if (usageEventRepository != null) {
            Map<String, Object> eventUsage = usageEventUsage(start, end, zoneId, safeDays);
            if (eventUsage != null) {
                return eventUsage;
            }
        }

        int totalSessions = sessionRepository.countAll();
        if (totalSessions <= 0) {
            return buildResponse(daily, byModel, totals);
        }

        List<SessionRecord> records = sessionRepository.listRecent(totalSessions);

        Map<String, Integer> dailySessions = new LinkedHashMap<String, Integer>();
        Map<String, Integer> modelSessions = new LinkedHashMap<String, Integer>();
        Map<String, Long> dailyInputTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyOutputTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyReasoningTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyCacheReadTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyCacheWriteTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelInputTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelOutputTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelReasoningTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelCacheReadTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelCacheWriteTokens = new LinkedHashMap<String, Long>();
        int totalInRange = 0;
        long totalInput = 0L;
        long totalOutput = 0L;
        long totalReasoning = 0L;
        long totalCacheRead = 0L;
        long totalCacheWrite = 0L;

        for (SessionRecord record : records) {
            long usageAt =
                    record.getLastUsageAt() > 0
                            ? record.getLastUsageAt()
                            : (record.getUpdatedAt() > 0
                                    ? record.getUpdatedAt()
                                    : record.getCreatedAt());
            LocalDate recordDay = Instant.ofEpochMilli(usageAt).atZone(zoneId).toLocalDate();
            if (recordDay.isBefore(start) || recordDay.isAfter(end)) {
                continue;
            }

            String dayKey = recordDay.toString();
            dailySessions.put(
                    dayKey, dailySessions.containsKey(dayKey) ? dailySessions.get(dayKey) + 1 : 1);
            addLong(dailyInputTokens, dayKey, record.getCumulativeInputTokens());
            addLong(dailyOutputTokens, dayKey, record.getCumulativeOutputTokens());
            addLong(dailyReasoningTokens, dayKey, record.getCumulativeReasoningTokens());
            addLong(dailyCacheReadTokens, dayKey, record.getCumulativeCacheReadTokens());
            addLong(dailyCacheWriteTokens, dayKey, record.getCumulativeCacheWriteTokens());

            String modelKey =
                    normalizeModelLabel(
                            StrUtil.blankToDefault(
                                    record.getLastResolvedModel(), record.getModelOverride()));
            modelSessions.put(
                    modelKey,
                    modelSessions.containsKey(modelKey) ? modelSessions.get(modelKey) + 1 : 1);
            addLong(modelInputTokens, modelKey, record.getCumulativeInputTokens());
            addLong(modelOutputTokens, modelKey, record.getCumulativeOutputTokens());
            addLong(modelReasoningTokens, modelKey, record.getCumulativeReasoningTokens());
            addLong(modelCacheReadTokens, modelKey, record.getCumulativeCacheReadTokens());
            addLong(modelCacheWriteTokens, modelKey, record.getCumulativeCacheWriteTokens());
            totalInRange++;
            totalInput += record.getCumulativeInputTokens();
            totalOutput += record.getCumulativeOutputTokens();
            totalReasoning += record.getCumulativeReasoningTokens();
            totalCacheRead += record.getCumulativeCacheReadTokens();
            totalCacheWrite += record.getCumulativeCacheWriteTokens();
        }

        if (totalInRange <= 0) {
            return buildResponse(daily, byModel, totals);
        }

        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            String dayKey = cursor.toString();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("day", dayKey);
            item.put(
                    "input_tokens",
                    dailyInputTokens.containsKey(dayKey) ? dailyInputTokens.get(dayKey) : 0L);
            item.put(
                    "output_tokens",
                    dailyOutputTokens.containsKey(dayKey) ? dailyOutputTokens.get(dayKey) : 0L);
            item.put(
                    "cache_read_tokens",
                    dailyCacheReadTokens.containsKey(dayKey)
                            ? dailyCacheReadTokens.get(dayKey)
                            : 0L);
            item.put(
                    "cache_write_tokens",
                    dailyCacheWriteTokens.containsKey(dayKey)
                            ? dailyCacheWriteTokens.get(dayKey)
                            : 0L);
            item.put(
                    "reasoning_tokens",
                    dailyReasoningTokens.containsKey(dayKey)
                            ? dailyReasoningTokens.get(dayKey)
                            : 0L);
            item.put("sessions", dailySessions.containsKey(dayKey) ? dailySessions.get(dayKey) : 0);
            putCostFields(item, 0L, "", false, 0L, 0L, 0L, 0L, 0L, false);
            daily.add(item);
            cursor = cursor.plusDays(1);
        }

        for (Map.Entry<String, Integer> entry : modelSessions.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("model", normalizeModelLabel(entry.getKey()));
            item.put(
                    "input_tokens",
                    modelInputTokens.containsKey(entry.getKey())
                            ? modelInputTokens.get(entry.getKey())
                            : 0L);
            item.put(
                    "output_tokens",
                    modelOutputTokens.containsKey(entry.getKey())
                            ? modelOutputTokens.get(entry.getKey())
                            : 0L);
            item.put(
                    "cache_read_tokens",
                    modelCacheReadTokens.containsKey(entry.getKey())
                            ? modelCacheReadTokens.get(entry.getKey())
                            : 0L);
            item.put(
                    "cache_write_tokens",
                    modelCacheWriteTokens.containsKey(entry.getKey())
                            ? modelCacheWriteTokens.get(entry.getKey())
                            : 0L);
            item.put(
                    "reasoning_tokens",
                    modelReasoningTokens.containsKey(entry.getKey())
                            ? modelReasoningTokens.get(entry.getKey())
                            : 0L);
            item.put("sessions", entry.getValue());
            putCostFields(item, 0L, "", false, 0L, 0L, 0L, 0L, 0L, false);
            byModel.add(item);
        }

        totals =
                createTotals(
                        totalInRange,
                        totalInput,
                        totalOutput,
                        totalCacheRead,
                        totalCacheWrite,
                        totalReasoning);
        return buildResponse(daily, byModel, totals);
    }

    private Map<String, Object> usageEventUsage(
            LocalDate start, LocalDate end, ZoneId zoneId, int safeDays) throws Exception {
        long from = start.atStartOfDay(zoneId).toInstant().toEpochMilli();
        long to = end.plusDays(1L).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L;
        List<UsageEventRecord> events = usageEventRepository.listBetween(from, to);
        if (events.isEmpty()) {
            return null;
        }

        Map<String, Set<String>> dailySessions = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> modelSessions = new LinkedHashMap<String, Set<String>>();
        Map<String, Long> dailyInputTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyOutputTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyReasoningTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyCacheReadTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyCacheWriteTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyCostMicros = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyUnpricedInput = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyUnpricedOutput = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyUnpricedCacheRead = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyUnpricedCacheWrite = new LinkedHashMap<String, Long>();
        Map<String, Long> dailyUnpricedReasoning = new LinkedHashMap<String, Long>();
        Map<String, Boolean> dailyPriced = new LinkedHashMap<String, Boolean>();
        Map<String, Boolean> dailyApproximate = new LinkedHashMap<String, Boolean>();
        Map<String, String> dailyCurrency = new LinkedHashMap<String, String>();

        Map<String, Long> modelInputTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelOutputTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelReasoningTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelCacheReadTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelCacheWriteTokens = new LinkedHashMap<String, Long>();
        Map<String, Long> modelCostMicros = new LinkedHashMap<String, Long>();
        Map<String, Long> modelUnpricedInput = new LinkedHashMap<String, Long>();
        Map<String, Long> modelUnpricedOutput = new LinkedHashMap<String, Long>();
        Map<String, Long> modelUnpricedCacheRead = new LinkedHashMap<String, Long>();
        Map<String, Long> modelUnpricedCacheWrite = new LinkedHashMap<String, Long>();
        Map<String, Long> modelUnpricedReasoning = new LinkedHashMap<String, Long>();
        Map<String, Boolean> modelPriced = new LinkedHashMap<String, Boolean>();
        Map<String, Boolean> modelApproximate = new LinkedHashMap<String, Boolean>();
        Map<String, String> modelCurrency = new LinkedHashMap<String, String>();

        Set<String> totalSessionIds = new LinkedHashSet<String>();
        long totalInput = 0L;
        long totalOutput = 0L;
        long totalReasoning = 0L;
        long totalCacheRead = 0L;
        long totalCacheWrite = 0L;
        long totalCostMicros = 0L;
        long totalUnpricedInput = 0L;
        long totalUnpricedOutput = 0L;
        long totalUnpricedCacheRead = 0L;
        long totalUnpricedCacheWrite = 0L;
        long totalUnpricedReasoning = 0L;
        boolean pricingAvailable = false;
        boolean approximate = false;
        String currency = "";

        for (UsageEventRecord event : events) {
            if (event == null || event.getCreatedAt() < from || event.getCreatedAt() > to) {
                continue;
            }
            String sessionId =
                    StrUtil.blankToDefault(
                            event.getSessionId(),
                            StrUtil.blankToDefault(event.getSourceKey(), event.getEventId()));
            if (StrUtil.isNotBlank(sessionId)) {
                totalSessionIds.add(sessionId);
            }
            LocalDate recordDay =
                    Instant.ofEpochMilli(event.getCreatedAt()).atZone(zoneId).toLocalDate();
            String dayKey = recordDay.toString();
            String modelKey = normalizeModelLabel(event.getModel());
            addSession(dailySessions, dayKey, sessionId);
            addSession(modelSessions, modelKey, sessionId);

            addUsageEvent(
                    dailyInputTokens,
                    dailyOutputTokens,
                    dailyReasoningTokens,
                    dailyCacheReadTokens,
                    dailyCacheWriteTokens,
                    dailyCostMicros,
                    dailyUnpricedInput,
                    dailyUnpricedOutput,
                    dailyUnpricedCacheRead,
                    dailyUnpricedCacheWrite,
                    dailyUnpricedReasoning,
                    dailyPriced,
                    dailyApproximate,
                    dailyCurrency,
                    dayKey,
                    event);
            addUsageEvent(
                    modelInputTokens,
                    modelOutputTokens,
                    modelReasoningTokens,
                    modelCacheReadTokens,
                    modelCacheWriteTokens,
                    modelCostMicros,
                    modelUnpricedInput,
                    modelUnpricedOutput,
                    modelUnpricedCacheRead,
                    modelUnpricedCacheWrite,
                    modelUnpricedReasoning,
                    modelPriced,
                    modelApproximate,
                    modelCurrency,
                    modelKey,
                    event);

            totalInput += Math.max(0L, event.getInputTokens());
            totalOutput += Math.max(0L, event.getOutputTokens());
            totalReasoning += Math.max(0L, event.getReasoningTokens());
            totalCacheRead += Math.max(0L, event.getCacheReadTokens());
            totalCacheWrite += Math.max(0L, event.getCacheWriteTokens());
            totalCostMicros += Math.max(0L, event.getCostMicros());
            totalUnpricedInput += Math.max(0L, event.getUnpricedInputTokens());
            totalUnpricedOutput += Math.max(0L, event.getUnpricedOutputTokens());
            totalUnpricedCacheRead += Math.max(0L, event.getUnpricedCacheReadTokens());
            totalUnpricedCacheWrite += Math.max(0L, event.getUnpricedCacheWriteTokens());
            totalUnpricedReasoning += Math.max(0L, event.getUnpricedReasoningTokens());
            pricingAvailable = pricingAvailable || event.isPricingAvailable();
            approximate = approximate || event.isBackfillApproximate();
            currency = mergeCurrency(currency, event.getCurrency());
        }

        List<Map<String, Object>> daily = new ArrayList<Map<String, Object>>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            String dayKey = cursor.toString();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("day", dayKey);
            item.put("input_tokens", longValue(dailyInputTokens, dayKey));
            item.put("output_tokens", longValue(dailyOutputTokens, dayKey));
            item.put("cache_read_tokens", longValue(dailyCacheReadTokens, dayKey));
            item.put("cache_write_tokens", longValue(dailyCacheWriteTokens, dayKey));
            item.put("reasoning_tokens", longValue(dailyReasoningTokens, dayKey));
            item.put("sessions", Integer.valueOf(sessionCount(dailySessions, dayKey)));
            putCostFields(
                    item,
                    longValue(dailyCostMicros, dayKey),
                    stringValue(dailyCurrency, dayKey),
                    booleanValue(dailyPriced, dayKey),
                    longValue(dailyUnpricedInput, dayKey),
                    longValue(dailyUnpricedOutput, dayKey),
                    longValue(dailyUnpricedCacheRead, dayKey),
                    longValue(dailyUnpricedCacheWrite, dayKey),
                    longValue(dailyUnpricedReasoning, dayKey),
                    booleanValue(dailyApproximate, dayKey));
            daily.add(item);
            cursor = cursor.plusDays(1);
        }

        List<Map<String, Object>> byModel = new ArrayList<Map<String, Object>>();
        for (String modelKey : modelSessions.keySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("model", normalizeModelLabel(modelKey));
            item.put("input_tokens", longValue(modelInputTokens, modelKey));
            item.put("output_tokens", longValue(modelOutputTokens, modelKey));
            item.put("cache_read_tokens", longValue(modelCacheReadTokens, modelKey));
            item.put("cache_write_tokens", longValue(modelCacheWriteTokens, modelKey));
            item.put("reasoning_tokens", longValue(modelReasoningTokens, modelKey));
            item.put("sessions", Integer.valueOf(sessionCount(modelSessions, modelKey)));
            putCostFields(
                    item,
                    longValue(modelCostMicros, modelKey),
                    stringValue(modelCurrency, modelKey),
                    booleanValue(modelPriced, modelKey),
                    longValue(modelUnpricedInput, modelKey),
                    longValue(modelUnpricedOutput, modelKey),
                    longValue(modelUnpricedCacheRead, modelKey),
                    longValue(modelUnpricedCacheWrite, modelKey),
                    longValue(modelUnpricedReasoning, modelKey),
                    booleanValue(modelApproximate, modelKey));
            byModel.add(item);
        }

        Map<String, Object> totals =
                createTotals(
                        totalSessionIds.size(),
                        totalInput,
                        totalOutput,
                        totalCacheRead,
                        totalCacheWrite,
                        totalReasoning,
                        totalCostMicros,
                        currency,
                        pricingAvailable,
                        totalUnpricedInput,
                        totalUnpricedOutput,
                        totalUnpricedCacheRead,
                        totalUnpricedCacheWrite,
                        totalUnpricedReasoning,
                        approximate);
        return buildResponse(daily, byModel, totals);
    }

    private Map<String, Object> buildResponse(
            List<Map<String, Object>> daily,
            List<Map<String, Object>> byModel,
            Map<String, Object> totals) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("daily", daily);
        result.put("by_model", byModel);
        result.put("totals", totals);
        return result;
    }

    private Map<String, Object> createTotals(int totalSessions) {
        return createTotals(totalSessions, 0L, 0L, 0L, 0L, 0L);
    }

    private Map<String, Object> createTotals(
            int totalSessions,
            long totalInput,
            long totalOutput,
            long totalCacheRead,
            long totalCacheWrite,
            long totalReasoning) {
        return createTotals(
                totalSessions,
                totalInput,
                totalOutput,
                totalCacheRead,
                totalCacheWrite,
                totalReasoning,
                0L,
                "",
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                false);
    }

    private Map<String, Object> createTotals(
            int totalSessions,
            long totalInput,
            long totalOutput,
            long totalCacheRead,
            long totalCacheWrite,
            long totalReasoning,
            long totalCostMicros,
            String currency,
            boolean pricingAvailable,
            long unpricedInput,
            long unpricedOutput,
            long unpricedCacheRead,
            long unpricedCacheWrite,
            long unpricedReasoning,
            boolean backfillApproximate) {
        Map<String, Object> totals = new LinkedHashMap<String, Object>();
        totals.put("total_input", totalInput);
        totals.put("total_output", totalOutput);
        totals.put("total_cache_read", totalCacheRead);
        totals.put("total_cache_write", totalCacheWrite);
        totals.put("total_reasoning", totalReasoning);
        totals.put("total_sessions", totalSessions);
        putCostFields(
                totals,
                totalCostMicros,
                currency,
                pricingAvailable,
                unpricedInput,
                unpricedOutput,
                unpricedCacheRead,
                unpricedCacheWrite,
                unpricedReasoning,
                backfillApproximate);
        return totals;
    }

    private String normalizeModelLabel(String model) {
        String value = StrUtil.blankToDefault(model, "default").trim();
        return value.toLowerCase(Locale.ROOT);
    }

    private void addLong(Map<String, Long> target, String key, long value) {
        target.put(key, target.containsKey(key) ? target.get(key) + value : value);
    }

    private void addUsageEvent(
            Map<String, Long> inputTokens,
            Map<String, Long> outputTokens,
            Map<String, Long> reasoningTokens,
            Map<String, Long> cacheReadTokens,
            Map<String, Long> cacheWriteTokens,
            Map<String, Long> costMicros,
            Map<String, Long> unpricedInput,
            Map<String, Long> unpricedOutput,
            Map<String, Long> unpricedCacheRead,
            Map<String, Long> unpricedCacheWrite,
            Map<String, Long> unpricedReasoning,
            Map<String, Boolean> priced,
            Map<String, Boolean> approximate,
            Map<String, String> currency,
            String key,
            UsageEventRecord event) {
        addLong(inputTokens, key, Math.max(0L, event.getInputTokens()));
        addLong(outputTokens, key, Math.max(0L, event.getOutputTokens()));
        addLong(reasoningTokens, key, Math.max(0L, event.getReasoningTokens()));
        addLong(cacheReadTokens, key, Math.max(0L, event.getCacheReadTokens()));
        addLong(cacheWriteTokens, key, Math.max(0L, event.getCacheWriteTokens()));
        addLong(costMicros, key, Math.max(0L, event.getCostMicros()));
        addLong(unpricedInput, key, Math.max(0L, event.getUnpricedInputTokens()));
        addLong(unpricedOutput, key, Math.max(0L, event.getUnpricedOutputTokens()));
        addLong(unpricedCacheRead, key, Math.max(0L, event.getUnpricedCacheReadTokens()));
        addLong(unpricedCacheWrite, key, Math.max(0L, event.getUnpricedCacheWriteTokens()));
        addLong(unpricedReasoning, key, Math.max(0L, event.getUnpricedReasoningTokens()));
        priced.put(key, Boolean.valueOf(booleanValue(priced, key) || event.isPricingAvailable()));
        approximate.put(
                key,
                Boolean.valueOf(booleanValue(approximate, key) || event.isBackfillApproximate()));
        currency.put(key, mergeCurrency(stringValue(currency, key), event.getCurrency()));
    }

    private void addSession(Map<String, Set<String>> sessions, String key, String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        Set<String> values = sessions.get(key);
        if (values == null) {
            values = new LinkedHashSet<String>();
            sessions.put(key, values);
        }
        values.add(sessionId);
    }

    private void putCostFields(
            Map<String, Object> target,
            long costMicros,
            String currency,
            boolean pricingAvailable,
            long unpricedInput,
            long unpricedOutput,
            long unpricedCacheRead,
            long unpricedCacheWrite,
            long unpricedReasoning,
            boolean backfillApproximate) {
        target.put("total_cost_micros", Long.valueOf(Math.max(0L, costMicros)));
        target.put("cost_micros", Long.valueOf(Math.max(0L, costMicros)));
        target.put("currency", StrUtil.blankToDefault(currency, ""));
        target.put("pricing_available", Boolean.valueOf(pricingAvailable));
        target.put("unpriced_input_tokens", Long.valueOf(Math.max(0L, unpricedInput)));
        target.put("unpriced_output_tokens", Long.valueOf(Math.max(0L, unpricedOutput)));
        target.put("unpriced_cache_read_tokens", Long.valueOf(Math.max(0L, unpricedCacheRead)));
        target.put("unpriced_cache_write_tokens", Long.valueOf(Math.max(0L, unpricedCacheWrite)));
        target.put("unpriced_reasoning_tokens", Long.valueOf(Math.max(0L, unpricedReasoning)));
        target.put(
                "unpriced_total_tokens",
                Long.valueOf(
                        Math.max(0L, unpricedInput)
                                + Math.max(0L, unpricedOutput)
                                + Math.max(0L, unpricedCacheRead)
                                + Math.max(0L, unpricedCacheWrite)
                                + Math.max(0L, unpricedReasoning)));
        target.put("backfill_approximate", Boolean.valueOf(backfillApproximate));
    }

    private long longValue(Map<String, Long> values, String key) {
        Long value = values.get(key);
        return value == null ? 0L : value.longValue();
    }

    private boolean booleanValue(Map<String, Boolean> values, String key) {
        Boolean value = values.get(key);
        return value != null && value.booleanValue();
    }

    private String stringValue(Map<String, String> values, String key) {
        return StrUtil.blankToDefault(values.get(key), "");
    }

    private int sessionCount(Map<String, Set<String>> sessions, String key) {
        Set<String> values = sessions.get(key);
        return values == null ? 0 : values.size();
    }

    private String mergeCurrency(String current, String incoming) {
        String normalizedIncoming =
                StrUtil.blankToDefault(incoming, "").trim().toUpperCase(Locale.ROOT);
        if (StrUtil.isBlank(normalizedIncoming)) {
            return StrUtil.blankToDefault(current, "");
        }
        String normalizedCurrent =
                StrUtil.blankToDefault(current, "").trim().toUpperCase(Locale.ROOT);
        if (StrUtil.isBlank(normalizedCurrent)) {
            return normalizedIncoming;
        }
        if (normalizedCurrent.equals(normalizedIncoming)) {
            return normalizedCurrent;
        }
        return "MIXED";
    }
}
