package com.paicli.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 故障转移装饰器：主 client 调用失败时，自动切到备份 client（另一平台的同一个模型）重试。
 *
 * <p>装饰器对上层透明——它本身是 {@link LlmClient}，Agent / SubAgent / Orchestrator 无需改动。
 *
 * <p><b>流式安全</b>：{@code chat()} 是流式的，已经吐字再切备份会导致屏幕重复输出。因此
 * <b>仅当本次尝试"尚未吐出任何 content/reasoning delta"时才 failover</b>。用户遇到的失败
 * （HTTP 4xx/5xx、连接超时、冷启动）都发生在吐字之前，覆盖绝大多数场景；已开始吐字才失败
 * 则不切、照常抛错（罕见的流中途断，避免重复输出）。
 *
 * <p>身份方法（getModelName/getProviderName/maxContextWindow 等）全部委托给主 client。
 */
public class FallbackLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmClient.class);

    private final LlmClient primary;
    private final List<LlmClient> fallbacks;

    public FallbackLlmClient(LlmClient primary, List<LlmClient> fallbacks) {
        if (primary == null) {
            throw new IllegalArgumentException("primary client 不能为空");
        }
        this.primary = primary;
        this.fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        StreamListener delegate = listener == null ? StreamListener.NO_OP : listener;

        List<LlmClient> chain = new ArrayList<>();
        chain.add(primary);
        chain.addAll(fallbacks);

        IOException last = null;
        for (int i = 0; i < chain.size(); i++) {
            LlmClient client = chain.get(i);
            GuardingListener guard = new GuardingListener(delegate);
            try {
                return client.chat(messages, tools, guard);
            } catch (IOException e) {
                last = e;
                // 已经吐字才失败：不切备份，直接��（避免屏幕重复输出）
                if (guard.streamedAny) {
                    log.warn("[fallback] {} 在已输出内容后失败，不再切备份: {}",
                            client.getProviderName(), e.getMessage());
                    throw e;
                }
                boolean hasMore = i < chain.size() - 1;
                if (hasMore) {
                    log.warn("[fallback] {} 调用失败（吐字前），切到备份 {}: {}",
                            client.getProviderName(), chain.get(i + 1).getProviderName(), e.getMessage());
                } else {
                    log.warn("[fallback] {} 调用失败，已无更多备份: {}",
                            client.getProviderName(), e.getMessage());
                }
            }
        }
        throw last != null ? last : new IOException("FallbackLlmClient: 无可用 client");
    }

    @Override
    public String getModelName() {
        return primary.getModelName();
    }

    @Override
    public String getProviderName() {
        return primary.getProviderName();
    }

    @Override
    public String getDisplayName() {
        return primary.getDisplayName();
    }

    @Override
    public int maxContextWindow() {
        return primary.maxContextWindow();
    }

    @Override
    public boolean supportsPromptCaching() {
        return primary.supportsPromptCaching();
    }

    @Override
    public String promptCacheMode() {
        return primary.promptCacheMode();
    }

    /** 包装上层 listener，记录本次尝试是否已经吐出过 delta（决定能否安全切备份）。 */
    private static final class GuardingListener implements StreamListener {
        private final StreamListener delegate;
        private boolean streamedAny;

        private GuardingListener(StreamListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta != null && !delta.isEmpty()) {
                streamedAny = true;
            }
            delegate.onReasoningDelta(delta);
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta != null && !delta.isEmpty()) {
                streamedAny = true;
            }
            delegate.onContentDelta(delta);
        }
    }
}
