package com.paicli.cost;

import com.paicli.llm.LlmClient;
import com.paicli.llm.SeriesQuirks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测性账本 —— 成本 / token 的累计，按模型分桶。
 *
 * <p>对照 {@code wiki/CluadeCode/CC-可观测性.md} 的「① 成本/token 账本」：逐项累计、按模型分用量、
 * 区分 cache_read 与 cache_creation，并能跨会话保存。本类只负责**累加与算钱**，从
 * {@code AgentBudget} 的「退出兜底」职责中抽离出来 —— 后者内部持有一个本账本做 token 计数。
 *
 * <p>三处用途：
 * <ol>
 *   <li>单次 run 的临时账本（AgentBudget 内部持有），run 结束 {@link #merge} 进会话账本；</li>
 *   <li>会话级账本（MemoryManager 持有），跨多轮累计，/cost 与持久化的数据源；</li>
 *   <li>项目历史聚合（SessionStore 把多份 sidecar merge 成一个总账本）。</li>
 * </ol>
 *
 * <p>非线程安全：账本只在单条 agent 执行线程上累加，并行子 agent 各自独立账本、收尾再 merge。
 */
public final class CostLedger {

    private final Map<String, ModelUsage> byModel = new LinkedHashMap<>();

    /** 按 LLM 客户端记一次调用的用量（模型 id 分桶，定价取客户端实时价）。 */
    public void record(LlmClient client, int inputTokens, int outputTokens,
                       int cacheReadTokens, int cacheCreationTokens) {
        String modelId = client == null ? "unknown" : client.getModelName();
        String displayName = client == null ? "unknown" : client.getDisplayName();
        String provider = client == null ? "" : client.getProviderName();
        SeriesQuirks.Pricing pricing = client == null ? SeriesQuirks.Pricing.UNKNOWN : client.pricing();
        bucket(modelId, displayName, provider, pricing)
                .add(inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens);
    }

    /** 把另一账本并进来（per-run → session、多 sidecar → 项目聚合）。 */
    public void merge(CostLedger other) {
        if (other == null) {
            return;
        }
        for (ModelUsage u : other.byModel.values()) {
            ModelUsage target = bucket(u.modelId(), u.displayName(), u.provider(), u.pricing());
            target.adoptPricingIfKnown(u.pricing());
            target.mergeRaw(u.inputTokens(), u.outputTokens(), u.cacheReadTokens(),
                    u.cacheCreationTokens(), u.callCount());
        }
    }

    private ModelUsage bucket(String modelId, String displayName, String provider, SeriesQuirks.Pricing pricing) {
        String key = modelId == null || modelId.isBlank() ? "unknown" : modelId;
        return byModel.computeIfAbsent(key, k -> new ModelUsage(k, displayName, provider, pricing));
    }

    public boolean isEmpty() {
        return byModel.isEmpty();
    }

    /** per-model 明细快照（按首次出现顺序）。 */
    public List<ModelUsage> models() {
        return List.copyOf(byModel.values());
    }

    public long totalInputTokens() {
        return byModel.values().stream().mapToLong(ModelUsage::inputTokens).sum();
    }

    public long totalOutputTokens() {
        return byModel.values().stream().mapToLong(ModelUsage::outputTokens).sum();
    }

    public long totalCacheReadTokens() {
        return byModel.values().stream().mapToLong(ModelUsage::cacheReadTokens).sum();
    }

    public long totalCacheCreationTokens() {
        return byModel.values().stream().mapToLong(ModelUsage::cacheCreationTokens).sum();
    }

    public long totalCallCount() {
        return byModel.values().stream().mapToLong(ModelUsage::callCount).sum();
    }

    /**
     * 总成本（CNY）；账本为空返回 0；只要有**任一**用到的模型价格未知，返回负值表示「整体未知」。
     * 这样展示层不会把"漏算了某个模型"的偏低值当成真实总价。
     */
    public double totalCostCny() {
        if (byModel.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (ModelUsage u : byModel.values()) {
            if (!u.priceKnown()) {
                return -1.0;
            }
            sum += u.costCny();
        }
        return sum;
    }

    public boolean allPricesKnown() {
        return !byModel.isEmpty() && byModel.values().stream().allMatch(ModelUsage::priceKnown);
    }

    /**
     * 统一成本公式（每百万 token，CNY）。{@link com.paicli.context.TokenUsageFormatter} 也复用本式。
     *
     * <p>{@code uncachedInput = input - cacheRead - cacheCreation}（夹到 ≥0）。
     * 成本 = 未命中输入×输入价 + 读缓存×缓存价 + 写缓存×输入价 + 输出×输出价。
     *
     * @return 成本 CNY；pricing 为空 / 未知时返回 -1。
     */
    public static double costCny(SeriesQuirks.Pricing pricing, long inputTokens, long outputTokens,
                                 long cacheReadTokens, long cacheCreationTokens) {
        if (pricing == null || !pricing.known()) {
            return -1.0;
        }
        long cacheRead = clampNonNeg(cacheReadTokens);
        long cacheCreate = clampNonNeg(cacheCreationTokens);
        long input = clampNonNeg(inputTokens);
        long uncachedInput = Math.max(0, input - cacheRead - cacheCreate);
        return uncachedInput / 1_000_000.0 * pricing.inputPerMillion()
                + cacheRead / 1_000_000.0 * pricing.effectiveCachedPerMillion()
                + cacheCreate / 1_000_000.0 * pricing.inputPerMillion()
                + clampNonNeg(outputTokens) / 1_000_000.0 * pricing.outputPerMillion();
    }

    private static long clampNonNeg(long v) {
        return Math.max(0, v);
    }

    /** 转成可序列化快照（写盘用）。 */
    public CostSnapshot toSnapshot() {
        var rows = byModel.values().stream()
                .map(u -> new CostSnapshot.ModelRow(
                        u.modelId(), u.displayName(), u.provider(),
                        u.inputTokens(), u.outputTokens(), u.cacheReadTokens(),
                        u.cacheCreationTokens(), u.callCount(),
                        u.pricing() == null ? -1 : u.pricing().inputPerMillion(),
                        u.pricing() == null ? -1 : u.pricing().cachedPerMillion(),
                        u.pricing() == null ? -1 : u.pricing().outputPerMillion()))
                .toList();
        return new CostSnapshot(1, rows);
    }

    /** 从快照恢复账本（持久化读回 / 项目聚合用）。 */
    public static CostLedger fromSnapshot(CostSnapshot snapshot) {
        CostLedger ledger = new CostLedger();
        if (snapshot == null || snapshot.models() == null) {
            return ledger;
        }
        for (CostSnapshot.ModelRow row : snapshot.models()) {
            if (row == null) {
                continue;
            }
            SeriesQuirks.Pricing pricing = new SeriesQuirks.Pricing(
                    row.inputPerMillion(), row.cachedPerMillion(), row.outputPerMillion());
            ModelUsage u = ledger.bucket(row.modelId(), row.displayName(), row.provider(), pricing);
            u.mergeRaw(row.inputTokens(), row.outputTokens(), row.cacheReadTokens(),
                    row.cacheCreationTokens(), row.callCount());
        }
        return ledger;
    }
}
