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
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.trace.RagTraceData;
import com.nageoffer.ai.ragent.rag.trace.RagTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 按子问题执行知识库检索，并组装回答所需的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    @Autowired
    private RagTraceRecorder traceRecorder = RagTraceRecorder.disabled();

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor;

    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents) {
        RagTraceContext.record("input", RagTraceData.map("subQuestions", RagTraceData.subIntents(subIntents)));
        if (CollUtil.isEmpty(subIntents)) {
            RagTraceContext.event("SKIPPED", "NO_SUBQUESTIONS");
            return RetrievalContext.builder()
                    .intentChunks(Map.of())
                    .build();
        }

        // 所有子问题共用同一份检索预算
        int contextTopK = searchProperties.getDefaultTopK();
        RetrievalBudget budget = new RetrievalBudget(
                searchProperties.resolveRecallBudget(contextTopK),
                searchProperties.getFusion().getRerankCandidateLimit(),
                contextTopK
        );
        List<CompletableFuture<SubQuestionContext>> tasks = IntStream.range(0, subIntents.size())
                .mapToObj(index -> CompletableFuture.supplyAsync(
                        () -> {
                            SubQuestionIntent intent = subIntents.get(index);
                            try (var questionScope = RagTraceContext.withSubQuestion("sq-" + (index + 1));
                                 var span = traceRecorder.start("retrieval-subquestion", "RETRIEVE");
                                 var scope = span.activate()) {
                                RagTraceContext.record("input", RagTraceData.map("question", intent.subQuestion(),
                                        "intents", RagTraceData.intents(intent.nodeScores()), "contextTopK", budget.contextTopK()));
                                try {
                                    SubQuestionContext result = buildSubQuestionContext(intent, budget);
                                    RagTraceContext.record("output", RagTraceData.map("context", result.kbContext(),
                                            "eligibleIntentIds", result.eligibleIntentIds()));
                                    return result;
                                } catch (Exception e) {
                                    RagTraceContext.event("DEGRADED", "SUBQUESTION_RETRIEVAL_ERROR");
                                    span.error(e);
                                    log.error("子问题上下文构建失败，降级为空上下文，question：{}", intent.subQuestion(), e);
                                    return new SubQuestionContext(
                                            intent.subQuestion(), "", Map.of(),
                                            KnowledgeRetrievalResult.empty().eligibleIntentIds(
                                                    NodeScoreFilters.kb(intent.nodeScores())));
                                }
                            }
                        },
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        Map<String, List<RetrievedChunk>> mergedIntentChunks = new LinkedHashMap<>();
        Set<String> eligibleIntentIds = new LinkedHashSet<>();
        for (SubQuestionContext context : contexts) {
            eligibleIntentIds.addAll(context.eligibleIntentIds());
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                context.intentChunks().forEach((intentId, chunks) -> {
                    if (CollUtil.isNotEmpty(chunks)) {
                        mergedIntentChunks
                                .computeIfAbsent(intentId, ignored -> new ArrayList<>())
                                .addAll(chunks);
                    }
                });
            }
        }

        String kbContext;
        if (contexts.size() == 1) {
            kbContext = StrUtil.emptyIfNull(contexts.get(0).kbContext()).trim();
        } else {
            StringBuilder kbBuilder = new StringBuilder();
            int globalIndex = 0;
            for (SubQuestionContext context : contexts) {
                if (StrUtil.isNotBlank(context.kbContext())) {
                    appendSection(kbBuilder, ++globalIndex, context.question(), context.kbContext());
                }
            }
            kbContext = kbBuilder.toString().trim();
        }

        RagTraceContext.record("output", RagTraceData.map("context", kbContext,
                "eligibleIntentIds", eligibleIntentIds, "intentGroups", mergedIntentChunks.keySet()));
        return RetrievalContext.builder()
                .kbContext(kbContext)
                .intentChunks(mergedIntentChunks)
                .eligibleIntentIds(Set.copyOf(eligibleIntentIds))
                .build();
    }

    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, RetrievalBudget budget) {
        List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores());
        KbResult kbResult = retrieveAndRerank(intent, kbIntents, budget);
        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(),
                kbResult.intentChunks(), kbResult.eligibleIntentIds());
    }

    private void appendSection(StringBuilder builder, int index, String question, String context) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, "sub-question-kb-wrapper", Map.of(
                "index", String.valueOf(index),
                "question", question,
                "context", context
        )));
    }

    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, RetrievalBudget budget) {
        KnowledgeRetrievalResult retrievalResult = multiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, budget);
        List<RetrievedChunk> chunks = retrievalResult.chunks();
        Set<String> eligibleIntentIds = retrievalResult.eligibleIntentIds(kbIntents);
        if (CollUtil.isEmpty(chunks)) {
            return new KbResult("", Map.of(), eligibleIntentIds);
        }

        Map<String, List<RetrievedChunk>> intentChunks = retrievalResult.groupByIntent(MULTI_CHANNEL_KEY);
        String groupedContext = contextFormatter.formatKbContext(
                kbIntents, eligibleIntentIds, chunks, budget.contextTopK());
        return new KbResult(groupedContext, intentChunks, eligibleIntentIds);
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      Map<String, List<RetrievedChunk>> intentChunks,
                                      Set<String> eligibleIntentIds) {
    }
}
