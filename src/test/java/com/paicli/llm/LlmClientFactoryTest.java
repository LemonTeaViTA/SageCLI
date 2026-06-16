package com.paicli.llm;

import com.paicli.config.PaiCliConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmClientFactoryTest {

    @Test
    void createsGlm5vTurboClientWithMultimodalEndpoint() {
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("glm",
                new PaiCliConfig.ProviderConfig("test-glm-key", null, "glm-5v-turbo"));

        LlmClient client = LlmClientFactory.create("glm", config);

        OpenAiCompatibleClient glmClient = assertInstanceOf(OpenAiCompatibleClient.class, client);
        assertEquals("glm", glmClient.getProviderName());
        assertEquals("glm-5v-turbo", glmClient.getModelName());
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", glmClient.getApiUrl());
    }

    @Test
    void glmDefaultsToTokenEndpointForAnyModel() {
        // 不填 baseUrl：任意 GLM 模型（含非 5v 的 glm-4.5-air）默认走 token 计费 paas/v4
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("glm",
                new PaiCliConfig.ProviderConfig("test-glm-key", null, "glm-4.5-air"));

        OpenAiCompatibleClient glm = assertInstanceOf(OpenAiCompatibleClient.class, LlmClientFactory.create("glm", config));
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", glm.getApiUrl());
    }

    @Test
    void glmCanOverrideToCodingEndpointViaBaseUrl() {
        // plan / coding 套餐用户：填 baseUrl 覆盖为 coding endpoint
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("glm",
                new PaiCliConfig.ProviderConfig("test-glm-key",
                        "https://open.bigmodel.cn/api/coding/paas/v4", "glm-5.1"));

        OpenAiCompatibleClient glm = assertInstanceOf(OpenAiCompatibleClient.class, LlmClientFactory.create("glm", config));
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4/chat/completions", glm.getApiUrl());
    }

    @Test
    void createsStepClientFromConfiguredProvider() {
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("step",
                new PaiCliConfig.ProviderConfig("test-step-key", null, "step-3.5-flash-2603"));

        LlmClient client = LlmClientFactory.create("step", config);

        OpenAiCompatibleClient stepClient = assertInstanceOf(OpenAiCompatibleClient.class, client);
        assertEquals("step", stepClient.getProviderName());
        assertEquals("step-3.5-flash-2603", stepClient.getModelName());
        assertEquals(256_000, stepClient.maxContextWindow());
        assertEquals(expectedStepChatUrl(config.getBaseUrl("step")), stepClient.getApiUrl());
    }

    @Test
    void createsStepClientFromStepfunAliasAndCustomBaseUrl() {
        PaiCliConfig config = new PaiCliConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("step",
                new PaiCliConfig.ProviderConfig(
                        "test-step-key",
                        "https://api.stepfun.com/step_plan/v1",
                        "step-router-v1"));

        LlmClient client = LlmClientFactory.create("stepfun", config);

        OpenAiCompatibleClient stepClient = assertInstanceOf(OpenAiCompatibleClient.class, client);
        assertEquals("step-router-v1", stepClient.getModelName());
        assertEquals("https://api.stepfun.com/step_plan/v1/chat/completions", stepClient.getApiUrl());
    }

    @Test
    void createsKimiClientFromMoonshotAliasAndCustomBaseUrl() {
        PaiCliConfig config = new PaiCliConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("kimi",
                new PaiCliConfig.ProviderConfig(
                        "test-kimi-key",
                        "https://api.moonshot.ai/v1",
                        "kimi-k2.6"));

        LlmClient client = LlmClientFactory.create("moonshot", config);

        OpenAiCompatibleClient kimiClient = assertInstanceOf(OpenAiCompatibleClient.class, client);
        assertEquals("kimi", kimiClient.getProviderName());
        assertEquals("kimi-k2.6", kimiClient.getModelName());
        assertEquals(256_000, kimiClient.maxContextWindow());
    }

    @Test
    void returnsNullWhenCustomProviderMissingBaseUrl() {
        // 自定义 provider 缺 baseUrl（无法确定 endpoint）→ 通用 client 无法构造，返回 null
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("unknown", new PaiCliConfig.ProviderConfig("test-key", null, "unknown-model"));

        assertNull(LlmClientFactory.create("unknown", config));
    }

    @Test
    void returnsNullWhenCustomProviderMissingApiKey() {
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("nokey",
                new PaiCliConfig.ProviderConfig(null, "https://api.example.com/v1", "some-model"));

        assertNull(LlmClientFactory.create("nokey", config));
    }

    @Test
    void createsGenericClientForCustomProviderWithFullConfig() {
        // 任意未知名字，只要配齐 key + model + baseUrl → 通用 OpenAiCompatibleClient，零代码改动
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("my-local-llama",
                new PaiCliConfig.ProviderConfig(
                        "test-key",
                        "http://localhost:8000/v1",
                        "llama-3.1-70b"));

        LlmClient client = LlmClientFactory.create("my-local-llama", config);

        OpenAiCompatibleClient generic = assertInstanceOf(OpenAiCompatibleClient.class, client);
        assertEquals("my-local-llama", generic.getProviderName());
        assertEquals("llama-3.1-70b", generic.getModelName());
        assertEquals("http://localhost:8000/v1/chat/completions", generic.getApiUrl());
        assertEquals(128_000, generic.maxContextWindow());  // 未配 window → 默认 128k
    }

    @Test
    void genericClientHonorsConfiguredContextWindow() {
        PaiCliConfig config = new PaiCliConfig();
        PaiCliConfig.ProviderConfig pc =
                new PaiCliConfig.ProviderConfig("k", "http://localhost:8000/v1", "m");
        pc.setMaxContextWindow(65_536);
        config.getProviders().put("custom", pc);

        OpenAiCompatibleClient generic =
                assertInstanceOf(OpenAiCompatibleClient.class, LlmClientFactory.create("custom", config));
        assertEquals(65_536, generic.maxContextWindow());
    }

    @Test
    void qwen35FallsBackToFixedModelAndSharedBaseUrl() {
        // 只配了 QWEN35 的 key + 通用 qwen 的 baseUrl，没配 QWEN35_MODEL → 兜底 xopqwen35v35b
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("qwen35", new PaiCliConfig.ProviderConfig("k35", null, null));
        config.getProviders().put("qwen",
                new PaiCliConfig.ProviderConfig("kq", "https://maas-api.cn-huabei-1.xf-yun.com/v2", null));

        OpenAiCompatibleClient client =
                assertInstanceOf(OpenAiCompatibleClient.class, LlmClientFactory.create("qwen35", config));
        assertEquals("qwen35", client.getProviderName());
        assertEquals("xopqwen35v35b", client.getModelName());
        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions", client.getApiUrl());
    }

    @Test
    void routesBySeriesNotByEntryName() {
        // 条目名叫 qwen36（切换名），series=qwen → 用 qwen 系列的 quirks（而非按条目名瞎猜）
        PaiCliConfig config = new PaiCliConfig();
        PaiCliConfig.ProviderConfig pc =
                new PaiCliConfig.ProviderConfig("k", "https://maas-api.cn-huabei-1.xf-yun.com/v2", "xopqwen36v35b");
        pc.setSeries("qwen");
        config.getProviders().put("qwen36", pc);

        LlmClient client = LlmClientFactory.create("qwen36", config);
        assertInstanceOf(OpenAiCompatibleClient.class, client);
        // providerName 是条目名（切换名），不是 series
        assertEquals("qwen36", client.getProviderName());
        // series=qwen 的差异生效：qwen 窗口 128k、不支持缓存（证明按 series 路由 quirks）
        assertEquals(128_000, client.maxContextWindow());
        assertEquals(false, client.supportsPromptCaching());
    }

    @Test
    void injectsDisplayNameForUiButKeepsModelIdForApi() {
        // displayName 用于展示，getModelName 仍是发给 API 的服务 id
        PaiCliConfig config = new PaiCliConfig();
        PaiCliConfig.ProviderConfig pc =
                new PaiCliConfig.ProviderConfig("k", "https://maas-api.cn-huabei-1.xf-yun.com/v2", "xopqwen36v35b");
        pc.setSeries("qwen");
        pc.setDisplayName("Qwen3.6-35B-A3B");
        config.getProviders().put("qwen36", pc);

        LlmClient client = LlmClientFactory.create("qwen36", config);
        assertEquals("Qwen3.6-35B-A3B", client.getDisplayName());  // 展示型号名
        assertEquals("xopqwen36v35b", client.getModelName());      // 服务 id 不变
    }

    @Test
    void displayNameFallsBackToModelIdWhenUnset() {
        PaiCliConfig config = new PaiCliConfig();
        PaiCliConfig.ProviderConfig pc =
                new PaiCliConfig.ProviderConfig("k", "http://localhost:8000/v1", "llama-3.1-70b");
        config.getProviders().put("my-llama", pc);  // 无 displayName

        LlmClient client = LlmClientFactory.create("my-llama", config);
        assertEquals("llama-3.1-70b", client.getDisplayName());  // 回退到 model id
    }

    @Test
    void wrapsInFallbackClientWhenFallbackConfigured() {
        // model 配了 fallback → 建出 FallbackLlmClient（两个 platform 各持同款模型）
        PaiCliConfig config = new PaiCliConfig();
        PaiCliConfig.PlatformConfig p1 = new PaiCliConfig.PlatformConfig();
        p1.setApiKey("k1"); p1.setBaseUrl("https://a/v1"); p1.setSeries("openai");
        PaiCliConfig.PlatformConfig p2 = new PaiCliConfig.PlatformConfig();
        p2.setApiKey("k2"); p2.setBaseUrl("https://b/v1"); p2.setSeries("openai");
        config.getPlatforms().put("pa", p1);
        config.getPlatforms().put("pb", p2);

        PaiCliConfig.ProviderConfig main = new PaiCliConfig.ProviderConfig();
        main.setPlatform("pa"); main.setModel("deepseek-v4-pro");
        main.setFallback(java.util.List.of("ds-backup"));
        PaiCliConfig.ProviderConfig backup = new PaiCliConfig.ProviderConfig();
        backup.setPlatform("pb"); backup.setModel("deepseek-v4-pro");
        config.getModels().put("ds-main", main);
        config.getModels().put("ds-backup", backup);

        LlmClient client = LlmClientFactory.create("ds-main", config);
        assertInstanceOf(FallbackLlmClient.class, client);
        assertEquals("ds-main", client.getProviderName());  // 委托 primary
    }

    @Test
    void noFallbackReturnsBareClient() {
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("solo",
                new PaiCliConfig.ProviderConfig("k", "https://a/v1", "m"));

        LlmClient client = LlmClientFactory.create("solo", config);
        assertInstanceOf(OpenAiCompatibleClient.class, client);  // 无 fallback → 裸 client
    }

    @Test
    void cyclicFallbackDoesNotLoop() {
        // A.fallback=[B], B.fallback=[A] —— 不应死循环。备份均建为裸 client，A 包成 Fallback(主A, 备B)。
        PaiCliConfig config = new PaiCliConfig();
        PaiCliConfig.ProviderConfig a = new PaiCliConfig.ProviderConfig("k", "https://a/v1", "ma");
        a.setSeries("openai"); a.setFallback(java.util.List.of("b"));
        PaiCliConfig.ProviderConfig b = new PaiCliConfig.ProviderConfig("k", "https://b/v1", "mb");
        b.setSeries("openai"); b.setFallback(java.util.List.of("a"));
        config.getModels().put("a", a);
        config.getModels().put("b", b);

        LlmClient client = LlmClientFactory.create("a", config);  // 不应 StackOverflow
        assertInstanceOf(FallbackLlmClient.class, client);
        assertEquals("a", client.getProviderName());
    }

    private static String expectedStepChatUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl.trim()
                : "https://api.stepfun.com/v1";
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
