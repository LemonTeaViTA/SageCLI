package com.paicli.cost;

import com.paicli.llm.LlmClient;
import com.paicli.llm.OpenAiCompatibleClient;
import com.paicli.llm.SeriesQuirks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可观测性账本：per-model 累加 / cache_read vs cache_creation / 统一成本公式 / merge / 未知价 / 快照往返。
 */
class CostLedgerTest {

    private static OpenAiCompatibleClient client(String model, SeriesQuirks.Pricing pricing) {
        // 用裸 SeriesQuirks 注入定价，模型名作为 per-model 分桶 key
        SeriesQuirks quirks = new SeriesQuirks(
                model, "https://example.invalid/v1", 128_000, true, "test-cache",
                false, null, null, pricing, true);
        return new OpenAiCompatibleClient("k", model, "https://example.invalid/v1", model, 0, quirks);
    }

    private static final SeriesQuirks.Pricing PRICING = new SeriesQuirks.Pricing(2.0, 0.5, 8.0);

    @Test
    void costFormulaSeparatesCacheReadAndCreation() {
        // 100万输入，其中 40万读缓存 + 10万写缓存 → 未命中 = 50万
        // 50万×2 + 40万×0.5(缓存价) + 10万×2(写缓存按输入价) + 100万输出×8
        // = 1.0 + 0.2 + 0.2 + 8.0 = 9.4
        double cny = CostLedger.costCny(PRICING, 1_000_000, 1_000_000, 400_000, 100_000);
        assertEquals(9.4, cny, 1e-9);
    }

    @Test
    void costUnknownPricingReturnsNegative() {
        assertEquals(-1.0, CostLedger.costCny(SeriesQuirks.Pricing.UNKNOWN, 1000, 1000, 0, 0), 1e-9);
        assertEquals(-1.0, CostLedger.costCny(null, 1000, 1000, 0, 0), 1e-9);
    }

    @Test
    void perModelBucketingAndTotals() {
        CostLedger ledger = new CostLedger();
        LlmClient a = client("model-a", PRICING);
        LlmClient b = client("model-b", PRICING);
        ledger.record(a, 100, 50, 10, 5);
        ledger.record(a, 200, 80, 20, 0);
        ledger.record(b, 1000, 400, 0, 0);

        assertEquals(2, ledger.models().size());
        assertEquals(1300, ledger.totalInputTokens());
        assertEquals(530, ledger.totalOutputTokens());
        assertEquals(30, ledger.totalCacheReadTokens());
        assertEquals(5, ledger.totalCacheCreationTokens());
        assertEquals(3, ledger.totalCallCount());

        ModelUsage usageA = ledger.models().stream()
                .filter(u -> u.modelId().equals("model-a")).findFirst().orElseThrow();
        assertEquals(2, usageA.callCount());
        assertEquals(300, usageA.inputTokens());
    }

    @Test
    void mergeCombinesLedgers() {
        CostLedger run1 = new CostLedger();
        run1.record(client("m", PRICING), 100, 50, 0, 0);
        CostLedger run2 = new CostLedger();
        run2.record(client("m", PRICING), 200, 60, 0, 0);

        CostLedger session = new CostLedger();
        session.merge(run1);
        session.merge(run2);

        assertEquals(1, session.models().size());
        assertEquals(300, session.totalInputTokens());
        assertEquals(110, session.totalOutputTokens());
        assertEquals(2, session.totalCallCount());
    }

    @Test
    void nullClientGoesToUnknownBucket() {
        CostLedger ledger = new CostLedger();
        ledger.record(null, 100, 50, 0, 0);
        assertEquals("unknown", ledger.models().get(0).modelId());
        assertFalse(ledger.models().get(0).priceKnown());
    }

    @Test
    void totalCostUnknownIfAnyModelPriceUnknown() {
        CostLedger ledger = new CostLedger();
        ledger.record(client("known", PRICING), 1_000_000, 0, 0, 0);
        ledger.record(client("unknown", SeriesQuirks.Pricing.UNKNOWN), 1_000_000, 0, 0, 0);
        assertTrue(ledger.totalCostCny() < 0, "任一模型价格未知 → 整体未知");
        assertFalse(ledger.allPricesKnown());
    }

    @Test
    void totalCostSumsWhenAllKnown() {
        CostLedger ledger = new CostLedger();
        ledger.record(client("m", PRICING), 1_000_000, 1_000_000, 0, 0);  // 2 + 8 = 10
        assertEquals(10.0, ledger.totalCostCny(), 1e-9);
        assertTrue(ledger.allPricesKnown());
    }

    @Test
    void snapshotRoundTripPreservesUsageAndPricing() {
        CostLedger ledger = new CostLedger();
        ledger.record(client("m", PRICING), 1_000_000, 500_000, 200_000, 100_000);

        CostSnapshot snapshot = ledger.toSnapshot();
        CostLedger restored = CostLedger.fromSnapshot(snapshot);

        assertEquals(ledger.totalInputTokens(), restored.totalInputTokens());
        assertEquals(ledger.totalOutputTokens(), restored.totalOutputTokens());
        assertEquals(ledger.totalCacheReadTokens(), restored.totalCacheReadTokens());
        assertEquals(ledger.totalCacheCreationTokens(), restored.totalCacheCreationTokens());
        assertEquals(ledger.totalCostCny(), restored.totalCostCny(), 1e-9);
        assertTrue(restored.allPricesKnown(), "定价随快照固化，恢复后仍可算成本");
    }

    @Test
    void emptyLedgerTotalsZero() {
        CostLedger ledger = new CostLedger();
        assertTrue(ledger.isEmpty());
        assertEquals(0.0, ledger.totalCostCny(), 1e-9);
        assertEquals(List.of(), ledger.models());
    }
}
