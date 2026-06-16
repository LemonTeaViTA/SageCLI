package com.paicli.agent;

import com.paicli.llm.OpenAiCompatibleClient;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRestoreHistoryTest {

    /** 恢复后第一条仍是 system，且恢复的消息（含 tool_calls）原样保留。 */
    @Test
    void restoreKeepsSystemAndPreservesToolCalls() {
        Agent agent = new Agent(new SilentGLMClient());

        List<LlmClient.Message> restored = List.of(
                LlmClient.Message.user("读取 pom.xml"),
                LlmClient.Message.assistant(null, List.of(new LlmClient.ToolCall(
                        "call_1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}")))),
                LlmClient.Message.tool("call_1", "文件内容: ..."),
                LlmClient.Message.assistant("pom.xml 有 120 行")
        );

        agent.restoreConversationHistory(restored);

        List<LlmClient.Message> history = agent.getConversationHistory();
        // 第 0 条永远是 system
        assertEquals("system", history.get(0).role());
        // 4 条恢复消息 + 1 条 system
        assertEquals(5, history.size());

        // tool_calls 配对完整
        LlmClient.Message assistantMsg = history.get(2);
        assertEquals("assistant", assistantMsg.role());
        assertNotNull(assistantMsg.toolCalls());
        assertEquals("call_1", assistantMsg.toolCalls().get(0).id());

        LlmClient.Message toolMsg = history.get(3);
        assertEquals("tool", toolMsg.role());
        assertEquals("call_1", toolMsg.toolCallId());
    }

    /** 恢复时丢弃恢复列表里的 system 消息（用当前 agent 的 system，不叠加）。 */
    @Test
    void restoreDropsSystemFromRestoredList() {
        Agent agent = new Agent(new SilentGLMClient());
        List<LlmClient.Message> restored = List.of(
                LlmClient.Message.system("旧的 system 提示"),
                LlmClient.Message.user("hi")
        );

        agent.restoreConversationHistory(restored);

        List<LlmClient.Message> history = agent.getConversationHistory();
        // 只保留 1 条 system（当前的）+ 1 条 user，旧 system 被丢弃
        assertEquals(2, history.size());
        assertEquals("system", history.get(0).role());
        assertEquals("user", history.get(1).role());
        assertTrue(history.stream().filter(m -> "system".equals(m.role())).count() == 1);
    }

    /** 空列表是安全的 no-op。 */
    @Test
    void restoreEmptyIsNoOp() {
        Agent agent = new Agent(new SilentGLMClient());
        int before = agent.getConversationHistory().size();
        agent.restoreConversationHistory(List.of());
        assertEquals(before, agent.getConversationHistory().size());
    }

    /** 不联网的 stub：任何 chat 调用都抛异常（本测试不触发 chat）。 */
    private static final class SilentGLMClient extends OpenAiCompatibleClient {
        private SilentGLMClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            throw new IOException("本测试不应调用 LLM");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            throw new IOException("本测试不应调用 LLM");
        }
    }
}
