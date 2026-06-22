package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 压缩 ReAct 主循环里的 {@code conversationHistory}（即 {@code List<LlmClient.Message>}）。
 *
 * 与 {@link ContextCompressor} 的区别：
 * - {@code ContextCompressor} 压的是 {@link ConversationMemory}（SageCLI 的短期记忆条目）
 * - 本类压的是 Agent 实际发给 LLM 的消息列表
 *
 * 第 3 期 Memory 设计假设"LLM 调用从 shortTermMemory 重建消息"，但实际 Agent 直接维护
 * conversationHistory，与 shortTermMemory 并行。两个度量错位导致旧版压缩从未真正缩短
 * 即将发给 LLM 的 token——本类是在 Agent.run 主循环里"调 LLM 前评估并压缩"的补丁。
 *
 * 算法：
 * 1. 估算 conversationHistory 当前 token，未达 trigger 直接返回 false
 * 2. 找出所有 user message 的索引；保留最近 retainRecentRounds 个 user 起算的尾部
 * 3. 把 system 之后、splitIdx 之前的全部消息喂给 LLM 摘要
 * 4. 重建：[system] + [user("[已压缩的历史对话摘要]\n" + summary)] +
 *         [assistant("好的，已了解上下文。请继续。")] + [尾部保留消息]
 *
 * 关键约束：分割点必然落在 user message 边界，避免切断 tool_call / tool_result 的成对协议。
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;

    // 第一档"清旧工具结果正文"保留的最近工具结果条数。工具结果（read_file/grep/execute_command 等）
    // 正文最占 token，且越老越无关；最近 N 条留全，更老的换占位符。对标 CC 的"清空旧工具结果正文"。
    private static final int DEFAULT_RETAIN_RECENT_TOOL_RESULTS = 5;
    // 占位符：替换被清理的工具结果正文。保留 tool_call_id 配对不动，只换 content。
    private static final String CLEARED_TOOL_RESULT_PLACEHOLDER =
            "[旧工具结果正文已清除以节省上下文；如仍需要请重新调用对应工具]";

    // 结构化摘要 prompt（对照 CC 的"防漂移"设计）：松散摘要会丢任务边界，导致压缩后跑偏。
    // 两条关键约束：① 所有用户消息逐条保留（用户的每次诉求都是任务边界，不能合并/省略）；
    // ② "下一步"必须附最近对话的原话引用（让续写锚定在真实上下文，而非摘要的二手转述）。
    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成结构化摘要，严格按以下分节输出（缺失的节写"无"）：

            ## 用户诉求（逐条保留，不要合并）
            按时间顺序列出用户提出的每一条诉求/指令的原意。用户的每次发言都是一个任务边界，
            即使后来被推翻也要保留，并标注"（已撤销/已变更）"。绝不省略任何一条用户消息。

            ## 已完成的关键操作
            Agent 做了哪些关键动作（用了什么工具、改了哪些文件、得到什么核心结果）。
            只记结论性事实，不复述工具原始输出。

            ## 已达成的共识与结论
            双方确认过的决定、约定的方案、排除掉的方向。

            ## 仍未解决 / 待办
            还没做完或还没确认的事项。

            ## 下一步
            紧接着应该做什么。这一节**必须引用最近几轮对话里的原话**（用引号标出关键句），
            让后续工作锚定在真实上下文上，不要凭摘要自由发挥。

            输出中文。除上述分节标题外不要加任何前缀、元描述或解释。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private LlmClient llmClient;
    private final int retainRecentRounds;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 评估并按需压缩 history，原地修改。
     *
     * @param history       Agent 主循环的 conversationHistory，调用结束后可能被替换为更短列表
     * @param triggerTokens 触发压缩的 token 阈值（通常是 ContextProfile.compressionTriggerTokens()）
     * @return 是否真的压缩了
     */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (currentTokens < triggerTokens) return false;

        // 第一档（便宜、无损语义、不调 LLM）：清掉较老的工具结果正文，只留最近 N 条。
        // 工具结果正文最占 token 且越老越无关；先腾这一块，常常就够，避免昂贵的整段 LLM 摘要。
        boolean clearedAny = clearOldToolResultBodies(history, DEFAULT_RETAIN_RECENT_TOOL_RESULTS);
        if (clearedAny) {
            int afterClear = TokenBudget.estimateMessagesTokens(history);
            log.info("tier-1 cleared old tool result bodies: tokens {} -> {}", currentTokens, afterClear);
            if (afterClear < triggerTokens) {
                return true;  // 第一档已腾够，不必动用 LLM 摘要
            }
            currentTokens = afterClear;  // 仍超阈值，带着新基线进入第二档
        }

        // 第二档（昂贵、调 LLM）：把早期对话整段摘要。
        // 注意：即便第二档因 user 轮次不足等原因没跑成，只要第一档清理过就是真实进展，应返回 true。
        boolean summarized = summarizeOldHistory(history, triggerTokens, currentTokens);
        return summarized || clearedAny;
    }

    /**
     * 第一档：把"较老的"工具结果消息（role=tool）正文换成占位符，保留最近 retainRecent 条不动。
     * 只改 content，tool_call_id 配对与 assistant 侧 tool_calls 全保持原样，不破坏协议。
     *
     * @return 是否清理了至少一条（决定要不要重估 token）
     */
    boolean clearOldToolResultBodies(List<LlmClient.Message> history, int retainRecent) {
        // 先找出所有 role=tool 且正文尚未被清除的消息索引。
        List<Integer> toolIndices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            LlmClient.Message m = history.get(i);
            if ("tool".equals(m.role())
                    && m.content() != null
                    && !m.content().isEmpty()
                    && !CLEARED_TOOL_RESULT_PLACEHOLDER.equals(m.content())) {
                toolIndices.add(i);
            }
        }
        if (toolIndices.size() <= retainRecent) {
            return false;  // 工具结果还不够多，清了也省不下，留着
        }
        // 保留最近 retainRecent 条，清除更早的。
        int clearUpTo = toolIndices.size() - retainRecent;
        boolean cleared = false;
        for (int k = 0; k < clearUpTo; k++) {
            int idx = toolIndices.get(k);
            LlmClient.Message old = history.get(idx);
            history.set(idx, LlmClient.Message.tool(old.toolCallId(), CLEARED_TOOL_RESULT_PLACEHOLDER));
            cleared = true;
        }
        return cleared;
    }

    /** 第二档：整段 LLM 摘要（原 compactIfNeeded 主体）。 */
    private boolean summarizeOldHistory(List<LlmClient.Message> history, int triggerTokens, int currentTokens) {
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;

        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userIndices.add(i);
            }
        }
        if (userIndices.size() <= retainRecentRounds) {
            log.info("compactIfNeeded skip: only {} user turns, < retain {}",
                    userIndices.size(), retainRecentRounds);
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - retainRecentRounds);
        if (splitIdx <= systemEnd) return false;

        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (IOException e) {
            log.warn("conversation summary LLM call failed; skip compaction", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("conversation summary returned empty; skip compaction");
            return false;
        }

        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);
        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory: tokens %d -> %d, messages %d -> %d, summary chars %d",
                currentTokens, afterTokens, userIndices.size() + systemEnd /* 估值 */, rebuilt.size(),
                summary.length()));
        return true;
    }

    /**
     * 真正调 LLM 摘要。包可见以便测试通过子类替换。
     */
    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }
        String prompt = String.format(SUMMARY_PROMPT, sb.toString());
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system("你是一个对话摘要助手，按用户给定的分节结构输出摘要，只输出摘要本身，不输出任何元描述。"),
                LlmClient.Message.user(prompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }

    public int retainRecentRounds() {
        return retainRecentRounds;
    }
}
