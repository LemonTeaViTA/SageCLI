package com.paicli.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.paicli.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话消息的持久化模型 —— 为"无损重放给 LLM"设计，区别于 tui 包的 ConversationSnapshot.MessageRecord。
 *
 * <p>关键点：必须存全 {@code toolCalls} / {@code toolCallId} / {@code reasoningContent}，
 * 否则恢复带工具调用的对话时会违反 OpenAI 协议（assistant 的 tool_calls 与后续 tool 消息无法配对）。
 *
 * <p>不持久化 {@code contentParts}（多模态图片）：恢复前 Agent 会 prune 历史图片 payload，
 * 这里只保留文本 content，避免会话文件因 base64 爆炸。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionMessageRecord(
        String role,
        String content,
        String reasoningContent,
        List<ToolCallRecord> toolCalls,
        String toolCallId,
        long timestamp
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallRecord(String id, String name, String arguments) {}

    /**
     * 从运行时 {@link LlmClient.Message} 转成可持久化记录。
     */
    public static SessionMessageRecord from(LlmClient.Message message, long timestamp) {
        List<ToolCallRecord> toolCallRecords = null;
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            toolCallRecords = new ArrayList<>();
            for (LlmClient.ToolCall tc : message.toolCalls()) {
                toolCallRecords.add(new ToolCallRecord(
                        tc.id(),
                        tc.function() == null ? null : tc.function().name(),
                        tc.function() == null ? null : tc.function().arguments()
                ));
            }
        }
        return new SessionMessageRecord(
                message.role(),
                message.content(),
                message.reasoningContent(),
                toolCallRecords,
                message.toolCallId(),
                timestamp
        );
    }

    /**
     * 还原成运行时 {@link LlmClient.Message}，供灌回 conversationHistory。
     */
    public LlmClient.Message toMessage() {
        List<LlmClient.ToolCall> restoredToolCalls = null;
        if (toolCalls != null && !toolCalls.isEmpty()) {
            restoredToolCalls = new ArrayList<>();
            for (ToolCallRecord tc : toolCalls) {
                restoredToolCalls.add(new LlmClient.ToolCall(
                        tc.id(),
                        new LlmClient.ToolCall.Function(tc.name(), tc.arguments())
                ));
            }
        }
        return new LlmClient.Message(role, content, reasoningContent, restoredToolCalls, toolCallId);
    }
}
