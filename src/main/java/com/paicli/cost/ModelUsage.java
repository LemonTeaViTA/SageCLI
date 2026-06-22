package com.paicli.cost;

import com.paicli.llm.SeriesQuirks;

/**
 * 单个模型的用量累加器 —— 可观测性账本的 per-model 维度。
 *
 * <p>对照 CC 的「按模型分用量」：每个用到的模型分别统计 input / output / cache_read / cache_creation /
 * 调用次数，并据各自定价算出成本。区分 cache_read（命中读缓存）与 cache_creation（首次写缓存）是
 * 测量前缀缓存命中率的依据 —— 写缓存量高说明缓存在反复重建、命中差。
 *
 * <p>计价沿用 {@link SeriesQuirks.Pricing}（input / cached / output，每百万 token CNY）：
 * 写缓存暂按输入价计（不引入额外档位）。价格未知时成本返回负值，由展示层标注「价格未知」。
 */
public final class ModelUsage {

    private final String modelId;
    private final String displayName;
    private final String provider;
    private SeriesQuirks.Pricing pricing;

    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheCreationTokens;
    private long callCount;

    public ModelUsage(String modelId, String displayName, String provider, SeriesQuirks.Pricing pricing) {
        this.modelId = modelId == null || modelId.isBlank() ? "unknown" : modelId;
        this.displayName = displayName == null || displayName.isBlank() ? this.modelId : displayName;
        this.provider = provider == null ? "" : provider;
        this.pricing = pricing == null ? SeriesQuirks.Pricing.UNKNOWN : pricing;
    }

    /** 累加一次调用的用量。 */
    public void add(long input, long output, long cacheRead, long cacheCreation) {
        this.inputTokens += Math.max(0, input);
        this.outputTokens += Math.max(0, output);
        this.cacheReadTokens += Math.max(0, cacheRead);
        this.cacheCreationTokens += Math.max(0, cacheCreation);
        this.callCount++;
    }

    /** 直接累加（持久化恢复 / merge 用，不++callCount，由调用方带上真实 callCount）。 */
    void mergeRaw(long input, long output, long cacheRead, long cacheCreation, long calls) {
        this.inputTokens += Math.max(0, input);
        this.outputTokens += Math.max(0, output);
        this.cacheReadTokens += Math.max(0, cacheRead);
        this.cacheCreationTokens += Math.max(0, cacheCreation);
        this.callCount += Math.max(0, calls);
    }

    /** 若新值有已知价而当前未知，则采用新价（恢复 / merge 时尽量保住可算的定价）。 */
    void adoptPricingIfKnown(SeriesQuirks.Pricing candidate) {
        if (candidate != null && candidate.known() && (pricing == null || !pricing.known())) {
            this.pricing = candidate;
        }
    }

    /** 本模型成本（CNY）；价格未知返回负值。 */
    public double costCny() {
        return CostLedger.costCny(pricing, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens);
    }

    public boolean priceKnown() {
        return pricing != null && pricing.known();
    }

    public String modelId() { return modelId; }
    public String displayName() { return displayName; }
    public String provider() { return provider; }
    public SeriesQuirks.Pricing pricing() { return pricing; }
    public long inputTokens() { return inputTokens; }
    public long outputTokens() { return outputTokens; }
    public long cacheReadTokens() { return cacheReadTokens; }
    public long cacheCreationTokens() { return cacheCreationTokens; }
    public long callCount() { return callCount; }
}
