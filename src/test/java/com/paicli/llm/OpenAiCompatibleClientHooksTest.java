package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证从作者最终版（XfyunMaaSClient）吸收的两个钩子：
 * 1. supportsTools=false → 不发 tools 字段
 * 2. extraHeaders（config 注入）→ 加到 HTTP header（如讯飞 lora_id）
 */
class OpenAiCompatibleClientHooksTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockResponse okResponse() {
        return new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}

                        data: [DONE]

                        """);
    }

    private static LlmClient.Tool sampleTool() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        return new LlmClient.Tool("read_file", "读文件", params);
    }

    @Test
    void seriesWithSupportsToolsFalseOmitsToolsField() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(okResponse());
            // qwen 系列 supportsTools=false（对应作者 XfyunMaaSClient）
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    "k", "m", server.url("/v1").toString(), "qwen", 0,
                    SeriesQuirks.forSeries("qwen"));
            assertFalse(client.supportsTools(), "qwen 系列应声明不支持工具");

            client.chat(List.of(LlmClient.Message.user("hi")), List.of(sampleTool()));

            RecordedRequest request = server.takeRequest();
            JsonNode root = MAPPER.readTree(request.getBody().readUtf8());
            assertTrue(root.path("tools").isMissingNode(),
                    "supportsTools=false 时不应发 tools 字段");
        }
    }

    @Test
    void normalSeriesStillSendsToolsField() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(okResponse());
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    "k", "m", server.url("/v1").toString(), "deepseek", 0,
                    SeriesQuirks.forSeries("deepseek"));
            assertTrue(client.supportsTools(), "deepseek 系列应支持工具");

            client.chat(List.of(LlmClient.Message.user("hi")), List.of(sampleTool()));

            RecordedRequest request = server.takeRequest();
            JsonNode root = MAPPER.readTree(request.getBody().readUtf8());
            assertFalse(root.path("tools").isMissingNode(), "正常系列应发 tools");
            assertEquals("read_file",
                    root.path("tools").get(0).path("function").path("name").asText());
        }
    }

    @Test
    void extraHeadersAreSentOnRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(okResponse());
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    "k", "m", server.url("/v1").toString(), "qwen", 0,
                    SeriesQuirks.forSeries("qwen"));
            // config 注入的自定义 header（如讯飞 lora_id）
            client.setExtraHeaders(Map.of("lora_id", "abc-123"));

            client.chat(List.of(LlmClient.Message.user("hi")), null);

            RecordedRequest request = server.takeRequest();
            assertEquals("abc-123", request.getHeader("lora_id"),
                    "extraHeaders 应被加到 HTTP 请求头");
            // 标准 header 不受影响
            assertEquals("Bearer k", request.getHeader("Authorization"));
        }
    }

    @Test
    void noExtraHeadersByDefault() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(okResponse());
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    "k", "m", server.url("/v1").toString(), "glm", 0,
                    SeriesQuirks.forSeries("glm"));

            client.chat(List.of(LlmClient.Message.user("hi")), null);

            RecordedRequest request = server.takeRequest();
            assertNull(request.getHeader("lora_id"), "未注入 extraHeaders 时不应有自定义 header");
        }
    }
}
