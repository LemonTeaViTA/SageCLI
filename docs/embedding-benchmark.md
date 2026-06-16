# Embedding 模型对比实验（代码库 RAG 检索）

> 实验日期：2026-06-10
> 目的：评估 paicli RAG 检索用哪个 embedding 模型最合适——默认的 ollama/nomic 够不够用，还是该换。
> 结论速览：**默认的 nomic-embed-text 不够用（中文坍缩 + 最慢）；Qwen3-Embedding-0.6B 是性价比首选；4B 略好但性价比一般。已将默认配置改为 0.6B。**

## 一、对比对象

| 模型 | 部署方式 | 参数量 | 文件大小 | 向量维度 | 端口 |
|------|---------|--------|---------|---------|------|
| nomic-embed-text | ollama (llama.cpp) | 1.37 亿 | 274 MB | 768 | 11434 |
| Qwen3-Embedding-0.6B | vLLM (OpenAI 兼容) | 6 亿 | 1.1 GB | 1024 | 6666 |
| Qwen3-Embedding-4B | vLLM (OpenAI 兼容) | 40 亿 | ~8 GB | 2560 | 6669 |

硬件：单张 RTX 4090 D（24G）/ 模型；vLLM 环境 `/mnt/data/conda_envs/vllm`（vLLM 0.18.0, torch 2.10+cu130）。

## 二、速度对比（客观硬数据）

逐条 embed 50 段代码样本，记录耗时：

| 模型 | 维度 | 每段耗时 | 相对速度 |
|------|------|---------|---------|
| nomic-embed-text (ollama) | 768 | **408 ms** | 最慢（基准）|
| Qwen3-Embedding-0.6B (vLLM) | 1024 | **7.5 ms** | **快 54 倍** |
| Qwen3-Embedding-4B (vLLM) | 2560 | 13.5 ms | 快 30 倍 |

**反直觉发现：参数最小的 nomic 反而最慢。**
原因不在模型大小，在**推理框架**：
- nomic 走 ollama/llama.cpp，逐条 HTTP、无批处理优化 → 慢
- Qwen 走 vLLM（连续批处理、PagedAttention、GPU 充分利用）→ 即使模型大，吞吐也碾压

**核心教训：embedding 选型不能只看模型参数大小，部署框架（vLLM vs ollama）对速度影响更大。**

## 三、检索效果对比（主观，5 个中文查询 × paicli 真实代码块语料）

语料：10 段 paicli 真实代码块（Agent.run / 并行工具 / PathGuard / 余弦相似度 / DAG / 会话持久化 / 记忆压缩 / Embedding / SQLite 等）。
评判：每个查询看正确代码块是否进 Top3 / 排第一。

| 查询 | 理想答案 | nomic | 0.6B | 4B |
|------|---------|-------|------|-----|
| ReAct 循环怎么判断结束 | Agent.run-ReAct循环 | ❌ 没命中 | ⚠️ Top1 | ⚠️ Top2 |
| 工具调用怎么并行执行 | 并行工具执行invokeAll | ❌ 没命中 | ⚠️ Top3 | ✅ Top1 |
| 怎么防止访问项目外文件 | PathGuard路径围栏 | ⚠️ Top3 | ⚠️ Top2 | ⚠️ Top2 |
| 余弦相似度怎么算 | 余弦相似度计算 | ❌ 没命中 | ⚠️ Top2 | ⚠️ Top2 |
| 对话太长怎么压缩 | 记忆压缩摘要 | ✅ Top1 | ✅ Top1 | ⚠️ Top2 |

**命中率（正确块进 Top3）**：nomic 2/5、0.6B 5/5、4B 5/5。

**关键发现**：
- **nomic 基本坍缩**：几乎每个查询的 Top3 都是同样几个块（记忆压缩 / CommandGuard / PathGuard），与查询内容无关 —— 说明它对中文查询无区分度，本质是英文为主的老模型（2024 初），对中文代码检索几乎不工作。
- **0.6B 够用**：5/5 命中，3 个排第一，中文理解在线。
- **4B 略好**：5/5 命中，分数更高、区分更开（如"工具并行"4B 排第一、0.6B 排第三），但与 0.6B 没有拉开代差。

## 四、综合性价比结论

| 维度 | nomic | 0.6B | 4B |
|------|-------|------|-----|
| 速度/段 | 408 ms 🐌 | 7.5 ms ⚡ | 13.5 ms |
| 显存 | 最省 | ~10 G | ~10 G+（更高负载）|
| 中文检索 | ❌ 坍缩 | ✅ 够用 | ✅✅ 略好 |
| 维度 | 768 | 1024 | 2560 |

1. **nomic 不够用** —— 中文坍缩 + 最慢。paicli 原默认用它，对中文场景是隐藏的坑。
2. **0.6B 是性价比甜点** —— 最快、中文够用、显存可控。**应作默认首选。**
3. **4B 是"要更高精度且不缺显存"时的升级** —— 效果略好，但速度减半、显存翻倍，对"辅助检索"场景属过剩，性价比一般。

## 五、已做的改动

- `.env`：embedding 默认改为 Qwen3-Embedding-0.6B（`EMBEDDING_PROVIDER=openai` / `EMBEDDING_MODEL=Qwen3-Embedding-0.6B` / `EMBEDDING_BASE_URL=http://localhost:6666/v1`）
- `.env.example`：补充推荐说明（中文场景用 Qwen，标注 nomic 坍缩问题）

## 六、复现方式

启动 0.6B 服务（vLLM）：
```bash
CUDA_VISIBLE_DEVICES=4 /mnt/data/conda_envs/vllm/bin/python -m vllm.entrypoints.openai.api_server \
  --model /home/ubuntu/ADgai/dsq/Qwen3-Embedding-0.6B \
  --convert embed --port 6666 --host 0.0.0.0 \
  --served-model-name Qwen3-Embedding-0.6B \
  --max-model-len 8192 --gpu-memory-utilization 0.4 --dtype bfloat16
```

验证接口：
```bash
curl -s http://localhost:6666/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"Qwen3-Embedding-0.6B","input":"public void login()"}'
```

注意：换 embedding 模型后维度变化（768→1024→2560），**必须 `/index` 重建索引**，旧向量与新查询向量维度不匹配会失效。

## 七、给项目的改进建议（面试可讲）

1. **默认 embedding 从 nomic 换成中文友好的模型** —— nomic 对中文坍缩是真实缺陷。
2. **文档应说明"换模型需重建索引"** —— 维度变化导致旧索引失效，当前代码未做维度校验，可加一个维度不匹配的检测与提示。
