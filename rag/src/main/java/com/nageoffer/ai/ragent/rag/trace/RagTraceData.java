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

package com.nageoffer.ai.ragent.rag.trace;

import cn.hutool.crypto.digest.DigestUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scalar projections only: later score/metadata mutations cannot change a previous stage. */
public final class RagTraceData {
    private RagTraceData() { }
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }
    public static List<Map<String, Object>> intents(List<NodeScore> scores) {
        if (scores == null || RagTraceContext.captureLimit() == 0) return List.of();
        return scores.stream().map(score -> {
            var node = score.getNode();
            return map("intentId", node == null ? null : node.getId(), "name", node == null ? null : node.getName(),
                    "kind", node == null ? null : String.valueOf(node.getKind()), "score", score.getScore(),
                    "collections", node == null ? List.of() : List.copyOf(node.getEffectiveCollectionNames()));
        }).toList();
    }
    public static List<Map<String, Object>> subIntents(List<SubQuestionIntent> intents) {
        if (intents == null || RagTraceContext.captureLimit() == 0) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < intents.size(); i++) {
            var item = intents.get(i);
            result.add(map("subQuestionId", "sq-" + (i + 1), "question", item.subQuestion(),
                    "intents", intents(item.nodeScores())));
        }
        return result;
    }
    public static List<String> evidenceKeys(List<RetrievedChunk> chunks) {
        return chunks.stream().limit(RagTraceContext.captureLimit()).map(RagTraceData::evidenceKey).toList();
    }
    public static Map<String, Object> branch(List<String> collections, int budget, List<RetrievedChunk> chunks) {
        Map<String, Object> result = map("collections", collections, "budget", budget,
                "action", collections.isEmpty() ? "NO_COLLECTIONS" : budget <= 0 ? "NO_QUOTA" : "SEARCH");
        if (chunks != null) {
            List<String> keys = evidenceKeys(chunks);
            result.put("resultCount", chunks.size());
            result.put("evidenceKeys", keys);
            result.put("truncated", keys.size() < chunks.size());
        }
        return result;
    }
    private static String evidenceKey(RetrievedChunk chunk) {
        return DigestUtil.sha256Hex(String.valueOf(chunk.getCollectionName()) + ":" + RetrievedChunkKey.of(chunk));
    }
    public static Map<String, Object> chunks(List<RetrievedChunk> chunks, String scoreType, List<SearchChannelResult> channels) {
        if (RagTraceContext.captureLimit() == 0) return map("totalCount", chunks.size(), "recordedCount", 0,
                "truncated", !chunks.isEmpty(), "candidates", List.of());
        Map<String, Set<String>> attribution = new LinkedHashMap<>();
        Map<RetrievedChunk, String> rawScoreTypes = new IdentityHashMap<>();
        if (channels != null) for (var channel : channels) for (var chunk : channel.getChunks()) {
            attribution.computeIfAbsent(RetrievedChunkKey.of(chunk), k -> new LinkedHashSet<>())
                    .add(String.valueOf(channel.getChannelType()));
            rawScoreTypes.putIfAbsent(chunk, scoreType(String.valueOf(channel.getChannelType())));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < chunks.size() && i < RagTraceContext.captureLimit(); i++) {
            var chunk = chunks.get(i);
            String key = RetrievedChunkKey.of(chunk);
            String effectiveScoreType = "CHANNEL_SCORE".equals(scoreType)
                    ? rawScoreTypes.getOrDefault(chunk, scoreType) : scoreType;
            items.add(map("evidenceKey", evidenceKey(chunk),
                    "dedupKey", key, "sourceId", chunk.getId(), "collectionName", chunk.getCollectionName(),
                    "docId", chunk.getDocId(), "docName", chunk.getDocName(), "chunkIndex", chunk.getChunkIndex(),
                    "rank", i + 1, "score", chunk.getScore(), "scoreType", chunk.getRerankScore() == null ? effectiveScoreType : "RERANK",
                    "rerankScore", chunk.getRerankScore(), "channels", new ArrayList<>(attribution.getOrDefault(key, Set.of())),
                    "textPreview", chunk.getText(), "textHash", DigestUtil.sha256Hex(chunk.getText() == null ? "" : chunk.getText())));
        }
        return map("totalCount", chunks.size(), "recordedCount", items.size(),
                "truncated", items.size() < chunks.size(), "candidates", items);
    }
    public static String scoreType(String channel) {
        return switch (channel) {
            case "VECTOR" -> "COSINE";
            case "KEYWORD" -> "BM25";
            case "GRAPH" -> "GRAPH_RANK";
            default -> "CHANNEL_SCORE";
        };
    }
}
