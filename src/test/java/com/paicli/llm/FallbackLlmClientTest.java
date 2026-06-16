package com.paicli.llm;

import com.paicli.llm.LlmClient.ChatResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackLlmClientTest {

    /** 测试桩：可配置成功/失败、是否先吐字再失败，并记录被调用次数。 */
    private static final class StubClient implements LlmClient {
        final String provider;
        final boolean fail;
        final boolean emitBeforeFail;   // 失败前是否先吐 content delta
        final String reply;
        final AtomicInteger calls = new AtomicInteger();

        StubClient(String provider, boolean fail, boolean emitBeforeFail, String reply) {
            this.provider = provider; this.fail = fail;
            this.emitBeforeFail = emitBeforeFail; this.reply = reply;
        }

        @Override public ChatResponse chat(List<Message> m, List<Tool> t) throws IOException {
            return chat(m, t, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> m, List<Tool> t, StreamListener l) throws IOException {
            calls.incrementAndGet();
            if (fail) {
                if (emitBeforeFail) l.onContentDelta("半句");  // 先吐字再失败
                throw new IOException(provider + " 模拟失败");
            }
            l.onContentDelta(reply);
            return new ChatResponse("assistant", reply, null, 1, 1);
        }
        @Override public String getModelName() { return provider + "-model"; }
        @Override public String getProviderName() { return provider; }
    }

    @Test
    void primarySuccessDoesNotTouchFallback() throws Exception {
        StubClient primary = new StubClient("p", false, false, "主答");
        StubClient fb = new StubClient("f", false, false, "备答");
        FallbackLlmClient c = new FallbackLlmClient(primary, List.of(fb));

        ChatResponse r = c.chat(List.of(), null, LlmClient.StreamListener.NO_OP);
        assertEquals("主答", r.content());
        assertEquals(1, primary.calls.get());
        assertEquals(0, fb.calls.get());   // 备份没被碰
    }

    @Test
    void failsOverWhenPrimaryFailsBeforeStreaming() throws Exception {
        StubClient primary = new StubClient("p", true, false, null);   // 吐字前失败
        StubClient fb = new StubClient("f", false, false, "备答");
        FallbackLlmClient c = new FallbackLlmClient(primary, List.of(fb));

        ChatResponse r = c.chat(List.of(), null, LlmClient.StreamListener.NO_OP);
        assertEquals("备答", r.content());   // 切到备份成功
        assertEquals(1, primary.calls.get());
        assertEquals(1, fb.calls.get());
    }

    @Test
    void doesNotFailOverWhenPrimaryAlreadyStreamed() {
        StubClient primary = new StubClient("p", true, true, null);    // 先吐字再失败
        StubClient fb = new StubClient("f", false, false, "备答");
        FallbackLlmClient c = new FallbackLlmClient(primary, List.of(fb));

        // 已吐字 → 不切备份，抛原错
        assertThrows(IOException.class, () -> c.chat(List.of(), null, LlmClient.StreamListener.NO_OP));
        assertEquals(0, fb.calls.get());   // 备份没被碰
    }

    @Test
    void throwsLastWhenAllFail() {
        StubClient primary = new StubClient("p", true, false, null);
        StubClient fb = new StubClient("f", true, false, null);
        FallbackLlmClient c = new FallbackLlmClient(primary, List.of(fb));

        IOException e = assertThrows(IOException.class,
                () -> c.chat(List.of(), null, LlmClient.StreamListener.NO_OP));
        assertTrue(e.getMessage().contains("f"));   // 抛最后一个（备份）的异常
        assertEquals(1, primary.calls.get());
        assertEquals(1, fb.calls.get());
    }

    @Test
    void identityMethodsDelegateToPrimary() {
        StubClient primary = new StubClient("p", false, false, "x");
        FallbackLlmClient c = new FallbackLlmClient(primary, List.of());
        assertEquals("p", c.getProviderName());
        assertEquals("p-model", c.getModelName());
    }

    @Test
    void deltaForwardedToRealListener() throws Exception {
        StubClient primary = new StubClient("p", true, false, null);
        StubClient fb = new StubClient("f", false, false, "备答内容");
        FallbackLlmClient c = new FallbackLlmClient(primary, List.of(fb));

        StringBuilder seen = new StringBuilder();
        c.chat(List.of(), null, new LlmClient.StreamListener() {
            @Override public void onContentDelta(String d) { seen.append(d); }
        });
        assertEquals("备答内容", seen.toString());   // 备份的输出透传到了真实 listener
    }
}
