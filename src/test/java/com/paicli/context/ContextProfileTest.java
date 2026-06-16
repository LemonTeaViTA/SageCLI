package com.paicli.context;

import com.paicli.llm.OpenAiCompatibleClient;
import com.paicli.llm.SeriesQuirks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ContextProfile 的派生公式。
 *
 * 设计原则：没有"长 / 短模式"分档，所有参数都是 maxContextWindow 的简单函数。
 * 压缩触发按绝对预留派生：触发点 = window − 摘要预留(≤window/4,封顶20k) − 缓冲(≤window/8,封顶13k)。
 * 比率仅用于展示，由绝对触发点反推。
 */
class ContextProfileTest {

    @Test
    void glmDerivesParamsFrom200kWindow() {
        ContextProfile profile = ContextProfile.from(new OpenAiCompatibleClient(
                "test-key", "glm-5.1", "https://open.bigmodel.cn/api/paas/v4", "glm", 0, SeriesQuirks.forSeries("glm")));

        assertEquals(200_000, profile.maxContextWindow());
        assertEquals(160_000, profile.agentTokenBudget());                  // 200k × 0.8（软提示）
        // 200k − 摘要预留20k(min(20k,50k)) − 缓冲13k(min(13k,25k)) = 167000
        assertEquals(167_000, profile.compressionTriggerTokens());
        assertEquals(0.835, profile.compressionTriggerRatio(), 0.001);       // 167000/200000
        assertEquals(90_000, profile.shortTermMemoryBudget());              // 200k × 0.45
        assertTrue(profile.mcpResourceIndexEnabled());                      // window ≥ 32k
        assertTrue(profile.promptCachingSupported());
    }

    @Test
    void deepSeekDerivesParamsFromMillionWindow() {
        ContextProfile profile = ContextProfile.from(new OpenAiCompatibleClient(
                "test-key", "deepseek-v4-flash", "https://api.deepseek.com", "deepseek", 0, SeriesQuirks.forSeries("deepseek")));

        assertEquals(1_000_000, profile.maxContextWindow());
        assertEquals(800_000, profile.agentTokenBudget());                  // 1M × 0.8（软提示）
        // 1M − 摘要预留20k(封顶) − 缓冲13k(封顶) = 967000，大窗口几乎用满
        assertEquals(967_000, profile.compressionTriggerTokens());
        assertEquals(0.967, profile.compressionTriggerRatio(), 0.001);       // 967000/1000000
        assertEquals(450_000, profile.shortTermMemoryBudget());             // 1M × 0.45
        assertEquals("automatic-prefix-cache", profile.promptCacheMode());
        assertTrue(profile.mcpResourceIndexEnabled());
    }

    @Test
    void autoCompactReservesAbsoluteTokens() {
        // 小窗口留更多相对余量，大窗口几乎用满（直接锁绝对预留，而非比率近似）
        assertEquals(5_000, ContextProfile.autoCompactTriggerTokens(8_000));    // 8k−2k−1k
        assertEquals(95_000, ContextProfile.autoCompactTriggerTokens(128_000)); // 128k−20k−13k
        assertEquals(167_000, ContextProfile.autoCompactTriggerTokens(200_000));
        assertEquals(967_000, ContextProfile.autoCompactTriggerTokens(1_000_000));
        // 单调：窗口越大，绝对触发点越大；比率也随窗口增大（越晚压）
        assertTrue(ContextProfile.compressionRatio(32_000) < ContextProfile.compressionRatio(128_000));
        assertTrue(ContextProfile.compressionRatio(128_000) < ContextProfile.compressionRatio(1_000_000));
    }

    @Test
    void compressionTriggerIsAlwaysOnRegardlessOfWindowSize() {
        // 关键：长 window 也必须可触发压缩，没有"长模式不压缩"的硬开关
        for (int window : new int[]{8_000, 32_000, 128_000, 200_000, 1_000_000}) {
            ContextProfile profile = ContextProfile.custom(window, 1_000);
            assertTrue(profile.compressionTriggerRatio() > 0,
                    "window=" + window + " 必须有正的触发率");
            assertTrue(profile.compressionTriggerTokens() > 0,
                    "window=" + window + " 必须有正的触发 token 数");
            // 触发点必须小于窗口本身（留得出预留+缓冲）
            assertTrue(profile.compressionTriggerTokens() < window,
                    "window=" + window + " 触发点应小于窗口");
        }
    }

    @Test
    void smallWindowDisablesMcpResourceIndexInjection() {
        // window < 32k 时索引注入不值当，关闭
        ContextProfile profile = ContextProfile.custom(16_000, 4_000);
        assertFalse(profile.mcpResourceIndexEnabled());
    }

    @Test
    void customProfileRespectsExplicitShortTermBudget() {
        ContextProfile profile = ContextProfile.custom(128_000, 40);

        assertEquals(40, profile.shortTermMemoryBudget());
        // custom 也按绝对预留派生（128k → 95000 → 0.742）
        assertEquals(95_000, profile.compressionTriggerTokens());
        assertEquals(0.742, profile.compressionTriggerRatio(), 0.001);
    }

    @Test
    void zeroWindowFallsBackTo128kNotSilentlyTo8k() {
        // 债3 修复：client 报 0/未配窗口时兜底到 128k（接口默认），而非 8k——
        // 8k < 32k 会静默关掉 MCP 索引。兜底到 128k 后索引保持开启。
        ContextProfile profile = ContextProfile.custom(0, 1_000);
        assertEquals(128_000, profile.maxContextWindow());
        assertTrue(profile.mcpResourceIndexEnabled());
    }

    @Test
    void nullClientFallsBackToReasonableDefault() {
        ContextProfile profile = ContextProfile.from(null);
        assertEquals(128_000, profile.maxContextWindow());
        assertEquals(95_000, profile.compressionTriggerTokens());           // 128k 派生
        assertFalse(profile.promptCachingSupported());
    }
}
