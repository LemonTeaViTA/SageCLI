package com.paicli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Request;

/**
 * 唯一的 LLM client：配置驱动 + 按 series 应用差异（{@link SeriesQuirks}）。
 *
 * <p>取代了原先 GLM/DeepSeek/Step/Kimi/Qwen 五个专用子类——provider 差异（默认模型/endpoint、
 * 上下文窗口、缓存、特殊请求字段、图片格式）全部收敛进 SeriesQuirks 注册表，对标 LiteLLM 的做法。
 * 加一家有特殊行为的平台 = 注册表加一条；无特殊行为的平台零代码。
 *
 * <p>能力值（窗口）：构造注入的 window>0 优先，否则用 quirks 默认——所以同系列多模型可在 config
 * 里各配各的窗口、同时保留该系列的钩子（解决"钩子 vs 可配窗口"的二选一矛盾）。
 */
public class OpenAiCompatibleClient extends AbstractOpenAiCompatibleClient {

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final String providerName;
    private final int maxContextWindow;
    private final SeriesQuirks quirks;

    /**
     * 完整构造。
     *
     * @param maxContextWindow >0 用此值，否则用 quirks 默认
     * @param quirks           系列差异；null 视为 {@link SeriesQuirks#GENERIC}
     */
    public OpenAiCompatibleClient(String apiKey, String model, String baseUrl,
                                  String providerName, int maxContextWindow, SeriesQuirks quirks) {
        this.quirks = quirks != null ? quirks : SeriesQuirks.GENERIC;
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
        this.providerName = providerName != null && !providerName.isBlank() ? providerName : "custom";
        this.maxContextWindow = maxContextWindow > 0 ? maxContextWindow : this.quirks.defaultWindow();
    }

    /**
     * 测试友好构造：只给 apiKey，model/baseUrl/series 走通用默认。
     * 主要给测试桩（override chat()、不真发请求）用，避免每个桩都填一堆参数。
     */
    public OpenAiCompatibleClient(String apiKey) {
        this(apiKey, "test-model", "https://example.invalid/v1", "custom", 0, SeriesQuirks.GENERIC);
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public int maxContextWindow() {
        return maxContextWindow;
    }

    @Override
    public boolean supportsPromptCaching() {
        return quirks.supportsCache();
    }

    @Override
    public String promptCacheMode() {
        return quirks.cacheMode();
    }

    @Override
    public SeriesQuirks.Pricing pricing() {
        // config 注入的覆盖价优先；否则用该 series 的参考价
        return pricingOverride() != null ? pricingOverride() : quirks.pricing();
    }

    @Override
    public boolean supportsTools() {
        return quirks.supportsTools();
    }

    @Override
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return quirks.sendReasoningInHistory();
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        if (quirks.requestCustomizer() != null) {
            quirks.requestCustomizer().accept(requestBody, model);
        }
    }

    @Override
    protected void customizeRequest(Request.Builder request) {
        // config 注入的自定义 header（如讯飞 MaaS 的 lora_id）：键由平台约定，值来自用户配置
        java.util.Map<String, String> headers = extraHeaders();
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    request.header(e.getKey(), e.getValue());
                }
            }
        }
    }

    @Override
    protected String toImageUrl(LlmClient.ContentPart part) {
        if (quirks.imageTransform() != null) {
            String custom = quirks.imageTransform().apply(part, model);
            if (custom != null) {
                return custom;
            }
        }
        return super.toImageUrl(part);
    }

    /**
     * 归一化 baseUrl 到 /chat/completions（填到 endpoint 根即可）；null/空则抛错。
     */
    private static String toChatCompletionsUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("必须提供 baseUrl（OpenAI 兼容 endpoint）");
        }
        String withoutTrailingSlash = baseUrl.trim().replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
