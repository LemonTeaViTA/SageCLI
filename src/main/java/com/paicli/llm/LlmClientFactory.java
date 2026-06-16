package com.paicli.llm;

import com.paicli.config.PaiCliConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LlmClientFactory {

    private LlmClientFactory() {}

    /**
     * 建出 provider 对应的 client；若该 model 配了 fallback，则包成 {@link FallbackLlmClient}
     * （主失败时自动切到同版本不同平台的备份）。无 fallback 时返回裸 client，零开销。
     */
    public static LlmClient create(String provider, PaiCliConfig config) {
        LlmClient primary = buildBareClient(provider, config);
        if (primary == null) {
            return null;
        }
        List<String> fallbackKeys = config.getFallback(provider);
        if (fallbackKeys == null || fallbackKeys.isEmpty()) {
            return primary;
        }
        // 备份均构建为裸 client（buildBareClient 不读 fallback）→ 结构上不可能成环。
        // visited 仅用于去重 + 跳过自引用。
        Set<String> visited = new LinkedHashSet<>();
        visited.add(provider == null ? "" : provider.trim().toLowerCase());
        List<LlmClient> fallbacks = new ArrayList<>();
        for (String key : fallbackKeys) {
            if (key == null || key.isBlank()) continue;
            if (!visited.add(key.trim().toLowerCase())) continue;  // 去重 / 跳过自引用
            LlmClient fb = buildBareClient(key.trim(), config);
            if (fb != null) {
                fallbacks.add(fb);
            }
        }
        return fallbacks.isEmpty() ? primary : new FallbackLlmClient(primary, fallbacks);
    }

    /**
     * 建���裸 client（不含 fallback 包装）。fallback 列表里的备份也走这里，因此不会递归读 fallback、不成环。
     */
    private static LlmClient buildBareClient(String provider, PaiCliConfig config) {
        if (provider == null) return null;

        String normalized = normalizeProvider(provider);
        String configuredProvider = provider.trim().toLowerCase();
        String apiKey = config.getApiKey(normalized);
        if ((apiKey == null || apiKey.isBlank()) && !configuredProvider.equals(normalized)) {
            apiKey = config.getApiKey(configuredProvider);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = firstConfigured(config.getModel(normalized),
                configuredProvider.equals(normalized) ? null : config.getModel(configuredProvider));
        String baseUrl = firstConfigured(config.getBaseUrl(normalized),
                configuredProvider.equals(normalized) ? null : config.getBaseUrl(configuredProvider));

        // qwen35 / qwen36 是独立 provider（各带独立 key），但都打到同一个讯飞 MaaS endpoint：
        // 若未单独配 QWEN35_BASE_URL / QWEN36_BASE_URL，则复用通用 QWEN_BASE_URL，省得重复填。
        if ((baseUrl == null || baseUrl.isBlank())
                && normalized.startsWith("qwen") && !normalized.equals("qwen")) {
            baseUrl = config.getBaseUrl("qwen");
        }
        // qwen35 / qwen36 的 model id 默认走 .env 的 QWEN35_MODEL / QWEN36_MODEL；
        // 若都没配，给讯飞已知的固定型号兜底，保证这两个别名开箱即用（用户仍可用配置覆盖）。
        if (model == null || model.isBlank()) {
            model = switch (normalized) {
                case "qwen35" -> "xopqwen35v35b";
                case "qwen36" -> "xopqwen36v35b";
                default -> model;
            };
        }

        // 系列（series）决定用哪套差异（SeriesQuirks），与"切换名"（map key）解耦。
        // 未配 series 则回退为 provider 名本身（兼容旧配置：条目名叫 qwen/glm 时即系列）。
        String series = normalizeProvider(config.getSeries(normalized));
        SeriesQuirks quirks = SeriesQuirks.forSeries(series);

        // model / baseUrl 缺省用该系列的默认值（如 glm 默认 paas/v4、step 默认 stepfun endpoint）
        if (model == null || model.isBlank()) {
            model = quirks.defaultModel();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = quirks.defaultBaseUrl();
        }
        // 配齐才能建（缺 model 或 baseUrl 无法确定调用目标）→ 返回 null，调用方报"未配置"
        if (model == null || model.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            return null;
        }

        // 唯一 client：注入 series + quirks；window 注入 0 时 client 用 quirks 默认窗口。
        // providerName 用条目名（normalized，用户的切换名），保持稳定身份——不是 series。
        int window = config.getMaxContextWindow(normalized);
        OpenAiCompatibleClient client =
                new OpenAiCompatibleClient(apiKey, model, baseUrl, normalized, window, quirks);
        // 注入展示用型号名 + 单次输出上限 + 温度 + 平台特有透传参数
        client.setDisplayName(config.getDisplayName(normalized));
        client.setMaxTokens(config.getMaxTokens(normalized));
        client.setTemperature(config.getTemperature(normalized));
        client.setExtraParams(config.getExtraParams(normalized));
        client.setExtraHeaders(config.getExtraHeaders(normalized));
        // 定价覆盖：config 填了 pricing 数组就注入，否则 client 用 series 参考价
        double[] price = config.getPricing(normalized);
        if (price != null) {
            double in = price.length > 0 ? price[0] : -1;
            double cached = price.length > 1 ? price[1] : -1;
            double out = price.length > 2 ? price[2] : -1;
            client.setPricingOverride(new SeriesQuirks.Pricing(in, cached, out));
        }
        return client;
    }

    public static LlmClient createFromConfig(PaiCliConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }

        for (String provider : new String[]{"glm", "deepseek", "step", "kimi", "qwen", "qwen35", "qwen36"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase();
        return switch (normalized) {
            case "stepfun", "step-fun" -> "step";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            default -> normalized;
        };
    }

    private static String firstConfigured(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
