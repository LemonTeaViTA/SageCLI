package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.llm.OpenAiCompatibleClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 prefix-cache 稳定性的两个前提：
 * 1. system prompt（conversationHistory[0]）跨轮字节稳定（memoryContext 不再每轮塞进去）。
 * 2. 工具定义顺序稳定（按名排序，不受 ConcurrentHashMap 迭代顺序影响）。
 */
class AgentPrefixStabilityTest {

    @Test
    void systemPromptStaysIdenticalAcrossTurns() {
        CapturingClient client = new CapturingClient();
        Agent agent = new Agent(client);

        agent.run("第一轮问题：帮我看看 A 模块");
        agent.run("第二轮完全不同的问题：分析 B 的性能");

        assertTrue(client.systemPrompts.size() >= 2, "应至少捕获两轮 system prompt");
        String first = client.systemPrompts.get(0);
        String second = client.systemPrompts.get(client.systemPrompts.size() - 1);
        assertEquals(first, second, "system prompt 跨轮应字节一致以命中 prefix cache");
        // memoryContext 不应出现在 system prompt 里
        assertTrue(!first.contains("## 相关长期记忆"), "记忆上下文不应再注入 system prompt");
    }

    @Test
    void toolDefinitionsOrderIsStable() {
        Agent agent = new Agent(new CapturingClient());
        List<String> order1 = agent.getToolRegistry().getToolDefinitions().stream()
                .map(LlmClient.Tool::name).toList();
        List<String> order2 = agent.getToolRegistry().getToolDefinitions().stream()
                .map(LlmClient.Tool::name).toList();

        assertEquals(order1, order2, "工具定义顺序应跨调用稳定");
        List<String> sorted = new ArrayList<>(order1);
        sorted.sort(String::compareTo);
        assertEquals(sorted, order1, "工具定义应按名称排序（确定性顺序）");
    }

    /** 捕获每次 chat 收到的 system prompt 文本；不产生工具调用，让 run() 一轮即结束。 */
    private static final class CapturingClient extends OpenAiCompatibleClient {
        private final List<String> systemPrompts = new ArrayList<>();

        private CapturingClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            for (Message m : messages) {
                if ("system".equals(m.role())) {
                    systemPrompts.add(m.content());
                    break;
                }
            }
            return new ChatResponse("assistant", "好的，已完成。", null, 10, 5);
        }
    }
}
