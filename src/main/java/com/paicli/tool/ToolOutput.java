package com.paicli.tool;

import com.paicli.llm.LlmClient;

import java.util.List;

public record ToolOutput(String text, List<LlmClient.ContentPart> imageParts, boolean failed) {
    public ToolOutput {
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
    }

    /** 向后兼容的 2 参构造：默认 failed=false。直接 new ToolOutput(text, imageParts) 的旧调用方继续可用。 */
    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {
        this(text, imageParts, false);
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of(), false);
    }

    /**
     * 结构化失败结果：策略拒绝 / 工具异常 / 超时等。
     * 上层（如 PlanExecuteAgent 的连续失败检测）据此判定，而不是字符串匹配 "🛡️ 策略拒绝"。
     */
    public static ToolOutput failure(String text) {
        return new ToolOutput(text, List.of(), true);
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
