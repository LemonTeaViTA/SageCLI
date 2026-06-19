package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.llm.OpenAiCompatibleClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dispatch_agent 工具：派生只读检索子 agent，只回传结论。验证工具注册、缺 LlmClient 的降级、
 * 结论回传，以及最关键的——子 agent 工具白名单（防递归 + 只读）。
 */
class DispatchAgentToolTest {

    @Test
    void shouldRegisterDispatchAgentTool() {
        ToolRegistry registry = new ToolRegistry();
        boolean present = registry.getToolDefinitions().stream()
                .anyMatch(t -> t.name().equals("dispatch_agent"));
        assertTrue(present, "dispatch_agent 应被注册为工具");
    }

    @Test
    void shouldReturnFriendlyErrorWhenLlmClientMissing() {
        // 未注入 LlmClient 时不崩，返回友好错误。
        ToolRegistry registry = new ToolRegistry();
        String result = registry.executeTool("dispatch_agent",
                "{\"description\":\"找鉴权\",\"prompt\":\"在项目里找鉴权逻辑\"}");
        assertTrue(result.contains("未注入 LlmClient") || result.contains("不可用"),
                "缺 LlmClient 应返回友好错误: " + result);
    }

    @Test
    void shouldReturnSubAgentConclusion() {
        ToolRegistry registry = new ToolRegistry();
        CapturingClient client = new CapturingClient(
                new LlmClient.ChatResponse("assistant", "鉴权在 AuthFilter.java:42，走 JWT。", "", null, 10, 5));
        registry.setAgentDispatchLlmClient(client);

        String result = registry.executeTool("dispatch_agent",
                "{\"description\":\"找鉴权\",\"prompt\":\"在项目里找鉴权逻辑在哪\"}");

        assertTrue(result.contains("AuthFilter.java:42"), "应回传子 agent 的结论文本: " + result);
        assertTrue(result.contains("检索子 agent 结论"), "应带结论标识包装: " + result);
    }

    @Test
    void subAgentShouldOnlySeeReadOnlyInvestigationTools() {
        // 最关键：派生的检索子 agent 拿到的工具集必须只含只读检索工具，
        // 不含 dispatch_agent 本身（防递归）、不含写/执行工具（只读）。
        ToolRegistry registry = new ToolRegistry();
        CapturingClient client = new CapturingClient(
                new LlmClient.ChatResponse("assistant", "完成", "", null, 1, 1));
        registry.setAgentDispatchLlmClient(client);

        registry.executeTool("dispatch_agent",
                "{\"description\":\"探索\",\"prompt\":\"随便找点东西\"}");

        List<LlmClient.Tool> seen = client.capturedTools.get();
        assertNotNull(seen, "子 agent 调 LLM 时应传入工具定义");
        List<String> names = seen.stream().map(LlmClient.Tool::name).toList();

        // 防递归：不能看到 dispatch_agent
        assertFalse(names.contains("dispatch_agent"), "检索子 agent 不应看到 dispatch_agent（防递归）: " + names);
        // 只读：不能看到写/执行/快照工具
        assertFalse(names.contains("write_file"), "不应含 write_file: " + names);
        assertFalse(names.contains("edit_file"), "不应含 edit_file: " + names);
        assertFalse(names.contains("execute_command"), "不应含 execute_command: " + names);
        assertFalse(names.contains("revert_turn"), "不应含 revert_turn: " + names);
        // 检索工具应在
        assertTrue(names.contains("read_file"), "应含 read_file: " + names);
        assertTrue(names.contains("grep_code"), "应含 grep_code: " + names);
        assertTrue(names.contains("glob_files"), "应含 glob_files: " + names);
    }

    /** 捕获 chat() 收到的 tools，并返回预设响应（无 tool_calls，让子 agent 一轮即结束）。 */
    private static final class CapturingClient extends OpenAiCompatibleClient {
        private final ChatResponse response;
        private final AtomicReference<List<Tool>> capturedTools = new AtomicReference<>();

        private CapturingClient(ChatResponse response) {
            super("test-key");
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            capturedTools.compareAndSet(null, tools);
            return response;
        }
    }
}
