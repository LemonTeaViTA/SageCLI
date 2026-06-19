package com.paicli.tool;

import com.paicli.browser.BrowserConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertTrue(result.contains("策略拒绝"));
    }

    @Test
    void shouldKeepTailWhenCommandOutputTooLarge(@TempDir Path tempDir) {
        // 命令输出超上限时保尾截断：报错/exit code 常在尾部，尾部内容必须保住，头部可丢。
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // 2000 行 "line N" 远超 8000 字符上限。
        String result = registry.executeTool("execute_command",
                "{\"command\":\"for i in $(seq 1 2000); do echo line $i; done\"}");

        assertTrue(result.contains("输出头部已截断"), "应标注头部截断: " + result.substring(0, Math.min(200, result.length())));
        assertTrue(result.contains("line 2000"), "尾部内容应保留");
        assertFalse(result.contains("line 1\n"), "头部内容应被截掉");
    }

    @Test
    void shouldReadRequestedLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "class Sample {",
                "  void first() {}",
                "  void second() {}",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"Sample.java\",\"offset\":2,\"limit\":2}");

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 |   void first() {}"));
        assertTrue(result.contains("3 |   void second() {}"));
        assertTrue(!result.contains("class Sample {"));
    }

    @Test
    void shouldRejectOversizedFullReadInsteadOfTruncating(@TempDir Path tempDir) throws Exception {
        // 构造一个 >5MB 的文件，不带 offset/limit 全文读取应直接抛错 + 提示用 offset/limit 或 grep，
        // 而不是降级读前 N 行（截断的头部会随历史逐轮重发烧 token，且大概率不是模型要的内容）。
        Path big = tempDir.resolve("huge.log");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 200; i++) line.append("x");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 30_000; i++) {        // 30000 * ~201 字节 ≈ 6MB
            content.append("line").append(i).append("-").append(line).append("\n");
        }
        Files.writeString(big, content.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"huge.log\"}");

        assertTrue(result.contains("读取文件失败"), "应是失败而非降级读取: " + result.substring(0, Math.min(120, result.length())));
        assertTrue(result.contains("超过 5MB 上限"), "应提示超限");
        assertTrue(result.contains("offset/limit") && result.contains("grep_code"), "应提示用 offset/limit 或 grep_code");
        // 抛错路径不应包含任何文件内容（证明没有读出头部）
        assertTrue(!result.contains("line0-"), "抛错时不应含文件内容");
    }

    @Test
    void shouldStillReadOversizedFileWhenRangedExplicitly(@TempDir Path tempDir) throws Exception {
        // 超大文件带 offset/limit 显式分页时仍应正常读取指定区间（抛错只针对无范围的整段读）。
        Path big = tempDir.resolve("huge2.log");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 200; i++) line.append("x");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 30_000; i++) {
            content.append("line").append(i).append("-").append(line).append("\n");
        }
        Files.writeString(big, content.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"huge2.log\",\"offset\":1,\"limit\":3}");

        assertTrue(result.contains("    1 | line0-"), "带范围应能读到指定区间: " + result.substring(0, Math.min(120, result.length())));
        assertTrue(!result.contains("读取文件失败"), "带 offset/limit 不应抛超限错");
    }

    @Test
    void shouldReadNormalSizedFileInFull(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("small.txt");
        Files.writeString(file, "hello\nworld\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"small.txt\"}");

        assertTrue(result.contains("文件内容:"));
        assertTrue(result.contains("hello"));
        assertTrue(result.contains("world"));
        assertTrue(!result.contains("超过 5MB"), "正常文件不应触发降级");
    }

    @Test
    void shouldDedupUnchangedRepeatedReadWithinWindow(@TempDir Path tempDir) throws Exception {
        // 同文件、同区间、mtime 未变且在近窗内重复读 → 第二次回"文件未变化"短桩，不重发内容（省 token）。
        Path file = tempDir.resolve("stable.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String first = registry.executeTool("read_file", "{\"path\":\"stable.txt\"}");
        String second = registry.executeTool("read_file", "{\"path\":\"stable.txt\"}");

        assertTrue(first.contains("alpha") && first.contains("gamma"), "首次应返回完整内容");
        assertTrue(second.contains("文件未变化"), "二次应回去重短桩: " + second);
        assertTrue(!second.contains("gamma"), "去重短桩不应重发文件内容");
    }

    @Test
    void shouldResendContentAfterFileChangesBetweenReads(@TempDir Path tempDir) throws Exception {
        // mtime 变了就必须重发内容，不能命中去重（防把旧内容当现状）。
        Path file = tempDir.resolve("mut.txt");
        Files.writeString(file, "v1-original\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String first = registry.executeTool("read_file", "{\"path\":\"mut.txt\"}");
        Files.writeString(file, "v2-changed\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_086_400_000L));
        String second = registry.executeTool("read_file", "{\"path\":\"mut.txt\"}");

        assertTrue(first.contains("v1-original"));
        assertTrue(second.contains("v2-changed"), "文件已变应重发新内容: " + second);
        assertTrue(!second.contains("文件未变化"), "变了不应命中去重");
    }

    @Test
    void shouldBlockEditWhenFileChangedAfterRead(@TempDir Path tempDir) throws Exception {
        // 读过之后文件被外部改动（mtime 变）→ edit 应拦截并要求重读，防"静默改错位置"。
        Path file = tempDir.resolve("Conf.java");
        Files.writeString(file, "class Conf {\n  int port = 8080;\n}\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("read_file", "{\"path\":\"Conf.java\"}");
        // 模拟外部修改
        Files.writeString(file, "class Conf {\n  int port = 9090;\n}\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_086_400_000L));

        String result = registry.executeTool("edit_file",
                "{\"path\":\"Conf.java\",\"old_string\":\"int port = 8080;\",\"new_string\":\"int port = 7000;\"}");

        assertTrue(result.contains("已被修改") && result.contains("重新 read_file"),
                "应拦截过期编辑并要求重读: " + result);
        // 文件不应被改动（仍是外部写入的 9090）
        assertTrue(Files.readString(file).contains("9090"), "拦截后不应执行编辑");
    }

    @Test
    void shouldAllowEditWhenNeverReadBefore(@TempDir Path tempDir) throws Exception {
        // 从未 read_file 过的文件直接 edit 仍应允许（保守：不破坏 SageCLI 既有免读直编行为）。
        Path file = tempDir.resolve("Fresh.java");
        Files.writeString(file, "class Fresh {\n  int v = 1;\n}\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"Fresh.java\",\"old_string\":\"int v = 1;\",\"new_string\":\"int v = 2;\"}");

        assertTrue(result.contains("文件已编辑"), "未读过的文件应允许直编: " + result);
        assertTrue(Files.readString(file).contains("int v = 2;"));
    }

    @Test
    void shouldAllowConsecutiveEditsWithoutStaleFalsePositive(@TempDir Path tempDir) throws Exception {
        // 读 → 编辑 → 再编辑：第二次编辑不应因第一次编辑改了 mtime 而误判"已被修改"。
        Path file = tempDir.resolve("Seq.java");
        Files.writeString(file, "class Seq {\n  int a = 1;\n  int b = 2;\n}\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("read_file", "{\"path\":\"Seq.java\"}");
        String e1 = registry.executeTool("edit_file",
                "{\"path\":\"Seq.java\",\"old_string\":\"int a = 1;\",\"new_string\":\"int a = 10;\"}");
        String e2 = registry.executeTool("edit_file",
                "{\"path\":\"Seq.java\",\"old_string\":\"int b = 2;\",\"new_string\":\"int b = 20;\"}");

        assertTrue(e1.contains("文件已编辑"), "首次编辑应成功: " + e1);
        assertTrue(e2.contains("文件已编辑"), "连续编辑不应误判过期: " + e2);
        String finalContent = Files.readString(file);
        assertTrue(finalContent.contains("int a = 10;") && finalContent.contains("int b = 20;"));
    }

    @Test
    void editFileReplacesUniqueMatch(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "class Sample {\n  int x = 1;\n  int y = 2;\n}\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"Sample.java\",\"old_string\":\"  int x = 1;\",\"new_string\":\"  int x = 42;\"}");

        assertTrue(result.contains("文件已编辑"), result);
        String after = Files.readString(file);
        assertTrue(after.contains("int x = 42;"));
        assertTrue(after.contains("int y = 2;"), "未涉及的行应保持不变");
        assertTrue(!after.contains("int x = 1;"));
    }

    @Test
    void editFileFailsWhenNoMatch(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "class Sample {}\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"Sample.java\",\"old_string\":\"不存在的内容\",\"new_string\":\"x\"}");

        assertTrue(result.contains("未找到 old_string"), result);
        assertTrue(Files.readString(file).equals("class Sample {}\n"), "失败时文件不应被修改");
    }

    @Test
    void editFileFailsOnAmbiguousMatchWithoutReplaceAll(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "a = 0;\na = 0;\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"Sample.java\",\"old_string\":\"a = 0;\",\"new_string\":\"a = 1;\"}");

        assertTrue(result.contains("匹配到 2 处"), result);
        assertTrue(Files.readString(file).equals("a = 0;\na = 0;\n"), "歧义匹配时文件不应被修改");
    }

    @Test
    void editFileReplaceAllReplacesEveryMatch(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "a = 0;\na = 0;\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"Sample.java\",\"old_string\":\"a = 0;\",\"new_string\":\"a = 1;\",\"replace_all\":true}");

        assertTrue(result.contains("共替换 2 处"), result);
        assertTrue(Files.readString(file).equals("a = 1;\na = 1;\n"));
    }

    @Test
    void editFileRejectsPathOutsideProjectRoot(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"/etc/passwd\",\"old_string\":\"root\",\"new_string\":\"x\"}");

        assertTrue(result.contains("🛡️ 策略拒绝") || result.contains("路径越界"), result);
    }

    @Test
    void editFileRejectsIdenticalOldAndNew(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "x = 1;\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"Sample.java\",\"old_string\":\"x = 1;\",\"new_string\":\"x = 1;\"}");

        assertTrue(result.contains("相同，无需编辑"), result);
    }

    @Test
    void editFileMatchesCurlyQuotesWithStraightOldString(@TempDir Path tempDir) throws Exception {
        // 文件用排版弯引号，模型给的是直引号——精确匹配会失败，弯引号归一化后应能命中。
        Path file = tempDir.resolve("doc.md");
        Files.writeString(file, "说明：“重要提示”在这里。\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"doc.md\",\"old_string\":\"\\\"重要提示\\\"\",\"new_string\":\"\\\"关键提示\\\"\"}");

        assertTrue(result.contains("文件已编辑"), result);
        String after = Files.readString(file);
        // new_string 的直引号应按文件原风格还原为弯引号
        assertTrue(after.contains("“关键提示”"), "替换后应保留弯引号风格: " + after);
        assertTrue(!after.contains("重要提示"));
    }

    @Test
    void editFileStillFailsWhenTrulyNotFound(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("doc.md");
        Files.writeString(file, "纯直引号 \"abc\" 内容\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("edit_file",
                "{\"path\":\"doc.md\",\"old_string\":\"这段内容文件里没有\",\"new_string\":\"x\"}");

        assertTrue(result.contains("未找到 old_string"), result);
    }

    @Test
    void shouldGlobFilesInsideProject(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), "class UserService {}\n");
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"**/*Service.java\"}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java"));
        assertTrue(!result.contains("README.md"));
    }

    @Test
    void shouldGlobRootFileByName(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"README.md\"}");

        assertTrue(result.contains("README.md"));
    }

    @Test
    void shouldOrderGlobResultsByMostRecentlyModified(@TempDir Path tempDir) throws Exception {
        // glob 结果应按修改时间降序（最近改的排前），与 Claude Code GrepTool 的相关性排序一致。
        Path older = tempDir.resolve("Older.java");
        Path newer = tempDir.resolve("Newer.java");
        Files.writeString(older, "class Older {}\n");
        Files.writeString(newer, "class Newer {}\n");
        // 显式设置修改时间：older 比 newer 早一天，避免依赖写入顺序的时序精度。
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(1_000_086_400_000L));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"*.java\"}");

        int idxNewer = result.indexOf("Newer.java");
        int idxOlder = result.indexOf("Older.java");
        assertTrue(idxNewer >= 0 && idxOlder >= 0, "两个文件都应命中: " + result);
        assertTrue(idxNewer < idxOlder, "最近修改的 Newer.java 应排在 Older.java 之前: " + result);
    }

    @Test
    void shouldGrepCodeWithLineNumbersAndContext(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), String.join("\n",
                "class UserService {",
                "  User getUserById(String id) {",
                "    return repository.findById(id);",
                "  }",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code",
                "{\"pattern\":\"getUserById\",\"glob\":\"**/*.java\",\"context_lines\":1}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java:2"));
        assertTrue(result.contains(">    2 |   User getUserById(String id) {"));
        assertTrue(result.contains("     3 |     return repository.findById(id);"));
    }

    @Test
    void shouldSkipCommonDependencyDirectoriesWhenGrepping(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App { String marker = \"targetSymbol\"; }\n");
        Files.writeString(tempDir.resolve("node_modules/pkg/Generated.java"), "class Generated { String marker = \"targetSymbol\"; }\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code", "{\"pattern\":\"targetSymbol\",\"max_results\":10}");

        assertTrue(result.contains("src/App.java:1"));
        assertTrue(!result.contains("node_modules"));
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"sleep 2\"}");

        assertTrue(result.contains("命令执行超时"));
    }

    @Test
    void shouldExecuteMultipleToolInvocationsInParallelAndKeepResultOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(prev -> Math.max(prev, now));
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "两个工具调用应同时进入执行区");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "first", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "second", "{}")
        ));

        assertEquals(2, peak.get(), "两个工具调用应并行执行");
        assertEquals("call_1", results.get(0).id());
        assertEquals("result-first", results.get(0).result());
        assertEquals("call_2", results.get(1).id());
        assertEquals("result-second", results.get(1).result());
    }

    @Test
    void shouldCancelToolInvocationWhenBatchTimeoutIsReached() {
        ToolRegistry registry = new ToolRegistry(1, 1) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("slow".equals(name)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "slow", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "fast", "{}")
        ));

        assertTrue(results.get(0).timedOut());
        assertTrue(results.get(0).result().contains("工具执行超时"));
        assertEquals("result-fast", results.get(1).result());
    }

    @Test
    void browserConnectToolUsesInjectedConnector() {
        ToolRegistry registry = new ToolRegistry();
        registry.setBrowserConnector(new BrowserConnector() {
            @Override
            public String status() {
                return "status-ok";
            }

            @Override
            public String connectDefault() {
                return "connected";
            }

            @Override
            public String disconnect() {
                return "disconnected";
            }
        });

        assertEquals("connected", registry.executeTool("browser_connect", "{}"));
        assertEquals("status-ok", registry.executeTool("browser_status", "{}"));
        assertEquals("disconnected", registry.executeTool("browser_disconnect", "{}"));
    }

    @Test
    void saveMemoryToolUsesInjectedMemorySaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        String result = registry.executeTool("save_memory", "{\"fact\":\"访问 yuque.com 时复用登录态\"}");

        assertEquals(List.of("访问 yuque.com 时复用登录态"), saved);
        assertTrue(result.contains("已保存到长期记忆"));
    }

    @Test
    void saveMemoryToolPassesScopeToScopedSaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        String result = registry.executeTool("save_memory", "{\"fact\":\"默认用中文回答\",\"scope\":\"global\"}");

        assertEquals(List.of("global:默认用中文回答"), saved);
        assertTrue(result.contains("长期记忆(global)"));
    }
}
