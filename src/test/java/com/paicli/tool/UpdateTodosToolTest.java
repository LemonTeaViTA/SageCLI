package com.paicli.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * update_todos 工具：会话级任务清单草稿板（学自 CC TodoWriteTool）。
 * 验证注册、结构化数组解析、三态校验、单 in_progress 约束、全完成清空、整表替换。
 */
class UpdateTodosToolTest {

    @Test
    void shouldRegisterUpdateTodosTool() {
        ToolRegistry registry = new ToolRegistry();
        boolean present = registry.getToolDefinitions().stream()
                .anyMatch(t -> t.name().equals("update_todos"));
        assertTrue(present, "update_todos 应被注册为工具");
    }

    @Test
    void shouldParseStructuredTodoArrayAndStore() {
        ToolRegistry registry = new ToolRegistry();
        String json = "{\"todos\":[" +
                "{\"content\":\"读源码\",\"activeForm\":\"正在读源码\",\"status\":\"completed\"}," +
                "{\"content\":\"写实现\",\"activeForm\":\"正在写实现\",\"status\":\"in_progress\"}," +
                "{\"content\":\"跑测试\",\"activeForm\":\"正在跑测试\",\"status\":\"pending\"}]}";
        String result = registry.executeTool("update_todos", json);

        assertTrue(result.contains("任务清单已更新"), "应回显清单: " + result);
        List<ToolRegistry.TodoItem> todos = registry.getTodos();
        assertEquals(3, todos.size(), "应存 3 条 todo");
        assertEquals("写实现", todos.get(1).content());
        assertEquals("in_progress", todos.get(1).status());
    }

    @Test
    void shouldShowActiveFormForInProgressItem() {
        ToolRegistry registry = new ToolRegistry();
        String json = "{\"todos\":[" +
                "{\"content\":\"写实现\",\"activeForm\":\"正在写实现\",\"status\":\"in_progress\"}]}";
        String result = registry.executeTool("update_todos", json);
        assertTrue(result.contains("正在写实现"), "in_progress 项应显示进行态文案: " + result);
    }

    @Test
    void shouldRejectMoreThanOneInProgress() {
        ToolRegistry registry = new ToolRegistry();
        String json = "{\"todos\":[" +
                "{\"content\":\"A\",\"activeForm\":\"做A\",\"status\":\"in_progress\"}," +
                "{\"content\":\"B\",\"activeForm\":\"做B\",\"status\":\"in_progress\"}]}";
        String result = registry.executeTool("update_todos", json);
        assertTrue(result.contains("只允许一个 in_progress"), "应拒绝多个 in_progress: " + result);
        assertTrue(registry.getTodos().isEmpty(), "校验失败时不应写入状态");
    }

    @Test
    void shouldRejectInvalidStatus() {
        ToolRegistry registry = new ToolRegistry();
        String json = "{\"todos\":[" +
                "{\"content\":\"A\",\"activeForm\":\"做A\",\"status\":\"doing\"}]}";
        String result = registry.executeTool("update_todos", json);
        assertTrue(result.contains("status 必须是"), "应拒绝非法 status: " + result);
    }

    @Test
    void shouldRejectEmptyContent() {
        ToolRegistry registry = new ToolRegistry();
        String json = "{\"todos\":[" +
                "{\"content\":\"\",\"activeForm\":\"做A\",\"status\":\"pending\"}]}";
        String result = registry.executeTool("update_todos", json);
        assertTrue(result.contains("content 不能为空"), "应拒绝空 content: " + result);
    }

    @Test
    void shouldClearListWhenAllCompleted() {
        ToolRegistry registry = new ToolRegistry();
        // 先建两条
        registry.executeTool("update_todos", "{\"todos\":[" +
                "{\"content\":\"A\",\"activeForm\":\"做A\",\"status\":\"in_progress\"}," +
                "{\"content\":\"B\",\"activeForm\":\"做B\",\"status\":\"pending\"}]}");
        assertFalse(registry.getTodos().isEmpty(), "建表后应非空");

        // 全部完成 → 清空
        String result = registry.executeTool("update_todos", "{\"todos\":[" +
                "{\"content\":\"A\",\"activeForm\":\"做A\",\"status\":\"completed\"}," +
                "{\"content\":\"B\",\"activeForm\":\"做B\",\"status\":\"completed\"}]}");
        assertTrue(result.contains("全部") && result.contains("已完成"), "应回收尾提示: " + result);
        assertTrue(registry.getTodos().isEmpty(), "全部完成后清单应清空");
    }

    @Test
    void shouldReplaceWholeListEachCall() {
        ToolRegistry registry = new ToolRegistry();
        registry.executeTool("update_todos", "{\"todos\":[" +
                "{\"content\":\"旧A\",\"activeForm\":\"做旧A\",\"status\":\"pending\"}," +
                "{\"content\":\"旧B\",\"activeForm\":\"做旧B\",\"status\":\"pending\"}]}");
        // 第二次只传一条 → 整表替换，旧的不残留
        registry.executeTool("update_todos", "{\"todos\":[" +
                "{\"content\":\"新C\",\"activeForm\":\"做新C\",\"status\":\"in_progress\"}]}");
        List<ToolRegistry.TodoItem> todos = registry.getTodos();
        assertEquals(1, todos.size(), "应整表替换为 1 条");
        assertEquals("新C", todos.get(0).content());
    }

    @Test
    void shouldRejectNonArrayTodos() {
        ToolRegistry registry = new ToolRegistry();
        // todos 传成对象（容器节点，走保留原始 JSON 的解析路径）→ 非数组应被拒。
        String result = registry.executeTool("update_todos", "{\"todos\":{\"foo\":\"bar\"}}");
        assertTrue(result.contains("必须是数组"), "应拒绝非数组: " + result);
    }

    @Test
    void shouldRejectZeroInProgressWhenWorkRemains() {
        ToolRegistry registry = new ToolRegistry();
        // 还有 pending 却没有任何 in_progress → 清单停摆，应被拒（恰好一个在做）。
        String json = "{\"todos\":[" +
                "{\"content\":\"A\",\"activeForm\":\"做A\",\"status\":\"completed\"}," +
                "{\"content\":\"B\",\"activeForm\":\"做B\",\"status\":\"pending\"}]}";
        String result = registry.executeTool("update_todos", json);
        assertTrue(result.contains("恰好有一个 in_progress"), "应拒绝 0 个 in_progress: " + result);
        assertTrue(registry.getTodos().isEmpty(), "校验失败时不应写入状态");
    }

    @Test
    void shouldAllowZeroInProgressWhenAllCompleted() {
        ToolRegistry registry = new ToolRegistry();
        // 全部 completed 是合法收尾态：0 个 in_progress 不应被下界约束拦截（应走清空逻辑）。
        String json = "{\"todos\":[" +
                "{\"content\":\"A\",\"activeForm\":\"做A\",\"status\":\"completed\"}]}";
        String result = registry.executeTool("update_todos", json);
        assertFalse(result.contains("恰好有一个 in_progress"), "全完成不应触发下界约束: " + result);
        assertTrue(registry.getTodos().isEmpty(), "全部完成后清单应清空");
    }
}
