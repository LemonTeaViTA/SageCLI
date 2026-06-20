package com.paicli.tool;

import com.paicli.policy.EnvironmentSanitizer;
import com.paicli.runtime.task.TaskStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 后台命令管理器（execute_command 的 run_in_background 路径用）。
 *
 * <p>设计取舍——这是"最小可用轮询版"，刻意只解决进程生命周期，不碰主循环：
 * <ul>
 *   <li>进程要活过单次工具调用 → 用会话级 Map 持有活 {@link Process}（同步版进程包在方法里，返回即终结）。</li>
 *   <li>结果怎么回到模型 → <b>轮询</b>：模型用 check_command 主动查，不主动注入对话。
 *       回避了"后台线程跨线程改 conversationHistory / 协议配对 / turn 已退出"这一整套难题
 *       （CC 用独立 task-notification 消息 + 空闲观察者重启 turn 解决，那是事件驱动的大改动）。</li>
 *   <li>输出在<b>有界内存尾部缓冲</b>（保末尾，与命令报错/exit code 在尾部一致），不落盘 →
 *       绕开 PathGuard、read_file 5MB 上限、文件清理。</li>
 *   <li>ShutdownHook <b>懒注册</b>（首次启动后台任务才注册）→ 从不起后台的会话/测试零开销，且退出时杀光残留进程防僵尸。</li>
 * </ul>
 *
 * <p>安��：CommandGuard 检查与 HITL 审批在进入本类之前已由 ToolRegistry / HitlToolRegistry 完成
 * （后台路径留在 execute_command 工具内，不另起工具），本类不重复也不绕过。
 */
final class BackgroundCommandManager {

    /** 同时存活的后台任务上限，防进程失控。 */
    static final int MAX_BACKGROUND_TASKS = 8;
    /** 每个任务保留的输出尾部字节上限（与 execute_command 同步路径的 8000 对齐量级，稍宽）。 */
    static final int MAX_TAIL_CHARS = 16_000;
    /** kill 时 SIGTERM 后等待进程优雅退出的时间，超时再 SIGKILL。 */
    private static final int GRACEFUL_KILL_WAIT_SECONDS = 2;

    private final Map<String, BgTask> tasks = new ConcurrentHashMap<>();
    private final Supplier<String> projectPathSupplier;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    BackgroundCommandManager(Supplier<String> projectPathSupplier) {
        this.projectPathSupplier = projectPathSupplier;
    }

    /**
     * 在后台启动命令，立即返回 task_id（不阻塞）。
     * 调用方须保证 command 已通过 CommandGuard / HITL。
     */
    String launch(String command) {
        // 清理已终结的旧任务，避免上限被历史任务占满。
        pruneTerminated();
        long live = tasks.values().stream().filter(t -> !isTerminal(t.status)).count();
        if (live >= MAX_BACKGROUND_TASKS) {
            return "后台任务已达上限（" + MAX_BACKGROUND_TASKS + " 个同时运行）。请先用 check_command 查看、"
                    + "用 kill_command 终止不再需要的任务，或等已有任务完成。";
        }

        String taskId = "bg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(new File(projectPathSupplier.get()));
        EnvironmentSanitizer.sanitize(pb.environment());
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            return "后台启动失败: " + e.getMessage();
        }

        BgTask task = new BgTask(taskId, command, process);
        tasks.put(taskId, task);
        task.startReader();
        ensureShutdownHook();

        return "命令已在后台启动，task_id=" + taskId + "。\n"
                + "用 check_command 查询状态/输出（不要 sleep 轮询，先去做别的事再回来查），"
                + "用 kill_command 终止。";
    }

    /** 查询：taskId 为空 → 列出全部任务概要；否则 → 单任务状态 + 输出尾部。 */
    String check(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return listAll();
        }
        BgTask task = tasks.get(taskId.trim());
        if (task == null) {
            return "未找到后台任务: " + taskId + "（可能 ID 写错，或任务从未启动）。用不带参数的 check_command 列出全部。";
        }
        return task.describe();
    }

    /** 两阶段终止：先 SIGTERM（destroy），超时再 SIGKILL（destroyForcibly）。 */
    String kill(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return "kill_command 需要 task_id。用不带参数的 check_command 列出全部任务。";
        }
        BgTask task = tasks.get(taskId.trim());
        if (task == null) {
            return "未找到后台任务: " + taskId;
        }
        if (isTerminal(task.status)) {
            return "任务 " + taskId + " 已结束（" + task.status.value() + "），无需终止。";
        }
        task.kill();
        return "已终止后台任务 " + taskId + "。";
    }

    /** 会话退出时杀光所有存活进程（ShutdownHook 调用）。 */
    void shutdownAll() {
        for (BgTask task : tasks.values()) {
            if (!isTerminal(task.status)) {
                task.kill();
            }
        }
    }

    private String listAll() {
        if (tasks.isEmpty()) {
            return "当前没有后台任务。";
        }
        StringBuilder sb = new StringBuilder("后台任务（共 " + tasks.size() + " 个）：\n");
        for (BgTask task : tasks.values()) {
            sb.append("- ").append(task.taskId)
                    .append("  [").append(task.status.value()).append("]");
            if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED) {
                sb.append(" exit=").append(task.exitCode);
            }
            sb.append("  ").append(preview(task.command)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private void pruneTerminated() {
        // 只保留最近的终结任务有意义，但简单起见：终结任务超过上限的 2 倍时清掉最老的终结项。
        if (tasks.size() <= MAX_BACKGROUND_TASKS * 2) {
            return;
        }
        List<String> terminatedIds = new ArrayList<>();
        for (BgTask t : tasks.values()) {
            if (isTerminal(t.status)) {
                terminatedIds.add(t.taskId);
            }
        }
        // 留一些方便回查，超出的删。
        int removable = terminatedIds.size() - MAX_BACKGROUND_TASKS;
        for (int i = 0; i < removable; i++) {
            tasks.remove(terminatedIds.get(i));
        }
    }

    private void ensureShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(this::shutdownAll, "paicli-bg-command-shutdown"));
        }
    }

    private static String preview(String s) {
        String oneLine = s.replace('\n', ' ').trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }

    /** TaskStatus 本身没有 terminal()（那是 DurableTask 上的），这里就地判定终态。 */
    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELED;
    }

    /** 单个后台任务：持有活进程 + 读输出线程 + 有界尾部缓冲。 */
    private static final class BgTask {
        final String taskId;
        final String command;
        final Process process;
        final Instant startedAt = Instant.now();
        // 尾部缓冲 + 状态/退出码由 reader 线程写、check 线程读，故 volatile + synchronized 缓冲。
        private final StringBuilder tail = new StringBuilder();
        private volatile boolean truncated = false;
        volatile TaskStatus status = TaskStatus.RUNNING;
        volatile int exitCode = -1;
        private Thread reader;

        BgTask(String taskId, String command, Process process) {
            this.taskId = taskId;
            this.command = command;
            this.process = process;
        }

        void startReader() {
            reader = new Thread(this::readLoop, "paicli-bg-" + taskId);
            reader.setDaemon(true);
            reader.start();
        }

        private void readLoop() {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int highWater = MAX_TAIL_CHARS * 2;
                while ((line = r.readLine()) != null) {
                    synchronized (tail) {
                        tail.append(line).append('\n');
                        if (tail.length() > highWater) {
                            tail.delete(0, tail.length() - MAX_TAIL_CHARS);
                            truncated = true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 读流异常（进程被杀等）不致命，状态由 waitFor 决定。
            }
            try {
                int code = process.waitFor();
                exitCode = code;
                // kill() 已置 CANCELED 时不覆盖。
                if (status == TaskStatus.RUNNING) {
                    status = code == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (status == TaskStatus.RUNNING) {
                    status = TaskStatus.FAILED;
                }
            }
        }

        void kill() {
            status = TaskStatus.CANCELED;
            process.destroy();
            try {
                if (!process.waitFor(GRACEFUL_KILL_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("后台任务 ").append(taskId).append('\n');
            sb.append("状态: ").append(status.value());
            if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) {
                sb.append("  (exit code ").append(exitCode).append(')');
            }
            sb.append('\n');
            sb.append("命令: ").append(preview(command)).append('\n');
            String out = snapshotTail();
            if (out.isEmpty()) {
                sb.append("输出: (暂无)");
            } else {
                sb.append("输出").append(truncated ? "（头部已截断，保留末尾 " + MAX_TAIL_CHARS + " 字符）" : "")
                        .append(":\n").append(out);
            }
            return sb.toString();
        }

        private String snapshotTail() {
            synchronized (tail) {
                return tail.toString().stripTrailing();
            }
        }
    }
}
