# RAG 意图识别与检索链路追踪

本次扩展沿用 `t_rag_trace_run` / `t_rag_trace_node` 和 `@RagTraceNode`。业务快照存入已有的 `extra_data`，不需要新增表或修改数据库结构。接口新增 Run / Node 的 `extraData`，管理页兼容旧记录和新结构。

## 链路结构

```text
rag-stream-chat / agent-knowledge-search
├─ query-rewrite-and-split
├─ intent-resolve
│  ├─ intent-classify [sq-1]
│  └─ intent-classify [sq-2]
├─ guidance-detect
├─ retrieval-engine
│  └─ retrieval-subquestion [sq-N]
│     ├─ multi-channel-retrieval
│     │  ├─ retrieval-scope
│     │  ├─ retrieve-vector / retrieve-keyword / retrieve-graph
│     │  ├─ deduplication
│     │  ├─ fusion
│     │  ├─ rerank
│     │  ├─ evidencegate
│     │  └─ metadataenrichment
│     └─ context-format
└─ retrieval-context
```

节点是否出现取决于实际分支和开关。例如引导短路、系统回复、检索为空时不会进入完整检索或最终知识上下文阶段；未注册或未启用的通道不创建执行节点。后处理器按实际启用顺序执行。

节点类型受现有 `t_rag_trace_node.node_type VARCHAR(16)` 限制，必须不超过 16 个字符。后处理节点使用 `RETRIEVE_PROC`，上下文节点使用 `RETRIEVE_CONTEXT`；详细阶段仍由 `node_name` 和快照中的 `stage` 区分。该命名兼容已有数据库，无需扩展字段长度。

## 记录内容

| 阶段 | 记录内容 |
| --- | --- |
| 问题改写 | 原问题、改写结果、拆分子问题、历史消息数量、规则改写或模型失败降级 |
| 意图分类 | 编排模式、可分类意图 ID、模型原始响应、候选意图及分数、采用结果 |
| 意图筛选 | 分数阈值、单问题数量限制、跨子问题总量限制、被过滤意图及原因 |
| 歧义引导 | 输入意图、是否产生引导、原因、歧义候选和引导提示 |
| 检索范围 | 定向或全局、置信阈值、最高意图分、有效库、绑定库、失效绑定、主路和补充库 |
| 各检索通道 | 超时预算、实际返回数量、候选排名和分数类型、主路/补充路配额与证据 ID、降级事件 |
| 后处理 | 输入/输出候选快照、原始分数/RRF/精排分、名次变化、淘汰记录、融合参数和证据门控决定 |
| 上下文 | 格式化时实际采用的证据顺序、可用意图、上下文内容及字符数；最终记录引用处理后的知识上下文 |

候选包含 `evidenceKey`、实际去重键、来源 ID、collection、文档 ID/名称、块序号、排名、分数、通道、正文预览和文本哈希。`evidenceKey` 是 collection 与实际去重键的 SHA-256，可串联各阶段及主路/补充路记录。不同通道的原始分数类型分别为 COSINE、BM25、GRAPH_RANK，不应直接比较这些分数。

向量和关键词的补充路会查询未命中库；图谱目前仍是全图查询后的结果侧过滤，补充记录标记 `UNMATCHED_GRAPH_EVIDENCE`，并记录匹配、未匹配和丢弃数量。

## 快照协议与执行状态

新节点使用 `schemaVersion: 1`，公共字段包括 `stage`、`subQuestionId`、`input`、`output`、`decision`、`execution`、`events`、`capture`，按阶段只填写适用字段。

节点 `status` 表示方法是否完成。异常被现有业务逻辑捕获并降级时，节点可以是 `SUCCESS`，但 `execution.outcome` / `events` 会记录 `DEGRADED`；正常空结果用 `decision.result: EMPTY` 表示。超时使用 `status: ERROR` 和 `execution.outcome: TIMEOUT`，同时记录 `CHANNEL_TIMEOUT`。严重执行状态不会被后续普通过滤事件覆盖。

快照在记录时复制为标量、Map 和 List，避免融合改分、元数据补齐等原地修改反向污染之前的阶段。TTL 对异步节点栈作副本传播，子问题通过 `sq-N` 关联。通道节点在派发时创建，因此耗时包含排队和执行；超时后只放弃结果，不强制中断后端执行。节点收尾后冻结记录，迟到线程无法再修改已记录的数据。

Run 和 Node 只允许从 `RUNNING` 写入终态，避免重复完成覆盖取消或超时。流式取消通过任务 ID 结束对应 Run，Agent 知识搜索通过 Reactor 取消钩子结束自己的 Run。新增采集、序列化及落库出口采用尽力记录，失败写日志并保持原业务流程。

## Agent 与 RAG 关联

`agent.trace.enabled` 和 `rag.trace.enabled` 独立控制各自采集。Agent 继续导出 OpenTelemetry，RAG 继续写 PostgreSQL。

`search_knowledge` 通过 Reactor Context 显式传入工具 Span，通过 `RagTraceLink` 将关联信息传到 RAG 入口。新建的 `agent-knowledge-search` Run 记录 `agentRunId`、`agentTraceId`、`agentSpanId`、`toolCallId`、会话和任务 ID；工具 Span 写回 `ragent.rag.trace_id`。没有有效 Agent Span 时仍可采集独立 RAG Run。已有 RAG 上下文时复用当前 Run，避免嵌套创建。

这提供跨存储关联，不会把两套 Trace 合并成一个 OpenTelemetry Trace。

## 配置

以下为 `RagTraceProperties` 的默认值，可在已有配置中按需覆盖；本次没有修改 `application.yaml`。

```yaml
rag:
  trace:
    enabled: true
    capture-content: true
    max-candidates: 200
    max-content-length: 4000
    max-preview-length: 300
    max-node-bytes: 65536
    max-error-length: 1000
```

`max-candidates` 只限制每个采集列表，不改变召回、融合、精排和上下文的业务预算。`capture-content: false` 屏蔽问题、改写文本、模型原始响应、片段正文和上下文，保留子问题 ID、意图、文档标识、分数、数量与决策。Agent 上报内容由自己的 `agent.trace.capture-content` 独立控制。

序列化会屏蔽命名为 password、authorization、api-key、secret、access-token 等的字段。该规则不扫描正文内部的敏感信息；不希望记录业务正文时应关闭正文采集。超限记录通过 `capture.truncated` 标识；单节点超过字节预算时先省略预览、缩短文本，再减少列表，极端情况下只保留版本、阶段和截断说明，JSON 始终保持完整。

管理页在节点详情中展示意图筛选、候选证据、排名变化、淘汰原因、检索分支和上下文，并保留原始 JSON 折叠视图。历史记录缺少业务快照时继续展示原有耗时信息。

本次按要求不新增或运行测试。最后的代码调整仅做静态检查，尚未进行最终版本的运行验证。
