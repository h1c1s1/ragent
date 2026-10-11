import { useMemo } from "react";

type Data = Record<string, unknown>;
const object = (value: unknown): Data => value && typeof value === "object" && !Array.isArray(value) ? value as Data : {};
const rows = (value: unknown): Data[] => Array.isArray(value) ? value.map(object) : [];
const text = (value: unknown): string => value == null ? "—" : typeof value === "object" ? JSON.stringify(value) : String(value);
const labels: Record<string, string> = {
  action: "动作", reason: "原因", result: "结果", scope: "检索范围", topScore: "最高意图分",
  confidenceThreshold: "定向阈值", minIntentScore: "最低意图分", minScore: "意图分阈值",
  maxIntentCount: "意图数量上限", contextTopK: "上下文数量", recallBudget: "召回预算",
  candidateLimit: "候选池上限", targetCollections: "主检索库", supplementCollections: "补充库",
  rrfK: "RRF 参数", weights: "渠道权重", strategy: "策略", minRerankScore: "证据阈值",
  topRerankScore: "最高精排分", consumer: "上下文使用方", characterCount: "上下文字符数",
  agentRunId: "Agent Run", agentTraceId: "Agent Trace", agentSpanId: "Agent Span", toolCallId: "工具调用",
  conversationId: "会话", taskId: "任务", enabled: "启用渠道", disabled: "关闭渠道",
  budget: "分支配额", resultCount: "返回数量", collections: "知识库", backendLatencyMs: "后端耗时（ms）",
  requestedTopK: "图谱请求数量", baseTopK: "图谱基础预算", queryMode: "图谱查询模式", filterMode: "图谱范围过滤",
  inputCount: "处理前数量", outputCount: "处理后数量", matchedCount: "命中范围数量",
  unmatchedCount: "范围外数量", discardedCount: "图谱丢弃数量", supplementSource: "补充证据来源",
  candidateCount: "候选意图数量", selectedCount: "采用意图数量", mode: "编排模式", candidateIds: "可分类意图",
  boundCollections: "意图绑定库", inactiveCollections: "已失效绑定库", totalCount: "总数",
};

function Fields({ data }: { data: Data }) {
  const entries = Object.entries(data).filter(([key, value]) => value != null &&
    !["candidates", "selected", "chunks", "context", "subQuestions", "intents", "dropped", "truncated",
      "question", "originalQuestion", "rewrittenQuestion", "prompt", "evidenceKeys", "removed", "primary", "supplement"].includes(key));
  if (!entries.length) return null;
  return <dl className="grid gap-2 text-xs sm:grid-cols-2">{entries.map(([key, value]) => (
    <div key={key} className="min-w-0"><dt className="text-slate-500">{labels[key] ?? key}</dt>
      <dd className="mt-0.5 break-all text-slate-800">{text(value)}</dd></div>
  ))}</dl>;
}

function Evidence({ title, data, before }: { title: string; data: Data; before: Data[] }) {
  if (data.recordedCount == null) return null;
  const items = rows(data.candidates);
  if (!items.length && data.totalCount == null) return null;
  const ranks = new Map<string, unknown>();
  before.forEach(item => { if (!ranks.has(text(item.dedupKey))) ranks.set(text(item.dedupKey), item.rank); });
  return <section className="space-y-2">
    <p className="text-xs font-medium text-slate-700">{title} · {text(data.totalCount)} 条
      {data.truncated === true && <span className="ml-2 text-amber-700">仅展示已采集的 {text(data.recordedCount)} 条</span>}</p>
    <div className="max-h-80 overflow-auto rounded border"><table className="w-full text-left text-xs">
      <thead className="sticky top-0 bg-slate-50"><tr>{["排名", "文档 / 证据", "渠道", "分数", "内容"].map(label =>
        <th key={label} className="whitespace-nowrap p-2 font-medium">{label}</th>)}</tr></thead>
      <tbody>{items.map((item, index) => <tr key={`${text(item.evidenceKey)}-${index}`} className="border-t align-top">
        <td className="p-2 whitespace-nowrap">{ranks.has(text(item.dedupKey)) && text(ranks.get(text(item.dedupKey))) !== text(item.rank)
          ? `${text(ranks.get(text(item.dedupKey)))} → ${text(item.rank)}` : text(item.rank)}</td>
        <td className="p-2"><p>{text(item.docName ?? item.docId ?? item.sourceId)}</p>
          <p className="mt-1 text-slate-500 break-all">{text(item.collectionName)} / {text(item.sourceId)}</p>
          {item.chunkIndex != null && <p className="text-slate-500">分块序号 {text(item.chunkIndex)}</p>}</td>
        <td className="p-2">{Array.isArray(item.channels) ? item.channels.join(", ") || "—" : "—"}</td>
        <td className="p-2 whitespace-nowrap">{text(item.score)}<p className="text-slate-500">{text(item.scoreType)}</p>
          {item.rerankScore != null && <p>精排 {text(item.rerankScore)}</p>}</td>
        <td className="p-2 min-w-40 max-w-xs"><details><summary className="cursor-pointer text-slate-600">片段预览</summary>
          <p className="mt-1 whitespace-pre-wrap break-words">{text(item.textPreview)}</p>
          <p className="mt-2 break-all text-slate-500">证据 ID：{text(item.evidenceKey)}</p>
          <p className="break-all text-slate-500">去重键：{text(item.dedupKey)}</p></details></td>
      </tr>)}</tbody>
    </table></div>
  </section>;
}

function Intents({ title, items, dropped }: { title: string; items: Data[]; dropped: Data[] }) {
  if (!items.length) return null;
  const reasons = new Map(dropped.map(item => [text(item.intentId), item.reason]));
  return <section className="space-y-2"><p className="text-xs font-medium text-slate-700">{title}</p>
    <div className="overflow-auto rounded border"><table className="w-full text-left text-xs">
      <thead className="bg-slate-50"><tr>{["意图", "分数", "知识库", "筛选结果"].map(label =>
        <th key={label} className="p-2 font-medium">{label}</th>)}</tr></thead>
      <tbody>{items.map((item, index) => <tr key={`${text(item.intentId)}-${index}`} className="border-t">
        <td className="p-2">{text(item.name ?? item.intentId)}<p className="text-slate-500">{text(item.intentId)} · {text(item.kind)}</p></td>
        <td className="p-2">{text(item.score)}</td><td className="p-2 break-all">{text(item.collections)}</td>
        <td className="p-2">{reasons.has(text(item.intentId)) ? text(reasons.get(text(item.intentId))) : "保留"}</td>
      </tr>)}</tbody>
    </table></div></section>;
}

function Branches({ data }: { data: Data }) {
  if (!Object.keys(data).length) return null;
  return <section className="space-y-3"><p className="text-xs font-medium text-slate-700">检索分支</p>
    {(["primary", "supplement"] as const).map(key => {
      const branch = object(data[key]);
      if (!Object.keys(branch).length) return null;
      return <div key={key} className="space-y-2 rounded border p-2">
        <p className="text-xs font-medium">{key === "primary" ? "主路" : "补充路"}</p><Fields data={branch} />
        {Array.isArray(branch.evidenceKeys) && branch.evidenceKeys.length > 0 && <details className="text-xs">
          <summary className="cursor-pointer text-slate-500">分支证据 ID{branch.truncated === true ? "（部分）" : ""}</summary>
          <ul className="mt-2 max-h-40 overflow-auto break-all text-slate-600">{branch.evidenceKeys.map((id, index) => <li key={index}>{text(id)}</li>)}</ul>
        </details>}
      </div>;
    })}<Fields data={data} /></section>;
}

export function TraceBusinessDetails({ raw }: { raw?: string | null }) {
  const data = useMemo(() => { try { return object(JSON.parse(raw ?? "")); } catch { return {}; } }, [raw]);
  if (!raw) return null;
  const input = object(data.input), output = object(data.output), decision = object(data.decision);
  const evidenceInput = input.recordedCount != null ? input : object(input.chunks);
  const before = rows(evidenceInput.candidates), dropped = rows(decision.dropped);
  const context = output.context ?? output.prompt;
  const groups = rows(output.subQuestions);
  return <div className="space-y-4">
    {data.schemaVersion === 1 && <>
      {object(data.capture).truncated === true && <p className="rounded bg-amber-50 p-2 text-xs text-amber-800">采集数据达到上限，以下内容为部分记录。</p>}
      {object(data.capture).contentCaptured === false && <p className="text-xs text-slate-500">正文采集已关闭，保留结构和决策数据。</p>}
      {data.subQuestionId != null && <p className="text-xs text-slate-500">子问题：{text(data.subQuestionId)}</p>}
      {(input.question ?? input.originalQuestion ?? input.rewrittenQuestion ?? data.question) != null &&
        <p className="rounded bg-slate-50 p-2 text-sm break-words">{text(input.question ?? input.originalQuestion ?? input.rewrittenQuestion ?? data.question)}</p>}
      {output.rewrittenQuestion != null && <p className="text-sm break-words">改写结果：{text(output.rewrittenQuestion)}</p>}
      {Array.isArray(output.subQuestions) && !groups.some(group => group.intents) &&
        <ol className="list-inside list-decimal text-sm">{output.subQuestions.map((question, index) => <li key={index}>{text(question)}</li>)}</ol>}
      <Fields data={input} /><Fields data={decision} /><Fields data={object(data.channels)} />
      {Object.keys(object(data.processors)).length > 0 && <section className="space-y-2">
        <p className="text-xs font-medium text-slate-700">后处理器配置</p><Fields data={object(data.processors)} /></section>}
      <Fields data={object(data.association)} />
      <Fields data={object(data.stats)} /><Fields data={object(data.graphQuery)} />
      <Fields data={object(data.classification)} /><Fields data={object(data.collectionBindings)} />
      <Branches data={object(data.branches)} />
      {rows(data.events).map((event, index) => <p key={index} className="rounded bg-amber-50 p-2 text-xs text-amber-900">
        {text(event.outcome)} · {text(event.reason)}</p>)}
      <Intents title="分类候选" items={rows(output.candidates).filter(item => item.intentId != null)} dropped={dropped} />
      <Intents title="采用的意图" items={rows(output.selected ?? output.intents)} dropped={[]} />
      {dropped.length > 0 && <section className="text-xs"><p className="mb-2 font-medium">意图筛选记录</p>
        <ul className="max-h-48 overflow-auto space-y-1">{dropped.map((item, index) => <li key={index} className="break-all">
          {item.subQuestionId != null ? `${text(item.subQuestionId)} · ` : ""}{text(item.intentId)} · 分数 {text(item.score)} · {text(item.reason)}
        </li>)}</ul></section>}
      {groups.filter(group => Array.isArray(group.intents)).map((group, index) => <div key={index} className="space-y-2">
        <p className="text-sm">{text(group.subQuestionId)}：{text(group.question)}</p>
        <Intents title="最终意图" items={rows(group.intents)} dropped={[]} />
        {!rows(group.intents).length && <p className="text-xs text-slate-500">没有最终意图</p>}
      </div>)}
      <Evidence title="输入候选" data={evidenceInput} before={[]} />
      <Evidence title="输出候选" data={output} before={before} />
      <Evidence title="实际上下文证据顺序" data={object(data.selectedEvidence)} before={before} />
      {rows(object(data.changes).removed).length > 0 && <section className="text-xs"><p className="mb-2 font-medium">淘汰记录</p>
        <ul className="max-h-48 overflow-auto space-y-1">{rows(object(data.changes).removed).map((item, index) =>
          <li key={index} className="break-all">{text(item.dedupKey)} · {text(item.reason)}</li>)}</ul></section>}
      <Fields data={object(data.changes)} />
      {context != null && <section><p className="mb-2 text-xs font-medium">{output.prompt != null ? "引导提示" : "最终知识上下文"}</p>
        <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded border bg-slate-50 p-3 text-xs">{text(context)}</pre></section>}
      <Fields data={output} />
    </>}
    <details className="text-xs"><summary className="cursor-pointer text-slate-500">原始追踪数据</summary>
      <pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap break-all rounded border bg-slate-50 p-2">{Object.keys(data).length ? JSON.stringify(data, null, 2) : raw}</pre>
    </details>
  </div>;
}
