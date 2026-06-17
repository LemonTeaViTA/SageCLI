# SageCLI

一个面向代码库工作的终端 AI Agent，用自然语言驱动代码开发、调试与自动化，定位对标 Claude Code。内置 ReAct / Plan-and-Execute / Multi-Agent 三条执行路径、多平台模型适配、长上下文工程、MCP 工具生态、代码库检索与安全纵深防御。

## 核心能力

### 三条执行路径

- **ReAct**（默认）：思考-行动-观察单轮循环，适合简单任务与单步操作。
- **Plan-and-Execute**（`/plan`）：把 LLM 输出解析为任务 DAG，拓扑排序检测循环依赖、按依赖分批且批内并行推进；计划生成后先与用户确认再执行。适合多步骤、带依赖的复杂任务。
- **Multi-Agent**（`/team`）：规划者（Planner）拆解 → 执行者（Worker）执行 → 检查者（Reviewer）审查，主从编排；审查未通过带「上一轮产出 + 问题」反馈重试（最多 2 次），Reviewer 可选独立模型降低同模型审查盲点。

三条路径**共享同一套 ToolRegistry / MemoryManager / 快照服务**，按任务复杂度切换，新增模式无需重写工具栈。

### 多平台模型适配

- `LlmClient` 接口 + OpenAI 兼容模板基类，把各 provider 的差异（默认模型 / 上下文窗口 / 缓存模式 / 特殊请求字段 / 图片格式 / 定价 / 是否支持工具）收敛进 **`SeriesQuirks` 数据驱动注册表**——provider 差异是数据而非类层次，新增平台往注册表加一条即可。
- 接入 GLM / DeepSeek / Kimi / StepFun / 讯飞 MaaS 等平台，通用 client 支持魔搭 / NVIDIA / 本地 vLLM 等任意 OpenAI 兼容平台零代码接入。
- 两层「平台 + 模型」配置：平台共享 key / baseUrl / series，模型只引用平台，消除 key 重复；`/model` 运行时热切换、按系列分组显示型号名。
- `FallbackLlmClient` 跨平台故障转���：主平台失败自动切同版本备份。

### 长上下文工程

- `ContextProfile` 按模型窗口派生上下文预算——**压缩触发点 = 窗口 − 摘要输出预留 − 缓冲**，直接锁定「触发时仍可用的绝对 token 余量」而非固定比率，避免大窗口浪费、小窗口溢出。
- 占用达阈值时自动将早期对话摘要压缩为一段、保留最近 N 轮原文（`ConversationHistoryCompactor`）。
- prompt cache 命中可见化 + 按模型 series 分档的调用成本估算。

### 记忆系统

- 短期记忆管理当前对话与工具结果；长期记忆通过 `/save <事实>` 或用户明确说「记一下 / 记住」时的 `save_memory` 保存稳定事实，默认项目级作用域、跨会话���用。
- 注入给模型的相关记忆只用长期稳定事实，不把当前轮短期对话误当历史记忆。
- 项目级记忆 `PAI.md`：可提交 git 的项目约定文档，`/init` 一键生成模板，每次构建 system prompt 时注入 Project Context；`PAI.local.md` 做个人覆盖（不入库）。

### 代码库检索

- 混合检索：`grep_code` / `glob_files` 实时定位优先（系统装有 ripgrep 时优先调用加速、未安装回退 Java 遍历），SQLite 向量语义检索兜底，兼顾精确符号定位与模糊语义召回。
- JavaParser 解析 AST 构建「继承 / 实现 / 调用 / 引用」关系图谱。
- `/index`、`/search`、`/graph` 命令；本地 Ollama Embedding 或远程 API 可配。

### 工具与并行

- 内置工具：文件读写、目录列举、glob 查找、grep 搜索、Shell 命令、项目创建、RAG 检索、联网搜索、网页抓取、快照回滚。
- 同一轮多个无依赖工具经固定线程池并发执行（默认上限 4），**按提交下标有序回收结果并与 `tool_call_id` 配对**满足 OpenAI 协议；单工具异常隔离 + 整批超时兜底。

### MCP 工具生态

- 集成 MCP 协议，支持 stdio 子进程与 Streamable HTTP 两种 transport，动态注册 `mcp__{server}__{tool}`。
- SchemaSanitizer 清洗 `$ref` / `anyOf` 等复杂 JSON Schema，降低模型调用失败率。
- 接入 Chrome DevTools MCP 实现网页导航 / 截图 / 表单填充等浏览器自动化；冷启动慢的 server 超时不阻塞首屏。

### 浏览器与图片

- 联网：`web_search`（智谱 Web Search / SerpAPI / SearXNG 三选一）+ `web_fetch`（readability 提取正文 Markdown）；静态页优先 web_fetch，SPA / 防爬墙 / 需登录态走浏览器 MCP。
- 图片输入：`@image:<路径>` 或 `Ctrl+V` 贴剪贴板图，按 Claude Code 同类策略预处理（压缩 / 缩放 / 透明 PNG 铺白底）后作为图片块发给多模态模型。

### 微信通道

- `java -jar sagecli.jar wechat setup / start` 通过 iLink 网关把 Agent 接入微信，扫码绑定后用微信消息驱动；远程无人值守场景采用比 CLI 更严的工具白名单策略。

### 安全纵深防御

- **HITL 人工审批**（`/hitl on`）：危险操作（write_file / edit_file / execute_command / create_project / revert_turn / 所有 mcp__ 工具）三级危险等级展示，支持批准 / 全部放行 / 拒绝 / 跳过 / 改参数后执行。
- **路径围栏**：文件类工具强制限定项目根之内，拦截绝对路径外逃 / `..` 穿越 / 符号链接逃逸。
- **命令快速拒绝**：HITL 之前的黑名单（`sudo` / `rm -rf` 全盘 / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`）。
- **结构化审计**：危险工具调用按天写 JSONL 到 `~/.paicli/audit/`、脱敏凭证，`/audit [N]` 查看。
- **资源上限**：`write_file` 5MB；`read_file` 全文读取超 5MB 自动降级为前 2000 行 + 分页提示；`execute_command` 60 秒超时 + 8KB 输出截断。
- 定位：HITL 之外的辅助层，不是沙箱、不提供进程隔离。

### 会话与快照

- 对话按项目无损持久化为 JSONL（完整保留 tool_calls / tool_call_id 保证恢复后协议正确），`/resume`、`/continue` 跨重启恢复会话。
- Git Side-History 快照：每个 turn 前后用 JGit 在 `~/.paicli/snapshots/` 旁路记录快照（不碰项目自身 `.git`），`/restore <N>` 回滚到指定 pre-turn 快照。

### 终端体验

- inline 流式 TUI：π 主题彩色开屏、底部状态栏（模型 / MCP / skill / token / cwd）、行内可折叠工具块、行内 git diff、单字符 HITL 提示。
- 终端对常见 Markdown（标题、列表、表格、代码块）渲染后再显示。
- 可切 lanterna 全屏 TUI 或 plain 纯文本兜底（`PAICLI_RENDERER`）。

## 启动界面

```text
   ████████    SageCLI π  v16.1.0
     ██  ██    Model glm-5.1 (glm)
     ██  ██    MCP 4/4 · 61 tools · 2/2 skills · ReAct
     ██  ██    ReAct · Plan · MCP · Browser · Image
     ██  ██

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:
```

## 快速开始

### 1. 配置 API Key

复制 `.env.example` 为 `.env`，填入你的 API Key（任填其一即可启动）：

```bash
cp .env.example .env
# 编辑 .env，填入 GLM_API_KEY / DEEPSEEK_API_KEY / STEP_API_KEY / KIMI_API_KEY 等
```

也支持环境变量或两层 config（`~/.paicli/config.json` 用户级、`<项目>/.paicli/config.json` 项目级，含 key 配置默认被 gitignore 忽略）。

### 2. 可选：配置 MCP server

MCP 默认开启。`~/.paicli/mcp.json` 不存在时自动创建 chrome-devtools 默认配置。可编辑该文件接入其他 server：

```json
{
  "mcpServers": {
    "fetch": { "command": "uvx", "args": ["mcp-server-fetch"] },
    "remote-demo": {
      "url": "https://mcp.example.com/v1",
      "headers": { "Authorization": "Bearer ${REMOTE_TOKEN}" }
    }
  }
}
```

`command` 表示 stdio server，`url` 表示 Streamable HTTP server。`${PROJECT_DIR}` / `${HOME}` 为内置变量，其他 `${VAR}` 从环境变量读取。

### 3. 编译运行

```bash
# 编译（默认跳过测试）
mvn clean package

# 运行（代码库检索功能需本地 Ollama 已启动且拉取 nomic-embed-text）
java -jar target/sagecli-1.0-SNAPSHOT.jar
```

### 4. 进入 Plan / Team 模式

默认是 ReAct。`/plan` 切到 Plan-and-Execute、`/team` 切到 Multi-Agent，都是**粘性模式**：切入后之后每条任务都走该模式，直到 `/react` 退回 ReAct 或 `/plan`↔`/team` 互切；运行中任务按 ESC 中断（不改模式）。一个对话窗口内三模式共享同一上下文，切换不丢前文。也可一条命令直接执行（同样会切到该模式）：

```text
/plan 创建一个 demo 项目，然后读取 pom.xml，最后验证项目结构
```

## 可用工具

| 工具 | 说明 |
|---|---|
| `read_file` / `write_file` / `edit_file` / `list_dir` | 文件读写、局部编辑与目录列举（限定项目根内） |
| `glob_files` / `grep_code` | 按文件名 / 内容实时检索（grep 优先 ripgrep 加速） |
| `execute_command` | 执行短时 Shell 命令（60 秒超时 + 黑名单拦截） |
| `create_project` | 创建项目结构（java / python / node） |
| `search_code` | RAG 语义检索（模糊查询或常规搜索无果时辅助） |
| `web_search` / `web_fetch` | 联网搜索 / 抓取 URL 正文 |
| `revert_turn` | 回滚到最近第 N 个 pre-turn 快照（走 HITL 与审计） |
| `mcp__{server}__{tool}` | MCP server 动态提供的外部工具 |

## 命令

| 命令 | 说明 |
|---|---|
| `/plan [任务]` / `/team [任务]` / `/react` | 切换执行模式 |
| `/cancel` | 取消运行中任务 |
| `/hitl [on\|off]` | 人工审批开关 / 状态 |
| `/model [型号]` | 查看 / 切换模型 |
| `/mcp [restart\|logs\|disable\|enable\|resources\|prompts] <name>` | MCP server 管理 |
| `/memory` `/memory list\|search\|delete\|clear` `/save <事实>` | 记忆管理 |
| `/index [路径]` `/search <查询>` `/graph <类名>` | 代码库检索 |
| `/snapshot [status\|clean]` `/restore <N>` | 快照与回滚 |
| `/sessions` `/resume <id>` `/continue` | 会话历史与恢复 |
| `/init [--force]` | 生成项目级记忆 PAI.md |
| `/policy` `/audit [N]` | 查看安全策略 / 审计记录 |
| `/context` `/config` `/clear` `/help` `/exit` | 上下文状态 / 配置 / 清空 / 帮助 / 退出 |

## 技术栈

- **语言 / 构建**：Java 17 / Maven
- **LLM**：多平台 OpenAI 兼容（GLM / DeepSeek / StepFun / Kimi / 讯飞 MaaS + 魔搭 / NVIDIA / 本地 vLLM）
- **网络 / 序列化**：OkHttp / Jackson
- **终端**：JLine 4（交互、Status、输入 widgets）
- **存储**：SQLite（向量与图谱持久化）/ JGit（旁路快照）
- **代码分析**：JavaParser（AST）/ ripgrep（加速检索）
- **Embedding**：Ollama（本地）或远程 API
- **二维码**：ZXing（微信通道扫码）

## 项目结构

```
src/main/java/com/paicli
├── agent/          # Agent 实现：ReAct / Plan-and-Execute / Multi-Agent 编排
├── cli/            # CLI 入口、命令解析、项目记忆初始化
├── llm/            # LlmClient 接口 + 模板基类 + SeriesQuirks 注册表 + 故障转移
├── context/        # ContextProfile 上下文预算、Token / 成本展示
├── memory/         # 短期 / 长期记忆、对话历史压缩、Token 预算
├── prompt/         # 分层 Prompt 组装、项目记忆加载（PAI.md）
├── plan/           # 任务定义、执行计划、规划器
├── rag/            # Embedding、向量存储、代码分块、AST 关系分析
├── tool/           # 工具注册表、代码搜索引擎
├── mcp/            # MCP 协议：transport、schema 清洗、resource 索引
├── browser/        # 浏览器会话、敏感页策略、连接探活
├── skill/          # Skill 系统：注册表、索引、上下文注入
├── snapshot/       # Git Side-History 快照与回滚
├── wechat/         # 微信 iLink 通道
├── render/         # inline / lanterna / plain 三套渲染器
├── hitl/           # 人工审批
├── policy/         # 路径围栏、命令黑名单、审计
└── runtime/        # 异步后台任务 + Runtime API
```

## 测试

```bash
mvn test -Pquick                # 快速回归（跳过外部进程 / 网络 / 命令超时类慢测试）
mvn test -Pphase16-smoke        # 终端 / TUI / inline renderer 冒烟
mvn test -DskipTests=false      # 全量（发版或大范围重构前）
```
