package com.paicli.context;

import com.paicli.llm.LlmClient;
import com.paicli.llm.SeriesQuirks;
import com.paicli.util.AnsiStyle;

import java.util.Locale;

public final class TokenUsageFormatter {
    private TokenUsageFormatter() {
    }

    public static String format(LlmClient llmClient, int inputTokens, int outputTokens,
                                int cachedInputTokens, long startNanos) {
        ContextProfile profile = ContextProfile.from(llmClient);
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        int total = Math.max(0, inputTokens) + Math.max(0, outputTokens);
        String cost = estimatedCostCny(llmClient, inputTokens, outputTokens, cachedInputTokens);
        // 第 16 期：Y 显示 maxContextWindow（即模型窗口本身），不再用 80% × window 这种"软预算"
        // 误导用户。AgentBudget 默认已无硬限，撞窗口由 ConversationHistoryCompactor 自动压缩兜底。
        return AnsiStyle.subtle(String.format(Locale.ROOT,
                "📊 Token: 已用 %d / %d (cached: %d, 估算 %s) | 输入 %d / 输出 %d | ⏱ %.1fs",
                total,
                profile.maxContextWindow(),
                Math.max(0, cachedInputTokens),
                cost,
                Math.max(0, inputTokens),
                Math.max(0, outputTokens),
                elapsedSeconds));
    }

    public static String estimatedCostCny(LlmClient llmClient, int inputTokens, int outputTokens, int cachedInputTokens) {
        SeriesQuirks.Pricing pricing = llmClient == null ? SeriesQuirks.Pricing.UNKNOWN : llmClient.pricing();
        if (pricing == null || !pricing.known()) {
            return "价格未知";  // 不冒充实价：未配/未知 series 时显式标注，提示用 config pricing 填实价
        }
        // 复用 CostLedger 的统一成本公式（cacheCreation 传 0，与旧式等价）；避免两处各算一遍口径漂移。
        int cached = Math.max(0, Math.min(Math.max(0, inputTokens), cachedInputTokens));
        double cny = com.paicli.cost.CostLedger.costCny(
                pricing, Math.max(0, inputTokens), Math.max(0, outputTokens), cached, 0);
        return String.format(Locale.ROOT, "¥%.4f", cny);
    }
}
