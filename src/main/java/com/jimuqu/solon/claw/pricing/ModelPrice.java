package com.jimuqu.solon.claw.pricing;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 承载模型价格相关状态和辅助逻辑。 */
@Getter
@Setter
@NoArgsConstructor
public class ModelPrice {
    /** 记录模型价格中的提供方。 */
    private String provider;

    /** 记录模型价格中的模型。 */
    private String model;

    /** 记录模型价格中的currency。 */
    private String currency = "USD";

    /** 记录模型价格中的输入MicrosPertoken。 */
    private long inputMicrosPerToken;

    /** 记录模型价格中的输出MicrosPertoken。 */
    private long outputMicrosPerToken;

    /** 记录模型价格中的缓存ReadMicrosPertoken。 */
    private long cacheReadMicrosPerToken;

    /** 记录模型价格中的缓存写入MicrosPertoken。 */
    private long cacheWriteMicrosPerToken;

    /** 记录模型价格中的推理MicrosPertoken。 */
    private long reasoningMicrosPerToken;

    /** 记录模型价格中的请求MicrosPer请求。 */
    private long requestMicrosPerRequest;

    /** 记录模型价格中的输入MicrosPertoken精确。 */
    private BigDecimal inputMicrosPerTokenExact;

    /** 记录模型价格中的输出MicrosPertoken精确。 */
    private BigDecimal outputMicrosPerTokenExact;

    /** 记录模型价格中的缓存ReadMicrosPertoken精确。 */
    private BigDecimal cacheReadMicrosPerTokenExact;

    /** 记录模型价格中的缓存写入MicrosPertoken精确。 */
    private BigDecimal cacheWriteMicrosPerTokenExact;

    /** 记录模型价格中的推理MicrosPertoken精确。 */
    private BigDecimal reasoningMicrosPerTokenExact;

    /** 是否启用输入价格已配置。 */
    private boolean inputPriceConfigured;

    /** 是否启用输出价格已配置。 */
    private boolean outputPriceConfigured;

    /** 是否启用缓存Read价格已配置。 */
    private boolean cacheReadPriceConfigured;

    /** 是否启用缓存写入价格已配置。 */
    private boolean cacheWritePriceConfigured;

    /** 是否启用推理价格已配置。 */
    private boolean reasoningPriceConfigured;

    /** 是否启用请求价格已配置。 */
    private boolean requestPriceConfigured;

    /** 记录模型价格中的来源。 */
    private String source;

    /** 记录模型价格中的来源URL。 */
    private String sourceUrl;

    /** 记录模型价格中的价格版本。 */
    private String pricingVersion;

    /** 记录模型价格中的fetched时间。 */
    private long fetchedAt;

    /**
     * 读取提示词Micros Per token。
     *
     * @return 返回读取到的提示词Micros Per token。
     */
    public long getPromptMicrosPerToken() {
        return inputMicrosPerToken;
    }

    /**
     * 读取Completion Micros Per token。
     *
     * @return 返回读取到的Completion Micros Per token。
     */
    public long getCompletionMicrosPerToken() {
        return outputMicrosPerToken;
    }

    /**
     * 写入输入Micros Per token。
     *
     * @param inputMicrosPerToken 输入MicrosPertoken参数。
     */
    public void setInputMicrosPerToken(long inputMicrosPerToken) {
        this.inputMicrosPerToken = Math.max(0L, inputMicrosPerToken);
        this.inputMicrosPerTokenExact = exactMicros(this.inputMicrosPerToken);
        this.inputPriceConfigured = true;
    }

    /**
     * 写入输出Micros Per token。
     *
     * @param outputMicrosPerToken 输出MicrosPertoken参数。
     */
    public void setOutputMicrosPerToken(long outputMicrosPerToken) {
        this.outputMicrosPerToken = Math.max(0L, outputMicrosPerToken);
        this.outputMicrosPerTokenExact = exactMicros(this.outputMicrosPerToken);
        this.outputPriceConfigured = true;
    }

    /**
     * 写入缓存Read Micros Per token。
     *
     * @param cacheReadMicrosPerToken 缓存ReadMicrosPertoken参数。
     */
    public void setCacheReadMicrosPerToken(long cacheReadMicrosPerToken) {
        this.cacheReadMicrosPerToken = Math.max(0L, cacheReadMicrosPerToken);
        this.cacheReadMicrosPerTokenExact = exactMicros(this.cacheReadMicrosPerToken);
        this.cacheReadPriceConfigured = true;
    }

    /**
     * 写入缓存Write Micros Per token。
     *
     * @param cacheWriteMicrosPerToken 缓存写入MicrosPertoken参数。
     */
    public void setCacheWriteMicrosPerToken(long cacheWriteMicrosPerToken) {
        this.cacheWriteMicrosPerToken = Math.max(0L, cacheWriteMicrosPerToken);
        this.cacheWriteMicrosPerTokenExact = exactMicros(this.cacheWriteMicrosPerToken);
        this.cacheWritePriceConfigured = true;
    }

    /**
     * 写入Reasoning Micros Per token。
     *
     * @param reasoningMicrosPerToken 推理MicrosPertoken参数。
     */
    public void setReasoningMicrosPerToken(long reasoningMicrosPerToken) {
        this.reasoningMicrosPerToken = Math.max(0L, reasoningMicrosPerToken);
        this.reasoningMicrosPerTokenExact = exactMicros(this.reasoningMicrosPerToken);
        this.reasoningPriceConfigured = true;
    }

    /**
     * 写入请求Micros Per请求。
     *
     * @param requestMicrosPerRequest 请求MicrosPer请求请求载荷。
     */
    public void setRequestMicrosPerRequest(long requestMicrosPerRequest) {
        this.requestMicrosPerRequest = Math.max(0L, requestMicrosPerRequest);
        this.requestPriceConfigured = true;
    }

    /**
     * 写入输入成本Per Million。
     *
     * @param inputCostPerMillion 输入成本PerMillion参数。
     */
    public void setInputCostPerMillion(String inputCostPerMillion) {
        this.inputMicrosPerTokenExact = perMillionToMicrosExact(inputCostPerMillion);
        this.inputMicrosPerToken = roundedMicros(inputMicrosPerTokenExact);
        this.inputPriceConfigured = true;
    }

    /**
     * 写入输出成本Per Million。
     *
     * @param outputCostPerMillion 输出成本PerMillion参数。
     */
    public void setOutputCostPerMillion(String outputCostPerMillion) {
        this.outputMicrosPerTokenExact = perMillionToMicrosExact(outputCostPerMillion);
        this.outputMicrosPerToken = roundedMicros(outputMicrosPerTokenExact);
        this.outputPriceConfigured = true;
    }

    /**
     * 写入缓存Read成本Per Million。
     *
     * @param cacheReadCostPerMillion 缓存Read成本PerMillion参数。
     */
    public void setCacheReadCostPerMillion(String cacheReadCostPerMillion) {
        this.cacheReadMicrosPerTokenExact = perMillionToMicrosExact(cacheReadCostPerMillion);
        this.cacheReadMicrosPerToken = roundedMicros(cacheReadMicrosPerTokenExact);
        this.cacheReadPriceConfigured = true;
    }

    /**
     * 写入缓存Write成本Per Million。
     *
     * @param cacheWriteCostPerMillion 缓存写入成本PerMillion参数。
     */
    public void setCacheWriteCostPerMillion(String cacheWriteCostPerMillion) {
        this.cacheWriteMicrosPerTokenExact = perMillionToMicrosExact(cacheWriteCostPerMillion);
        this.cacheWriteMicrosPerToken = roundedMicros(cacheWriteMicrosPerTokenExact);
        this.cacheWritePriceConfigured = true;
    }

    /**
     * 写入Reasoning成本Per Million。
     *
     * @param reasoningCostPerMillion 推理成本PerMillion参数。
     */
    public void setReasoningCostPerMillion(String reasoningCostPerMillion) {
        this.reasoningMicrosPerTokenExact = perMillionToMicrosExact(reasoningCostPerMillion);
        this.reasoningMicrosPerToken = roundedMicros(reasoningMicrosPerTokenExact);
        this.reasoningPriceConfigured = true;
    }

    /**
     * 执行输入MicrosPertoken精确相关逻辑。
     *
     * @return 返回输入Micros Per token Exact结果。
     */
    public BigDecimal inputMicrosPerTokenExact() {
        return exactOrLong(inputMicrosPerTokenExact, inputMicrosPerToken);
    }

    /**
     * 执行输出MicrosPertoken精确相关逻辑。
     *
     * @return 返回输出Micros Per token Exact结果。
     */
    public BigDecimal outputMicrosPerTokenExact() {
        return exactOrLong(outputMicrosPerTokenExact, outputMicrosPerToken);
    }

    /**
     * 执行缓存ReadMicrosPertoken精确相关逻辑。
     *
     * @return 返回缓存Read Micros Per token Exact结果。
     */
    public BigDecimal cacheReadMicrosPerTokenExact() {
        return exactOrLong(cacheReadMicrosPerTokenExact, cacheReadMicrosPerToken);
    }

    /**
     * 执行缓存写入MicrosPertoken精确相关逻辑。
     *
     * @return 返回缓存Write Micros Per token Exact结果。
     */
    public BigDecimal cacheWriteMicrosPerTokenExact() {
        return exactOrLong(cacheWriteMicrosPerTokenExact, cacheWriteMicrosPerToken);
    }

    /**
     * 执行推理MicrosPertoken精确相关逻辑。
     *
     * @return 返回reasoning Micros Per token Exact结果。
     */
    public BigDecimal reasoningMicrosPerTokenExact() {
        return exactOrLong(reasoningMicrosPerTokenExact, reasoningMicrosPerToken);
    }

    /**
     * 执行键相关逻辑。
     *
     * @return 返回键结果。
     */
    public String key() {
        return normalize(provider) + "/" + normalize(model);
    }

    /**
     * 判断当前模型是否所有计费维度均为免费。
     *
     * @return 如果输入、输出、缓存和推理价格都为0则返回true。
     */
    public boolean isFree() {
        return inputMicrosPerTokenExact().signum() == 0
                && outputMicrosPerTokenExact().signum() == 0
                && cacheReadMicrosPerTokenExact().signum() == 0
                && cacheWriteMicrosPerTokenExact().signum() == 0
                && reasoningMicrosPerTokenExact().signum() == 0;
    }

    /**
     * 合并Override。
     *
     * @param override override标识或键值。
     * @return 返回Override结果。
     */
    public ModelPrice mergeOverride(ModelPrice override) {
        if (override == null) {
            return copy();
        }
        ModelPrice merged = copy();
        merged.setProvider(StrUtil.blankToDefault(override.getProvider(), merged.getProvider()));
        merged.setModel(StrUtil.blankToDefault(override.getModel(), merged.getModel()));
        if (StrUtil.isNotBlank(override.getCurrency())) {
            merged.setCurrency(override.getCurrency());
        }
        if (override.isInputPriceConfigured()) {
            merged.setInputExact(override.inputMicrosPerTokenExact());
        }
        if (override.isOutputPriceConfigured()) {
            merged.setOutputExact(override.outputMicrosPerTokenExact());
        }
        if (override.isCacheReadPriceConfigured()) {
            merged.setCacheReadExact(override.cacheReadMicrosPerTokenExact());
        }
        if (override.isCacheWritePriceConfigured()) {
            merged.setCacheWriteExact(override.cacheWriteMicrosPerTokenExact());
        }
        if (override.isReasoningPriceConfigured()) {
            merged.setReasoningExact(override.reasoningMicrosPerTokenExact());
        }
        if (override.isRequestPriceConfigured()) {
            merged.setRequestMicrosPerRequest(override.getRequestMicrosPerRequest());
        }
        if (StrUtil.isNotBlank(override.getSource())) {
            merged.setSource(override.getSource());
        }
        if (StrUtil.isNotBlank(override.getSourceUrl())) {
            merged.setSourceUrl(override.getSourceUrl());
        }
        if (StrUtil.isNotBlank(override.getPricingVersion())) {
            merged.setPricingVersion(override.getPricingVersion());
        }
        if (override.getFetchedAt() > 0L) {
            merged.setFetchedAt(override.getFetchedAt());
        }
        return merged;
    }

    /**
     * 执行copy相关逻辑。
     *
     * @return 返回copy结果。
     */
    public ModelPrice copy() {
        ModelPrice copy = new ModelPrice();
        copy.setProvider(provider);
        copy.setModel(model);
        copy.setCurrency(currency);
        copy.setInputExact(inputMicrosPerTokenExact());
        copy.setOutputExact(outputMicrosPerTokenExact());
        copy.setCacheReadExact(cacheReadMicrosPerTokenExact());
        copy.setCacheWriteExact(cacheWriteMicrosPerTokenExact());
        copy.setReasoningExact(reasoningMicrosPerTokenExact());
        copy.setRequestMicrosPerRequest(requestMicrosPerRequest);
        copy.setInputPriceConfigured(inputPriceConfigured);
        copy.setOutputPriceConfigured(outputPriceConfigured);
        copy.setCacheReadPriceConfigured(cacheReadPriceConfigured);
        copy.setCacheWritePriceConfigured(cacheWritePriceConfigured);
        copy.setReasoningPriceConfigured(reasoningPriceConfigured);
        copy.setRequestPriceConfigured(requestPriceConfigured);
        copy.setSource(source);
        copy.setSourceUrl(sourceUrl);
        copy.setPricingVersion(pricingVersion);
        copy.setFetchedAt(fetchedAt);
        return copy;
    }

    /**
     * 执行规范化相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回规范化结果。
     */
    static String normalize(String value) {
        return StrUtil.nullToEmpty(value).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 写入输入Exact。
     *
     * @param exact 精确参数。
     */
    private void setInputExact(BigDecimal exact) {
        this.inputMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.inputMicrosPerToken = roundedMicros(this.inputMicrosPerTokenExact);
    }

    /**
     * 写入输出Exact。
     *
     * @param exact 精确参数。
     */
    private void setOutputExact(BigDecimal exact) {
        this.outputMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.outputMicrosPerToken = roundedMicros(this.outputMicrosPerTokenExact);
    }

    /**
     * 写入缓存Read Exact。
     *
     * @param exact 精确参数。
     */
    private void setCacheReadExact(BigDecimal exact) {
        this.cacheReadMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.cacheReadMicrosPerToken = roundedMicros(this.cacheReadMicrosPerTokenExact);
    }

    /**
     * 写入缓存Write Exact。
     *
     * @param exact 精确参数。
     */
    private void setCacheWriteExact(BigDecimal exact) {
        this.cacheWriteMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.cacheWriteMicrosPerToken = roundedMicros(this.cacheWriteMicrosPerTokenExact);
    }

    /**
     * 写入Reasoning Exact。
     *
     * @param exact 精确参数。
     */
    private void setReasoningExact(BigDecimal exact) {
        this.reasoningMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.reasoningMicrosPerToken = roundedMicros(this.reasoningMicrosPerTokenExact);
    }

    /**
     * 执行精确Micros相关逻辑。
     *
     * @param microsPerToken microsPertoken参数。
     * @return 返回exact Micros结果。
     */
    private static BigDecimal exactMicros(long microsPerToken) {
        return BigDecimal.valueOf(Math.max(0L, microsPerToken));
    }

    /**
     * 执行精确Or长整型相关逻辑。
     *
     * @param exact 精确参数。
     * @param rounded rounded 参数。
     * @return 返回exact Or Long结果。
     */
    private static BigDecimal exactOrLong(BigDecimal exact, long rounded) {
        return exact == null ? exactMicros(rounded) : exact.max(BigDecimal.ZERO);
    }

    /**
     * 执行perMillionToMicros精确相关逻辑。
     *
     * @param costPerMillion 成本PerMillion参数。
     * @return 返回per Million To Micros Exact结果。
     */
    private static BigDecimal perMillionToMicrosExact(String costPerMillion) {
        if (StrUtil.isBlank(costPerMillion)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(costPerMillion.trim()).max(BigDecimal.ZERO);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 执行roundedMicros相关逻辑。
     *
     * @param microsPerToken microsPertoken参数。
     * @return 返回rounded Micros结果。
     */
    private static long roundedMicros(BigDecimal microsPerToken) {
        if (microsPerToken == null || microsPerToken.signum() <= 0) {
            return 0L;
        }
        return microsPerToken.setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
