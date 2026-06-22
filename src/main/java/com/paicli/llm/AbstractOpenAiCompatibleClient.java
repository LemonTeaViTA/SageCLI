package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper mapper = new ObjectMapper();

    // 展示用型号名（可注入）；为空则 getDisplayName() 回退到 getModelName()
    private String displayName;
    // 单次回复最大输出 token（可注入）；> 0 才发 max_tokens，否则用平台默认（避免被小默认值截断）
    private int maxTokens;
    // 采样温度（可注入）；>= 0 才发 temperature，<0 表示不发（用平台默认）
    private double temperature = -1;
    // 平台特有的透传参数（可注入）；如 enable_thinking / reasoning_effort 等，原样塞进请求体
    private java.util.Map<String, Object> extraParams;
    // 平台特有的自定义 HTTP header（可注入）；如讯飞 MaaS 的 lora_id，由 config 提供值
    private java.util.Map<String, String> extraHeaders;
    // 定价覆盖（可注入）；非 null 时优先于 series 默认价，用于 config 填平台实价
    private SeriesQuirks.Pricing pricingOverride;

    // SSE 流式接口下，OkHttp 的 readTimeout 是"两次 read 之间的最大间隔"，不是请求总时长。
    // GLM-5.1 在生成大段 reasoning_content 时服务端可能长时间静默，所以默认值放宽到 300s；
    // callTimeout 作为整体兜底，覆盖极端情况下的连接半死状态。
    // 三项均可通过系统属性覆盖，便于不同模型 / 网络环境调优。
    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("paicli.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("paicli.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("paicli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("paicli.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            .build();

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected abstract String getApiUrl();

    protected abstract String getModel();

    protected abstract String getApiKey();

    /** 注入展示用型号名（如 Qwen3.6-35B-A3B）。工厂建好 client 后按配置调用。 */
    public void setDisplayName(String displayName) {
        this.displayName = displayName != null && !displayName.isBlank() ? displayName.trim() : null;
    }

    /** 注入单次回复最大输出 token；> 0 才发 max_tokens。工厂按配置调用。 */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /** 注入采样温度；>= 0 才发 temperature。工厂按配置调用。 */
    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /** 注入平台特有透传参数（enable_thinking / reasoning_effort 等）；原样塞进请求体。 */
    public void setExtraParams(java.util.Map<String, Object> extraParams) {
        this.extraParams = extraParams;
    }

    /** 注入平台特有自定义 HTTP header（如讯飞 MaaS 的 lora_id）；工厂按配置调用。 */
    public void setExtraHeaders(java.util.Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders;
    }

    /** 当前自定义 header；供子类的 customizeRequest 消费。 */
    protected java.util.Map<String, String> extraHeaders() {
        return extraHeaders;
    }

    /** 注入定价覆盖（config pricing）；非 null 时优先于 series 默认价。 */
    public void setPricingOverride(SeriesQuirks.Pricing pricingOverride) {
        this.pricingOverride = pricingOverride;
    }

    /** 当前生效定价：config 覆盖优先，否则子类提供的 series 默认价。 */
    protected SeriesQuirks.Pricing pricingOverride() {
        return pricingOverride;
    }

    @Override
    public String getDisplayName() {
        return displayName != null ? displayName : getModelName();
    }

    protected boolean shouldSendReasoningContentInRequestHistory() {
        return false;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools).toString(),
                MediaType.parse("application/json")
        );

        Request.Builder request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .post(body);
        customizeRequest(request);
        Request builtRequest = request.build();

        try (Response response = SHARED_HTTP_CLIENT.newCall(builtRequest).execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                throw new IOException("API请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBodyObj == null) {
                throw new IOException("API返回空响应体");
            }

            BufferedSource source = responseBodyObj.source();
            String role = "assistant";
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
            int inputTokens = 0;
            int outputTokens = 0;
            int cachedInputTokens = 0;
            int cacheCreationInputTokens = 0;
            String finishReason = null;

            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }

                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }

                JsonNode root = mapper.readTree(payload);
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                    cachedInputTokens = parseCachedInputTokens(usage, cachedInputTokens);
                    cacheCreationInputTokens = parseCacheCreationTokens(usage, cacheCreationInputTokens);
                }

                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }

                JsonNode choice = choices.get(0);
                // finish_reason 在每个 chunk 都可能为 null，只有结束 chunk 才有值；保留最后一个非空值。
                String chunkFinish = choice.path("finish_reason").asText("");
                if (!chunkFinish.isEmpty() && !"null".equals(chunkFinish)) {
                    finishReason = chunkFinish;
                }
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode() || delta.isNull()) {
                    delta = choice.path("message");
                }
                if (delta.isMissingNode() || delta.isNull()) {
                    continue;
                }

                String deltaRole = delta.path("role").asText("");
                if (!deltaRole.isEmpty()) {
                    role = deltaRole;
                }

                String reasoningDelta = extractReasoningDelta(delta);
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    streamListener.onReasoningDelta(reasoningDelta);
                }

                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    streamListener.onContentDelta(contentDelta);
                }

                mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));
            }

            return new ChatResponse(
                    role,
                    content.toString(),
                    reasoning.toString(),
                    buildToolCalls(toolAccumulators),
                    inputTokens,
                    outputTokens,
                    cachedInputTokens,
                    finishReason,
                    cacheCreationInputTokens
            );
        }
    }

    private String extractReasoningDelta(JsonNode delta) {
        String reasoningContent = delta.path("reasoning_content").asText("");
        if (!reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        String reasoning = delta.path("reasoning").asText("");
        if (!reasoning.isEmpty()) {
            return reasoning;
        }
        JsonNode details = delta.path("reasoning_details");
        if (details.isArray() && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode detail : details) {
                String text = detail.path("text").asText("");
                if (text.isEmpty()) {
                    text = detail.path("content").asText("");
                }
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        cached = usage.path("input_cache_hit_tokens").asInt(cached);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }
        JsonNode inputDetails = usage.path("input_tokens_details");
        if (!inputDetails.isMissingNode()) {
            cached = inputDetails.path("cached_tokens").asInt(cached);
        }
        return cached;
    }

    /**
     * 解析"写缓存"（首次建立前缀缓存）的 input token。
     *
     * <p>区别于 {@link #parseCachedInputTokens}（命中读缓存）：写缓存量高说明缓存在反复重建、命中差。
     * 多数 OpenAI 兼容平台不返回该字段，缺省即 0；Anthropic 口径用 {@code cache_creation_input_tokens}，
     * 部分平台放在 {@code prompt_tokens_details.cache_creation_tokens}。
     */
    private int parseCacheCreationTokens(JsonNode usage, int fallback) {
        int created = usage.path("cache_creation_input_tokens").asInt(fallback);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            created = promptDetails.path("cache_creation_tokens").asInt(created);
        }
        return created;
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", getModel());
        requestBody.put("stream", true);
        // 配了 maxTokens 才发 max_tokens，控制单次输出上限，避免被平台的小默认值截断
        if (maxTokens > 0) {
            requestBody.put("max_tokens", maxTokens);
        }
        // 配了 temperature（>=0）才发，否则用平台默认
        if (temperature >= 0) {
            requestBody.put("temperature", temperature);
        }
        // 平台特有透传参数（enable_thinking / reasoning_effort 等），按类型塞入；
        // 在 customizeRequestBody 之前发，子类钩子仍可覆盖。
        if (extraParams != null) {
            for (java.util.Map.Entry<String, Object> e : extraParams.entrySet()) {
                requestBody.putPOJO(e.getKey(), e.getValue());
            }
        }

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            appendMessageContent(msgNode, msg);
            if (shouldSendReasoningContentInRequestHistory()
                    && msg.reasoningContent() != null
                    && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty() && supportsTools()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }
        customizeRequestBody(requestBody);
        return requestBody;
    }

    protected void customizeRequestBody(ObjectNode requestBody) {
    }

    /** 请求级钩子：子类/quirks 可往 HTTP 请求加自定义 header（如讯飞 MaaS 的 lora_id）。默认 no-op。 */
    protected void customizeRequest(Request.Builder request) {
    }

    private void appendMessageContent(ObjectNode msgNode, Message msg) {
        if (!msg.hasContentParts()) {
            msgNode.put("content", msg.content());
            return;
        }

        ArrayNode contentArray = msgNode.putArray("content");
        for (LlmClient.ContentPart part : msg.contentParts()) {
            if (part == null) {
                continue;
            }
            if (part.isText()) {
                if (part.text() != null && !part.text().isBlank()) {
                    ObjectNode textNode = contentArray.addObject();
                    textNode.put("type", "text");
                    textNode.put("text", part.text());
                }
                continue;
            }
            if (part.isImage()) {
                String imageUrl = toImageUrl(part);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                ObjectNode imageNode = contentArray.addObject();
                imageNode.put("type", "image_url");
                ObjectNode imageUrlNode = imageNode.putObject("image_url");
                imageUrlNode.put("url", imageUrl);
            }
        }

        if (contentArray.isEmpty()) {
            msgNode.put("content", msg.content());
        }
    }

    protected String toImageUrl(LlmClient.ContentPart part) {
        if ("image_url".equals(part.type())) {
            return part.imageUrl();
        }
        if ("image_base64".equals(part.type())) {
            String mimeType = part.mimeType() == null || part.mimeType().isBlank() ? "image/png" : part.mimeType();
            return "data:" + mimeType + ";base64," + part.imageBase64();
        }
        return null;
    }

    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }

        for (JsonNode tc : toolCallsNode) {
            int index = tc.path("index").asInt(accumulators.size());
            while (accumulators.size() <= index) {
                accumulators.add(new ToolCallAccumulator());
            }

            ToolCallAccumulator acc = accumulators.get(index);
            String id = tc.path("id").asText("");
            if (!id.isEmpty()) {
                acc.id = id;
            }

            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");
            if (!name.isEmpty()) {
                acc.name.append(name);
            }
            String arguments = function.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                acc.arguments.append(arguments);
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.id == null || acc.id.isBlank()) {
                continue;
            }
            toolCalls.add(new ToolCall(
                    acc.id,
                    new ToolCall.Function(acc.name.toString(), acc.arguments.toString())
            ));
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }

    private static final class ToolCallAccumulator {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
