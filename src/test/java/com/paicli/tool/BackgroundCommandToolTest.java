package com.paicli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * execute_command 后台执行（run_in_background）+ check_command / kill_command 轮询版。
 * 验证：注册、立即返回 task_id、状态轮询、输出捕获、exit code、kill、列全部、CommandGuard 仍生效。
 */
class BackgroundCommandToolTest {

    /** 轮询等待某后台任务进入终态（COMPLETED/FAILED/CANCELED），超时返回最后一次 check 文本。 */
    private static String waitUntilTerminal(ToolRegistry registry, String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            last = registry.executeTool("check_command", "{\"task_id\":\"" + taskId + "\"}");
            if (last.contains("completed") || last.contains("failed") || last.contains("canceled")) {
                return last;
            }
            Thread.sleep(50);
        }
        return last;
    }

    private static String extractTaskId(String launchResult) {
        int idx = launchResult.indexOf("task_id=");
        assertTrue(idx >= 0, "启动结果应含 task_id: " + launchResult);
        String rest = launchResult.substring(idx + "task_id=".length());
        // task_id 形如 bg_xxx，取到第一个非 [a-zA-Z0-9_] 为止
        int end = 0;
        while (end < rest.length() && (Character.isLetterOrDigit(rest.charAt(end)) || rest.charAt(end) == '_')) {
            end++;
        }
        return rest.substring(0, end);
    }

    @Test
    void shouldRegisterBackgroundTools() {
        ToolRegistry registry = new ToolRegistry();
        var names = registry.getToolDefinitions().stream().map(t -> t.name()).toList();
        assertTrue(names.contains("check_command"), "应注册 check_command");
        assertTrue(names.contains("kill_command"), "应注册 kill_command");
    }

    @Test
    void shouldLaunchInBackgroundAndReturnTaskIdImmediately(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        long start = System.currentTimeMillis();
        // sleep 2 但后台立即返回，不应阻塞 2 秒
        String result = registry.executeTool("execute_command",
                "{\"command\":\"sleep 2; echo done\",\"run_in_background\":true}");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result.contains("task_id="), "应返回 task_id: " + result);
        assertTrue(elapsed < 1500, "后台启动应立即返回，不阻塞，实际耗时 " + elapsed + "ms");
    }

    @Test
    void shouldCaptureOutputAndExitCode(@TempDir Path tempDir) throws InterruptedException {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        String launch = registry.executeTool("execute_command",
                "{\"command\":\"echo hello-bg\",\"run_in_background\":true}");
        String taskId = extractTaskId(launch);

        String check = waitUntilTerminal(registry, taskId, 5000);
        assertTrue(check.contains("completed"), "应完成: " + check);
        assertTrue(check.contains("exit code 0"), "应记录 exit code 0: " + check);
        assertTrue(check.contains("hello-bg"), "应捕获输出: " + check);
    }

    @Test
    void shouldRecordNonZeroExitAsFailed(@TempDir Path tempDir) throws InterruptedException {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        String launch = registry.executeTool("execute_command",
                "{\"command\":\"exit 3\",\"run_in_background\":true}");
        String taskId = extractTaskId(launch);

        String check = waitUntilTerminal(registry, taskId, 5000);
        assertTrue(check.contains("failed"), "非 0 退出应为 failed: " + check);
        assertTrue(check.contains("exit code 3"), "应记录 exit code 3: " + check);
    }

    @Test
    void shouldKillRunningTask(@TempDir Path tempDir) throws InterruptedException {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        String launch = registry.executeTool("execute_command",
                "{\"command\":\"sleep 30\",\"run_in_background\":true}");
        String taskId = extractTaskId(launch);

        String killResult = registry.executeTool("kill_command", "{\"task_id\":\"" + taskId + "\"}");
        assertTrue(killResult.contains("已终止"), "应终止: " + killResult);

        // 终止后状态应为 canceled
        Thread.sleep(200);
        String check = registry.executeTool("check_command", "{\"task_id\":\"" + taskId + "\"}");
        assertTrue(check.contains("canceled"), "终止后应为 canceled: " + check);
    }

    @Test
    void shouldListAllTasksWhenNoTaskId(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        // 无任务时
        assertTrue(registry.executeTool("check_command", "{}").contains("没有后台任务"));
        // 起一个
        registry.executeTool("execute_command",
                "{\"command\":\"sleep 5\",\"run_in_background\":true}");
        String list = registry.executeTool("check_command", "{}");
        assertTrue(list.contains("后台任务") && list.contains("bg_"), "应列出任务: " + list);
    }

    @Test
    void shouldStillEnforceCommandGuardInBackground(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        // 危险命令即使走后台也应被 CommandGuard 拒（策略拒绝），不应启动
        String result = registry.executeTool("execute_command",
                "{\"command\":\"find / -name x\",\"run_in_background\":true}");
        assertTrue(result.contains("策略拒绝"), "后台路径也应过 CommandGuard: " + result);
        assertFalse(result.contains("task_id="), "被拒命令不应返回 task_id");
    }

    @Test
    void shouldReturnFriendlyErrorForUnknownTaskId() {
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.executeTool("check_command", "{\"task_id\":\"bg_nope\"}").contains("未找到"));
        assertTrue(registry.executeTool("kill_command", "{\"task_id\":\"bg_nope\"}").contains("未找到"));
    }
}
