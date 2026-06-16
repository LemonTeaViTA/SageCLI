package com.paicli.cli;

import com.paicli.config.PaiCliConfig.ProviderConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型候选目录：{@code /model} 的补全提示 / help 文本的单一数据源。
 *
 * <p>以 config.json 里实际声明的 provider 为准——只列用户配了的模型，用各自的
 * displayName（型号名）展示，并按 series（系列）分组，让同系列模型挨在一起。
 * config 为空时回退到内置名单（兼容纯 .env / 首次启动场景）。
 */
final class ModelCatalog {

    private ModelCatalog() {}

    /** 一个可切换模型的展示项。name=切换名（map key），description=展示文本（型号名）。 */
    record ModelOption(String name, String description) {}

    /**
     * 内置模型选项——仅在 config.json 无任何 provider 时作为兜底展示。
     */
    static List<ModelOption> builtins() {
        return List.of(
                new ModelOption("glm-5.1", "GLM-5.1 长上下文"),
                new ModelOption("glm-5v-turbo", "GLM-5V-Turbo 多模态"),
                new ModelOption("deepseek", "DeepSeek（读取配置模型）"),
                new ModelOption("step", "StepFun（读取配置模型）"),
                new ModelOption("kimi", "Kimi/Moonshot（读取配置模型）"),
                new ModelOption("qwen", "Qwen（讯飞 MaaS 平台，读取配置模型）")
        );
    }

    /**
     * 按 config.json 的 providers 生成展示列表：
     * <ul>
     *   <li>只列 config 里声明的条目（不混入未配置的内置名）；</li>
     *   <li>描述用 displayName（型号名），没填则退回 model id；</li>
     *   <li>按 series 分组（同系列挨在一起），组内按切换名排序；无 series 的归到末尾。</li>
     * </ul>
     * providers 为空 → 回退 {@link #builtins()}。
     */
    static List<ModelOption> forDisplay(Map<String, ProviderConfig> providers) {
        if (providers == null || providers.isEmpty()) {
            return builtins();
        }
        // 按 series 分组（保持系列首次出现的顺序），组内按切换名排序
        Map<String, List<String>> bySeries = new LinkedHashMap<>();
        for (String name : providers.keySet()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            ProviderConfig pc = providers.get(name);
            String series = pc != null && pc.getSeries() != null && !pc.getSeries().isBlank()
                    ? pc.getSeries().trim() : name;  // 无 series 自成一组
            bySeries.computeIfAbsent(series, k -> new ArrayList<>()).add(name);
        }

        List<ModelOption> result = new ArrayList<>();
        for (List<String> names : bySeries.values()) {
            names.sort(String::compareTo);  // 组内：qwen35 在 qwen36 前、glm45air 在 glm47 前
            for (String name : names) {
                ProviderConfig pc = providers.get(name);
                result.add(new ModelOption(name, describe(name, pc)));
            }
        }
        return result;
    }

    private static String describe(String name, ProviderConfig pc) {
        if (pc == null) {
            return name;
        }
        if (pc.getDisplayName() != null && !pc.getDisplayName().isBlank()) {
            return pc.getDisplayName().trim();   // 优先型号名
        }
        if (pc.getModel() != null && !pc.getModel().isBlank()) {
            return pc.getModel().trim();         // 退回 model id
        }
        return name;
    }

    /**
     * 兼容旧调用：内置 + config keys 合并去重（仅用切换名，无 displayName/分组）。
     * 新代码应改用 {@link #forDisplay}。
     */
    static List<ModelOption> withConfigured(Collection<String> configuredProviderNames) {
        Map<String, ModelOption> merged = new LinkedHashMap<>();
        for (ModelOption option : builtins()) {
            merged.put(option.name(), option);
        }
        if (configuredProviderNames != null) {
            for (String name : configuredProviderNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String key = name.trim();
                merged.putIfAbsent(key, new ModelOption(key, "配置中的模型"));
            }
        }
        return new ArrayList<>(merged.values());
    }
}
