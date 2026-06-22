package com.paicli.session;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    private SessionStore store() {
        return new SessionStore(tempDir);
    }

    @Test
    void writeThenLoadRoundTripsPlainMessages() {
        SessionStore store = store();
        String project = "/proj/a";
        String sid = SessionStore.generateSessionId(1000L);

        store.appendMessage(project, sid,
                SessionMessageRecord.from(LlmClient.Message.user("你好"), 1000L));
        store.appendMessage(project, sid,
                SessionMessageRecord.from(LlmClient.Message.assistant("你好，有什么可以帮你"), 1001L));

        List<SessionMessageRecord> loaded = store.loadSession(project, sid);
        assertEquals(2, loaded.size());
        assertEquals("user", loaded.get(0).role());
        assertEquals("你好", loaded.get(0).content());
        assertEquals("assistant", loaded.get(1).role());
    }

    @Test
    void preservesToolCallsAndToolCallIdAcrossRoundTrip() {
        SessionStore store = store();
        String project = "/proj/tools";
        String sid = SessionStore.generateSessionId(2000L);

        // assistant 发起一个 tool_call
        LlmClient.Message assistantWithToolCall = LlmClient.Message.assistant(
                null,
                List.of(new LlmClient.ToolCall(
                        "call_42",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}")))
        );
        // tool 消息回灌结果，toolCallId 必须配对
        LlmClient.Message toolResult = LlmClient.Message.tool("call_42", "文件内容: ...");

        store.appendMessage(project, sid, SessionMessageRecord.from(assistantWithToolCall, 2000L));
        store.appendMessage(project, sid, SessionMessageRecord.from(toolResult, 2001L));

        List<SessionMessageRecord> loaded = store.loadSession(project, sid);
        assertEquals(2, loaded.size());

        // 还原成 LlmClient.Message，验证 tool_calls / toolCallId 没丢
        LlmClient.Message restoredAssistant = loaded.get(0).toMessage();
        assertNotNull(restoredAssistant.toolCalls());
        assertEquals(1, restoredAssistant.toolCalls().size());
        assertEquals("call_42", restoredAssistant.toolCalls().get(0).id());
        assertEquals("read_file", restoredAssistant.toolCalls().get(0).function().name());
        assertEquals("{\"path\":\"pom.xml\"}", restoredAssistant.toolCalls().get(0).function().arguments());

        LlmClient.Message restoredTool = loaded.get(1).toMessage();
        assertEquals("call_42", restoredTool.toolCallId());
    }

    @Test
    void sessionsAreIsolatedByProject() {
        SessionStore store = store();
        String sidA = SessionStore.generateSessionId(3000L);
        String sidB = SessionStore.generateSessionId(3001L);
        store.appendMessage("/proj/A", sidA, SessionMessageRecord.from(LlmClient.Message.user("a"), 3000L));
        store.appendMessage("/proj/B", sidB, SessionMessageRecord.from(LlmClient.Message.user("b"), 3001L));

        assertEquals(1, store.listSessions("/proj/A").size());
        assertEquals(1, store.listSessions("/proj/B").size());
        assertEquals(sidA, store.listSessions("/proj/A").get(0).sessionId());
    }

    @Test
    void listSessionsHasRealTitleAndCount() {
        SessionStore store = store();
        String project = "/proj/meta";
        String sid = SessionStore.generateSessionId(4000L);
        store.appendMessage(project, sid,
                SessionMessageRecord.from(LlmClient.Message.user("帮我重构登录模块"), 4000L));
        store.appendMessage(project, sid,
                SessionMessageRecord.from(LlmClient.Message.assistant("好的"), 4001L));

        List<SessionStore.SessionMeta> metas = store.listSessions(project);
        assertEquals(1, metas.size());
        assertEquals("帮我重构登录模块", metas.get(0).title());   // 真实标题=首条 user 消息
        assertEquals(2, metas.get(0).messageCount());            // 真实计数
    }

    @Test
    void latestSessionIdReturnsMostRecent() {
        SessionStore store = store();
        String project = "/proj/latest";
        assertNull(store.latestSessionId(project));   // 空项目

        String sid = SessionStore.generateSessionId(5000L);
        store.appendMessage(project, sid, SessionMessageRecord.from(LlmClient.Message.user("hi"), 5000L));
        assertEquals(sid, store.latestSessionId(project));
    }

    @Test
    void loadMissingSessionReturnsEmpty() {
        assertTrue(store().loadSession("/proj/none", "session_does_not_exist").isEmpty());
    }

    @Test
    void costLedgerSidecarRoundTrips() {
        SessionStore store = store();
        String project = "/proj/cost";
        String sid = SessionStore.generateSessionId(6000L);

        com.paicli.cost.CostLedger ledger = new com.paicli.cost.CostLedger();
        ledger.record(client("glm-x", new com.paicli.llm.SeriesQuirks.Pricing(2.0, 0.5, 8.0)),
                1_000_000, 500_000, 200_000, 100_000);
        store.saveCostLedger(project, sid, ledger);

        com.paicli.cost.CostLedger loaded = store.loadCostLedger(project, sid);
        assertEquals(1_000_000, loaded.totalInputTokens());
        assertEquals(500_000, loaded.totalOutputTokens());
        assertEquals(200_000, loaded.totalCacheReadTokens());
        assertEquals(100_000, loaded.totalCacheCreationTokens());
        assertEquals(ledger.totalCostCny(), loaded.totalCostCny(), 1e-9);
    }

    @Test
    void loadMissingCostLedgerReturnsEmptyNotNull() {
        com.paicli.cost.CostLedger loaded = store().loadCostLedger("/proj/none", "session_x");
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void emptyCostLedgerNotPersisted() {
        SessionStore store = store();
        store.saveCostLedger("/proj/empty", "session_e", new com.paicli.cost.CostLedger());
        assertTrue(store.loadCostLedger("/proj/empty", "session_e").isEmpty());
    }

    @Test
    void aggregateProjectCostMergesAllSessions() {
        SessionStore store = store();
        String project = "/proj/agg";
        com.paicli.llm.SeriesQuirks.Pricing pricing = new com.paicli.llm.SeriesQuirks.Pricing(2.0, 0.5, 8.0);

        com.paicli.cost.CostLedger s1 = new com.paicli.cost.CostLedger();
        s1.record(client("m", pricing), 1_000_000, 0, 0, 0);
        store.saveCostLedger(project, SessionStore.generateSessionId(7000L), s1);

        com.paicli.cost.CostLedger s2 = new com.paicli.cost.CostLedger();
        s2.record(client("m", pricing), 500_000, 0, 0, 0);
        store.saveCostLedger(project, SessionStore.generateSessionId(7001L), s2);

        com.paicli.cost.CostLedger total = store.aggregateProjectCost(project);
        assertEquals(1_500_000, total.totalInputTokens());
        assertEquals(1, total.models().size(), "同模型应合并为一桶");
    }

    private static com.paicli.llm.OpenAiCompatibleClient client(
            String model, com.paicli.llm.SeriesQuirks.Pricing pricing) {
        com.paicli.llm.SeriesQuirks quirks = new com.paicli.llm.SeriesQuirks(
                model, "https://example.invalid/v1", 128_000, true, "test-cache",
                false, null, null, pricing, true);
        return new com.paicli.llm.OpenAiCompatibleClient(
                "k", model, "https://example.invalid/v1", model, 0, quirks);
    }
}
