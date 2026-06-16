package com.paicli.context;

import com.paicli.llm.LlmClient;

import java.util.logging.Logger;

/**
 * 上下文策略配置。
 *
 * **设计原则**：没有"长 / 短 / 平衡"模式分档。所有参数都是 maxContextWindow 的简单函数，
 * 全模型走同一套行为，只是 window 大小不同导致触发时机和容量不同。
 *
 * 按 window 派生：
 * - 压缩触发：预留摘要输出空间(≤window/4，封顶20k) + 自动压缩缓冲(≤window/8，封顶13k)后触发。
 *   即触发点 = window − 摘要预留 − 缓冲；直接锁"触发时还剩多少绝对 token"，比固定比率更贴本质。
 * - 短期记忆预算 = window × 0.45
 * - 注入到 system prompt 的相关记忆 token 上限 = window × 0.005，封顶 5000
 * - MCP resource 索引注入：window ≥ 32k 才有意义（再小就挤）
 */
public record ContextProfile(
        int maxContextWindow,
        int agentTokenBudget,
        double compressionTriggerRatio,
        int shortTermMemoryBudget,
        int memoryContextTokens,
        boolean mcpResourceIndexEnabled,
        boolean promptCachingSupported,
        String promptCacheMode
) {
    private static final Logger LOGGER = Logger.getLogger(ContextProfile.class.getName());

    /** 摘要输出预留：压缩要调 LLM 生成摘要，摘要输出本身也占空间，按 window/4 预留、封顶 20k。 */
    public static final int MAX_SUMMARY_OUTPUT_RESERVE_TOKENS = 20_000;
    /** 自动压缩缓冲：触发后到真正压完之间还会继续涨，留 window/8、封顶 13k 的余量。 */
    public static final int AUTOCOMPACT_BUFFER_TOKENS = 13_000;
    /** 压缩触发率下限：极小窗口下预留+缓冲占比可能很大，触发率不低于 0.50（仅用于展示）。 */
    public static final double MIN_COMPRESSION_TRIGGER_RATIO = 0.50;
    /** client 报告的窗口异常低（0/未配）时的兜底，对齐 LlmClient.maxContextWindow() 接口默认。 */
    private static final int FALLBACK_WINDOW = 128_000;
    private static final int MCP_RESOURCE_INDEX_MIN_WINDOW = 32_000;

    public static ContextProfile from(LlmClient llmClient) {
        int window = normalizeWindow(llmClient == null ? FALLBACK_WINDOW : llmClient.maxContextWindow(), "llmClient");
        return new ContextProfile(
                window,
                agentBudget(window),
                compressionRatio(window),
                shortTermBudget(window),
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                llmClient != null && llmClient.supportsPromptCaching(),
                llmClient == null ? "none" : llmClient.promptCacheMode()
        );
    }

    public static ContextProfile custom(int contextWindow, int shortTermMemoryBudget) {
        int window = normalizeWindow(contextWindow, "custom");
        int shortTerm = Math.max(1, shortTermMemoryBudget);
        return new ContextProfile(
                window,
                agentBudget(window),
                compressionRatio(window),
                shortTerm,
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                false,
                "none"
        );
    }

    /** 触发压缩的绝对 token 阈值（占用 ≥ 此值即压缩）：直接锁绝对预留，不走"比率×窗口"。 */
    public int compressionTriggerTokens() {
        return autoCompactTriggerTokens(maxContextWindow);
    }

    public String summary() {
        return "window: " + maxContextWindow
                + " | 压缩阈值: " + (int) (compressionTriggerRatio * 100) + "% (" + compressionTriggerTokens() + " tokens)"
                + " | 短期记忆预算: " + shortTermMemoryBudget
                + " | MCP resource 索引: " + (mcpResourceIndexEnabled ? "on" : "off")
                + " | prompt cache: " + promptCacheMode;
    }

    private static int agentBudget(int window) {
        // Agent 单次 run 的 token "软提示"值（仅 /context 展示）；真正的硬限是 AgentBudget.tokenBudget，
        // 默认 Integer.MAX_VALUE（不限）。此值不卡用户，只在 UI 提示"约用到窗口的 80% 时该留意了"。
        return Math.max(4_000, (int) Math.floor(window * 0.8));
    }

    /** client 报告的窗口 ≤ 0（未配/异常）时兜底到接口默认 128k，并出声——否则会被静默放大成"MCP 索引消失"。 */
    private static int normalizeWindow(int reported, String source) {
        if (reported > 0) {
            return reported;
        }
        LOGGER.warning(() -> "上下文窗口未配置或异常(" + reported + ", 来源=" + source
                + ")，兜底为 " + FALLBACK_WINDOW + "；如该模型窗口确实更小请在 config 显式配置 maxContextWindow。");
        return FALLBACK_WINDOW;
    }

    /**
     * 压缩触发率（仅用于展示/summary）：由绝对触发点反推 = autoCompactTriggerTokens / window。
     * 下限 0.50、上限 0.99，避免极端窗口下展示出怪比率。真正驱动压缩的是 {@link #compressionTriggerTokens()}。
     */
    static double compressionRatio(int window) {
        return Math.max(MIN_COMPRESSION_TRIGGER_RATIO,
                Math.min(0.99, autoCompactTriggerTokens(window) / (double) Math.max(1, window)));
    }

    /**
     * 触发自动压缩的绝对 token 数：window − 摘要输出预留 − 自动压缩缓冲。
     * 直接对"触发时还剩多少绝对 token（够做摘要+继续涨的缓冲）"建模，而非用比率近似。
     * 大窗口几乎用满（只留固定预留），小窗口留更多相对余量。
     */
    static int autoCompactTriggerTokens(int window) {
        int safeWindow = Math.max(1_000, window);
        int summaryReserve = Math.min(MAX_SUMMARY_OUTPUT_RESERVE_TOKENS, Math.max(1_000, safeWindow / 4));
        int buffer = Math.min(AUTOCOMPACT_BUFFER_TOKENS, Math.max(1_000, safeWindow / 8));
        int trigger = safeWindow - summaryReserve - buffer;
        return Math.max(1_000, Math.min(safeWindow - 1, trigger));
    }

    private static int shortTermBudget(int window) {
        return Math.max(4_000, (int) Math.floor(window * 0.45));
    }

    private static int memoryContextTokens(int window) {
        return Math.max(500, Math.min(5_000, window / 200));
    }
}
