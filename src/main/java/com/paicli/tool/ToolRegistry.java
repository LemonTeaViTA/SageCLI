package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.browser.BrowserAuditMetadata;
import com.paicli.browser.BrowserCheckResult;
import com.paicli.browser.BrowserConnector;
import com.paicli.browser.BrowserGuard;
import com.paicli.context.ContextProfile;
import com.paicli.lsp.LspDiagnosticReport;
import com.paicli.lsp.LspManager;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.SearchResultFormatter;
import com.paicli.rag.VectorStore;
import com.paicli.policy.AuditLog;
import com.paicli.policy.CommandGuard;
import com.paicli.policy.EnvironmentSanitizer;
import com.paicli.policy.PathGuard;
import com.paicli.policy.PolicyException;
import com.paicli.runtime.CancellationContext;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.SnapshotService;
import com.paicli.skill.Skill;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.skill.SkillRegistry;
import com.paicli.web.FetchResult;
import com.paicli.web.HtmlExtractor;
import com.paicli.web.NetworkPolicy;
import com.paicli.web.SearchProvider;
import com.paicli.web.SearchProviderFactory;
import com.paicli.web.SearchResult;
import com.paicli.web.WebFetcher;

import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;
    private static final int MAX_READ_FILE_LINES = 2_000;
    // read_file 不带 offset/limit 时全文读取的字节上限。与 write 的 5MB 对称：
    // 超限直接抛错（提示用 offset/limit 或 grep_code），不再降级读前 N 行——
    // 截断塞进上下文的"文件头部"会随历史逐轮重发烧 token，且大概率不是模型要的内容。
    private static final long MAX_READ_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_GREP_RESULTS = 200;
    // glob 候选收集上限：先收集全部命中（受围栏 + 排除目录限制），再按 mtime 排序、截到 max_results。
    // 设上限防 `**/*` 这类模式在超大树上无界占内存；超过则只在已收集的候选里排序。
    private static final int MAX_GLOB_SCAN = 5_000;
    private static final int MAX_GREP_CONTEXT_LINES = 5;
    private static final long MAX_SEARCH_FILE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> SEARCH_EXCLUDED_DIRS = Set.of(
            ".git", ".paicli", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    // 需要审计的内置工具（与 ApprovalPolicy 的 DANGEROUS_TOOLS 保持一致）；MCP 工具按前缀动态纳入审计。
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "edit_file", "execute_command", "create_project", "revert_turn");
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;
    private String projectPath = System.getProperty("user.dir");
    private PathGuard pathGuard = new PathGuard(projectPath);
    private final AuditLog auditLog = new AuditLog();
    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;
    private ContextProfile contextProfile = ContextProfile.from(null);
    private BrowserGuard browserGuard;
    private BrowserConnector browserConnector;
    private BiConsumer<String, String> memorySaver;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private java.util.function.BiConsumer<String, String[]> writeFileObserver = (p, ba) -> {};
    private LspManager lspManager = new LspManager(projectPath);
    private SnapshotService snapshotService = SnapshotService.forProject(Path.of(projectPath));
    private boolean customSnapshotService;

    // dispatch_agent 用：派生子 agent 需要 LlmClient。ToolRegistry 本身不持有，由 Agent 构造时注入。
    private com.paicli.llm.LlmClient agentDispatchLlmClient;
    // web_fetch 的 AI 摘要用：把抓取的长网页喂给（通常更便宜的）小模型浓缩，主模型只看结论。
    // 由主程序注入（配了 webFetchSummaryProvider 用专属模型，否则复用主模型）；未注入则降级返回原文截断。
    private com.paicli.llm.LlmClient webFetchSummaryClient;
    // 防递归深度计数：进入 dispatch_agent +1，退出 -1；>1 直接拒绝，防子 agent 再派孙 agent。
    private final java.util.concurrent.atomic.AtomicInteger agentDispatchDepth = new java.util.concurrent.atomic.AtomicInteger();
    // 检索子 agent 的只读工具白名单：只给检索类，不给写/执行/快照，更不含 dispatch_agent 本身（防递归主闸）。
    private static final Set<String> AGENT_INVESTIGATION_TOOLS = Set.of(
            "read_file", "list_dir", "glob_files", "grep_code", "search_code");

    // 读取台账（会话级，随 ToolRegistry 实例生命周期）：key=规范化绝对路径，value=最近一次读取的快照。
    // 支撑两件事：① read 去重（同文件同区间、mtime 未变 → 回桩不重发内容，省 token）；
    //            ② edit 失效检测（编辑前比对 mtime，变了就要求重读，防"静默改错位置"）。
    // 用 ConcurrentHashMap：工具可能并行执行（见 MAX_PARALLEL_TOOLS）。
    private final Map<String, ReadRecord> readFileState = new ConcurrentHashMap<>();
    // 单调递增的读取序号；去重只在"最近 DEDUP_WINDOW 次读取内"生效（保守策略）。
    // 理由：上下文被压缩后早轮内容可能已不在窗口里，台账却还记着"读过"——限定近窗 + 桩里给逃生口，避免模型看不到内容又无法重读。
    private final java.util.concurrent.atomic.AtomicLong readSeq = new java.util.concurrent.atomic.AtomicLong();
    private static final long DEDUP_WINDOW = 8;

    /** 一次读取的快照：mtime（毫秒）、读取的区间（offset/limit，整段读为 null）、当时的读取序号。 */
    private record ReadRecord(long mtimeMs, Integer offset, Integer limit, long seq) {}

    // 会话级任务清单（update_todos 工具用）：学自 CC TodoWriteTool——模型自己管的草稿板，不是执行引擎。
    // 无依赖/无拓扑/无执行（那是 /plan DAG 的职责），只是让模型把中等复杂任务的步骤外显出来，
    // 减少漏步、进度可见。整表替换式更新；全部完成即清空。随 ToolRegistry 实例生命周期（会话级）。
    private final List<TodoItem> todos =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /** 一条待办：content（祈使态，"运行测试"）、activeForm（进行态，"正在运行测试"）、status（三态）。 */
    public record TodoItem(String content, String activeForm, String status) {}

    // 后台命令管理器（execute_command 的 run_in_background 路径用）。会话级，projectPath 用 supplier 传以应对 setProjectPath 变更。
    private final BackgroundCommandManager backgroundCommands =
            new BackgroundCommandManager(this::getProjectPath);

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds, Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS));
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        // 注册内置工具
        registerFileTools();
        registerShellTools();
        registerTodoTool();
        registerCodeTools();
        registerRagTools();
        registerWebTools();
        registerBrowserTools();
        registerMemoryTools();
        registerSkillTools();
        registerSnapshotTools();
        registerAgentTools();
    }

    /**
     * 注入派生子 agent 所需的 LlmClient（dispatch_agent 工具用）。
     * 未注入时 dispatch_agent 会返回友好错误而非崩溃。由 Agent / PlanExecuteAgent / AgentOrchestrator 构造时接入。
     */
    public void setAgentDispatchLlmClient(com.paicli.llm.LlmClient agentDispatchLlmClient) {
        this.agentDispatchLlmClient = agentDispatchLlmClient;
    }

    /**
     * 注入 web_fetch AI 摘要所用的 LlmClient。配了 webFetchSummaryProvider 时传专属（便宜）模型，
     * 否则主程序传主模型复用。未注入（null）时 web_fetch 带 prompt 也只能降级返回原文截断。
     */
    public void setWebFetchSummaryClient(com.paicli.llm.LlmClient webFetchSummaryClient) {
        this.webFetchSummaryClient = webFetchSummaryClient;
    }

    /**
     * 设置代码检索的项目路径
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
        this.lspManager.setProjectPath(projectPath);
        if (!customSnapshotService) {
            this.snapshotService.close();
            this.snapshotService = SnapshotService.forProject(Path.of(projectPath));
        }
    }

    /**
     * 获取代码检索的项目路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    public void setContextProfile(ContextProfile contextProfile) {
        if (contextProfile != null) {
            this.contextProfile = contextProfile;
        }
    }

    public ContextProfile getContextProfile() {
        return contextProfile;
    }

    public void setBrowserGuard(BrowserGuard browserGuard) {
        this.browserGuard = browserGuard;
    }

    protected BrowserGuard getBrowserGuard() {
        return browserGuard;
    }

    public void setBrowserConnector(BrowserConnector browserConnector) {
        this.browserConnector = browserConnector;
    }

    public void setMemorySaver(Consumer<String> memorySaver) {
        this.memorySaver = memorySaver == null ? null : (fact, scope) -> memorySaver.accept(fact);
    }

    public void setScopedMemorySaver(BiConsumer<String, String> memorySaver) {
        this.memorySaver = memorySaver;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public SkillContextBuffer getSkillContextBuffer() {
        return skillContextBuffer;
    }

    /**
     * 注册 write_file 写入观察者：参数 (path, [before, after])，
     * before == null 表示新建文件或读不出原文。
     * 用于把 write_file 接到行内 diff 渲染等只读副作用里；
     * 观察者抛异常不影响 write_file 主路径。
     */
    public void setWriteFileObserver(java.util.function.BiConsumer<String, String[]> observer) {
        this.writeFileObserver = observer == null ? (p, ba) -> {} : observer;
    }

    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager == null ? new LspManager(projectPath) : lspManager;
        this.lspManager.setProjectPath(projectPath);
    }

    public LspDiagnosticReport flushPendingLspDiagnostics() {
        return lspManager == null ? LspDiagnosticReport.EMPTY : lspManager.flushPendingDiagnostics();
    }

    public SnapshotService getSnapshotService() {
        return snapshotService;
    }

    public void setSnapshotService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService == null ? SnapshotService.forProject(Path.of(projectPath)) : snapshotService;
        this.customSnapshotService = snapshotService != null;
    }

    /**
     * 注册文件操作工具
     */
    private void registerFileTools() {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容（仅限项目根目录之内）；可用 offset/limit 按行读取，避免把大文件整段塞进上下文",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("offset", "integer", "起始行号，1 表示第一行；省略时读取全文", false),
                        new Param("limit", "integer", "最多读取多少行；省略时读取全文，最大 2000 行", false)
                ),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    try {
                        return readFileForTool(safe, args);
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        // write_file 工具
        tools.put("write_file", new Tool(
                "write_file",
                "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content") == null ? "" : args.get("content");
                    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (contentBytes > MAX_WRITE_FILE_BYTES) {
                        throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                                + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
                    }
                    Path safe = pathGuard.resolveSafe(path);
                    // 失效检测（对齐 CC）：覆写已存在文件前确认模型看过且未被外部改动；新建放行。
                    String staleErr = checkWriteStaleness(safe, path);
                    if (staleErr != null) {
                        return staleErr;
                    }
                    String before = null;
                    try {
                        if (Files.exists(safe) && Files.isRegularFile(safe)) {
                            before = Files.readString(safe);
                        }
                    } catch (Exception ignored) {
                        // 二进制 / 大文件 / 编码错读不出来时，前文当 null 处理（diff 退化为长度提示）
                    }
                    try {
                        Path parent = safe.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(safe, content);
                        // 刷新台账：write_file 是模型自己写的，它已知最新内容；记为整段读，
                        // 这样紧接着的 edit_file 不会误判"已被外部修改"。
                        recordRead(safe, null, null);
                        try {
                            writeFileObserver.accept(path, new String[]{before, content});
                        } catch (Exception ignored) {
                            // observer 失败不能影响 write_file 主路径
                        }
                        runPostEditLspHook(path, safe);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        // edit_file 工具：str_replace 式局部编辑（精确字符串替换，不整文件覆写）
        tools.put("edit_file", new Tool(
                "edit_file",
                "局部编辑文件：把文件中唯一出现的 old_string 替换为 new_string（仅限项目根之内）。"
                        + "old_string 必须在文件中唯一匹配——若匹配 0 处会报\"未找到\"，匹配多处会要求补充上下文使其唯一，"
                        + "或设 replace_all=true 替换全部。相比 write_file 整文件覆写，只动改的那一小段，更省 token、更不易改错。",
                createParameters(
                        new Param("path", "string", "文件路径（必须已存在）", true),
                        new Param("old_string", "string", "要被替换的原文片段；需带足够上下文以在文件中唯一定位", true),
                        new Param("new_string", "string", "替换后的新内容", true),
                        new Param("replace_all", "boolean", "是否替换所有匹配，默认 false（要求唯一匹配）", false)
                ),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    return editFileForTool(args.get("path"), safe, args);
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容（仅限项目根目录之内）",
                createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    try {
                        File[] files = safe.toFile().listFiles();
                        if (files == null) {
                            return "目录为空或不存在";
                        }
                        StringBuilder sb = new StringBuilder("目录内容:\n");
                        for (File f : files) {
                            sb.append(f.isDirectory() ? "[D] " : "[F] ")
                              .append(f.getName())
                              .append("\n");
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        ));

        tools.put("glob_files", new Tool(
                "glob_files",
                "按文件名 glob 查找项目内文件（只读、实时、尊重常见忽略目录）；适合先定位候选文件，例如 **/*Service.java",
                createParameters(
                        new Param("pattern", "string", "glob 模式，例如 **/*.java、**/*Controller*、README.md", true),
                        new Param("path", "string", "搜索起始目录，默认 .", false),
                        new Param("max_results", "integer", "最多返回结果数，默认 50，上限 200", false)
                ),
                args -> globFiles(args)
        ));

        tools.put("grep_code", new Tool(
                "grep_code",
                "在项目内按关键字或正则实时搜索代码（只读、返回文件和行号）；适合精确符号/字符串定位，找到后再 read_file 读取上下文",
                createParameters(
                        new Param("pattern", "string", "要搜索的关键字或正则", true),
                        new Param("path", "string", "搜索起始目录，默认 .", false),
                        new Param("glob", "string", "可选文件 glob 过滤，例如 **/*.java", false),
                        new Param("regex", "boolean", "是否按 Java 正则解释 pattern，默认 false 表示字面量搜索", false),
                        new Param("case_sensitive", "boolean", "是否大小写敏感，默认 true", false),
                        new Param("context_lines", "integer", "每条命中前后上下文行数，默认 0，上限 5", false),
                        new Param("max_results", "integer", "最多返回命中数，默认 50，上限 200", false)
                ),
                args -> grepCode(args)
        ));
    }

    private String readFileForTool(Path file, Map<String, String> args) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "读取文件失败: 不是普通文件";
        }
        boolean ranged = args.containsKey("offset") || args.containsKey("limit");
        if (!ranged) {
            long size = Files.size(file);
            if (size > MAX_READ_FILE_BYTES) {
                // 超大文件不整段读、也不降级读前 N 行：直接抛错把决策权还给模型。
                // 理由（对照 Claude Code FileReadTool/limits.ts 实测 #21841）：tool_result 会随对话历史
                // 逐轮重发，截断塞进去的"文件头部"大概率不是模型要的内容，却被反复重发并烧 token，
                // 模型还得再精读一次；而一条短错误（~百字节）把"如何精确取"交回模型，一步走对。
                return "读取文件失败: " + file.getFileName() + " 共 " + size + " 字节，超过 "
                        + (MAX_READ_FILE_BYTES / 1024 / 1024) + "MB 上限，未读取。\n"
                        + "请改用 offset/limit 分页读取指定区间，或用 grep_code 检索关键内容定位行号后再精读。";
            }
            String dedup = dedupStubOrNull(file, null, null);
            if (dedup != null) {
                return dedup;
            }
            String content = "文件内容:\n" + Files.readString(file);
            recordRead(file, null, null);
            return content;
        }

        int offset = Math.max(1, parseInt(args.get("offset"), 1));
        int limit = Math.max(1, Math.min(parseInt(args.get("limit"), 200), MAX_READ_FILE_LINES));
        String dedup = dedupStubOrNull(file, offset, limit);
        if (dedup != null) {
            return dedup;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = lines.size();
        if (offset > total) {
            return "文件内容: " + file.getFileName() + " 共 " + total + " 行，offset 超出范围";
        }

        int from = offset - 1;
        int to = Math.min(from + limit, total);
        StringBuilder sb = new StringBuilder();
        sb.append("文件内容: ").append(file.getFileName())
                .append(" (lines ").append(offset).append("-").append(to)
                .append(" of ").append(total).append(")\n");
        for (int i = from; i < to; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
        }
        if (to < total) {
            sb.append("...(已截断，可用 offset=").append(to + 1).append(" 继续读取)");
        }
        recordRead(file, offset, limit);
        return sb.toString().trim();
    }

    /**
     * 读去重：若同一文件、同一区间在最近 DEDUP_WINDOW 次读取内已读过、且 mtime 未变，
     * 返回一条短桩而不重发文件内容（省 token）。否则返回 null 走正常读取。
     * 保守点：① 超出近窗就重发（防上下文压缩后内容已不在）；② mtime 变了必重读；③ 桩里给逃生口。
     */
    private String dedupStubOrNull(Path file, Integer offset, Integer limit) {
        ReadRecord rec = readFileState.get(canonicalKey(file));
        if (rec == null
                || !Objects.equals(rec.offset(), offset)
                || !Objects.equals(rec.limit(), limit)
                || readSeq.get() - rec.seq() > DEDUP_WINDOW) {
            return null;
        }
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return null;
        }
        if (mtime != rec.mtimeMs()) {
            return null;
        }
        return "文件未变化: " + file.getFileName()
                + "（自上次读取以来未修改，内容同前，已省略以省 token）。"
                + "如该内容已不在上下文，可换 offset/limit 区间或在多轮后重读。";
    }

    /** 记录一次成功读取到台账（mtime + 区间 + 递增序号）。stat 失败则不记录，留待下次正常读。 */
    private void recordRead(Path file, Integer offset, Integer limit) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return;
        }
        readFileState.put(canonicalKey(file), new ReadRecord(mtime, offset, limit, readSeq.incrementAndGet()));
    }

    /** 台账 key：规范化绝对路径（解析符号链接），保证不同写法指向同一文件时命中同一条记录。 */
    private String canonicalKey(Path file) {
        try {
            return file.toRealPath().toString();
        } catch (Exception e) {
            return file.toAbsolutePath().normalize().toString();
        }
    }

    /**
     * write_file 覆写已存在文件前的失效检测（对齐 CC FileWriteTool.validateInput）。
     *
     * <p>write 比 edit 更需要这道闸：edit 有 old_string 当内容校验（匹配不上就拒），而 write 是整文件覆写、
     * 无任何校验，盲目覆写会静默抹掉用户/linter 在这之间的改动。所以：
     * <ul>
     *   <li>文件不存在（新建）→ 放行（无内容可抹）。</li>
     *   <li>已存在但台账里没读过 → 拦，要求先读（write 无 old_string 安全网，比 edit 的"免读直编"更该拦）。</li>
     *   <li>已存在但只读过一段（带 offset/limit 的分页读）→ 拦：没看过整文件就整文件覆写，同样会抹掉没看到的部分（对齐 CC 的 isPartialView）。</li>
     *   <li>已存在且整文件读过、但 mtime 变了 → 拦，要求重读。</li>
     * </ul>
     * 模型自己写过的文件，write/edit 成功后会 recordRead 刷新台账，故连续覆写不会被误拦。
     *
     * @return null 表示放行；非 null 是给模型看的拦截提示。
     */
    private String checkWriteStaleness(Path file, String displayPath) {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return null; // 新建，放行
        }
        ReadRecord prior = readFileState.get(canonicalKey(file));
        if (prior == null) {
            return "写入文件失败: " + displayPath + " 已存在但尚未读取。write_file 会整文件覆写，"
                    + "为避免抹掉你尚未看到的内容，请先 read_file 确认当前内容再覆写。"
                    + "（若只改局部，用 edit_file 更安全、更省 token。）";
        }
        // 只读过一段（分页读）不算看过整文件：整文件覆写会抹掉没读到的部分，要求完整读。
        if (prior.offset() != null || prior.limit() != null) {
            return "写入文件失败: " + displayPath + " 只读取过部分内容（带 offset/limit 的分页读），"
                    + "未读过完整文件。write_file 会整文件覆写，请先完整 read_file（不带 offset/limit）"
                    + "确认全部内容再覆写。（若只改局部，用 edit_file 更安全。）";
        }
        try {
            if (Files.getLastModifiedTime(file).toMillis() != prior.mtimeMs()) {
                return "写入文件失败: " + displayPath + " 自你上次 read_file 之后已被修改，"
                        + "覆写会抹掉这些改动。请先重新 read_file 确认最新内容，再覆写。";
            }
        } catch (Exception ignored) {
            // stat 失败不阻断写入主路径
        }
        return null;
    }

    /**
     * str_replace 式局部编辑。精确性来自"唯一性强制"：
     * old_string 在文件中必须恰好出现一次（replace_all=false），匹配 0 处或多处都报错让 LLM 自我纠正。
     * 复用 write 路径的 observer（diff 渲染）和 LSP hook，不重复造轮子。
     */
    private String editFileForTool(String displayPath, Path file, Map<String, String> args) {
        String oldString = args.get("old_string");
        String newString = args.get("new_string") == null ? "" : args.get("new_string");
        if (oldString == null || oldString.isEmpty()) {
            return "编辑文件失败: old_string 不能为空";
        }
        if (oldString.equals(newString)) {
            return "编辑文件失败: old_string 与 new_string 相同，无需编辑";
        }
        if (!Files.isRegularFile(file)) {
            return "编辑文件失败: 文件不存在或不是普通文件: " + displayPath;
        }

        String content;
        try {
            content = Files.readString(file);
        } catch (Exception e) {
            return "编辑文件失败: 无法读取文件（可能是二进制或非 UTF-8）: " + e.getMessage();
        }

        // 失效检测：若该文件被 read_file 读过、但之后 mtime 变了（外部进程/用户改动），
        // 模型手里的 old_string 可能已是旧内容，盲目替换有"改错位置"风险——要求重读。
        // 保守：只在"读过且确实变了"时拦；从未读过的文件不拦（不破坏 SageCLI 既有的免读直编行为）。
        ReadRecord prior = readFileState.get(canonicalKey(file));
        if (prior != null) {
            try {
                if (Files.getLastModifiedTime(file).toMillis() != prior.mtimeMs()) {
                    return "编辑文件失败: " + displayPath
                            + " 自你上次 read_file 之后已被修改，当前内容可能与你掌握的不一致。"
                            + "请先重新 read_file 确认最新内容，再基于新内容编辑。";
                }
            } catch (Exception ignored) {
                // stat 失败不阻断编辑主路径
            }
        }

        int occurrences = countOccurrences(content, oldString);
        // 弯引号归一化匹配（参考 Claude Code FileEditTool.findActualString）：
        // 文件里若是排版弯引号 “ ” ‘ ’ 而模型给的是直引号 " '，精确匹配会失败。
        // 先精确匹配；失败就归一化两边弯引号再找，命中后回到原文取真实子串（保留弯引号）。
        String actualOld = oldString;
        if (occurrences == 0) {
            String resolved = findActualString(content, oldString);
            if (resolved != null) {
                actualOld = resolved;
                occurrences = countOccurrences(content, actualOld);
            }
        }
        if (occurrences == 0) {
            return "编辑文件失败: 在文件中未找到 old_string，请先 read_file 确认原文后重试。";
        }
        boolean replaceAll = Boolean.parseBoolean(args.getOrDefault("replace_all", "false"));
        if (occurrences > 1 && !replaceAll) {
            return "编辑文件失败: old_string 在文件中匹配到 " + occurrences
                    + " 处，无法唯一定位。请在 old_string 中补充更多上下文使其唯一，或设 replace_all=true 替换全部。";
        }

        // 若经弯引号归一化才匹配上，把 new_string 的直引号按文件原本风格还原，保持排版一致。
        String actualNew = preserveQuoteStyle(oldString, actualOld, newString);
        String updated = replaceAll
                ? content.replace(actualOld, actualNew)
                : replaceFirst(content, actualOld, actualNew);

        int updatedBytes = updated.getBytes(StandardCharsets.UTF_8).length;
        if (updatedBytes > MAX_WRITE_FILE_BYTES) {
            return "编辑文件失败: 编辑后内容 " + updatedBytes + " 字节超过 "
                    + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限";
        }

        try {
            Files.writeString(file, updated);
            // 编辑成功后刷新台账 mtime：把"模型已知的最新内容"对齐到这次写入，
            // 否则连续两次 edit 时第二次会误判"已被修改"。沿用 prior 的区间信息（无则记整段）。
            recordRead(file, prior == null ? null : prior.offset(), prior == null ? null : prior.limit());
            try {
                writeFileObserver.accept(displayPath, new String[]{content, updated});
            } catch (Exception ignored) {
                // observer 失败不影响编辑主路径
            }
            runPostEditLspHook(displayPath, file);
            String scope = replaceAll ? ("，共替换 " + occurrences + " 处") : "";
            return "文件已编辑: " + displayPath + scope;
        } catch (Exception e) {
            return "编辑文件失败: " + e.getMessage();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 只替换第一处匹配（String.replaceFirst 会把参数当正则，这里按字面量替换）。 */
    private static String replaceFirst(String content, String oldString, String newString) {
        int idx = content.indexOf(oldString);
        if (idx == -1) {
            return content;
        }
        return content.substring(0, idx) + newString + content.substring(idx + oldString.length());
    }

    // 排版弯引号常量（模型输出不了弯引号，文件里却常有，尤其中文/Markdown 场景）。
    private static final char LEFT_SINGLE_CURLY = '‘';   // ‘
    private static final char RIGHT_SINGLE_CURLY = '’';  // ’
    private static final char LEFT_DOUBLE_CURLY = '“';   // “
    private static final char RIGHT_DOUBLE_CURLY = '”';  // ”

    private static String normalizeQuotes(String s) {
        return s.replace(LEFT_SINGLE_CURLY, '\'')
                .replace(RIGHT_SINGLE_CURLY, '\'')
                .replace(LEFT_DOUBLE_CURLY, '"')
                .replace(RIGHT_DOUBLE_CURLY, '"');
    }

    /**
     * 在文件内容里找到与 searchString 匹配的真实子串，兼容弯引号差异。
     * 先精确匹配；失败则归一化两边弯引号再找，命中后回到原文取真实子串（保留弯引号）。
     * 返回 null 表示确实找不到。参考 Claude Code FileEditTool.findActualString。
     */
    private static String findActualString(String fileContent, String searchString) {
        if (fileContent.contains(searchString)) {
            return searchString;
        }
        String normFile = normalizeQuotes(fileContent);
        String normSearch = normalizeQuotes(searchString);
        int idx = normFile.indexOf(normSearch);
        if (idx == -1) {
            return null;
        }
        // 归一化不改变长度（弯引号与直引号都是单字符），可直接用原文同位置同长度子串。
        return fileContent.substring(idx, idx + searchString.length());
    }

    /**
     * 当 old_string 经弯引号归一化才匹配上时，把 new_string 里的直引号按文件原本的弯引号风格还原，
     * 使编辑保留排版一致性。用简单的开/闭启发式：引号前是空白/起始/开括号视为开引号，否则为闭引号。
     * 参考 Claude Code FileEditTool.preserveQuoteStyle。
     */
    private static String preserveQuoteStyle(String oldString, String actualOld, String newString) {
        if (oldString.equals(actualOld)) {
            return newString; // 未发生归一化
        }
        boolean hasDouble = actualOld.indexOf(LEFT_DOUBLE_CURLY) >= 0 || actualOld.indexOf(RIGHT_DOUBLE_CURLY) >= 0;
        boolean hasSingle = actualOld.indexOf(LEFT_SINGLE_CURLY) >= 0 || actualOld.indexOf(RIGHT_SINGLE_CURLY) >= 0;
        if (!hasDouble && !hasSingle) {
            return newString;
        }
        String result = newString;
        if (hasDouble) {
            result = applyCurlyQuotes(result, '"', LEFT_DOUBLE_CURLY, RIGHT_DOUBLE_CURLY, false);
        }
        if (hasSingle) {
            result = applyCurlyQuotes(result, '\'', LEFT_SINGLE_CURLY, RIGHT_SINGLE_CURLY, true);
        }
        return result;
    }

    private static boolean isOpeningContext(char[] chars, int index) {
        if (index == 0) {
            return true;
        }
        char prev = chars[index - 1];
        return prev == ' ' || prev == '\t' || prev == '\n' || prev == '\r'
                || prev == '(' || prev == '[' || prev == '{'
                || prev == '—' || prev == '–'; // em / en dash
    }

    private static String applyCurlyQuotes(String s, char straight, char left, char right, boolean isSingle) {
        char[] chars = s.toCharArray();
        StringBuilder out = new StringBuilder(chars.length);
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] != straight) {
                out.append(chars[i]);
                continue;
            }
            if (isSingle) {
                // 缩写里的撇号（don't / it's）不是引号：两侧都是字母时用右单弯引号
                boolean prevLetter = i > 0 && Character.isLetter(chars[i - 1]);
                boolean nextLetter = i < chars.length - 1 && Character.isLetter(chars[i + 1]);
                if (prevLetter && nextLetter) {
                    out.append(right);
                    continue;
                }
            }
            out.append(isOpeningContext(chars, i) ? left : right);
        }
        return out.toString();
    }

    private String globFiles(Map<String, String> args) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "文件匹配失败: pattern 不能为空";
        }
        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        Path projectRoot = pathGuard.getRootPath();
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(pattern));
        PathMatcher fileNameMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(pattern));
        // 先收集全部命中（受 MAX_GLOB_SCAN 上限），再按修改时间降序排（最近改的最相关），最后截到 maxResults。
        List<Path> candidates = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SearchFileVisitor(projectRoot, path -> {
                if (candidates.size() >= MAX_GLOB_SCAN) {
                    return;
                }
                Path relative = projectRoot.relativize(path);
                if (matcher.matches(relative) || fileNameMatcher.matches(path.getFileName())) {
                    candidates.add(path);
                }
            }));
        } catch (Exception e) {
            return "文件匹配失败: " + e.getMessage();
        }

        if (candidates.isEmpty()) {
            return "未找到匹配文件: " + pattern;
        }
        int total = candidates.size();
        sortByMtimeDesc(candidates, p -> p);
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < candidates.size() && matches.size() < maxResults; i++) {
            matches.add(projectRoot.relativize(candidates.get(i)).toString());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (total > matches.size()) {
            sb.append("（共 ").append(total).append(" 个，按最近修改取前 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 按文件修改时间降序排序（最近改的排前面，通常最相关——对照 Claude Code GrepTool 的 files_with_matches）。
     * stat 失败的项按 mtime=0 沉底，不抛错。keyFn 把列表元素映射成可 stat 的绝对/相对 Path。
     * 用一个 map 缓存每个 Path 的 mtime，避免比较器里重复 stat（O(n log n) 次 syscall → O(n) 次）。
     */
    private <T> void sortByMtimeDesc(List<T> items, java.util.function.Function<T, Path> keyFn) {
        Map<Path, Long> mtimeCache = new java.util.HashMap<>();
        for (T item : items) {
            Path p = keyFn.apply(item);
            mtimeCache.computeIfAbsent(p, k -> {
                try {
                    return Files.getLastModifiedTime(k).toMillis();
                } catch (Exception e) {
                    return 0L;
                }
            });
        }
        items.sort((a, b) -> Long.compare(
                mtimeCache.getOrDefault(keyFn.apply(b), 0L),
                mtimeCache.getOrDefault(keyFn.apply(a), 0L)));
    }

    private String grepCode(Map<String, String> args) {
        String query = args.get("pattern");
        if (query == null || query.isBlank()) {
            return "代码搜索失败: pattern 不能为空";
        }
        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        Path projectRoot = pathGuard.getRootPath();
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        int contextLines = clamp(parseInt(args.get("context_lines"), 0), 0, MAX_GREP_CONTEXT_LINES);
        boolean regex = parseBoolean(args.get("regex"), false);
        boolean caseSensitive = parseBoolean(args.get("case_sensitive"), true);
        PathMatcher globMatcher = null;
        PathMatcher fileNameGlobMatcher = null;
        if (args.get("glob") != null && !args.get("glob").isBlank()) {
            globMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(args.get("glob")));
            fileNameGlobMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(args.get("glob")));
        }

        Pattern contentPattern;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            contentPattern = Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            return "代码搜索失败: 正则表达式无效: " + e.getMessage();
        }

        List<GrepMatch> matches = new ArrayList<>();
        boolean usedRipgrep = false;
        // 优先用系统 ripgrep（快几个数量级）；不可用或失败时回退到下面的 Java 遍历。
        if (isRipgrepAvailable()) {
            List<GrepMatch> rg;
            try {
                rg = ripgrepGrep(root, projectRoot, query, args.get("glob"),
                        regex, caseSensitive, contextLines, maxResults);
            } catch (GrepTimeoutException te) {
                // rg 在时限内没搜完：绝不静默返回部分结果（"没搜完"会被模型当成"没有"，导致走错方向）。
                // 也不回退 Java 遍历——它更慢、同样会卡。直接抛错把决策权交回模型：缩小范围再搜。
                return "代码搜索超时: 模式 \"" + query + "\" 在时限内未搜完，结果不完整未返回。\n"
                        + "请缩小搜索范围（用 path 限定子目录、用 glob 限定文件类型），或换更精确的 pattern 再试。";
            }
            if (rg != null) {  // null = rg 失败，需回退；空 list = rg 成功但无匹配
                matches = rg;
                usedRipgrep = true;
            }
        }
        if (!usedRipgrep) {
            List<GrepMatch> javaMatches = new ArrayList<>();
            PathMatcher finalGlobMatcher = globMatcher;
            PathMatcher finalFileNameGlobMatcher = fileNameGlobMatcher;
            try {
                Files.walkFileTree(root, new SearchFileVisitor(projectRoot, path -> {
                    if (javaMatches.size() >= maxResults || !Files.isRegularFile(path)) {
                        return;
                    }
                    Path relative = projectRoot.relativize(path);
                    if (finalGlobMatcher != null
                            && !finalGlobMatcher.matches(relative)
                            && !finalFileNameGlobMatcher.matches(path.getFileName())) {
                        return;
                    }
                    collectMatches(path, relative, contentPattern, contextLines, maxResults, javaMatches);
                }));
            } catch (Exception e) {
                return "代码搜索失败: " + e.getMessage();
            }
            matches = javaMatches;
        }

        if (matches.isEmpty()) {
            return "未找到匹配内容: " + query;
        }
        // 按文件修改时间降序排（最近改的最相关）。同文件多条命中 mtime 相同，稳定排序保持其分组与行序。
        sortByMtimeDesc(matches, m -> projectRoot.resolve(m.file()));
        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(matches.size()).append(" 条");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            GrepMatch match = matches.get(i);
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                sb.append(String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text()));
            }
        }
        return sb.toString().trim();
    }

    private void collectMatches(Path file, Path relative, Pattern contentPattern, int contextLines,
                                int maxResults, List<GrepMatch> matches) {
        try {
            if (Files.size(file) > MAX_SEARCH_FILE_BYTES || isLikelyBinary(file)) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && matches.size() < maxResults; i++) {
                String line = lines.get(i);
                if (contentPattern.matcher(line).find()) {
                    int from = Math.max(0, i - contextLines);
                    int to = Math.min(lines.size() - 1, i + contextLines);
                    List<ContextLine> context = new ArrayList<>();
                    for (int j = from; j <= to; j++) {
                        context.add(new ContextLine(j + 1, lines.get(j)));
                    }
                    matches.add(new GrepMatch(relative.toString(), i + 1, context));
                }
            }
        } catch (Exception ignored) {
            // 编码不支持、权限异常或短暂文件变化时跳过该文件，保持搜索路径 fail-soft。
        }
    }

    // ripgrep 可用性探测结果缓存：null=未探测，TRUE/FALSE=已探测。避免每次搜索都 fork 一次 rg --version。
    private static volatile Boolean ripgrepAvailable;

    /** 系统是否装了 ripgrep（rg）。可用 -Dpaicli.search.disable.rg=true 强制关掉走 Java 兜底。 */
    private static boolean isRipgrepAvailable() {
        if (Boolean.getBoolean("paicli.search.disable.rg")) {
            return false;
        }
        Boolean cached = ripgrepAvailable;
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            Process p = new ProcessBuilder("rg", "--version").redirectErrorStream(true).start();
            available = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            available = false;
        }
        ripgrepAvailable = available;
        return available;
    }

    /**
     * 用系统 ripgrep 做搜索，解析 --json 输出成现有 {@link GrepMatch}。比 Java 遍历快几个数量级。
     * 任何异常/超时返回 null，调用方据此回退到 Java 遍历（{@link SearchFileVisitor} 路径）。
     */
    private List<GrepMatch> ripgrepGrep(Path root, Path projectRoot, String query, String glob,
                                        boolean regex, boolean caseSensitive, int contextLines, int maxResults) {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--json");
        cmd.add("--color=never");
        cmd.add("--line-number");
        cmd.add("--max-filesize");
        cmd.add(MAX_SEARCH_FILE_BYTES + "");
        if (!caseSensitive) cmd.add("-i");
        if (!regex) cmd.add("--fixed-strings");
        if (contextLines > 0) {
            cmd.add("-C");
            cmd.add(String.valueOf(contextLines));
        }
        for (String dir : SEARCH_EXCLUDED_DIRS) {
            cmd.add("--glob");
            cmd.add("!" + dir + "/**");
        }
        if (glob != null && !glob.isBlank()) {
            cmd.add("--glob");
            cmd.add(glob);
        }
        cmd.add("--");
        cmd.add(query);
        cmd.add(root.equals(projectRoot) ? "." : projectRoot.relativize(root).toString());

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            // 看门狗给"读取 + 进程退出"整体设时限。
            // 关键：parseRipgrepJson 的 readLine 会一直阻塞到 EOF 或达上限——若 rg 中途卡住
            // （超大文件 / 慢 IO），仅靠后置的 waitFor 永远触发不到。看门狗到点强杀进程，
            // readLine 随之收到 EOF 解除阻塞，再由 timedOut 标志区分"真超时"与"正常读完"。
            final Process p = process;
            java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                try {
                    if (!p.waitFor(8, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        p.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "grep-rg-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            List<GrepMatch> matches;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                matches = parseRipgrepJson(reader, maxResults);
            }
            watchdog.interrupt();  // 已读完，停掉看门狗，避免它在 8s 后误杀/误报

            // 达上限是合法的提前停读：我们主动不再读，rg 仍在写管道导致进程未退出，属正常，需收尾杀掉。
            // 只有"没达上限却被看门狗强杀"才是真超时——此时流被中途截断，结果不完整。
            if (matches.size() >= maxResults) {
                process.destroyForcibly();
            } else if (timedOut.get()) {
                throw new GrepTimeoutException();
            }
            return matches;
        } catch (GrepTimeoutException te) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw te;  // 透传给 grepCode，由其返回"搜索未完成"错误，区别于"无匹配"
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;  // 任何问题都回退 Java 遍历
        }
    }

    /** ripgrep 在时限内未搜完（结果不完整）的信号。区别于"搜完了但无匹配"——后者返回空 list。 */
    private static final class GrepTimeoutException extends RuntimeException {
    }

    /**
     * 解析 ripgrep {@code --json} 流式输出成 {@link GrepMatch} 列表（包可见，便于单测）。
     * rg 的 JSON 事件序列：context 行先到（before-context）→ match 行 → context 行（after-context）。
     * 同一 match 的上下文按文件归属：与当前 match 同文件的 context 追加到当前块，否则暂存给下一个 match。
     */
    static List<GrepMatch> parseRipgrepJson(BufferedReader reader, int maxResults) throws IOException {
        List<GrepMatch> matches = new ArrayList<>();
        List<ContextLine> pendingBefore = new ArrayList<>();
        String[] curFile = {null};
        int[] curLine = {0};
        List<ContextLine> curCtx = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && matches.size() < maxResults) {
            JsonNode event = mapper.readTree(line);
            String type = event.path("type").asText();
            JsonNode data = event.path("data");
            if ("match".equals(type)) {
                if (curFile[0] != null) {
                    matches.add(new GrepMatch(curFile[0], curLine[0], new ArrayList<>(curCtx)));
                    curCtx.clear();
                    if (matches.size() >= maxResults) break;
                }
                curFile[0] = data.path("path").path("text").asText();
                curLine[0] = data.path("line_number").asInt();
                curCtx.addAll(pendingBefore);
                pendingBefore.clear();
                curCtx.add(new ContextLine(curLine[0],
                        data.path("lines").path("text").asText().stripTrailing()));
            } else if ("context".equals(type)) {
                ContextLine ctx = new ContextLine(data.path("line_number").asInt(),
                        data.path("lines").path("text").asText().stripTrailing());
                String ctxFile = data.path("path").path("text").asText();
                if (curFile[0] != null && curFile[0].equals(ctxFile)) {
                    curCtx.add(ctx);
                } else {
                    pendingBefore.add(ctx);
                }
            }
        }
        if (curFile[0] != null && matches.size() < maxResults) {
            matches.add(new GrepMatch(curFile[0], curLine[0], new ArrayList<>(curCtx)));
        }
        return matches;
    }

    private boolean isLikelyBinary(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int sample = Math.min(bytes.length, 4096);
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 注册Shell命令工具
     */
    private void registerShellTools() {
        tools.put("execute_command", new Tool(
                "execute_command",
                "在当前项目目录中执行 Shell 命令（前台默认 60 秒超时，不允许全盘扫描）。"
                        + "对长任务（build/测试/下载等）设 run_in_background=true：立即返回 task_id 不阻塞，"
                        + "之后用 check_command 主动查询状态/输出（不要 sleep 轮询，先去做别的事再回来查），kill_command 终止。",
                createParameters(
                        new Param("command", "string", "要执行的命令", true),
                        new Param("run_in_background", "boolean",
                                "设为 true 在后台运行，立即返回 task_id 不阻塞；用于长任务。默认 false（前台同步执行）。", false)
                ),
                args -> executeCommand(args.get("command"),
                        "true".equalsIgnoreCase(args.get("run_in_background")))
        ));
        tools.put("check_command", new Tool(
                "check_command",
                "查询后台命令（execute_command run_in_background 启动的）的状态与输出。"
                        + "传 task_id 查单个；不传则列出全部后台任务概要。只读，不影响进程。",
                createParameters(
                        new Param("task_id", "string", "要查询的后台任务 ID；省略则列出全部", false)
                ),
                args -> backgroundCommands.check(args.get("task_id"))
        ));
        tools.put("kill_command", new Tool(
                "kill_command",
                "终止一个后台命令（先 SIGTERM，2 秒后仍在则 SIGKILL）。只能终止本会话启动的后台任务。",
                createParameters(
                        new Param("task_id", "string", "要终止的后台任务 ID", true)
                ),
                args -> backgroundCommands.kill(args.get("task_id"))
        ));
    }

    /**
     * 注册任务清单工具（update_todos）。学自 CC TodoWriteTool：模型自己管的草稿板，不替模型执行。
     * 与 /plan DAG 职责正交——plan 管"需拆解+依赖+审批"的大任务，update_todos 管"主循环里自我跟踪"的中任务。
     */
    private void registerTodoTool() {
        tools.put("update_todos", new Tool(
                "update_todos",
                "维护当前会话的任务清单，跟踪多步任务进度。适用于 3+ 步骤的中等复杂任务：开工前把步骤列出来，"
                        + "开始某步时标 in_progress（任意时刻只允许一个 in_progress），完成立即标 completed（不要批量）。"
                        + "单步/琐碎任务不要用——直接做更好。每次传完整列表（整表替换）；全部完成后清单会自动清空。",
                buildTodoParameters(),
                args -> updateTodos(args.get("todos"))
        ));
    }

    /** update_todos 的入参 schema：todos 数组，每项 {content, activeForm, status} 带完整 items 定义。 */
    private JsonNode buildTodoParameters() {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        ObjectNode todosProp = properties.putObject("todos");
        todosProp.put("type", "array");
        todosProp.put("description", "完整任务列表（整表替换）。每次都传全量，不要只传增量。");

        ObjectNode items = todosProp.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("content").put("type", "string")
                .put("description", "祈使态描述，如\"运行测试\"");
        itemProps.putObject("activeForm").put("type", "string")
                .put("description", "进行态描述，如\"正在运行测试\"");
        ObjectNode statusProp = itemProps.putObject("status");
        statusProp.put("type", "string");
        statusProp.put("description", "pending | in_progress | completed");
        statusProp.putArray("enum").add("pending").add("in_progress").add("completed");
        items.putArray("required").add("content").add("activeForm").add("status");

        parameters.putArray("required").add("todos");
        return parameters;
    }

    /**
     * 注册代码相关工具
     */
    private void registerCodeTools() {
        tools.put("create_project", new Tool(
                "create_project",
                "创建新项目结构",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/node)", true)
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type");
                    Path projectRoot = pathGuard.resolveSafe(name);
                    try {
                        Files.createDirectories(projectRoot);

                        switch (type.toLowerCase()) {
                            case "java" -> {
                                Files.createDirectories(projectRoot.resolve("src/main/java"));
                                Files.createDirectories(projectRoot.resolve("src/main/resources"));
                                Files.writeString(projectRoot.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectRoot.resolve(name));
                                Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> {
                                Files.writeString(projectRoot.resolve("package.json"),
                                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            }
                        }
                        return "项目已创建: " + name + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册 RAG 检索工具
     */
    private void registerRagTools() {
        tools.put("search_code", new Tool(
                "search_code",
                "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块；精确符号/字符串定位请优先用 grep_code/glob_files/read_file；默认 top_k=5，可显式指定（上限 30）",
                createParameters(
                        new Param("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                        new Param("top_k", "integer", "返回结果数量（默认 5，上限 30）", false)
                ),
                args -> {
                    String query = args.get("query");
                    int topK = 5;
                    try {
                        if (args.containsKey("top_k")) {
                            topK = Integer.parseInt(args.get("top_k"));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    topK = Math.max(1, Math.min(topK, 30));

                    try (CodeRetriever retriever = new CodeRetriever(projectPath)) {
                        var stats = retriever.getStats();
                        if (stats.chunkCount() == 0) {
                            return "代码库尚未索引，请先使用 /index 命令索引当前项目。";
                        }

                        List<VectorStore.SearchResult> results = retriever.hybridSearch(query, topK);
                        if (results.isEmpty()) {
                            return "未找到与查询相关的代码。";
                        }

                        return SearchResultFormatter.formatForTool(query, results);
                    } catch (Exception e) {
                        return "代码检索失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册联网工具：web_search（多 provider 抽象）+ web_fetch（HTTP + readability）
     */
    private void registerWebTools() {
        tools.put("web_search", new Tool(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。" +
                        "支持 SerpAPI（默认）和 SearXNG（自托管）两种 provider，由 SEARCH_PROVIDER 环境变量切换。",
                createParameters(
                        new Param("query", "string", "搜索关键词，例如'Java 21 新特性'、'Spring Boot 3.3 release notes'", true),
                        new Param("top_k", "integer", "返回结果数量（默认5）", false)
                ),
                args -> webSearch(args.get("query"), parseInt(args.get("top_k"), 5))
        ));

        tools.put("web_fetch", new Tool(
                "web_fetch",
                "抓取指定 URL，提取正文转 Markdown。" +
                        "适用静态 / SSR 页面（博客、文档、官网）；JS 渲染或防爬站会返回空正文，本期不重试。" +
                        "可选 prompt：填了就用小模型按你的问题对正文做摘要/检索（省 token，只回相关结论）；不填则返回正文原文（截断）。",
                createParameters(
                        new Param("url", "string", "完整 URL，需 http 或 https 协议", true),
                        new Param("prompt", "string", "可选：要从页面提取什么信息（如\"这个 API 怎么用\"）。填了走 AI 摘要，不填返回原文", false),
                        new Param("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000，仅原文模式生效）", false)
                ),
                args -> webFetch(args.get("url"), args.get("prompt"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS))
        ));
    }

    private void registerBrowserTools() {
        tools.put("browser_connect", new Tool(
                "browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法自动切换 shared 模式"
                        : browserConnector.connectDefault()
        ));
        tools.put("browser_disconnect", new Tool(
                "browser_disconnect",
                "完成登录态页面访问后，可切回 isolated 浏览器模式。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法切回 isolated 模式"
                        : browserConnector.disconnect()
        ));
        tools.put("browser_status", new Tool(
                "browser_status",
                "查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法查看浏览器状态"
                        : browserConnector.status()
        ));
    }

    private void registerSkillTools() {
        tools.put("load_skill", new Tool(
                "load_skill",
                "Load full SKILL.md instructions for a skill the system has indexed (see the \"可用 Skills\" section in this system prompt). Call this when a skill's description matches the current task. Pass the exact kebab-case skill name. The full body will appear at the start of your next user message under \"## 已加载 Skill：<name>\". Don't reload the same skill twice in one session.",
                createParameters(new Param("name", "string", "the exact kebab-case skill name (e.g. web-access)", true)),
                args -> {
                    String name = args.get("name");
                    if (name == null || name.isBlank()) {
                        return "load_skill 失败: name 不能为空";
                    }
                    if (skillRegistry == null) {
                        return "load_skill 失败: Skill 系统未初始化";
                    }
                    Skill skill = skillRegistry.findSkill(name);
                    if (skill == null) {
                        Skill any = skillRegistry.findAnySkill(name);
                        if (any == null) {
                            return "Skill '" + name + "' 未找到，可用 /skill list 查看可用 skill";
                        }
                        return "Skill '" + name + "' 已被禁用，可用 /skill on " + name + " 启用";
                    }
                    String body = skill.body();
                    int originalLen = body == null ? 0 : body.length();
                    int max = 5 * 1024;
                    String injected = body == null ? "" : body;
                    if (injected.length() > max) {
                        injected = injected.substring(0, max)
                                + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
                    }
                    if (skillContextBuffer != null) {
                        skillContextBuffer.push(name, injected);
                    }
                    return "已加载 skill '" + name + "' 的完整指引（" + originalLen
                            + " bytes），将在下一轮上下文中以 \"## 已加载 Skill：" + name + "\" 段出现。";
                }
        ));
    }

    private void registerMemoryTools() {
        tools.put("save_memory", new Tool(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；scope 默认 project，跨项目偏好才用 global；不要保存一次性任务请求、临时文件名或模型猜测。",
                createParameters(
                        new Param("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true),
                        new Param("scope", "string", "记忆作用域：project 或 global。默认 project；跨项目长期偏好才用 global", false)
                ),
                args -> {
                    String fact = args.get("fact");
                    if (fact == null || fact.isBlank()) {
                        return "保存长期记忆失败: fact 不能为空";
                    }
                    if (memorySaver == null) {
                        return "保存长期记忆失败: 记忆保存器未初始化";
                    }
                    String normalized = fact.trim();
                    String scope = "global".equalsIgnoreCase(args.get("scope")) ? "global" : "project";
                    memorySaver.accept(normalized, scope);
                    return "💾 已保存到长期记忆(" + scope + "): " + normalized;
                }
        ));
    }

    private void registerSnapshotTools() {
        tools.put("revert_turn", new Tool(
                "revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 快照；属于高危写入操作，必须经 HITL 审批。",
                createParameters(new Param("offset", "integer", "要恢复的 pre-turn 快照序号，1 表示最近一次任务开始前", false)),
                args -> {
                    int offset = parseInt(args.get("offset"), 1);
                    try {
                        RestoreResult result = snapshotService.restorePreTurn(Math.max(1, offset));
                        return result.formatForCli();
                    } catch (Exception e) {
                        return "恢复快照失败: " + e.getMessage();
                    }
                }
        ));
    }

    private void registerAgentTools() {
        tools.put("dispatch_agent", new Tool(
                "dispatch_agent",
                "派生一个只读检索子 agent 来完成开放式、需多轮翻多文件的搜索任务，只把提炼后的结论回传——"
                        + "中间翻过的大量文件内容不会进入你的上下文（省 token、不干扰主线）。"
                        + "适用：\"在整个项目里找 X 在哪/怎么实现的\"这类需要多轮 grep+read 的探索。"
                        + "不要用于：已知单个文件路径（直接 read_file）、找某个符号定义（直接 grep_code）、"
                        + "只在 2-3 个文件内查看（直接 read_file）。子 agent 只能读、不能改文件。",
                createParameters(
                        new Param("description", "string", "任务的 3-5 字概述", true),
                        new Param("prompt", "string", "给子 agent 的完整检索指令：要找什么、判断标准、希望回传什么结论", true)
                ),
                this::dispatchInvestigationAgent
        ));
    }

    /**
     * dispatch_agent 工具实现：派生一个只读检索子 agent，跑完只回传它的最终结论文本。
     * 防递归两道闸：① 子 agent 工具白名单不含 dispatch_agent（LLM 看不到）；② 深度计数 >1 拒绝。
     */
    private String dispatchInvestigationAgent(Map<String, String> args) {
        if (agentDispatchLlmClient == null) {
            return "检索子 agent 不可用：未注入 LlmClient。请在主程序构造时调用 setAgentDispatchLlmClient。";
        }
        String prompt = args.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return "检索子 agent 启动失败：prompt 不能为空。";
        }
        String description = args.getOrDefault("description", "检索任务");
        // 深度兜底闸：理论上白名单已挡住，这里防任何绕过白名单的直接调用造成无限下放。
        if (agentDispatchDepth.get() > 0) {
            return "拒绝嵌套派生：检索子 agent 内不能再派生子 agent。请在当前层完成检索。";
        }
        agentDispatchDepth.incrementAndGet();
        try {
            com.paicli.agent.SubAgent investigator = new com.paicli.agent.SubAgent(
                    "investigator", com.paicli.agent.AgentRole.WORKER, agentDispatchLlmClient, this);
            investigator.setAllowedToolNames(AGENT_INVESTIGATION_TOOLS);
            String task = "你是一个**只读检索子 agent**。任务：" + description + "\n\n"
                    + "具体要求：\n" + prompt + "\n\n"
                    + "工作方式：用 grep_code/glob_files/read_file/list_dir/search_code 多轮检索定位答案。\n"
                    + "回传约束：只返回**精炼结论**——关键发现 + `文件路径:行号` 引用 + 必要的简短代码片段。"
                    + "**不要**回贴整段文件内容，不要复述检索过程。你的最终回复就是要交给主 agent 的答案。";
            com.paicli.agent.AgentMessage result = investigator.execute(
                    com.paicli.agent.AgentMessage.task("dispatch_agent", task));
            String content = result.content();
            if (result.type() == com.paicli.agent.AgentMessage.Type.ERROR) {
                return "检索子 agent 执行出错：" + content;
            }
            return "【检索子 agent 结论 · " + description + "】\n" + content;
        } catch (Exception e) {
            log.error("dispatch_agent failed", e);
            return "检索子 agent 异常：" + e.getMessage();
        } finally {
            agentDispatchDepth.decrementAndGet();
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static final class SearchFileVisitor extends SimpleFileVisitor<Path> {
        private final Path projectRoot;
        private final java.util.function.Consumer<Path> fileConsumer;

        private SearchFileVisitor(Path projectRoot, java.util.function.Consumer<Path> fileConsumer) {
            this.projectRoot = projectRoot;
            this.fileConsumer = fileConsumer;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (!dir.equals(projectRoot) && SEARCH_EXCLUDED_DIRS.contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileConsumer.accept(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }

    record ContextLine(int lineNumber, String text) {}

    record GrepMatch(String file, int lineNumber, List<ContextLine> context) {}

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    String webSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        SearchProvider provider = searchProvider();
        if (!provider.isReady()) {
            return "⚠️ " + provider.unavailableHint();
        }
        try {
            List<SearchResult> results = provider.search(query.trim(), topK);
            return formatSearchResults(provider.name(), query, results);
        } catch (Exception e) {
            return "搜索失败 (" + provider.name() + "): " + e.getMessage();
        }
    }

    private void runPostEditLspHook(String displayPath, Path safePath) {
        try {
            if (lspManager != null) {
                lspManager.runPostEditLspHook(displayPath, safePath);
            }
        } catch (Exception ignored) {
            // LSP 诊断是 post-edit 辅助信号，失败不能影响工具主结果。
        }
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    String webFetch(String url, String prompt, int maxChars) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        NetworkPolicy policy = networkPolicy();

        // http→https 升级：明文 http 易被中间人篡改/重定向，先尝试升级（对标 CC 的 http→https）。
        String currentUrl = upgradeHttpToHttps(url.trim());

        try {
            // 重定向逐跳循环：每跳都过 SSRF 校验；同域自动跟随，跨域交还模型决定（不自动跟，防开放重定向）。
            final int maxRedirects = 5;
            for (int hop = 0; ; hop++) {
                String denyReason = policy.checkUrl(currentUrl);
                if (denyReason != null) {
                    return "❌ 网络访问被拒绝: " + denyReason;
                }
                String rateReason = policy.acquire();
                if (rateReason != null) {
                    return "❌ " + rateReason;
                }

                WebFetcher.RawResponse raw = webFetcher().fetch(currentUrl);
                if (!raw.isRedirect()) {
                    return renderFetchedBody(raw, prompt, maxChars);
                }

                // 命中重定向。
                if (hop >= maxRedirects) {
                    return "❌ 重定向次数过多（超过 " + maxRedirects + " 跳），已停止。最后目标: " + raw.redirectLocation();
                }
                String target = upgradeHttpToHttps(raw.redirectLocation());
                if (!sameHost(currentUrl, target)) {
                    // 跨域重定向：不自动跟，把目标交回模型——由它判断是否信任并显式再抓一次 web_fetch。
                    return "🔀 检测到跨域重定向（HTTP " + raw.redirectStatus() + "），未自动跟随。\n"
                            + "原始 URL: " + currentUrl + "\n"
                            + "重定向目标: " + target + "\n"
                            + "如确认该目标可信且确实需要，请对上面的重定向目标 URL 重新调用 web_fetch。";
                }
                // 同域重定向（含 http→https 自升级后的同主机跳转）：自动跟随。
                currentUrl = target;
            }
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    /** 把 http:// 升级为 https://（仅协议头，host/path 不动）。非 http 开头原样返回。 */
    private static String upgradeHttpToHttps(String url) {
        if (url != null && url.regionMatches(true, 0, "http://", 0, "http://".length())) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    /** 两个 URL 是否同主机（忽略大小写）。解析失败时保守返回 false（当作跨域，交模型决定）。 */
    private static boolean sameHost(String a, String b) {
        try {
            String ha = java.net.URI.create(a).getHost();
            String hb = java.net.URI.create(b).getHost();
            return ha != null && ha.equalsIgnoreCase(hb);
        } catch (Exception e) {
            return false;
        }
    }

    /** 把成功抓取的正文渲染成工具结果：摘要模式优先，否则原文截断。 */
    private String renderFetchedBody(WebFetcher.RawResponse raw, String prompt, int maxChars) {
        HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
        String markdown = extracted.markdown();
        int originalLength = markdown.length();

        // 摘要模式：填了 prompt 且摘要 client 可用 → 用小模型按 prompt 浓缩正文，主模型只看结论（省 token）。
        // 任一不满足（没 prompt / 没注入 client / 正文为空 / 调用异常）都降级到原文截断（同 dispatch_agent 降级思路）。
        if (prompt != null && !prompt.isBlank() && webFetchSummaryClient != null && !markdown.isBlank()) {
            String summary = summarizeFetchedContent(markdown, prompt);
            if (summary != null) {
                return formatFetchSummary(raw.url(), extracted.title(), prompt, summary, originalLength);
            }
            // summary==null 表示摘要失败，落到下面原文截断。
        }

        boolean truncated = false;
        if (maxChars > 0 && markdown.length() > maxChars) {
            markdown = markdown.substring(0, maxChars);
            truncated = true;
        }
        FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
        return formatFetchResult(result);
    }

    /** 摘要喂给二级模型的正文输入上限：防 prompt 过长被 API 拒；对标 CC 的 MAX_MARKDOWN_LENGTH（取更保守值）。 */
    private static final int MAX_SUMMARY_INPUT_CHARS = 100_000;

    /**
     * 用摘要 client（通常是便宜小模型）按 prompt 对正文做浓缩/检索。
     * @return 浓缩结果；失败（异常/空响应）返回 null 让调用方降级到原文截断。
     */
    String summarizeFetchedContent(String markdown, String prompt) {
        String content = markdown.length() > MAX_SUMMARY_INPUT_CHARS
                ? markdown.substring(0, MAX_SUMMARY_INPUT_CHARS) + "\n\n[正文过长已截断…]"
                : markdown;
        String userPrompt = "网页正文：\n---\n" + content + "\n---\n\n" + prompt
                + "\n\n请仅基于上面的正文简洁作答：提取与问题相关的信息、必要的代码示例或文档片段；"
                + "正文里没有的就说没有，不要编造。";
        try {
            List<com.paicli.llm.LlmClient.Message> messages = List.of(
                    com.paicli.llm.LlmClient.Message.system("你是网页内容提炼助手，基于给定正文简洁、准确地回答用户问题，不展开无关内容。"),
                    com.paicli.llm.LlmClient.Message.user(userPrompt));
            com.paicli.llm.LlmClient.ChatResponse resp = webFetchSummaryClient.chat(messages, List.of());
            if (resp == null || resp.content() == null || resp.content().isBlank()) {
                return null;
            }
            return resp.content().trim();
        } catch (Exception e) {
            log.warn("web_fetch 摘要失败，降级返回原文截断: {}", e.getMessage());
            return null;
        }
    }

    private String formatFetchSummary(String url, String title, String prompt, String summary, int originalLength) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(url).append("\n");
        if (title != null && !title.isBlank()) {
            sb.append("📄 标题: ").append(title).append("\n");
        }
        sb.append("🔍 已按你的问题对正文（").append(originalLength).append(" 字符）做 AI 摘要：\n");
        sb.append("（问题：").append(prompt).append("）\n\n---\n\n");
        sb.append(summary);
        return sb.toString();
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
    }

    /**
     * 创建参数定义
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    /**
     * 获取所有工具定义（用于LLM）
     */
    public List<com.paicli.llm.LlmClient.Tool> getToolDefinitions() {
        // 按工具名排序输出：tools 是 ConcurrentHashMap，迭代顺序不稳定；若每轮顺序变，
        // 工具定义 JSON 跟着变，破坏平台 prefix cache 命中。排序后跨轮字节稳定。
        return tools.values().stream()
                .sorted(java.util.Comparator.comparing(Tool::name))
                .map(t -> new com.paicli.llm.LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 注册一个 MCP 工具到 ToolRegistry。
     *
     * @param descriptor 工具描述（含 namespacedName 如 mcp__filesystem__read_file）
     * @param invoker    工具执行器：输入 JSON 参数字符串，输出给 LLM 看的字符串结果。
     *                   typically lambda 在内部调用 McpClient.callTool 并处理异常 → 字符串。
     */
    public synchronized void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        registerMcpToolOutput(descriptor, args -> ToolOutput.text(invoker.apply(args)));
    }

    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        String toolName = descriptor.namespacedName();
        McpRegisteredTool registered = new McpRegisteredTool(descriptor, invoker);
        mcpTools.put(toolName, registered);
        tools.put(toolName, new Tool(
                toolName,
                mcpDescription(descriptor),
                descriptor.inputSchema(),
                args -> "MCP 工具不应通过 Map<String,String> 入口执行"
        ));
    }

    public synchronized void unregisterMcpTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        mcpTools.remove(toolName);
        tools.remove(toolName);
    }

    public synchronized void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                      Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        replaceMcpToolOutputsForServer(serverName, newTools,
                descriptor -> args -> ToolOutput.text(invokerFactory.apply(descriptor).apply(args)));
    }

    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(newTools, "newTools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        String prefix = "mcp__" + serverName + "__";
        List<String> existing = mcpTools.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .toList();
        for (String toolName : existing) {
            mcpTools.remove(toolName);
            tools.remove(toolName);
        }
        for (McpToolDescriptor descriptor : newTools) {
            registerMcpToolOutput(descriptor, invokerFactory.apply(descriptor));
        }
    }

    /**
     * 执行工具调用
     *
     * 危险工具（write_file / execute_command / create_project）会写一行审计：
     * - 策略拦截（PathGuard / CommandGuard / 文件大小上限）→ deny
     * - 普通异常 → error
     * - 其他情况 → allow（仅表示工具调用真的发生过，工具内部的业务错误仍以返回字符串呈现给 LLM）
     */
    public String executeTool(String name, String argumentsJson) {
        return doExecuteTool(name, argumentsJson).text();
    }

    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (isLegacyExecuteToolOverride()) {
            return ToolOutput.text(executeTool(name, argumentsJson));
        }
        return doExecuteTool(name, argumentsJson);
    }

    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        if (CancellationContext.isCancelled()) {
            return ToolOutput.text("用户取消了此次工具调用");
        }
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolOutput.text("未知工具: " + name);
        }

        boolean shouldAudit = shouldAudit(name);
        long start = System.nanoTime();
        BrowserAuditMetadata auditMetadata = null;

        try {
            McpRegisteredTool mcpTool = mcpTools.get(name);
            if (mcpTool != null) {
                BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
                auditMetadata = browserCheck.metadata();
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
                ToolOutput output = mcpTool.invoker().apply(argumentsJson);
                if (output == null) {
                    output = ToolOutput.text("");
                }
                if (browserGuard != null) {
                    browserGuard.applyAfterExecution(name, argumentsJson, output.text());
                }
                if (shouldAudit) {
                    auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
                }
                return output;
            }

            JsonNode args = mapper.readTree(argumentsJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                // 标量走 asText()（兼容历史行为）；array/object 保留原始 JSON 字符串，
                // 否则 asText() 对容器节点返回空串——结构化参数（如 update_todos 的 todos 数组）会丢失。
                // executor 拿到原始 JSON 自行解析，对仅用标量的旧工具完全无影响。
                argMap.put(entry.getKey(),
                        value.isContainerNode() ? value.toString() : value.asText());
            });
            String result = tool.executor().execute(argMap);
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.text(result);
        } catch (PolicyException e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.failure("🛡️ 策略拒绝: " + e.getMessage());
        } catch (Exception e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.failure("工具执行失败: " + e.getMessage());
        }
    }

    // 只读工具白名单：同一轮 LLM 返回多个工具调用时，只有这些无副作用的查询/读取类可以并行。
    // 写/编辑/执行/MCP（副作用未知）等一律串行，且作为屏障——夹在两次读之间的写会强制顺序，
    // 防"读到的是写之前还是之后"的竞态。安全优先：不在白名单的工具默认当写处理。
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read_file", "list_dir", "glob_files", "grep_code", "search_code",
            "web_search", "web_fetch", "check_command", "browser_status");

    private boolean isReadOnlyTool(String name) {
        return name != null && READ_ONLY_TOOLS.contains(name);
    }

    private boolean isLegacyExecuteToolOverride() {
        try {
            return getClass()
                    .getMethod("executeTool", String.class, String.class)
                    .getDeclaringClass() != ToolRegistry.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    protected BrowserCheckResult checkBrowserTool(String name, String argumentsJson, boolean previewOnly) {
        if (browserGuard == null || !BrowserGuard.isChromeTool(name)) {
            return BrowserCheckResult.allow(null);
        }
        return browserGuard.check(name, argumentsJson, !previewOnly);
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    /**
     * 执行同一轮 LLM 返回的多个工具调用。
     *
     * <p>调度策略（读并行 / 写串行）：按传入顺序扫描，连续的<b>只读</b>工具（{@link #READ_ONLY_TOOLS}）
     * 攒成一批<b>并行</b>跑；遇到写/编辑/执行类则先把已攒的只读批 flush 掉，再把这个写工具<b>单独串行</b>执行。
     * 写工具天然成为屏障——夹在两次读之间的写会强制"读→写→读"的顺序，杜绝"读到的是写之前还是之后"的竞态。
     *
     * <p>结果按传入顺序返回，调用方可安全地按原 tool_call 顺序回灌消息历史。
     * 只读批里某工具超过批次超时仍未返回，会取消并返回超时结果；已完成工具不受影响。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (CancellationContext.isCancelled()) {
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "用户取消了此次工具调用"))
                    .toList();
        }
        if (invocations.size() == 1) {
            return List.of(executeOneSerial(invocations.get(0)));
        }

        List<ToolExecutionResult> results = new ArrayList<>(invocations.size());
        List<ToolInvocation> readBatch = new ArrayList<>();
        for (ToolInvocation invocation : invocations) {
            if (isReadOnlyTool(invocation.name())) {
                readBatch.add(invocation);
                continue;
            }
            // 遇到写工具：先把已攒的只读批并行跑完（屏障），再串行执行这个写工具。
            if (!readBatch.isEmpty()) {
                results.addAll(executeReadOnlyBatch(readBatch));
                readBatch.clear();
            }
            results.add(executeOneSerial(invocation));
        }
        // 收尾：末尾还攒着的只读批。
        if (!readBatch.isEmpty()) {
            results.addAll(executeReadOnlyBatch(readBatch));
        }
        return results;
    }

    /** 串行执行单个工具调用（写工具走这条；尊重取消）。 */
    private ToolExecutionResult executeOneSerial(ToolInvocation invocation) {
        if (CancellationContext.isCancelled()) {
            return ToolExecutionResult.failed(invocation, "用户取消了此次工具调用");
        }
        long startedAt = System.nanoTime();
        ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
        return ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt));
    }

    /** 并行执行一批只读工具调用。结果按传入顺序返回。单个调用直接串行，不开线程池。 */
    private List<ToolExecutionResult> executeReadOnlyBatch(List<ToolInvocation> batch) {
        if (batch.size() == 1) {
            return List.of(executeOneSerial(batch.get(0)));
        }
        // 防御性拷贝：调用方会 clear() 复用同一个 list，闭包按值捕获引用会读到被清空的内容。
        List<ToolInvocation> invocations = List.copyOf(batch);
        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "paicli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        if (CancellationContext.isCancelled()) {
                            return ToolExecutionResult.failed(invocation, "用户取消了此次工具调用");
                        }
                        long startedAt = System.nanoTime();
                        ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
                        return ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }

                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, message));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private static boolean shouldAudit(String name) {
        return AUDIT_TOOLS.contains(name) || (name != null && name.startsWith("mcp__"));
    }

    private static String mcpDescription(McpToolDescriptor descriptor) {
        String base = descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP server 提供的外部工具"
                : descriptor.description();
        return base + " (MCP server: " + descriptor.serverName() + ", tool: " + descriptor.name() + ")";
    }

    private String updateTodos(String todosJson) {
        if (todosJson == null || todosJson.isBlank()) {
            return "update_todos 失败：todos 不能为空，请传完整任务列表（数组）。";
        }
        List<TodoItem> parsed = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(todosJson);
            if (!arr.isArray()) {
                return "update_todos 失败：todos 必须是数组，每项为 {content, activeForm, status}。";
            }
            int inProgress = 0;
            int pending = 0;
            for (JsonNode node : arr) {
                String content = node.path("content").asText("").trim();
                String activeForm = node.path("activeForm").asText("").trim();
                String status = node.path("status").asText("").trim();
                if (content.isEmpty()) {
                    return "update_todos 失败：每条 todo 的 content 不能为空。";
                }
                if (!status.equals("pending") && !status.equals("in_progress") && !status.equals("completed")) {
                    return "update_todos 失败：status 必须是 pending / in_progress / completed 之一，收到：'" + status + "'。";
                }
                if (status.equals("in_progress")) {
                    inProgress++;
                } else if (status.equals("pending")) {
                    pending++;
                }
                // activeForm 缺省时用 content 兜底，避免渲染空白。
                parsed.add(new TodoItem(content, activeForm.isEmpty() ? content : activeForm, status));
            }
            if (inProgress > 1) {
                return "update_todos 失败：任意时刻只允许一个 in_progress，收到 " + inProgress + " 个。请只把当前正在做的那一步标为 in_progress。";
            }
            // 下界：还有未完成任务却没有任何 in_progress，说明清单"停摆"（学自 CC：恰好一个在做）。
            // 例外：全部 completed 是合法的收尾态（下面会触发 allDone 清空），不强制。
            if (inProgress == 0 && pending > 0) {
                return "update_todos 失败：还有未完成任务时必须恰好有一个 in_progress（不能 0 个）。"
                        + "请把你接下来要做的那一步标为 in_progress；若全部做完请把它们都标为 completed。";
            }
        } catch (Exception e) {
            return "update_todos 失败：todos 解析错误：" + e.getMessage();
        }

        boolean allDone = !parsed.isEmpty() && parsed.stream().allMatch(t -> t.status().equals("completed"));
        synchronized (todos) {
            todos.clear();
            // 全部完成即清空清单（学自 CC：allDone → []），下次再有多步任务重新建。
            if (!allDone) {
                todos.addAll(parsed);
            }
        }
        return renderTodos(parsed, allDone);
    }

    /** 把清单渲染成给模型看的文本：勾选框 + 进行态高亮。allDone 时回收尾提示。 */
    private String renderTodos(List<TodoItem> items, boolean allDone) {
        if (allDone) {
            return "✅ 全部 " + items.size() + " 项任务已完成，清单已清空。";
        }
        StringBuilder sb = new StringBuilder("任务清单已更新：\n");
        for (TodoItem t : items) {
            String box = switch (t.status()) {
                case "completed" -> "[x]";
                case "in_progress" -> "[→]";
                default -> "[ ]";
            };
            // 进行中的项显示进行态文案，其余显示祈使态。
            String label = t.status().equals("in_progress") ? t.activeForm() : t.content();
            sb.append(box).append(' ').append(label).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 当前会话任务清单的只读快照（供渲染层 / 测试使用）。 */
    public List<TodoItem> getTodos() {
        synchronized (todos) {
            return List.copyOf(todos);
        }
    }

    private String executeCommand(String command, boolean runInBackground) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        String denyReason = CommandGuard.check(normalized);
        if (denyReason != null) {
            // 抛 PolicyException 让外层 executeTool 统一写 audit 并格式化拒绝消息，
            // 命令围栏与路径围栏的拒绝路径走同一个出口。后台路径同样先过围栏，不绕过。
            throw new PolicyException(denyReason);
        }

        // 后台路径：不阻塞，立即返回 task_id。CommandGuard 已在上面跑过，HITL 审批由 HitlToolRegistry 在更外层完成。
        if (runInBackground) {
            return backgroundCommands.launch(normalized);
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", normalized);
            pb.directory(new File(projectPath));
            // 子进程环境脱敏：默认删掉继承下来的凭证类变量，避免 LLM 用 env/printenv 偷走 API key。
            // 这是确定性边界（删了就读不到），区别于 CommandGuard 的 best-effort 黑名单。
            EnvironmentSanitizer.sanitize(pb.environment());
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "命令执行超时（" + commandTimeoutSeconds + "秒），已强制终止";
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            return String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return "用户取消了此次工具调用";
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private String readProcessOutput(Process process) throws Exception {
        // 保尾截断：命令的报错信息 / exit code 通常在输出尾部，截头比截尾更可能保住模型真正需要的内容
        // （与 read_file 保头相反——文件结构在头部，命令结论在尾部）。
        // 滚动保留末尾 MAX_COMMAND_OUTPUT_CHARS 字符：用 2× 高水位线触发裁剪，使整体裁剪成本保持线性
        // （每增长 MAX 才裁一次，每次 O(MAX)），避免逐行 delete 退化成 O(n²)。
        StringBuilder output = new StringBuilder();
        boolean truncated = false;
        int highWater = MAX_COMMAND_OUTPUT_CHARS * 2;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (output.length() > highWater) {
                    output.delete(0, output.length() - MAX_COMMAND_OUTPUT_CHARS);
                    truncated = true;
                }
            }
        }
        // 收尾再裁一次：可能在 (MAX, 2*MAX] 区间结束循环却没触发过高水位裁剪。
        if (output.length() > MAX_COMMAND_OUTPUT_CHARS) {
            output.delete(0, output.length() - MAX_COMMAND_OUTPUT_CHARS);
            truncated = true;
        }
        if (truncated) {
            return "...(输出头部已截断，保留末尾 " + MAX_COMMAND_OUTPUT_CHARS + " 字符)\n" + output;
        }
        return output.toString();
    }

    private String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }

    // 记录定义
    private record Param(String name, String type, String description, boolean required) {}

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    private record McpRegisteredTool(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {}

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public record ToolExecutionResult(String id, String name, String argumentsJson,
                                      String result, long elapsedMillis, boolean timedOut,
                                      List<com.paicli.llm.LlmClient.ContentPart> imageParts,
                                      boolean failed) {
        private static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output, long elapsedMillis) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    output == null ? "" : output.text(),
                    elapsedMillis,
                    false,
                    output == null ? List.of() : output.imageParts(),
                    output != null && output.failed());
        }

        private static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
            return completed(invocation, ToolOutput.text(result), elapsedMillis);
        }

        private static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return completed(invocation, ToolOutput.failure("工具执行失败: " + message), 0);
        }

        private static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行超时（" + timeoutSeconds + "秒），已取消",
                    timeoutSeconds * 1000,
                    true,
                    List.of(),
                    true
            );
        }

        public boolean hasImageParts() {
            return imageParts != null && !imageParts.isEmpty();
        }
    }

    /**
     * 构造一条"工具被拒绝"的结果（标记 failed）。供 SubAgent 白名单兜底等场景使用：
     * 某个工具调用未真正执行（越权/不可用），但仍需回一条带原 tool_call id 的结果维持消息配对。
     */
    public static ToolExecutionResult blockedToolResult(String id, String toolName, String message) {
        return new ToolExecutionResult(id, toolName, "", message, 0, false, List.of(), true);
    }

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }
}
