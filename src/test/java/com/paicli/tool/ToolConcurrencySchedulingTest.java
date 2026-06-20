package com.paicli.tool;

import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * executeTools 的读并行 / 写串行调度（对齐 CC 的 isConcurrencySafe/isReadOnly）。
 * 重点验证调度逻辑本身：混合读写时结果按输入顺序返回、id 配对、读写各自正确执行、屏障 flush 不丢不乱序。
 * （读并行的提速是 timing 行为，不在单测里断言，避免 flaky；这里保证正确性。）
 */
class ToolConcurrencySchedulingTest {

    private static ToolInvocation read(String id, String relPath) {
        return new ToolInvocation(id, "read_file", "{\"path\":\"" + relPath + "\"}");
    }

    private static ToolInvocation write(String id, String relPath, String content) {
        return new ToolInvocation(id, "write_file",
                "{\"path\":\"" + relPath + "\",\"content\":\"" + content + "\"}");
    }

    @Test
    void shouldPreserveInputOrderForAllReadOnlyBatch(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "AAA");
        Files.writeString(tempDir.resolve("b.txt"), "BBB");
        Files.writeString(tempDir.resolve("c.txt"), "CCC");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        List<ToolExecutionResult> results = registry.executeTools(List.of(
                read("r1", "a.txt"), read("r2", "b.txt"), read("r3", "c.txt")));

        assertEquals(3, results.size());
        // 结果按输入顺序，id 配对
        assertEquals("r1", results.get(0).id());
        assertEquals("r2", results.get(1).id());
        assertEquals("r3", results.get(2).id());
        assertTrue(results.get(0).result().contains("AAA"), "r1 应读到 a.txt");
        assertTrue(results.get(2).result().contains("CCC"), "r3 应读到 c.txt");
    }

    @Test
    void shouldSerializeWritesAndPreserveOrderInMixedBatch(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "AAA");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // 读 a → 写 new1（写工具作屏障）→ 读 a 又一次：结果必须严格按输入顺序返回
        List<ToolExecutionResult> results = registry.executeTools(List.of(
                read("r1", "a.txt"),
                write("w1", "new1.txt", "hello"),
                read("r2", "a.txt")));

        assertEquals(3, results.size());
        assertEquals("r1", results.get(0).id());
        assertEquals("w1", results.get(1).id());
        assertEquals("r2", results.get(2).id());
        assertTrue(results.get(1).result().contains("已写入"), "写应成功: " + results.get(1).result());
        assertTrue(Files.exists(tempDir.resolve("new1.txt")), "写文件应落盘");
    }

    @Test
    void shouldHandleConsecutiveWritesSerially(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // 连续两个写（都新建，互不依赖）：串行执行，顺序保留
        List<ToolExecutionResult> results = registry.executeTools(List.of(
                write("w1", "f1.txt", "one"),
                write("w2", "f2.txt", "two")));

        assertEquals(2, results.size());
        assertEquals("w1", results.get(0).id());
        assertEquals("w2", results.get(1).id());
        assertTrue(Files.exists(tempDir.resolve("f1.txt")));
        assertTrue(Files.exists(tempDir.resolve("f2.txt")));
    }

    @Test
    void shouldHandleReadsTrailingAfterWrite(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "AAA");
        Files.writeString(tempDir.resolve("b.txt"), "BBB");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // 写在前、末尾两个读攒成一批：验证收尾 flush 不丢结果
        List<ToolExecutionResult> results = registry.executeTools(List.of(
                write("w1", "new.txt", "x"),
                read("r1", "a.txt"),
                read("r2", "b.txt")));

        assertEquals(3, results.size());
        assertEquals("w1", results.get(0).id());
        assertEquals("r1", results.get(1).id());
        assertEquals("r2", results.get(2).id());
        assertTrue(results.get(1).result().contains("AAA"));
        assertTrue(results.get(2).result().contains("BBB"));
    }
}
