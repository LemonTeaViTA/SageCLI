package com.paicli.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PaiCliConfig {

    private static final Path USER_CONFIG_DIR = Path.of(System.getProperty("user.home"), ".paicli");
    private static final Path USER_CONFIG_FILE = USER_CONFIG_DIR.resolve("config.json");
    // 项目级：当前工作目录下的 .paicli/config.json（已被 .gitignore 忽略，可安全放 key）
    private static final Path PROJECT_CONFIG_FILE =
            Path.of(System.getProperty("user.dir"), ".paicli", "config.json");
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private String defaultProvider = "glm";
    // 可选：Multi-Agent（/team）模式下 Reviewer 用哪个 provider（指向某个模型条目名）。
    // 不配则 Reviewer 复用 Worker 同款模型（默认行为）。配成不同模型可降同模型 self-review 盲点。
    private String reviewerProvider;
    // 可选：web_fetch 的 AI 摘要用哪个 provider（指向某个模型条目名）。摘要是"读长网页提炼"的脏活，
    // 配个便宜小快模型最划算（对标 CC 用 Haiku）。不配则复用主模型；建不出则降级返回原文截断。
    private String webFetchSummaryProvider;
    // 两层结构（新）：平台层存共享的 key/baseUrl/series，模型层只引用平台 + 写 model/displayName。
    // 同平台多模型不再重复填 key/baseUrl。
    private Map<String, PlatformConfig> platforms = new LinkedHashMap<>();
    private Map<String, ProviderConfig> models = new LinkedHashMap<>();
    // 扁平结构（旧，保留兼容）：每条自带 key/baseUrl/model。旧配置 / 运行时 /model 落盘仍走这里。
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    // 本配置从哪个文件加载（save 写回同一个文件）；不序列化进 JSON
    @JsonIgnore
    private Path sourceFile = USER_CONFIG_FILE;

    /**
     * 平台：同一服务商/接入点的共享配置（key/baseUrl/series/window），被多个模型条目引用。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlatformConfig {
        private String apiKey;
        private String baseUrl;
        private String series;             // 该平台默认系列（决定 client 实现），如 qwen/openai
        private int maxContextWindow = 0;

        public PlatformConfig() {}

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getSeries() { return series; }
        public void setSeries(String series) { this.series = series; }
        public int getMaxContextWindow() { return maxContextWindow; }
        public void setMaxContextWindow(int maxContextWindow) { this.maxContextWindow = maxContextWindow; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;          // 服务商内部模型 id（发给 API），如 xopqwen36v35b
        private String displayName;    // 展示用型号名，如 Qwen3.6-35B-A3B；不填则展示 model
        private String series;         // 系列（决定用哪个 client），如 qwen/glm/deepseek；不填则用条目名
        private String platform;       // 引用的平台名（两层结构）；为空则是旧式扁平条目
        private java.util.List<String> fallback;  // 故障转移：主调用失败时按序切到这些 model key（应为同版本不同平台）
        private double temperature = 0.7;  // 默认温度
        private int maxTokens = 8192;      // 默认最大 token 数
        private int maxContextWindow = 0;  // 上下文窗口；0 = 用 client 默认（通用 client 默认 128k）
        private java.util.Map<String, Object> extraParams;  // 平台特有透传参数（enable_thinking 等）
        private java.util.Map<String, String> extraHeaders;  // 平台特有自定义 HTTP header（如讯飞 lora_id）
        private double[] pricing;  // 定价覆盖 [输入, 缓存, 输出] 元/百万 token；不填则用 series 参考价

        public ProviderConfig() {}

        public ProviderConfig(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getSeries() { return series; }
        public void setSeries(String series) { this.series = series; }
        public String getPlatform() { return platform; }
        public void setPlatform(String platform) { this.platform = platform; }
        public java.util.List<String> getFallback() { return fallback; }
        public void setFallback(java.util.List<String> fallback) { this.fallback = fallback; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getMaxContextWindow() { return maxContextWindow; }
        public void setMaxContextWindow(int maxContextWindow) { this.maxContextWindow = maxContextWindow; }
        public java.util.Map<String, Object> getExtraParams() { return extraParams; }
        public void setExtraParams(java.util.Map<String, Object> extraParams) { this.extraParams = extraParams; }
        public java.util.Map<String, String> getExtraHeaders() { return extraHeaders; }
        public void setExtraHeaders(java.util.Map<String, String> extraHeaders) { this.extraHeaders = extraHeaders; }
        public double[] getPricing() { return pricing; }
        public void setPricing(double[] pricing) { this.pricing = pricing; }
    }

    public String getDefaultProvider() { return defaultProvider; }
    public void setReviewerProvider(String reviewerProvider) { this.reviewerProvider = reviewerProvider; }
    public String getReviewerProvider() { return reviewerProvider; }
    public void setWebFetchSummaryProvider(String webFetchSummaryProvider) { this.webFetchSummaryProvider = webFetchSummaryProvider; }
    public String getWebFetchSummaryProvider() { return webFetchSummaryProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }
    public Map<String, PlatformConfig> getPlatforms() { return platforms; }
    public void setPlatforms(Map<String, PlatformConfig> platforms) { this.platforms = platforms; }
    public Map<String, ProviderConfig> getModels() { return models; }
    public void setModels(Map<String, ProviderConfig> models) { this.models = models; }

    /** 本配置加载/保存对应的文件（用于诊断 / 测试）。 */
    @JsonIgnore
    public Path getSourceFile() { return sourceFile; }

    /**
     * 合并视图：把两层（models 引用 platform）解析成 effective {@link ProviderConfig}，
     * 再并入 legacy 扁平 {@code providers}（models 层优先）。所有读路径（取值器 / 展示 / 补全）走这里，
     * 因此上层无需关心配置是两层还是扁平。
     *
     * <p>合并规则：model 条目的字段优先，platform 兜底——
     * apiKey/baseUrl/series/maxContextWindow 缺省取所引用 platform 的；model/displayName 来自 model 条目。
     */
    @JsonIgnore
    public Map<String, ProviderConfig> resolvedProviders() {
        Map<String, ProviderConfig> resolved = new LinkedHashMap<>();
        if (models != null) {
            for (Map.Entry<String, ProviderConfig> e : models.entrySet()) {
                resolved.put(e.getKey(), mergeWithPlatform(e.getValue()));
            }
        }
        if (providers != null) {
            for (Map.Entry<String, ProviderConfig> e : providers.entrySet()) {
                resolved.putIfAbsent(e.getKey(), e.getValue());  // models 层优先，不被扁平覆盖
            }
        }
        return resolved;
    }

    /** 把一个 model 条目和它引用的 platform 合并成 effective config（model 字段优先，platform 兜底）。 */
    private ProviderConfig mergeWithPlatform(ProviderConfig model) {
        PlatformConfig plat = (model.getPlatform() != null && platforms != null)
                ? platforms.get(model.getPlatform()) : null;
        ProviderConfig eff = new ProviderConfig();
        eff.setModel(model.getModel());
        eff.setDisplayName(model.getDisplayName());
        eff.setPlatform(model.getPlatform());
        eff.setFallback(model.getFallback());  // fallback 是 model 层独有，原样带上
        eff.setExtraParams(model.getExtraParams());  // extraParams 同样 model 层独有
        eff.setExtraHeaders(model.getExtraHeaders());  // extraHeaders 同样 model 层独有
        eff.setPricing(model.getPricing());          // pricing 覆盖也是 model 层独有
        eff.setTemperature(model.getTemperature());
        eff.setMaxTokens(model.getMaxTokens());
        eff.setApiKey(firstNonBlank(model.getApiKey(), plat == null ? null : plat.getApiKey()));
        eff.setBaseUrl(firstNonBlank(model.getBaseUrl(), plat == null ? null : plat.getBaseUrl()));
        eff.setSeries(firstNonBlank(model.getSeries(), plat == null ? null : plat.getSeries()));
        int window = model.getMaxContextWindow() > 0 ? model.getMaxContextWindow()
                : (plat != null ? plat.getMaxContextWindow() : 0);
        eff.setMaxContextWindow(window);
        return eff;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    public String getApiKey(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getApiKey() != null && !providerConfig.getApiKey().isBlank()) {
            return providerConfig.getApiKey();
        }
        return loadApiKeyFromEnv(provider);
    }

    /** 故障转移列表：该 model 失败时按序切到的备份 model key。无则空列表。 */
    public java.util.List<String> getFallback(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getFallback() != null) {
            return providerConfig.getFallback();
        }
        return java.util.List.of();
    }

    /**
     * 单次回复最大输出 token：config 的 maxTokens 优先，否则读 {@code <PROVIDER>_MAX_TOKENS}，
     * 都没有则返回 0（调用方不发 max_tokens，用平台默认）。
     */
    public int getMaxTokens(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getMaxTokens() > 0) {
            return providerConfig.getMaxTokens();
        }
        String raw = loadFromEnv(provider.toUpperCase() + "_MAX_TOKENS");
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** 采样温度：config 的 temperature（model 层，默认 0.7）；resolvedProviders 没有该条目则返回 -1（不发）。 */
    public double getTemperature(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        return providerConfig != null ? providerConfig.getTemperature() : -1;
    }

    /** 平台特有透传参数：config 的 extraParams（model 层），无则 null。 */
    public java.util.Map<String, Object> getExtraParams(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        return providerConfig != null ? providerConfig.getExtraParams() : null;
    }

    /** 平台特有自定义 HTTP header：config 的 extraHeaders（model 层），无则 null。 */
    public java.util.Map<String, String> getExtraHeaders(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        return providerConfig != null ? providerConfig.getExtraHeaders() : null;
    }

    /**
     * 定价覆盖：config 的 pricing 数组 [输入, 缓存, 输出]（元/百万 token），无则 null。
     * 填了就优先于 series 参考价——用户在平台账单看到实价后填这里即可，不必改代码。
     */
    public double[] getPricing(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getPricing() != null
                && providerConfig.getPricing().length >= 1) {
            return providerConfig.getPricing();
        }
        return null;
    }

    public String getModel(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getModel() != null && !providerConfig.getModel().isBlank()) {
            return providerConfig.getModel();
        }
        return loadModelFromEnv(provider);
    }

    public String getBaseUrl(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getBaseUrl() != null && !providerConfig.getBaseUrl().isBlank()) {
            return providerConfig.getBaseUrl();
        }
        return loadBaseUrlFromEnv(provider);
    }

    /**
     * 系列：决定用哪个 client 实现。config 的 series（model 层或所引用 platform）优先，
     * 否则读 {@code <PROVIDER>_SERIES}，都没有则回退为 provider 名本身（兼容旧配置：条目名叫 qwen/glm 时即为系列）。
     */
    public String getSeries(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getSeries() != null && !providerConfig.getSeries().isBlank()) {
            return providerConfig.getSeries().trim();
        }
        String raw = loadFromEnv(provider.toUpperCase() + "_SERIES");
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        return provider;
    }

    /**
     * 展示用型号名。config 的 displayName 优先，否则读 {@code <PROVIDER>_DISPLAY_NAME}，
     * 都没有则返回 null（调用方回退到展示 model id）。
     */
    public String getDisplayName(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getDisplayName() != null && !providerConfig.getDisplayName().isBlank()) {
            return providerConfig.getDisplayName().trim();
        }
        String raw = loadFromEnv(provider.toUpperCase() + "_DISPLAY_NAME");
        return raw != null && !raw.isBlank() ? raw.trim() : null;
    }

    /**
     * 上下文窗口：config 的 maxContextWindow（model 层或 platform）优先，否则读环境变量
     * {@code <PROVIDER>_MAX_CONTEXT_WINDOW}，都没有则返回 0（调用方按 client 默认 128k 处理）��
     */
    public int getMaxContextWindow(String provider) {
        ProviderConfig providerConfig = resolvedProviders().get(provider);
        if (providerConfig != null && providerConfig.getMaxContextWindow() > 0) {
            return providerConfig.getMaxContextWindow();
        }
        String raw = loadFromEnv(provider.toUpperCase() + "_MAX_CONTEXT_WINDOW");
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String loadFromEnv(String envKey) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return readFromDotEnv(envKey);
    }

    public static PaiCliConfig load() {
        return load(PROJECT_CONFIG_FILE, USER_CONFIG_FILE);
    }

    /**
     * 项目级文件存在则只用它（key 可安全放里面，已 gitignore）；否则回退到用户级（旧行为，兼容）。
     * package-private 重载便于测试注入路径。
     */
    static PaiCliConfig load(Path projectFile, Path userFile) {
        Path source = Files.exists(projectFile) ? projectFile : userFile;
        if (Files.exists(source)) {
            try {
                PaiCliConfig config = mapper.readValue(source.toFile(), PaiCliConfig.class);
                config.sourceFile = source;
                return config;
            } catch (IOException e) {
                System.err.println("⚠️ 配置文件读取失败，使用默认配置: " + e.getMessage());
            }
        }
        PaiCliConfig fresh = new PaiCliConfig();
        fresh.sourceFile = source;
        return fresh;
    }

    public void save() {
        try {
            Path target = sourceFile != null ? sourceFile : USER_CONFIG_FILE;
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writeValue(target.toFile(), this);
        } catch (IOException e) {
            System.err.println("⚠️ 配置保存失败: " + e.getMessage());
        }
    }

    private static String loadModelFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_MODEL";
            case "deepseek" -> "DEEPSEEK_MODEL";
            case "kimi" -> "KIMI_MODEL";
            default -> provider.toUpperCase() + "_MODEL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_MODEL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_MODEL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadApiKeyFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "step" -> "STEP_API_KEY";
            case "kimi" -> "KIMI_API_KEY";
            default -> provider.toUpperCase() + "_API_KEY";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_API_KEY");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_API_KEY");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadBaseUrlFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "step" -> "STEP_BASE_URL";
            case "kimi" -> "KIMI_BASE_URL";
            default -> provider.toUpperCase() + "_BASE_URL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_BASE_URL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_BASE_URL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = { new File(".env"), new File(System.getProperty("user.home"), ".env") };
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }
}
