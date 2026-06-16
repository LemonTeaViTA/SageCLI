package com.paicli.context;

import com.paicli.llm.OpenAiCompatibleClient;
import com.paicli.llm.SeriesQuirks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证成本估算改为按 series 定价（而非旧的 providerName 字符串匹配）+ config 覆盖 + 未知诚实标注。
 */
class TokenUsageFormatterTest {

    private static OpenAiCompatibleClient client(String series) {
        // providerName 用条目名（如 ds-v4-nv），故意和 series 不同——验证不再按 providerName 匹配
        return new OpenAiCompatibleClient(
                "k", "m", "https://example.invalid/v1", "entry-name-not-series",
                0, SeriesQuirks.forSeries(series));
    }

    @Test
    void deepseekUsesSeriesPricingNotProviderName() {
        // deepseek 占位价 2/0.5/8：100万输入(无缓存) + 100万输出 = 2 + 8 = ¥10
        String cost = TokenUsageFormatter.estimatedCostCny(client("deepseek"), 1_000_000, 1_000_000, 0);
        assertEquals("¥10.0000", cost);
    }

    @Test
    void cachedInputUsesDiscountedRate() {
        // deepseek：100万输入全部命中缓存(0.5) + 0 输出 = ¥0.5
        String cost = TokenUsageFormatter.estimatedCostCny(client("deepseek"), 1_000_000, 0, 1_000_000);
        assertEquals("¥0.5000", cost);
    }

    @Test
    void unknownSeriesShowsPriceUnknownNotDefaultRate() {
        // qwen/step/kimi/generic 价格未知——必须诚实标注，绝不套默认价
        assertEquals("价格未知", TokenUsageFormatter.estimatedCostCny(client("qwen"), 1_000_000, 1_000_000, 0));
        assertEquals("价格未知", TokenUsageFormatter.estimatedCostCny(client("step"), 1_000, 1_000, 0));
        assertEquals("价格未知", TokenUsageFormatter.estimatedCostCny(client(null), 1_000, 1_000, 0));
    }

    @Test
    void nullClientShowsPriceUnknown() {
        assertEquals("价格未知", TokenUsageFormatter.estimatedCostCny(null, 1_000, 1_000, 0));
    }

    @Test
    void configPricingOverrideWins() {
        // 即便 series 未知，config 注入的覆盖价也能让估算生效
        OpenAiCompatibleClient c = client("qwen");
        c.setPricingOverride(new SeriesQuirks.Pricing(3.0, -1, 6.0));  // 缓存价缺省→退回输入价
        // 100万输入(无缓存,3) + 100万输出(6) = ¥9
        assertEquals("¥9.0000", TokenUsageFormatter.estimatedCostCny(c, 1_000_000, 1_000_000, 0));
    }

    @Test
    void overrideWithoutCachedRateFallsBackToInputRate() {
        OpenAiCompatibleClient c = client("qwen");
        c.setPricingOverride(new SeriesQuirks.Pricing(3.0, -1, 6.0));
        // 100万输入全命中缓存，但未标缓存价→按输入价3算 = ¥3
        assertEquals("¥3.0000", TokenUsageFormatter.estimatedCostCny(c, 1_000_000, 0, 1_000_000));
    }
}
