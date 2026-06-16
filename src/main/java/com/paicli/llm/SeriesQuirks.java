package com.paicli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * 系列差异注册表：把各家 provider 的"个性"（默认模型/endpoint、上下文窗口、缓存模式、特殊请求字段、
 * 图片格式）收敛成**数据 + 小函数**，而非每家一个 client 类。
 *
 * <p>对标 LiteLLM 的做法——provider 差异是数据，不是类层次。新增一家有特殊行为的平台 = 往
 * {@link #forSeries(String)} 加一条；无特殊行为的平台直接走 {@link #GENERIC}（零代码）。
 *
 * @param defaultModel       缺省模型 id（config 没配 model 时用）；可空
 * @param defaultBaseUrl     缺省 endpoint 根（config 没配 baseUrl 时用）；可空
 * @param defaultWindow      默认上下文窗口；config 配了 maxContextWindow 则被覆盖
 * @param supportsCache      是否支持 prompt 缓存（仅用于展示）
 * @param cacheMode          缓存模式名（展示用）；不支持则 "none"
 * @param sendReasoningInHistory 是否把 assistant 的 reasoning_content 回灌进后续请求（Kimi 用）
 * @param requestCustomizer  往请求体塞特殊字段的钩子 (body, model)->void；无则 null（StepFun 用）
 * @param imageTransform     图片 URL 转换钩子 (part, model)->url；返回 null 表示用默认；整个为 null 表示全用默认（GLM-5v 用）
 * @param pricing            该系列的参考定价（每百万 token，CNY）；用于 /context 成本估算。
 *                           未知用 {@link Pricing#UNKNOWN}——估算时显式标注"价格未知"，绝不冒充实价。
 * @param supportsTools      该系列是否支持 function calling；false 时不发 tools 字段（讯飞 MaaS 部分模型用）
 */
public record SeriesQuirks(
        String defaultModel,
        String defaultBaseUrl,
        int defaultWindow,
        boolean supportsCache,
        String cacheMode,
        boolean sendReasoningInHistory,
        BiConsumer<ObjectNode, String> requestCustomizer,
        BiFunction<LlmClient.ContentPart, String, String> imageTransform,
        Pricing pricing,
        boolean supportsTools
) {

    /**
     * 参考定价：每百万 token 的人民币单价。input=未命中缓存的输入价，cached=命中缓存的输入价，output=输出价。
     *
     * <p>价格是**会变的外部数据**（各家调过多次价），不应硬编码当真值；这里给的是**占位/历史参考值**，
     * 真实计费请以平台账单为准、并用 config 的 {@code pricing} 字段覆盖。负值表示**该项未知**。
     */
    public record Pricing(double inputPerMillion, double cachedPerMillion, double outputPerMillion) {
        /** 三项均未知——估算时显示"价格未知"，不参与计算。 */
        public static final Pricing UNKNOWN = new Pricing(-1, -1, -1);

        /** 是否有可用于估算的价格（输入价已知即可）。 */
        public boolean known() {
            return inputPerMillion >= 0;
        }

        /** 命中缓存的输入价：未单独标注则退回输入价（即不享受缓存折扣）。 */
        public double effectiveCachedPerMillion() {
            return cachedPerMillion >= 0 ? cachedPerMillion : inputPerMillion;
        }
    }

    /** 通用：任意 OpenAI 兼容平台，无特殊行为，128k 默认窗口，价格未知，支持工具。 */
    public static final SeriesQuirks GENERIC = new SeriesQuirks(
            null, null, 128_000, false, "none", false, null, null, Pricing.UNKNOWN, true);

    private static final Map<String, SeriesQuirks> REGISTRY = Map.of(
            "glm", new SeriesQuirks(
                    "glm-5.1", "https://open.bigmodel.cn/api/paas/v4", 200_000,
                    true, "glm-prompt-cache", false,
                    null,
                    // GLM-5v 多模态：image_base64 直接用裸 base64（智谱格式），不加 data: 前缀
                    (part, model) -> {
                        if (model != null && model.trim().toLowerCase().startsWith("glm-5v")
                                && "image_base64".equals(part.type())) {
                            return part.imageBase64();
                        }
                        return null;  // 其余走默认
                    },
                    // TODO 占位价，请以智谱账单为准（config pricing 可覆盖）：输入5 / 缓存1 / 输出15 元每百万
                    new Pricing(5.0, 1.0, 15.0), true),
            "deepseek", new SeriesQuirks(
                    "deepseek-v4-flash", "https://api.deepseek.com", 1_000_000,
                    true, "automatic-prefix-cache", false, null, null,
                    // TODO 占位价，请以 DeepSeek 账单为准（config pricing 可覆盖）：输入2 / 缓存0.5 / 输出8 元每百万
                    new Pricing(2.0, 0.5, 8.0), true),
            "step", new SeriesQuirks(
                    "step-3.5-flash", "https://api.stepfun.com/v1", 256_000,
                    true, "step-prefix-cache", false,
                    // StepFun 特有：让它返回 deepseek 兼容的 reasoning_content；特定型号开高推理
                    (body, model) -> {
                        body.put("reasoning_format", "deepseek-style");
                        if (model != null && model.contains("2603")) {
                            body.put("reasoning_effort", "high");
                        }
                    },
                    null,
                    Pricing.UNKNOWN, true),  // 价格未知：请用 config pricing 填平台实价
            "kimi", new SeriesQuirks(
                    "kimi-k2.6", "https://api.moonshot.ai/v1", 256_000,
                    true, "moonshot-context-cache", true, null, null,
                    Pricing.UNKNOWN, true),  // 价格未知：请用 config pricing 填平台实价
            "qwen", new SeriesQuirks(
                    "xopqwen36v35b", "https://maas-api.cn-huabei-1.xf-yun.com/v2", 128_000,
                    false, "none", false, null, null,
                    Pricing.UNKNOWN, false)  // 价格未知 + 讯飞 MaaS 部分模型不支持 function calling
    );

    /** 按 series 名查差异；未知 series 返回 {@link #GENERIC}。 */
    public static SeriesQuirks forSeries(String series) {
        if (series == null) {
            return GENERIC;
        }
        return REGISTRY.getOrDefault(series.trim().toLowerCase(), GENERIC);
    }
}
