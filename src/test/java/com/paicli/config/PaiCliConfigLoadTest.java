package com.paicli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaiCliConfigLoadTest {

    @Test
    void prefersProjectConfigOverUserConfig(@TempDir Path dir) throws Exception {
        Path projectFile = dir.resolve("project/.paicli/config.json");
        Path userFile = dir.resolve("user/.paicli/config.json");
        Files.createDirectories(projectFile.getParent());
        Files.createDirectories(userFile.getParent());
        Files.writeString(userFile, "{\"defaultProvider\":\"glm\",\"providers\":{}}");
        Files.writeString(projectFile, "{\"defaultProvider\":\"qwen\",\"providers\":{}}");

        PaiCliConfig config = PaiCliConfig.load(projectFile, userFile);

        // 项目级存在 → 只用项目级
        assertEquals("qwen", config.getDefaultProvider());
        assertEquals(projectFile, config.getSourceFile());
    }

    @Test
    void fallsBackToUserConfigWhenNoProjectConfig(@TempDir Path dir) throws Exception {
        Path projectFile = dir.resolve("project/.paicli/config.json");  // 不创建
        Path userFile = dir.resolve("user/.paicli/config.json");
        Files.createDirectories(userFile.getParent());
        Files.writeString(userFile, "{\"defaultProvider\":\"deepseek\",\"providers\":{}}");

        PaiCliConfig config = PaiCliConfig.load(projectFile, userFile);

        assertEquals("deepseek", config.getDefaultProvider());
        assertEquals(userFile, config.getSourceFile());
    }

    @Test
    void savesBackToProjectFileWhenLoadedFromProject(@TempDir Path dir) throws Exception {
        Path projectFile = dir.resolve("project/.paicli/config.json");
        Path userFile = dir.resolve("user/.paicli/config.json");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(projectFile, "{\"defaultProvider\":\"qwen\",\"providers\":{}}");

        PaiCliConfig config = PaiCliConfig.load(projectFile, userFile);
        config.setDefaultProvider("kimi");
        config.save();

        // save 应写回项目级文件，且用户级文件不被创建
        String written = Files.readString(projectFile);
        assertTrue(written.contains("\"kimi\""), "项目级文件应被更新");
        assertTrue(Files.notExists(userFile), "用户级文件不应被创建");
    }

    @Test
    void keyInConfigJsonIsReadable(@TempDir Path dir) throws Exception {
        // key 直接写在 config.json 的 apiKey 字段，能被 getApiKey 读到
        Path projectFile = dir.resolve(".paicli/config.json");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(projectFile,
                "{\"defaultProvider\":\"qwen\",\"providers\":{\"qwen\":{\"apiKey\":\"sk-test-123\",\"model\":\"xopqwen36v35b\"}}}");

        PaiCliConfig config = PaiCliConfig.load(projectFile, dir.resolve("nope.json"));

        assertEquals("sk-test-123", config.getApiKey("qwen"));
        assertEquals("xopqwen36v35b", config.getModel("qwen"));
    }

    @Test
    void twoTierModelResolvesPlatformKeyBaseUrlSeries(@TempDir Path dir) throws Exception {
        // 两层：model 只引用 platform，key/baseUrl/series 从 platform 解析
        Path f = dir.resolve(".paicli/config.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                {
                  "defaultProvider": "glm47",
                  "platforms": {
                    "zhipu": {"apiKey":"zhipu-key","baseUrl":"https://open.bigmodel.cn/api/paas/v4","series":"openai","maxContextWindow":200000}
                  },
                  "models": {
                    "glm47": {"platform":"zhipu","model":"glm-4.7","displayName":"GLM-4.7"}
                  }
                }
                """);

        PaiCliConfig config = PaiCliConfig.load(f, dir.resolve("nope.json"));

        assertEquals("zhipu-key", config.getApiKey("glm47"));        // key 来自 platform
        assertEquals("https://open.bigmodel.cn/api/paas/v4", config.getBaseUrl("glm47"));
        assertEquals("openai", config.getSeries("glm47"));           // series 来自 platform
        assertEquals("glm-4.7", config.getModel("glm47"));           // model 来自 model 条目
        assertEquals("GLM-4.7", config.getDisplayName("glm47"));
        assertEquals(200000, config.getMaxContextWindow("glm47"));   // window 来自 platform
    }

    @Test
    void getMaxTokensResolvesFromConfigDefaultAndOverride(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".paicli/config.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                {
                  "platforms": {"p": {"apiKey":"k","baseUrl":"https://x/v1","series":"openai"}},
                  "models": {
                    "default-tok": {"platform":"p","model":"a"},
                    "custom-tok":  {"platform":"p","model":"b","maxTokens":32768}
                  }
                }
                """);

        PaiCliConfig config = PaiCliConfig.load(f, dir.resolve("nope.json"));

        assertEquals(8192, config.getMaxTokens("default-tok"));   // ProviderConfig 默认 8192
        assertEquals(32768, config.getMaxTokens("custom-tok"));   // 条目覆盖
    }

    @Test
    void modelLayerOverridesPlatformApiKey(@TempDir Path dir) throws Exception {
        // 同平台不同 key：model 条目的 apiKey 覆盖 platform 的
        Path f = dir.resolve(".paicli/config.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                {
                  "platforms": {"xf": {"apiKey":"shared-key","baseUrl":"https://x/v2","series":"qwen"}},
                  "models": {
                    "m1": {"platform":"xf","model":"a"},
                    "m2": {"platform":"xf","model":"b","apiKey":"override-key"}
                  }
                }
                """);

        PaiCliConfig config = PaiCliConfig.load(f, dir.resolve("nope.json"));

        assertEquals("shared-key", config.getApiKey("m1"));    // 用平台的
        assertEquals("override-key", config.getApiKey("m2"));  // model 层覆盖
        assertEquals("https://x/v2", config.getBaseUrl("m2")); // baseUrl 仍用平台的
    }

    @Test
    void legacyFlatProvidersStillWork(@TempDir Path dir) throws Exception {
        // 向后兼容：纯旧式扁平 providers 仍能读
        Path f = dir.resolve(".paicli/config.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f,
                "{\"defaultProvider\":\"qwen\",\"providers\":{\"qwen\":{\"apiKey\":\"flat-key\",\"baseUrl\":\"https://flat/v1\",\"model\":\"m\",\"series\":\"qwen\"}}}");

        PaiCliConfig config = PaiCliConfig.load(f, dir.resolve("nope.json"));

        assertEquals("flat-key", config.getApiKey("qwen"));
        assertEquals("https://flat/v1", config.getBaseUrl("qwen"));
        assertEquals("m", config.getModel("qwen"));
    }

    @Test
    void modelsLayerTakesPrecedenceOverFlatProviders(@TempDir Path dir) throws Exception {
        // 同名时 models 层优先于 legacy providers
        Path f = dir.resolve(".paicli/config.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                {
                  "platforms": {"p": {"apiKey":"new-key","baseUrl":"https://new/v1","series":"openai"}},
                  "models": {"x": {"platform":"p","model":"new-model"}},
                  "providers": {"x": {"apiKey":"old-key","baseUrl":"https://old/v1","model":"old-model"}}
                }
                """);

        PaiCliConfig config = PaiCliConfig.load(f, dir.resolve("nope.json"));

        assertEquals("new-key", config.getApiKey("x"));      // models 层赢
        assertEquals("new-model", config.getModel("x"));
    }
}
