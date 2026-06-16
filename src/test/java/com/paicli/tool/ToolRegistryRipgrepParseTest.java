package com.paicli.tool;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ripgrep --json 输出解析（吸收自作者最终版的搜索引擎重构）。
 * 本机不一定装 rg，所以喂样例 JSON 直接测解析逻辑，不依赖 rg 进程。
 */
class ToolRegistryRipgrepParseTest {

    private static BufferedReader lines(String... jsonLines) {
        return new BufferedReader(new StringReader(String.join("\n", jsonLines)));
    }

    // rg --json 的真实事件格式（精简）：match 行带 path/line_number/lines.text
    private static String match(String file, int line, String text) {
        return "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"" + file
                + "\"},\"line_number\":" + line + ",\"lines\":{\"text\":\"" + text + "\\n\"}}}";
    }

    private static String context(String file, int line, String text) {
        return "{\"type\":\"context\",\"data\":{\"path\":{\"text\":\"" + file
                + "\"},\"line_number\":" + line + ",\"lines\":{\"text\":\"" + text + "\\n\"}}}";
    }

    @Test
    void parsesSingleMatch() throws Exception {
        List<ToolRegistry.GrepMatch> r = ToolRegistry.parseRipgrepJson(
                lines(match("src/Foo.java", 12, "UserService svc;")), 50);
        assertEquals(1, r.size());
        assertEquals("src/Foo.java", r.get(0).file());
        assertEquals(12, r.get(0).lineNumber());
        assertEquals("UserService svc;", r.get(0).context().get(0).text());  // stripTrailing 去了 \n
    }

    @Test
    void parsesMultipleMatchesAcrossFiles() throws Exception {
        List<ToolRegistry.GrepMatch> r = ToolRegistry.parseRipgrepJson(lines(
                match("A.java", 3, "foo"),
                match("B.java", 7, "foo")), 50);
        assertEquals(2, r.size());
        assertEquals("A.java", r.get(0).file());
        assertEquals("B.java", r.get(1).file());
    }

    @Test
    void attachesContextLinesToMatch() throws Exception {
        // before-context 先到 → match → after-context；都该归到这个 match
        List<ToolRegistry.GrepMatch> r = ToolRegistry.parseRipgrepJson(lines(
                context("A.java", 2, "before"),
                match("A.java", 3, "hit"),
                context("A.java", 4, "after")), 50);
        assertEquals(1, r.size());
        List<ToolRegistry.ContextLine> ctx = r.get(0).context();
        assertEquals(3, ctx.size());
        assertEquals("before", ctx.get(0).text());
        assertEquals("hit", ctx.get(1).text());
        assertEquals("after", ctx.get(2).text());
    }

    @Test
    void respectsMaxResults() throws Exception {
        List<ToolRegistry.GrepMatch> r = ToolRegistry.parseRipgrepJson(lines(
                match("A.java", 1, "x"),
                match("A.java", 2, "x"),
                match("A.java", 3, "x")), 2);
        assertTrue(r.size() <= 2, "不应超过 maxResults");
    }

    @Test
    void emptyOutputYieldsNoMatches() throws Exception {
        List<ToolRegistry.GrepMatch> r = ToolRegistry.parseRipgrepJson(lines(""), 50);
        assertTrue(r.isEmpty());
    }
}
