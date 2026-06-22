package com.paicli.cost;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * {@link CostLedger} 的磁盘格式 —— per-model 成本账本的可序列化快照。
 *
 * <p>独立 sidecar：每会话一个 {@code <sessionId>.cost.json}，与 message JSONL 分离。
 * message JSONL 要无损重放给 LLM，把累计成本塞进去会污染重放语义；账本单独存更干净，
 * 项目历史聚合时也只需扫 {@code *.cost.json}。
 *
 * <p>定价三项（input / cached / output，每百万 CNY）一并存盘：定价是会变的外部数据，
 * 把"记账当时的价"固化下来，跨会话恢复后仍能复算同一笔历史成本，不被后来的调价改写。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CostSnapshot(int version, List<ModelRow> models) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelRow(
            String modelId,
            String displayName,
            String provider,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            long callCount,
            double inputPerMillion,
            double cachedPerMillion,
            double outputPerMillion
    ) {}
}
