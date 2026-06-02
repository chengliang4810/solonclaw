package com.jimuqu.solon.claw.pricing;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-token model pricing in micros. */
@Getter
@Setter
@NoArgsConstructor
public class ModelPrice {
    private String provider;
    private String model;
    private String currency = "USD";
    private long inputMicrosPerToken;
    private long outputMicrosPerToken;
    private long cacheReadMicrosPerToken;
    private long cacheWriteMicrosPerToken;
    private long reasoningMicrosPerToken;
    private long requestMicrosPerRequest;
    private BigDecimal inputMicrosPerTokenExact;
    private BigDecimal outputMicrosPerTokenExact;
    private BigDecimal cacheReadMicrosPerTokenExact;
    private BigDecimal cacheWriteMicrosPerTokenExact;
    private BigDecimal reasoningMicrosPerTokenExact;
    private boolean inputPriceConfigured;
    private boolean outputPriceConfigured;
    private boolean cacheReadPriceConfigured;
    private boolean cacheWritePriceConfigured;
    private boolean reasoningPriceConfigured;
    private boolean requestPriceConfigured;
    private String source;
    private String sourceUrl;
    private String pricingVersion;
    private long fetchedAt;

    public long getPromptMicrosPerToken() {
        return inputMicrosPerToken;
    }

    public void setPromptMicrosPerToken(long promptMicrosPerToken) {
        setInputMicrosPerToken(promptMicrosPerToken);
    }

    public long getCompletionMicrosPerToken() {
        return outputMicrosPerToken;
    }

    public void setCompletionMicrosPerToken(long completionMicrosPerToken) {
        setOutputMicrosPerToken(completionMicrosPerToken);
    }

    public void setInputMicrosPerToken(long inputMicrosPerToken) {
        this.inputMicrosPerToken = Math.max(0L, inputMicrosPerToken);
        this.inputMicrosPerTokenExact = exactMicros(this.inputMicrosPerToken);
        this.inputPriceConfigured = true;
    }

    public void setOutputMicrosPerToken(long outputMicrosPerToken) {
        this.outputMicrosPerToken = Math.max(0L, outputMicrosPerToken);
        this.outputMicrosPerTokenExact = exactMicros(this.outputMicrosPerToken);
        this.outputPriceConfigured = true;
    }

    public void setCacheReadMicrosPerToken(long cacheReadMicrosPerToken) {
        this.cacheReadMicrosPerToken = Math.max(0L, cacheReadMicrosPerToken);
        this.cacheReadMicrosPerTokenExact = exactMicros(this.cacheReadMicrosPerToken);
        this.cacheReadPriceConfigured = true;
    }

    public void setCacheWriteMicrosPerToken(long cacheWriteMicrosPerToken) {
        this.cacheWriteMicrosPerToken = Math.max(0L, cacheWriteMicrosPerToken);
        this.cacheWriteMicrosPerTokenExact = exactMicros(this.cacheWriteMicrosPerToken);
        this.cacheWritePriceConfigured = true;
    }

    public void setReasoningMicrosPerToken(long reasoningMicrosPerToken) {
        this.reasoningMicrosPerToken = Math.max(0L, reasoningMicrosPerToken);
        this.reasoningMicrosPerTokenExact = exactMicros(this.reasoningMicrosPerToken);
        this.reasoningPriceConfigured = true;
    }

    public void setRequestMicrosPerRequest(long requestMicrosPerRequest) {
        this.requestMicrosPerRequest = Math.max(0L, requestMicrosPerRequest);
        this.requestPriceConfigured = true;
    }

    public void setInputCostPerMillion(String inputCostPerMillion) {
        this.inputMicrosPerTokenExact = perMillionToMicrosExact(inputCostPerMillion);
        this.inputMicrosPerToken = roundedMicros(inputMicrosPerTokenExact);
        this.inputPriceConfigured = true;
    }

    public void setOutputCostPerMillion(String outputCostPerMillion) {
        this.outputMicrosPerTokenExact = perMillionToMicrosExact(outputCostPerMillion);
        this.outputMicrosPerToken = roundedMicros(outputMicrosPerTokenExact);
        this.outputPriceConfigured = true;
    }

    public void setCacheReadCostPerMillion(String cacheReadCostPerMillion) {
        this.cacheReadMicrosPerTokenExact = perMillionToMicrosExact(cacheReadCostPerMillion);
        this.cacheReadMicrosPerToken = roundedMicros(cacheReadMicrosPerTokenExact);
        this.cacheReadPriceConfigured = true;
    }

    public void setCacheWriteCostPerMillion(String cacheWriteCostPerMillion) {
        this.cacheWriteMicrosPerTokenExact = perMillionToMicrosExact(cacheWriteCostPerMillion);
        this.cacheWriteMicrosPerToken = roundedMicros(cacheWriteMicrosPerTokenExact);
        this.cacheWritePriceConfigured = true;
    }

    public void setReasoningCostPerMillion(String reasoningCostPerMillion) {
        this.reasoningMicrosPerTokenExact = perMillionToMicrosExact(reasoningCostPerMillion);
        this.reasoningMicrosPerToken = roundedMicros(reasoningMicrosPerTokenExact);
        this.reasoningPriceConfigured = true;
    }

    public BigDecimal inputMicrosPerTokenExact() {
        return exactOrLong(inputMicrosPerTokenExact, inputMicrosPerToken);
    }

    public BigDecimal outputMicrosPerTokenExact() {
        return exactOrLong(outputMicrosPerTokenExact, outputMicrosPerToken);
    }

    public BigDecimal cacheReadMicrosPerTokenExact() {
        return exactOrLong(cacheReadMicrosPerTokenExact, cacheReadMicrosPerToken);
    }

    public BigDecimal cacheWriteMicrosPerTokenExact() {
        return exactOrLong(cacheWriteMicrosPerTokenExact, cacheWriteMicrosPerToken);
    }

    public BigDecimal reasoningMicrosPerTokenExact() {
        return exactOrLong(reasoningMicrosPerTokenExact, reasoningMicrosPerToken);
    }

    public String key() {
        return normalize(provider) + "/" + normalize(model);
    }

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

    static String normalize(String value) {
        return StrUtil.nullToEmpty(value).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void setInputExact(BigDecimal exact) {
        this.inputMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.inputMicrosPerToken = roundedMicros(this.inputMicrosPerTokenExact);
    }

    private void setOutputExact(BigDecimal exact) {
        this.outputMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.outputMicrosPerToken = roundedMicros(this.outputMicrosPerTokenExact);
    }

    private void setCacheReadExact(BigDecimal exact) {
        this.cacheReadMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.cacheReadMicrosPerToken = roundedMicros(this.cacheReadMicrosPerTokenExact);
    }

    private void setCacheWriteExact(BigDecimal exact) {
        this.cacheWriteMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.cacheWriteMicrosPerToken = roundedMicros(this.cacheWriteMicrosPerTokenExact);
    }

    private void setReasoningExact(BigDecimal exact) {
        this.reasoningMicrosPerTokenExact = exactOrLong(exact, 0L);
        this.reasoningMicrosPerToken = roundedMicros(this.reasoningMicrosPerTokenExact);
    }

    private static BigDecimal exactMicros(long microsPerToken) {
        return BigDecimal.valueOf(Math.max(0L, microsPerToken));
    }

    private static BigDecimal exactOrLong(BigDecimal exact, long rounded) {
        return exact == null ? exactMicros(rounded) : exact.max(BigDecimal.ZERO);
    }

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

    private static long roundedMicros(BigDecimal microsPerToken) {
        if (microsPerToken == null || microsPerToken.signum() <= 0) {
            return 0L;
        }
        return microsPerToken.setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
