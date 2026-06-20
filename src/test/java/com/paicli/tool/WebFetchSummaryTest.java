package com.paicli.tool;

import com.paicli.llm.LlmClient;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * web_fetch 的 AI 摘要分支（summarizeFetchedContent）。
 * 直接测摘要逻辑（抓取/提正文已被 WebFetcherTest 覆盖；端到端受 NetworkPolicy 拦 localhost 限制不便）。
 * 验证：摘要成功返回浓缩、空响应/异常降级返 null（让上层退回原文截断）、未注入 client 时也降级。
 */
class WebFetchSummaryTest {

    @Test
    void shouldReturnSummaryWhenClientWorks() {
        ToolRegistry registry = new ToolRegistry();
        AtomicReference<List<LlmClient.Message>> captured = new AtomicReference<>();
        registry.setWebFetchSummaryClient(new FakeClient(
                new LlmClient.ChatResponse("assistant", "这页讲的是 X，用法是 foo()。", List.of(), 10, 5),
                captured));

        String result = registry.summarizeFetchedContent("一大段网页正文……", "这个 API 怎么用？");

        assertNotNull(result, "应返回摘要");
        assertEquals("这页讲的是 X，用法是 foo()。", result);
        // 正文和 prompt 都应进入喂给二级模型的消息
        String userMsg = captured.get().get(1).content();
        assertTrue(userMsg.contains("一大段网页正文"), "正文应进入摘要输入");
        assertTrue(userMsg.contains("这个 API 怎么用"), "prompt 应进入摘要输入");
    }

    @Test
    void shouldReturnNullWhenResponseBlank() {
        ToolRegistry registry = new ToolRegistry();
        registry.setWebFetchSummaryClient(new FakeClient(
                new LlmClient.ChatResponse("assistant", "   ", List.of(), 0, 0), null));
        assertNull(registry.summarizeFetchedContent("正文", "问题"),
                "空响应应返回 null 让上层降级");
    }

    @Test
    void shouldReturnNullWhenClientThrows() {
        ToolRegistry registry = new ToolRegistry();
        registry.setWebFetchSummaryClient(new ThrowingClient());
        assertNull(registry.summarizeFetchedContent("正文", "问题"),
                "client 抛异常应返回 null 让上层降级（不崩）");
    }

    @Test
    void shouldTruncateOverlongContentBeforeSummarizing() {
        ToolRegistry registry = new ToolRegistry();
        AtomicReference<List<LlmClient.Message>> captured = new AtomicReference<>();
        registry.setWebFetchSummaryClient(new FakeClient(
                new LlmClient.ChatResponse("assistant", "摘要", List.of(), 1, 1), captured));

        // 远超 MAX_SUMMARY_INPUT_CHARS(100K) 的正文
        String huge = "x".repeat(150_000);
        registry.summarizeFetchedContent(huge, "概括");

        String userMsg = captured.get().get(1).content();
        assertTrue(userMsg.contains("正文过长已截断"), "超长正文应被截断后再喂给模型: 长度=" + userMsg.length());
        assertTrue(userMsg.length() < 130_000, "截断后用户消息不应包含全部 15 万字符");
    }

    // ===== 测试桩 =====

    private static final class FakeClient implements LlmClient {
        private final ChatResponse response;
        private final AtomicReference<List<Message>> captured;

        FakeClient(ChatResponse response, AtomicReference<List<Message>> captured) {
            this.response = response;
            this.captured = captured;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            if (captured != null) {
                captured.set(messages);
            }
            return response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "fake-summary-model";
        }

        @Override
        public String getProviderName() {
            return "fake";
        }
    }

    private static final class ThrowingClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            throw new IOException("模拟摘要模型调用失败");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "throwing-model";
        }

        @Override
        public String getProviderName() {
            return "fake";
        }
    }
}
