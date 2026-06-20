package com.paicli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * write_file 失效检测（对齐 CC FileWriteTool.validateInput）。
 *
 * <p>补齐文件三件套唯一缺失效检测的工具。write 比 edit 更需要这道闸：edit 有 old_string 当内容校验，
 * write 是整文件覆写无校验，盲目覆写会静默抹掉用户/linter 改动。
 */
class WriteFileStalenessTest {

    private static String writeJson(String path, String content) {
        // 简单转义：测试内容不含特殊字符。
        return "{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}";
    }

    @Test
    void shouldAllowCreatingNewFile(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        String result = registry.executeTool("write_file", writeJson("new.txt", "hello"));
        assertTrue(result.contains("已写入"), "新建文件应放行: " + result);
        assertTrue(Files.exists(tempDir.resolve("new.txt")));
    }

    @Test
    void shouldBlockOverwriteOfExistingNeverReadFile(@TempDir Path tempDir) throws Exception {
        Path f = tempDir.resolve("exists.txt");
        Files.writeString(f, "原有内容");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // 已存在但从没 read_file 过 → 拦
        String result = registry.executeTool("write_file", writeJson("exists.txt", "覆写"));
        assertTrue(result.contains("尚未读取"), "已存在但没读过应拦: " + result);
        // 文件不应被改
        assertEquals("原有内容", Files.readString(f), "拦截时不应写入");
    }

    @Test
    void shouldAllowOverwriteAfterRead(@TempDir Path tempDir) throws Exception {
        Path f = tempDir.resolve("read-then-write.txt");
        Files.writeString(f, "原有内容");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // 先 read_file 建台账
        registry.executeTool("read_file", "{\"path\":\"read-then-write.txt\"}");
        // 再 write → 放行
        String result = registry.executeTool("write_file", writeJson("read-then-write.txt", "新内容"));
        assertTrue(result.contains("已写入"), "读过后覆写应放行: " + result);
        assertEquals("新内容", Files.readString(f));
    }

    @Test
    void shouldBlockOverwriteWhenModifiedSinceRead(@TempDir Path tempDir) throws Exception {
        Path f = tempDir.resolve("changed.txt");
        Files.writeString(f, "v1");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("read_file", "{\"path\":\"changed.txt\"}");
        // 模拟外部修改：改内容并显式抬高 mtime（避免同毫秒内 mtime 不变导致漏判）
        Thread.sleep(20);
        Files.writeString(f, "外部改动");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        String result = registry.executeTool("write_file", writeJson("changed.txt", "模型覆写"));
        assertTrue(result.contains("已被修改"), "读后被改应拦: " + result);
        assertEquals("外部改动", Files.readString(f), "拦截时不应覆写用户改动");
    }

    @Test
    void shouldAllowConsecutiveWritesToOwnFile(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // 第一次新建（放行）→ 写后刷台账
        registry.executeTool("write_file", writeJson("own.txt", "v1"));
        // 第二次覆写自己刚写的文件 → 台账已记，mtime 匹配 → 放行（不因"没 read_file"被拦）
        String result = registry.executeTool("write_file", writeJson("own.txt", "v2"));
        assertTrue(result.contains("已写入"), "覆写自己写的文件应放行: " + result);
        assertEquals("v2", Files.readString(tempDir.resolve("own.txt")));
    }

    @Test
    void shouldBlockOverwriteWhenOnlyPartiallyRead(@TempDir Path tempDir) throws Exception {
        // 多行文件，只用 offset/limit 读一段 → 没看过整文件 → write 整文件覆写应被拦（对齐 CC isPartialView）
        Path f = tempDir.resolve("multiline.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("line ").append(i).append('\n');
        }
        Files.writeString(f, sb.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        // 只读前 5 行（分页读）
        registry.executeTool("read_file", "{\"path\":\"multiline.txt\",\"offset\":0,\"limit\":5}");
        String result = registry.executeTool("write_file", writeJson("multiline.txt", "整体覆写"));
        assertTrue(result.contains("offset/limit") || result.contains("部分"),
                "只读一段不应允许整文件覆写: " + result);
        assertTrue(Files.readString(f).startsWith("line 1"), "拦截时不应覆写");
    }

    @Test
    void shouldAllowOverwriteAfterFullRead(@TempDir Path tempDir) throws Exception {
        // 对照：完整读（不带 offset/limit）后 write 应放行
        Path f = tempDir.resolve("fullread.txt");
        Files.writeString(f, "abc\ndef\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("read_file", "{\"path\":\"fullread.txt\"}");
        String result = registry.executeTool("write_file", writeJson("fullread.txt", "新内容"));
        assertTrue(result.contains("已写入"), "完整读后覆写应放行: " + result);
    }
}
