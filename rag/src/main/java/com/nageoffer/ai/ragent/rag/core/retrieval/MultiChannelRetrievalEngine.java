/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.trace.RagTraceData;
import com.nageoffer.ai.ragent.rag.trace.RagTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎
 * <p>
 * 负责协调多个检索通道和后置处理器：
 * 1. 并行执行所有启用的检索通道
 * 2. 依次执行后置处理器链
 * 3. 返回最终的检索结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {
    @Autowired
    private RagTraceRecorder traceRecorder = RagTraceRecorder.disabled();

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final RetrievalScopeResolver retrievalScopeResolver;
    private final Executor ragRetrievalExecutor;
    private final SearchChannelProperties searchProperties;

    /**
     * 执行多通道检索（仅 KB 场景）
     * <p>
     * 按子问题逐个调用：检索问题与作用域都取自同一个子问题，二者同源
     *
     * @param subIntent 子问题及其意图
     * @param budget    检索预算（召回扇出 / Rerank 候选池上限 / 最终条数）
     * @return 后处理后的 Chunk 及其意图归属
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public KnowledgeRetrievalResult retrieveKnowledgeChannels(SubQuestionIntent subIntent,
                                                               RetrievalBudget budget) {
        SearchContext context = buildSearchContext(subIntent, budget);
        RagTraceContext.record("input", traceInput(context));

        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            RagTraceContext.record("output", RagTraceData.chunks(List.of(), "CHANNEL_SCORE", List.of()));
            RagTraceContext.event("SKIPPED", "NO_ENABLED_CHANNELS");
            return KnowledgeRetrievalResult.empty();
        }

        List<RetrievedChunk> chunks = executePostProcessors(channelResults, context);
        RagTraceContext.record("attribution", deriveAttribution(chunks, context.getRetrievalScope()));
        // 异常或超时导致定向证据为空时，保留的定向范围会使其按未命中处理
        return new KnowledgeRetrievalResult(
                chunks,
                deriveAttribution(chunks, context.getRetrievalScope()),
                context.getRetrievalScope().directedIntentIds());
    }

    /**
     * 按库推导意图归属：定向作用域下，最终存活 chunk 的 collection 属于某命中意图的绑定库即归属该意图
     * <p>
     * 归属与证据经由哪条通道到达无关——所有检索共用同一个问题，「哪条查询捞到它」只携带库信息与排名运气；
     * 同一库被多个意图绑定时全部归属（确定性多归属）。补充路证据的库不在任何命中意图绑定里，天然无归属；
     * 全局作用域没有命中意图，整体无归属
     */
    private Map<String, Set<String>> deriveAttribution(List<RetrievedChunk> chunks, RetrievalScope scope) {
        if (scope == null || !scope.directed() || chunks.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> intentIdsByCollection = new LinkedHashMap<>();
        for (NodeScore intent : scope.intents()) {
            String intentId = intent.getNode().getId();
            if (intentId == null || intentId.isBlank()) {
                continue;
            }
            for (String collection : intent.getNode().getEffectiveCollectionNames()) {
                intentIdsByCollection
                        .computeIfAbsent(collection, ignored -> new LinkedHashSet<>())
                        .add(intentId);
            }
        }
        Map<String, Set<String>> intentIdsByChunkKey = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            Set<String> intentIds = chunk.getCollectionName() == null
                    ? null
                    : intentIdsByCollection.get(chunk.getCollectionName());
            if (intentIds != null && !intentIds.isEmpty()) {
                intentIdsByChunkKey.putIfAbsent(RetrievedChunkKey.of(chunk), Set.copyOf(intentIds));
            }
        }
        return intentIdsByChunkKey;
    }

    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // 按通道类型枚举序做稳定排序：通道并行执行、下游融合（RRF）与归因均与顺序无关，
        // 这里排序仅为日志/派发顺序稳定可复现，不承载任何检索优先级语义
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(channel -> channel.getType().ordinal()))
                .toList();

        RagTraceContext.record("channels", RagTraceData.map("enabled", enabledChannels.stream().map(SearchChannel::getName).toList(),
                "disabled", searchChannels.stream().filter(c -> !enabledChannels.contains(c)).map(SearchChannel::getName).toList()));
        if (enabledChannels.isEmpty()) {
            // 全站无任何知识召回、退化为裸 LLM，属配置事故而非正常降级，不能静默
            log.warn("没有任何启用的检索通道，本次不做知识召回；请检查 rag.search.channels.*.enabled 与对应后端开关");
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        long channelTimeoutMs = searchProperties.getChannels().getTimeoutMs();
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> dispatchChannel(channel, context, channelTimeoutMs))
                .toList();

        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getLatencyMs()
                );
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 耗时：{}ms",
                        result.getChannelName(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);

        return results;
    }

    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        RagTraceContext.record("processors", RagTraceData.map("enabled", enabledProcessors.stream().map(SearchResultPostProcessor::getName).toList(),
                "disabled", postProcessors.stream().filter(p -> !enabledProcessors.contains(p)).map(SearchResultPostProcessor::getName).toList()));

        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            List<RetrievedChunk> rawChunks = results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
            RagTraceContext.record("output", RagTraceData.chunks(rawChunks, "CHANNEL_SCORE", results));
            return rawChunks;
        }

        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        String scoreType = "CHANNEL_SCORE";
        for (SearchResultPostProcessor processor : enabledProcessors) {
            String processorName = processor.getName() == null ? "postprocess" : processor.getName();
            var span = traceRecorder.start(processorName.toLowerCase(java.util.Locale.ROOT), "RETRIEVE_PROC");
            try (var scope = span.activate()) {
                RagTraceContext.record("input", RagTraceData.chunks(chunks, scoreType, results));
                List<String> beforeKeys = RagTraceContext.captureLimit() == 0 ? List.of()
                        : chunks.stream().map(RetrievedChunkKey::of).toList();
                int beforeSize = chunks.size();
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();
                if ("Fusion".equals(processorName) && results.size() > 1) scoreType = "RRF";
                RagTraceContext.record("output", RagTraceData.chunks(chunks, scoreType, results));
                Set<String> keptKeys = beforeKeys.isEmpty() ? Set.of()
                        : chunks.stream().map(RetrievedChunkKey::of).collect(Collectors.toSet());
                Map<String, Integer> seen = new LinkedHashMap<>();
                List<Map<String, Object>> removed = new ArrayList<>();
                for (String key : beforeKeys) {
                    int occurrence = seen.merge(key, 1, Integer::sum);
                    if (!keptKeys.contains(key) || ("Deduplication".equals(processorName) && occurrence > 1)) {
                        String reason = switch (processorName) {
                            case "Deduplication" -> "DEDUPLICATED";
                            case "Fusion" -> "FUSION_CANDIDATE_LIMIT";
                            case "Rerank" -> "RERANK_TOP_K";
                            case "EvidenceGate" -> "EVIDENCE_GATE_REJECTED";
                            default -> "PROCESSOR_REMOVED";
                        };
                        removed.add(RagTraceData.map("dedupKey", key, "reason", reason));
                    }
                }
                RagTraceContext.record("changes", RagTraceData.map("removed", removed, "inputCount", beforeSize, "outputCount", afterSize));
                span.success();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                span.error(e);
                RagTraceContext.event("DEGRADED", "PROCESSOR_ERROR: " + processorName);
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        RagTraceContext.record("output", RagTraceData.chunks(chunks, scoreType, results));
        return chunks;
    }

    /**
     * 通道级超时：超过预算的通道按空结果降级，不让最慢一条钳制同一子问题里其余通道的融合
     * 只放弃结果、不中断执行，任务仍在池内跑完，超时值过小等于整路白算
     * <p>
     * 降级结果记引擎等到放弃为止的真实耗时：记 0 会让下游统计把「等满预算才放弃」读成
     * 「秒回、库里没料」，而这两者的处置方向正相反——前者调超时、后者补语料
     */
    private CompletableFuture<SearchChannelResult> dispatchChannel(SearchChannel channel, SearchContext context, long timeoutMs) {
        var span = traceRecorder.start("retrieve-" + channel.getType().name().toLowerCase(java.util.Locale.ROOT), "RETRIEVE_CHANNEL");
        span.record("input", traceInput(context));
        span.record("timeoutMs", timeoutMs);
        long started = System.currentTimeMillis();
        CompletableFuture<SearchChannelResult> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                try (var scope = span.activate()) {
                    RagTraceContext.record("input", traceInput(context));
                    return channel.search(context);
                }
            }, ragRetrievalExecutor);
        } catch (RuntimeException error) {
            span.error(error);
            throw error;
        }
        if (timeoutMs > 0) future = future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        return future.handle((result, error) -> {
            try (var scope = span.activate()) {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    RagTraceContext.record("output", RagTraceData.chunks(List.of(), RagTraceData.scoreType(channel.getType().name()), List.of()));
                    RagTraceContext.record("decision", RagTraceData.map("result", "FALLBACK_EMPTY", "reason",
                            cause instanceof TimeoutException ? "CHANNEL_TIMEOUT" : "CHANNEL_ERROR"));
                    if (cause instanceof TimeoutException) span.timeout(cause); else span.error(cause);
                    log.warn("检索通道 {} 失败或超时，使用空结果", channel.getName(), cause);
                    return channel.emptyResult(System.currentTimeMillis() - started);
                }
                if (result != null) {
                    RagTraceContext.record("output", RagTraceData.chunks(result.getChunks(),
                            RagTraceData.scoreType(channel.getType().name()), List.of(result)));
                    RagTraceContext.record("stats", RagTraceData.map("resultCount", result.getChunks().size(),
                            "backendLatencyMs", result.getLatencyMs(), "metadata", result.getMetadata()));
                    RagTraceContext.record("decision", RagTraceData.map("result", result.getChunks().isEmpty() ? "EMPTY" : "FOUND"));
                }
                span.success();
                return result;
            }
        });
    }

    private Map<String, Object> traceInput(SearchContext context) {
        var scope = context.getRetrievalScope();
        var budget = context.getBudget();
        return RagTraceData.map("question", context.getMainQuestion(), "scope", scope.directed() ? "DIRECTED" : "GLOBAL",
                "targetCollections", scope.targetCollections(), "supplementCollections", scope.supplementCollections(),
                "recallBudget", budget.recallBudget(), "candidateLimit", budget.candidateLimit(), "contextTopK", budget.contextTopK());
    }

    /**
     * 构建检索上下文
     * 作用域在此处算一次挂进上下文，各通道只读不判，保证同一子问题内三条通道的检索范围一致
     */
    private SearchContext buildSearchContext(SubQuestionIntent subIntent, RetrievalBudget budget) {
        List<SubQuestionIntent> subIntents = List.of(subIntent);
        String question = subIntent.subQuestion();

        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .budget(budget)
                .retrievalScope(retrievalScopeResolver.resolve(subIntents))
                .build();
    }
}
