package com.paicli.cli;

import com.paicli.config.PaiCliConfig.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogTest {

    private static ProviderConfig entry(String series, String displayName, String model) {
        ProviderConfig pc = new ProviderConfig("k", "http://x/v1", model);
        pc.setSeries(series);
        pc.setDisplayName(displayName);
        return pc;
    }

    @Test
    void forDisplayUsesDisplayNameAndOnlyListsConfigured() {
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("qwen36", entry("qwen", "Qwen3.6-35B-A3B", "xopqwen36v35b"));
        providers.put("glm47", entry("glm", "GLM-4.7", "glm-4.7"));

        List<ModelCatalog.ModelOption> opts = ModelCatalog.forDisplay(providers);
        // 只列配了的两个，描述是型号名（不是 "配置中的模型"，也没有未配置的内置名）
        assertEquals(2, opts.size());
        assertTrue(opts.stream().anyMatch(o -> o.name().equals("qwen36") && o.description().equals("Qwen3.6-35B-A3B")));
        assertTrue(opts.stream().anyMatch(o -> o.name().equals("glm47") && o.description().equals("GLM-4.7")));
        assertFalse(opts.stream().anyMatch(o -> o.description().equals("配置中的模型")));
        assertFalse(opts.stream().anyMatch(o -> o.name().equals("deepseek")));  // 未配置的内置名不出现
    }

    @Test
    void forDisplayGroupsBySeriesThenSortsByName() {
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        // 故意交错插入，验证同系列被归到一起、组内按名字排序
        providers.put("glm47", entry("glm", "GLM-4.7", "glm-4.7"));
        providers.put("qwen36", entry("qwen", "Qwen3.6-35B-A3B", "xopqwen36v35b"));
        providers.put("glm45air", entry("glm", "GLM-4.5-Air", "glm-4.5-air"));
        providers.put("qwen35", entry("qwen", "Qwen3.5-35B-A3B", "xopqwen35v35b"));

        List<String> order = ModelCatalog.forDisplay(providers).stream().map(ModelCatalog.ModelOption::name).toList();
        // glm 系列先出现（首个 glm47 决定系列顺序），组内排序 glm45air<glm47；然后 qwen 组 qwen35<qwen36
        assertEquals(List.of("glm45air", "glm47", "qwen35", "qwen36"), order);
    }

    @Test
    void forDisplayDescriptionFallsBackToModelIdThenName() {
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        ProviderConfig noDisplay = new ProviderConfig("k", "http://x/v1", "llama-3.1-70b");  // 无 displayName
        providers.put("myllama", noDisplay);

        List<ModelCatalog.ModelOption> opts = ModelCatalog.forDisplay(providers);
        assertEquals("llama-3.1-70b", opts.get(0).description());  // 回退到 model id
    }

    @Test
    void forDisplayFallsBackToBuiltinsWhenEmpty() {
        assertEquals(ModelCatalog.builtins().size(), ModelCatalog.forDisplay(null).size());
        assertEquals(ModelCatalog.builtins().size(), ModelCatalog.forDisplay(new LinkedHashMap<>()).size());
    }

    @Test
    void builtinsContainsKnownModels() {
        List<String> names = ModelCatalog.builtins().stream().map(ModelCatalog.ModelOption::name).toList();
        assertTrue(names.contains("glm-5.1"));
        assertTrue(names.contains("qwen"));
    }
}

